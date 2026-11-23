definition(
    name: "EyezOn UNO Integration",
    namespace: "uno",
    author: "Eric Hess",
    description: "Hubitat integration for the EyezOn UNO alarm system",
    category: "Safety & Security",
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
            input "password", "text", title: "UNO Local/TPI Password", required: true
            input "restUsername", "text", title: "UNO REST Username", defaultValue: "user", required: false
            input "masterCode", "text", title: "Master Code (optional, for disarm)", required: false
        }

        section("Partitions") {
            input "partitionSet", "text",
                title: "Partitions to create (e.g. 1 or 1-2 or 1,3)",
                description: "The UNO supports partitions 1-8. Leave blank for partition 1 only.",
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
        }

        section("Users (optional)") {
            input "userNames", "textarea",
                title: "User names",
                description: "One per line as userNumber:Name, e.g. 2:Alice. Used to say who armed or disarmed. Unnamed users show as 'user N'.",
                required: false
        }

        section("REST Metadata / Startup Sync") {
            input "useRestMetadata", "bool",
                title: "Enable REST metadata and startup sync",
                defaultValue: true
            paragraph "Uses UNO REST endpoint D for labels and endpoint A for startup zone-state sync. TPI remains the real-time event engine."
        }

        section("Zone Discovery") {
            input "zoneCount", "number", title: "Configured Zone Count", defaultValue: 27, required: true
            input "zoneSet", "text",
                title: "Zone set (optional, e.g. 1-8,12,20-27)",
                description: "Only these zones are created and tracked. Overrides Zone Count when filled in.",
                required: false
            paragraph rangePreview(settings.zoneSet, 1, 128, "zone", "zones 1-${safeInt(settings.zoneCount, 27)} (from Zone Count)")
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

        section("Logging / Health") {
            input "debugLogging", "bool", title: "Enable debug logging", defaultValue: false
            input "traceLogging", "bool", title: "Enable trace logging", defaultValue: false
            input "heartbeatMinutes", "enum",
                title: "Heartbeat interval",
                options: ["1":"Every 1 minute", "5":"Every 5 minutes", "10":"Every 10 minutes"],
                defaultValue: "1",
                required: true
            input "watchdogMinutes", "enum",
                title: "Reconnect if no frames seen for",
                options: ["3":"3 minutes", "5":"5 minutes", "10":"10 minutes"],
                defaultValue: "3",
                required: true
        }
    }
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
    state.restZoneLabels = state.restZoneLabels ?: [:]
    state.restPartitionLabels = state.restPartitionLabels ?: [:]
    state.restSystemName = state.restSystemName ?: null

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

    ensurePartitionDevices()

    if (settings.autoCreateConfiguredZones != false) {
        configuredZones().each { Integer zoneNum ->
            upsertDiscoveredZone(zoneNum, null, null)
        }
    }

    reconcileExistingZones()
    applyConnectionSettings()
    runIn(1, "initializeConnection")
}

def initializeConnection() {
    def conn = getChildDevice("uno-connection")
    if (conn) {
        conn.initialize()
    }
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
    }
}

private void ensurePartitionDevices() {
    configuredPartitions().each { Integer partition ->
        ensurePartitionDevice(partition)
    }
}

