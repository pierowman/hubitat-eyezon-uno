// App: selective save, status device and panel summary, offline alert, quiet hours, filters, repeat suppression,
// stale sensors and the new pass-through commands.

def at = { int h, int m -> Calendar c = Calendar.getInstance(); c.set(2026, 0, 15, h, m, 0); c.set(Calendar.MILLISECOND, 0); c.timeInMillis }

k.section("Saving only restarts the connection when it has to") {
    def (a, env) = k.newApp([zoneCount: 3])
    a.initialize()
    def conn = env.children["uno-connection"]
    k.check("the first save pushes every setting and starts the connection", conn.settingsUpdated.size() == 19 && a.pending.containsKey("initializeConnection"), [conn.settingsUpdated.size(), a.pending])
    k.check("the password is never copied into the app's state", a.state.pushedSettings.size() == 19 && a.state.pushedSettings.values().every { !it.contains("pw") }, a.state.pushedSettings)

    a.pending.clear(); conn.settingsUpdated.clear()
    a.updated()
    k.check("saving with no changes pushes nothing and leaves the connection alone", conn.settingsUpdated.isEmpty() && a.pending.isEmpty(), [conn.settingsUpdated, a.pending])

    a.settings.notifyAlarms = false; a.settings.partitionHints = "1:Home"; a.settings.userNames = "2:Alice"
    a.updated()
    k.check("names and notification options don't touch the connection", conn.settingsUpdated.isEmpty() && a.pending.isEmpty())

    a.settings.enablePanic = true
    a.updated()
    k.check("a behaviour setting is pushed without a restart", conn.settingsUpdated*.getAt(0) == ["enablePanic"] && !a.pending.containsKey("initializeConnection"), [conn.settingsUpdated, a.pending])

    conn.settingsUpdated.clear()
    a.settings.ip = "9.9.9.9"
    a.updated()
    k.check("a new address is pushed and restarts it", conn.settingsUpdated*.getAt(0) == ["ip"] && a.pending.containsKey("initializeConnection"), [conn.settingsUpdated, a.pending])

    ["port": 4026, "password": "other", "zoneCount": 4, "zoneSet": "1-2", "keepaliveSeconds": "300", "watchdogMinutes": "5", "restUsername": "bob"].each { String name, def value ->
        a.pending.clear()
        a.settings[name] = value
        a.updated()
        k.check("changing ${name} restarts the connection", a.pending.containsKey("initializeConnection"), a.pending)
    }

    a.pending.clear(); conn.settingsUpdated.clear()
    a.settings.zoneSet = ""
    a.settings.zoneCount = 4
    a.updated()
    a.pending.clear()
    a.settings.zoneHints = "1:Leak:water"
    a.updated()
    k.check("a recreated device is announced to without a reconnect", env.children["uno-zone-1"].typeName == "UNO Zone Water" && a.pending.containsKey("resyncConnection") && !a.pending.containsKey("initializeConnection"), a.pending)

    a.state.startedVersion = "1.2.0"
    a.pending.clear()
    a.updated()
    k.check("an updated app restarts the connection once", a.pending.containsKey("initializeConnection") && a.state.startedVersion == "1.3.0")

    env.children.remove("uno-connection")
    a.pending.clear()
    a.updated()
    def fresh = env.children["uno-connection"]
    k.check("a recreated connection device gets every setting again", fresh.settingsUpdated.size() == 19 && a.pending.containsKey("initializeConnection"), fresh.settingsUpdated.size())
}

