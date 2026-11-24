// The partitions and zones are children of the UNO Connection device (so the Devices page lists them under it), and
// the app no longer makes them. Includes the move from the earlier layout, where they were children of the app.

def bitmap = { List<Integer> zones ->
    byte[] b = new byte[16]
    zones.each { int z -> b[(z - 1).intdiv(8)] |= (1 << ((z - 1) % 8)) }
    b.collect { String.format("%02X", it & 0xFF) }.join()
}

k.section("Zones and partitions are made by the connection device, not the app") {
    def (a, conn, env) = k.newPair([zoneCount: 3, partitionSet: "1-2", createStatusDevice: true, pgmSet: "1"])
    conn.save()
    k.check("the app keeps only the connection, status and PGM devices", env.children.keySet() == ["uno-connection", "uno-status", "uno-pgm-1"] as Set, env.children.keySet())
    k.check("every partition and zone is the connection device's child", conn.host.children.keySet() == ["uno-partition-1", "uno-partition-2", "uno-zone-1", "uno-zone-2", "uno-zone-3"] as Set, conn.host.children.keySet())
    k.check("made as component devices, so they can't be removed on their own", conn.host.children.values().every { it.props.isComponent == true }, conn.host.children.values()*.props)
    k.check("and the app never asked for any of them", env.created.every { it[0] in ["uno-connection", "uno-status", "uno-pgm-1"] }, env.created)
}

k.section("Devices from the earlier layout are replaced") {
    def (a, conn, env) = k.newPair([zoneCount: 2, partitionSet: "1", pgmSet: "1", createStatusDevice: true])
    // As it was before: the app owned the partition and the zones.
    ["uno-partition-1": "UNO Partition", "uno-zone-1": "UNO Zone Contact", "uno-zone-2": "UNO Zone Motion"].each { String dni, String type ->
        env.children[dni] = new MockChild(dni: dni, typeName: type, label: type == "UNO Zone Contact" ? "Back Door" : (dni == "uno-zone-2" ? "Zone 2" : "House Alarm"))
    }
    conn.save()
    k.check("the app removes them (the ids can't be shared)", env.deleted.sort() == ["uno-partition-1", "uno-zone-1", "uno-zone-2"], env.deleted)
    k.check("the connection device makes new ones", conn.host.children.keySet() == ["uno-partition-1", "uno-zone-1", "uno-zone-2"] as Set, conn.host.children.keySet())
    k.check("a zone keeps the name its old device had, even one changed by hand", conn.host.children["uno-zone-1"].label == "Back Door" && conn.host.children["uno-zone-2"].label == "Zone 2", conn.host.children*.value*.label)
    k.check("the PGM and status devices are left alone", env.children.containsKey("uno-pgm-1") && env.children.containsKey("uno-status"))

    env.deleted.clear()
    conn.save()
    k.check("saving again removes nothing", env.deleted.isEmpty(), env.deleted)
    k.check("and the name is still kept", conn.host.children["uno-zone-1"].label == "Back Door")

    def (b, connB, envB) = k.newPair([zoneCount: 1, devicePrefix: "Alarm"], ["zone labels": ["Basement Back Do"], "partition labels": []])
    envB.children["uno-zone-1"] = new MockChild(dni: "uno-zone-1", typeName: "UNO Zone Contact", label: "Alarm Basement Back Do")
    b.state.appliedPrefix = "Alarm"
    connB.save()
    k.check("a prefix it already had is not stacked on, and the UNO's name is followed", connB.host.children["uno-zone-1"].label == "Alarm Basement Back Do", connB.host.children["uno-zone-1"].label)
}

k.section("A device that can't be removed is reported, not fatal") {
    def (a, conn, env) = k.newPair([zoneCount: 1])
    env.children["uno-zone-1"] = new MockChild(dni: "uno-zone-1", typeName: "UNO Zone Contact", label: "Old")
    a.metaClass.deleteChildDevice = { String dni -> throw new IllegalStateException("in use by a rule") }
    boolean threw = false
    try { conn.save() } catch (Throwable t) { threw = true; println "    ${t}" }
    k.check("saving still completes", !threw)
    k.check("and it says which device is in the way and what to do", a.warns().any { it.contains("uno-zone-1") && it.contains("press Done again") }, a.warns())
}

