// App and connection driver together: the app decides what the devices are, the connection device creates the
// partitions and zones as its own children. (Run with newPair; `conn.save()` is what the hub does after Done.)

k.section("Zone discovery and hints") {
    def (a, conn, env) = k.newPair([zoneHints: "2:Dining Room PIR:motion\n3:Front Door:contact\n4:Kitchen Smoke:smoke\n# comment\nbad line"])
    conn.save()
    Map kids = conn.host.children
    k.check("the connection device makes the partition and 5 zones", kids.size() == 6, kids.keySet())
    k.check("and the app makes none of them", env.children.keySet() == ["uno-connection"] as Set, env.children.keySet())
    k.check("zone 1 default contact", kids["uno-zone-1"].typeName == "UNO Zone Contact")
    k.check("zone 2 motion with label", kids["uno-zone-2"].typeName == "UNO Zone Motion" && kids["uno-zone-2"].label == "Dining Room PIR")
    k.check("zone 4 smoke", kids["uno-zone-4"].typeName == "UNO Zone Smoke")
    k.check("partition 1 gets the default name", kids["uno-partition-1"].label == "House Alarm")
    k.check("connection settings pushed", env.children["uno-connection"].settingsUpdated.any { it[0] == "ip" })
    conn.host.deleted.clear()
    conn.save()
    k.check("re-initialize doesn't delete/recreate zones", conn.host.deleted.isEmpty(), conn.host.deleted)
}

k.section("REST labels and reconcile don't flip-flop") {
    def (a, conn, env) = k.newPair([useRestMetadata: true, zoneCount: 3],
        ["zone labels": ["Kitchen CO", "Hall PIR", "Front Door"], "partition labels": ["Main"], "system name": "My UNO"])
    conn.save()
    Map kids = conn.host.children
    k.check("'Kitchen CO' => CO driver", kids["uno-zone-1"].typeName == "UNO Zone CO", kids["uno-zone-1"].typeName)
    k.check("'Hall PIR' => motion", kids["uno-zone-2"].typeName == "UNO Zone Motion")
    conn.host.deleted.clear(); conn.host.created.clear()
    conn.save()
    k.check("save/update doesn't recreate inferred zones", conn.host.deleted.isEmpty() && conn.host.created.isEmpty(), [conn.host.deleted, conn.host.created])
}

k.section("CID suggestions are stable") {
    def (a, conn, env) = k.newPair([zoneCount: 8, zoneHints: "7:Garage:contact"])
    conn.save(); conn.host.deleted.clear()
    Map kids = conn.host.children
    conn.ensureZoneFor(6, "smoke")
    k.check("a default-type zone is upgraded once", kids["uno-zone-6"].typeName == "UNO Zone Smoke" && conn.host.deleted == ["uno-zone-6"], conn.host.deleted)
    conn.ensureZoneFor(6, null); conn.ensureZoneFor(6, null)
    k.check("later events don't flip it back", kids["uno-zone-6"].typeName == "UNO Zone Smoke" && conn.host.deleted.size() == 1, conn.host.deleted)
    conn.ensureZoneFor(6, "water")
    k.check("an already-specific zone isn't re-typed", kids["uno-zone-6"].typeName == "UNO Zone Smoke")
    conn.ensureZoneFor(7, "smoke")
    k.check("an explicit hint beats a CID suggestion", kids["uno-zone-7"].typeName == "UNO Zone Contact")
    conn.ensureZoneFor(0, null); conn.ensureZoneFor(null, null)
    k.check("zone 0 / null ignored", kids.keySet().every { !it.endsWith("zone-0") && !it.endsWith("zone-null") })
}

k.section("Hint changes still apply") {
    def (a, conn, env) = k.newPair([zoneCount: 3])
    conn.save()
    Map kids = conn.host.children
    a.settings.zoneHints = "1:Basement Leak:water"
    conn.save()
    k.check("a new hint recreates the zone with the new driver", kids["uno-zone-1"].typeName == "UNO Zone Water" && kids["uno-zone-1"].label == "Basement Leak")
    a.settings.zoneHints = "1:Basement Sensor:contact"
    conn.save()
    k.check("changing the hint back recreates it again", kids["uno-zone-1"].typeName == "UNO Zone Contact")
    a.settings.defaultUnknownZoneType = "motion"
    a.settings.zoneHints = ""
    conn.host.deleted.clear()
    conn.save()
    k.check("a hint-less zone on a specific driver is left alone", conn.host.deleted.isEmpty(), conn.host.deleted)
}

