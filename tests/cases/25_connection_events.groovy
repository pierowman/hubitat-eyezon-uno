// Contact ID semantics: who armed or disarmed, readable alarm and trouble text, history and partition attributes.

k.section("Spec example: CID 3441010020") {
    def d = k.authed()
    k.feed(d, "%03,3441010020\$")   // restoral (closing), armed stay, partition 1, user 2
    k.check("it is described as an arm by user 2", d.lastAttr("lastEvent") == "Armed (stay) by user 2" && d.lastAttr("lastEventType") == "arm", d.lastAttr("lastEvent"))
    k.check("partition 1 records who armed it and when",
            d.parent.partitionAttr(1, "lastArmedBy") == "user 2" && d.parent.partitionAttr(1, "lastArmedAt") ==~ /\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}/, d.parent.partitionEvents)
    k.check("the raw summary is unchanged", d.lastAttr("lastCidEvent") == "CID q=3 code=441 partition=1 zoneOrUser=2")
    def notified = d.parent.callArgs.find { it[0] == "notifyEvent" }
    k.check("the app is asked to notify, with the category, text, partition and whether it is a restoral",
            notified?.get(1)?.get(0) == [category: "arm", text: "Armed (stay) by user 2", partition: 1, restore: true], d.parent.callArgs)
}

k.section("Arm and disarm") {
    def d = k.authed()
    d.parent.userNames[2] = "Alice"
    k.feed(d, "%03,3401010020\$")
    k.check("401 closing = armed, with the user's name", d.lastAttr("lastEvent") == "Armed by Alice" && d.parent.partitionAttr(1, "lastArmedBy") == "Alice", d.lastAttr("lastEvent"))
    k.feed(d, "%03,1401010020\$")
    k.check("401 opening = disarmed", d.lastAttr("lastEvent") == "Disarmed by Alice" && d.lastAttr("lastEventType") == "disarm", d.lastAttr("lastEvent"))
    k.check("partition 1 records who disarmed it", d.parent.partitionAttr(1, "lastDisarmedBy") == "Alice" && d.parent.partitionAttr(1, "lastDisarmedAt") != null)

    k.feed(d, "%03,3401010030\$")
    k.check("an unnamed user is shown by number", d.lastAttr("lastEvent") == "Armed by user 3", d.lastAttr("lastEvent"))
    k.feed(d, "%03,3403010000\$")
    k.check("user 0 is the system", d.lastAttr("lastEvent") == "Armed (automatic) by the system", d.lastAttr("lastEvent"))
    k.feed(d, "%03,3407010050\$")
    k.check("remote arming says so", d.lastAttr("lastEvent") == "Armed (remote) by user 5", d.lastAttr("lastEvent"))
    k.feed(d, "%03,1441010020\$")
    k.check("opening after a stay arm is a disarm", d.lastAttr("lastEvent") == "Disarmed by Alice")
    k.feed(d, "%03,3442010020\$")
    k.check("keyswitch stay arm", d.lastAttr("lastEvent") == "Armed (keyswitch, stay) by Alice", d.lastAttr("lastEvent"))

    def p2 = k.authed()
    k.feed(p2, "%03,3401020020\$")
    k.check("an arm on partition 2 goes to partition 2 only", p2.parent.partitionAttr(2, "lastArmedBy") == "user 2" && p2.parent.partitionAttr(1, "lastArmedBy") == null, p2.parent.partitionEvents)

    def bad = k.authed()
    k.feed(bad, "%03,3401090020\$")
    k.check("a partition outside 1-8 is recorded but not forwarded", bad.lastAttr("lastEvent") == "Armed by user 2" && bad.parent.partitionEvents.isEmpty())
}

k.section("Alarms, troubles and other events in words") {
    def d = k.authed(zoneCount: 27)
    d.parent.zoneNames[5] = "Front Door"
    k.feed(d, "%03,1130010050\$")
    k.check("alarm with the zone's name", d.lastAttr("lastEvent") == "Burglary alarm: Front Door (zone 5)" && d.lastAttr("lastEventType") == "alarm", d.lastAttr("lastEvent"))
    k.feed(d, "%03,3130010050\$")
    k.check("alarm restored", d.lastAttr("lastEvent") == "Burglary alarm restored: Front Door (zone 5)", d.lastAttr("lastEvent"))
    k.feed(d, "%03,1162010090\$")
    k.check("an unnamed zone shows its number", d.lastAttr("lastEvent") == "Carbon monoxide detected: zone 9", d.lastAttr("lastEvent"))
    k.check("describing the event doesn't stop the zone fast path", d.parent.children["uno-zone-5"]?.last("contact") == "closed" && d.parent.children["uno-zone-9"] != null)

    k.feed(d, "%03,1301010000\$")
    k.check("system trouble has no zone", d.lastAttr("lastEvent") == "AC power loss" && d.lastAttr("lastEventType") == "trouble", d.lastAttr("lastEvent"))
    k.feed(d, "%03,3301010000\$")
    k.check("system trouble restored", d.lastAttr("lastEvent") == "AC power loss restored")
    k.feed(d, "%03,1384010070\$")
    k.check("a sensor trouble names the sensor", d.lastAttr("lastEvent") == "Sensor low battery: zone 7", d.lastAttr("lastEvent"))
    k.feed(d, "%03,1570010030\$")
    k.check("bypass", d.lastAttr("lastEvent") == "Zone bypassed: zone 3" && d.lastAttr("lastEventType") == "bypass", d.lastAttr("lastEvent"))
    k.feed(d, "%03,1461010040\$")
    k.check("a wrong code entry is an access event with the user field", d.lastAttr("lastEvent") == "Wrong code entered by user 4" && d.lastAttr("lastEventType") == "access", d.lastAttr("lastEvent"))
    k.feed(d, "%03,1454010020\$")
    k.check("an exception such as failed to close", d.lastAttr("lastEvent") == "Failed to close by user 2" && d.lastAttr("lastEventType") == "exception", d.lastAttr("lastEvent"))
    k.feed(d, "%03,1999010000\$")
    k.check("an unknown code is still reported", d.lastAttr("lastEvent") == "Event 999" && d.lastAttr("lastEventType") == "other", d.lastAttr("lastEvent"))
    k.feed(d, "%03,6130010050\$")
    k.check("a qualifier other than 1 or 3 is not described", d.lastAttr("lastEvent") == "Event 999")
}

