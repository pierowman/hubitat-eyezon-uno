// Connection driver: TPI session handling (spec section 2.1), reconnect and watchdog behaviour.

k.section("Login handshake") {
    def d = k.newConn()
    d.initialize()
    k.check("initialize opens socket to 1.2.3.4:4025", d.socket.connects == [["1.2.3.4", 4025]])
    k.check("state is connecting", d.lastAttr("connectionState") == "connecting")
    k.check("heartbeat + watchdog scheduled",
            d.scheduleCalls.any { it[0] == "heartbeatCheck" } && d.scheduleCalls.any { it[0] == "watchdogCheck" }, d.scheduleCalls)

    d.parse(k.hex("Login:"))
    k.check("password sent followed by CR in a single message", d.sent == ["pw\r"], d.sent)
    k.check("login prompt cleared from rx buffer", d.state.rxBuffer == "", d.state.rxBuffer)
    d.parse(k.hex("OK\r\n"))
    k.check("OK => authenticated", d.lastAttr("connectionState") == "authenticated")
    k.check("state dump ^0C,\$ requested with CRLF", d.sent.contains("^0C,\$\r\n"), d.sent)
    k.check("no separate ^0D request (panel sends host info itself)", !d.sent.any { it.startsWith("^0D") }, d.sent)
    d.parse(k.hex("^0C,0\$\r\n"))
    k.check("matching ack clears in-flight", d.state.commandInFlight == null)
}

k.section("Login variants") {
    def d1 = k.newConn(); d1.initialize(); d1.parse(k.hex("Login"))
    k.check("prompt without colon still triggers login", d1.sent == ["pw\r"], d1.sent)

    def d2 = k.newConn(); d2.initialize(); d2.parse(k.hex("Login:")); d2.parse(k.hex("OK"))
    k.check("unterminated OK accepted", d2.lastAttr("connectionState") == "authenticated", d2.lastAttr("connectionState"))

    def d3 = k.newConn(); d3.initialize(); d3.parse(k.hex("Login:")); d3.parse("OK\r\n")
    k.check("raw (non-hex) text fallback works", d3.lastAttr("connectionState") == "authenticated")
}

k.section("Login failures back off") {
    def d = k.newConn(); d.initialize(); d.parse(k.hex("Login:")); d.parse(k.hex("FAILED\r\n"))
    k.check("FAILED => auth_failed", d.lastAttr("connectionState") == "auth_failed")
    k.check("auth failure backs off 300s", d.pending["reconnect"] == 300, d.pending)
    d.socketStatus("status: closed")
    k.check("socket close after FAILED keeps 300s back-off", d.pending["reconnect"] == 300, d.pending)
    k.check("auth_failed not overwritten by disconnected", d.lastAttr("connectionState") == "auth_failed")
    d.watchdogCheck()
    k.check("watchdog doesn't hammer after auth failure", d.socket.connects.size() == 1, d.socket.connects)

    def t = k.newConn(); t.initialize(); t.parse(k.hex("Login:")); t.parse(k.hex("Timed Out!\r\n"))
    k.check("Timed Out => retry in 2s (first back-off step)", t.lastAttr("connectionState") == "loginTimedOut" && t.pending["reconnect"] == 2, t.pending)
}

k.section("Reconnect and socket status") {
    def f = k.newConn(); f.socket.failConnect = true; f.initialize()
    k.check("connect failure => error + first retry in 2s", f.lastAttr("connectionState") == "error" && f.pending["reconnect"] == 2, f.pending)

    def d = k.newConn(); d.initialize()
    d.socketStatus("status: open")
    k.check("'status: open' => connected", d.lastAttr("connectionState") == "connected")
    d.socketStatus("disconnected")
    k.check("'disconnected' => disconnected + reconnect in 2s",
            d.lastAttr("connectionState") == "disconnected" && d.pending["reconnect"] == 2, d.pending)
    d.pending.clear(); d.state.reconnectDueAt = 0L
    d.socketStatus("failed to connect")
    k.check("'failed to connect' schedules a reconnect (not treated as connected), 4s into the back-off", d.pending["reconnect"] == 4, d.pending)
    d.pending.clear(); d.state.reconnectDueAt = 0L
    d.socketStatus("receive error: Stream closed")
    k.check("receive error schedules a reconnect, 8s into the back-off", d.pending["reconnect"] == 8, d.pending)
}

