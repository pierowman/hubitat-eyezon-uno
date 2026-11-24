definition(
    name: "EyezOn UNO Integration",
    namespace: "uno",
    author: "Eric Hess",
    description: "Hubitat integration for the EyezOn UNO alarm system",
    category: "Safety & Security",
    importUrl: "https://raw.githubusercontent.com/pierowman/hubitat-eyezon-uno/main/apps/UNOIntegrationApp.groovy",
    iconUrl: "https://raw.githubusercontent.com/hubitat/HubitatPublic/master/resources/icons/app-Coordinator.png",
    iconX2Url: "https://raw.githubusercontent.com/hubitat/HubitatPublic/master/resources/icons/app-Coordinator@2x.png",
    iconX3Url: "https://raw.githubusercontent.com/hubitat/HubitatPublic/master/resources/icons/app-Coordinator@2x.png"
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", install: true, uninstall: true) {
        section("Status") {
            paragraph statusSummary()
        }

        section("UNO Connection") {
            input "ip", "text", title: "UNO IP Address", required: true
            input "port", "number", title: "UNO Port", defaultValue: 4025, required: true
            input "password", "password", title: "UNO Local/TPI Password", required: true
            input "restUsername", "text", title: "UNO REST Username", defaultValue: "user", required: false
            input "masterCode", "password", title: "Master Code (optional, for disarm)", required: false
        }

        section("Partitions") {
            input "partitionSet", "text",
                title: "Partitions to create (e.g. 1 or 1-2 or 1,3)",
                description: "The UNO supports partitions 1-8. Leave blank for partition 1 only.",
                submitOnChange: true,
                required: false
            paragraph rangePreview(settings.partitionSet, 1, 8, "partition", "partition 1 only")
            input "partitionLabel", "text",
                title: "Partition 1 device name",
                description: "Friendly name for partition 1. Leave blank to use the name configured on the UNO.",
                required: false
            input "partitionHints", "textarea",
                title: "Partition names (optional)",
                description: "One per line as partitionNumber:Name, e.g. 2:Guest House",
                required: false
            input "partitionZones", "textarea",
                title: "Zones per partition (optional)",
                description: "One per line as partitionNumber:zones, e.g. 1:1-8 and 2:9-16. The UNO doesn't say which partition a zone is in, so tell it here. Each partition then shows only its own open and bypassed zones.",
                submitOnChange: true,
                required: false
            paragraph partitionZonesPreview()
        }

        section("Users (optional)") {
            input "userNames", "textarea",
                title: "User names",
                description: "One per line as userNumber:Name, e.g. 2:Alice. Used to say who armed or disarmed. Unnamed users show as 'user N'.",
                required: false
        }

        section("Device names (optional)") {
            input "devicePrefix", "text",
                title: "Device name prefix",
                description: "Put in front of the name of every partition and zone, so they sort together on the Devices page, for example 'Alarm' gives 'Alarm Front Door'. Leave empty for none. Longer than ${maxPrefix()} characters is cut, and a whole name is cut at ${maxLabel()}.",
                submitOnChange: true,
                required: false
            paragraph "Example: ${prefixedLabel('Front Door')}. The UNO itself keeps only 16 characters of each zone name, so use the zone names below for longer ones."
        }

        section("REST Metadata / Startup Sync") {
            input "useRestMetadata", "bool",
                title: "Enable REST metadata and startup sync",
                defaultValue: true
            paragraph "Uses UNO REST endpoint D for labels and endpoint A for startup zone-state sync. TPI remains the real-time event engine."
        }

        section("Zone Discovery") {
            input "zoneCount", "number", title: "Configured Zone Count", defaultValue: 27, required: true, submitOnChange: true
            input "zoneSet", "text",
                title: "Zone set (optional, e.g. 1-8,12,20-27)",
                description: "Only these zones are created and tracked. Overrides Zone Count when filled in.",
                submitOnChange: true,
                required: false
            paragraph rangePreview(settings.zoneSet, 1, 128, "zone", "zones 1-${safeInt(settings.zoneCount, 27)} (from Zone Count)")
            input "holdBypassedOpenZones", "bool",
                title: "Keep a bypassed zone in the open list if it was open when it was bypassed",
                description: "Off by default. Some panels (Honeywell and Ademco) report a bypassed zone as closed whatever it is doing. With this on, a zone that was open when it was bypassed stays in the open zone list, marked (bypassed), until the bypass is removed.",
                defaultValue: false
            input "autoCreateConfiguredZones", "bool", title: "Pre-create the configured zones at install", defaultValue: true
            input "defaultUnknownZoneType", "enum",
                title: "Default type for unknown zones",
                options: [
                    "contact": "Contact",
                    "motion" : "Motion",
                    "smoke"  : "Smoke",
                    "co"     : "CO",
                    "water"  : "Water"
                ],
                defaultValue: "contact",
                required: true
        }

        section("Zone names and types (optional)", hideable: true, hidden: true) {
            paragraph "The names the UNO has stored for your zones are filled in as suggestions. Edit any of them to rename the zone (the UNO keeps only 16 characters of each name, so a longer one has to be typed here). A name or type chosen here wins over Zone Hints. Press Done to apply."
            input "loadZoneNames", "button", title: "Load zone names from the UNO"
            paragraph zoneNamesStatus()
            configuredZones().each { Integer z ->
                String panelName = state.restZoneLabels ? state.restZoneLabels["${z}"] : null
                // Same call shape as every other input on the page (named arguments after the name and type).
                if (panelName) {
                    input "zoneName_${z}", "text", title: "Zone ${z} name", defaultValue: panelName, required: false
                } else {
                    input "zoneName_${z}", "text", title: "Zone ${z} name", description: "No name stored on the UNO", required: false
                }
                input "zoneType_${z}", "enum",
                    title: "Zone ${z} type",
                    options: ["auto": "Automatic (from the name)", "contact": "Contact", "motion": "Motion", "smoke": "Smoke", "co": "CO", "water": "Water"],
                    defaultValue: "auto",
                    required: false
            }
        }

        section("Zone Hints (optional fallback / override)") {
            paragraph """One zone per line.
Format:
zoneNumber:Label:type

Examples:
1:Interconnected Smokes:smoke
2:Dining Room PIR:motion
17:Family Patio Door:contact
22:Kitchen CO:co
23:Kitchen Heat:smoke
25:Office PIR:motion
26:Master Hall PIR:motion
27:Office Door:contact"""
            input "zoneHints", "textarea", title: "Zone Hints", required: false
        }

        section("Safety") {
            input "requireCodeToArm", "bool",
                title: "Require the master code to arm",
                defaultValue: false
            paragraph "Off by default. When on, Arm Stay / Arm Away only work if a code is passed (for example the *WithCode commands) and it matches the master code above. The UNO's arm commands take no code, so this is checked here, not by the UNO. It also blocks arming from HSM or a dashboard keypad, which don't send a code."
            input "enablePanic", "bool",
                title: "Allow panic commands (fire / medical / police)",
                defaultValue: false
            paragraph "Off by default. If your UNO is professionally monitored, a panic command contacts emergency services. When off, panic commands are ignored."
        }

        section("PGM Outputs (optional)") {
            input "pgmSet", "text",
                title: "PGM outputs to create (1-14, e.g. 1 or 1,3-4)",
                description: "UNO only. Each becomes a button that toggles or pulses the output, depending on how the output is programmed on the panel. The UNO reports no state for a PGM.",
                submitOnChange: true,
                required: false
            paragraph rangePreview(settings.pgmSet, 1, 14, "PGM", "no PGM outputs")
            input "pgmNames", "textarea",
                title: "PGM names (optional)",
                description: "One per line as pgmNumber:Name, e.g. 1:Garage Door",
                required: false
        }

        section("Status device and stale sensors (optional)") {
            input "createStatusDevice", "bool",
                title: "Create a UNO Status device",
                description: "One device showing which zones are open or bypassed, the troubles, and stale sensors, for dashboard tiles. Needs the UNO Status driver.",
                defaultValue: false
            input "staleSensorDays", "number",
                title: "Report sensors with no activity for this many days (0 = off)",
                description: "A zone that has never opened or triggered in that time may have a dead sensor. Shown on the status device and sent as a notification when it changes.",
                defaultValue: 0,
                required: false
        }

        section("Notifications (optional)") {
            input "notifyDevices", "capability.notification",
                title: "Send notifications to",
                multiple: true,
                required: false
            input "notifyAlarms", "bool", title: "Alarms (burglary, fire, panic, CO, water and so on)", defaultValue: true
            input "notifyTroubles", "bool", title: "Troubles and their restorals (AC loss, low battery, tamper)", defaultValue: true
            input "notifyArmDisarm", "bool", title: "Armed and disarmed, and by whom", defaultValue: false
            input "notifyPartitions", "text",
                title: "Only for these partitions (e.g. 1 or 1,3; blank = all)",
                required: false
            input "quietStart", "time", title: "Quiet hours start (troubles and arm/disarm only)", required: false
            input "quietEnd", "time", title: "Quiet hours end", required: false
            paragraph "During quiet hours troubles and arm/disarm notices are skipped. Alarms are always sent. The same trouble is not repeated within 6 hours."
            input "notifyOfflineMinutes", "enum",
                title: "Tell me if the UNO connection is down for",
                options: ["0": "Never", "5": "5 minutes", "15": "15 minutes", "30": "30 minutes", "60": "1 hour"],
                defaultValue: "5",
                required: false
        }

        section("Logging / Health") {
            input "debugLogging", "bool", title: "Enable debug logging", defaultValue: false
            input "traceLogging", "bool", title: "Enable trace logging", defaultValue: false
            input "diagnosticEvents", "bool",
                title: "Publish diagnostic events (last heartbeat, last raw message, panel time)",
                defaultValue: false
            paragraph "Off by default: these change on every frame and add a lot of database writes to the hub."
            input "recordTestEvents", "bool",
                title: "Record Contact ID test reports in the event history",
                defaultValue: false
            input "keepaliveSeconds", "enum",
                title: "Keepalive: poll the UNO every",
                description: "A poll proves the link is alive. Shorter notices a dead link sooner.",
                options: ["30":"30 seconds", "60":"1 minute", "300":"5 minutes", "600":"10 minutes"],
                defaultValue: keepaliveDefault(),
                required: true
            input "watchdogMinutes", "enum",
                title: "Reconnect if no frames seen for",
                options: ["3":"3 minutes", "5":"5 minutes", "10":"10 minutes"],
                defaultValue: "3",
                required: true
            input "commandTimeoutSeconds", "enum",
                title: "Wait for a command's reply for",
                description: "A command with no reply is sent once more, and then the connection is restarted. Raise this on a slow or unreliable network.",
                options: ["2":"2 seconds", "4":"4 seconds", "10":"10 seconds", "20":"20 seconds"],
                defaultValue: "4",
                required: true
        }
    }
}

