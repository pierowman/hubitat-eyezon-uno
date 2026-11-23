// Connection driver: commands sent to the UNO (spec section 3.2), the command queue and acks (3.7).

k.section("Queue and acks") {
    def d = k.authed()
    d.armStay(); d.armAway()
    k.check("armStay sent as ^08,1\$ CRLF", d.sent == ["^08,1\$\r\n"], d.sent)
    d.poll()
    k.check("poll skipped while a command is in flight", d.sent.size() == 1)
    k.ack(d, "00")
    k.check("ack for a different command doesn't clear in-flight", d.state.commandInFlight?.cc == "08")
    k.ack(d, "08")
    k.check("matching ack sends the next queued command", d.sent == ["^08,1\$\r\n", "^09,1\$\r\n"], d.sent)
    k.ack(d, "09", "6")
    k.check("error code 6 logged with its meaning", d.warns().any { it.contains("cannot be completed") }, d.warns())
    k.check("error recorded in lastAck", d.lastAttr("lastAck") == "error:armAway:6", d.lastAttr("lastAck"))
    k.check("queue free after an error ack", d.state.commandInFlight == null)

    d.sent.clear()
    d.poll()
    k.check("idle poll sent as ^00,\$", d.sent == ["^00,\$\r\n"], d.sent)
}

k.section("Retry and timeout") {
    def d = k.authed()
    d.armStay()
    d.nowMs += 4000
    d.commandTimeoutCheck()
    k.check("no ack => one retry", d.sent.size() == 2 && d.state.commandInFlight?.retries == 1, d.sent)
    d.nowMs += 4000
    d.commandTimeoutCheck()
    k.check("second timeout drops the command", d.state.commandInFlight == null && d.lastAttr("lastAck") == "timeout:armStay", d.lastAttr("lastAck"))
    k.check("and reconnects, because silence means the link is dead", d.socket.connects.size() == 2 && d.lastAttr("connectionState") == "connecting", d.socket.connects)

    def p = k.authed(enablePanic: true)
    p.panic("fire")
    p.nowMs += 4000
    p.commandTimeoutCheck()
    k.check("a panic with no reply also triggers a reconnect, without being re-sent", p.sent.size() == 1 && p.socket.connects.size() == 2, p.sent)

    def q = k.newConn(); q.initialize()
    q.armStay()
    k.check("commands are queued (not sent) before login", q.sent.isEmpty() && q.state.commandQueue.size() == 1, q.sent)
    q.parse(k.hex("Login:")); q.sent.clear(); q.parse(k.hex("OK\r\n"))
    k.check("queue drains after login", q.sent.first() == "^08,1\$\r\n" || q.sent.first() == "^0C,\$\r\n", q.sent)
}

k.section("Stale commands never run late") {
    def d = k.authed()
    d.armStay(); d.armAway()
    d.reconnect()
    k.check("a reconnect drops the in-flight and queued commands", d.state.commandInFlight == null && d.state.commandQueue.isEmpty())
    k.check("each dropped command is recorded and logged", d.lastAttr("lastAck") == "dropped:armAway" && d.warns().any { it.contains("Dropped 2 command(s) (reconnecting): armStay, armAway") }, d.warns())
    d.sent.clear()
    d.parse(k.hex("Login:")); d.parse(k.hex("OK\r\n"))
    k.check("after logging in again the only command sent is the state dump", d.sent.findAll { it.startsWith("^") } == ["^0C,\$\r\n"], d.sent)

    def c = k.authed()
    c.armAway()
    c.socketStatus("status: closed")
    k.check("losing the connection drops the in-flight command", c.state.commandInFlight == null && c.warns().any { it.contains("(connection lost)") }, c.warns())

    def i = k.authed()
    i.armStay(); i.armAway()
    i.initialize()
    k.check("saving the settings drops queued commands too", i.state.commandQueue.isEmpty() && i.warns().any { it.contains("(reinitializing)") }, i.warns())

    def u = k.authed()
    u.armStay(); u.armAway()
    u.clearCommandQueue()
    k.check("clearing the queue by hand is reported", u.state.commandQueue.isEmpty() && u.warns().any { it.contains("(cleared by user)") }, u.warns())

    def q = k.newConn(); q.initialize()
    q.armAway()
    k.check("a backstop timer is scheduled when a command is queued", q.pending["expireQueue"] == 35, q.pending)
    q.nowMs += 31000L
    q.parse(k.hex("Login:")); q.sent.clear(); q.parse(k.hex("OK\r\n"))
    k.check("a command that waited over 30s for the login is dropped, not sent", q.sent == ["^0C,\$\r\n"] && q.warns().any { it.contains("waited more than 30s") }, [q.sent, q.warns()])
    k.check("and is recorded as dropped", q.lastAttr("lastAck") == "dropped:armAway")

    def e = k.newConn(); e.initialize()
    e.armStay()
    e.nowMs += 31000L
    e.expireQueue()
    k.check("the backstop timer expires old commands even with no other activity", e.state.commandQueue.isEmpty() && e.lastAttr("lastAck") == "dropped:armStay")

    def f = k.newConn(enablePanic: true); f.initialize()
    f.panic("fire")
    f.reconnect()
    k.check("a panic that could not be sent is logged as an error", f.errors().any { it.contains("PANIC command 'panic:fire' was NOT sent") }, f.errors())

    def n = k.authed()
    n.armStay()
    n.nowMs += 5000L
    n.armAway()
    k.check("a fresh command behind a slow one is not expired", n.state.commandQueue.size() == 1)
}

