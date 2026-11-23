// Tamper, online/offline health, event noise, PGM outputs, temperatures and the debug dump.

k.section("Tamper is not an open zone") {
    def d = k.authed(zoneCount: 27)
    k.feed(d, "%01," + ("0" * 32) + "\$")   // create the zone devices
    def z5 = d.parent.children["uno-zone-5"]
    d.locationEvents.clear()

    k.feed(d, "%03,1137010050\$")
    k.check("tamper (137) sets the tamper attribute", z5.last("tamper") == "detected", z5.events)
    k.check("and does not change the contact", z5.last("contact") == "closed")
    k.check("no zone event for the open/close path", !d.locationEvents.any { it.name == "unoZoneEvent" }, d.locationEvents)
    k.check("it is still described and recorded", d.lastAttr("lastEvent") == "Tamper: zone 5", d.lastAttr("lastEvent"))
    k.feed(d, "%03,3137010050\$")
    k.check("the restoral clears it", z5.last("tamper") == "clear")

    k.feed(d, "%03,1144010050\$")
    k.check("sensor tamper (144) is tamper too", z5.last("tamper") == "detected" && z5.last("contact") == "closed")
    k.feed(d, "%03,3144010050\$")
    k.feed(d, "%03,1383010050\$")
    k.check("sensor tamper trouble (383) is tamper too", z5.last("tamper") == "detected")

    k.feed(d, "%03,1137010999\$")
    k.check("a tamper for a zone outside the configured set is ignored", d.parent.children["uno-zone-999"] == null)

    def n = k.authed(zoneCount: 27)
    k.feed(n, "%03,1137010050\$")
    k.check("a tamper never creates a zone device", n.parent.children.isEmpty() && n.parent.upserts.isEmpty())

    def b = k.authed(zoneCount: 27)
    k.feed(b, "%03,1130010050\$")
    k.check("a real burglary alarm still opens the zone", b.parent.children["uno-zone-5"]?.last("contact") == "open")
}

k.section("Online / offline health") {
    def d = k.authed()
    k.check("logging in marks everything online", d.lastAttr("healthStatus") == "online" && d.parent.callArgs.contains(["notifyHealth", ["online"]]), d.parent.callArgs)

    d.socketStatus("status: closed")
    k.check("a dropped connection isn't declared offline straight away", d.lastAttr("healthStatus") == "online" && d.pending["offlineCheck"] == 30, d.pending)
    long due = d.state.offlineCheckDueAt
    d.reconnect()
    k.check("a reconnect doesn't push the deadline out", d.state.offlineCheckDueAt == due)

    d.offlineCheck()
    k.check("30 seconds later it is offline, and every child is told", d.lastAttr("healthStatus") == "offline" && d.parent.callArgs.last() == ["notifyHealth", ["offline"]], d.parent.callArgs)

    d.parse(k.hex("Login:")); d.parse(k.hex("OK\r\n"))
    k.check("logging in again brings it back online", d.lastAttr("healthStatus") == "online" && d.parent.callArgs.last() == ["notifyHealth", ["online"]])
    k.check("and cancels a pending offline check", d.state.offlineCheckDueAt == 0L && !d.pending.containsKey("offlineCheck"), d.pending)

    def q = k.authed()
    q.socketStatus("status: closed")
    q.parse(k.hex("Login:")); q.parse(k.hex("OK\r\n"))
    int calls = q.parent.callArgs.count { it[0] == "notifyHealth" }
    q.offlineCheck()
    k.check("a drop that recovers within 30 seconds never goes offline", q.lastAttr("healthStatus") == "online" && q.parent.callArgs.count { it[0] == "notifyHealth" } == calls)

    def n = k.newConn(); n.initialize()
    k.check("a new connection starts offline until it logs in", n.lastAttr("healthStatus") == "offline")
    k.check("checkInterval follows the heartbeat", n.lastAttr("checkInterval") == "120", n.lastAttr("checkInterval"))

    k.ack(d, "0C")
    k.ack(d, "0D")
    d.ping()
    k.check("ping asks the panel for a poll", d.sent.last() == "^00,\$\r\n", d.sent)
}

k.section("Diagnostic events are opt-in") {
    def d = k.authed()
    k.feed(d, "%02,0100000000000000\$")
    k.feed(d, "%05,001C2A45F894,UNO,01.00.117,1,20250923183203\$")
    k.check("no heartbeat, raw message or panel time events by default",
            d.lastAttr("lastHeartbeat") == null && d.lastAttr("lastRawMessage") == null && d.lastAttr("panelTime") == null, d.currentVals)
    k.check("the useful panel details are still published", d.lastAttr("panelModel") == "UNO" && d.lastAttr("firmwareVersion") == "01.00.117")
    k.check("the watchdog still sees every frame", d.state.lastFrameTs > 0)

    def o = k.authed(diagnosticEvents: true)
    k.feed(o, "%02,0100000000000000\$")
    k.feed(o, "%05,001C2A45F894,UNO,01.00.117,1,20250923183203\$")
    k.check("turned on, they are published (the raw message is the latest frame)", o.lastAttr("lastHeartbeat") != null && o.lastAttr("lastRawMessage") == "%05,001C2A45F894,UNO,01.00.117,1,20250923183203" && o.lastAttr("panelTime") == "20250923183203", o.currentVals)
}