k.section("Status device and panel summary") {
    def (a, env) = k.newApp([zoneCount: 1])
    a.initialize()
    k.check("not created unless asked for", env.children["uno-status"] == null)
    def conn = env.children["uno-connection"]
    conn.sendEvent(name: "openZones", value: "Front Door (zone 5)")
    conn.sendEvent(name: "bypassedZones", value: "none")
    conn.sendEvent(name: "troubleSummary", value: "AC failure")

    a.settings.createStatusDevice = true
    a.updated()
    def s = env.children["uno-status"]
    k.check("created when switched on", s != null && s.typeName == "UNO Status" && env.created.contains(["uno-status", "UNO Status"]), env.created)
    k.check("and brought up to date from the connection device", s.last("openZones") == "Front Door (zone 5)" && s.last("bypassedZones") == "none" && s.last("troubleSummary") == "AC failure", s.events)
    k.check("with the current health", s.last("healthStatus") == "offline")

    a.notifyPanelSummary([openZones: "zone 7", troubleSummary: "none"])
    k.check("summaries are forwarded to it", s.last("openZones") == "zone 7" && s.last("troubleSummary") == "none")
    def p1 = env.children["uno-partition-1"]
    k.check("with one partition its device shows the zones, but not the troubles", p1.last("openZones") == "zone 7" && p1.last("troubleSummary") == null, p1.events)

    def (m, envM) = k.newApp([zoneCount: 1, partitionSet: "1-2", createStatusDevice: true])
    m.initialize()
    m.notifyPanelSummary([openZones: "zone 7"])
    k.check("with several partitions only the status device shows them (zones aren't tied to a partition)", envM.children["uno-status"].last("openZones") == "zone 7" && envM.children["uno-partition-1"].last("openZones") == null && envM.children["uno-partition-2"].last("openZones") == null)

    def (n, envN) = k.newApp([zoneCount: 1])
    n.initialize()
    n.notifyPanelSummary([openZones: "zone 7"])
    k.check("a summary with no status device is fine", envN.children["uno-status"] == null && envN.children["uno-partition-1"].last("openZones") == "zone 7")

    def (x, envX) = k.newApp([zoneCount: 1, createStatusDevice: true])
    x.metaClass.addChildDevice = { String ns, String type, String dni, Map props ->
        if (type == "UNO Status") throw new IllegalStateException("driver not found")
        envX.created << [dni, type]; envX.children[dni] = new MockChild(dni: dni, typeName: type, label: props.label)
    }
    x.initialize()
    k.check("a missing driver is reported without breaking the rest", x.warns().any { it.contains("UNO Status") } && envX.children["uno-connection"] != null && envX.children["uno-partition-1"] != null, x.warns())
}

k.section("Connection lost alert") {
    def (a, env) = k.newApp([zoneCount: 1])
    a.initialize()
    MockNotifier n = new MockNotifier()

    a.notifyHealth("offline")
    k.check("nothing is scheduled without notification devices", !a.pending.containsKey("offlineAlert"))

    a.settings.notifyDevices = [n]
    a.notifyHealth("online")
    a.notifyHealth("offline")
    k.check("the alert is scheduled for 5 minutes by default", a.pending["offlineAlert"] == 300, a.pending)
    a.notifyHealth("online")
    k.check("a recovery before then cancels it, silently", !a.pending.containsKey("offlineAlert") && n.messages.isEmpty(), n.messages)

    a.notifyHealth("offline")
    a.offlineAlert()
    k.check("after the wait a message is sent", n.messages.size() == 1 && n.messages[0].contains("connection lost"), n.messages)
    a.notifyHealth("online")
    k.check("and so is the recovery", n.messages.size() == 2 && n.messages[1] == "UNO connection restored", n.messages)
    a.notifyHealth("online")
    k.check("only once", n.messages.size() == 2)

    a.settings.notifyOfflineMinutes = "15"
    a.notifyHealth("offline")
    k.check("the wait is a setting", a.pending["offlineAlert"] == 900, a.pending)
    a.settings.notifyOfflineMinutes = "0"
    a.pending.clear()
    a.notifyHealth("offline")
    k.check("and can be switched off", !a.pending.containsKey("offlineAlert"))

    a.state.health = "online"
    n.messages.clear()
    a.offlineAlert()
    k.check("a stale timer after recovery sends nothing", n.messages.isEmpty())
}