k.section("Busy panel: retry on response code 1 (spec 3.7)") {
    def d = k.authed()
    d.armStay(); d.armAway()
    k.ack(d, "08", "1")
    k.check("code 1 waits to retry instead of failing", d.state.commandInFlight?.waiting == true && d.pending["retryInFlight"] == 1 && d.sent.size() == 1, d.pending)
    k.check("the queued command stays behind it", d.state.commandQueue.size() == 1)
    d.nowMs += 10000L; d.commandTimeoutCheck()
    k.check("a command waiting to retry isn't treated as a timeout", d.state.commandInFlight != null && d.socket.connects.size() == 1)

    d.retryInFlight()
    k.check("re-sent after 1s", d.sent == ["^08,1\$\r\n", "^08,1\$\r\n"] && d.state.commandInFlight.waiting == false, d.sent)
    k.ack(d, "08", "1")
    k.check("second busy reply waits 2s", d.pending["retryInFlight"] == 2, d.pending)
    d.retryInFlight()
    k.ack(d, "08", "1")
    k.check("third busy reply waits 4s", d.pending["retryInFlight"] == 4, d.pending)
    d.retryInFlight()
    k.check("sent 4 times in total", d.sent.size() == 4)
    k.ack(d, "08", "1")
    k.check("after 3 retries the command fails", d.lastAttr("lastAck") == "error:armStay:1" && d.warns().any { it.contains("receive buffer overrun") })
    k.check("the queue moves on to the next command", d.sent.last() == "^09,1\$\r\n", d.sent)

    def s = k.authed()
    s.armStay()
    k.ack(s, "08", "1"); s.retryInFlight()
    k.ack(s, "08", "0")
    k.check("a retry that is accepted completes normally", s.state.commandInFlight == null && s.lastAttr("lastAck") == "08,0" && s.warns().count { it.contains("rejected") } == 0, s.warns())

    s.retryInFlight()
    k.check("a stray retry timer does nothing", s.sent.size() == 2)

    def p = k.authed(enablePanic: true)
    p.panic("fire")
    k.ack(p, "11", "1"); p.retryInFlight()
    k.check("a panic the UNO rejected as busy was not run, so it is re-sent", p.sent == ["^11,1,0\$\r\n", "^11,1,0\$\r\n"], p.sent)

    def other = k.authed()
    other.armStay()
    k.ack(other, "08", "6")
    k.check("other error codes are not retried", other.state.commandInFlight == null && other.sent.size() == 1)

    def r = k.authed()
    r.armStay()
    k.ack(r, "08", "1")
    r.reconnect()
    r.retryInFlight()
    k.check("a reconnect cancels a pending retry", r.state.commandInFlight == null && r.sent.size() == 1, r.sent)
}

