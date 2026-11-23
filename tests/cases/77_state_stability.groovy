// Arming state must not flap while other frames arrive. (Regression guard for envisalink_new #255, where a standing
// trouble made the keypad text alternate and the entity oscillated between arming and armed during the exit delay.)
// The UNO reports the exit delay as its own partition status (%02 code 08), so no text is parsed to decide it.

def bitmap = { List<Integer> zones ->
    byte[] b = new byte[16]
    zones.each { int z -> b[(z - 1).intdiv(8)] |= (1 << ((z - 1) % 8)) }
    b.collect { String.format("%02X", it & 0xFF) }.join()
}

// What the partition device shows for each state the connection driver reports.
def replay = { List states ->
    HubBase p = k.load("drivers/UNOPartitionDriver.groovy")
    p.parent = new MockApp()
    p.installed()
    p.events.clear()
    states.each { p.setAlarmState(it) }
    return p
}

k.section("Arming with a standing trouble does not flap (envisalink_new #255)") {
    def d = k.authed()
    d.parent.zoneNames[2] = "Garage"
    k.feed(d, "%01," + bitmap([2]) + "\$")                  // a zone that stays open
    k.feed(d, "%06,0200000000000000\$")                      // a standing trouble: AC failure
    k.feed(d, "%02,0100000000000000\$")                      // ready
    d.parent.partitionStates.clear()
    d.events.clear()

    k.feed(d, "%02,0800000000000000\$")                      // exit delay begins

    // The panel keeps sending the other frames for the whole exit delay: the trouble, the zones, sounds,
    // host information with each poll, the chime and the exit delay status itself.
    12.times { n ->
        k.feed(d, "%06,0200000000000000\$")
        k.feed(d, "%01," + bitmap([2]) + "\$")
        k.feed(d, "%09,1,B,01\$")
        k.feed(d, "%0A,1,1,0,0\$")
        k.feed(d, "%10,00000000\$")
        k.feed(d, "%05,001122334455,UNO,1.0.1,1,12:00\$")
        k.feed(d, "%02,0800000000000000\$")
        k.feed(d, "%03,1301010000\$")                         // the trouble reported again as an event
        k.feed(d, "%03,3441010020\$")                         // the arm itself, closing by user 2
    }

    k.feed(d, "%02,0400000000000000\$")                      // armed stay

    k.check("the partition goes exit delay then armed, exactly once each", d.parent.partitionStates == [[1, "exitDelay"], [1, "armedStay"]], d.parent.partitionStates)
    List shown = d.events.findAll { it.name == "alarmSystemStatus" }*.value
    k.check("and so does the connection device's own status", shown == ["exitDelay", "armedStay"], shown)

    def p = replay(["exitDelay", "armedStay"])
    List sec = p.events.findAll { it.name == "securitySystemStatus" }*.value
    k.check("the dashboard sees arming then armed home, with no oscillation", sec == ["arming", "armed home"], sec)
    k.check("ready is false while arming and armed", p.events.findAll { it.name == "ready" }*.value == ["false", "false"])
    k.check("the keypad keeps its last stable value through the exit delay, then changes once", p.events.findAll { it.name == "securityKeypad" }*.value == ["armed home"], p.events.findAll { it.name == "securityKeypad" }*.value)
    k.check("and the arm mode is known", p.lastAttr("lastArmMode") == "stay")
}

k.section("The trouble state itself stays steady while the panel repeats it") {
    def d = k.authed()
    k.feed(d, "%06,0200000000000000\$")
    int changes = d.events.count { it.name in ["troubleState", "acFailure", "acPower", "troubleDetail"] }
    10.times { k.feed(d, "%06,0200000000000000\$") }
    k.check("repeating the same trouble frame changes nothing", d.events.count { it.name in ["troubleState", "acFailure", "acPower", "troubleDetail"] } == changes)
    k.check("and the trouble is reported", d.lastAttr("troubleState") == "trouble" && d.lastAttr("acFailure") == "active")
}

k.section("Arm away, entry delay and alarm with a trouble standing") {
    def d = k.authed()
    k.feed(d, "%06,0400000000000000\$")                      // low battery trouble
    k.feed(d, "%02,0100000000000000\$")
    d.parent.partitionStates.clear()

    k.feed(d, "%02,0800000000000000\$")
    8.times { k.feed(d, "%06,0400000000000000\$"); k.feed(d, "%02,0800000000000000\$") }
    k.feed(d, "%02,0500000000000000\$")                      // armed away
    5.times { k.feed(d, "%06,0400000000000000\$") }
    k.feed(d, "%02,0C00000000000000\$")                      // entry delay
    4.times { k.feed(d, "%06,0400000000000000\$"); k.feed(d, "%02,0C00000000000000\$") }
    k.feed(d, "%02,1100000000000000\$")                      // alarm
    k.feed(d, "%06,0400000000000000\$")
    k.feed(d, "%02,0100000000000000\$")                      // disarmed

    List states = d.parent.partitionStates*.getAt(1)
    k.check("each state is reported once, in order, whatever else arrives", states == ["exitDelay", "armedAway", "entryDelay", "alarm", "disarmed"], states)

    def p = replay(states)
    k.check("the partition device shows the same unbroken path", p.events.findAll { it.name == "securitySystemStatus" }*.value == ["arming", "armed away", "arming", "alarm", "disarmed"], p.events.findAll { it.name == "securitySystemStatus" }*.value)
}

k.section("A disarm during the exit delay") {
    def d = k.authed()
    k.feed(d, "%02,0100000000000000\$")
    d.parent.partitionStates.clear()
    k.feed(d, "%02,0800000000000000\$")
    3.times { k.feed(d, "%06,0200000000000000\$") }
    k.feed(d, "%02,0100000000000000\$")
    k.check("goes straight back to disarmed", d.parent.partitionStates*.getAt(1) == ["exitDelay", "disarmed"], d.parent.partitionStates)
    def p = replay(["exitDelay", "disarmed"])
    k.check("with no armed state in between", p.events.findAll { it.name == "securitySystemStatus" }*.value == ["arming", "disarmed"] && p.events.every { it.value != "armed home" })
}

k.section("Several partitions in different states don't disturb each other") {
    def d = k.authed()
    k.feed(d, "%02,0100000000000000\$")
    d.parent.partitionStates.clear()
    k.feed(d, "%02,0801000000000000\$")                      // 1 exit delay, 2 ready
    k.feed(d, "%06,0200000000000000\$")
    k.feed(d, "%02,0801000000000000\$")
    k.feed(d, "%02,0804000000000000\$")                      // 2 armed stay
    k.feed(d, "%02,0404000000000000\$")                      // 1 armed stay
    k.check("each partition reports only its own changes", d.parent.partitionStates == [[1, "exitDelay"], [2, "disarmed"], [2, "armedStay"], [1, "armedStay"]], d.parent.partitionStates)
}
