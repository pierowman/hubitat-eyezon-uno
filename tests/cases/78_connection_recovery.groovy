// Connection failure handling: recovery must never get stuck, whatever fails. (Regression guard for envisalink_new
// #237/#238, where a TypeError in the login-timeout handler left the reconnect loop permanently broken, the backoff
// stuck at its minimum, fused messages caused false keepalive timeouts, and exceptions on bad data dropped the connection.)

def exceptions = [new IOException("refused"), new IllegalStateException("closed"), new RuntimeException("boom"), new NullPointerException(), new TypeError("x")]
class TypeError extends Exception { TypeError(String m) { super(m) } }   // stands in for the reported TypeError

// A raw socket whose operations fail on demand.
def brokenSocket = { Map fails ->
    def calls = [connect: 0, close: 0, send: 0]
    return [calls: calls, sock: [
        connect    : { Object[] a -> calls.connect++; if (fails.connect) throw fails.connect },
        close      : { Object[] a -> calls.close++; if (fails.close) throw fails.close },
        sendMessage: { Object[] a -> calls.send++; if (fails.send) throw fails.send }
    ]]
}

k.section("A failed connect always schedules the next attempt") {
    exceptions.each { Exception ex ->
        def d = k.newConn()
        def b = brokenSocket([connect: ex])
        d.interfaces = [rawSocket: b.sock]
        boolean threw = false
        try { d.initialize() } catch (Throwable t) { threw = true; println "    ${ex.class.simpleName}: ${t}" }
        k.check("${ex.class.simpleName} on connect: nothing escapes", !threw)
        k.check("  the state says so and a retry is scheduled", d.lastAttr("connectionState") == "error" && d.pending.containsKey("reconnect"), [d.lastAttr("connectionState"), d.pending])
    }

    exceptions.each { Exception ex ->
        def d = k.newConn()
        def b = brokenSocket([close: ex])
        d.interfaces = [rawSocket: b.sock]
        boolean threw = false
        try { d.initialize() } catch (Throwable t) { threw = true; println "    ${ex.class.simpleName}: ${t}" }
        k.check("${ex.class.simpleName} when closing the old socket: still connects", !threw && b.calls.connect == 1, b.calls)
    }

    def both = k.newConn()
    def bb = brokenSocket([close: new IOException("x"), connect: new IOException("y")])
    both.interfaces = [rawSocket: bb.sock]
    both.initialize()
    both.pending.remove("reconnect")
    both.reconnect()
    k.check("the retry itself can fail and still schedules another", both.pending.containsKey("reconnect") && bb.calls.connect == 2, bb.calls)
}

k.section("Repeated connect timeouts keep retrying, with a growing delay") {
    def d = k.newConn()
    d.socket.failConnect = true
    d.initialize()
    List delays = []
    12.times {
        delays << d.pending["reconnect"]
        d.pending.remove("reconnect")
        d.reconnect()
    }
    k.check("2, 4, 8 ... up to 128 seconds, and then stays there", delays == [2, 4, 8, 16, 32, 64, 128, 128, 128, 128, 128, 128], delays)
    k.check("it never gave up", d.pending.containsKey("reconnect"))

    d.socket.failConnect = false
    d.pending.remove("reconnect")
    d.reconnect()
    d.parse(k.hex("Login:")); d.parse(k.hex("OK\r\n"))
    k.check("and recovers as soon as the panel answers", d.lastAttr("connectionState") == "authenticated")
}

k.section("A flapping connection doesn't reset the backoff") {
    def d = k.newConn()
    d.initialize()
    List delays = []
    8.times {
        d.parse(k.hex("Login:")); d.parse(k.hex("OK\r\n"))     // logs in...
        d.socketStatus("status: closed")                         // ...and drops straight away
        delays << d.pending["reconnect"]
        d.pending.remove("reconnect")
        d.reconnect()
    }
    k.check("each quick drop waits longer than the last, not 2 seconds every time", delays == [2, 4, 8, 16, 32, 64, 128, 128], delays)

    d.parse(k.hex("Login:")); d.parse(k.hex("OK\r\n"))
    d.nowMs += 150000L
    d.watchdogCheck()
    k.check("after staying up for 2 minutes the backoff starts over", d.state.reconnectDelay == 2, d.state.reconnectDelay)
}

k.section("A failed send is treated as a lost connection") {
    exceptions.each { Exception ex ->
        def d = k.newConn()
        def b = brokenSocket([send: ex])
        d.interfaces = [rawSocket: b.sock]
        d.initialize()
        boolean threw = false
        try {
            d.parse(k.hex("Login:"))          // sending the password fails
        } catch (Throwable t) { threw = true; println "    ${ex.class.simpleName}: ${t}" }
        k.check("${ex.class.simpleName} sending the password: nothing escapes parse", !threw)
        k.check("  a reconnect is scheduled", d.pending.containsKey("reconnect") && d.lastAttr("connectionState") == "disconnected", [d.pending, d.lastAttr("connectionState")])
        k.check("  and the receive buffer is still consistent", d.state.rxBuffer == "", d.state.rxBuffer)
    }

    def d = k.authed()
    d.socket.metaClass.sendMessage = { String m -> throw new IOException("broken pipe") }
    boolean threw = false
    try { d.armStay() } catch (Throwable t) { threw = true }
    k.check("a command sent on a dead socket doesn't throw", !threw)
    k.check("it is dropped, not left blocking the queue", d.state.commandInFlight == null && d.state.commandQueue.isEmpty(), [d.state.commandInFlight, d.state.commandQueue])
    k.check("and the connection is reconnected", d.pending.containsKey("reconnect"), d.pending)

    def r = k.authed()
    r.armStay()
    r.socket.metaClass.sendMessage = { String m -> throw new IOException("broken pipe") }
    r.nowMs += 4000L
    boolean threw2 = false
    try { r.commandTimeoutCheck() } catch (Throwable t) { threw2 = true }
    k.check("a retry on a dead socket doesn't throw or leave the command stuck", !threw2 && r.state.commandInFlight == null, r.state.commandInFlight)
}

