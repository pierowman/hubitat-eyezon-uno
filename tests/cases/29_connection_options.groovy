// Connection driver options: keepalive interval, command timeout and zones per partition.

def bitmap = { List<Integer> zones ->
    byte[] b = new byte[16]
    zones.each { int z -> b[(z - 1).intdiv(8)] |= (1 << ((z - 1) % 8)) }
    b.collect { String.format("%02X", it & 0xFF) }.join()
}

k.section("Keepalive interval") {
    def d = k.authed(keepaliveSeconds: "60")
    d.minuteCheck()
    k.check("one minute polls every minute", d.sent.contains("^00,\$\r\n"), d.sent)

    def none = k.authed()
    none.minuteCheck()
    k.check("with no setting (and no older heartbeat setting) it is one minute", none.keepaliveSeconds() == 60 && none.sent.contains("^00,\$\r\n"))

    def s = k.authed(keepaliveSeconds: "30")
    s.minuteCheck()
    k.check("30 seconds polls now", s.sent.count { it == "^00,\$\r\n" } == 1, s.sent)
    k.check("and again half a minute later", s.pending["heartbeatCheck"] == 30, s.pending)
    k.ack(s, "00")
    s.heartbeatCheck()
    k.check("the second poll goes out", s.sent.count { it == "^00,\$\r\n" } == 2, s.sent)

    def f = k.authed(keepaliveSeconds: "300")
    4.times { f.minuteCheck() }
    k.check("5 minutes sends nothing for four minutes", !f.sent.any { it.startsWith("^00") }, f.sent)
    f.minuteCheck()
    k.check("then polls", f.sent.contains("^00,\$\r\n"))

    def both = k.authed(keepaliveSeconds: "300", heartbeatMinutes: "1")
    k.check("the new setting wins over the older heartbeat setting", both.keepaliveSeconds() == 300)
    def old = k.authed(heartbeatMinutes: "10")
    k.check("an older install keeps its heartbeat", old.keepaliveSeconds() == 600)
    def tiny = k.authed(keepaliveSeconds: "5")
    k.check("it never goes below 30 seconds", tiny.keepaliveSeconds() == 30)
    def junk = k.authed(keepaliveSeconds: "soon")
    k.check("nonsense falls back to a minute", junk.keepaliveSeconds() == 60)

    k.check("the health check interval is twice the keepalive", k.authed(keepaliveSeconds: "60").lastAttr("checkInterval").toString() == "120" && k.authed(keepaliveSeconds: "300").lastAttr("checkInterval").toString() == "600")
}

k.section("The watchdog allows a minute past the keepalive") {
    def a = k.authed(keepaliveSeconds: "30", watchdogMinutes: "3")
    a.nowMs += 179000L; a.watchdogCheck()
    k.check("30 second keepalive: the watchdog's own 3 minutes apply", a.socket.connects.size() == 1)
    a.nowMs += 2000L; a.watchdogCheck()
    k.check("and it trips just after", a.socket.connects.size() == 2)

    def b = k.authed(keepaliveSeconds: "600", watchdogMinutes: "3")
    b.nowMs += 659000L; b.watchdogCheck()
    k.check("a 10 minute keepalive stretches it to 11 minutes", b.socket.connects.size() == 1)
    b.nowMs += 2000L; b.watchdogCheck()
    k.check("then it trips", b.socket.connects.size() == 2)
}

k.section("Command timeout") {
    def d = k.authed()
    d.armStay()
    k.check("4 seconds by default", d.commandTimeoutSeconds() == 4 && d.pending["commandTimeoutCheck"] == 4, d.pending)

    def t = k.authed(commandTimeoutSeconds: "10")
    t.armStay()
    k.check("the timer follows the setting", t.pending["commandTimeoutCheck"] == 10, t.pending)
    t.nowMs += 4000L; t.commandTimeoutCheck()
    k.check("nothing is re-sent after 4 seconds", t.sent.size() == 1, t.sent)
    t.nowMs += 6000L; t.commandTimeoutCheck()
    k.check("after 10 seconds it is re-sent once", t.sent.size() == 2 && t.state.commandInFlight?.retries == 1, t.sent)
    t.nowMs += 10000L; t.commandTimeoutCheck()
    k.check("and after another 10 it gives up and reconnects", t.state.commandInFlight == null && t.lastAttr("lastAck") == "timeout:armStay" && t.socket.connects.size() == 2)

    k.check("a value that is too small or too large is kept within 1 to 60 seconds", k.authed(commandTimeoutSeconds: "0").commandTimeoutSeconds() == 1 && k.authed(commandTimeoutSeconds: "999").commandTimeoutSeconds() == 60 && k.authed(commandTimeoutSeconds: "x").commandTimeoutSeconds() == 4)
}

