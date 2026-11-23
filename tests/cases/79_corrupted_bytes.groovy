// Corrupted bytes from the panel (e.g. 0xBA 0xBA in the middle of a frame) must never crash parsing, disconnect,
// or turn into bogus events. (Regression guard for envisalink_new #234, where a UnicodeDecodeError on non-ASCII
// bytes killed the connection on every occurrence.)

// Builds the hex string the hub delivers from raw bytes, so bytes above 0x7F can be sent.
def hexOf = { List bytes -> bytes.collect { String.format("%02X", (it as int) & 0xFF) }.join() }
def bytesOf = { String s -> s.getBytes("ISO-8859-1").collect { it as int } }
def corrupt = { String frame, int at, List bad -> bytesOf(frame.substring(0, at)) + bad + bytesOf(frame.substring(at)) }
def replaceAt = { String frame, int at, List bad -> bytesOf(frame.substring(0, at)) + bad + bytesOf(frame.substring(at + bad.size())) }

def bitmap = { List<Integer> zones ->
    byte[] b = new byte[16]
    zones.each { int z -> b[(z - 1).intdiv(8)] |= (1 << ((z - 1) % 8)) }
    b.collect { String.format("%02X", it & 0xFF) }.join()
}

// Representative frames of every type the UNO sends.
def frames = [
    "%01," + bitmap([2]) + "\$\r\n",
    "%02,0300000000000000\$\r\n",
    "%03,1130010050\$\r\n",
    "%03,3441010020\$\r\n",
    "%04," + bitmap([3]) + "\$\r\n",
    "%05,001122334455,UNO,1.0.1,1,2026-11-23 10:00:00\$\r\n",
    "%06,0200000000000000\$\r\n",
    "%07," + ("80" * 128) + "\$\r\n",
    "%08," + ("50" * 16) + "\$\r\n",
    "%09,1,B,06\$\r\n",
    "%0A,1,1,0,0\$\r\n",
    "%10,01000000\$\r\n",
    "%FF," + ("FFFF" * 128) + "\$\r\n",
    "^08,00\$\r\n",
    "^00,00\$\r\n"
]

k.section("Corrupted bytes never throw, disconnect or log an error") {
    int tried = 0
    boolean threw = false
    def d = k.authed(zoneCount: 27)
    int connects = d.socket.connects.size()
    frames.each { String f ->
        // Corrupt many positions with the byte patterns from the report and a few nastier ones, both inserting and
        // overwriting. One driver is reused: any single failure shows up in its warnings or its connection state.
        [0, 1, 2, 3, 4, 5, 7, 9, f.length() - 3, f.length() - 2].findAll { it >= 0 && it < f.length() }.each { int at ->
            [[0xBA, 0xBA], [0xFF], [0x80, 0x00], [0xC3, 0x28], [0xE2, 0x82]].each { List bad ->
                [corrupt(f, at, bad), replaceAt(f, at, bad)].each { List bytes ->
                    tried++
                    try { d.parse(hexOf(bytes)) } catch (Throwable t) { threw = true; println "    ${f.trim()} @${at} ${bad}: ${t}" }
                }
            }
        }
    }
    k.check("${tried} corrupted frames: nothing escapes parse", !threw)
    k.check("and no frame was reported as an error", d.warns().every { !it.contains("Error handling frame") }, d.warns().findAll { it.contains("Error handling frame") }.take(3))
    k.check("none of it disconnects", d.socket.connects.size() == connects && d.lastAttr("connectionState") == "authenticated", d.socket.connects.size())
    k.check("the buffer doesn't keep growing", d.state.rxBuffer.length() < 4096, d.state.rxBuffer.length())
}

k.section("A corrupted frame is lost, the next good one isn't") {
    def d = k.authed()
    d.parse(hexOf(corrupt("%02,0300000000000000\$\r\n", 5, [0xBA, 0xBA]) + bytesOf("%02,0400000000000000\$\r\n")))
    k.check("the good frame after a damaged one is handled", d.state.partitionStatus["1"] == "armedStay", d.state.partitionStatus)

    d.parse(hexOf(bytesOf("%02,01000000") + [0xBA] + bytesOf("00000000\$\r\n%06,0200000000000000\$\r\n")))
    k.check("a damaged frame doesn't stop a later one in the same packet", d.lastAttr("acFailure") == "active")
    k.check("and the damaged frame changed nothing", d.state.partitionStatus["1"] == "armedStay", d.state.partitionStatus)
}

