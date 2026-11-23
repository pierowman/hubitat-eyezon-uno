// Housekeeping: values kept out of hub state, zone names cached for the open lists, and the firmware warning.

def bitmap = { List<Integer> zones ->
    byte[] b = new byte[16]
    zones.each { int z -> b[(z - 1).intdiv(8)] |= (1 << ((z - 1) % 8)) }
    b.collect { String.format("%02X", it & 0xFF) }.join()
}

k.section("The frame timestamp and temperature caches are not in hub state") {
    def d = k.authed(zoneCount: 128)
    k.feed(d, "%01," + bitmap([]) + "\$")
    k.feed(d, "%07," + ("A0" * 128) + "\$")
    k.feed(d, "%08," + ("50" * 16) + "\$")
    k.check("none of them is written to state", !d.state.containsKey("lastFrameTs") && !d.state.containsKey("zoneTemps") && !d.state.containsKey("partitionTemps"), d.state.keySet())
    k.check("the watchdog still sees every frame", d.lastFrameAt() > 0)
    int size = groovy.json.JsonOutput.toJson(d.state).length()
    k.check("so state stays small even with 128 zones reporting temperatures", size < 1500, size)

    // Upgrading: whatever an earlier version left behind is removed.
    def u = k.newConn()
    u.state.zoneTemps = [a: 1]; u.state.partitionTemps = [a: 1]; u.state.lastFrameTs = 5L
    u.initialize()
    k.check("old state entries are cleaned up on initialize", !u.state.containsKey("zoneTemps") && !u.state.containsKey("partitionTemps") && !u.state.containsKey("lastFrameTs"), u.state.keySet())
}

k.section("Temperature change detection works from memory") {
    def d = k.authed(zoneCount: 4)
    k.feed(d, "%01," + bitmap([]) + "\$")
    def z2 = d.parent.children["uno-zone-2"]
    k.feed(d, "%07,00A000000000\$")
    int first = z2.events.count { it.name == "temperature" }
    k.feed(d, "%07,00A000000000\$")
    k.check("a repeated reading isn't sent again", first == 1 && z2.events.count { it.name == "temperature" } == 1, z2.events)
    k.feed(d, "%07,00A100000000\$")
    k.check("a changed one is", z2.events.count { it.name == "temperature" } == 2)

    k.feed(d, "%08," + ("50" * 16) + "\$")
    int p = d.parent.partitionEvents[1].count { it.name == "temperature" }
    k.feed(d, "%08," + ("50" * 16) + "\$")
    k.check("the same for partitions", p == 1 && d.parent.partitionEvents[1].count { it.name == "temperature" } == 1, p)

    d.resyncStates()
    k.feed(d, "%07,00A100000000\$")
    k.check("after a resync everything is announced again", z2.events.count { it.name == "temperature" } == 3, z2.events.count { it.name == "temperature" })
}

k.section("The watchdog's timestamp") {
    def d = k.authed()
    d.nowMs += 10 * 60 * 1000L
    d.watchdogCheck()
    k.check("silence still trips the watchdog", d.socket.connects.size() == 2, d.socket.connects)

    def r = k.authed()
    r.nowMs += 10 * 60 * 1000L
    r.resyncStates()
    r.watchdogCheck()
    k.check("a resync doesn't reset it", r.socket.connects.size() == 2, r.socket.connects)

    def n = k.newConn()
    n.initialize()
    n.nowMs += 10 * 60 * 1000L
    n.state.loggedIn = true
    n.sendEvent(name: "connectionState", value: "authenticated")
    n.watchdogCheck()
    k.check("with no frame yet (or after a hub restart) it waits for one instead of tripping", n.socket.connects.size() == 1)
}

k.section("Zone names for the open lists are looked up once, not per list") {
    def d = k.authed(zoneCount: 10, partitionZones: "1:1-5;2:6-10")
    int lookups = 0
    d.parent.zoneNames[2] = "Kitchen"; d.parent.zoneNames[7] = "Garage"
    d.parent.metaClass.zoneName = { Integer z -> lookups++; delegate.zoneNames[z] }

    k.feed(d, "%01," + bitmap([2, 7]) + "\$")
    int firstLookups = lookups
    k.check("the first change looks each open zone up once, however many lists use it", firstLookups == 2, firstLookups)
    k.check("and uses the names", d.lastAttr("openZones") == "Kitchen (zone 2), Garage (zone 7)" && d.parent.partitionAttr(2, "openZones") == "Garage (zone 7)", d.lastAttr("openZones"))

    lookups = 0
    k.feed(d, "%04," + bitmap([2]) + "\$")
    k.feed(d, "%01," + bitmap([2, 7, 8]) + "\$")
    k.check("later changes within a minute make only the lookups for zones not seen yet", lookups == 1, lookups)

    d.nowMs += 30000L
    lookups = 0
    k.feed(d, "%01," + bitmap([2, 7]) + "\$")
    k.check("still cached after 30 seconds", lookups == 0, lookups)

    d.parent.zoneNames[2] = "Back Kitchen"
    d.nowMs += 31000L
    k.feed(d, "%01," + bitmap([2, 7, 9]) + "\$")
    k.check("a name is looked up again after a minute", d.lastAttr("openZones").startsWith("Back Kitchen (zone 2)"), d.lastAttr("openZones"))

    d.parent.zoneNames[2] = "Pantry"
    k.feed(d, "%03,1130010020\$")
    k.check("event text always uses the current name, not the cache", d.lastAttr("lastEvent") == "Burglary alarm: Pantry (zone 2)", d.lastAttr("lastEvent"))

    d.initialize(); d.parse(k.hex("Login:")); d.parse(k.hex("OK\r\n"))
    lookups = 0
    k.feed(d, "%01," + bitmap([2]) + "\$")
    k.check("restarting the connection clears the cache", d.lastAttr("openZones") == "Pantry (zone 2)" && lookups >= 1, [d.lastAttr("openZones"), lookups])
}