k.section("Quiet hours, partition filter and repeats") {
    def (a, env) = k.newApp([zoneCount: 1])
    a.initialize()
    MockNotifier n = new MockNotifier()
    a.settings.notifyDevices = [n]
    a.settings.notifyArmDisarm = true

    def trouble = { String text, boolean restore = false, int partition = 1 -> a.notifyEvent([category: "trouble", text: text, partition: partition, restore: restore]) }
    def alarm = { a.notifyEvent([category: "alarm", text: "Fire alarm", partition: 1, restore: false]) }
    def arm = { a.notifyEvent([category: "arm", text: "Armed by Alice", partition: 1, restore: true]) }

    a.settings.quietStart = "2026-01-01T22:00:00.000-0500"
    a.settings.quietEnd = "2026-01-01T07:00:00.000-0500"
    a.nowMs = at(23, 0)
    trouble("AC power loss"); arm(); alarm()
    k.check("overnight quiet hours skip troubles and arm/disarm but never alarms", n.messages == ["Fire alarm"], n.messages)

    a.nowMs = at(3, 0)
    trouble("AC power loss"); arm()
    k.check("after midnight is still quiet", n.messages == ["Fire alarm"], n.messages)

    a.nowMs = at(12, 0)
    trouble("AC power loss"); arm()
    k.check("outside quiet hours they are sent", n.messages == ["Fire alarm", "AC power loss", "Armed by Alice"], n.messages)

    n.messages.clear()
    a.settings.quietStart = "2026-01-01T09:00:00.000-0500"
    a.settings.quietEnd = "2026-01-01T17:00:00.000-0500"
    trouble("Sensor low battery: zone 3")
    k.check("a same-day window works", n.messages.isEmpty())
    a.nowMs = at(18, 0)
    trouble("Sensor low battery: zone 3")
    k.check("and ends", n.messages == ["Sensor low battery: zone 3"], n.messages)

    n.messages.clear()
    a.settings.quietEnd = a.settings.quietStart
    a.nowMs = at(10, 0)
    trouble("Low system battery")
    k.check("equal start and end means no quiet hours", n.messages == ["Low system battery"])
    a.settings.quietStart = null; a.settings.quietEnd = null

    n.messages.clear()
    trouble("Sensor tamper trouble: zone 4"); trouble("Sensor tamper trouble: zone 4")
    k.check("the same trouble isn't repeated", n.messages == ["Sensor tamper trouble: zone 4"], n.messages)
    trouble("Sensor tamper trouble: zone 5")
    k.check("a different one is sent", n.messages.size() == 2)
    trouble("Sensor tamper trouble restored: zone 4", true)
    k.check("a restoral is sent", n.messages.size() == 3)
    trouble("Sensor tamper trouble: zone 4")
    k.check("and lets the next occurrence through", n.messages.size() == 4 && n.messages[3] == "Sensor tamper trouble: zone 4", n.messages)
    trouble("Sensor tamper trouble: zone 4")
    a.nowMs += 7 * 3600 * 1000L
    trouble("Sensor tamper trouble: zone 4")
    k.check("after 6 hours it is sent again", n.messages.size() == 5, n.messages)
    alarm(); alarm()
    k.check("alarms are never held back as repeats", n.messages.count { it == "Fire alarm" } == 2, n.messages)

    n.messages.clear()
    a.settings.notifyPartitions = "2"
    alarm()
    k.check("an alarm on another partition is skipped", n.messages.isEmpty())
    a.notifyEvent([category: "alarm", text: "Fire alarm", partition: 2, restore: false])
    a.notifyEvent([category: "alarm", text: "Power", partition: 0, restore: false])
    k.check("the chosen partition, and system-wide events, are sent", n.messages == ["Fire alarm", "Power"], n.messages)

    def (m, envM) = k.newApp([zoneCount: 1, partitionSet: "1-2"])
    m.initialize()
    MockNotifier mn = new MockNotifier()
    m.settings.notifyDevices = [mn]
    m.notifyEvent([category: "alarm", text: "Power", partition: 0, restore: false])
    k.check("a system-wide event isn't labelled with a partition", mn.messages == ["Power"], mn.messages)
}

