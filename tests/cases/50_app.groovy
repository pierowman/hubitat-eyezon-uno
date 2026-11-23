// App: device creation, discovery, hints, partitions, zone sets, forwarding and setup checks.

k.section("Zone discovery and hints") {
    def (a, env) = k.newApp([zoneHints: "2:Dining Room PIR:motion\n3:Front Door:contact\n4:Kitchen Smoke:smoke\n# comment\nbad line"])
    a.initialize()
    k.check("connection + partition + 5 zones created", env.children.size() == 7, env.children.keySet())
    k.check("zone 1 default contact", env.children["uno-zone-1"].typeName == "UNO Zone Contact")
    k.check("zone 2 motion with label", env.children["uno-zone-2"].typeName == "UNO Zone Motion" && env.children["uno-zone-2"].label == "Dining Room PIR")
    k.check("zone 4 smoke", env.children["uno-zone-4"].typeName == "UNO Zone Smoke")
    k.check("partition 1 gets the default name", env.children["uno-partition-1"].label == "House Alarm")
    k.check("connection settings pushed", env.children["uno-connection"].settingsUpdated.any { it[0] == "ip" })
    env.deleted.clear()
    a.initialize()
    k.check("re-initialize doesn't delete/recreate zones", env.deleted.isEmpty(), env.deleted)
}

k.section("REST labels and reconcile don't flip-flop") {
    def (a, env) = k.newApp([useRestMetadata: true, zoneCount: 3],
        ["zone labels": ["Kitchen CO", "Hall PIR", "Front Door"], "partition labels": ["Main"], "system name": "My UNO"])
    a.initialize()
    k.check("'Kitchen CO' => CO driver", env.children["uno-zone-1"].typeName == "UNO Zone CO", env.children["uno-zone-1"].typeName)
    k.check("'Hall PIR' => motion", env.children["uno-zone-2"].typeName == "UNO Zone Motion")
    env.deleted.clear(); env.created.clear()
    a.updated()
    k.check("save/update doesn't recreate inferred zones", env.deleted.isEmpty() && env.created.isEmpty(), [env.deleted, env.created])
}

k.section("CID suggestions are stable") {
    def (a, env) = k.newApp([zoneCount: 8, zoneHints: "7:Garage:contact"])
    a.initialize(); env.deleted.clear()
    a.upsertDiscoveredZone(6, "smoke", null)
    k.check("a default-type zone is upgraded once", env.children["uno-zone-6"].typeName == "UNO Zone Smoke" && env.deleted == ["uno-zone-6"], env.deleted)
    a.upsertDiscoveredZone(6, null, null); a.upsertDiscoveredZone(6, null, null)
    k.check("later upserts don't flip it back", env.children["uno-zone-6"].typeName == "UNO Zone Smoke" && env.deleted.size() == 1, env.deleted)
    a.upsertDiscoveredZone(6, "water", null)
    k.check("an already-specific zone isn't re-typed", env.children["uno-zone-6"].typeName == "UNO Zone Smoke")
    a.upsertDiscoveredZone(7, "smoke", null)
    k.check("an explicit hint beats a CID suggestion", env.children["uno-zone-7"].typeName == "UNO Zone Contact")
    a.upsertDiscoveredZone(0, null, null); a.upsertDiscoveredZone(null, null, null)
    k.check("zone 0 / null ignored", env.children.keySet().every { !it.endsWith("zone-0") && !it.endsWith("zone-null") })
}

k.section("Hint changes still apply") {
    def (a, env) = k.newApp([zoneCount: 3])
    a.initialize()
    a.settings.zoneHints = "1:Basement Leak:water"
    a.updated()
    k.check("a new hint recreates the zone with the new driver", env.children["uno-zone-1"].typeName == "UNO Zone Water" && env.children["uno-zone-1"].label == "Basement Leak")
    a.settings.zoneHints = "1:Basement Sensor:contact"
    a.updated()
    k.check("changing the hint back recreates it again", env.children["uno-zone-1"].typeName == "UNO Zone Contact")
    a.settings.defaultUnknownZoneType = "motion"
    a.settings.zoneHints = ""
    env.deleted.clear()
    a.updated()
    k.check("a hint-less zone on a specific driver is left alone", env.deleted.isEmpty(), env.deleted)
}