private void ensurePartitionDevice(Integer partition) {
    String dni = "uno-partition-${partition}"
    String label = partitionLabelFor(partition)

    def dev = getChildDevice(dni)

    if (!dev) {
        addChildDevice(
            "uno",
            "UNO Partition",
            dni,
            [
                name       : label,
                label      : label,
                isComponent: true
            ]
        )
        logInfo("Created partition device: ${label}")
    } else {
        try {
            if (dev.label != label) {
                dev.setLabel(label)
                logInfo("Updated partition ${partition} label to ${label}")
            }
        } catch (e) {
            log.warn "Unable to update partition label: ${e}"
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

private void applyConnectionSettings() {
    def conn = getChildDevice("uno-connection")
    if (!conn) return

    conn.updateSetting("ip", [value: settings.ip, type: "text"])
    conn.updateSetting("port", [value: "${settings.port ?: 4025}", type: "number"])
    conn.updateSetting("password", [value: settings.password, type: "text"])
    conn.updateSetting("restUsername", [value: settings.restUsername ?: "user", type: "text"])
    conn.updateSetting("masterCode", [value: settings.masterCode ?: "", type: "text"])
    conn.updateSetting("zoneCount", [value: "${safeInt(settings.zoneCount, 27)}", type: "number"])
    conn.updateSetting("zoneSet", [value: settings.zoneSet ?: "", type: "text"])
    conn.updateSetting("enablePanic", [value: settings.enablePanic == true, type: "bool"])
    conn.updateSetting("requireCodeToArm", [value: settings.requireCodeToArm == true, type: "bool"])
    conn.updateSetting("useRestMetadata", [value: settings.useRestMetadata == true, type: "bool"])
    conn.updateSetting("debugLogging", [value: settings.debugLogging == true, type: "bool"])
    conn.updateSetting("traceLogging", [value: settings.traceLogging == true, type: "bool"])
    conn.updateSetting("heartbeatMinutes", [value: (settings.heartbeatMinutes ?: "1").toString(), type: "enum"])
    conn.updateSetting("watchdogMinutes", [value: (settings.watchdogMinutes ?: "3").toString(), type: "enum"])
}

def refreshAll() {
    getChildDevice("uno-connection")?.refreshAll()
}

def armStay(def partition = 1, String code = null) {
    getChildDevice("uno-connection")?.armStay(partition, code)
}

def armAway(def partition = 1, String code = null) {
    getChildDevice("uno-connection")?.armAway(partition, code)
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

// Partitions without a device (not in the partition set) are ignored.
def notifyPartitionState(Integer partition, String status) {
    getChildDevice("uno-partition-${partition}")?.setAlarmState(status)
}

// Forwards attribute events (trouble flags, chime) from the connection device to a partition device.
def notifyPartitionEvents(Integer partition, List events) {
    def dev = getChildDevice("uno-partition-${partition}")
    if (!dev) return
    events?.each { Map e -> dev.sendEvent(e) }
}

// Lookups the connection driver uses to put names into event descriptions.
def userName(Integer user) {
    Map names = (state.userNamesMap instanceof Map) ? state.userNamesMap : [:]
    return names["${user}"]
}

def zoneName(Integer zone) {
    String label = getChildDevice("uno-zone-${zone}")?.label
    // A zone that still has its default label has no real name to show.
    return (label && label != "Zone ${zone}") ? label : null
}

def upsertDiscoveredZone(Integer zoneNum, String suggestedType = null, String suggestedName = null) {
    if (zoneNum == null || zoneNum <= 0) return

    Map hints = (state.zoneHintsMap instanceof Map) ? state.zoneHintsMap : parseZoneHints(settings.zoneHints)
    Map hint = hints["${zoneNum}"] ?: [:]

    String hintedLabel = hint.label?.trim() ?: null
    String hintedType  = hint.type?.trim() ?: null
    String restLabel   = state.restZoneLabels ? state.restZoneLabels["${zoneNum}"] : null

    String inferredName = suggestedName?.trim()
    String inferredType = suggestedType?.trim()
    String nameForInference = hintedLabel ?: restLabel ?: inferredName

    String dni = "uno-zone-${zoneNum}"
    def existing = getChildDevice(dni)

    String finalLabel = hintedLabel ?: restLabel ?: inferredName ?: existing?.label ?: "Zone ${zoneNum}"

    if (!existing) {
        String desiredDriver = driverNameForType(canonicalType(
            hintedType ?:
            inferredType ?:
            inferTypeFromName(nameForInference) ?:
            settings.defaultUnknownZoneType ?:
            "contact"
        ))

        addChildDevice(
            "uno",
            desiredDriver,
            dni,
            [
                name       : "UNO Zone ${zoneNum}",
                label      : finalLabel,
                isComponent: true
            ]
        )
        logDebug("Created zone ${zoneNum}: ${finalLabel} (${desiredDriver})")
        return
    }

    String existingTypeName = deviceTypeName(existing)
    String desiredDriver = desiredDriverForExisting(existingTypeName, hintedType, inferredType, nameForInference)

    if (existingTypeName && existingTypeName != desiredDriver) {
        try {
            deleteChildDevice(dni)
        } catch (e) {
            log.warn "Could not recreate zone ${zoneNum}; device is in use: ${e}"
            return
        }

        addChildDevice(
            "uno",
            desiredDriver,
            dni,
            [
                name       : "UNO Zone ${zoneNum}",
                label      : finalLabel,
                isComponent: true
            ]
        )
        logInfo("Recreated zone ${zoneNum}: ${finalLabel} (${desiredDriver})")
        return
    }

    try {
        if (existing.label != finalLabel) {
            existing.setLabel(finalLabel)
            logDebug("Updated zone ${zoneNum} label to ${finalLabel}")
        }
    } catch (e) {
        log.warn "Unable to relabel zone ${zoneNum}: ${e}"
    }
}

private void reconcileExistingZones() {
    // Reuse the upsert rules so reconcile and discovery can never disagree about a zone's type.
    List<Integer> zoneNums = []
    getChildDevices()?.each { dev ->
        String dni = dev.deviceNetworkId ?: ""
        if (!dni.startsWith("uno-zone-")) return

        Integer zoneNum = safeParseZoneFromDni(dni)
        if (zoneNum) zoneNums << zoneNum
    }

    zoneNums.each { Integer zoneNum ->
        upsertDiscoveredZone(zoneNum, null, null)
    }
}

private String deviceTypeName(dev) {
    try {
        return dev.getTypeName()
    } catch (ignored) {
        try {
            return dev.typeName
        } catch (ignored2) {
            return null
        }
    }
}

// An explicit hint always wins. Otherwise a device still on the default driver may be upgraded from a
// CID suggestion or its label; a device already on a specific driver is left alone to avoid flip-flopping.
private String desiredDriverForExisting(String currentDriver, String hintedType, String suggestedType, String nameForInference) {
    if (hintedType) return driverNameForType(canonicalType(hintedType))

    String defaultDriver = driverNameForType(canonicalType(settings.defaultUnknownZoneType ?: "contact"))
    if (currentDriver && currentDriver != defaultDriver) return currentDriver

    String learned = suggestedType ?: inferTypeFromName(nameForInference)
    return learned ? driverNameForType(canonicalType(learned)) : currentDriver
}

private void loadRestMetadata() {
    Map meta = restGetJson("D")
    if (!meta) return

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

private Integer safeParseZoneFromDni(String dni) {
    try {
        return dni.replace("uno-zone-", "") as Integer
    } catch (ignored) {
        return null
    }
}

private String driverNameForType(String zoneType) {
    switch ((zoneType ?: "contact").toLowerCase()) {
        case "motion":
            return "UNO Zone Motion"
        case "smoke":
            return "UNO Zone Smoke"
        case "co":
            return "UNO Zone CO"
        case "water":
            return "UNO Zone Water"
        default:
            return "UNO Zone Contact"
    }
}

private String canonicalType(String raw) {
    String t = (raw ?: "contact").toLowerCase().trim()

    switch (t) {
        case "pir":
        case "interior":
            return "motion"
        case "fire":
        case "heat":
            return "smoke"
        case "carbonmonoxide":
        case "carbon_monoxide":
            return "co"
        case "leak":
        case "flood":
            return "water"
        default:
            return t
    }
}

private String inferTypeFromName(String name) {
    String n = (name ?: "").toLowerCase()

    // Match at word starts so "Courtyard" isn't CO and "Wheat" isn't heat.
    if (n =~ /\b(pir|motion)/) return "motion"
    if (n =~ /\b(smoke|heat)/) return "smoke"
    if (n.contains("carbon monoxide") || n =~ /\bco\b/) return "co"
    if (n =~ /\b(water|leak|flood|hvac)/) return "water"
    if (n =~ /\b(door|window|gate|patio)/) return "contact"

    return null
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
    if (settings.requireCodeToArm == true) {
        lines << (settings.masterCode ? "Arming requires the master code" : "WARNING: arming requires a code but no master code is set, so arming is blocked")
    }
    if (settings.enablePanic == true) lines << "Panic commands are ENABLED"
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

private void logDebug(String msg) {
    if (settings.debugLogging) {
        log.debug msg
    }
}
