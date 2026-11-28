// Zone drivers' applyZoneState and new attributes, the partition driver's new commands, the status driver.

k.section("Zone drivers apply a state in one call") {
    [
        ["UNOZoneContactDriver", "contact", "open", "closed", "Zone 4 open"],
        ["UNOZoneMotionDriver", "motion", "active", "inactive", "Zone 4 motion active"],
        ["UNOZoneSmokeDriver", "smoke", "detected", "clear", "Zone 4 smoke detected"],
        ["UNOZoneCODriver", "carbonMonoxide", "detected", "clear", "Zone 4 carbon monoxide detected"],
        ["UNOZoneWaterDriver", "water", "wet", "dry", "Zone 4 water wet"]
    ].each { List c ->
        HubBase z = k.load("drivers/${c[0]}.groovy")
        z.device.deviceNetworkId = "uno-zone-4"
        z.installed()
        k.check("${c[0]}: starts with a healthy battery and supervision", z.lastAttr("batteryStatus") == "ok" && z.lastAttr("supervision") == "ok" && z.lastAttr("tamper") == "clear", z.currentVals)

        z.events.clear()
        z.applyZoneState(true)
        k.check("${c[0]}: active sets ${c[1]} ${c[2]}, the digit and lastFault", z.lastAttr(c[1] as String) == c[2] && z.lastAttr("zoneStateDigit") == "1" && z.lastAttr("lastFault") ==~ /\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}/, z.events)
        k.check("${c[0]}: the event says which zone", z.events.find { it.name == c[1] }.descriptionText == c[4], z.events)

        int faults = z.events.count { it.name == "lastFault" }
        z.applyZoneState(false)
        k.check("${c[0]}: inactive sets ${c[3]} and leaves lastFault alone", z.lastAttr(c[1] as String) == c[3] && z.lastAttr("zoneStateDigit") == "0" && z.events.count { it.name == "lastFault" } == faults)
        k.check("${c[0]}: it is a physical, state-change event", z.events.find { it.name == c[1] }.with { it.type == "physical" && it.isStateChange == true })
    }
}

k.section("Partition driver: arm with bypass") {
    HubBase p = k.load("drivers/UNOPartitionDriver.groovy")
    MockApp app = new MockApp()
    p.parent = app
    p.device.deviceNetworkId = "uno-partition-2"

    p.armStayBypass(); p.armAwayBypass("4321")
    k.check("both are forwarded with the partition and code", app.callArgs == [["armStayBypass", [2, null]], ["armAwayBypass", [2, "4321"]]], app.callArgs)
    k.check("and recorded as the last action", p.lastAttr("lastUserAction") == "armAwayBypass")
}

k.section("Status driver") {
    HubBase s = k.load("drivers/UNOStatusDriver.groovy")
    s.parent = new MockApp()
    s.installed()
    k.check("starts with nothing to report", ["openZones", "bypassedZones", "troubleSummary", "staleSensors"].every { s.lastAttr(it) == "none" }, s.currentVals)
    s.ping()
    k.check("ping asks the panel for a refresh", s.parent.callArgs == [["refreshAll", []]], s.parent.callArgs)
}
k.section("Zone devices are not offered as temperature sensors") {
    // A device with the TemperatureMeasurement capability is listed by Alexa (and any capability picker) as a temperature
    // sensor, so a door contact showed up as one. The attribute stays, for the zones that do report a temperature.
    ["Contact", "Motion", "Smoke", "CO", "Water"].each { String type ->
        String src = new File(k.repo, "drivers/UNOZone${type}Driver.groovy").text
        k.check("${type}: no TemperatureMeasurement capability", !src.contains('capability "TemperatureMeasurement"'))
        k.check("${type}: still declares a temperature attribute", src.contains('attribute "temperature", "number"'))
    }
    k.check("the partition keeps it, as it reports the inside temperature", new File(k.repo, "drivers/UNOPartitionDriver.groovy").text.contains('capability "TemperatureMeasurement"'))
}