k.section("Arm commands and partitions") {
    def d = k.authed()
    d.armStay(2)
    k.check("armStay(2) => ^08,2\$", d.sent == ["^08,2\$\r\n"], d.sent)
    k.ack(d, "08")
    d.armAway(3)
    k.check("armAway(3) => ^09,3\$", d.sent.last() == "^09,3\$\r\n", d.sent)
    k.ack(d, "09")
    d.sent.clear()
    d.armStay(null)
    k.check("null partition defaults to 1", d.sent == ["^08,1\$\r\n"], d.sent)
    k.ack(d, "08")

    d.sent.clear()
    d.armStay(9); d.armAway(0); d.armStay("abc"); d.armAway(-1)
    k.check("partitions outside 1-8 or non-numeric are rejected", d.sent.isEmpty() && d.warns().count { it.contains("Partition must be 1 to 8") } == 4, d.warns())
    d.armStay(2.0G)
    k.check("a BigDecimal partition from the Hubitat UI is accepted", d.sent == ["^08,2\$\r\n"], d.sent)
}

k.section("Disarm") {
    def d = k.authed(masterCode: "")
    d.disarm("12ab"); d.disarm("123"); d.disarm("1234567"); d.disarm("12,4")
    k.check("invalid disarm codes rejected", d.sent.isEmpty() && d.warns().size() == 4, [d.sent, d.warns()])
    d.disarm("1234")
    k.check("valid 4-digit disarm => ^12,1,1234\$", d.sent == ["^12,1,1234\$\r\n"], d.sent)
    k.ack(d, "12", "7")
    k.check("code 7 => invalid user code", d.warns().any { it.contains("invalid user code") }, d.warns())
    d.sent.clear(); d.disarm("123456")
    k.check("6-digit code accepted", d.sent == ["^12,1,123456\$\r\n"], d.sent)
    k.ack(d, "12")
    d.sent.clear(); d.disarm("1234", 2)
    k.check("disarm on partition 2 => ^12,2,1234\$", d.sent == ["^12,2,1234\$\r\n"], d.sent)

    def m = k.authed(masterCode: "4321")
    m.disarm()
    k.check("falls back to the configured master code", m.sent == ["^12,1,4321\$\r\n"], m.sent)
    k.ack(m, "12")
    m.sent.clear(); m.disarm(null, 3)
    k.check("master code used on another partition", m.sent == ["^12,3,4321\$\r\n"], m.sent)
}

k.section("Chime commands") {
    def d = k.authed()
    d.chimeOn()
    k.check("chimeOn => ^10,1,1\$", d.sent == ["^10,1,1\$\r\n"], d.sent)
    k.ack(d, "10")
    k.check("chime attribute on after the ack", d.lastAttr("chime") == "on" && d.parent.partitionAttr(1, "chime") == "on")
    d.chimeOff(); k.ack(d, "10")
    k.check("chimeOff => ^10,1,0\$ and chime=off", d.sent.last() == "^10,1,0\$\r\n" && d.lastAttr("chime") == "off", d.sent)
    d.chimeOn(); k.ack(d, "10", "2")
    k.check("a rejected chime leaves the attribute unchanged", d.lastAttr("chime") == "off")

    d.sent.clear()
    d.chimeOn(2)
    k.check("chimeOn(2) => ^10,2,1\$", d.sent == ["^10,2,1\$\r\n"], d.sent)
    k.ack(d, "10")
    k.check("partition 2 chime state goes to partition 2 only", d.parent.partitionAttr(2, "chime") == "on" && d.lastAttr("chime") == "off", d.lastAttr("chime"))
}

k.section("Require code to arm") {
    def off = k.authed(masterCode: "4321")
    off.armStay()
    k.check("by default arming needs no code", off.sent == ["^08,1\$\r\n"], off.sent)
    k.ack(off, "08")
    off.armAway(1, "9999")
    k.check("and an extra code is simply ignored", off.sent.last() == "^09,1\$\r\n")

    def d = k.authed(requireCodeToArm: true, masterCode: "4321")
    d.armStay()
    d.armAway(1, "0000")
    d.armStay(1, "")
    k.check("arming without a code, or with a wrong one, is rejected", d.sent.isEmpty() && d.warns().count { it.contains("a valid code is required to arm") } == 3, d.warns())
    k.check("the rejection is recorded", d.lastAttr("lastAck") == "rejected:armStay:code")
    k.check("nothing is queued for a rejected request", d.state.commandQueue.isEmpty() && d.state.commandInFlight == null)

    d.armStay(1, "4321")
    k.check("the master code allows arming stay", d.sent == ["^08,1\$\r\n"], d.sent)
    k.ack(d, "08")
    d.armAway(2, " 4321 ")
    k.check("surrounding spaces are ignored, and any partition works", d.sent.last() == "^09,2\$\r\n", d.sent)
    k.ack(d, "09")

    d.disarm("1234")
    k.check("disarm is unaffected (the UNO checks its own code)", d.sent.last() == "^12,1,1234\$\r\n", d.sent)
    k.ack(d, "12")
    d.bypassZone(3); k.ack(d, "04")
    k.check("other commands are unaffected too", d.sent.last() == "^04,003\$\r\n")

    k.check("the codes never appear in the log", !d.logs*.msg.any { it.contains("4321") || it.contains("0000") }, d.warns())

    def none = k.authed(requireCodeToArm: true)
    none.armStay(1, "1234")
    k.check("with no master code configured, arming is blocked and the reason is logged", none.sent.isEmpty() && none.warns().any { it.contains("no master code is configured") }, none.warns())
}

