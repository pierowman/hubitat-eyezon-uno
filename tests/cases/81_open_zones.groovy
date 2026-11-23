// Open zones with when they opened, and the option to keep a bypassed zone in the list.

def bitmap = { List<Integer> zones ->
    byte[] b = new byte[16]
    zones.each { int z -> b[(z - 1).intdiv(8)] |= (1 << ((z - 1) % 8)) }
    b.collect { String.format("%02X", it & 0xFF) }.join()
}
def zonesFrame = { List<Integer> z -> "%01," + bitmap(z) + "\$" }
def bypassFrame = { List<Integer> z -> "%04," + bitmap(z) + "\$" }
def detail = { HubBase d -> new groovy.json.JsonSlurper().parseText(d.lastAttr("openZonesDetail")) }
def stamp = { HubBase d, long ms -> new Date(ms).format("yyyy-MM-dd HH:mm:ss", d.location.timeZone) }
def hhmm = { HubBase d, long ms -> new Date(ms).format("HH:mm", d.location.timeZone) }

k.section("Open zones, with when each opened") {
    def d = k.authed()
    d.parent.zoneNames[5] = "Front Door"
    k.feed(d, zonesFrame([]))
    k.check("nothing open to start", d.lastAttr("openZoneCount") == "0" && d.lastAttr("openZonesTimed") == "none" && d.lastAttr("openZonesDetail") == "[]", d.currentVals)

    long opened = d.nowMs
    k.feed(d, zonesFrame([5]))
    k.check("a zone opens: the count, the names and the time", d.lastAttr("openZoneCount") == "1" && d.lastAttr("openZones") == "Front Door (zone 5)" && d.lastAttr("openZonesTimed") == "Front Door (zone 5) since ${hhmm(d, opened)}", [d.lastAttr("openZoneCount"), d.lastAttr("openZones"), d.lastAttr("openZonesTimed")])
    List rows = detail(d)
    k.check("the JSON has the zone, its name and the full date and time", rows.size() == 1 && rows[0].zone == 5 && rows[0].name == "Front Door" && rows[0].since == stamp(d, opened) && !rows[0].containsKey("bypassed"), rows)

    d.nowMs += 600000L
    long later = d.nowMs
    k.feed(d, zonesFrame([5, 7]))
    List two = detail(d)
    k.check("a second zone gets its own time, and the first keeps its own", two*.zone == [5, 7] && two[0].since == stamp(d, opened) && two[1].since == stamp(d, later), two)
    k.check("the text lists them in zone order", d.lastAttr("openZonesTimed") == "Front Door (zone 5) since ${hhmm(d, opened)}; zone 7 since ${hhmm(d, later)}", d.lastAttr("openZonesTimed"))

    d.nowMs += 1000L
    k.feed(d, zonesFrame([5, 7, 9]))
    k.check("another zone changing doesn't move the first one's time", detail(d)[0].since == stamp(d, opened))

    k.feed(d, zonesFrame([7, 9]))
    k.check("a zone that closes leaves the list, the others stay", detail(d)*.zone == [7, 9] && d.lastAttr("openZoneCount") == "2")

    d.nowMs += 5000L
    long again = d.nowMs
    k.feed(d, zonesFrame([5, 7, 9]))
    k.check("and when it opens again it has a new time", detail(d)[0].since == stamp(d, again) && detail(d)[1].since == stamp(d, later), detail(d))

    k.feed(d, zonesFrame([]))
    k.check("everything closing empties it", d.lastAttr("openZoneCount") == "0" && d.lastAttr("openZones") == "none" && d.lastAttr("openZonesDetail") == "[]")
}