k.section("A longer timeout lets queued commands wait longer") {
    k.check("30 seconds at the default", k.authed().queueMaxAgeSeconds() == 30)
    k.check("4 times the timeout when that is longer", k.authed(commandTimeoutSeconds: "20").queueMaxAgeSeconds() == 80 && k.authed(commandTimeoutSeconds: "10").queueMaxAgeSeconds() == 40)

    def d = k.authed()
    d.armStay(); d.armAway()
    d.nowMs += 40000L; d.expireQueue()
    k.check("a command queued 40s is dropped at the default", d.state.commandQueue.isEmpty() && d.lastAttr("lastAck") == "dropped:armAway", d.state.commandQueue)

    def l = k.authed(commandTimeoutSeconds: "20")
    l.armStay(); l.armAway()
    l.nowMs += 40000L; l.expireQueue()
    k.check("but kept with a 20 second timeout", l.state.commandQueue.size() == 1)
    l.nowMs += 45000L; l.expireQueue()
    k.check("until it is too old", l.state.commandQueue.isEmpty())
}

k.section("Zones per partition") {
    def d = k.authed(partitionZones: "1:1-3;2:4,5")
    d.parent.zoneNames[5] = "Garage Door"
    k.feed(d, "%01," + bitmap([]) + "\$")
    k.check("each partition starts with nothing open", d.parent.partitionAttr(1, "openZones") == "none" && d.parent.partitionAttr(2, "openZones") == "none" && d.parent.partitionAttr(2, "bypassedZones") == "none", d.parent.partitionEvents)

    k.feed(d, "%01," + bitmap([5]) + "\$")
    k.check("an open zone appears on its own partition only", d.parent.partitionAttr(2, "openZones") == "Garage Door (zone 5)" && d.parent.partitionAttr(1, "openZones") == "none", d.parent.partitionEvents)
    k.check("the panel-wide list still shows it", d.lastAttr("openZones") == "Garage Door (zone 5)")

    int p1 = d.parent.partitionEvents[1].count { it.name == "openZones" }
    k.feed(d, "%01," + bitmap([5, 20]) + "\$")
    k.check("a zone that isn't assigned changes only the panel-wide list", d.lastAttr("openZones") == "Garage Door (zone 5), zone 20" && d.parent.partitionAttr(2, "openZones") == "Garage Door (zone 5)" && d.parent.partitionEvents[1].count { it.name == "openZones" } == p1)

    k.feed(d, "%01," + bitmap([2, 5, 20]) + "\$")
    k.check("a zone in partition 1 reaches partition 1", d.parent.partitionAttr(1, "openZones") == "zone 2" && d.parent.partitionAttr(2, "openZones") == "Garage Door (zone 5)")

    int sent = d.parent.partitionEvents[2].count { it.name == "openZones" }
    k.feed(d, "%01," + bitmap([2, 5, 20, 21]) + "\$")
    k.check("a list that didn't change isn't sent again", d.parent.partitionEvents[2].count { it.name == "openZones" } == sent)

    k.feed(d, "%04," + bitmap([4]) + "\$")
    k.check("bypassed zones follow the same assignment", d.parent.partitionAttr(2, "bypassedZones") == "zone 4" && d.parent.partitionAttr(1, "bypassedZones") == "none")

    d.resyncStates()
    int before = d.parent.partitionEvents[1].count { it.name == "openZones" }
    k.feed(d, "%01," + bitmap([2, 5, 20, 21]) + "\$")
    k.check("after a resync every partition is told again (new devices know nothing)", d.parent.partitionEvents[1].count { it.name == "openZones" } == before + 1)
}

k.section("Assignment rules") {
    def dup = k.authed(partitionZones: "1:1-3;2:3,4")
    k.check("a zone listed twice stays with the first partition", dup.partitionZoneMap() == [1: [1, 2, 3], 2: [4]], dup.partitionZoneMap())

    def nl = k.authed(partitionZones: "1:1-2\n2:3")
    k.check("one assignment per line works too", nl.partitionZoneMap() == [1: [1, 2], 2: [3]], nl.partitionZoneMap())

    def narrow = k.authed(zoneSet: "1-3", partitionZones: "1:1-9")
    k.check("zones outside the configured set are ignored", narrow.partitionZoneMap() == [1: [1, 2, 3]], narrow.partitionZoneMap())

    def junk = k.authed(partitionZones: "x:1;9:2;3:2;4")
    k.check("bad lines and bad partitions are skipped", junk.partitionZoneMap() == [3: [2]], junk.partitionZoneMap())

    def none = k.authed()
    k.feed(none, "%01," + bitmap([5]) + "\$")
    k.check("with no assignments no partition is sent a list", none.partitionZoneMap().isEmpty() && !none.parent.partitionEvents.values().flatten().any { it.name == "openZones" })
    k.check("and the panel-wide list still works", none.lastAttr("openZones") == "zone 5")
}