k.section("A missing driver is reported without breaking the connection") {
    def (a, conn, env) = k.newPair([zoneCount: 2])
    conn.metaClass.addChildDevice = { String ns, String type, String dni, Map props -> throw new IllegalStateException("driver not found: ${type}") }
    boolean threw = false
    try { conn.save() } catch (Throwable t) { threw = true; println "    ${t}" }
    k.check("syncing devices doesn't throw", !threw)
    k.check("each missing driver is named", conn.warns().any { it.contains("UNO Partition") } && conn.warns().any { it.contains("UNO Zone Contact") }, conn.warns())

    conn.initialize(); conn.parse(k.hex("Login:")); conn.parse(k.hex("OK\r\n"))
    boolean fed = true
    try { k.feed(conn, "%01," + bitmap([1]) + "\$"); k.feed(conn, "%02,0100000000000000\$") } catch (Throwable t) { fed = false }
    k.check("frames still arrive and the connection device keeps its own state", fed && conn.lastAttr("alarmSystemStatus") == "disarmed", conn.warns())
}

k.section("Without the app's plan nothing happens") {
    def (a, conn, env) = k.newPair([zoneCount: 2])
    conn.parent = null
    boolean threw = false
    try { conn.syncDevices() } catch (Throwable t) { threw = true }
    k.check("a missing parent is fine", !threw && conn.host.children.isEmpty(), conn.host.children.keySet())

    conn.parent = new MockApp()
    conn.parent.metaClass.devicePlan = { -> throw new IllegalStateException("app busy") }
    try { conn.syncDevices() } catch (Throwable t) { threw = true }
    k.check("so is a plan that can't be read, and it says so", !threw && conn.warns().any { it.contains("device plan") }, conn.warns())
}

k.section("Zones outside the zone set keep their devices") {
    def (a, conn, env) = k.newPair([zoneSet: "1-3"])
    conn.save()
    a.settings.zoneSet = "1"
    conn.save()
    k.check("shrinking the set deletes nothing", conn.host.children.keySet().findAll { it.startsWith("uno-zone-") }.sort() == ["uno-zone-1", "uno-zone-2", "uno-zone-3"] && conn.host.deleted.isEmpty(), conn.host.children.keySet())

    a.settings.zoneHints = "3:Leak:water"
    conn.save()
    k.check("but they are still kept up to date", conn.host.children["uno-zone-3"].typeName == "UNO Zone Water" && conn.host.children["uno-zone-3"].label == "Leak")
}

k.section("Zones are not pre-created when that is off, and appear when the panel reports them") {
    def (a, conn, env) = k.newPair([zoneCount: 3, autoCreateConfiguredZones: false])
    conn.save()
    k.check("only the partition is made at first", conn.host.children.keySet() == ["uno-partition-1"] as Set, conn.host.children.keySet())

    conn.initialize(); conn.parse(k.hex("Login:")); conn.parse(k.hex("OK\r\n"))
    k.feed(conn, "%01," + bitmap([2]) + "\$")
    k.check("a zone gets its device when it first changes, and is updated", conn.host.children["uno-zone-2"]?.last("contact") == "open", conn.host.children.keySet())
    k.check("a zone outside the set never does", !conn.host.children.containsKey("uno-zone-9"))
}

k.section("The partition sees the panel's updates") {
    def (a, conn, env) = k.newPair([zoneCount: 2, partitionSet: "1-2"])
    conn.save()
    conn.initialize(); conn.parse(k.hex("Login:")); conn.parse(k.hex("OK\r\n"))
    k.feed(conn, "%02,0105000000000000\$")
    k.check("each partition gets its own state", conn.host.children["uno-partition-1"].alarmStates == ["disarmed"] && conn.host.children["uno-partition-2"].alarmStates == ["armedAway"], [conn.host.children["uno-partition-1"].alarmStates, conn.host.children["uno-partition-2"].alarmStates])
}

k.section("The app can't be asked: existing devices are left as they are") {
    def (a, conn, env) = k.newPair([zoneCount: 3, zoneHints: "2:Garage:contact"])
    conn.save()
    Map kids = conn.host.children
    a.metaClass.zoneSpec = { Integer z -> throw new IllegalStateException("app busy") }
    conn.host.deleted.clear()
    conn.ensureZoneFor(2, "smoke")
    k.check("a fire event doesn't retype a zone the app says is a contact", kids["uno-zone-2"].typeName == "UNO Zone Contact" && conn.host.deleted.isEmpty(), conn.host.deleted)
    k.check("or rename it", kids["uno-zone-2"].label == "Garage")

    conn.ensureZoneFor(9, null)
    k.check("a zone with no device yet is still made, so its updates aren't lost", kids["uno-zone-9"]?.typeName == "UNO Zone Contact", kids.keySet())
}

