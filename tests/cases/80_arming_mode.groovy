// The exit delay doesn't say whether the arm is stay or away. A dashboard must not show the previous arm's mode while it
// counts down. (Regression guard for envisalink_new #191, where a DSC arm to Home showed Away until the delay ended.)

// What the partition device shows, replaying the events the connection driver forwarded to partition 1.
def partitionView = { HubBase conn, int partition ->
    HubBase p = k.load("drivers/UNOPartitionDriver.groovy")
    p.parent = new MockApp()
    p.installed()
    p.events.clear()
    conn.parent.partitionStates.findAll { it[0] == partition }.each { p.setAlarmState(it[1]) }
    (conn.parent.partitionEvents[partition] ?: []).each { p.sendEvent(it) }
    return p
}
def modes = { HubBase conn, int partition -> (conn.parent.partitionEvents[partition] ?: []).findAll { it.name == "armingMode" }*.value }

k.section("The mode of an exit delay is what was requested") {
    def d = k.authed()
    k.feed(d, "%02,0100000000000000\$")
    d.armStay()
    k.ack(d, "08")
    d.parent.partitionEvents.clear()
    k.feed(d, "%02,0800000000000000\$")
    k.check("a stay arm: the exit delay says stay", modes(d, 1) == ["stay"], modes(d, 1))
    k.feed(d, "%02,0400000000000000\$")
    k.check("and it clears once armed", modes(d, 1) == ["stay", "none"], modes(d, 1))

    k.feed(d, "%02,0100000000000000\$")
    d.armAway()
    k.ack(d, "09")
    d.parent.partitionEvents.clear()
    k.feed(d, "%02,0800000000000000\$")
    k.check("an away arm: the exit delay says away", modes(d, 1) == ["away"], modes(d, 1))
    k.feed(d, "%02,0500000000000000\$")
    k.check("and it clears", modes(d, 1) == ["away", "none"])
}

k.section("The previous arm's mode is not shown during the next exit delay") {
    def d = k.authed()
    k.feed(d, "%02,0100000000000000\$")
    d.armAway(); k.ack(d, "09")
    k.feed(d, "%02,0800000000000000\$"); k.feed(d, "%02,0500000000000000\$")     // armed away
    k.feed(d, "%02,0100000000000000\$")                                           // disarmed

    d.armStay(); k.ack(d, "08")
    k.feed(d, "%02,0800000000000000\$")                                           // exit delay for a STAY arm

    def p = partitionView(d, 1)
    k.check("lastArmMode still says the last COMPLETED arm (away)", p.lastAttr("lastArmMode") == "away", p.lastAttr("lastArmMode"))
    k.check("but armingMode says what is happening now", p.lastAttr("armingMode") == "stay", p.lastAttr("armingMode"))
    k.check("and the dashboard shows arming, not armed away", p.lastAttr("securitySystemStatus") == "arming" && p.lastAttr("alarm") == "off")
    k.feed(d, "%02,0400000000000000\$")
    def done = partitionView(d, 1)
    k.check("when it arms, lastArmMode is the new mode and armingMode is none", done.lastAttr("lastArmMode") == "stay" && done.lastAttr("armingMode") == "none", [done.lastAttr("lastArmMode"), done.lastAttr("armingMode")])
}

k.section("When the mode isn't known it says so") {
    def d = k.authed()
    k.feed(d, "%02,0100000000000000\$")
    d.parent.partitionEvents.clear()
    k.feed(d, "%02,0800000000000000\$")
    k.check("an arm from a keypad: unknown", modes(d, 1) == ["unknown"], modes(d, 1))

    def s = k.authed()
    s.armStay(); k.ack(s, "08")
    s.nowMs += 301000L
    s.parent.partitionEvents.clear()
    k.feed(s, "%02,0800000000000000\$")
    k.check("a request from more than 5 minutes ago belongs to some earlier arm", modes(s, 1) == ["unknown"], modes(s, 1))

    def r = k.authed()
    r.armStay()
    k.ack(r, "08", "6")                                    // the UNO refused: partition not ready
    r.parent.partitionEvents.clear()
    k.feed(r, "%02,0800000000000000\$")
    k.check("a refused arm isn't a request", modes(r, 1) == ["unknown"], modes(r, 1))

    def u = k.authed()
    u.armStay()
    u.parent.partitionEvents.clear()
    k.feed(u, "%02,0800000000000000\$")
    k.check("a command still waiting for its acknowledgement isn't one either (yet)", modes(u, 1) == ["unknown"])
}