k.section("Zone bypass commands") {
    def d = k.authed()
    d.bypassZone(5)
    k.check("bypassZone(5) => ^04,005\$ (3 digit zone)", d.sent == ["^04,005\$\r\n"], d.sent)
    k.ack(d, "04")
    d.unbypassZone(128)
    k.check("unbypassZone(128) => ^05,128\$", d.sent.last() == "^05,128\$\r\n", d.sent)
    k.ack(d, "05")
    d.bypassOpenZones(2)
    k.check("bypassOpenZones(2) => ^06,2\$", d.sent.last() == "^06,2\$\r\n", d.sent)
    k.ack(d, "06")
    d.unbypassAllZones()
    k.check("unbypassAllZones() => ^07,1\$", d.sent.last() == "^07,1\$\r\n", d.sent)
    k.ack(d, "07")

    int sent = d.sent.size()
    d.bypassZone(0); d.bypassZone(129); d.unbypassZone("x"); d.bypassZone(null)
    k.check("zones outside 1-128 are rejected", d.sent.size() == sent && d.warns().count { it.contains("Zone must be 1 to 128") } == 4, d.warns())
    d.bypassOpenZones(9)
    k.check("bypass commands validate the partition too", d.sent.size() == sent)
}

k.section("Keypresses (command 03)") {
    def d = k.authed()
    d.sendKeys("*71", 2)
    k.check("first key sent as ^03,2,*\$", d.sent == ["^03,2,*\$\r\n"], d.sent)
    k.ack(d, "03")
    k.ack(d, "03")
    k.ack(d, "03")
    k.check("one command per key, in order", d.sent == ["^03,2,*\$\r\n", "^03,2,7\$\r\n", "^03,2,1\$\r\n"], d.sent)
    k.check("queue is empty afterwards", d.state.commandInFlight == null && d.state.commandQueue.isEmpty())

    d.sent.clear()
    d.sendKeys(" 1 2 # ")
    k.check("whitespace is ignored, partition defaults to 1", d.sent == ["^03,1,1\$\r\n"] && d.state.commandQueue*.data == ["1,2", "1,#"], [d.sent, d.state.commandQueue])

    def bad = k.authed()
    bad.sendKeys("12a"); bad.sendKeys(""); bad.sendKeys("1234567890123"); bad.sendKeys(null); bad.sendKeys("12", 9)
    k.check("invalid keys, empty, too long or bad partition are rejected", bad.sent.isEmpty() && bad.warns().size() == 5, bad.warns())
}