k.section("Event history") {
    def d = k.authed()
    k.check("starts empty", d.lastAttr("eventHistory") == "[]" || d.lastAttr("eventHistory") == null)
    (1..12).each { n -> k.feed(d, "%03,1301010000\$"); k.feed(d, "%03,1401010${String.format('%02d', n)}0\$") }
    List shown = new groovy.json.JsonSlurper().parseText(d.lastAttr("eventHistory"))
    k.check("the attribute shows the newest 10, newest first", shown.size() == 10 && shown[0].text == "Disarmed by user 12" && shown[0].type == "disarm", shown)
    k.check("each entry has a time, partition, code, type and text", shown[0].keySet() == ["at", "partition", "code", "type", "text"] as Set && shown[0].partition == 1 && shown[0].code == "401", shown[0])
    k.check("state keeps the last 25", d.state.eventHistory.size() == 24)
    (1..5).each { k.feed(d, "%03,1301010000\$") }
    k.check("history never grows past 25", d.state.eventHistory.size() == 25)

    d.clearEventHistory()
    k.check("clearEventHistory empties it", d.lastAttr("eventHistory") == "[]" && d.state.eventHistory.isEmpty())

    def spammy = k.authed()
    (1..10).each { k.feed(spammy, "%03,1130010050\$") }
    k.check("the attribute stays a reasonable size", spammy.lastAttr("eventHistory").length() < 2000, spammy.lastAttr("eventHistory").length())

    def kept = k.authed()
    k.feed(kept, "%03,1301010000\$")
    kept.initialize()
    k.check("history survives re-initialising", kept.state.eventHistory.size() == 1)
}

k.section("Location events for automations") {
    def d = k.authed()
    d.parent.userNames[2] = "Alice"
    k.feed(d, "%03,3401010020\$")
    def e = d.locationEvents.find { it.name == "unoEvent" }
    k.check("an unoEvent is published with category:code", e?.value == "arm:401", d.locationEvents)
    k.check("with the description", e.descriptionText == "Armed by Alice")
    Map data = new groovy.json.JsonSlurper().parseText(e.data)
    k.check("and details as JSON", data.partition == 1 && data.code == "401" && data.qualifier == "3" && data.category == "arm" && data.zoneOrUser == 2, data)

    d.locationEvents.clear()
    k.feed(d, "%03,1130010050\$")
    k.check("zone alarms publish both the zone event and the semantic event", d.locationEvents*.name.sort() == ["unoEvent", "unoZoneEvent"], d.locationEvents*.name)
    k.check("the unoEvent value for an alarm", d.locationEvents.find { it.name == "unoEvent" }.value == "alarm:130")
}

k.section("Describing never breaks event handling") {
    def d = k.authed()
    d.parent.metaClass.userName = { Integer u -> throw new IllegalStateException("boom") }
    k.feed(d, "%03,3401010020\$")
    k.check("a failing name lookup falls back to the user number", d.lastAttr("lastEvent") == "Armed by user 2", d.lastAttr("lastEvent"))
}

k.section("Partition attributes follow the state") {
    HubBase p = k.load("drivers/UNOPartitionDriver.groovy")
    p.parent = new MockApp()
    p.setAlarmState("armedStay")
    k.check("lastArmMode is stay", p.lastAttr("lastArmMode") == "stay")
    p.setAlarmState("armedAway")
    k.check("lastArmMode is away", p.lastAttr("lastArmMode") == "away")
    p.setAlarmState("exitDelay")
    p.setAlarmState("disarmed")
    k.check("lastArmMode is kept when disarmed", p.lastAttr("lastArmMode") == "away")
    p.setAlarmState("armedAwayZeroEntry")
    k.check("zero-entry away counts as away", p.lastAttr("lastArmMode") == "away")
}

k.section("Event table sanity") {
    def d = k.newConn()
    Map table = d.CID_EVENTS
    k.check("the table has a good spread of codes", table.size() > 100, table.size())
    k.check("every entry is [category, text] with a known category",
            table.every { code, v -> code ==~ /\d{3}/ && v.size() == 2 && v[0] in ["alarm", "supervisory", "trouble", "arm", "exception", "access", "bypass", "test"] && v[1] }, table.findAll { c, v -> !(v.size() == 2) })
    k.check("every code the zone fast path uses is described", ["110", "113", "130", "131", "132", "134", "137", "144", "154", "162"].every { table.containsKey(it) })
    k.check("every arm code has an arm hint", d.CID_ARM_SUFFIX.keySet().every { table.containsKey(it) })
}