k.section("Stale sensors") {
    def (a, env) = k.newApp([zoneCount: 3, createStatusDevice: true])
    a.nowMs = at(12, 0)
    a.initialize()
    k.check("not scheduled while off", !a.scheduleCalls.any { it[0] == "staleSensorCheck" })

    a.settings.staleSensorDays = 3
    a.updated()
    k.check("scheduled daily when on", a.scheduleCalls.any { it[0] == "staleSensorCheck" && it[1] == "0 0 9 * * ?" }, a.scheduleCalls)

    MockNotifier n = new MockNotifier()
    a.settings.notifyDevices = [n]
    def fmt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    env.children["uno-zone-1"].sendEvent(name: "lastFault", value: fmt.format(new Date(a.nowMs - 5 * 86400000L)))
    env.children["uno-zone-2"].sendEvent(name: "lastFault", value: fmt.format(new Date(a.nowMs - 1 * 86400000L)))
    env.children["uno-zone-1"].label = "Garage Door"

    a.staleSensorCheck()
    String status = env.children["uno-status"].last("staleSensors")
    k.check("a zone quiet for longer than the limit is stale", status == "Garage Door", status)
    k.check("a zone that has never triggered isn't judged until the check has run that long", !status.contains("Zone 3"), status)
    k.check("a notification is sent", n.messages == ["No activity for 3 days: Garage Door"], n.messages)

    a.staleSensorCheck()
    k.check("the same list isn't sent again", n.messages.size() == 1)

    a.nowMs += 4 * 86400000L
    a.staleSensorCheck()
    k.check("later, quiet zones and never-triggered zones are included", env.children["uno-status"].last("staleSensors") == "Garage Door, Zone 2, Zone 3" || env.children["uno-status"].last("staleSensors") == "Garage Door, Zone 2, Zone 3".toString(), env.children["uno-status"].last("staleSensors"))
    k.check("and a changed list is sent", n.messages.size() == 2 && n.messages[1].contains("Zone 3"), n.messages)

    env.children["uno-zone-1"].sendEvent(name: "lastFault", value: fmt.format(new Date(a.nowMs)))
    env.children["uno-zone-2"].sendEvent(name: "lastFault", value: fmt.format(new Date(a.nowMs)))
    env.children["uno-zone-3"].sendEvent(name: "lastFault", value: fmt.format(new Date(a.nowMs)))
    a.staleSensorCheck()
    k.check("when everything has reported the list is empty", env.children["uno-status"].last("staleSensors") == "none")

    a.settings.staleSensorDays = 0
    a.updated()
    k.check("switching it off removes the schedule", a.unscheduleCalls.contains("staleSensorCheck"))
}

k.section("Arm with bypass is passed through") {
    def (a, env) = k.newApp([zoneCount: 1])
    a.initialize()
    a.armStayBypass(2, "1234"); a.armAwayBypass()
    k.check("to the connection device", env.children["uno-connection"].callArgs.containsAll([["armStayBypass", [2, "1234"]], ["armAwayBypass", [1, null]]]), env.children["uno-connection"].callArgs)
}