// An install from before keepalive was in seconds keeps the heartbeat it had.
private String keepaliveDefault() {
    Integer minutes = settings.heartbeatMinutes ? safeInt(settings.heartbeatMinutes, 1) : null
    return minutes ? "${minutes * 60}".toString() : "60"
}

def installed() {
    initialize()
}

def updated() {
    initialize()
}

def initialize() {
    logInfo("Initializing UNO integration")

    state.zoneHintsMap = parseZoneHints(settings.zoneHints)
    state.partitionHintsMap = parseNumberedNames(settings.partitionHints)
    state.userNamesMap = parseNumberedNames(settings.userNames)
    state.pgmNamesMap = parseNumberedNames(settings.pgmNames)
    state.restZoneLabels = state.restZoneLabels ?: [:]
    state.restPartitionLabels = state.restPartitionLabels ?: [:]
    state.restSystemName = state.restSystemName ?: null
    // The connection device keeps the names it applied now.
    state.remove("appliedLabels")

    ensureConnectionDevice()

    if (settings.useRestMetadata == true) {
        try {
            loadRestMetadata()
        } catch (e) {
            noteRestFailure(e)
        }
    } else {
        state.restStatus = "disabled"
    }

    removeOldChildren()
    ensurePgmDevices()
    ensureStatusDevice()

    List<String> changed = applyConnectionSettings()
    scheduleStaleCheck()

    // Saving the app only restarts the connection when something it depends on changed (or the app was updated).
    // Cosmetic changes such as names, hints and notification options leave it, and its command queue, alone.
    boolean restart = changed.any { String n -> n in reconnectSettings() } || state.startedVersion != appVersion()
    if (restart) {
        state.startedVersion = appVersion()
        runIn(1, "initializeConnection")
    } else if (((state.deviceChurn ?: 0) as Integer) > 0 || changed.contains("partitionZones") || changed.contains("holdBypassedOpenZones")) {
        // New devices (and new zone assignments, or a changed open-list rule) start knowing nothing, so have the panel announce everything again.
        runIn(1, "resyncConnection")
    }
    // The connection device owns the partitions and zones; it creates and names them from what was just saved.
    runIn(2, "syncConnectionDevices")
    state.appliedPrefix = devicePrefix()
    state.deviceChurn = 0
}

