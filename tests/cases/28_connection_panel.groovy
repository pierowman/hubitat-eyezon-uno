// Frame handling shortcuts, open/bypassed/trouble summaries, zone conditions, last alarm, arm with bypass,
// zone timers, keypad sounds and the single minute schedule.

def bitmap = { List<Integer> zones ->
    byte[] b = new byte[16]
    zones.each { int z -> b[(z - 1).intdiv(8)] |= (1 << ((z - 1) % 8)) }
    b.collect { String.format("%02X", it & 0xFF) }.join()
}

k.section("Unchanged frames are skipped") {
    def d = k.authed()
    k.feed(d, "%01," + bitmap([]) + "\$")
    def z1 = d.parent.children["uno-zone-1"]
    def z20 = d.parent.children["uno-zone-20"]
    k.feed(d, "%01," + bitmap([20]) + "\$")
    int z1Events = z1.events.size()
    k.check("only the zone whose byte changed is updated", z20.last("contact") == "open" && z1.events.size() == z1Events, z1.events.size())

    int total = d.parent.children.values().sum { it.events.size() }
    int locations = d.locationEvents.size()
    d.logs.clear()
    k.feed(d, "%01," + bitmap([20]) + "\$")
    k.check("the same bitmap again does nothing", d.parent.children.values().sum { it.events.size() } == total && d.locationEvents.size() == locations)

    k.feed(d, "%01," + bitmap([]) + "\$")
    k.check("a change back is still seen", z20.last("contact") == "closed")

    k.check("each zone update is one call to the zone device", z20.applied == [false, true, false], z20.applied)
}

k.section("A zone device that rejects the update is reported") {
    def d = k.authed()
    k.feed(d, "%01," + bitmap([]) + "\$")
    def z2 = d.parent.children["uno-zone-2"]
    z2.metaClass.applyZoneState = { def a -> throw new IllegalStateException("old driver") }
    k.feed(d, "%01," + bitmap([2]) + "\$")
    k.check("it is logged and other zones carry on", d.warns().any { it.contains("Zone 2 did not accept the update") }, d.warns())
}

k.section("Open and bypassed zones") {
    def d = k.authed()
    d.parent.zoneNames[5] = "Front Door"
    k.feed(d, "%01," + bitmap([]) + "\$")
    k.check("nothing open to start", d.lastAttr("openZones") == "none" && d.lastAttr("bypassedZones") == "none", d.currentVals)

    k.feed(d, "%01," + bitmap([5, 7]) + "\$")
    k.check("open zones are listed by name", d.lastAttr("openZones") == "Front Door (zone 5), zone 7", d.lastAttr("openZones"))
    k.check("and forwarded to the app", d.parent.callArgs.any { it[0] == "notifyPanelSummary" && it[1][0].openZones == "Front Door (zone 5), zone 7" }, d.parent.callArgs.findAll { it[0] == "notifyPanelSummary" })

    int calls = d.parent.callArgs.count { it[0] == "notifyPanelSummary" }
    k.feed(d, "%01," + bitmap([5, 7]) + "\$")
    k.check("an unchanged list is not forwarded again", d.parent.callArgs.count { it[0] == "notifyPanelSummary" } == calls)

    k.feed(d, "%04," + bitmap([7]) + "\$")
    k.check("bypassed zones are listed too", d.lastAttr("bypassedZones") == "zone 7", d.lastAttr("bypassedZones"))
    k.feed(d, "%01," + bitmap([]) + "\$")
    k.check("closing everything empties the list", d.lastAttr("openZones") == "none")

    def narrow = k.authed(zoneSet: "1-3")
    k.feed(narrow, "%01," + bitmap([2, 9]) + "\$")
    k.check("zones outside the configured set are left out", narrow.lastAttr("openZones") == "zone 2", narrow.lastAttr("openZones"))
}

k.section("Trouble summary") {
    def d = k.authed()
    k.feed(d, "%06,0000000000000000\$")
    k.check("clear to start", d.lastAttr("troubleSummary") == "none", d.lastAttr("troubleSummary"))
    k.feed(d, "%06,0200000000000000\$")
    k.check("partition 1 alone is shown plainly", d.lastAttr("troubleSummary") == "AC failure", d.lastAttr("troubleSummary"))
    k.feed(d, "%06,0200010000000000\$")
    k.check("several partitions are labelled", d.lastAttr("troubleSummary") == "P1: AC failure; P3: Service required", d.lastAttr("troubleSummary"))
    k.check("and the summary reaches the app", d.parent.callArgs.contains(["notifyPanelSummary", [[troubleSummary: "P1: AC failure; P3: Service required"]]]))
}

