// Truncated and garbled data from the UNO must lose at most the damaged frame, never the ones after it,
// and never grow the receive buffer without limit. (Regression guard for envisalink_new v1.2.3, where
// truncated data from the panel caused frequent disconnects.)

def bitmap = { List<Integer> zones ->
    byte[] b = new byte[16]
    zones.each { int z -> b[(z - 1).intdiv(8)] |= (1 << ((z - 1) % 8)) }
    b.collect { String.format("%02X", it & 0xFF) }.join()
}

k.section("A truncated frame costs only itself") {
    def d = k.authed()
    k.feed(d, "%01," + bitmap([]) + "\$")
    def z3 = d.parent.children["uno-zone-3"]

    // The tail of a zone frame was lost, so the next frame starts before the first one ended.
    k.feed(d, "%01,0000%02,0300000000000000\$")
    k.check("the frame that follows a truncated one is still handled", d.state.partitionStatus?."1" == "notReady", d.state.partitionStatus)
    k.check("and nothing is reported as an error", d.warns().every { !it.contains("Error handling frame") }, d.warns())

    k.feed(d, "%01,00%01," + bitmap([3]) + "\$")
    k.check("a truncated frame followed by a good one of the same kind", z3.last("contact") == "open", z3.events)

    d.logs.clear()
    k.feed(d, "%02,03%06,0200000000000000\$")
    k.check("works for any frame type", d.lastAttr("acFailure") == "active", d.currentVals)

    k.feed(d, "^08,%02,0400000000000000\$")
    k.check("a truncated acknowledgement doesn't swallow the next frame", d.state.partitionStatus["1"] == "armedStay", d.state.partitionStatus)

    k.feed(d, "%03,1130\$")
    k.feed(d, "%02,0100000000000000\$")
    k.check("a too-short Contact ID event is ignored and the next frame is handled", d.state.partitionStatus["1"] == "disarmed")
}

k.section("A truncated frame split across packets") {
    def d = k.authed()
    d.parse(k.hex("%02,0"))
    d.parse(k.hex("3%02,0400"))
    d.parse(k.hex("000000000000\$"))
    k.check("the damaged part is dropped and the rest reassembled", d.state.partitionStatus["1"] == "armedStay", d.state.partitionStatus)
    k.check("the buffer is empty afterwards", d.state.rxBuffer == "", d.state.rxBuffer)
}

k.section("The receive buffer is bounded") {
    def d = k.authed()
    d.parse(k.hex("x" * 3000))
    k.check("some unterminated data is kept in case the rest arrives", d.state.rxBuffer.length() == 3000)
    d.parse(k.hex("y" * 3000))
    k.check("but not without limit: data with no terminator is dropped", d.state.rxBuffer.length() < 4096, d.state.rxBuffer.length())
    k.check("with a warning", d.warns().any { it.contains("receive buffer") }, d.warns())

    k.feed(d, "%02,0300000000000000\$")
    k.check("and the next good frame is still handled", d.state.partitionStatus["1"] == "notReady")

    def big = k.authed()
    big.parse(k.hex("%FF," + ("A" * 512)))
    big.parse(k.hex("\$"))
    k.check("the largest real frame (zone timers, 512 hex characters) fits", big.state.rxBuffer == "" && big.warns().every { !it.contains("receive buffer") }, big.warns())
}

k.section("Garbled data never disconnects") {
    def d = k.authed()
    int connects = d.socket.connects.size()
    ["%\$", "%,\$", "%zz,zz\$", "%01\$", "%01,\$", "%01,ZZZZ\$", "%02,\$", "%02,GG\$", "%03,\$", "%03,abcdefghij\$", "%04,not-hex\$", "%05,\$", "%06,\$", "%07,Q\$", "%08,\$", "%09,\$", "%0A,\$", "%10,\$", "%FF,\$", "\$", "^\$", "^,\$", "^08\$", "^ZZ,9\$", "\u0000\u0001\u0002\$", "%03,1\u00ff30010050\$"].each { String junk ->
        k.feed(d, junk)
    }
    k.check("none of it disconnects", d.socket.connects.size() == connects && d.lastAttr("connectionState") == "authenticated", d.socket.connects)
    k.check("and none of it throws out of parse", true)

    k.feed(d, "%02,0300000000000000\$")
    k.check("the connection still works afterwards", d.state.partitionStatus["1"] == "notReady")
}

k.section("A cleared bypass always clears the flag (envisalink_new fixed this for Honeywell)") {
    def d = k.authed()
    d.parent.zoneNames[4] = "Window"
    k.feed(d, "%01," + bitmap([]) + "\$")
    def z4 = d.parent.children["uno-zone-4"]

    k.feed(d, "%04," + bitmap([4]) + "\$")
    k.check("a bypassed zone is flagged and listed", z4.last("bypassed") == "true" && d.lastAttr("bypassedZones") == "Window (zone 4)", [z4.last("bypassed"), d.lastAttr("bypassedZones")])
    k.feed(d, "%04," + bitmap([]) + "\$")
    k.check("clearing it clears the flag and the list", z4.last("bypassed") == "false" && d.lastAttr("bypassedZones") == "none")

    k.feed(d, "%04," + bitmap([4]) + "\$")
    d.parse(k.hex("%04,00\$"))
    k.check("a shorter bypass bitmap clears zones it no longer covers", z4.last("bypassed") == "false", z4.events.findAll { it.name == "bypassed" }*.value)

    k.feed(d, "%04," + bitmap([4]) + "\$")
    d.initialize(); d.parse(k.hex("Login:")); d.parse(k.hex("OK\r\n"))
    k.feed(d, "%04," + bitmap([]) + "\$")
    k.check("a bypass cleared while disconnected is cleared after reconnecting", z4.last("bypassed") == "false")

    k.feed(d, "%04," + bitmap([4]) + "\$")
    d.resyncStates()
    k.feed(d, "%04," + bitmap([]) + "\$")
    k.check("and after a resync", z4.last("bypassed") == "false")

    def p = k.authed()
    k.feed(p, "%01," + bitmap([]) + "\$")
    k.feed(p, "%04," + bitmap([2, 3]) + "\$")
    k.feed(p, "%04," + bitmap([3]) + "\$")
    k.check("only the zone that was un-bypassed changes", p.parent.children["uno-zone-2"].last("bypassed") == "false" && p.parent.children["uno-zone-3"].last("bypassed") == "true")
}

k.section("Partition states the spec doesn't list (the UNO has no installer or programming state)") {
    def d = k.authed()
    k.feed(d, "%01," + bitmap([]) + "\$")
    k.feed(d, "%02,0B00000000000000\$")
    k.check("an unlisted state is treated as busy, as spec 3.4 says", d.state.partitionStatus["1"] == "busy" && d.lastAttr("alarmSystemStatus") == "busy", d.state.partitionStatus)
    k.feed(d, "%01," + bitmap([2]) + "\$")
    k.check("and zone changes are still reported (there is nothing to suppress)", d.parent.children["uno-zone-2"].last("contact") == "open")
}