k.section("A late acknowledgement fixes it up") {
    def d = k.authed()
    d.armAway()
    d.parent.partitionEvents.clear()
    k.feed(d, "%02,0800000000000000\$")                    // the exit delay arrives before the acknowledgement
    k.check("first it can only say unknown", modes(d, 1) == ["unknown"], modes(d, 1))
    k.ack(d, "09")
    k.check("then the acknowledgement says away", modes(d, 1) == ["unknown", "away"], modes(d, 1))
}

k.section("Disarming during the exit delay") {
    def d = k.authed()
    d.armStay(); k.ack(d, "08")
    k.feed(d, "%02,0800000000000000\$")
    d.parent.partitionEvents.clear()
    k.feed(d, "%02,0100000000000000\$")
    k.check("clears the arming mode", modes(d, 1) == ["none"], modes(d, 1))
}

k.section("A request belongs to one arm") {
    def d = k.authed()
    d.armStay(); k.ack(d, "08")
    k.feed(d, "%02,0800000000000000\$")
    k.feed(d, "%02,0100000000000000\$")                    // cancelled during the exit delay
    d.nowMs += 60000L
    d.parent.partitionEvents.clear()
    k.feed(d, "%02,0800000000000000\$")                    // a minute later someone arms from a keypad
    k.check("a cancelled arm's request isn't used for the next one", modes(d, 1) == ["unknown"], modes(d, 1))

    def f = k.authed()
    f.armStay(); k.ack(f, "08")
    k.feed(f, "%02,0400000000000000\$")                    // armed at once, no exit delay at all
    k.feed(f, "%02,0100000000000000\$")
    f.parent.partitionEvents.clear()
    k.feed(f, "%02,0800000000000000\$")
    k.check("nor is an arm that finished without an exit delay", modes(f, 1) == ["unknown"], modes(f, 1))

    def l = k.authed()
    l.armStay()
    k.feed(l, "%02,0400000000000000\$")                    // armed before the acknowledgement arrived
    k.ack(l, "08")
    k.feed(l, "%02,0100000000000000\$")
    l.parent.partitionEvents.clear()
    k.feed(l, "%02,0800000000000000\$")
    k.check("a late acknowledgement for an arm that already finished isn't kept", modes(l, 1) == ["unknown"], modes(l, 1))
}

k.section("A stay-arm Contact ID event names the mode of a keypad arm") {
    def d = k.authed()
    k.feed(d, "%02,0100000000000000\$")
    k.feed(d, "%03,3441010020\$")                          // armed stay, user 2
    d.parent.partitionEvents.clear()
    k.feed(d, "%02,0800000000000000\$")
    k.check("so the exit delay says stay", modes(d, 1) == ["stay"], modes(d, 1))

    def o = k.authed()
    k.feed(o, "%03,3401010020\$")                          // generic open/close: no mode
    o.parent.partitionEvents.clear()
    k.feed(o, "%02,0800000000000000\$")
    k.check("a generic arm event doesn't guess", modes(o, 1) == ["unknown"], modes(o, 1))
}

k.section("Each partition has its own") {
    def d = k.authed()
    d.armStay(2); k.ack(d, "08")
    d.armAway(1)
    k.ack(d, "09")
    d.parent.partitionEvents.clear()
    k.feed(d, "%02,0808000000000000\$")
    k.check("partition 1 away, partition 2 stay", modes(d, 1) == ["away"] && modes(d, 2) == ["stay"], [modes(d, 1), modes(d, 2)])
}

k.section("The mode isn't kept across a reconnect") {
    def d = k.authed()
    d.armStay(); k.ack(d, "08")
    d.initialize(); d.parse(k.hex("Login:")); d.parse(k.hex("OK\r\n"))
    d.parent.partitionEvents.clear()
    k.feed(d, "%02,0800000000000000\$")
    k.check("a request from before the reconnect is forgotten", modes(d, 1) == ["unknown"], modes(d, 1))
}

k.section("Partition device") {
    HubBase p = k.load("drivers/UNOPartitionDriver.groovy")
    p.parent = new MockApp()
    p.installed()
    k.check("starts with no arming mode", p.lastAttr("armingMode") == "none")
}