k.section("Zone set") {
    def (a, conn, env) = k.newPair([zoneSet: "1-3,5", zoneCount: 27])
    conn.save()
    List zones = conn.host.children.keySet().findAll { it.startsWith("uno-zone-") }.sort()
    k.check("only the zones in the set are created", zones == ["uno-zone-1", "uno-zone-2", "uno-zone-3", "uno-zone-5"], zones)
    k.check("the set is pushed to the connection device", env.children["uno-connection"].settingsUpdated.any { it[0] == "zoneSet" && it[1].value == "1-3,5" })

    def (b, connB, envB) = k.newPair([zoneSet: "", zoneCount: 4])
    connB.save()
    k.check("an empty set falls back to the zone count", connB.host.children.keySet().findAll { it.startsWith("uno-zone-") }.size() == 4)

    def (c, connC, envC) = k.newPair([zoneSet: "garbage", zoneCount: 2])
    connC.save()
    k.check("an invalid set falls back to the zone count", connC.host.children.keySet().findAll { it.startsWith("uno-zone-") }.size() == 2)

    k.check("preview lists the zones", a.rangePreview("1-3,5", 1, 128, "zone", "x").contains("zones: 1, 2, 3, 5 (4)"), a.rangePreview("1-3,5", 1, 128, "zone", "x"))
    k.check("preview reports ignored entries", a.rangePreview("1-3,x,500", 1, 128, "zone", "x").contains("Ignored") && a.rangePreview("1-3,x,500", 1, 128, "zone", "x").contains("x, 500"))
    k.check("preview shows the fallback when blank", a.rangePreview("", 1, 128, "zone", "zones 1-27").contains("Will use zones 1-27"))
}

k.section("Multiple partitions") {
    def (a, conn, env) = k.newPair([partitionSet: "1-2,4", zoneCount: 1, partitionHints: "2:Guest House\n# note\nbad\n4:Shop"])
    conn.save()
    Map kids = conn.host.children
    List parts = kids.keySet().findAll { it.startsWith("uno-partition-") }.sort()
    k.check("a partition device is created for each partition in the set", parts == ["uno-partition-1", "uno-partition-2", "uno-partition-4"], parts)
    k.check("partition 1 default name", kids["uno-partition-1"].label == "House Alarm")
    k.check("hints name the other partitions", kids["uno-partition-2"].label == "Guest House" && kids["uno-partition-4"].label == "Shop")

    def (b, connB, envB) = k.newPair([zoneCount: 1])
    connB.save()
    k.check("no partition set => partition 1 only", connB.host.children.keySet().findAll { it.startsWith("uno-partition-") }.sort() == ["uno-partition-1"])

    def (c, connC, envC) = k.newPair([partitionSet: "9,x", zoneCount: 1])
    connC.save()
    k.check("an invalid partition set falls back to partition 1", connC.host.children.keySet().findAll { it.startsWith("uno-partition-") }.sort() == ["uno-partition-1"])
}