private String appVersion() {
    return "1.4.1"
}

// The settings a running connection cannot pick up on its own.
private List<String> reconnectSettings() {
    return ["ip", "port", "password", "restUsername", "useRestMetadata", "zoneCount", "zoneSet", "keepaliveSeconds", "watchdogMinutes"]
}

private void noteDeviceCreated() {
    state.deviceChurn = ((state.deviceChurn ?: 0) as Integer) + 1
}

def initializeConnection() {
    def conn = getChildDevice("uno-connection")
    if (conn) {
        conn.initialize()
    }
}

def resyncConnection() {
    getChildDevice("uno-connection")?.resyncStates()
}

private void ensureConnectionDevice() {
    String dni = "uno-connection"
    def dev = getChildDevice(dni)

    if (!dev) {
        addChildDevice(
            "uno",
            "UNO Connection",
            dni,
            [
                name       : "UNO Connection",
                label      : "UNO Connection",
                isComponent: false
            ]
        )
        logInfo("Created UNO Connection device")
        // A new device has none of the settings that were pushed to the old one.
        state.pushedSettings = [:]
    } else {
        relabelDefault(dev, "UNO Connection")
    }
}

// ---- Device label prefix ----
// The prefix is for the partitions and zones only (the connection device names them; the app just says what the
// prefix is). The app's own devices (connection, PGM, status) keep their plain names.

private Integer maxPrefix() { return 50 }

// Hubitat doesn't document a limit for a device label; its app labels stop at 255 characters, so that is used here.
private Integer maxLabel() { return 255 }

private String devicePrefix() {
    return (settings.devicePrefix ?: "").toString().trim().take(maxPrefix())
}

// How a partition or zone label comes out, for the example on the settings page.
private String prefixedLabel(String base) {
    String prefix = devicePrefix()
    return prefix ? "${prefix} ${base}".toString() : base
}

// A label with the prefix removed that an earlier version of this app put on it.
private String unprefixedLabel(String label) {
    String applied = (state.appliedPrefix ?: "").toString()
    if (label && applied && label.startsWith(applied + " ")) return label.substring(applied.length() + 1)
    return label
}

// An earlier version put the prefix on every device. A device that still has its default name loses it; one that has
// been renamed by hand is left alone.
private void relabelDefault(def dev, String base) {
    if (unprefixedLabel(dev.label ?: base) != base) return

    try {
        if (dev.label != base) dev.setLabel(base)
    } catch (e) {
        log.warn "Unable to relabel ${base}: ${e}"
    }
}