k.section("Test reports are hidden unless asked for") {
    def d = k.authed()
    k.feed(d, "%03,1602010000\$")
    k.check("a periodic test is not recorded or published", d.lastAttr("lastEvent") == null && d.historyList().isEmpty() && d.locationEvents.isEmpty() && d.parent.callArgs.every { it[0] != "notifyEvent" }, d.currentVals)
    k.check("but the raw summary still shows it", d.lastAttr("lastCidEvent") == "CID q=1 code=602 partition=1 zoneOrUser=0")

    def o = k.authed(recordTestEvents: true)
    k.feed(o, "%03,1602010000\$")
    k.check("turned on, it is recorded", o.lastAttr("lastEvent") == "Periodic test" && o.lastAttr("lastEventType") == "test" && o.historyList().size() == 1, o.currentVals)
}

k.section("PGM outputs (command 0A)") {
    def d = k.authed()
    d.triggerPgm(1)
    k.check("PGM 1 => ^0A,01\$ (two digits)", d.sent == ["^0A,01\$\r\n"], d.sent)
    k.ack(d, "0A")
    d.triggerPgm(14)
    k.check("PGM 14 => ^0A,14\$", d.sent.last() == "^0A,14\$\r\n", d.sent)
    k.ack(d, "0A")
    k.check("the PGM device is told when the panel accepted the trigger", d.parent.callArgs.findAll { it[0] == "notifyPgmEvents" }*.get(1)*.get(0) == [1, 14], d.parent.callArgs)
    k.check("with a time", d.parent.callArgs.find { it[0] == "notifyPgmEvents" }[1][1][0].name == "lastTriggered")

    int sent = d.sent.size()
    d.triggerPgm(0); d.triggerPgm(15); d.triggerPgm("x"); d.triggerPgm(null)
    k.check("PGM numbers outside 1-14 are rejected", d.sent.size() == sent && d.warns().count { it.contains("PGM must be 1 to 14") } == 4, d.warns())

    def r = k.authed()
    r.triggerPgm(3)
    r.nowMs += 4000; r.commandTimeoutCheck()
    k.check("a PGM with no reply is never re-sent (a repeat would undo a toggle)", r.sent.size() == 1 && r.socket.connects.size() == 2, r.sent)

    def e = k.authed()
    e.triggerPgm(2)
    k.ack(e, "0A", "8")
    k.check("an out-of-range PGM on this panel is reported", e.warns().any { it.contains("out of range") } && e.lastAttr("lastAck") == "error:pgm2:8", e.warns())
    k.check("and the device isn't told it was triggered", e.parent.callArgs.every { it[0] != "notifyPgmEvents" })

    def b = k.authed()
    b.triggerPgm(5)
    k.ack(b, "0A", "1"); b.retryInFlight()
    k.check("a busy panel (code 1, not run) is retried", b.sent == ["^0A,05\$\r\n", "^0A,05\$\r\n"], b.sent)
}

k.section("PGM device") {
    HubBase p = k.load("drivers/UNOPGMDriver.groovy")
    MockApp app = new MockApp(); p.parent = app
    p.device.deviceNetworkId = "uno-pgm-7"
    p.push(); p.toggle()
    k.check("push and toggle both trigger this PGM on the app", app.callArgs == [["triggerPgm", [7]], ["triggerPgm", [7]]], app.callArgs)
    p.ping()
    k.check("ping refreshes", app.calls.last() == "refreshAll")

    HubBase u = k.load("drivers/UNOPGMDriver.groovy")
    u.parent = new MockApp()
    u.push()
    k.check("a device without a uno-pgm-N id refuses and says why", u.warns().any { it.contains("uno-pgm-N") } && u.parent.callArgs.isEmpty(), u.warns())
}