k.section("Zone set") {
    def (a, env) = k.newApp([zoneSet: "1-3,5", zoneCount: 27])
    a.initialize()
    List zones = env.children.keySet().findAll { it.startsWith("uno-zone-") }.sort()
    k.check("only the zones in the set are created", zones == ["uno-zone-1", "uno-zone-2", "uno-zone-3", "uno-zone-5"], zones)
    k.check("the set is pushed to the connection device", env.children["uno-connection"].settingsUpdated.any { it[0] == "zoneSet" && it[1].value == "1-3,5" })

    def (b, envB) = k.newApp([zoneSet: "", zoneCount: 4])
    b.initialize()
    k.check("an empty set falls back to the zone count", envB.children.keySet().findAll { it.startsWith("uno-zone-") }.size() == 4)

    def (c, envC) = k.newApp([zoneSet: "garbage", zoneCount: 2])
    c.initialize()
    k.check("an invalid set falls back to the zone count", envC.children.keySet().findAll { it.startsWith("uno-zone-") }.size() == 2)

    k.check("preview lists the zones", a.rangePreview("1-3,5", 1, 128, "zone", "x").contains("zones: 1, 2, 3, 5 (4)"), a.rangePreview("1-3,5", 1, 128, "zone", "x"))
    k.check("preview reports ignored entries", a.rangePreview("1-3,x,500", 1, 128, "zone", "x").contains("Ignored") && a.rangePreview("1-3,x,500", 1, 128, "zone", "x").contains("x, 500"))
    k.check("preview shows the fallback when blank", a.rangePreview("", 1, 128, "zone", "zones 1-27").contains("Will use zones 1-27"))
}

k.section("Multiple partitions") {
    def (a, env) = k.newApp([partitionSet: "1-2,4", zoneCount: 1, partitionHints: "2:Guest House\n# note\nbad\n4:Shop"])
    a.initialize()
    List parts = env.children.keySet().findAll { it.startsWith("uno-partition-") }.sort()
    k.check("a partition device is created for each partition in the set", parts == ["uno-partition-1", "uno-partition-2", "uno-partition-4"], parts)
    k.check("partition 1 default name", env.children["uno-partition-1"].label == "House Alarm")
    k.check("hints name the other partitions", env.children["uno-partition-2"].label == "Guest House" && env.children["uno-partition-4"].label == "Shop")

    def (b, envB) = k.newApp([zoneCount: 1])
    b.initialize()
    k.check("no partition set => partition 1 only", envB.children.keySet().findAll { it.startsWith("uno-partition-") }.sort() == ["uno-partition-1"])

    def (c, envC) = k.newApp([partitionSet: "9,x", zoneCount: 1])
    c.initialize()
    k.check("an invalid partition set falls back to partition 1", envC.children.keySet().findAll { it.startsWith("uno-partition-") }.sort() == ["uno-partition-1"])
}

k.section("Partition names") {
    Map rest = ["zone labels": [], "partition labels": ["Main", "Annex"]]
    def (a, env) = k.newApp([useRestMetadata: true, partitionSet: "1-3", zoneCount: 1], rest)
    a.initialize()
    k.check("names come from the UNO when nothing else is set", env.children["uno-partition-1"].label == "Main" && env.children["uno-partition-2"].label == "Annex")
    k.check("a partition the UNO doesn't name gets a generic name", env.children["uno-partition-3"].label == "Partition 3")

    def (b, envB) = k.newApp([useRestMetadata: true, partitionSet: "1-2", partitionLabel: "Home", partitionHints: "2:Cabin", zoneCount: 1], rest)
    b.initialize()
    k.check("the partition 1 name setting beats the UNO name", envB.children["uno-partition-1"].label == "Home")
    k.check("a partition hint beats the UNO name", envB.children["uno-partition-2"].label == "Cabin")

    def (c, envC) = k.newApp([useRestMetadata: true, partitionSet: "1", partitionLabel: "Home", partitionHints: "1:Hint wins", zoneCount: 1], rest)
    c.initialize()
    k.check("a hint beats the partition 1 name setting", envC.children["uno-partition-1"].label == "Hint wins")

    env.children["uno-partition-1"].label = "Renamed by hand"
    a.updated()
    k.check("labels are reapplied on update from the stored UNO names", env.children["uno-partition-1"].label == "Main")
}