// The connection device creates the partitions and zones as its own children, so the Devices page lists them under
// it. Before version 1.4.0 they were children of this app, and device ids can't be shared, so those are removed first
// (the connection device makes new ones).
private void removeOldChildren() {
    getChildDevices()?.each { dev ->
        String dni = dev.deviceNetworkId ?: ""
        if (!(dni.startsWith("uno-zone-") || dni.startsWith("uno-partition-"))) return

        // A zone keeps the name its old device had, so a name changed by hand isn't lost in the move.
        rememberLegacyLabel(dni, dev.label)
        try {
            deleteChildDevice(dni)
            logInfo("Removed ${dni}; it is recreated under the UNO Connection device")
        } catch (e) {
            log.warn "Could not remove the old ${dni} (it may be in use by a rule or dashboard). Stop using it there and press Done again; until it is gone the new device can't be made: ${e}"
        }
    }
}

private void rememberLegacyLabel(String dni, def label) {
    if (!dni.startsWith("uno-zone-") || !label) return

    Map legacy = (state.legacyLabels instanceof Map) ? new LinkedHashMap(state.legacyLabels) : [:]
    legacy[dni.substring("uno-zone-".length())] = unprefixedLabel(label.toString())
    state.legacyLabels = legacy
}

// Asks the connection device to create or update its partitions and zones from what was just saved.
def syncConnectionDevices() {
    getChildDevice("uno-connection")?.syncDevices()
}

// What the connection device should create: the prefix, the partitions with their names, whether to pre-create the
// zones, and the type for a zone nothing says anything about.
def devicePlan() {
    Map partitions = [:]
    configuredPartitions().each { Integer p -> partitions[p] = partitionLabelFor(p) }
    return [
        prefix         : devicePrefix(),
        partitions     : partitions,
        autoCreate     : settings.autoCreateConfiguredZones != false,
        defaultZoneType: (settings.defaultUnknownZoneType ?: "contact").toString()
    ]
}

// What the app knows about one zone: the name and type chosen for it (on the page, or in Zone Hints) and the name the
// UNO has stored for it. The connection device decides what to do with them.
def zoneSpec(Integer zoneNum) {
    Map hints = (state.zoneHintsMap instanceof Map) ? state.zoneHintsMap : parseZoneHints(settings.zoneHints)
    Map hint = hints["${zoneNum}"] ?: [:]
    String panel = state.restZoneLabels ? state.restZoneLabels["${zoneNum}"] : null
    String ownName = settings["zoneName_${zoneNum}"]?.toString()?.trim()
    String ownType = settings["zoneType_${zoneNum}"]?.toString()?.trim()
    // The name boxes are pre-filled with the UNO's name, and Hubitat may save a pre-filled value as if it were typed.
    // A name that is just the UNO's own is not a choice: it must not hide the hint or a later change on the UNO.
    if (ownName && ownName == panel) ownName = null
    return [
        label : ownName ?: hint.label?.trim() ?: null,
        type  : (ownType && ownType != "auto") ? ownType : (hint.type?.trim() ?: null),
        panel : panel,
        legacy: state.legacyLabels instanceof Map ? state.legacyLabels["${zoneNum}"] : null
    ]
}

// PGM outputs are optional buttons; one device per output in the set.
private void ensurePgmDevices() {
    Map names = (state.pgmNamesMap instanceof Map) ? state.pgmNamesMap : [:]
    parseRange(settings.pgmSet, 1, 14).values.each { Integer pgm ->
        String dni = "uno-pgm-${pgm}"
        String base = names["${pgm}"]?.toString()?.trim() ?: "PGM ${pgm}"
        String label = base
        def dev = getChildDevice(dni)

        if (!dev) {
            def created = addChildDevice("uno", "UNO PGM", dni, [name: base, label: label, isComponent: true])
            applyHealth(created)
            noteDeviceCreated()
            logInfo("Created PGM device: ${label}")
        } else {
            try {
                if (dev.label != label) {
                    dev.setLabel(label)
                    logInfo("Updated PGM ${pgm} label to ${label}")
                }
            } catch (e) {
                log.warn "Unable to update PGM label: ${e}"
            }
        }
    }
}

// Priority: partition hint > partition 1 device name > name configured on the UNO > default.
private String partitionLabelFor(Integer partition) {
    Map hints = (state.partitionHintsMap instanceof Map) ? state.partitionHintsMap : [:]
    Map restLabels = (state.restPartitionLabels instanceof Map) ? state.restPartitionLabels : [:]

    String label = hints["${partition}"]?.toString()?.trim()
    if (!label && partition == 1) label = settings.partitionLabel?.toString()?.trim()
    if (!label) label = restLabels["${partition}"]?.toString()?.trim()
    if (!label) label = (partition == 1) ? "House Alarm" : "Partition ${partition}"
    return label
}