k.section("Partition names") {
    Map rest = ["zone labels": [], "partition labels": ["Main", "Annex"]]
    def (a, conn, env) = k.newPair([useRestMetadata: true, partitionSet: "1-3", zoneCount: 1], rest)
    conn.save()
    Map kids = conn.host.children
    k.check("names come from the UNO when nothing else is set", kids["uno-partition-1"].label == "Main" && kids["uno-partition-2"].label == "Annex")
    k.check("a partition the UNO doesn't name gets a generic name", kids["uno-partition-3"].label == "Partition 3")

    def (b, connB, envB) = k.newPair([useRestMetadata: true, partitionSet: "1-2", partitionLabel: "Home", partitionHints: "2:Cabin", zoneCount: 1], rest)
    connB.save()
    k.check("the partition 1 name setting beats the UNO name", connB.host.children["uno-partition-1"].label == "Home")
    k.check("a partition hint beats the UNO name", connB.host.children["uno-partition-2"].label == "Cabin")

    def (c, connC, envC) = k.newPair([useRestMetadata: true, partitionSet: "1", partitionLabel: "Home", partitionHints: "1:Hint wins", zoneCount: 1], rest)
    connC.save()
    k.check("a hint beats the partition 1 name setting", connC.host.children["uno-partition-1"].label == "Hint wins")

    kids["uno-partition-1"].label = "Renamed by hand"
    conn.save()
    k.check("labels are reapplied on update from the stored UNO names", kids["uno-partition-1"].label == "Main")
}

k.section("Forwarding to the connection and partitions") {
    def (a, conn, env) = k.newPair([zoneCount: 1, partitionSet: "1-2"])
    conn.save()
    env.children["uno-connection"].callArgs.clear()
    a.chimeOn(); a.chimeOff(2); a.armStay(); a.armAway(2); a.refreshAll(); a.disarm(); a.disarm("1234", 2)
    a.bypassZone(7); a.unbypassZone(7); a.bypassOpenZones(2); a.unbypassAllZones(); a.sendKeys("*71", 2); a.panic("fire", 2)
    List calls = env.children["uno-connection"].callArgs
    List expected = [
        ["chimeOn", [1]], ["chimeOff", [2]], ["armStay", [1, null]], ["armAway", [2, null]], ["refreshAll", []],
        ["disarm", [null, 1]], ["disarm", ["1234", 2]],
        ["bypassZone", [7]], ["unbypassZone", [7]], ["bypassOpenZones", [2]], ["unbypassAllZones", [1]],
        ["sendKeys", ["*71", 2]], ["panic", ["fire", 2]]
    ]
    k.check("every command reaches the connection device with its partition", calls == expected, calls)

    a.armStay(2, "4321"); a.armAway(1, "4321")
    k.check("an arm code is passed through", env.children["uno-connection"].callArgs.takeRight(2) == [["armStay", [2, "4321"]], ["armAway", [1, "4321"]]], env.children["uno-connection"].callArgs)

    Map kids = conn.host.children
    conn.sendPartitionState(2, "armedStay")
    k.check("partition state goes to the right partition device", kids["uno-partition-2"].alarmStates == ["armedStay"] && kids["uno-partition-1"].alarmStates == [])
    conn.sendPartitionState(5, "armedAway")
    k.check("a state for a partition without a device is ignored", kids["uno-partition-5"] == null)
    conn.sendPartitionEvents(1, [[name: "acFailure", value: "active"], [name: "troubleState", value: "trouble"]])
    k.check("attribute events reach the partition device", kids["uno-partition-1"].last("acFailure") == "active" && kids["uno-partition-1"].last("troubleState") == "trouble")
    conn.sendPartitionEvents(5, [[name: "x", value: "y"]])
    conn.sendPartitionEvents(1, null)
    k.check("events for a missing partition or a null list are ignored", kids["uno-partition-5"] == null)
}

k.section("Panic setting") {
    def (a, env) = k.newApp([zoneCount: 1])
    a.initialize()
    k.check("panic is off by default", env.children["uno-connection"].settingsUpdated.any { it[0] == "enablePanic" && it[1].value == false })
    a.settings.enablePanic = true
    a.updated()
    k.check("turning it on is pushed to the connection device", env.children["uno-connection"].settingsUpdated.any { it[0] == "enablePanic" && it[1].value == true })
    k.check("the status summary warns while panic is enabled", a.statusSummary().contains("Panic commands are ENABLED"))
}