k.section("A zone with no name is listed by number, and the lookup can fail") {
    def d = k.authed()
    d.parent.metaClass.zoneName = { Integer z -> throw new IllegalStateException("app busy") }
    boolean threw = false
    try { k.feed(d, "%01," + bitmap([3]) + "\$") } catch (Throwable t) { threw = true }
    k.check("a failing lookup doesn't break the list", !threw && d.lastAttr("openZones") == "zone 3", d.lastAttr("openZones"))
}

k.section("Firmware warning") {
    def frame = { String model, String fw -> "%05,001122334455,${model},${fw},1,2026-11-23 10:00:00\$".toString() }
    def warnAttr = { HubBase d -> d.lastAttr("firmwareWarning") }

    def ok = k.authed()
    k.feed(ok, frame("UNO", "1.0.154"))
    k.check("the minimum itself is fine", warnAttr(ok) == "none" && !ok.warns().any { it.contains("older than") }, ok.warns())

    ["1.0.155", "1.1.0", "2.0.1", "01.00.154A", "v1.0.200-beta", "1.0.1540"].each { String fw ->
        def d = k.authed()
        k.feed(d, frame("UNO", fw))
        k.check("UNO ${fw} is fine", warnAttr(d) == "none", warnAttr(d))
    }

    def old = k.authed()
    k.feed(old, frame("UNO", "1.0.153"))
    k.check("one build older warns", warnAttr(old) == "Firmware 1.0.153 is older than 1.0.154, the minimum the TPI document lists for UNO. Some commands or frames may not work", warnAttr(old))
    k.check("and logs it", old.warns().any { it.contains("older than 1.0.154") }, old.warns())

    ["0.9.999", "1.0.1", "00.99.200"].each { String fw ->
        def d = k.authed()
        k.feed(d, frame("UNO", fw))
        k.check("UNO ${fw} warns", warnAttr(d) != "none", warnAttr(d))
    }

    int logged = old.warns().count { it.contains("older than") }
    k.feed(old, frame("UNO", "1.0.153"))
    k.feed(old, frame("UNO", "1.0.153"))
    k.check("it is logged once, not on every poll", old.warns().count { it.contains("older than") } == logged)

    k.feed(old, frame("UNO", "1.0.160"))
    k.check("and clears after an upgrade", warnAttr(old) == "none")

    def solo = k.authed()
    k.feed(solo, frame("SOLO", "1.0.145"))
    k.check("each product has its own minimum", warnAttr(solo).contains("1.0.146") && warnAttr(solo).contains("SOLO"), warnAttr(solo))
    def evl = k.authed()
    k.feed(evl, frame("EVL4MAX-SA", "1.0.219"))
    k.check("including the EVL4MAX in standalone mode", warnAttr(evl).contains("1.0.220"), warnAttr(evl))
    def duo = k.authed()
    k.feed(duo, frame("DUO-SA", "1.0.103"))
    k.check("and the DUO", warnAttr(duo).contains("1.0.104"), warnAttr(duo))

    ["abc", "", "1.0", "1", "x.y.z"].each { String fw ->
        def d = k.authed()
        k.feed(d, frame("UNO", fw))
        k.check("a version it can't read ('${fw}') gets no warning", warnAttr(d) == "none" || warnAttr(d) == null, warnAttr(d))
    }
    def unknown = k.authed()
    k.feed(unknown, frame("MYSTERY", "0.0.1"))
    k.check("neither does a product it doesn't know", warnAttr(unknown) == "none" || warnAttr(unknown) == null, warnAttr(unknown))

    def (a, env) = k.newApp([zoneCount: 1])
    a.initialize()
    def conn = env.children["uno-connection"]
    conn.sendEvent(name: "firmwareWarning", value: "none")
    k.check("the app's status shows nothing extra when the firmware is fine", !a.statusSummary().contains("older than"))
    conn.sendEvent(name: "firmwareWarning", value: "Firmware 1.0.120 is older than 1.0.154, the minimum the TPI document lists for UNO. Some commands or frames may not work")
    k.check("and a warning when it isn't", a.statusSummary().contains("WARNING: Firmware 1.0.120 is older than 1.0.154"), a.statusSummary())
}