// Pushes the app's settings to the connection device and returns the names of those that changed since last time.
// Only a hash of each value is remembered, so the password is never copied into the app's state.
private List<String> applyConnectionSettings() {
    def conn = getChildDevice("uno-connection")
    if (!conn) return []

    Map desired = [
        ip              : [settings.ip, "text"],
        port            : ["${settings.port ?: 4025}", "number"],
        password        : [settings.password, "password"],
        restUsername    : [settings.restUsername ?: "user", "text"],
        masterCode      : [settings.masterCode ?: "", "password"],
        zoneCount       : ["${safeInt(settings.zoneCount, 27)}", "number"],
        zoneSet         : [settings.zoneSet ?: "", "text"],
        enablePanic     : [settings.enablePanic == true, "bool"],
        requireCodeToArm: [settings.requireCodeToArm == true, "bool"],
        diagnosticEvents: [settings.diagnosticEvents == true, "bool"],
        recordTestEvents: [settings.recordTestEvents == true, "bool"],
        useRestMetadata : [settings.useRestMetadata == true, "bool"],
        debugLogging    : [settings.debugLogging == true, "bool"],
        traceLogging    : [settings.traceLogging == true, "bool"],
        keepaliveSeconds: [(settings.keepaliveSeconds ?: keepaliveDefault()).toString(), "enum"],
        watchdogMinutes : [(settings.watchdogMinutes ?: "3").toString(), "enum"],
        commandTimeoutSeconds: [(settings.commandTimeoutSeconds ?: "4").toString(), "enum"],
        partitionZones  : [normalizedPartitionZones(), "text"],
        holdBypassedOpenZones: [settings.holdBypassedOpenZones == true, "bool"]
    ]

    Map pushed = (state.pushedSettings instanceof Map) ? state.pushedSettings : [:]
    List<String> changed = []
    desired.each { String name, List spec ->
        String signature = "${spec[0]}".hashCode().toString()
        if (pushed[name] == signature) return
        conn.updateSetting(name, [value: spec[0], type: spec[1]])
        pushed[name] = signature
        changed << name
    }
    state.pushedSettings = pushed
    return changed
}

def refreshAll() {
    getChildDevice("uno-connection")?.refreshAll()
}

def triggerPgm(def pgm) {
    getChildDevice("uno-connection")?.triggerPgm(pgm)
}

// Forwards attribute events from the connection device to a PGM device.
def notifyPgmEvents(Integer pgm, List events) {
    def dev = getChildDevice("uno-pgm-${pgm}")
    if (!dev) return
    events?.each { Map e -> dev.sendEvent(e) }
}

// The connection device says whether the panel is reachable; every other device mirrors it.
def notifyHealth(String value) {
    state.health = value
    getChildDevices()?.each { dev ->
        if (dev.deviceNetworkId != "uno-connection") dev.sendEvent(name: "healthStatus", value: value)
    }
    handleOfflineAlert(value)
}

// An outage longer than the chosen time is worth a message, and so is the recovery that follows it.
private void handleOfflineAlert(String value) {
    Integer minutes = safeInt(settings.notifyOfflineMinutes, 5)
    if (value == "offline") {
        if (minutes > 0 && settings.notifyDevices) runIn(minutes * 60, "offlineAlert")
        return
    }

    unschedule("offlineAlert")
    if (state.offlineAlerted == true) {
        state.offlineAlerted = false
        sendNotice("UNO connection restored")
    }
}

def offlineAlert() {
    if (state.health != "offline") return
    state.offlineAlerted = true
    sendNotice("UNO connection lost: the alarm cannot be monitored until it is back".toString())
}

private void sendNotice(String message) {
    def devices = settings.notifyDevices
    if (!devices) return
    try {
        devices.each { it.deviceNotification(message) }
    } catch (e) {
        log.warn "Could not send notification: ${e}"
    }
}

// A device created after the connection came up still needs to know the current state.
private void applyHealth(def dev) {
    try {
        dev?.sendEvent(name: "healthStatus", value: state.health ?: "offline")
    } catch (ignored) {
    }
}

// Sends the alarm, trouble and arm/disarm notifications that are switched on. Test reports never notify.
def notifyEvent(Map event) {
    if (!settings.notifyDevices) return

    String category = event.category
    boolean restore = event.restore == true
    boolean wanted = false
    switch (category) {
        case "alarm":
            wanted = settings.notifyAlarms != false && !restore
            break
        case "trouble":
        case "supervisory":
            wanted = settings.notifyTroubles != false
            break
        case "arm":
        case "disarm":
            wanted = settings.notifyArmDisarm == true
            break
    }
    if (!wanted) return

    // Partition 0 means a panel-wide event, which `safeInt` would turn into partition 1.
    Integer partition = (event.partition == null) ? 1 : safeInt(event.partition.toString(), 1)
    if (!partitionWanted(partition)) return
    // Alarms always get through; everything else waits out quiet hours.
    if (category != "alarm" && inQuietHours()) return

    String where = (configuredPartitions().size() > 1 && partition >= 1) ? "${partitionLabelFor(partition)}: " : ""
    String message = "${where}${event.text}".toString()
    if (category in ["trouble", "supervisory"] && isRepeatNotice(message, restore)) return
    sendNotice(message)
}

private boolean partitionWanted(Integer partition) {
    List<Integer> only = parseRange(settings.notifyPartitions, 1, 8).values
    return !only || partition < 1 || only.contains(partition)
}

// Quiet hours come from two "time" inputs, which arrive as ISO strings such as 2026-11-22T22:00:00.000-0500.
private boolean inQuietHours() {
    Integer start = minutesOfDay(settings.quietStart)
    Integer end = minutesOfDay(settings.quietEnd)
    if (start == null || end == null || start == end) return false

    Integer current = minutesOfDay("T" + new Date(now()).format("HH:mm", location.timeZone))
    return start < end ? (current >= start && current < end) : (current >= start || current < end)
}

private Integer minutesOfDay(def time) {
    def m = (time?.toString() =~ /T(\d{2}):(\d{2})/)
    return m.find() ? (Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2))) : null
}