k.section("Require code to arm setting") {
    def (a, env) = k.newApp([zoneCount: 1])
    a.initialize()
    k.check("off by default and pushed to the connection device", env.children["uno-connection"].settingsUpdated.any { it[0] == "requireCodeToArm" && it[1].value == false })
    k.check("the summary says nothing while it is off", !a.statusSummary().contains("code"))

    a.settings.requireCodeToArm = true
    a.settings.masterCode = "4321"
    a.updated()
    k.check("turning it on is pushed to the connection device", env.children["uno-connection"].settingsUpdated.any { it[0] == "requireCodeToArm" && it[1].value == true })
    k.check("the summary says arming needs the master code", a.statusSummary().contains("Arming requires the master code"), a.statusSummary())

    a.settings.masterCode = ""
    k.check("the summary warns when there is no master code to check against", a.statusSummary().contains("WARNING: arming requires a code but no master code is set"), a.statusSummary())
}

k.section("REST status and setup checks") {
    def (a, env) = k.newApp([useRestMetadata: true, zoneCount: 2], ["zone labels": ["A", "B"], "partition labels": ["Main"]])
    a.initialize()
    k.check("a successful REST load is recorded", a.state.restStatus == "ok")

    def (u, connU, envU) = k.newPair([useRestMetadata: true, zoneCount: 2])
    u.httpGetImpl = { Map p -> throw new MockHttpException(401) }
    connU.save()
    k.check("HTTP 401 is reported as rejected credentials", u.state.restStatus.contains("401") && u.errors().any { it.contains("rejected the REST credentials") }, u.state.restStatus)
    k.check("initialization continues after a REST failure", connU.host.children.containsKey("uno-partition-1"))

    def (n, envN) = k.newApp([useRestMetadata: true, zoneCount: 2])
    n.httpGetImpl = { Map p -> throw new IOException("timeout") }
    n.initialize()
    k.check("other failures are reported as unreachable", n.state.restStatus == "unreachable" && n.warns().any { it.contains("REST metadata load failed") }, n.state.restStatus)

    def (o, envO) = k.newApp([useRestMetadata: false, zoneCount: 2])
    o.initialize()
    k.check("disabled REST is reported as disabled", o.state.restStatus == "disabled")

    def (s, envS) = k.newApp([zoneCount: 1])
    k.check("before install the summary explains the connection device doesn't exist yet", s.statusSummary().contains("created when you press Done"))
    s.initialize()
    def conn = envS.children["uno-connection"]
    conn.events << [name: "connectionState", value: "authenticated"] << [name: "panelModel", value: "UNO"] << [name: "firmwareVersion", value: "01.00.117"] << [name: "panelMac", value: "001C2A45F894"]
    String summary = s.statusSummary()
    k.check("the summary shows connection state, panel details and REST status",
            summary.contains("Connection: authenticated") && summary.contains("UNO, firmware 01.00.117, MAC 001C2A45F894") && summary.contains("REST: disabled"), summary)
}

k.section("PGM outputs") {
    def (a, env) = k.newApp([zoneCount: 1, pgmSet: "1,3-4", pgmNames: "1:Garage Door\n# note\nbad\n4:Gate"])
    a.initialize()
    List pgms = env.children.keySet().findAll { it.startsWith("uno-pgm-") }.sort()
    k.check("a PGM device for each output in the set", pgms == ["uno-pgm-1", "uno-pgm-3", "uno-pgm-4"], pgms)
    k.check("created with the UNO PGM driver", env.children["uno-pgm-1"].typeName == "UNO PGM")
    k.check("named from the hints, with a generic fallback", env.children["uno-pgm-1"].label == "Garage Door" && env.children["uno-pgm-3"].label == "PGM 3" && env.children["uno-pgm-4"].label == "Gate", pgms.collect { env.children[it].label })

    a.settings.pgmNames = "3:Sprinklers"
    a.updated()
    k.check("renaming takes effect on update", env.children["uno-pgm-3"].label == "Sprinklers")

    a.triggerPgm(3)
    k.check("a trigger is forwarded to the connection device", env.children["uno-connection"].callArgs.last() == ["triggerPgm", [3]], env.children["uno-connection"].callArgs)
    a.notifyPgmEvents(3, [[name: "lastTriggered", value: "now"]])
    k.check("accepted triggers are recorded on the PGM device", env.children["uno-pgm-3"].last("lastTriggered") == "now")
    a.notifyPgmEvents(9, [[name: "x", value: "y"]])
    k.check("events for a PGM without a device are ignored", true)

    def (b, envB) = k.newApp([zoneCount: 1])
    b.initialize()
    k.check("no PGM set => no PGM devices", envB.children.keySet().every { !it.startsWith("uno-pgm-") })
    def (c, envC) = k.newApp([zoneCount: 1, pgmSet: "0,15,x"])
    c.initialize()
    k.check("invalid PGM numbers create nothing", envC.children.keySet().every { !it.startsWith("uno-pgm-") })
}