k.section("A corrupted Contact ID event doesn't become an event") {
    def d = k.authed()
    d.parent.userNames[2] = "Alice"
    d.parse(hexOf(replaceAt("%03,1130010050\$\r\n", 4, [0xBA, 0xBA])))
    k.check("no event, history, notification or location event is made from garbage", d.lastAttr("lastEvent") == null && d.historyList().isEmpty() && d.locationEvents.isEmpty() && !d.parent.callArgs.any { it[0] == "notifyEvent" }, [d.lastAttr("lastEvent"), d.historyList(), d.locationEvents])
    d.parse(hexOf(replaceAt("%03,3441010020\$\r\n", 8, [0xBA])))
    k.check("nor does a damaged arm say it was armed by someone", d.lastAttr("lastEvent") == null && d.lastAttr("lastEventUser") == null, d.currentVals)
    d.parse(hexOf(bytesOf("%03,1301A10000\$\r\n")))
    k.check("a letter where a digit belongs is not an event either", d.lastAttr("lastEvent") == null)
    d.parse(hexOf(bytesOf("%03,1301010000X\$\r\n")))
    k.check("but anything after the 10 digits is harmless", d.lastAttr("lastEvent") == "AC power loss", d.lastAttr("lastEvent"))
    d.logs.clear()

    d.parse(hexOf(bytesOf("%03,1301010000\$\r\n")))
    k.check("a good event right after is described normally", d.lastAttr("lastEvent") == "AC power loss")
}

k.section("A corrupted acknowledgement doesn't touch lastAck or the queue") {
    def d = k.authed()
    d.armStay()
    d.parse(hexOf(replaceAt("^08,00\$\r\n", 1, [0xBA, 0xBA])))
    k.check("lastAck isn't set to garbage", d.lastAttr("lastAck") == null, d.lastAttr("lastAck"))
    k.check("and the command is still waiting", d.state.commandInFlight?.name == "armStay")
    k.ack(d, "08")
    k.check("until its real acknowledgement", d.state.commandInFlight == null && d.lastAttr("lastAck") == "08,0", [d.state.commandInFlight, d.lastAttr("lastAck")])
}

k.section("Corrupted host information doesn't overwrite the panel details") {
    def d = k.authed()
    k.feed(d, "%05,001122334455,UNO,1.0.1,1,2026-11-23 10:00:00\$")
    d.parse(hexOf(replaceAt("%05,001122334455,UNO,1.0.1,1,2026-11-23 10:00:00\$\r\n", 20, [0xBA, 0xBA])))
    k.check("model, firmware and MAC keep readable values", d.lastAttr("panelModel") == "UNO" && d.lastAttr("firmwareVersion") == "1.0.1" && d.lastAttr("panelMac") == "001122334455", [d.lastAttr("panelModel"), d.lastAttr("firmwareVersion"), d.lastAttr("panelMac")])
    def printable = { Object v -> v.toString().toCharArray().every { char ch -> ch >= 32 && ch <= 126 } }
    k.check("no attribute ever contains a non-printable character", d.currentVals.values().every { v -> printable(v) }, d.currentVals.findAll { k2, v -> !printable(v) })
}

k.section("Corrupted login traffic") {
    def d = k.newConn()
    d.initialize()
    d.parse(hexOf(bytesOf("Lo") + [0xBA] + bytesOf("gin:")))
    k.check("a damaged prompt doesn't send the password", d.sent.isEmpty(), d.sent)
    d.parse(hexOf(bytesOf("\r\nLogin:")))
    k.check("the next real prompt does", d.sent == ["pw\r"], d.sent)
    d.parse(hexOf(bytesOf("O") + [0xBA] + bytesOf("K\r\n")))
    k.check("a damaged OK doesn't authenticate", d.lastAttr("connectionState") != "authenticated")
    d.parse(hexOf(bytesOf("OK\r\n")))
    k.check("the real one does", d.lastAttr("connectionState") == "authenticated")
}

k.section("Bytes are decoded one-for-one, whatever the hub's default character set") {
    def d = k.authed()
    d.parse(hexOf([0xE9, 0xFF, 0x80]))
    k.check("high bytes are kept as single characters, so they can't swallow a following terminator", d.state.rxBuffer.length() == 3, d.state.rxBuffer.length())
    d.parse(hexOf(bytesOf("\$")))
    d.parse(hexOf([0xC3]))
    d.parse(hexOf(bytesOf("%02,0300000000000000\$")))
    k.check("a lone UTF-8 lead byte doesn't eat the next frame", d.state.partitionStatus["1"] == "notReady", d.state.partitionStatus)
}
