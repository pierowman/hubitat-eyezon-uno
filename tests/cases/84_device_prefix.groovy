// The optional device name prefix, so the devices sort together on the Devices page.

def labels = { env -> env.children.collectEntries { String dni, c -> [(dni): c.label] } }

k.section("With no prefix, labels are as before") {
    def (a, env) = k.newApp([zoneCount: 2, createStatusDevice: true, pgmSet: "1"])
    a.initialize()
    k.check("the connection, partition, zones, PGM and status keep their plain labels",
        labels(env) == ["uno-connection": "UNO Connection", "uno-partition-1": "House Alarm", "uno-zone-1": "Zone 1", "uno-zone-2": "Zone 2",
                        "uno-pgm-1": "PGM 1", "uno-status": "UNO Status"], labels(env))
}

k.section("A prefix goes on every device") {
    def (a, env) = k.newApp([zoneCount: 2, createStatusDevice: true, pgmSet: "1",     devicePrefix: "  Alarm ", zoneHints: "1:Front Door:contact"])
    a.initialize()
    k.check("each label starts with it",
        labels(env) == ["uno-connection": "Alarm UNO Connection", "uno-partition-1": "Alarm House Alarm", "uno-zone-1": "Alarm Front Door",
                        "uno-zone-2": "Alarm Zone 2", "uno-pgm-1": "Alarm PGM 1", "uno-status": "Alarm UNO Status"], labels(env))
    k.check("the device name (not shown on the Devices page) is left alone", env.children["uno-zone-2"].name == "UNO Zone 2" && env.children["uno-connection"].name == "UNO Connection", env.children["uno-zone-2"].name)
    k.check("surrounding spaces in the setting are ignored", a.prefixedLabel("X") == "Alarm X", a.prefixedLabel("X"))

    k.check("notifications and event text use the zone's own name", a.zoneName(1) == "Front Door" && a.zoneName(2) == null, [a.zoneName(1), a.zoneName(2)])
}

k.section("Changing or removing the prefix relabels without stacking") {
    def (a, env) = k.newApp([zoneCount: 2, createStatusDevice: true, devicePrefix: "Alarm", zoneHints: "1:Front Door:contact"])
    a.initialize()

    a.settings.devicePrefix = "Home Security"
    a.initialize()
    k.check("a new prefix replaces the old one",
        labels(env) == ["uno-connection": "Home Security UNO Connection", "uno-partition-1": "Home Security House Alarm", "uno-zone-1": "Home Security Front Door",
                        "uno-zone-2": "Home Security Zone 2", "uno-status": "Home Security UNO Status"], labels(env))

    a.settings.devicePrefix = "  "
    a.initialize()
    k.check("an empty one takes it off again",
        labels(env) == ["uno-connection": "UNO Connection", "uno-partition-1": "House Alarm", "uno-zone-1": "Front Door", "uno-zone-2": "Zone 2", "uno-status": "UNO Status"], labels(env))

    a.initialize()
    k.check("saving again changes nothing", labels(env)["uno-zone-1"] == "Front Door", labels(env))
}

k.section("A prefix is added to devices that already exist") {
    def (a, env) = k.newApp([zoneCount: 2])
    a.initialize()
    k.check("they start plain", labels(env)["uno-zone-1"] == "Zone 1")

    a.settings.devicePrefix = "Alarm"
    a.initialize()
    k.check("and are renamed on the next save", labels(env)["uno-zone-1"] == "Alarm Zone 1" && labels(env)["uno-connection"] == "Alarm UNO Connection", labels(env))
}

k.section("A name you set by hand is kept") {
    def (a, env) = k.newApp([zoneCount: 2])
    a.initialize()
    env.children["uno-connection"].label = "Alarm Link"
    env.children["uno-zone-2"].label = "Garage Side"

    a.settings.devicePrefix = "Alarm"
    a.initialize()
    k.check("the connection device keeps its own name", labels(env)["uno-connection"] == "Alarm Link", labels(env)["uno-connection"])
    k.check("a zone you renamed keeps its name, with the prefix in front", labels(env)["uno-zone-2"] == "Alarm Garage Side", labels(env)["uno-zone-2"])

    a.initialize()
    k.check("and it isn't prefixed twice", labels(env)["uno-zone-2"] == "Alarm Garage Side", labels(env)["uno-zone-2"])
}

k.section("Zones found later get the prefix too") {
    def (a, env) = k.newApp([zoneCount: 2, devicePrefix: "Alarm", autoCreateConfiguredZones: false])
    a.initialize()
    a.upsertDiscoveredZone(7, "smoke", "Hallway Smoke")
    k.check("a zone created from an event is prefixed", labels(env)["uno-zone-7"] == "Alarm Hallway Smoke", labels(env))
}

k.section("Names are kept within the limit") {
    def (a, env) = k.newApp([zoneCount: 1, devicePrefix: "P" * 80, zoneHints: "1:" + ("N" * 400) + ":contact"])
    a.initialize()
    String label = labels(env)["uno-zone-1"]
    k.check("a long name is cut to 255 characters", label.length() == 255, label.length())
    k.check("and the prefix is cut to 50, so the name still shows", label.startsWith("P" * 50 + " N") && !label.startsWith("P" * 51), label.take(60))

    def (b, env2) = k.newApp([zoneCount: 1])
    b.initialize()
    k.check("a short name is never cut", labels(env2)["uno-zone-1"] == "Zone 1")
}

k.section("The example on the settings page uses the prefix") {
    def (a, env) = k.newApp([zoneCount: 1, devicePrefix: "Alarm"])
    k.check("a name comes out as it will on the Devices page", a.prefixedLabel("Front Door") == "Alarm Front Door", a.prefixedLabel("Front Door"))
}