// The same trouble (a low battery that keeps reporting, say) is only sent once in 6 hours. Its restoral
// is always sent, and it lets the next occurrence through.
private boolean isRepeatNotice(String message, boolean restore) {
    long cutoff = now() - 6L * 3600L * 1000L
    Map seen = ((state.recentNotices instanceof Map) ? state.recentNotices : [:]).findAll { String k, def v -> (v as Long) > cutoff }

    boolean repeat = false
    if (restore) {
        seen.remove(message.replace(" restored", ""))
    } else {
        repeat = seen.containsKey(message)
        if (!repeat) seen[message] = now()
    }
    state.recentNotices = seen
    return repeat
}

// ---- Status device, panel summary and stale sensors ----

private void ensureStatusDevice() {
    if (settings.createStatusDevice != true) return

    def existing = getChildDevice("uno-status")
    if (existing) {
        relabelDefault(existing, "UNO Status")
        return
    }
    try {
        def created = addChildDevice("uno", "UNO Status", "uno-status", [name: "UNO Status", label: "UNO Status", isComponent: true])
        applyHealth(created)
        applyPanelSummary(created)
        noteDeviceCreated()
        logInfo("Created UNO Status device")
    } catch (e) {
        log.warn "Could not create the UNO Status device (is the 'UNO Status' driver installed?): ${e}"
    }
}

// The connection device reports which zones are open or bypassed and the troubles; the status device shows all of it.
// (It gives its partition the lists itself, since the partitions are its own children.)
def notifyPanelSummary(Map summary) {
    getChildDevice("uno-status")?.with { dev -> summary.each { String n, def v -> dev.sendEvent(name: n, value: v) } }
}

// Brings a newly created status device up to date from what the connection device already knows.
private void applyPanelSummary(def dev) {
    def conn = getChildDevice("uno-connection")
    if (!dev || !conn) return

    ["openZones", "openZoneCount", "openZonesTimed", "openZonesDetail", "bypassedZones", "troubleSummary"].each { String n ->
        def v = conn.currentValue(n)
        if (v != null) dev.sendEvent(name: n, value: v)
    }
}

private void scheduleStaleCheck() {
    unschedule("staleSensorCheck")
    if (safeInt(settings.staleSensorDays, 0) <= 0) return
    if (!state.staleSince) state.staleSince = now()
    schedule("0 0 9 * * ?", "staleSensorCheck")
}

// Zones with no recorded activity in the chosen number of days. The connection device owns the zones, so it does the
// looking and answers through staleSensorResult.
def staleSensorCheck() {
    Integer days = safeInt(settings.staleSensorDays, 0)
    if (days <= 0) return

    getChildDevice("uno-connection")?.checkStaleSensors(days, (state.staleSince ?: now()) as Long)
}

def staleSensorResult(Integer days, List names) {
    String summary = names ? names.join(", ") : "none"
    getChildDevice("uno-status")?.sendEvent(name: "staleSensors", value: summary)
    if (names && state.staleSummary != summary && settings.notifyTroubles != false) {
        sendNotice("No activity for ${days} days: ${summary}".toString())
    }
    state.staleSummary = summary
}

def armStay(def partition = 1, String code = null) {
    getChildDevice("uno-connection")?.armStay(partition, code)
}

def armAway(def partition = 1, String code = null) {
    getChildDevice("uno-connection")?.armAway(partition, code)
}

def armStayBypass(def partition = 1, String code = null) {
    getChildDevice("uno-connection")?.armStayBypass(partition, code)
}

def armAwayBypass(def partition = 1, String code = null) {
    getChildDevice("uno-connection")?.armAwayBypass(partition, code)
}

def chimeOn(def partition = 1) {
    getChildDevice("uno-connection")?.chimeOn(partition)
}

def chimeOff(def partition = 1) {
    getChildDevice("uno-connection")?.chimeOff(partition)
}

def disarm(String code = null, def partition = 1) {
    getChildDevice("uno-connection")?.disarm(code, partition)
}

def bypassZone(def zone) {
    getChildDevice("uno-connection")?.bypassZone(zone)
}

def unbypassZone(def zone) {
    getChildDevice("uno-connection")?.unbypassZone(zone)
}

def bypassOpenZones(def partition = 1) {
    getChildDevice("uno-connection")?.bypassOpenZones(partition)
}

def unbypassAllZones(def partition = 1) {
    getChildDevice("uno-connection")?.unbypassAllZones(partition)
}

def sendKeys(String keys, def partition = 1) {
    getChildDevice("uno-connection")?.sendKeys(keys, partition)
}

def panic(String type, def partition = 1) {
    getChildDevice("uno-connection")?.panic(type, partition)
}

// Lookups the connection driver uses to put names into event descriptions.
def userName(Integer user) {
    Map names = (state.userNamesMap instanceof Map) ? state.userNamesMap : [:]
    return names["${user}"]
}

// The "Load zone names from the UNO" button: fetches the names now, so they can be seen and changed before pressing Done.
def appButtonHandler(String button) {
    if (button != "loadZoneNames") return
    try {
        loadRestMetadata()
    } catch (e) {
        noteRestFailure(e)
    }
}

private String zoneNamesStatus() {
    Map labels = (state.restZoneLabels instanceof Map) ? state.restZoneLabels : [:]
    String found = labels ? "${labels.size()} zone name(s) loaded from the UNO." : "No zone names loaded from the UNO yet."
    return "${found} REST: ${state.restStatus ?: 'not checked yet'}"
}