k.section("Low battery, lost supervision and tamper are zone conditions") {
    def d = k.authed()
    k.feed(d, "%01," + bitmap([]) + "\$")
    def z5 = d.parent.children["uno-zone-5"]
    d.locationEvents.clear()

    k.feed(d, "%03,1384010050\$")
    k.check("low battery (384) sets batteryStatus", z5.last("batteryStatus") == "low" && z5.last("contact") == "closed", z5.events)
    k.feed(d, "%03,3384010050\$")
    k.check("its restoral sets it back", z5.last("batteryStatus") == "ok")

    k.feed(d, "%03,1381010050\$")
    k.check("lost supervision (381) sets supervision", z5.last("supervision") == "lost" && z5.last("contact") == "closed")
    k.feed(d, "%03,3381010050\$")
    k.check("and it clears", z5.last("supervision") == "ok")

    k.check("they are still described in words", d.lastAttr("lastEvent") == "Loss of supervision (RF) restored: zone 5", d.lastAttr("lastEvent"))
    k.check("no open/close event for any of it", !d.locationEvents.any { it.name == "unoZoneEvent" && it.value.startsWith("5:") })

    def n = k.authed()
    k.feed(n, "%03,1384010050\$")
    k.check("a condition never creates a zone device", n.parent.children.isEmpty() && n.parent.upserts.isEmpty())
}

k.section("Last alarm") {
    def d = k.authed()
    d.parent.zoneNames[5] = "Front Door"
    k.feed(d, "%03,1130010050\$")
    k.check("type, zone and time are forwarded to the partition", d.parent.partitionAttr(1, "lastAlarmType") == "Burglary alarm" && d.parent.partitionAttr(1, "lastAlarmZone") == "Front Door (zone 5)" && d.parent.partitionAttr(1, "lastAlarmAt") != null, d.parent.partitionEvents)
    String at = d.parent.partitionAttr(1, "lastAlarmAt")

    k.feed(d, "%03,3130010050\$")
    k.feed(d, "%03,1401010020\$")
    k.check("a restoral or an arm event is not an alarm", d.parent.partitionEvents[1].count { it.name == "lastAlarmType" } == 1)

    k.feed(d, "%03,1120010000\$")
    k.check("an alarm without a zone says so", d.parent.partitionAttr(1, "lastAlarmType") == "Panic alarm" && d.parent.partitionAttr(1, "lastAlarmZone") == "none")
}

k.section("Arm with bypass") {
    def d = k.authed()
    d.armStayBypass(1)
    k.check("open zones are bypassed first", d.sent == ["^06,1\$\r\n"], d.sent)
    k.ack(d, "06")
    k.check("then the partition is armed", d.sent == ["^06,1\$\r\n", "^08,1\$\r\n"], d.sent)

    def a = k.authed()
    a.armAwayBypass(2)
    k.ack(a, "06")
    k.check("away works the same on another partition", a.sent == ["^06,2\$\r\n", "^09,2\$\r\n"], a.sent)

    def r = k.authed(requireCodeToArm: true, masterCode: "1234")
    r.armStayBypass(1)
    k.check("a required code is checked before anything is bypassed", r.sent.isEmpty() && r.lastAttr("lastAck") == "rejected:armStayBypass:code", [r.sent, r.lastAttr("lastAck")])
    r.armStayBypass(1, "1234")
    k.check("with the code both steps are queued", r.sent == ["^06,1\$\r\n"] && r.state.commandQueue*.name == ["armStay"], [r.sent, r.state.commandQueue])

    def bad = k.authed()
    bad.armStayBypass(9)
    k.check("a bad partition queues nothing", bad.sent.isEmpty() && bad.state.commandQueue.isEmpty())
}

k.section("Zone timers (command 02, frame FF)") {
    def d = k.authed()
    d.dumpZoneTimers()
    k.check("the dump command is ^02,\$", d.sent == ["^02,\$\r\n"], d.sent)

    // Little endian words: FFFF open, FEFF = 0xFFFE (one tick, 5 s ago), 0A00 = 0x000A, 0000 forgotten.
    String words = ["FFFF", "FEFF", "0000", "0A00"].join() + ("0000" * 124)
    k.feed(d, "%FF," + words + "\$")
    Map t = new groovy.json.JsonSlurper().parseText(d.lastAttr("zoneTimers"))
    k.check("open zones are marked open", t["1"] == "open", t)
    k.check("a timer counts 5 second ticks since the zone closed", t["2"] == 5 && t["4"] == (0xFFFF - 0x0A) * 5, t)
    k.check("a forgotten zone is left out", !t.containsKey("3") && !t.containsKey("5"), t)

    def warnsBefore = d.warns().size()
    k.feed(d, "%FF,XYZ\$")
    k.check("a malformed dump is ignored with a warning", d.warns().size() == warnsBefore + 1)
}