k.section("Online / offline health") {
    def (a, conn, env) = k.newPair([zoneCount: 2, partitionSet: "1-2", pgmSet: "1"])
    conn.save()
    Map kids = conn.host.children
    k.check("devices created before the connection is up start offline", kids["uno-zone-1"].last("healthStatus") == "offline" && kids["uno-partition-1"].last("healthStatus") == "offline" && env.children["uno-pgm-1"].last("healthStatus") == "offline")

    conn.markHealth("online")
    List mine = kids.keySet().toList()
    k.check("every zone and partition is told when the connection comes online", mine.every { kids[it].last("healthStatus") == "online" }, mine)
    k.check("and so is the app's own PGM device", env.children["uno-pgm-1"].last("healthStatus") == "online")
    k.check("the connection device manages its own status", env.children["uno-connection"].currentValue("healthStatus") == "online" && a.state.health == "online")

    a.settings.zoneCount = 4
    conn.settings.zoneCount = 4
    conn.save()
    k.check("a device created while online starts online", kids["uno-zone-4"].last("healthStatus") == "online")

    conn.markHealth("offline")
    k.check("and everything goes offline together", kids.keySet().every { kids[it].last("healthStatus") == "offline" } && env.children["uno-pgm-1"].last("healthStatus") == "offline")

    a.settings.zoneHints = "1:Basement Leak:water"
    conn.markHealth("online")
    conn.save()
    k.check("a zone recreated with another driver gets the current status", kids["uno-zone-1"].typeName == "UNO Zone Water" && kids["uno-zone-1"].last("healthStatus") == "online")
}

k.section("Notifications") {
    def (a, env) = k.newApp([zoneCount: 1])
    a.initialize()
    MockNotifier n1 = new MockNotifier(), n2 = new MockNotifier()

    a.notifyEvent([category: "alarm", text: "Burglary alarm: Front Door (zone 5)", partition: 1, restore: false])
    k.check("nothing is sent with no notification devices", n1.messages.isEmpty())

    a.settings.notifyDevices = [n1, n2]
    a.notifyEvent([category: "alarm", text: "Burglary alarm: Front Door (zone 5)", partition: 1, restore: false])
    k.check("alarms notify every device by default", n1.messages == ["Burglary alarm: Front Door (zone 5)"] && n2.messages == n1.messages, n1.messages)
    a.notifyEvent([category: "alarm", text: "Burglary alarm restored: Front Door (zone 5)", partition: 1, restore: true])
    k.check("alarm restorals don't notify", n1.messages.size() == 1)
    a.notifyEvent([category: "trouble", text: "AC power loss", partition: 1, restore: false])
    a.notifyEvent([category: "trouble", text: "AC power loss restored", partition: 1, restore: true])
    a.notifyEvent([category: "supervisory", text: "Pump failure", partition: 1, restore: false])
    k.check("troubles, their restorals and supervisory events notify by default", n1.messages.takeRight(3) == ["AC power loss", "AC power loss restored", "Pump failure"], n1.messages)

    a.notifyEvent([category: "arm", text: "Armed by Alice", partition: 1, restore: true])
    a.notifyEvent([category: "disarm", text: "Disarmed by Alice", partition: 1, restore: false])
    k.check("arm and disarm are off by default", n1.messages.size() == 4)
    a.settings.notifyArmDisarm = true
    a.notifyEvent([category: "arm", text: "Armed by Alice", partition: 1, restore: true])
    a.notifyEvent([category: "disarm", text: "Disarmed by Alice", partition: 1, restore: false])
    k.check("and notify once switched on", n1.messages.takeRight(2) == ["Armed by Alice", "Disarmed by Alice"], n1.messages)

    int count = n1.messages.size()
    ["test", "access", "exception", "bypass", "other"].each { c -> a.notifyEvent([category: c, text: "x", partition: 1, restore: false]) }
    k.check("tests and other categories never notify", n1.messages.size() == count)

    a.settings.notifyAlarms = false; a.settings.notifyTroubles = false
    a.notifyEvent([category: "alarm", text: "Fire alarm", partition: 1, restore: false])
    a.notifyEvent([category: "trouble", text: "AC power loss", partition: 1, restore: false])
    k.check("alarms and troubles can be switched off", n1.messages.size() == count)

    n1.fail = true
    a.settings.notifyAlarms = true
    a.notifyEvent([category: "alarm", text: "Fire alarm", partition: 1, restore: false])
    k.check("a failing notification device is logged, not thrown", a.warns().any { it.contains("Could not send notification") }, a.warns())

    def (m, envM) = k.newApp([zoneCount: 1, partitionSet: "1-2", partitionHints: "2:Guest House"])
    m.initialize()
    MockNotifier mn = new MockNotifier()
    m.settings.notifyDevices = [mn]
    m.notifyEvent([category: "alarm", text: "Fire alarm", partition: 2, restore: false])
    m.notifyEvent([category: "alarm", text: "Fire alarm", partition: 1, restore: false])
    k.check("with several partitions the message says which", mn.messages == ["Guest House: Fire alarm", "House Alarm: Fire alarm"], mn.messages)
}