k.section("Panic (command 11)") {
    def off = k.authed()
    off.panic("fire")
    k.check("panic is ignored unless enabled", off.sent.isEmpty() && off.warns().any { it.contains("Panic request ignored") }, off.warns())

    def d = k.authed(enablePanic: true)
    d.panic("fire")
    k.check("fire => ^11,1,0\$", d.sent == ["^11,1,0\$\r\n"], d.sent)
    k.check("panic is logged as a warning for the audit trail", d.warns().any { it.contains("PANIC requested") })
    k.ack(d, "11")
    d.panic("Medical")
    k.check("medical (case-insensitive) => ^11,1,1\$", d.sent.last() == "^11,1,1\$\r\n", d.sent)
    k.ack(d, "11")
    d.panic("police", 2)
    k.check("police on partition 2 => ^11,2,2\$", d.sent.last() == "^11,2,2\$\r\n", d.sent)
    k.ack(d, "11")

    int sent = d.sent.size()
    d.panic("burglar"); d.panic("fire", 9)
    k.check("unknown type or bad partition rejected", d.sent.size() == sent)

    def q = k.authed(enablePanic: true)
    q.armStay(); q.armAway(); q.panic("fire")
    k.ack(q, "08")
    k.check("panic jumps ahead of queued commands", q.sent == ["^08,1\$\r\n", "^11,1,0\$\r\n"], q.sent)
    k.ack(q, "11")
    k.check("queued commands resume afterwards", q.sent.last() == "^09,1\$\r\n", q.sent)

    def r = k.authed(enablePanic: true)
    r.panic("fire")
    r.nowMs += 4000
    r.commandTimeoutCheck()
    k.check("a panic with no ack is never re-sent", r.sent.size() == 1 && r.state.commandInFlight == null, r.sent)
    k.check("the timeout says it wasn't retried", r.warns().any { it.contains("not retried") }, r.warns())
}

k.section("Secrets are kept out of trace logs") {
    def d = k.newConn(traceLogging: true, password: "s3cretpw", masterCode: "9876")
    d.initialize(); d.parse(k.hex("Login:")); d.parse(k.hex("OK\r\n"))
    k.ack(d, "0C")
    d.disarm("1234")
    d.sendKeys("5678", 1)
    k.ack(d, "12")
    String trace = d.logs.findAll { it.lvl == "debug" && it.msg.startsWith("TX") }*.msg.join("\n")
    k.check("trace log shows the TX traffic", trace.contains("^12,1,"), trace)
    k.check("password never appears in the log", !d.logs*.msg.any { it.contains("s3cretpw") }, trace)
    k.check("disarm code is masked", trace.contains("^12,1,****\$") && !trace.contains("1234"), trace)
    k.check("keypress digits are masked", trace.contains("^03,1,*\$") && !trace.contains("5678"), trace)
    k.check("master code never appears in the log", !d.logs*.msg.any { it.contains("9876") })
}

k.section("REST startup sync") {
    def d = k.newConn(useRestMetadata: true)
    d.httpGetImpl = { Map p -> [status: 200, data: [partitions: [[status: 4], [status: 5]], zoneStatus: [[[n: 1, t: 65535], [n: 2, t: 0], [n: 99, t: 65535]]]]] }
    d.initialize()
    d.restStartupSync()
    k.check("REST partition statuses forwarded per partition", d.parent.partitionStates == [[1, "armedStay"], [2, "armedAway"]], d.parent.partitionStates)
    k.check("connection attribute mirrors partition 1", d.lastAttr("alarmSystemStatus") == "armedStay")
    k.check("REST zone 1 open / zone 2 closed", d.parent.children["uno-zone-1"]?.last("contact") == "open" && d.parent.children["uno-zone-2"]?.last("contact") == "closed")
    k.check("REST zone above the count ignored", d.parent.children["uno-zone-99"] == null)
    k.check("restStartupSync marked ok", d.lastAttr("restStartupSync") == "ok")

    def z = k.newConn(useRestMetadata: true, zoneSet: "2")
    z.httpGetImpl = { Map p -> [status: 200, data: [partitions: [], zoneStatus: [[[n: 1, t: 65535], [n: 2, t: 65535]]]]] }
    z.initialize(); z.restStartupSync()
    k.check("REST sync honours the zone set", z.parent.children.keySet() == ["uno-zone-2"] as Set, z.parent.children.keySet())

    def e = k.newConn(useRestMetadata: true)
    e.httpGetImpl = { Map p -> throw new IOException("boom") }
    e.initialize(); e.parse(k.hex("Login:")); e.parse(k.hex("OK\r\n"))
    k.check("a REST failure doesn't break login", e.lastAttr("connectionState") == "authenticated")
    k.check("failure is reported", e.lastAttr("restStartupSync") == "failed" && e.warns().any { it.contains("REST startup sync failed") }, e.warns())

    def u = k.newConn(useRestMetadata: true)
    u.httpGetImpl = { Map p -> throw new MockHttpException(401) }
    u.initialize(); u.restStartupSync()
    k.check("HTTP 401 is reported as rejected credentials", u.lastAttr("restStartupSync") == "auth_failed" && u.errors().any { it.contains("rejected the REST credentials") }, u.errors())
}
