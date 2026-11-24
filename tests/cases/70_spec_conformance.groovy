// Conformance with the EnvisaLink UNO TPI Programmer's Document v1.08: the spec's own examples, the exact
// bytes of every command we send, and the response codes of section 3.7.

k.section("Spec examples: zone state change (3.3 command 01)") {
    def d = k.authed(zoneCount: 128)
    k.feed(d, "%01,FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF\$")
    k.check("all zones open/faulted", (1..128).every { d.parent.children["uno-zone-${it}"]?.last("contact") == "open" })
    k.feed(d, "%01,00000000000000000000000000000000\$")
    k.check("no zones open/faulted", (1..128).every { d.parent.children["uno-zone-${it}"]?.last("contact") == "closed" })
    k.feed(d, "%01,01000000000000800000000000000000\$")
    List open = (1..128).findAll { d.parent.children["uno-zone-${it}"]?.last("contact") == "open" }
    k.check("zone 1 and 64 open/faulted", open == [1, 64], open)
}

k.section("Spec examples: partition state change (3.3 command 02)") {
    def a = k.authed()
    k.feed(a, "%02,0100000000000000\$")
    k.check("partition 1 READY, no other partitions", a.parent.partitionStates.find { it[0] == 1 } == [1, "disarmed"] && a.parent.partitionStates.findAll { it[0] != 1 }.every { it[1] == "unused" }, a.parent.partitionStates)
    def b = k.authed()
    k.feed(b, "%02,0100010000000000\$")
    k.check("partition 1 and 3 READY, no other partitions", b.parent.partitionStates.findAll { it[1] == "disarmed" }*.get(0) == [1, 3] && b.parent.partitionStates.find { it[0] == 2 }[1] == "unused", b.parent.partitionStates)
}

k.section("Spec examples: CID event, chime and other frames") {
    def d = k.authed()
    k.feed(d, "%03,3441010020\$")
    k.check("CID 3441010020: restoral, code 441, partition 1, user 2", d.lastAttr("lastCidEvent") == "CID q=3 code=441 partition=1 zoneOrUser=2", d.lastAttr("lastCidEvent"))
    k.check("a user-number CID never becomes a zone", d.parent.zoneChildren().isEmpty())

    k.feed(d, "%10,01000005\$")
    k.check("%10,01000005: partition 2 chime type 1 and partition 8 type 5", d.parent.partitionAttr(2, "lastChime") == "type 1" && d.parent.partitionAttr(8, "lastChime") == "type 5")

    int warnsBefore = d.warns().size()
    k.feed(d, "%09,1,B,06\$")
    k.feed(d, "%09,8,Z,03\$")
    k.feed(d, "%0A,1,1,0,0\$")
    k.feed(d, "%0A,1,0,0,0\$")
    k.feed(d, "%0A,8,0,3,2\$")
    k.feed(d, "%07," + ("0" * 10) + "3400007A00" + ("0" * 236) + "\$")
    k.feed(d, "%08,7A000000000000003400000000000000\$")
    k.feed(d, "%FF," + ("FFFF" * 128) + "\$")
    k.check("keypad sound, temperature and zone timer frames are tolerated without errors", d.warns().size() == warnsBefore, d.warns())
    k.feed(d, "%01,01" + ("0" * 30) + "\$")
    k.check("unknown or unsupported frames don't disturb later processing", d.parent.children["uno-zone-1"]?.last("contact") == "open")
}

k.section("Framing (3.1)") {
    def d = k.authed()
    k.feed(d, "%02,0400000000000000\$")
    k.feed(d, "%02,0500000000000000\$\r\n")
    k.feed(d, "\r\n\r\n%02,0100000000000000\$\n")
    k.check("CR and/or LF after the \$ are disregarded", d.parent.partitionStates.findAll { it[0] == 1 }*.get(1) == ["armedStay", "armedAway", "disarmed"], d.parent.partitionStates)
    k.check("a command always has the comma, even with no data", ["^00,\$", "^0C,\$"].every { it ==~ /\^[0-9A-F]{2},.*\$/ })
}

