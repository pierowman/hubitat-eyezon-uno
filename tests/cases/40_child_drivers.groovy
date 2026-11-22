// Partition and zone child drivers.

k.section("Partition driver: state mapping") {
    HubBase p = k.load("drivers/UNOPartitionDriver.groovy")
    p.parent = new MockApp()
    p.installed()
    p.setAlarmState("armedAwayZeroEntry")
    k.check("armedAwayZeroEntry => armed away", p.lastAttr("securitySystemStatus") == "armed away")
    p.setAlarmState("busy")
    k.check("busy => busy", p.lastAttr("securitySystemStatus") == "busy")
    p.setAlarmState("alarm")
    k.check("alarm => alarm capability both", p.lastAttr("alarm") == "both")
    p.setAlarmState("disarmed")
    k.check("disarmed => ready", p.lastAttr("ready") == "true" && p.lastAttr("alarm") == "off")
    p.setAlarmState("readyBypassed")
    k.check("ready-with-bypass still counts as ready", p.lastAttr("ready") == "true")
    p.setAlarmState("notReady")
    k.check("not ready => ready=false", p.lastAttr("ready") == "false" && p.lastAttr("securitySystemStatus") == "not ready")
    p.setAlarmState("armedStay")
    k.check("armed => ready=false", p.lastAttr("ready") == "false" && p.lastAttr("securitySystemStatus") == "armed home")
}

k.section("Partition driver: commands carry its own partition number") {
    HubBase p = k.load("drivers/UNOPartitionDriver.groovy")
    MockApp app = new MockApp()
    p.parent = app
    p.device.deviceNetworkId = "uno-partition-3"

    p.armStay(); p.armAway(); p.disarm("1234"); p.off(); p.chimeOn(); p.chimeOff()
    p.bypassOpenZones(); p.unbypassAllZones(); p.sendKeys("*71"); p.refresh()
    p.panicFire(); p.panicMedical(); p.panicPolice()

    List expected = [
        ["armStay", [3]], ["armAway", [3]], ["disarm", ["1234", 3]], ["disarm", [null, 3]],
        ["chimeOn", [3]], ["chimeOff", [3]], ["bypassOpenZones", [3]], ["unbypassAllZones", [3]],
        ["sendKeys", ["*71", 3]], ["refreshAll", []],
        ["panic", ["fire", 3]], ["panic", ["medical", 3]], ["panic", ["police", 3]]
    ]
    k.check("each command is forwarded with partition 3", app.callArgs == expected, app.callArgs)
    k.check("panic is recorded as the last user action", p.lastAttr("lastUserAction") == "panic:police")

    HubBase d = k.load("drivers/UNOPartitionDriver.groovy")
    MockApp app2 = new MockApp(); d.parent = app2
    d.armStay()
    k.check("a device without a uno-partition-N id falls back to partition 1", app2.callArgs.last() == ["armStay", [1]], app2.callArgs)
}

k.section("Zone drivers: bypass commands and attributes") {
    ["UNOZoneContactDriver": "contact", "UNOZoneMotionDriver": "motion", "UNOZoneSmokeDriver": "smoke",
     "UNOZoneCODriver": "carbonMonoxide", "UNOZoneWaterDriver": "water"].each { file, attr ->
        HubBase z = k.load("drivers/${file}.groovy")
        MockApp app = new MockApp()
        z.parent = app
        z.device.deviceNetworkId = "uno-zone-12"

        z.installed()
        k.check("${file}: installed sets ${attr}, zone digit and bypassed", z.lastAttr(attr) != null && z.lastAttr("zoneStateDigit") == "0" && z.lastAttr("bypassed") == "false", z.currentVals)
        z.bypass(); z.unbypass()
        k.check("${file}: bypass/unbypass use the zone number from the device id", app.callArgs == [["bypassZone", [12]], ["unbypassZone", [12]]], app.callArgs)
    }
}