private void loadRestMetadata() {
    if (!settings.ip || !settings.password) {
        state.restStatus = "enter the IP address and password first"
        return
    }
    Map meta = restGetJson("D")
    if (!meta) {
        state.restStatus = "no answer from the UNO"
        return
    }

    List zoneLabels = meta["zone labels"] instanceof List ? meta["zone labels"] : []
    Map<String, String> mapped = [:]

    zoneLabels.eachWithIndex { def label, int idx ->
        String cleaned = (label ?: "").toString().trim()
        if (cleaned) {
            mapped["${idx + 1}"] = cleaned
        }
    }

    state.restZoneLabels = mapped

    List partitionLabels = meta["partition labels"] instanceof List ? meta["partition labels"] : []
    Map<String, String> partitionMapped = [:]
    partitionLabels.eachWithIndex { def label, int idx ->
        String cleaned = (label ?: "").toString().trim()
        if (cleaned && idx < 8) {
            partitionMapped["${idx + 1}"] = cleaned
        }
    }
    state.restPartitionLabels = partitionMapped

    String sys = (meta["system name"] ?: "").toString().trim()
    if (sys) {
        state.restSystemName = sys
    }

    state.restStatus = "ok"
    logInfo("Loaded REST metadata: ${mapped.size()} zone labels, ${partitionMapped.size()} partition labels")
}

// A 401 means the REST credentials are wrong, which is worth calling out separately from "unreachable".
private void noteRestFailure(Exception e) {
    Integer status = null
    try {
        status = (e.hasProperty("statusCode") ? e.statusCode : e.response?.status) as Integer
    } catch (ignored) {
    }

    if (status == 401) {
        state.restStatus = "credentials rejected (HTTP 401)"
        log.error "UNO rejected the REST credentials (HTTP 401). Check the REST username and the local password."
    } else {
        state.restStatus = "unreachable"
        log.warn "REST metadata load failed: ${e}"
    }
}

private Map restGetJson(String path) {
    if (!settings.ip || !settings.password) return null

    Map result = null
    String username = (settings.restUsername ?: "user").toString()
    String auth = "${username}:${settings.password}".bytes.encodeBase64().toString()

    httpGet(
        [
            uri: "http://${settings.ip}/${path}",
            headers: [
                "Authorization": "Basic ${auth}"
            ],
            contentType: "application/json",
            timeout: 5
        ]
    ) { resp ->
        if (resp?.status == 200) {
            if (resp.data instanceof Map) {
                result = resp.data as Map
            } else if (resp.data) {
                result = resp.data
            }
        }
    }

    return result
}

private Map parseZoneHints(String raw) {
    Map result = [:]
    if (!raw) return result

    raw.split("\\n").each { String line ->
        String cleaned = line?.trim()
        if (!cleaned) return
        if (cleaned.startsWith("#")) return

        List<String> parts = cleaned.split(":") as List<String>
        if (parts.size() < 2) return

        String zone = parts[0]?.trim()
        if (!zone?.isInteger()) return

        String label = parts.size() >= 2 ? parts[1]?.trim() : "Zone ${zone}"
        String type  = parts.size() >= 3 ? parts[2]?.trim() : null

        result[zone] = [
            label: label,
            type : type
        ]
    }

    return result
}

// "number:Name" lines (partition names, user names). Blank lines, comments and bad lines are ignored.
private Map parseNumberedNames(String raw) {
    Map result = [:]
    if (!raw) return result

    raw.split("\\n").each { String line ->
        String cleaned = line?.trim()
        if (!cleaned || cleaned.startsWith("#")) return

        int idx = cleaned.indexOf(":")
        if (idx < 1) return

        String number = cleaned.substring(0, idx).trim()
        String name = cleaned.substring(idx + 1).trim()
        if (number.isInteger() && name) {
            result[number] = name
        }
    }

    return result
}

// Parses "1-3,7,10-12" into sorted unique numbers within min..max; anything else is reported in invalid.
private Map parseRange(def spec, Integer min, Integer max) {
    Set<Integer> values = new TreeSet<Integer>()
    List<String> invalid = []

    String text = (spec ?: "").toString().replaceAll("\\s", "")
    if (!text) return [values: [], invalid: invalid]

    text.split(",").each { String token ->
        if (!token) return
        def m = (token =~ /^(\d+)(?:-(\d+))?$/)
        if (!m.matches()) {
            invalid << token
            return
        }
        // Anything this long is out of range anyway, and a longer number would overflow an int.
        if (m.group(1).length() > 4 || (m.group(2) != null && m.group(2).length() > 4)) {
            invalid << token
            return
        }
        int lo = Integer.parseInt(m.group(1))
        int hi = m.group(2) != null ? Integer.parseInt(m.group(2)) : lo
        if (lo > hi || lo < min || hi > max) {
            invalid << token
            return
        }
        (lo..hi).each { values << it }
    }

    return [values: values as List<Integer>, invalid: invalid]
}

// Zones to create and track: the zone set if configured, otherwise 1..zone count.
private List<Integer> configuredZones() {
    Map parsed = parseRange(settings.zoneSet, 1, 128)
    if (parsed.values) return parsed.values

    Integer count = Math.min(Math.max(safeInt(settings.zoneCount, 27), 1), 128)
    return (1..count).collect { it as Integer }
}

private List<Integer> configuredPartitions() {
    Map parsed = parseRange(settings.partitionSet, 1, 8)
    return parsed.values ?: [1]
}