k.section("Zone temperatures (command 07, spec 3.3)") {
    def d = k.authed(zoneCount: 27)
    k.feed(d, "%01," + ("0" * 32) + "\$")
    def z6 = d.parent.children["uno-zone-6"]
    def z9 = d.parent.children["uno-zone-9"]
    // The spec's example: zone 6 is -14 C and zone 9 is 21 C; everything else is 00 (unknown)
    k.feed(d, "%07,00000000003400007A00" + ("0" * 236) + "\$")
    k.check("zone 6 is -14 degrees", z6.last("temperature") == -14.0G, z6.events)
    k.check("zone 9 is 21 degrees", z9.last("temperature") == 21.0G, z9.events)
    k.check("the unit is sent", z6.events.find { it.name == "temperature" }.unit == "°C")
    k.check("unknown (00) zones get nothing", d.parent.children["uno-zone-1"].last("temperature") == null)

    int before = z6.events.count { it.name == "temperature" }
    k.feed(d, "%07,00000000003400007A00" + ("0" * 236) + "\$")
    k.check("an unchanged reading isn't re-sent", z6.events.count { it.name == "temperature" } == before)
    k.feed(d, "%07,00000000003500007A00" + ("0" * 236) + "\$")
    k.check("a changed reading is: 0x35 = -13.5", z6.last("temperature") == -13.5G, z6.events)

    d.location.temperatureScale = "F"
    k.feed(d, "%07,00000000003600007A00" + ("0" * 236) + "\$")
    k.check("a hub set to Fahrenheit gets Fahrenheit (-13 C = 8.6 F)", z6.last("temperature") == 8.6G && z6.events.findAll { it.name == "temperature" }.last().unit == "°F", z6.events)

    k.feed(d, "%07,ZZ\$")
    k.check("a malformed frame is ignored", d.warns().any { it.contains("malformed zone temperature") })

    def s = k.authed(zoneCount: 27)
    k.feed(s, "%07,00000000003400007A00" + ("0" * 236) + "\$")
    k.check("temperatures never create zone devices", s.parent.children.isEmpty() && s.parent.upserts.isEmpty())

    def z = k.authed(zoneSet: "9")
    k.feed(z, "%01," + ("0" * 32) + "\$")
    k.feed(z, "%07,00000000003400007A00" + ("0" * 236) + "\$")
    k.check("only configured zones are updated", z.parent.children.keySet() == ["uno-zone-9"] as Set && z.parent.children["uno-zone-9"].last("temperature") == 21.0G, z.parent.children.keySet())
}

k.section("Partition temperatures (command 08, spec 3.3)") {
    def d = k.authed()
    // The spec's example: partition 1 inside 21 C, outside -14 C
    k.feed(d, "%08,7A000000000000003400000000000000\$")
    k.check("partition 1 inside 21 and outside -14", d.parent.partitionAttr(1, "temperature") == 21.0G && d.parent.partitionAttr(1, "outsideTemperature") == -14.0G, d.parent.partitionEvents)
    k.check("other partitions have nothing", d.parent.partitionEvents.keySet() == [1] as Set, d.parent.partitionEvents)
    int before = d.parent.partitionEvents[1].size()
    k.feed(d, "%08,7A000000000000003400000000000000\$")
    k.check("an unchanged frame forwards nothing", d.parent.partitionEvents[1].size() == before)
    k.feed(d, "%08,00007C00000000000000003600000000\$")
    k.check("partitions 3 and 4: inside from the first 8 bytes, outside from the next 8", d.parent.partitionAttr(2, "temperature") == null && d.parent.partitionAttr(3, "temperature") == 22.0G && d.parent.partitionAttr(3, "outsideTemperature") == null && d.parent.partitionAttr(4, "temperature") == null && d.parent.partitionAttr(4, "outsideTemperature") == -13.0G, d.parent.partitionEvents)
    k.feed(d, "%08,Q\$")
    k.check("a malformed frame is ignored", d.warns().any { it.contains("malformed partition temperature") })
}

k.section("Debug dump") {
    def d = k.authed(password: "s3cretpw", masterCode: "9876", zoneSet: "1-4", enablePanic: true)
    d.disarm("1234"); d.armStay()
    k.feed(d, "%02,0100000000000000\$")
    k.feed(d, "%03,3401010020\$")
    d.logs.clear()
    d.debugDump()
    String dump = d.logs.findAll { it.lvl == "info" }*.msg.join("\n")
    k.check("it logs a readable snapshot", dump.contains("UNO Connection 1.3.0") && dump.contains("connection: authenticated") && dump.contains("zones: 4 (from zone set)") && dump.contains("panic true"), dump)
    k.check("it shows the queue by name", dump.contains("in flight disarm") && dump.contains("1 queued (armStay)"), dump)
    k.check("and the recent events", dump.contains("Armed by user 2"), dump)
    k.check("it says whether secrets are set without showing them", dump.contains("password set") && dump.contains("master code set"), dump)
    k.check("no password, master code or disarm code appears", !dump.contains("s3cretpw") && !dump.contains("9876") && !dump.contains("1234"), dump)
    k.check("it counts the codes held in memory", dump.contains("1 code(s) held in memory"), dump)
}