k.section("Event settings are pushed to the connection device") {
    def (a, env) = k.newApp([zoneCount: 1])
    a.initialize()
    List sent = env.children["uno-connection"].settingsUpdated
    k.check("diagnostic and test events are off by default", sent.any { it[0] == "diagnosticEvents" && it[1].value == false } && sent.any { it[0] == "recordTestEvents" && it[1].value == false })
    a.settings.diagnosticEvents = true; a.settings.recordTestEvents = true
    a.updated()
    k.check("switching them on is pushed", sent.any { it[0] == "diagnosticEvents" && it[1].value == true } && sent.any { it[0] == "recordTestEvents" && it[1].value == true })
}

k.section("User names and zone names for event text") {
    def (a, conn, env) = k.newPair([zoneCount: 3, userNames: "2:Alice\n# comment\nbad line\n3 : Bob \n0:Nobody", zoneHints: "2:Front Door:contact"])
    conn.save()
    k.check("a named user is found", a.userName(2) == "Alice", a.userName(2))
    k.check("spaces around the number and name are ignored", a.userName(3) == "Bob", a.userName(3))
    k.check("an unnamed user has no name", a.userName(4) == null)
    k.check("comments and bad lines are ignored", a.state.userNamesMap.keySet().sort() == ["0", "2", "3"], a.state.userNamesMap)

    k.check("a zone with a real label is named", conn.zoneName(2) == "Front Door", conn.zoneName(2))
    k.check("a zone that still has its default label has no name", conn.zoneName(1) == null, conn.zoneName(1))
    k.check("a zone with no device has no name", conn.zoneName(99) == null)

    a.settings.userNames = "2:Carol"
    a.updated()
    k.check("changing the names takes effect on update", a.userName(2) == "Carol" && a.userName(3) == null)

    def (b, envB) = k.newApp([zoneCount: 1])
    b.initialize()
    k.check("no names configured is fine", b.userName(1) == null)
}

k.section("Zone name inference") {
    def conn = k.newConn()
    Map cases = ["Kitchen CO": "co", "Dining Room PIR": "motion", "Attic Heat": "smoke", "Basement Leak": "water",
                 "Front Door": "contact", "Back Courtyard Door": "contact", "Corner Window": "contact", "Wheat Room": null,
                 "Garage Entry": null, "Carbon Monoxide": "co"]
    cases.each { n, want ->
        def got = conn.inferTypeFromName(n)
        k.check("inferTypeFromName('${n}') == ${want}", got == want, got)
    }
}