k.section("Exponential reconnect back-off") {
    def d = k.newConn(); d.socket.failConnect = true
    d.initialize()
    List delays = [d.pending["reconnect"]]
    8.times {
        d.reconnect()   // the scheduled retry firing
        delays << d.pending["reconnect"]
    }
    k.check("delays double from 2s up to a 128s cap", delays == [2, 4, 8, 16, 32, 64, 128, 128, 128], delays)
    d.initialize()
    k.check("saving the settings starts the back-off over", d.pending["reconnect"] == 2, d.pending)

    def dup = k.newConn(); dup.initialize()
    dup.socketStatus("status: closed")
    dup.socketStatus("receive error: Stream closed")
    k.check("several callbacks for one drop schedule one reconnect and advance the back-off once", dup.pending["reconnect"] == 2 && dup.state.reconnectDelay == 4, [dup.pending, dup.state.reconnectDelay])
    dup.reconnect()
    dup.socketStatus("status: closed")
    k.check("a connection that drops again right away keeps backing off", dup.pending["reconnect"] == 4, dup.pending)

    def s = k.authed()
    s.state.reconnectDelay = 64
    s.nowMs += 119000L; s.watchdogCheck()
    k.check("a connection that has been up for less than 2 minutes keeps its back-off", s.state.reconnectDelay == 64, s.state.reconnectDelay)
    s.nowMs += 2000L; s.watchdogCheck()
    k.check("after 2 stable minutes the back-off resets", s.state.reconnectDelay == 2, s.state.reconnectDelay)

    def a = k.newConn(); a.initialize(); a.state.reconnectDelay = 64
    a.parse(k.hex("Login:")); a.parse(k.hex("FAILED\r\n"))
    k.check("a rejected password always waits 5 minutes, whatever the back-off", a.pending["reconnect"] == 300, a.pending)
}

k.section("Watchdog") {
    def a = k.authed()
    a.nowMs += 4 * 60 * 1000L
    a.state.lastFrameTs = a.nowMs - 4 * 60 * 1000L
    int before = a.socket.connects.size()
    a.watchdogCheck()
    k.check("reconnects after 4 min silence (threshold 3)", a.socket.connects.size() == before + 1)

    def b = k.authed(heartbeatMinutes: "10", watchdogMinutes: "3")
    b.state.lastFrameTs = b.nowMs - 4 * 60 * 1000L
    int beforeB = b.socket.connects.size()
    b.watchdogCheck()
    k.check("threshold never shorter than heartbeat+1", b.socket.connects.size() == beforeB)

    def c = k.newConn(); c.initialize()
    c.nowMs += 3 * 60 * 1000L
    int beforeC = c.socket.connects.size()
    c.watchdogCheck()
    k.check("recovers a connection that never authenticated", c.socket.connects.size() == beforeC + 1)
}

k.section("Frame handling robustness") {
    def d = k.authed(zoneCount: 8)
    k.feed(d, "%01,0100000000000000")
    k.feed(d, "0000000000000000\$\r\n%02,0100000000000000\$\r\n")
    k.check("fragmented frame reassembled", d.parent.children["uno-zone-1"]?.last("contact") == "open")
    k.check("second frame in the same packet parsed", d.lastAttr("alarmSystemStatus") == "disarmed")

    def e = k.authed(zoneCount: 8)
    e.parent.failPartitionNotify = true
    k.feed(e, "%02,0100000000000000\$\r\n%01,0100000000000000\$\r\n")
    k.check("a handler exception is logged with the frame", e.warns().any { it.contains("Error handling frame") }, e.warns())
    k.check("later frames in the same packet are still processed", e.parent.children["uno-zone-1"]?.last("contact") == "open")
}