k.section("Zones per partition") {
    def (a, env) = k.newApp([zoneCount: 5, partitionSet: "1-2", partitionZones: "1:1-3\n2:4-5"])
    a.initialize()
    def conn = env.children["uno-connection"]
    k.check("the validated assignments are pushed in compact form", conn.settingsUpdated.any { it[0] == "partitionZones" && it[1].value == "1:1,2,3;2:4,5" }, conn.settingsUpdated.findAll { it[0] == "partitionZones" })
    k.check("the preview says what each partition gets", a.partitionZonesPreview().contains("Partition 1: 3 zones (1, 2, 3)") && a.partitionZonesPreview().contains("Partition 2: 2 zones (4, 5)") && !a.partitionZonesPreview().contains("Ignored"), a.partitionZonesPreview())

    def (b, envB) = k.newApp([zoneCount: 5, partitionSet: "1-2", partitionZones: "1:1-3;2:3,4,9\n3:1\nnonsense\n1:x"])
    b.initialize()
    String preview = b.partitionZonesPreview()
    k.check("a zone can't be in two partitions", preview.contains("zone 3 is already in partition 1"), preview)
    k.check("a zone that isn't configured is ignored", preview.contains("zone 9 is not one of your zones"), preview)
    k.check("a partition that isn't created is ignored", preview.contains("partition 3 is not one of the partitions you create"), preview)
    k.check("a line that isn't partition:zones is reported", preview.contains("'nonsense' is not partition:zones") && preview.contains("not valid zone numbers: x"), preview)
    k.check("zones left out are listed as unassigned", preview.contains("Not assigned") && preview.contains("5"), preview)
    k.check("only the valid part is pushed", envB.children["uno-connection"].settingsUpdated.any { it[0] == "partitionZones" && it[1].value == "1:1,2,3;2:4" }, envB.children["uno-connection"].settingsUpdated.findAll { it[0] == "partitionZones" })

    def (n, envN) = k.newApp([zoneCount: 5])
    n.initialize()
    k.check("nothing pushed means no assignments", envN.children["uno-connection"].settingsUpdated.any { it[0] == "partitionZones" && it[1].value == "" } && n.partitionZonesPreview().startsWith("No zones assigned"))

    // With one partition and no assignments the panel-wide lists are copied to it; assignments take over from that.
    def (m, envM) = k.newApp([zoneCount: 5, partitionZones: "1:1-2"])
    m.initialize()
    m.notifyPanelSummary([openZones: "zone 4", bypassedZones: "none"])
    k.check("with assignments the app no longer copies the panel-wide lists to the partition", envM.children["uno-partition-1"].last("openZones") == null, envM.children["uno-partition-1"].events)
    envM.children["uno-connection"].sendEvent(name: "openZones", value: "zone 4")
    m.applyPanelSummary(envM.children["uno-partition-1"])
    k.check("nor to a newly created partition device", envM.children["uno-partition-1"].last("openZones") == null)

    // Changing assignments re-announces without a reconnect.
    conn.settingsUpdated.clear(); a.pending.clear()
    a.settings.partitionZones = "1:1-2\n2:3-5"
    a.updated()
    k.check("a changed assignment is pushed, and the panel is asked to announce again without reconnecting", conn.settingsUpdated*.getAt(0) == ["partitionZones"] && a.pending.containsKey("resyncConnection") && !a.pending.containsKey("initializeConnection"), [conn.settingsUpdated, a.pending])
}

k.section("Keepalive and command timeout settings") {
    def (a, env) = k.newApp([zoneCount: 1])
    a.initialize()
    def conn = env.children["uno-connection"]
    def pushed = { String n -> conn.settingsUpdated.findAll { it[0] == n }*.getAt(1)*.value }
    k.check("keepalive is a minute and the timeout 4 seconds by default", pushed("keepaliveSeconds") == ["60"] && pushed("commandTimeoutSeconds") == ["4"], [pushed("keepaliveSeconds"), pushed("commandTimeoutSeconds")])
    k.check("the old heartbeat setting is no longer pushed", !conn.settingsUpdated.any { it[0] == "heartbeatMinutes" })

    a.pending.clear(); conn.settingsUpdated.clear()
    a.settings.commandTimeoutSeconds = "10"
    a.updated()
    k.check("a new command timeout is pushed without a restart", pushed("commandTimeoutSeconds") == ["10"] && !a.pending.containsKey("initializeConnection"), a.pending)

    a.settings.keepaliveSeconds = "30"
    a.updated()
    k.check("a new keepalive restarts the connection so the schedule changes", pushed("keepaliveSeconds") == ["30"] && a.pending.containsKey("initializeConnection"), a.pending)

    def (o, envO) = k.newApp([zoneCount: 1, heartbeatMinutes: "5"])
    k.check("an install from before keeps its heartbeat as the keepalive", o.keepaliveDefault() == "300")
    o.initialize()
    k.check("and pushes it", envO.children["uno-connection"].settingsUpdated.any { it[0] == "keepaliveSeconds" && it[1].value == "300" })
    k.check("a new install defaults to a minute", k.newApp([zoneCount: 1])[0].keepaliveDefault() == "60")
}