k.section("A partition taken out of the set doesn't count as a second one") {
    def (a, conn, env) = k.newPair([zoneCount: 1, partitionSet: "1-2"])
    conn.save()
    a.settings.partitionSet = "1"
    conn.save()
    Map kids = conn.host.children
    k.check("its device stays", kids.containsKey("uno-partition-2"))
    conn.forwardPartitionSummary([openZones: "zone 3"])
    k.check("the partition still in the set shows the zone lists", kids["uno-partition-1"].last("openZones") == "zone 3", kids["uno-partition-1"].events)
    k.check("the one taken out does not", kids["uno-partition-2"].last("openZones") == null)
}

k.section("A device id that is taken by the old copy") {
    def (a, conn, env) = k.newPair([zoneCount: 3])
    // The old zone 1 can't be deleted (something uses it), so the new one can't be made with the same id.
    def old = new MockChild(dni: "uno-zone-1", typeName: "UNO Zone Contact", label: "Front Door", caps: ["ContactSensor"] as Set)
    env.children["uno-zone-1"] = old
    a.metaClass.deleteChildDevice = { String dni -> throw new IllegalStateException("in use by a rule") }
    int attempts = 0
    def realAdd = conn.host.&make
    conn.metaClass.addChildDevice = { String ns, String type, String dni, Map props ->
        if (dni == "uno-zone-1") { attempts++; throw new IllegalStateException("A device with DNI 'uno-zone-1' already exists") }
        realAdd(type, dni, props)
    }
    conn.save()
    k.check("the others are made, and the failure is explained once", conn.host.children.keySet().containsAll(["uno-zone-2", "uno-zone-3"]) && conn.warns().count { it.contains("uno-zone-1") && it.contains("already exists") && it.contains("Sync devices") } == 1, conn.warns())

    conn.initialize(); conn.parse(k.hex("Login:")); conn.parse(k.hex("OK\r\n"))
    int before = attempts
    k.feed(conn, "%01," + bitmap([1]) + "\$")
    k.check("updates keep going to the old device, so rules on it keep working", old.last("contact") == "open", old.events)
    k.feed(conn, "%01," + bitmap([]) + "\$")
    k.feed(conn, "%01," + bitmap([1]) + "\$")
    k.check("it is not retried on every change, and the log isn't flooded", attempts == before && conn.warns().count { it.contains("uno-zone-1") } == 1, [attempts, conn.warns().size()])

    conn.nowMs += 301000L
    k.feed(conn, "%01," + bitmap([]) + "\$")
    k.check("it is tried again after five minutes", attempts == before + 1, attempts)

    attempts = 0
    conn.syncDevices()
    k.check("and at once when Sync devices is pressed", attempts >= 1, attempts)

    env.children.remove("uno-zone-1")
    conn.metaClass.addChildDevice = { String ns, String type, String dni, Map props -> realAdd(type, dni, props) }
    conn.syncDevices()
    k.check("once the old one is gone the new one is made", conn.host.children.containsKey("uno-zone-1"), conn.host.children.keySet())
}

k.section("The partition and zone devices talk to the connection device") {
    // The partition and zone drivers send their commands to their parent, which is now the connection device.
    def conn = k.newConn()
    ["armStay", "armAway", "armStayBypass", "armAwayBypass", "disarm", "chimeOn", "chimeOff", "bypassOpenZones", "unbypassAllZones",
     "sendKeys", "panic", "refreshAll", "bypassZone", "unbypassZone"].each { String name ->
        k.check("the connection device has ${name}", conn.metaClass.respondsTo(conn, name).size() > 0, name)
    }
    k.check("armStay takes (partition, code) as the partition driver sends it", conn.metaClass.respondsTo(conn, "armStay", [1, "1234"] as Object[]).size() > 0)
    k.check("disarm takes (code, partition)", conn.metaClass.respondsTo(conn, "disarm", ["1234", 2] as Object[]).size() > 0)
    k.check("and the app no longer has the partition and zone helpers", !k.newApp([:])[0].metaClass.respondsTo(k.newApp([:])[0], "upsertDiscoveredZone"))
}