// Reads "partition:zones" lines such as 2:9-16 into [assignments: [partition: [zones]], problems: [text]].
// Anything that can't be used is left out and reported, so a typo never silently moves a zone.
private Map parsePartitionZones(def spec) {
    Map<Integer, List<Integer>> assignments = new TreeMap<Integer, List<Integer>>()
    List<String> problems = []
    Map<Integer, Integer> owner = [:]
    List<Integer> zones = configuredZones()
    List<Integer> partitions = configuredPartitions()

    (spec ?: "").toString().split("[\\r\\n;]+").each { String raw ->
        String line = raw.trim()
        if (!line) return

        int colon = line.indexOf(":")
        Integer partition = colon > 0 ? safeInt(line.substring(0, colon).trim(), 0) : 0
        if (partition < 1) {
            problems << "'${line}' is not partition:zones".toString()
            return
        }
        if (!partitions.contains(partition)) {
            problems << "partition ${partition} is not one of the partitions you create".toString()
            return
        }

        Map parsed = parseRange(line.substring(colon + 1), 1, 128)
        if (parsed.invalid) problems << "not valid zone numbers: ${parsed.invalid.join(', ')}".toString()
        List<Integer> mine = []
        parsed.values.each { Integer z ->
            if (!zones.contains(z)) {
                problems << "zone ${z} is not one of your zones".toString()
            } else if (owner.containsKey(z)) {
                problems << "zone ${z} is already in partition ${owner[z]}".toString()
            } else {
                owner[z] = partition
                mine << z
            }
        }
        if (mine.isEmpty()) {
            // A line that ends up with no zones must not create an assignment, or that partition would show nothing forever.
            if (!parsed.invalid) problems << "partition ${partition} has no zones".toString()
            return
        }
        assignments[partition] = ((assignments[partition] ?: []) + mine).sort()
    }

    return [assignments: assignments, problems: problems, unassigned: zones.findAll { !owner.containsKey(it) }]
}

// The validated assignments in the compact form the connection device reads, e.g. "1:1,2,3;2:9,10".
private String normalizedPartitionZones() {
    Map<Integer, List<Integer>> assignments = parsePartitionZones(settings.partitionZones).assignments
    return assignments.collect { Integer p, List<Integer> zs -> "${p}:${zs.join(',')}".toString() }.join(";")
}

private String partitionZonesPreview() {
    Map parsed = parsePartitionZones(settings.partitionZones)
    if (!parsed.assignments && !parsed.problems) return "No zones assigned: with one partition its device shows every open zone, with several only the status device does."

    List<String> lines = parsed.assignments.collect { Integer p, List<Integer> zs -> "Partition ${p}: ${zs.size()} zone${zs.size() == 1 ? '' : 's'} (${zs.join(', ')})".toString() }
    if (parsed.assignments && parsed.unassigned) lines << "Not assigned (shown only in the panel-wide lists): ${parsed.unassigned.join(', ')}".toString()
    parsed.problems.each { String p -> lines << "Ignored: ${p}".toString() }
    return lines.join("\n")
}

private String rangePreview(def spec, Integer min, Integer max, String noun, String fallback) {
    Map parsed = parseRange(spec, min, max)
    List<String> lines = []
    if (parsed.values) {
        lines << "Will use ${noun}s: ${parsed.values.join(', ')} (${parsed.values.size()})"
    } else {
        lines << "Will use ${fallback}"
    }
    if (parsed.invalid) {
        lines << "Ignored (not valid ${noun} numbers ${min}-${max}): ${parsed.invalid.join(', ')}"
    }
    return lines.join("\n")
}

private String statusSummary() {
    def conn = getChildDevice("uno-connection")
    if (!conn) return "The UNO Connection device is created when you press Done."

    List<String> lines = []
    lines << "Connection: ${conn.currentValue('connectionState') ?: 'unknown'}"

    String model = conn.currentValue("panelModel")
    String fw = conn.currentValue("firmwareVersion")
    String mac = conn.currentValue("panelMac")
    if (model || fw || mac) {
        lines << "Panel: ${[model, fw ? 'firmware ' + fw : null, mac ? 'MAC ' + mac : null].findAll { it }.join(', ')}"
    }

    lines << "REST: ${state.restStatus ?: 'not checked yet'}"
    String firmwareWarning = conn.currentValue("firmwareWarning")
    if (firmwareWarning && firmwareWarning != "none") lines << "WARNING: ${firmwareWarning}"
    if (settings.requireCodeToArm == true) {
        lines << (settings.masterCode ? "Arming requires the master code" : "WARNING: arming requires a code but no master code is set, so arming is blocked")
    }
    if (settings.enablePanic == true) lines << "Panic commands are ENABLED"
    // Assignments that no longer match the partitions or zones above are ignored, never an error; say so here.
    Map zp = parsePartitionZones(settings.partitionZones)
    if (zp.problems) lines << "WARNING: ${zp.problems.size()} part${zp.problems.size() == 1 ? '' : 's'} of Zones per partition ${zp.problems.size() == 1 ? 'is' : 'are'} being ignored (see Partitions)"
    return lines.join("\n")
}

private Integer safeInt(def val, Integer fallback) {
    try {
        return (val ?: fallback) as Integer
    } catch (ignored) {
        return fallback
    }
}

private void logInfo(String msg) {
    log.info msg
}