k.section("Fused and split messages") {
    def d = k.authed()
    d.poll()
    d.parse(k.hex("^00,00\$%02,0300000000000000\$\r\n"))
    k.check("an acknowledgement fused with a frame: both are handled", d.state.commandInFlight == null && d.state.partitionStatus["1"] == "notReady", [d.state.commandInFlight, d.state.partitionStatus])

    d.parse(k.hex("%02,0100000000000000\$^00,00\$%06,0200000000000000\$"))
    k.check("three in a row", d.state.partitionStatus["1"] == "disarmed" && d.lastAttr("acFailure") == "active")

    d.parse(k.hex("%02,04000000"))
    d.parse(k.hex("00000000\$\r\n%06,0000000000000000\$\r\n"))
    k.check("a frame split across packets, then another", d.state.partitionStatus["1"] == "armedStay" && d.lastAttr("acFailure") == "clear")

    def f = k.authed()
    f.poll()
    f.parse(k.hex("%02,0300000000000000\$\r\n^00,00\$\r\n"))
    k.check("a frame then the reply, as the EVL does after a stall", f.state.commandInFlight == null && f.state.partitionStatus["1"] == "notReady")

    def c = k.authed()
    c.poll()
    c.parse(k.hex("^00,00"))
    k.check("an acknowledgement with no terminator waits for it", c.state.commandInFlight != null && c.state.rxBuffer == "^00,00")
    c.parse(k.hex("\$"))
    k.check("and completes when it arrives", c.state.commandInFlight == null)
}

k.section("An acknowledgement for a different command") {
    def d = k.authed()
    d.armStay()
    k.feed(d, "^09,00\$")
    k.check("is ignored: the command in flight is still waiting", d.state.commandInFlight?.name == "armStay", d.state.commandInFlight)
    k.check("and nothing is released early", d.sent.size() == 1)
    k.ack(d, "08")
    k.check("its own acknowledgement completes it", d.state.commandInFlight == null)

    def q = k.authed()
    q.armStay(); q.armAway()
    k.feed(q, "^09,00\$")
    k.check("an acknowledgement for a queued command doesn't skip ahead", q.state.commandInFlight?.name == "armStay" && q.state.commandQueue.size() == 1)

    def n = k.authed()
    k.feed(n, "^08,00\$")
    k.check("an acknowledgement with nothing in flight is harmless", n.state.commandInFlight == null && n.warns().isEmpty(), n.warns())
}

k.section("The login-timeout handler can't be the thing that breaks recovery") {
    def d = k.newConn()
    d.initialize()
    d.parse(k.hex("Login:"))
    boolean threw = false
    try { d.parse(k.hex("Timed Out!\r\n")) } catch (Throwable t) { threw = true }
    k.check("the panel's 'Timed Out!' is handled", !threw && d.lastAttr("connectionState") == "loginTimedOut" && d.pending.containsKey("reconnect"), [d.lastAttr("connectionState"), d.pending])

    // The retry fires, connects, and the same thing happens again, repeatedly.
    4.times {
        d.pending.remove("reconnect")
        boolean t2 = false
        try { d.reconnect(); d.parse(k.hex("Login:")); d.parse(k.hex("Timed Out!\r\n")) } catch (Throwable t) { t2 = true }
        k.check("login timeout, attempt ${it + 2}: still recovering", !t2 && d.pending.containsKey("reconnect"))
    }

    def socketTimeout = k.newConn()
    socketTimeout.initialize()
    ["status: timeout", "status: Timed out connecting", "java.net.SocketTimeoutException", "failed to connect", ""].each { String msg ->
        socketTimeout.pending.remove("reconnect")
        boolean t3 = false
        try { socketTimeout.socketStatus(msg) } catch (Throwable t) { t3 = true }
        k.check("socketStatus('${msg}') never throws", !t3)
    }
    k.check("socketStatus(null) never throws either", { try { socketTimeout.socketStatus(null); true } catch (Throwable t) { false } }())
}

// Probe: how socketStatus reads messages the hub might deliver (kept as a test if it passes).
k.section("socketStatus: failures are recognised whatever the wording") {
    def failures = ["java.net.ConnectException: Connection refused", "java.net.SocketTimeoutException: connect timed out", "java.net.NoRouteToHostException: No route to host",
                    "Connection reset by peer", "java.io.IOException: Broken pipe", "java.net.UnknownHostException: uno.local", "Network is unreachable",
                    "receive error: Stream closed.", "send error: Broken pipe", "status: closed", "java.io.EOFException", "Read timed out", "Connection timed out", "Software caused connection abort"]
    failures.each { String msg ->
        def d = k.newConn()
        d.initialize()
        d.pending.remove("reconnect")
        d.socketStatus(msg)
        k.check("'${msg}' schedules a reconnect and doesn't claim a connection", d.pending.containsKey("reconnect") && d.lastAttr("connectionState") != "connected", [d.lastAttr("connectionState"), d.pending])
    }
    ["status: open", "status: Connected"].each { String msg ->
        def d = k.newConn()
        d.initialize()
        d.socketStatus(msg)
        k.check("'${msg}' is a connection", d.lastAttr("connectionState") == "connected")
    }
}
