// The optional name prefix, so the partitions and zones sort together on the Devices page. The connection device names
// its own partitions and zones with it. The app's own devices (connection, PGM, status) keep their plain names.

def labels = { conn, env -> (conn.host.children + env.children).collectEntries { String dni, c -> [(dni): c.label] } }

k.section("With no prefix, labels are as before") {
    def (a, conn, env) = k.newPair([zoneCount: 2, createStatusDevice: true, pgmSet: "1"])
    conn.save()
    k.check("the connection, partition, zones, PGM and status keep their plain labels",
        labels(conn, env) == ["uno-connection": "UNO Connection", "uno-partition-1": "House Alarm", "uno-zone-1": "Zone 1", "uno-zone-2": "Zone 2",
                              "uno-pgm-1": "PGM 1", "uno-status": "UNO Status"], labels(conn, env))
}

k.section("A prefix goes on the partitions and zones only") {
    def (a, conn, env) = k.newPair([zoneCount: 2, createStatusDevice: true, pgmSet: "1", devicePrefix: "  Alarm ", zoneHints: "1:Front Door:contact"])
    conn.save()
    k.check("the partition and zones start with it, and nothing else does",
        labels(conn, env) == ["uno-connection": "UNO Connection", "uno-partition-1": "Alarm House Alarm", "uno-zone-1": "Alarm Front Door",
                              "uno-zone-2": "Alarm Zone 2", "uno-pgm-1": "PGM 1", "uno-status": "UNO Status"], labels(conn, env))
    k.check("the device name (not shown on the Devices page) is left alone", conn.host.children["uno-zone-2"].name == "UNO Zone 2" && env.children["uno-connection"].typeName == "UNO Connection", conn.host.children["uno-zone-2"].name)
    k.check("surrounding spaces in the setting are ignored", a.prefixedLabel("X") == "Alarm X", a.prefixedLabel("X"))

    k.check("notifications and event text use the zone's own name", conn.zoneName(1) == "Front Door" && conn.zoneName(2) == null, [conn.zoneName(1), conn.zoneName(2)])
}

k.section("Changing or removing the prefix relabels without stacking") {
    def (a, conn, env) = k.newPair([zoneCount: 2, createStatusDevice: true, devicePrefix: "Alarm", zoneHints: "1:Front Door:contact"])
    conn.save()

    a.settings.devicePrefix = "Home Security"
    conn.save()
    k.check("a new prefix replaces the old one",
        labels(conn, env) == ["uno-connection": "UNO Connection", "uno-partition-1": "Home Security House Alarm", "uno-zone-1": "Home Security Front Door",
                              "uno-zone-2": "Home Security Zone 2", "uno-status": "UNO Status"], labels(conn, env))

    a.settings.devicePrefix = "  "
    conn.save()
    k.check("an empty one takes it off again",
        labels(conn, env) == ["uno-connection": "UNO Connection", "uno-partition-1": "House Alarm", "uno-zone-1": "Front Door", "uno-zone-2": "Zone 2", "uno-status": "UNO Status"], labels(conn, env))

    conn.save()
    k.check("saving again changes nothing", labels(conn, env)["uno-zone-1"] == "Front Door", labels(conn, env))
}

k.section("A prefix is added to partitions and zones that already exist") {
    def (a, conn, env) = k.newPair([zoneCount: 2])
    conn.save()
    k.check("they start plain", labels(conn, env)["uno-zone-1"] == "Zone 1")

    a.settings.devicePrefix = "Alarm"
    conn.save()
    k.check("and are renamed on the next save, but the connection device is not", labels(conn, env)["uno-zone-1"] == "Alarm Zone 1" && labels(conn, env)["uno-connection"] == "UNO Connection", labels(conn, env))
}

k.section("Devices an earlier version prefixed lose it") {
    def (a, conn, env) = k.newPair([zoneCount: 1, createStatusDevice: true, pgmSet: "1", devicePrefix: "Alarm"])
    a.state.appliedPrefix = "Alarm"
    env.children["uno-connection"] = new ConnAdapter(script: conn, label: "Alarm UNO Connection")
    env.children["uno-status"] = new MockChild(dni: "uno-status", typeName: "UNO Status", label: "Alarm UNO Status")
    env.children["uno-pgm-1"] = new MockChild(dni: "uno-pgm-1", typeName: "UNO PGM", label: "Alarm PGM 1")
    conn.save()
    k.check("the connection, status and PGM devices go back to their plain names", labels(conn, env)["uno-connection"] == "UNO Connection" && labels(conn, env)["uno-status"] == "UNO Status" && labels(conn, env)["uno-pgm-1"] == "PGM 1", labels(conn, env))

    env.children["uno-status"].label = "Alarm Tile"
    conn.save()
    k.check("one renamed by hand is left alone", labels(conn, env)["uno-status"] == "Alarm Tile", labels(conn, env)["uno-status"])
}

k.section("A name you set by hand is kept") {
    def (a, conn, env) = k.newPair([zoneCount: 2])
    conn.save()
    env.children["uno-connection"].label = "Alarm Link"
    conn.host.children["uno-zone-2"].label = "Garage Side"

    a.settings.devicePrefix = "Alarm"
    conn.save()
    k.check("the connection device keeps its own name", labels(conn, env)["uno-connection"] == "Alarm Link", labels(conn, env)["uno-connection"])
    k.check("a zone you renamed keeps its name, with the prefix in front", labels(conn, env)["uno-zone-2"] == "Alarm Garage Side", labels(conn, env)["uno-zone-2"])

    conn.save()
    k.check("and it isn't prefixed twice", labels(conn, env)["uno-zone-2"] == "Alarm Garage Side", labels(conn, env)["uno-zone-2"])
}

k.section("Zones found later get the prefix too") {
    def (a, conn, env) = k.newPair([zoneCount: 2, devicePrefix: "Alarm", autoCreateConfiguredZones: false])
    conn.save()
    a.state.restZoneLabels = ["7": "Hallway Smoke"]
    conn.ensureZoneFor(7, "smoke")
    k.check("a zone created from an event is prefixed", labels(conn, env)["uno-zone-7"] == "Alarm Hallway Smoke", labels(conn, env))
}

k.section("Names are kept within the limit") {
    def (a, conn, env) = k.newPair([zoneCount: 1, devicePrefix: "P" * 80, zoneHints: "1:" + ("N" * 400) + ":contact"])
    conn.save()
    String label = labels(conn, env)["uno-zone-1"]
    k.check("a long name is cut to 255 characters", label.length() == 255, label.length())
    k.check("and the prefix is cut to 50, so the name still shows", label.startsWith("P" * 50 + " N") && !label.startsWith("P" * 51), label.take(60))

    def (b, connB, envB) = k.newPair([zoneCount: 1])
    connB.save()
    k.check("a short name is never cut", labels(connB, envB)["uno-zone-1"] == "Zone 1")
}

k.section("The example on the settings page uses the prefix") {
    def (a, env) = k.newApp([zoneCount: 1, devicePrefix: "Alarm"])
    k.check("a name comes out as it will on the Devices page", a.prefixedLabel("Front Door") == "Alarm Front Door", a.prefixedLabel("Front Door"))
}