k.section("Keypad sounds (frames 09 and 0A)") {
    def d = k.authed()
    k.feed(d, "%09,1,B,06\$")
    k.check("off by default (they change constantly)", d.lastAttr("lastKeypadSound") == null && d.parent.partitionEvents.isEmpty())

    def s = k.authed(diagnosticEvents: true)
    k.feed(s, "%09,1,B,06\$")
    k.check("beeps (the value is hex)", s.lastAttr("lastKeypadSound") == "6 beeps", s.lastAttr("lastKeypadSound"))
    k.check("and reach the partition", s.parent.partitionAttr(1, "lastKeypadSound") == "6 beeps")
    k.feed(s, "%09,8,Z,03\$")
    k.check("a continuous tone", s.lastAttr("lastKeypadSound") == "tone for 3s" && s.parent.partitionAttr(8, "lastKeypadSound") == "tone for 3s")
    k.feed(s, "%09,1,B,0A\$")
    k.check("hex values are read as hex", s.lastAttr("lastKeypadSound") == "10 beeps")
    k.feed(s, "%0A,1,1,0,0\$")
    k.check("persistent: continuous tone", s.lastAttr("lastKeypadSound") == "continuous tone")
    k.feed(s, "%0A,1,0,0,0\$")
    k.check("persistent: stop", s.lastAttr("lastKeypadSound") == "stopped")
    k.feed(s, "%0A,8,0,3,2\$")
    k.check("persistent: beeps on an interval", s.lastAttr("lastKeypadSound") == "3 beeps every 2s" && s.parent.partitionAttr(8, "lastKeypadSound") == "3 beeps every 2s")

    int warns = s.warns().size()
    int events = s.events.count { it.name == "lastKeypadSound" }
    ["%09,9,B,06\$", "%09,1,Q,06\$", "%09,1,B\$", "%0A,1,2,0,0\$", "%0A,0,0,0,0\$"].each { k.feed(s, it) }
    k.check("malformed sound frames are ignored quietly", s.warns().size() == warns && s.events.count { it.name == "lastKeypadSound" } == events)
    k.check("frame 09 is no longer a system message", s.lastAttr("lastSystemMessage") == null)
}

k.section("One schedule drives the heartbeat and the watchdog") {
    def d = k.authed(heartbeatMinutes: "5")
    d.scheduleCalls.clear()
    d.initialize(); d.sent.clear()
    k.check("a single schedule is created", d.scheduleCalls.count { it[0] == "minuteCheck" } == 1 && !d.scheduleCalls.any { it[0] == "heartbeatCheck" || it[0] == "watchdogCheck" }, d.scheduleCalls)

    def h = k.authed(heartbeatMinutes: "5")
    4.times { h.minuteCheck() }
    k.check("with a 5 minute heartbeat the first four minutes send no poll", !h.sent.any { it.startsWith("^00") }, h.sent)
    h.minuteCheck()
    k.check("the fifth does", h.sent.contains("^00,\$\r\n"), h.sent)

    def one = k.authed(heartbeatMinutes: "1")
    one.minuteCheck()
    k.check("a 1 minute heartbeat polls every minute", one.sent.contains("^00,\$\r\n"))

    def w = k.authed()
    w.state.commandInFlight = [name: "x"]
    w.nowMs += 10 * 60 * 1000L
    w.minuteCheck()
    k.check("the same tick runs the watchdog", w.socket.connects.size() == 2, w.socket.connects)

    k.check("the old handlers still work, for schedules created by an earlier version", d.metaClass.respondsTo(d, "heartbeatCheck") && d.metaClass.respondsTo(d, "watchdogCheck"))
}

k.section("Re-announcing state without reconnecting") {
    def d = k.authed()
    k.feed(d, "%01," + bitmap([3]) + "\$")
    k.feed(d, "%02,0300000000000000\$")
    def z3 = d.parent.children["uno-zone-3"]
    int before = z3.events.size()
    k.feed(d, "%01," + bitmap([3]) + "\$")
    k.check("normally a repeat says nothing", z3.events.size() == before)

    d.sent.clear(); d.state.commandInFlight = null
    int connects = d.socket.connects.size()
    d.resyncStates()
    k.check("resyncStates asks the panel for everything", d.sent == ["^0C,\$\r\n"], d.sent)
    k.feed(d, "%01," + bitmap([3]) + "\$")
    k.check("so the same bitmap is announced again, to new devices too", z3.events.size() > before)
    k.check("without reconnecting", d.socket.connects.size() == connects && d.lastAttr("connectionState") == "authenticated")
}

k.section("Hot paths keep the hub's database quiet") {
    def d = k.authed(zoneSet: "1-3,20")
    k.check("the zone list is worked out once per setting", d.configuredZones().is(d.configuredZones()))
    d.settings.zoneSet = "1-2"
    k.check("and again when the setting changes", d.configuredZones() == [1, 2])

    def f = k.authed()
    f.parse(k.hex("%01," + bitmap([1]) + "\$%0"))
    f.parse(k.hex("2,01000000"))
    f.parse(k.hex("00000000\$"))
    k.check("fragments are reassembled with the buffer kept locally", f.parent.children["uno-zone-1"].last("contact") == "open" && f.state.rxBuffer == "", f.state.rxBuffer)
    k.check("the rest of the buffer is kept for the next packet", { def p = k.authed(); p.parse(k.hex("%01,00")); p.state.rxBuffer == "%01,00" }())

    def s = k.authed()
    k.feed(s, "%03,1130010050\$")
    k.check("event history and fast-path timestamps are not kept in state", s.state.eventHistory == null && s.state.lastCidByZone == null, s.state.keySet())
}