k.section("Yesterday's zone shows its date") {
    def d = k.authed()
    k.feed(d, zonesFrame([3]))
    d.nowMs += 26 * 3600 * 1000L
    k.feed(d, zonesFrame([3, 4]))
    String text = d.lastAttr("openZonesTimed")
    k.check("a zone open since yesterday says the date as well as the time", text.startsWith("zone 3 since ") && text.split(";")[0] ==~ /zone 3 since \d\d-\d\d \d\d:\d\d/ && text.split(";")[1].trim() ==~ /zone 4 since \d\d:\d\d/, text)
}

k.section("Zones already open when the integration starts") {
    def d = k.authed()
    long start = d.nowMs
    k.feed(d, zonesFrame([2]))
    k.check("are timed from when they were first seen", detail(d)[0].since == stamp(d, start), detail(d))
    d.initialize(); d.parse(k.hex("Login:")); d.parse(k.hex("OK\r\n"))
    d.nowMs += 60000L
    k.feed(d, zonesFrame([2]))
    k.check("and keep that time across a reconnect", detail(d)[0].since == stamp(d, start), detail(d))
}

k.section("A long list is cut, but the count is true") {
    def d = k.authed(zoneCount: 40)
    k.feed(d, zonesFrame((1..30).toList()))
    k.check("30 zones open: 25 are listed in the JSON, the count says 30", detail(d).size() == 25 && d.lastAttr("openZoneCount") == "30", [detail(d).size(), d.lastAttr("openZoneCount")])
    k.check("the text still lists them all", d.lastAttr("openZonesTimed").split(";").size() == 30)
}

k.section("Nothing is sent again when nothing changed") {
    def d = k.authed()
    k.feed(d, zonesFrame([5]))
    int sent = d.parent.callArgs.count { it[0] == "notifyPanelSummary" }
    int events = d.events.count { it.name == "openZonesTimed" }
    d.nowMs += 600000L
    k.feed(d, zonesFrame([5]))
    k.feed(d, bypassFrame([]))
    k.check("the same zones later change no attribute, whatever the time", d.events.count { it.name == "openZonesTimed" } == events && d.parent.callArgs.count { it[0] == "notifyPanelSummary" } == sent)
}

k.section("By default a bypassed zone that reads closed leaves the list") {
    def d = k.authed()
    k.feed(d, zonesFrame([5]))
    k.feed(d, bypassFrame([5]))
    k.check("a bypassed zone that still reads open is listed, and marked bypassed", detail(d)*.zone == [5] && detail(d)[0].bypassed == true && d.lastAttr("openZonesTimed").contains("(bypassed)"), detail(d))
    k.feed(d, zonesFrame([]))                               // the panel now reports the bypassed zone as closed
    k.check("the list follows the panel", d.lastAttr("openZoneCount") == "0" && d.lastAttr("bypassedZones") == "zone 5", d.currentVals)
}

k.section("Keeping a bypassed zone in the open list") {
    def d = k.authed(holdBypassedOpenZones: true)
    d.parent.zoneNames[5] = "Garage"
    long opened = d.nowMs
    k.feed(d, zonesFrame([5, 6]))
    d.nowMs += 30000L
    k.feed(d, bypassFrame([5]))                             // bypass arrives while the zone still reads open
    k.check("an open bypassed zone stays, marked as bypassed", detail(d)*.zone == [5, 6] && detail(d)[0].bypassed == true && detail(d)[1].bypassed == null, detail(d))

    d.nowMs += 30000L
    k.feed(d, zonesFrame([6]))                              // the panel reports the bypassed zone as closed
    k.check("it stays listed after the panel reports it closed", detail(d)*.zone == [5, 6] && d.lastAttr("openZoneCount") == "2", detail(d))
    k.check("with the time it really opened", detail(d)[0].since == stamp(d, opened), detail(d))
    k.check("and the text says it is bypassed", d.lastAttr("openZonesTimed").contains("Garage (zone 5) since") && d.lastAttr("openZonesTimed").contains("(bypassed)"), d.lastAttr("openZonesTimed"))
    k.check("the plain name list includes it too", d.lastAttr("openZones") == "Garage (zone 5), zone 6", d.lastAttr("openZones"))

    k.feed(d, zonesFrame([]))
    k.check("an unbypassed zone that closes still leaves", detail(d)*.zone == [5], detail(d))

    k.feed(d, bypassFrame([]))
    k.check("removing the bypass clears it when the zone reads closed", d.lastAttr("openZoneCount") == "0" && d.lastAttr("bypassedZones") == "none", d.currentVals)
}