k.section("Forwarding to the connection and partitions") {
    def (a, env) = k.newApp([zoneCount: 1, partitionSet: "1-2"])
    a.initialize()
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

    a.notifyPartitionState(2, "armedStay")
    k.check("partition state goes to the right partition device", env.children["uno-partition-2"].alarmStates == ["armedStay"] && env.children["uno-partition-1"].alarmStates == [])
    a.notifyPartitionState(5, "armedAway")
    k.check("a state for a partition without a device is ignored", true)
    a.notifyPartitionEvents(1, [[name: "acFailure", value: "active"], [name: "troubleState", value: "trouble"]])
    k.check("attribute events reach the partition device", env.children["uno-partition-1"].last("acFailure") == "active" && env.children["uno-partition-1"].last("troubleState") == "trouble")
    a.notifyPartitionEvents(5, [[name: "x", value: "y"]])
    a.notifyPartitionEvents(1, null)
    k.check("events for a missing partition or a null list are ignored", true)
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

    def (u, envU) = k.newApp([useRestMetadata: true, zoneCount: 2])
    u.httpGetImpl = { Map p -> throw new MockHttpException(401) }
    u.initialize()
    k.check("HTTP 401 is reported as rejected credentials", u.state.restStatus.contains("401") && u.errors().any { it.contains("rejected the REST credentials") }, u.state.restStatus)
    k.check("initialization continues after a REST failure", envU.children.containsKey("uno-partition-1"))

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

k.section("User names and zone names for event text") {
    def (a, env) = k.newApp([zoneCount: 3, userNames: "2:Alice\n# comment\nbad line\n3 : Bob \n0:Nobody", zoneHints: "2:Front Door:contact"])
    a.initialize()
    k.check("a named user is found", a.userName(2) == "Alice", a.userName(2))
    k.check("spaces around the number and name are ignored", a.userName(3) == "Bob", a.userName(3))
    k.check("an unnamed user has no name", a.userName(4) == null)
    k.check("comments and bad lines are ignored", a.state.userNamesMap.keySet().sort() == ["0", "2", "3"], a.state.userNamesMap)

    k.check("a zone with a real label is named", a.zoneName(2) == "Front Door", a.zoneName(2))
    k.check("a zone that still has its default label has no name", a.zoneName(1) == null, a.zoneName(1))
    k.check("a zone with no device has no name", a.zoneName(99) == null)

    a.settings.userNames = "2:Carol"
    a.updated()
    k.check("changing the names takes effect on update", a.userName(2) == "Carol" && a.userName(3) == null)

    def (b, envB) = k.newApp([zoneCount: 1])
    b.initialize()
    k.check("no names configured is fine", b.userName(1) == null)
}

k.section("Zone name inference") {
    def (a, env) = k.newApp([zoneCount: 1])
    Map cases = ["Kitchen CO": "co", "Dining Room PIR": "motion", "Attic Heat": "smoke", "Basement Leak": "water",
                 "Front Door": "contact", "Back Courtyard Door": "contact", "Corner Window": "contact", "Wheat Room": null,
                 "Garage Entry": null, "Carbon Monoxide": "co"]
    cases.each { n, want ->
        def got = a.inferTypeFromName(n)
        k.check("inferTypeFromName('${n}') == ${want}", got == want, got)
    }
}
