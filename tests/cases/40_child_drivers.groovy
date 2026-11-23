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

k.section("Partition driver: arming with a code") {
    HubBase p = k.load("drivers/UNOPartitionDriver.groovy")
    MockApp app = new MockApp(); p.parent = app
    p.device.deviceNetworkId = "uno-partition-2"
    p.armStayWithCode("4321"); p.armAwayWithCode("4321")
    k.check("the WithCode commands pass the code and this partition to the app", app.callArgs == [["armStay", [2, "4321"]], ["armAway", [2, "4321"]]], app.callArgs)
}

k.section("Partition driver: SecurityKeypad capability") {
    HubBase p = k.load("drivers/UNOPartitionDriver.groovy")
    MockApp app = new MockApp(); p.parent = app
    p.device.deviceNetworkId = "uno-partition-3"
    p.installed()
    k.check("installed sets the keypad attributes", p.lastAttr("securityKeypad") == "unknown" && p.lastAttr("codeLength") == "6" && p.lastAttr("maxCodes") == "0" && p.lastAttr("lockCodes") == "{}", p.currentVals)

    Map expected = ["disarmed": "disarmed", "readyBypassed": "disarmed", "notReady": "disarmed", "armedStay": "armed home",
                    "armedAway": "armed away", "armedAwayZeroEntry": "armed away", "unused": "unknown"]
    expected.each { status, want ->
        p.setAlarmState(status)
        k.check("${status} => securityKeypad '${want}'", p.lastAttr("securityKeypad") == want, p.lastAttr("securityKeypad"))
    }

    p.setAlarmState("armedAway")
    ["exitDelay", "entryDelay", "alarm", "busy"].each { status ->
        p.setAlarmState(status)
        k.check("${status} keeps the last stable keypad state", p.lastAttr("securityKeypad") == "armed away")
    }

    // HSM and dashboard entry points
    HubBase h = k.load("drivers/UNOPartitionDriver.groovy")
    MockApp hApp = new MockApp(); h.parent = hApp
    h.device.deviceNetworkId = "uno-partition-3"
    h.setAlarmState("disarmed")

    h.armHome(0)
    k.check("armHome(delay) arms stay on this partition", hApp.callArgs.last() == ["armStay", [3]], hApp.callArgs)
    h.setAlarmState("armedStay")
    int calls = hApp.callArgs.size()
    h.armHome(0)
    k.check("a repeat armHome (HSM sends it twice) is skipped", hApp.callArgs.size() == calls)

    h.armAway(5)
    k.check("armAway(delay) arms away", hApp.callArgs.last() == ["armAway", [3]], hApp.callArgs)
    h.setAlarmState("armedAway")
    calls = hApp.callArgs.size()
    h.armAway(5)
    k.check("a repeat armAway is skipped", hApp.callArgs.size() == calls)

    h.setAlarmState("armedStay")
    h.armNight(0)
    k.check("armNight is skipped when already armed home (night maps to stay)", hApp.callArgs.size() == calls)
    h.setAlarmState("disarmed")
    h.armNight(0)
    k.check("armNight arms stay", hApp.callArgs.last() == ["armStay", [3]], hApp.callArgs)

    h.setAlarmState("armedAway")
    h.disarm(0)
    k.check("disarm(delay) from HSM disarms with the master code", hApp.callArgs.last() == ["disarm", [null, 3]], hApp.callArgs)
    h.setAlarmState("disarmed")
    calls = hApp.callArgs.size()
    h.disarm(0)
    k.check("a repeat disarm from HSM is skipped", hApp.callArgs.size() == calls)
    h.disarm("1234")
    k.check("a disarm with a code is always sent", hApp.callArgs.last() == ["disarm", ["1234", 3]], hApp.callArgs)

    h.setExitDelay([awayDelay: 5]); h.setEntryDelay(30)
    h.setCode(1, "1234", "Me"); h.deleteCode(1); h.setCodeLength(4)
    k.check("code management is refused with an explanation (codes live on the UNO)", h.warns().count { it.contains("managed on the UNO") } == 3, h.warns())
    h.getCodes()
    k.check("getCodes reports an empty list", h.lastAttr("lockCodes") == "{}")
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