k.section("A bypass that arrives just after the zone closed") {
    def d = k.authed(holdBypassedOpenZones: true)
    long opened = d.nowMs
    k.feed(d, zonesFrame([5]))
    d.nowMs += 20000L
    k.feed(d, zonesFrame([]))                               // some panels report the zone closed first...
    d.nowMs += 3000L
    k.feed(d, bypassFrame([5]))                             // ...and the bypass a moment later
    k.check("within 10 seconds it is restored, with its original time", detail(d)*.zone == [5] && detail(d)[0].since == stamp(d, opened) && detail(d)[0].bypassed == true, detail(d))

    def l = k.authed(holdBypassedOpenZones: true)
    k.feed(l, zonesFrame([5]))
    k.feed(l, zonesFrame([]))
    l.nowMs += 11000L
    k.feed(l, bypassFrame([5]))
    k.check("after 10 seconds it was really closed, so it is not", l.lastAttr("openZoneCount") == "0", l.currentVals)

    def n = k.authed(holdBypassedOpenZones: true)
    k.feed(n, zonesFrame([]))
    k.feed(n, bypassFrame([5]))
    k.check("a zone that was never open isn't listed just for being bypassed", n.lastAttr("openZoneCount") == "0")

    def o = k.authed()
    k.feed(o, zonesFrame([5]))
    k.feed(o, zonesFrame([]))
    k.feed(o, bypassFrame([5]))
    k.check("and with the option off none of this happens", o.lastAttr("openZoneCount") == "0")
}

k.section("Per partition lists follow the same rule") {
    def d = k.authed(partitionZones: "1:1-5;2:6-9", holdBypassedOpenZones: true)
    k.feed(d, zonesFrame([7]))
    k.feed(d, bypassFrame([7]))
    k.feed(d, zonesFrame([]))
    k.check("a held zone stays on its own partition's list", d.parent.partitionAttr(2, "openZones") == "zone 7" && d.parent.partitionAttr(1, "openZones") == "none", d.parent.partitionEvents)
}

k.section("Status device and settings") {
    HubBase s = k.load("drivers/UNOStatusDriver.groovy")
    s.parent = new MockApp()
    s.installed()
    k.check("the status device starts with empty lists", s.lastAttr("openZoneCount") == "0" && s.lastAttr("openZonesTimed") == "none" && s.lastAttr("openZonesDetail") == "[]", s.currentVals)

    def (a, env) = k.newApp([zoneCount: 2, createStatusDevice: true])
    a.initialize()
    def conn = env.children["uno-connection"]
    k.check("the option is off by default and pushed to the connection device", conn.settingsUpdated.any { it[0] == "holdBypassedOpenZones" && it[1].value == false })
    a.notifyPanelSummary([openZones: "zone 1", openZoneCount: 1, openZonesTimed: "zone 1 since 10:00", openZonesDetail: "[]"])
    def st = env.children["uno-status"]
    k.check("the app passes the new lists to the status device", st.last("openZoneCount") == 1 && st.last("openZonesTimed") == "zone 1 since 10:00", st.events)

    conn.settingsUpdated.clear(); a.pending.clear()
    a.settings.holdBypassedOpenZones = true
    a.updated()
    k.check("switching it on is pushed without a restart, and the panel announces again so the list is right", conn.settingsUpdated*.getAt(0) == ["holdBypassedOpenZones"] && a.pending.containsKey("resyncConnection") && !a.pending.containsKey("initializeConnection"), [conn.settingsUpdated, a.pending])
}