k.section("Changing partitions or zones after zones were assigned never blocks saving (envisalink_new: assignments left invalid)") {
    def (a, env) = k.newApp([zoneCount: 5, partitionSet: "1-2", partitionZones: "1:1-3\n2:4-5"])
    a.initialize()
    def conn = env.children["uno-connection"]
    def pushed = { conn.settingsUpdated.findAll { it[0] == "partitionZones" }.last()[1].value }
    k.check("starts valid", pushed() == "1:1,2,3;2:4,5" && !a.statusSummary().contains("WARNING"), a.statusSummary())

    // Drop partition 2: its assignment now refers to a partition that isn't created.
    a.settings.partitionSet = "1"
    boolean saved = true
    try { a.updated() } catch (Throwable t) { saved = false; println "    ${t}" }
    k.check("removing a partition still saves", saved)
    k.check("the assignment for it is ignored, not pushed", pushed() == "1:1,2,3", pushed())
    k.check("the preview says why", a.partitionZonesPreview().contains("partition 2 is not one of the partitions you create"), a.partitionZonesPreview())
    k.check("and the status section warns", a.statusSummary().contains("WARNING: 1 part of Zones per partition is being ignored"), a.statusSummary())
    k.check("what was typed is left alone, not silently rewritten", a.settings.partitionZones == "1:1-3\n2:4-5")

    // Put it back: nothing was lost.
    a.settings.partitionSet = "1-2"
    a.updated()
    k.check("restoring the partition brings its zones back", pushed() == "1:1,2,3;2:4,5" && !a.statusSummary().contains("WARNING"), pushed())

    // Shrink the zones: zones 4 and 5 no longer exist.
    a.settings.zoneCount = 3
    boolean saved2 = true
    try { a.updated() } catch (Throwable t) { saved2 = false; println "    ${t}" }
    k.check("removing zones still saves", saved2)
    k.check("assignments to missing zones are ignored", pushed() == "1:1,2,3", pushed())
    k.check("and reported", a.partitionZonesPreview().contains("zone 4 is not one of your zones") && a.partitionZonesPreview().contains("zone 5 is not one of your zones") && a.statusSummary().contains("WARNING"), a.partitionZonesPreview())

    a.settings.zoneSet = "1,2"
    a.updated()
    k.check("a zone set that excludes more works the same way", pushed() == "1:1,2", pushed())

    a.settings.zoneSet = null; a.settings.zoneCount = 5
    a.updated()
    k.check("and everything returns when the zones do", pushed() == "1:1,2,3;2:4,5")

    // Everything assigned becomes invalid at once.
    a.settings.partitionSet = "3"
    boolean saved3 = true
    try { a.updated() } catch (Throwable t) { saved3 = false }
    k.check("even when no assignment is valid any more, it saves", saved3 && pushed() == "", pushed())
    k.check("and that is no longer an assignment, so partitions fall back to the default behaviour", a.hasPartitionZones() == false)
}

k.section("The page refreshes its previews as settings change") {
    def src = new File(k.repo, "apps/UNOIntegrationApp.groovy").text
    ["partitionSet", "partitionZones", "zoneCount", "zoneSet", "pgmSet"].each { String name ->
        // The input call spans several lines; find it and check it asks the page to re-render on change.
        int pos = src.indexOf("input \"${name}\"")
        int end = src.indexOf("paragraph", pos)
        String call = src.substring(pos, end > pos ? end : pos + 600)
        k.check("${name} re-renders the page when changed, so its preview and warning update straight away", call.contains("submitOnChange: true"), call.take(200))
    }
}
