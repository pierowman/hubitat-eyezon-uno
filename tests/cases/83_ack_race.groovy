// A reply can arrive on the hub before the execution that sent the command has saved it as in flight (seen on a real
// hub as "Retrying poll" a few seconds after the poll had been acknowledged). The reply must not be lost.

// Delivers the reply before the in-flight command is visible in state, then lets the sender's write land.
def lateWrite = { d, String cc, String code ->
    def saved = d.state.commandInFlight
    d.state.commandInFlight = null
    k.ack(d, cc, code)
    d.state.commandInFlight = saved
}

k.section("A login written over by a reconnect that was still finishing") {
    // State is saved when an execution ends, so "not logged in" can overwrite a login that completed in between.
    def d = k.authed()
    d.state.loggedIn = false
    d.poll()
    k.check("the connection state says it is logged in, so the queue goes on", d.sent.size() == 1 && d.state.loggedIn == true, [d.sent, d.state.loggedIn])
    k.check("and does not wait for a login that will never come", !d.logs.any { it.msg.contains("Queue paused until login completes") }, d.logs.takeRight(3))

    def c = k.newConn()
    c.initialize()
    c.state.loggedIn = false
    c.poll()
    k.check("while connecting, it still waits for the login", c.sent.isEmpty() && c.state.loggedIn == false, c.sent)

    def p = k.authed()
    p.state.loggedIn = false
    p.login()
    k.check("a password is never typed in while the connection says it is logged in (the panel would take it as keystrokes)", p.sent.isEmpty() && p.warns().any { it.contains("Already logged in") }, [p.sent, p.warns()])
}

k.section("A reply that beats the sender's state write is still honoured") {
    def d = k.authed()
    d.poll()
    k.check("the poll is sent", d.sent == ["^00,\$\r\n"], d.sent)
    lateWrite(d, "00", "0")
    d.nowMs += 4000; d.commandTimeoutCheck()
    k.check("the timeout check does not send it again", d.sent.size() == 1, d.sent)
    k.check("the command is finished", d.state.commandInFlight == null)
    k.check("and the connection is not dropped", d.lastAttr("connectionState") != "disconnected" && !d.pending.containsKey("reconnect"), d.pending)

    d.poll()
    k.check("so the next command can go", d.sent.size() == 2, d.sent)
}

k.section("A rejection that arrives early is acted on, and its code is released") {
    def d = k.authed(masterCode: "")
    d.disarm("5555")
    lateWrite(d, "12", "7")
    d.nowMs += 4000; d.commandTimeoutCheck()
    k.check("not re-sent", d.sent.size() == 1, d.sent)
    k.check("the rejection is reported", d.lastAttr("lastAck") == "error:disarm:7", d.lastAttr("lastAck"))
    k.check("the code is released from memory", d.CODE_VAULT.isEmpty(), d.CODE_VAULT)
}

k.section("A busy reply that arrives early still triggers the retry") {
    def d = k.authed()
    d.poll()
    lateWrite(d, "00", "1")
    d.nowMs += 4000; d.commandTimeoutCheck()
    k.check("a retry is scheduled", d.pending.containsKey("retryInFlight"), d.pending)
    d.retryInFlight()
    k.check("and the command is sent again", d.sent.size() == 2, d.sent)
}

k.section("An earlier reply is never mistaken for this command's") {
    def d = k.authed()
    d.poll()
    k.ack(d, "00")
    d.nowMs += 60000L
    d.poll()
    k.check("the second poll is sent", d.sent.size() == 2, d.sent)
    d.nowMs += 4000; d.commandTimeoutCheck()
    k.check("with no reply to it, it is retried", d.sent.size() == 3, d.sent)
}

k.section("Nothing connects before an address is set") {
    def d = k.newConn()
    d.settings.remove("ip")
    int connects = 0
    d.interfaces = [rawSocket: [connect: { Object[] a -> connects++ }, close: { Object[] a -> }, sendMessage: { Object[] a -> }]]
    d.installed()
    k.check("the driver does not open a socket to null", connects == 0, connects)
    k.check("it says why", d.lastAttr("connectionState") != "connecting", d.lastAttr("connectionState"))

    d.settings.ip = "1.2.3.4"
    d.reconnect()
    k.check("it connects once there is an address", connects == 1, connects)
}