k.section("Exact command bytes (3.2)") {
    // [description, how to trigger it, command code to acknowledge, expected bytes]
    List table = [
        ["Poll (00)",                        { d -> d.poll() },                              "00", "^00,\$"],
        ["Keypress to a partition (03)",     { d -> d.sendKeys("*", 2) },                    "03", "^03,2,*\$"],
        ["Bypass a zone (04), 3 digits",     { d -> d.bypassZone(7) },                       "04", "^04,007\$"],
        ["Un-bypass a zone (05), 3 digits",  { d -> d.unbypassZone(128) },                   "05", "^05,128\$"],
        ["Bypass all open zones (06)",       { d -> d.bypassOpenZones(2) },                  "06", "^06,2\$"],
        ["Un-bypass all zones (07)",         { d -> d.unbypassAllZones(2) },                 "07", "^07,2\$"],
        ["Stay-arm (08)",                    { d -> d.armStay(1) },                          "08", "^08,1\$"],
        ["Away-arm (09)",                    { d -> d.armAway(8) },                          "09", "^09,8\$"],
        ["Initial state dump (0C)",          { d -> d.refreshAll() },                        "0C", "^0C,\$"],
        ["Chime on (10)",                    { d -> d.chimeOn(1) },                          "10", "^10,1,1\$"],
        ["Chime off (10)",                   { d -> d.chimeOff(1) },                         "10", "^10,1,0\$"],
        ["Panic, spec example: medical on partition 5 (11)", { d -> d.panic("medical", 5) }, "11", "^11,5,1\$"],
        ["Panic fire (11)",                  { d -> d.panic("fire", 1) },                    "11", "^11,1,0\$"],
        ["Panic police (11)",                { d -> d.panic("police", 1) },                  "11", "^11,1,2\$"],
        ["Disarm with a 4 digit code (12)",  { d -> d.disarm("1234", 1) },                   "12", "^12,1,1234\$"],
        ["Disarm with a 6 digit code (12)",  { d -> d.disarm("123456", 4) },                 "12", "^12,4,123456\$"],
    ]

    table.each { List row ->
        def d = k.authed(enablePanic: true)
        row[1].call(d)
        k.check("${row[0]} => ${row[3]} + CRLF", d.sent == [row[3] + "\r\n"], d.sent)
        k.ack(d, row[2])
        k.check("${row[0]}: the matching ack frees the queue", d.state.commandInFlight == null)
    }
}

k.section("Login rules (2.1)") {
    def d = k.newConn(); d.initialize(); d.parse(k.hex("Login:"))
    k.check("the password is sent once, followed by a CR, as a single message", d.sent == ["pw\r"], d.sent)

    def a = k.authed(password: "123456")
    a.login()
    k.check("login() after login is refused, so the password can't be typed into the keypad as keystrokes", a.sent.isEmpty() && a.warns().any { it.contains("Already logged in") }, a.sent)
}

k.section("Response codes (3.7)") {
    Map expectations = ["2": "unknown command", "3": "syntax error",
                        "6": "cannot be completed", "7": "invalid user code", "8": "out of range"]
    expectations.each { code, text ->
        def d = k.authed()
        d.armStay()
        k.ack(d, "08", code)
        k.check("code ${code} is reported as '${text}'", d.warns().any { it.contains(text) } && d.lastAttr("lastAck") == "error:armStay:${code}", d.warns())
        k.check("code ${code} doesn't stall the queue", d.state.commandInFlight == null)
    }

    // Code 1: the panel was busy and did not run the command, so it is retried (1s, 2s, 4s) before giving up.
    def busy = k.authed()
    busy.armStay()
    3.times { k.ack(busy, "08", "1"); busy.retryInFlight() }
    k.ack(busy, "08", "1")
    k.check("code 1 is retried three times, then reported as 'receive buffer overrun'", busy.sent.size() == 4 && busy.warns().any { it.contains("receive buffer overrun") } && busy.lastAttr("lastAck") == "error:armStay:1", [busy.sent.size(), busy.warns()])
    k.check("code 1 doesn't stall the queue once retries are used up", busy.state.commandInFlight == null)

    ["4", "5"].each { code ->
        def d = k.authed()
        d.armStay()
        k.ack(d, "08", code)
        k.check("code ${code} (not used by the panel) is still treated as a failure and frees the queue", d.lastAttr("lastAck") == "error:armStay:${code}" && d.state.commandInFlight == null)
    }

    def ok = k.authed()
    ok.armStay(); k.ack(ok, "08", "0")
    k.check("code 0 is success and logs no warning", ok.warns().isEmpty())
}

k.section("Partition status codes (3.4)") {
    Map spec = ["00": "unused", "01": "disarmed", "02": "readyBypassed", "03": "notReady", "04": "armedStay", "05": "armedAway",
                "08": "exitDelay", "09": "armedAwayZeroEntry", "0C": "entryDelay", "11": "alarm"]
    spec.each { code, name ->
        def d = k.authed()
        k.feed(d, "%02,${code}00000000000000\$")
        k.check("status ${code} => ${name}", d.parent.partitionStates.find { it[0] == 1 }[1] == name)
    }
    ["06", "07", "0A", "10", "FF"].each { code ->
        def d = k.authed()
        k.feed(d, "%02,${code}00000000000000\$")
        k.check("status ${code} (not used / transitory) is treated as busy", d.parent.partitionStates.find { it[0] == 1 }[1] == "busy")
    }
}
