import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap

// Codes and key presses waiting to be sent. In memory only (shared by every UNO Connection device on the hub),
// so they are never written to the hub's database and disappear if the hub restarts.
@Field static final ConcurrentHashMap CODE_VAULT = new ConcurrentHashMap()

// Hub state is stored in the database, so data that changes constantly or can be rebuilt lives in memory instead.
@Field static final ConcurrentHashMap ZONE_CACHE = new ConcurrentHashMap()   // zone settings -> zone list
@Field static final ConcurrentHashMap CID_SEEN = new ConcurrentHashMap()     // "<device>:<zone>" -> time of the last fast-path event
@Field static final ConcurrentHashMap HISTORY = new ConcurrentHashMap()      // device id -> recent events, newest first
@Field static final ConcurrentHashMap PARTITION_SEEN = new ConcurrentHashMap() // "<device>:<partition>:<attribute>" -> last value sent
@Field static final ConcurrentHashMap TEMP_SEEN = new ConcurrentHashMap()      // "<device>:z<zone>" / "<device>:p<n>-inside" -> last temperature byte
@Field static final ConcurrentHashMap NAME_CACHE = new ConcurrentHashMap()     // "<device>:<zone>" -> [name, at], for the open zone lists
@Field static final ConcurrentHashMap FRAME_AT = new ConcurrentHashMap()       // device id -> time of the last data from the panel
@Field static final ConcurrentHashMap CREATE_FAILED = new ConcurrentHashMap()  // "<device>:<dni>" -> when making that child device last failed
@Field static final ConcurrentHashMap ACK_SEEN = new ConcurrentHashMap()       // device id -> [cc, ee, code, at] of the last acknowledgement
@Field static final ConcurrentHashMap OPEN_SINCE = new ConcurrentHashMap()    // "<device>:<zone>" -> when it opened (or was first seen open)
@Field static final ConcurrentHashMap LEFT_OPEN = new ConcurrentHashMap()      // "<device>:<zone>" -> [since, leftAt], for a zone that just closed

metadata {
    definition(name: "UNO Connection", namespace: "uno", author: "Eric Hess",
               importUrl: "https://raw.githubusercontent.com/pierowman/hubitat-eyezon-uno/main/drivers/UNOConnectionDriver.groovy") {
        capability "Initialize"
        capability "HealthCheck"
        capability "Refresh"
        capability "Actuator"
        capability "Sensor"

        attribute "connectionState", "string"
        attribute "healthStatus", "enum", ["online", "offline"]
        attribute "alarmSystemStatus", "string"
        attribute "troubleState", "string"
        attribute "troubleDetail", "string"
        attribute "acPower", "string"
        attribute "serviceRequired", "string"
        attribute "acFailure", "string"
        attribute "lowBattery", "string"
        attribute "serverOffline", "string"
        attribute "zoneTrouble", "string"
        attribute "batteryOvercurrent", "string"
        attribute "sirenFault", "string"
        attribute "supervisoryFault", "string"
        attribute "chime", "string"
        attribute "lastChime", "string"
        attribute "panelModel", "string"
        attribute "firmwareVersion", "string"
        attribute "firmwareWarning", "string"
        attribute "panelTime", "string"
        attribute "panelMac", "string"
        attribute "lastAck", "string"
        attribute "lastKeypadSound", "string"
        attribute "zoneTimers", "string"
        attribute "openZones", "string"
        attribute "openZoneCount", "number"
        attribute "openZonesTimed", "string"
        attribute "openZonesDetail", "string"
        attribute "bypassedZones", "string"
        attribute "troubleSummary", "string"
        attribute "lastRawMessage", "string"
        attribute "zoneBitmapLength", "number"
        attribute "lastHeartbeat", "string"
        attribute "lastCidEvent", "string"
        attribute "lastEvent", "string"
        attribute "lastEventType", "string"
        attribute "lastEventRaw", "string"
        attribute "lastEventCode", "string"
        attribute "lastEventQualifier", "string"
        attribute "lastEventPartition", "number"
        attribute "lastEventZone", "number"
        attribute "lastEventUser", "string"
        attribute "eventHistory", "string"
        attribute "restStartupSync", "string"

        command "armStay"
        command "armAway"
        command "armStayBypass"
        command "armAwayBypass"
        command "dumpZoneTimers"
        command "chimeOn"
        command "chimeOff"
        command "bypassZone", [[name: "Zone", type: "NUMBER"]]
        command "unbypassZone", [[name: "Zone", type: "NUMBER"]]
        command "bypassOpenZones", [[name: "Partition", type: "NUMBER"]]
        command "unbypassAllZones", [[name: "Partition", type: "NUMBER"]]
        command "sendKeys", [[name: "Keys (0-9 * #)", type: "STRING"], [name: "Partition", type: "NUMBER"]]
        command "panic", [[name: "Type", type: "ENUM", constraints: ["fire", "medical", "police"]], [name: "Partition", type: "NUMBER"]]
        command "disarm", [[name: "Master Code", type: "STRING"]]
        command "reconnect"
        command "refreshAll"
        command "login"
        command "clearCommandQueue"
        command "clearEventHistory"
        command "triggerPgm", [[name: "PGM number (1-14)", type: "NUMBER"]]
        command "debugDump"
        command "poll"
        command "restStartupSync"
        command "syncDevices"
    }

    preferences {
        input name: "ip", type: "text", title: "UNO IP Address", required: true
        input name: "port", type: "number", title: "UNO Port", defaultValue: 4025, required: true
        input name: "password", type: "password", title: "UNO TPI Password", required: true
        input name: "restUsername", type: "text", title: "UNO REST Username", defaultValue: "user", required: false
        input name: "masterCode", type: "password", title: "UNO Master Code", required: false
        input name: "zoneCount", type: "number", title: "Configured Zone Count", defaultValue: 27, required: true
        input name: "zoneSet", type: "text", title: "Zone set (optional, e.g. 1-8,12,20-27; overrides zone count)", required: false
        input name: "enablePanic", type: "bool", title: "Allow panic commands (contacts emergency services if the UNO is monitored)", defaultValue: false
        input name: "requireCodeToArm", type: "bool", title: "Require the master code to arm (checked locally; the UNO's arm commands take no code)", defaultValue: false
        input name: "diagnosticEvents", type: "bool", title: "Publish diagnostic events (last heartbeat, last raw message, panel time). These change constantly and add a lot of database writes", defaultValue: false
        input name: "recordTestEvents", type: "bool", title: "Record Contact ID test reports in the event history and events", defaultValue: false
        input name: "useRestMetadata", type: "bool", title: "Enable REST metadata/startup sync", defaultValue: true
        input name: "debugLogging", type: "bool", title: "Enable debug logging", defaultValue: false
        input name: "traceLogging", type: "bool", title: "Enable trace logging", defaultValue: false
        input name: "keepaliveSeconds", type: "enum", title: "Keepalive: poll the UNO every", options: ["30":"30 seconds", "60":"1 minute", "300":"5 minutes", "600":"10 minutes"], defaultValue: "60", required: true
        input name: "watchdogMinutes", type: "enum", title: "Reconnect if no frames seen for", options: ["3":"3 minutes", "5":"5 minutes", "10":"10 minutes"], defaultValue: "3", required: true
        input name: "commandTimeoutSeconds", type: "enum", title: "Wait for a command's reply for", options: ["2":"2 seconds", "4":"4 seconds", "10":"10 seconds", "20":"20 seconds"], defaultValue: "4", required: true
        input name: "partitionZones", type: "text", title: "Zones per partition (set by the app, e.g. 1:1-8;2:9-16)", required: false
        input name: "holdBypassedOpenZones", type: "bool", title: "Keep a zone that was open when it was bypassed in the open list until the bypass is removed (for panels that report bypassed zones as closed)", defaultValue: false
    }
}

def installed() {
    logInfo("Installed")
    initialize()
}

def updated() {
    logInfo("Updated")
    unschedule()
    initialize()
}

def initialize() {
    // Anything still queued belongs to the previous session and must not run in this one.
    failOutstanding("reinitializing")

    state.rxBuffer = ""
    state.commandQueue = []
    state.commandInFlight = null
    state.reconnectDelay = 2
    state.reconnectDueAt = 0L
    state.lastZoneBitmap = null
    state.lastPartitionBitmap = null
    state.partitionStatus = [:]
    state.armRequests = [:]
    state.troubleBytes = [:]
    state.lastBypassBitmap = null
    state.lastTroubleBitmap = null
    state.loggedIn = false
    state.loginSent = false
    state.lastCidEvent = ""
    // Earlier versions kept these in state; they are in memory now (they change constantly or can be rebuilt).
    ["lastCidByZone", "eventHistory", "zoneTemps", "partitionTemps", "lastFrameTs"].each { String old -> state.remove(old) }
    FRAME_AT.remove("${device.id}".toString())
    clearSeen()
    state.restStartupDone = false
    state.authFailed = false

    Map zoneSelection = parseRange(settings.zoneSet, 1, 128)
    if (zoneSelection.invalid) logWarn("Ignoring invalid zone set entries: ${zoneSelection.invalid.join(', ')}")
    if (settings.zoneSet && !zoneSelection.values) logWarn("Zone set has no valid zones; using zone count ${configuredZoneCount()}")

    sendEvent(name: "connectionState", value: "disconnected")
    if (!device.currentValue("alarmSystemStatus")) sendEvent(name: "alarmSystemStatus", value: "unknown")
    if (!device.currentValue("troubleState")) sendEvent(name: "troubleState", value: "unknown")
    if (device.currentValue("eventHistory") == null) sendEvent(name: "eventHistory", value: "[]")
    if (device.currentValue("healthStatus") == null) sendEvent(name: "healthStatus", value: "offline")
    // How long the connection may stay silent before the device is considered offline, in seconds.
    sendEvent(name: "checkInterval", value: keepaliveSeconds() * 2)

    scheduleHealthChecks()
    reconnect()
}

private void scheduleHealthChecks() {
    state.heartbeatTick = 0
    runEvery1Minute("minuteCheck")
    logDebug("Scheduled keepalive=${keepaliveSeconds()}s watchdog=${settings.watchdogMinutes ?: '3'}m command timeout=${commandTimeoutSeconds()}s")
}

// How often to poll the UNO to prove the link is alive. An older install only has heartbeatMinutes.
private Integer keepaliveSeconds() {
    Integer secs = settings.keepaliveSeconds != null ? safeInt(settings.keepaliveSeconds, 60) : safeInt(settings.heartbeatMinutes, 1) * 60
    return Math.max(secs, 30)
}

// One schedule drives both checks. The configured "no frames" threshold is applied inside watchdogCheck.
def minuteCheck() {
    int secs = keepaliveSeconds()
    if (secs <= 30) {
        // Twice a minute: now, and again half a minute later.
        heartbeatCheck()
        runIn(30, "heartbeatCheck")
    } else if (secs <= 60) {
        heartbeatCheck()
    } else {
        int every = secs.intdiv(60)
        int tick = safeInt(state.heartbeatTick, 0) + 1
        state.heartbeatTick = tick >= every ? 0 : tick
        if (tick >= every) heartbeatCheck()
    }
    watchdogCheck()
}

// Forgets what was last reported for this device, so everything is sent again.
private void clearSeen() {
    ACK_SEEN.remove("${device.id}".toString())
    String prefix = "${device.id}:".toString()
    [CID_SEEN, PARTITION_SEEN, TEMP_SEEN, NAME_CACHE].each { ConcurrentHashMap m ->
        m.keySet().findAll { String k -> k.startsWith(prefix) }.each { String k -> m.remove(k) }
    }
}

// The time of the last data from the panel, for the watchdog. It changes with every frame, so it is kept in
// memory: after a hub restart it is empty and the watchdog simply waits for the next frame.
private void noteFrame(Long at = null) {
    FRAME_AT.put("${device.id}".toString(), at ?: now())
}

private long lastFrameAt() {
    return (FRAME_AT.get("${device.id}".toString()) ?: 0L) as long
}

def reconnect() {
    unschedule("reconnect")
    state.reconnectDueAt = 0L

    try {
        interfaces.rawSocket.close()
    } catch (ignored) {
    }

    state.loggedIn = false
    state.loginSent = false
    // A new session starts from a clean slate: queued commands may be stale and must not fire late.
    failOutstanding("reconnecting")
    state.restStartupDone = false
    state.authFailed = false
    state.rxBuffer = ""
    state.lastReconnectTs = now()
    scheduleOfflineCheck()

    // Installing the driver starts it before the app has given it an address.
    if (!settings.ip) {
        logInfo("No IP address set yet; not connecting")
        return
    }

    Integer p = safePort()
    logInfo("Opening raw socket to ${settings.ip}:${p}")
    sendEvent(name: "connectionState", value: "connecting")

    try {
        interfaces.rawSocket.connect(settings.ip, p, byteInterface: false)
    } catch (Exception e) {
        log.error "Socket connect failed: ${e}"
        sendEvent(name: "connectionState", value: "error")
        scheduleReconnect(nextReconnectDelay())
    }
}

private void scheduleReconnect(Integer seconds) {
    logInfo("Reconnecting in ${seconds}s")
    state.reconnectDueAt = now() + (seconds * 1000L)
    runIn(seconds, "reconnect")
}

// Exponential back-off: 2, 4, 8 ... up to 128 seconds. It resets once a connection has stayed up.
private Integer nextReconnectDelay() {
    Integer delay = safeInt(state.reconnectDelay, 2)
    state.reconnectDelay = Math.min(delay * 2, 128)
    return delay
}

private boolean reconnectPending() {
    return ((state.reconnectDueAt ?: 0L) as Long) > now()
}

// Words that mean the connection is gone or could not be made.
@Field static final List<String> SOCKET_FAILURE_WORDS = ["close", "disconnect", "error", "fail", "exception", "refused", "reset", "timeout",
                                                         "timed out", "unreachable", "no route", "unknown host", "broken pipe", "abort", "eof", "denied"]

def socketStatus(String message) {
    logDebug("socketStatus: ${message}")

    String m = (message ?: "").toLowerCase()

    // Check failure words first: "disconnected" and "failed to connect" both contain "connect". The hub's own
    // messages usually say "error" or "status: closed", but a Java exception text can arrive as it is.
    if (SOCKET_FAILURE_WORDS.any { String w -> m.contains(w) }) {
        logWarn("Socket disconnected: ${message}")
        if (!state.authFailed) {
            sendEvent(name: "connectionState", value: "disconnected")
        }
        state.loggedIn = false
        state.loginSent = false
        failOutstanding("connection lost")
        scheduleOfflineCheck()
        // One drop can produce several callbacks; only the first schedules a reconnect (and advances the back-off).
        // After a rejected password the longer delay is already scheduled, so retries can't lock the panel out.
        if (!reconnectPending()) {
            scheduleReconnect(state.authFailed ? 300 : nextReconnectDelay())
        }
        return
    }

    if (m.contains("open") || m.contains("connect")) {
        sendEvent(name: "connectionState", value: "connected")
    }
}

def login() {
    if (!settings.password) {
        log.warn "No UNO TPI password configured"
        return
    }

    // Outside a ^...$ packet the TPI treats 0-9, * and # as keypad keystrokes (spec 3.1), so a password
    // sent after login would be typed into the panel.
    if (isLoggedIn()) {
        logWarn("Already logged in; not sending the password")
        return
    }

    logInfo("Sending UNO login password")
    state.loginSent = true
    rawSend(settings.password + "\r")
}

def refresh() {
    refreshAll()
}

def refreshAll() {
    logDebug("Queueing refreshAll")
    enqueueCommand("0C", "", "refreshAll")
}

def poll() {
    // Any in-flight command or its ack already proves the link is alive.
    if (state.commandInFlight) return
    logDebug("Queueing poll")
    enqueueCommand("00", "", "poll")
}

def armStay(def partition = 1, String code = null) {
    Integer p = normalizePartition(partition)
    if (p == null) return
    if (!armCodeAccepted("armStay", code)) return
    enqueueCommand("08", "${p}", "armStay")
}

def armAway(def partition = 1, String code = null) {
    Integer p = normalizePartition(partition)
    if (p == null) return
    if (!armCodeAccepted("armAway", code)) return
    enqueueCommand("09", "${p}", "armAway")
}

// Bypasses whatever is open, then arms. The code check runs first so a rejected arm bypasses nothing.
def armStayBypass(def partition = 1, String code = null) { armBypassing("armStay", partition, code) }
def armAwayBypass(def partition = 1, String code = null) { armBypassing("armAway", partition, code) }

private void armBypassing(String which, def partition, String code) {
    Integer p = normalizePartition(partition)
    if (p == null) return
    if (!armCodeAccepted("${which}Bypass".toString(), code)) return

    logInfo("${which}: bypassing open zones on partition ${p} first")
    enqueueCommand("06", "${p}", "bypassOpenZones")
    if (which == "armStay") armStay(p, code) else armAway(p, code)
}

// TPI command 02: the UNO answers with a %FF frame of per-zone timers.
def dumpZoneTimers() {
    enqueueCommand("02", "", "zoneTimers")
}

// Forgets what was last reported, so the next frames re-announce every zone and partition.
// The app calls this after it creates or recreates devices, which start out knowing nothing.
def resyncStates() {
    state.lastZoneBitmap = null
    state.lastPartitionBitmap = null
    state.partitionStatus = [:]
    state.lastBypassBitmap = null
    state.lastTroubleBitmap = null
    state.troubleBytes = [:]
    clearSeen()
    refreshAll()
}

// "Require code to arm" is a local gate: the UNO's arm commands (08/09) take no code, so the code is
// compared with the configured master code here. Disarming always needs a code and is checked by the UNO.
private boolean armCodeAccepted(String name, String code) {
    if (settings.requireCodeToArm != true) return true

    String master = settings.masterCode?.toString()?.trim()
    String supplied = code?.toString()?.trim()

    if (!master) {
        log.warn "${name} rejected: 'Require code to arm' is on but no master code is configured"
    } else if (!supplied || supplied != master) {
        log.warn "${name} rejected: a valid code is required to arm"
    } else {
        return true
    }

    sendEvent(name: "lastAck", value: "rejected:${name}:code", isStateChange: true)
    return false
}

// TPI command 10: <partition>,<0|1>. Not available on EVL4MAX-SA / DUO-SA.
def chimeOn(def partition = 1) {
    Integer p = normalizePartition(partition)
    if (p == null) return
    enqueueCommand("10", "${p},1", "chimeOn", [partition: p])
}

def chimeOff(def partition = 1) {
    Integer p = normalizePartition(partition)
    if (p == null) return
    enqueueCommand("10", "${p},0", "chimeOff", [partition: p])
}

def disarm(String suppliedCode = null, def partition = 1) {
    Integer p = normalizePartition(partition)
    if (p == null) return

    String code = (suppliedCode ?: settings.masterCode)?.toString()?.trim()
    if (!code) {
        log.warn "Disarm requested without master code"
        return
    }
    // TPI command 12 takes a 4 to 6 digit code; reject anything else so it can't corrupt the packet.
    if (!(code ==~ /\d{4,6}/)) {
        log.warn "Disarm code must be 4 to 6 digits"
        return
    }
    enqueueCommand("12", "${p},", "disarm", [secret: code, log: "${p},${'*' * code.length()}"])
}

// TPI commands 04/05: 3 digit zone number.
def bypassZone(def zone) {
    Integer z = normalizeZone(zone)
    if (z == null) return
    enqueueCommand("04", z.toString().padLeft(3, "0"), "bypassZone")
}

def unbypassZone(def zone) {
    Integer z = normalizeZone(zone)
    if (z == null) return
    enqueueCommand("05", z.toString().padLeft(3, "0"), "unbypassZone")
}

// TPI commands 06/07: bypass all open zones / un-bypass all zones in a partition.
def bypassOpenZones(def partition = 1) {
    Integer p = normalizePartition(partition)
    if (p == null) return
    enqueueCommand("06", "${p}", "bypassOpenZones")
}

def unbypassAllZones(def partition = 1) {
    Integer p = normalizePartition(partition)
    if (p == null) return
    enqueueCommand("07", "${p}", "unbypassAllZones")
}

// TPI command 03 sends one key per command, so each key is queued and acknowledged on its own.
def sendKeys(String keys, def partition = 1) {
    Integer p = normalizePartition(partition)
    if (p == null) return

    String k = (keys ?: "").replaceAll("\\s", "")
    if (!(k ==~ /[0-9*#]{1,12}/)) {
        log.warn "Keys must be 1 to 12 characters from 0-9, * and #"
        return
    }

    for (int i = 0; i < k.length(); i++) {
        // Keys may contain a user code, so never log them.
        enqueueCommand("03", "${p},", "keypress", [secret: k.substring(i, i + 1), log: "${p},*"])
    }
}

// TPI command 11. Disabled unless explicitly enabled because a monitored panel dispatches emergency services.
def panic(String type, def partition = 1) {
    if (settings.enablePanic != true) {
        logWarn("Panic request ignored: enable 'Allow panic commands' in the app first")
        return
    }

    Integer p = normalizePartition(partition)
    if (p == null) return

    Map codes = [fire: "0", medical: "1", ambulance: "1", police: "2"]
    String t = codes[(type ?: "").toString().toLowerCase().trim()]
    if (t == null) {
        log.warn "Unknown panic type '${type}' (use fire, medical or police)"
        return
    }

    logWarn("PANIC requested: ${type} on partition ${p}")
    // Jump the queue and never resend automatically.
    enqueueCommand("11", "${p},${t}", "panic:${type}", [noRetry: true, priority: true])
}

def clearCommandQueue() {
    failOutstanding("cleared by user")
    logInfo("Cleared command queue")
}

private String driverVersion() {
    return "1.4.2"
}

// ---- Health: children report online while the connection is authenticated ----

def ping() {
    poll()
}

private void markHealth(String value) {
    if (state.health == value) return
    state.health = value
    sendEvent(name: "healthStatus", value: value)
    children().each { dev ->
        try {
            dev.sendEvent(name: "healthStatus", value: value)
        } catch (e) {
            logDebug("Could not set the health of ${dev.deviceNetworkId}: ${e}")
        }
    }
    parent?.notifyHealth(value)
}

// A reconnect gets 30 seconds before everything is declared offline, so a brief drop doesn't flap the devices.
private void scheduleOfflineCheck() {
    if (state.health == "offline") return
    if (((state.offlineCheckDueAt ?: 0L) as Long) > now()) return
    state.offlineCheckDueAt = now() + 30000L
    runIn(30, "offlineCheck")
}

def offlineCheck() {
    state.offlineCheckDueAt = 0L
    if (device.currentValue("connectionState") != "authenticated") {
        markHealth("offline")
    }
}

// TPI command 0A toggles or pulses a programmable output (UNO only), depending on how it is programmed.
// A repeat would undo a toggle, so it is never re-sent because of silence.
def triggerPgm(def pgm) {
    Integer n = safeInt(pgm, -1)
    if (n < 1 || n > 14) {
        logWarn("PGM must be 1 to 14 (got '${pgm}')")
        return
    }
    enqueueCommand("0A", n.toString().padLeft(2, "0"), "pgm${n}", [noRetry: true, pgm: n])
}

// Logs a redacted snapshot of the integration's state, for troubleshooting and support requests.
def debugDump() {
    List<String> lines = []
    lines << "UNO Connection ${driverVersion()}"
    lines.addAll(dumpConnectionLines())
    lines.addAll(dumpSettingsLines())
    lines.addAll(dumpActivityLines())
    historyList().take(5).each { Map e ->
        lines << "event ${e.at} p${e.partition} ${e.type}: ${e.text}"
    }

    lines.each { String line -> log.info "debugDump: ${redact(line)}" }
}

private List<String> dumpConnectionLines() {
    String model = device.currentValue("panelModel")
    String firmware = device.currentValue("firmwareVersion")
    String mac = device.currentValue("panelMac")
    return [
        "connection: ${device.currentValue('connectionState')}, health ${state.health ?: 'unknown'}, logged in ${state.loggedIn == true}, password rejected ${state.authFailed == true}",
        "panel: ${model ?: 'unknown'}, firmware ${firmware ?: 'unknown'}, MAC ${mac ?: 'unknown'}"
    ]
}

private List<String> dumpSettingsLines() {
    return [
        "settings: ip ${settings.ip}, port ${safePort()}, password ${settings.password ? 'set' : 'NOT SET'}, master code ${settings.masterCode ? 'set' : 'not set'}, REST ${settings.useRestMetadata == true}, keepalive ${keepaliveSeconds()}s, watchdog ${settings.watchdogMinutes ?: '3'}m, command timeout ${commandTimeoutSeconds()}s",
        "zones: ${configuredZones().size()} (${settings.zoneSet ? 'from zone set' : 'from zone count'}), devices: ${partitionChildren().size()} partition(s) and ${children().size() - partitionChildren().size()} zone(s) under this device",
        "options: panic ${settings.enablePanic == true}, require code to arm ${settings.requireCodeToArm == true}, diagnostic events ${settings.diagnosticEvents == true}, test events ${settings.recordTestEvents == true}, trace ${settings.traceLogging == true}, debug ${settings.debugLogging == true}"
    ]
}

private List<String> dumpActivityLines() {
    List queue = (state.commandQueue instanceof List) ? state.commandQueue : []
    return [
        "timing: last frame ${secondsSince(lastFrameAt())}, last reconnect ${secondsSince(state.lastReconnectTs)}, reconnect delay ${state.reconnectDelay ?: 2}s",
        "commands: ${queue.size()} queued (${queue*.name.join(', ') ?: 'none'}), in flight ${state.commandInFlight?.name ?: 'none'}, ${CODE_VAULT.size()} code(s) held in memory",
        "partitions: ${state.partitionStatus ?: [:]}",
        "troubles: ${state.troubleBytes ?: [:]}",
        "last ack: ${device.currentValue('lastAck') ?: 'none'}, last CID: ${state.lastCidEvent ?: 'none'}"
    ]
}

private String secondsSince(def ts) {
    long t = ((ts ?: 0L) as Long)
    return t > 0L ? "${((now() - t) / 1000L) as Long}s ago" : "never"
}

def heartbeatCheck() {
    if (device.currentValue("connectionState") == "authenticated") {
        poll()
    }
}

def watchdogCheck() {
    String cs = device.currentValue("connectionState")

    if (cs != "authenticated") {
        // Safety net for a connection that never recovered; auth failures retry on their own back-off.
        if (state.authFailed) return
        long sinceAttemptMs = now() - ((state.lastReconnectTs ?: 0L) as Long)
        if (sinceAttemptMs > 120000L) {
            logWarn("Watchdog reconnect: connection state is ${cs}")
            reconnect()
        }
        return
    }

    // A connection that has stayed up for 2 minutes is healthy again, so the next drop starts the back-off over.
    long authenticatedAt = (state.authenticatedAt ?: 0L) as Long
    if (authenticatedAt > 0L && (now() - authenticatedAt) > 120000L && safeInt(state.reconnectDelay, 2) != 2) {
        state.reconnectDelay = 2
    }

    long lastTs = lastFrameAt()
    if (lastTs == 0L) return

    // Never trip faster than a minute past the keepalive, or an idle panel would be reconnected needlessly.
    Integer wdSeconds = Math.max(safeInt(settings.watchdogMinutes, 3) * 60, keepaliveSeconds() + 60)

    long ageSeconds = (now() - lastTs) / 1000L
    if (ageSeconds > wdSeconds) {
        logWarn("Watchdog reconnect: no frames for ${ageSeconds}s")
        reconnect()
    }
}

private Integer safePort() {
    try {
        return (settings.port ?: 4025) as Integer
    } catch (ignored) {
        return 4025
    }
}

private Integer safeInt(def val, Integer fallback) {
    try {
        if (val == null) return fallback
        if (val instanceof Number) return ((Number) val).intValue()
        return Integer.parseInt(val.toString())
    } catch (ignored) {
        return fallback
    }
}

private Integer safeParseInt(String s, Integer fallback = 0) {
    try {
        return Integer.parseInt(s)
    } catch (ignored) {
        return fallback
    }
}

private Integer configuredZoneCount() {
    // The UNO supports up to 128 zones (TPI command 01 carries a 128 bit field).
    try {
        return Math.min(Math.max((settings.zoneCount ?: 27) as Integer, 1), 128)
    } catch (ignored) {
        return 27
    }
}

// Zones this integration manages: the zone set if one is configured, otherwise 1..zone count.
// The result only depends on two settings, so it is worked out once and reused for every frame.
private List<Integer> configuredZones() {
    String key = "${settings.zoneSet}|${settings.zoneCount}".toString()
    List<Integer> cached = (List<Integer>) ZONE_CACHE.get(key)
    if (cached != null) return cached

    Map parsed = parseRange(settings.zoneSet, 1, 128)
    List<Integer> zones = parsed.values ? (parsed.values as List<Integer>) : (1..configuredZoneCount()).collect { it as Integer }
    if (ZONE_CACHE.size() > 16) ZONE_CACHE.clear()
    ZONE_CACHE.put(key, zones)
    return zones
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

private Integer normalizePartition(def partition) {
    Integer p = (partition == null || partition == "") ? 1 : safeInt(partition, -1)
    if (p < 1 || p > 8) {
        logWarn("Partition must be 1 to 8 (got '${partition}')")
        return null
    }
    return p
}

private Integer normalizeZone(def zone) {
    Integer z = safeInt(zone, -1)
    if (z < 1 || z > 128) {
        logWarn("Zone must be 1 to 128 (got '${zone}')")
        return null
    }
    return z
}

// Masks the TPI password and master code wherever they appear in text that is about to be logged.
private String redact(String text) {
    String out = text ?: ""
    [settings.password, settings.masterCode].each { secret ->
        String s = secret?.toString()
        if (s) out = out.replace(s, "*" * s.length())
    }
    return out
}

private String tpiCommand(String cc, String data = "") {
    return "^" + cc + "," + (data ?: "") + '$'
}

private void enqueueCommand(String cc, String data, String name, Map opts = [:]) {
    state.commandQueue = state.commandQueue ?: []

    Map entry = [cc: cc, data: (data ?: ""), name: name, ts: now()]
    ["log", "noRetry", "partition", "pgm"].each { String key ->
        if (opts[key] != null) entry[key] = opts[key]
    }

    // A code or key press is kept in memory only. The queue lives in state (the hub's database), so it
    // holds a reference and the command text is finished from the vault at send time.
    if (opts.secret != null) {
        state.secretSeq = safeInt(state.secretSeq, 0) + 1
        String key = "${device.id}:${state.secretSeq}".toString()
        CODE_VAULT[key] = opts.secret.toString()
        entry.secretKey = key
    }

    if (opts.priority) {
        state.commandQueue.add(0, entry)
    } else {
        state.commandQueue << entry
    }

    logDebug("Queued ${name}")
    // Backstop so a command stuck behind a dead connection still expires without any other activity.
    runIn(queueMaxAgeSeconds() + 5, "expireQueue")
    processQueue()
}

// The data to send for a command: its stored text plus the secret part, if it has one.
// Returns null when the secret is gone (the hub restarted), which means the command can't be sent.
private String commandData(Map c) {
    String data = (c.data ?: "").toString()
    if (c.secretKey) {
        String secret = CODE_VAULT[c.secretKey]
        if (secret == null) return null
        data += secret
    }
    return data
}

private void releaseSecret(def c) {
    if (c?.secretKey) CODE_VAULT.remove(c.secretKey)
}

// How long to wait for the UNO to answer a command before re-sending it (once) and then giving up.
private Integer commandTimeoutSeconds() {
    return Math.min(Math.max(safeInt(settings.commandTimeoutSeconds, 4), 1), 60)
}

// A command that hasn't been sent within this time is dropped rather than run late. A longer reply timeout
// lets a command wait behind slower ones, so the limit grows with it.
private Integer queueMaxAgeSeconds() {
    return Math.max(30, commandTimeoutSeconds() * 4)
}

def expireQueue() {
    expireStaleCommands()
}

private void expireStaleCommands() {
    if (!(state.commandQueue instanceof List) || state.commandQueue.isEmpty()) return

    long cutoff = now() - (queueMaxAgeSeconds() * 1000L)
    List stale = state.commandQueue.findAll { ((it.ts ?: 0L) as Long) < cutoff }
    if (!stale) return

    state.commandQueue.removeAll(stale)
    stale.each { releaseSecret(it) }
    reportDropped(stale, "waited more than ${queueMaxAgeSeconds()}s to be sent")
}

// Drops the in-flight command and everything queued behind it, and says so.
private void failOutstanding(String reason) {
    List dropped = []
    if (state.commandInFlight) dropped << state.commandInFlight
    if (state.commandQueue instanceof List) dropped.addAll(state.commandQueue)

    state.commandInFlight = null
    state.commandQueue = []
    unschedule("commandTimeoutCheck")
    unschedule("retryInFlight")

    dropped.each { releaseSecret(it) }
    if (dropped) reportDropped(dropped, reason)
}

private void reportDropped(List dropped, String reason) {
    dropped.each { def c ->
        String name = (c.name ?: "command").toString()
        sendEvent(name: "lastAck", value: "dropped:${name}", isStateChange: true)
        if (name.startsWith("panic")) {
            log.error "PANIC command '${name}' was NOT sent (${reason})"
        }
    }
    logWarn("Dropped ${dropped.size()} command(s) (${reason}): ${dropped*.name.join(', ')}")
}

// Hub state is saved when an execution ends, so a reconnect that was still finishing can write "not logged in" over a login
// that completed in the meantime (the queue then waits for a login that never comes). The connection state attribute is
// written at once and only says "authenticated" between a login and the next drop, so it settles the question.
private boolean isLoggedIn() {
    if (state.loggedIn) return true
    if (device.currentValue("connectionState") != "authenticated") return false

    state.loggedIn = true
    return true
}

private void processQueue() {
    expireStaleCommands()

    if (!isLoggedIn()) {
        logDebug("Queue paused until login completes")
        return
    }

    if (state.commandInFlight) {
        logDebug("Queue paused; waiting for ack")
        return
    }

    if (!(state.commandQueue instanceof List) || state.commandQueue.isEmpty()) {
        return
    }

    def next = state.commandQueue.remove(0)
    state.commandInFlight = [
        cc       : next.cc,
        data     : next.data,
        name     : next.name,
        log      : next.log,
        noRetry  : next.noRetry,
        partition: next.partition,
        pgm      : next.pgm,
        secretKey: next.secretKey,
        sentAt   : now(),
        retries  : 0,
        overruns : 0,
        waiting  : false
    ]

    logInfo("Sending ${next.name}")
    sendInFlight()
}

// Sends (or re-sends) the in-flight command and starts its reply timer.
private void sendInFlight() {
    def inflight = state.commandInFlight
    if (!inflight) return

    String data = commandData(inflight)
    if (data == null) {
        // The code was only ever held in memory, so after a hub restart there is nothing to send.
        logWarn("${inflight.name} dropped: its code is no longer available")
        sendEvent(name: "lastAck", value: "dropped:${inflight.name}", isStateChange: true)
        state.commandInFlight = null
        processQueue()
        return
    }

    String cmd = tpiCommand(inflight.cc as String, data)
    String logCmd = tpiCommand(inflight.cc as String, (inflight.log ?: data) as String)
    rawSend(cmd + "\r\n", logCmd + "\r\n")
    runIn(commandTimeoutSeconds(), "commandTimeoutCheck")
}

// Re-sends a command the panel rejected as busy (response code 1), after a back-off delay.
def retryInFlight() {
    def inflight = state.commandInFlight
    if (!inflight || !inflight.waiting) return

    inflight.waiting = false
    inflight.sentAt = now()
    state.commandInFlight = inflight
    sendInFlight()
}

def commandTimeoutCheck() {
    def inflight = state.commandInFlight
    if (!inflight || inflight.waiting) return

    long ageMs = now() - (inflight.sentAt as Long)
    // The timer fires on the second, so allow half a second of slack.
    if (ageMs < (commandTimeoutSeconds() * 1000L - 500L)) return

    Map early = answeredEarly(inflight)
    if (early) {
        logDebug("${inflight.name} was answered before it was recorded as sent")
        finishCommand(inflight, early.code as Integer, early.ee as String)
        return
    }

    if (!inflight.noRetry && (inflight.retries as Integer) < 1) {
        inflight.retries = ((inflight.retries as Integer) + 1)
        inflight.sentAt = now()
        state.commandInFlight = inflight

        logWarn("Retrying ${inflight.name}")
        sendInFlight()
    } else {
        logWarn("Command timeout: ${inflight.name}" + (inflight.noRetry ? " (not retried)" : ""))
        sendEvent(name: "lastAck", value: "timeout:${inflight.name}")
        releaseSecret(inflight)
        state.commandInFlight = null
        // Silence after a command (and its retry) means the link is dead; reconnecting also drops the
        // commands queued behind it, which would otherwise run late.
        logWarn("No reply from the UNO; reconnecting")
        reconnect()
    }
}

private void rawSend(String msg, String logMsg = null) {
    if (settings.traceLogging) {
        log.debug "TX >>> ${logMsg ?: redact(msg)}"
    }
    try {
        interfaces.rawSocket.sendMessage(msg)
    } catch (Throwable e) {
        // A socket that can't be written to is a lost connection. Treating it as one reconnects, and drops the
        // command that couldn't be sent, instead of leaving it to block the queue.
        logWarn("Send failed: ${e}")
        socketStatus("error: send failed")
    }
}

def parse(String message) {
    if (message == null) return

    String decoded = decodeIncoming(message)
    if (decoded == null) return

    noteFrame()
    if (settings.diagnosticEvents == true) {
        sendEvent(name: "lastHeartbeat", value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone))
    }

    // Work on a local copy and store the remainder once: every state access is a database round trip on the hub.
    String buffer = takeLoginPrompt((state.rxBuffer ?: "") + decoded)

    while (true) {
        List split = splitFrame(buffer)
        if (split == null) break
        buffer = split[1] as String
        handleRawFrame(split[0] as String)
    }

    buffer = takeBareLoginReply(buffer)

    // The longest real frame (zone timers) is about 520 characters. Far more than that with no terminator is
    // damaged data, and keeping it would grow the hub's state for ever.
    if (buffer.length() > 4096) {
        logWarn("Dropped ${buffer.length()} characters from the receive buffer: no frame terminator")
        buffer = ""
    }

    state.rxBuffer = buffer
}

// The login prompt has no terminator, so it is removed from the buffer (or it would prefix the "OK" reply) and
// answered with the password. Returns the rest of the buffer.
private String takeLoginPrompt(String buffer) {
    if (isLoggedIn() || state.loginSent) return buffer

    int idx = buffer.toLowerCase().indexOf("login")
    if (idx < 0) return buffer

    int end = idx + 5
    if (buffer.length() > end && buffer.substring(end, end + 1) == ":") end++
    logInfo("UNO presented login prompt")
    sendEvent(name: "connectionState", value: "loginPrompt")
    login()
    return buffer.substring(end)
}

// The next complete frame (ended by $, CR or LF) and what follows it, or null if there is no complete frame yet.
private List splitFrame(String buffer) {
    List<Integer> candidates = [buffer.indexOf('$'), buffer.indexOf('\n'), buffer.indexOf('\r')].findAll { it >= 0 }
    if (!candidates) return null

    int endIdx = candidates.min()
    return [buffer.substring(0, endIdx).trim(), buffer.substring(endIdx + 1)]
}

private void handleRawFrame(String raw) {
    String frame = dropTruncatedPrefix(raw)
    if (!frame) return

    // One bad frame must not stop the rest of the buffer from being processed.
    try {
        parseFrame(frame)
    } catch (Exception e) {
        logWarn("Error handling frame '${redact(frame).take(80)}': ${e}")
    }
}

// Every frame starts at its own % (or ^ for an acknowledgement). One found inside a frame means the data
// before it was cut short, so only the last frame is kept instead of losing both to a failed parse.
private String dropTruncatedPrefix(String frame) {
    int restart = Math.max(frame.lastIndexOf('%'), frame.lastIndexOf('^'))
    if (restart <= 0) return frame

    logDebug("Dropped truncated data before a new frame: ${redact(frame.substring(0, restart)).take(80)}")
    return frame.substring(restart)
}

private boolean isLoginReply(String text) {
    return ["OK", "FAILED", "Timed Out!", "Timed Out"].any { String word -> text.equalsIgnoreCase(word) }
}

// Login replies are plain words; accept them even if the panel doesn't terminate them.
private String takeBareLoginReply(String buffer) {
    if (!state.loginSent || state.loggedIn) return buffer

    String pending = buffer.trim()
    if (!isLoginReply(pending)) return buffer

    parseFrame(pending)
    return ""
}

private String decodeIncoming(String message) {
    try {
        byte[] data = hubitat.helper.HexUtils.hexStringToByteArray(message)
        // One byte is one character whatever the hub's default character set, so a damaged byte can't change how
        // many characters follow it (and can't swallow a terminator).
        String decoded = new String(data, "ISO-8859-1")
        if (settings.traceLogging) {
            log.debug "RX HEX <<< ${message}"
            log.debug "RX TXT <<< ${decoded}"
        }
        return decoded
    } catch (ignored) {
        if (settings.traceLogging) {
            log.debug "RX RAW <<< ${message}"
        }
        return message
    }
}

private void parseFrame(String frame) {
    if (settings.diagnosticEvents == true) {
        sendEvent(name: "lastRawMessage", value: frame.take(1024))
    }

    if (handleLoginText(frame)) return

    if (frame.startsWith("^")) {
        parseAckFrame(frame)
        return
    }

    if (!frame.startsWith("%")) {
        logDebug("Ignoring non-frame message: ${frame}")
        return
    }

    List<String> parts = frame.substring(1).split(",", -1) as List<String>
    if (!parts || parts.isEmpty()) return

    dispatchFrame(parts[0], parts)
}

// The plain-word replies of the login exchange. Returns true if the text was one of them.
private boolean handleLoginText(String frame) {
    if (frame.equalsIgnoreCase("OK")) {
        onLoginAccepted()
    } else if (frame.equalsIgnoreCase("FAILED")) {
        onLoginRejected()
    } else if (frame.equalsIgnoreCase("Timed Out!") || frame.equalsIgnoreCase("Timed Out")) {
        onLoginTimedOut()
    } else if (frame.equalsIgnoreCase("Login:")) {
        logDebug("Ignoring login prompt frame after handling")
    } else {
        return false
    }
    return true
}

private void onLoginAccepted() {
    state.loggedIn = true
    state.loginSent = false
    state.authFailed = false
    state.authenticatedAt = now()
    logInfo("UNO login successful")
    sendEvent(name: "connectionState", value: "authenticated")
    unschedule("offlineCheck")
    state.offlineCheckDueAt = 0L
    markHealth("online")

    if (settings.useRestMetadata == true && state.restStartupDone != true) {
        try {
            restStartupSync()
        } catch (e) {
            logWarn("REST startup sync failed: ${e}")
        }
    }

    // The panel sends host information (%05) on its own with each poll, but asking now means the model, firmware
    // and MAC are known straight away rather than at the first poll (which can be 10 minutes away).
    enqueueCommand("0C", "", "refreshAll")
    enqueueCommand("0D", "", "hostInfo")
    processQueue()
}

private void onLoginRejected() {
    state.loggedIn = false
    state.loginSent = false
    state.authFailed = true
    logWarn("UNO login failed")
    sendEvent(name: "connectionState", value: "auth_failed")
    scheduleReconnect(300)
}

private void onLoginTimedOut() {
    state.loginSent = false
    logWarn("UNO login timed out")
    sendEvent(name: "connectionState", value: "loginTimedOut")
    scheduleReconnect(nextReconnectDelay())
}

// Routes a frame from the UNO (%CC,DATA$) to its handler by command code (spec 3.3).
private void dispatchFrame(String code, List<String> parts) {
    switch (code) {
        case "01":
            handleZoneBitmap(parts)
            break
        case "02":
            handlePartitionFrame(parts)
            break
        case "03":
            handleCidEvent(parts)
            break
        case "04":
            handleBypassBitmap(parts)
            break
        case "05":
            handlePanelInfo(parts)
            break
        case "06":
            handleTroubleBitmap(parts)
            break
        case "07":
            handleZoneTemperatures(parts)
            break
        case "08":
            handlePartitionTemperatures(parts)
            break
        case "09":
            handleKeypadSound(parts)
            break
        case "0A":
            handlePersistentSound(parts)
            break
        case "10":
            handleChime(parts)
            break
        case "FF":
            handleZoneTimers(parts)
            break
        default:
            logDebug("Unhandled UNO frame ${code}: ${parts}")
            break
    }
}

def restStartupSync() {
    if (!settings.useRestMetadata || !settings.ip || !settings.password) return

    Map snapshot = null
    try {
        snapshot = restGetJson("A")
    } catch (Exception e) {
        noteRestFailure(e)
        return
    }
    if (!snapshot) return

    def partitions = snapshot["partitions"] instanceof List ? snapshot["partitions"] : []
    partitions.eachWithIndex { def part, int idx ->
        if (!(part instanceof Map) || idx >= 8) return
        Integer pStatus = safeInt(part["status"], -1)
        String mapped = mapRestPartitionStatus(pStatus)
        if (!mapped) return

        Integer partitionNum = idx + 1
        if (partitionNum == 1) sendEvent(name: "alarmSystemStatus", value: mapped)
        state.partitionStatus = state.partitionStatus ?: [:]
        state.partitionStatus["${partitionNum}"] = mapped
        sendPartitionState(partitionNum, mapped)
    }

    def zoneStatus = snapshot["zoneStatus"] instanceof List ? snapshot["zoneStatus"] : []
    if (zoneStatus && zoneStatus[0] instanceof List) {
        Set<Integer> zones = configuredZones() as Set<Integer>
        (zoneStatus[0] as List).each { z ->
            if (!(z instanceof Map)) return
            Integer zoneNum = safeInt(z["n"], 0)
            Integer t = safeInt(z["t"], 0)
            if (!zones.contains(zoneNum)) return

            boolean active = isRestZoneActive(t)
            routeZoneUpdate(zoneNum, active)
        }
    }

    state.restStartupDone = true
    sendEvent(name: "restStartupSync", value: "ok")
    logInfo("REST startup sync complete")
}

// A 401 means the REST credentials are wrong, which is worth calling out separately from "unreachable".
private void noteRestFailure(Exception e) {
    Integer status = null
    try {
        status = (e.hasProperty("statusCode") ? e.statusCode : e.response?.status) as Integer
    } catch (ignored) {
    }

    if (status == 401) {
        log.error "UNO rejected the REST credentials (HTTP 401). Check the REST username and the local password."
        sendEvent(name: "restStartupSync", value: "auth_failed")
    } else {
        logWarn("REST startup sync failed: ${e}")
        sendEvent(name: "restStartupSync", value: "failed")
    }
}

private boolean isRestZoneActive(Integer t) {
    return ((t ?: 0) as Integer) == 65535
}

private String mapRestPartitionStatus(Integer status) {
    switch (status) {
        case 1: return "disarmed"
        case 2: return "readyBypassed"
        case 3: return "notReady"
        case 4: return "armedStay"
        case 5: return "armedAway"
        case 8: return "exitDelay"
        case 9: return "armedAwayZeroEntry"
        case 12: return "entryDelay"
        case 17: return "alarm"
        default: return null
    }
}

private Map restGetJson(String path) {
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

private void parseAckFrame(String frame) {
    String body = frame.substring(1)
    if (body.endsWith('$')) {
        body = body.substring(0, body.length() - 1)
    }

    List<String> parts = body.split(",", -1) as List<String>
    if (parts.size() < 2) return

    String cc = parts[0]
    String ee = parts[1]

    // An acknowledgement is two hex digits (the command) and a response code. Anything else is damaged data.
    if (!(cc ==~ /[0-9A-Fa-f]{2}/) || !(ee ==~ /\d+/)) {
        logDebug("Ignoring malformed acknowledgement: ${redact(frame).take(40)}")
        return
    }
    Integer code = safeParseInt(ee, -1)
    String ack = cc + "," + ee
    sendEvent(name: "lastAck", value: ack)
    logDebug("ACK: ${ack}")

    // Kept in memory as well: the reply can arrive before the execution that sent the command has saved it as in
    // flight, and the timeout check then finds it here instead of resending a command that was already answered.
    ACK_SEEN.put("${device.id}".toString(), [cc: cc.toUpperCase(), ee: ee, code: code, at: now()])

    def inflight = state.commandInFlight
    // The ack echoes the command it answers; ignore acks for anything other than the in-flight command.
    if (!inflight || !cc.equalsIgnoreCase(inflight.cc as String)) {
        return
    }

    finishCommand(inflight, code, ee)
}

// Completes the in-flight command from the panel's response code (0 is accepted).
private void finishCommand(Map inflight, Integer code, String ee) {
    if (code == 1 && retryBusyCommand(inflight)) return

    if (code != 0) {
        logWarn("Command ${inflight.name} rejected: ${tpiResponseText(code)} (code ${ee})")
        sendEvent(name: "lastAck", value: "error:${inflight.name}:${ee}", isStateChange: true)
    } else {
        onCommandAccepted(inflight)
    }
    releaseSecret(inflight)
    state.commandInFlight = null

    processQueue()
}

// The reply to the in-flight command if it already arrived (after the command was sent), otherwise null.
private Map answeredEarly(Map inflight) {
    Map seen = (Map) ACK_SEEN.get("${device.id}".toString())
    if (seen == null || seen.cc != (inflight.cc as String).toUpperCase()) return null
    return ((seen.at as Long) >= (inflight.sentAt as Long)) ? seen : null
}

// Response code 1 means the panel was still busy and did not run the command (spec 3.7), so it is safe to
// send it again after a growing delay: 1s, 2s, 4s. Returns false once the retries are used up.
private boolean retryBusyCommand(Map inflight) {
    if (safeInt(inflight.overruns, 0) >= 3) return false

    Integer attempt = safeInt(inflight.overruns, 0) + 1
    Integer delay = 1 << (attempt - 1)
    inflight.overruns = attempt
    inflight.waiting = true
    state.commandInFlight = inflight
    unschedule("commandTimeoutCheck")
    logWarn("${inflight.name}: the UNO was busy (receive buffer overrun); retry ${attempt} of 3 in ${delay}s")
    runIn(delay, "retryInFlight")
    return true
}

// What to record when the panel accepted a command, for the commands that get no state back.
private void onCommandAccepted(Map inflight) {
    if (inflight.name == "chimeOn" || inflight.name == "chimeOff") {
        // The TPI doesn't report chime state, so this reflects the last accepted command.
        String chime = inflight.name == "chimeOn" ? "on" : "off"
        Integer chimePartition = safeInt(inflight.partition, 1)
        if (chimePartition == 1) sendEvent(name: "chime", value: chime)
        sendPartitionEvents(chimePartition, [[name: "chime", value: chime]])
    } else if (inflight.pgm) {
        // No state comes back for a PGM, so the best we can say is when the panel last accepted a trigger.
        parent?.notifyPgmEvents(inflight.pgm as Integer, [[name: "lastTriggered", value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone), isStateChange: true]])
    }
    if (inflight.name == "armStay" || inflight.name == "armAway") {
        noteArmRequest(safeInt(inflight.data, 1), inflight.name == "armStay" ? "stay" : "away")
    }
}

private String tpiResponseText(Integer code) {
    switch (code) {
        case 1: return "receive buffer overrun"
        case 2: return "unknown command"
        case 3: return "syntax error"
        case 6: return "command cannot be completed (e.g. partition not ready or already armed)"
        case 7: return "invalid user code"
        case 8: return "partition, zone or PGM out of range"
        default: return "unknown response"
    }
}

private void handleZoneBitmap(List<String> parts) {
    if (parts.size() < 2) return

    String bitmapHex = cleanPayload(parts[1]).toUpperCase()
    if (!(bitmapHex ==~ /[0-9A-F]+/)) {
        logWarn("Ignoring malformed zone bitmap: ${bitmapHex.take(64)}")
        return
    }
    String previous = state.lastZoneBitmap
    // Polls and refreshes repeat the same bitmap; there is nothing to do for those.
    if (previous == bitmapHex) return
    if (previous == null || previous.length() != bitmapHex.length()) sendEvent(name: "zoneBitmapLength", value: bitmapHex.length())
    state.lastZoneBitmap = bitmapHex

    // Bitmaps are padded to whole bytes; only the configured zones are real. A zone whose byte is
    // unchanged cannot have changed, so those are skipped without decoding.
    for (Integer zone : configuredZones()) {
        if (previous != null && byteAt(previous, zone) == byteAt(bitmapHex, zone)) continue

        boolean newState = zoneBit(bitmapHex, zone)
        if (previous == null || newState != zoneBit(previous, zone)) {
            Long lastCidTs = (Long) CID_SEEN.get("${device.id}:${zone}".toString())
            boolean suppressDebug = lastCidTs != null && (now() - lastCidTs) >= 0 && (now() - lastCidTs) <= 1500L

            if (!suppressDebug) {
                logDebug("Zone ${zone} -> ${newState ? 'active/open' : 'inactive/closed'}")
            }

            emitZoneFastEvent(zone, newState)
            routeZoneUpdate(zone, newState)
        }
    }

    publishZoneSummary()
}

// Zone N is bit (N-1) % 8 of byte (N-1) / 8 (spec 3.3, command 01).
private String byteAt(String hex, Integer zone) {
    int idx = (zone - 1).intdiv(8) * 2
    return (idx + 2 <= hex.length()) ? hex.substring(idx, idx + 2) : ""
}

private boolean zoneBit(String hex, Integer zone) {
    String b = byteAt(hex, zone)
    if (!b) return false
    return ((Integer.parseInt(b, 16) >> ((zone - 1) % 8)) & 0x01) == 1
}

// Which zones are open and which are bypassed, by name, for dashboards and notifications. Each open zone also
// carries the time it opened, as JSON for rules and as text for notifications and tiles.
private void publishZoneSummary() {
    List<Integer> zones = configuredZones()
    Map<Integer, Map> open = reconcileOpenZones(zones)
    List<Integer> openZones = open.keySet().toList()
    String bypassed = zoneList(zones, state.lastBypassBitmap)

    List<Map> detail = open.collect { Integer z, Map e ->
        Map row = [zone: z, name: zoneNameOnly(z), since: formatTime(e.since as Long, true)]
        if (e.held) row.bypassed = true
        return row
    }
    String timed = open.collect { Integer z, Map e -> "${listLabel(z)} since ${formatTime(e.since as Long, false)}${e.held ? ' (bypassed)' : ''}".toString() }.join("; ")

    Map values = [
        openZones      : openZones ? openZones.collect { Integer z -> listLabel(z) }.join(", ") : "none",
        openZoneCount  : openZones.size(),
        openZonesTimed : timed ?: "none",
        // A page of the list is plenty for a tile; the count above says if there are more.
        openZonesDetail: JsonOutput.toJson(detail.take(25)),
        bypassedZones  : bypassed
    ]

    Map changed = [:]
    values.each { String name, def value ->
        if (device.currentValue(name)?.toString() != value.toString()) changed[name] = value
    }
    if (changed) {
        changed.each { String name, def value -> sendEvent(name: name, value: value) }
        try {
            forwardPartitionSummary(changed)
            parent?.notifyPanelSummary(changed)
        } catch (e) {
            logDebug("Failed to forward the zone summary: ${e}")
        }
    }

    publishPartitionZones(openZones as Set<Integer>)
}

// The zones to list as open, with when each opened. Normally that is what the zone bitmap says. With "keep
// bypassed zones in the open list", a zone that was open when it was bypassed stays listed until the bypass is
// removed, because some panels report a bypassed zone as closed whatever it is really doing.
private Map<Integer, Map> reconcileOpenZones(List<Integer> zones) {
    boolean hold = settings.holdBypassedOpenZones == true
    String prefix = "${device.id}:".toString()
    long t = now()
    Map<Integer, Map> result = new TreeMap<Integer, Map>()

    // A zone that closed a moment before its bypass arrived is still restored; older ones are forgotten.
    LEFT_OPEN.keySet().findAll { String k -> k.startsWith(prefix) && (t - ((Map) LEFT_OPEN.get(k)).leftAt) > 60000L }.each { String k -> LEFT_OPEN.remove(k) }

    zones.each { Integer z ->
        boolean isOpen = state.lastZoneBitmap ? zoneBit(state.lastZoneBitmap, z) : false
        boolean bypassed = state.lastBypassBitmap ? zoneBit(state.lastBypassBitmap, z) : false
        String key = "${prefix}${z}".toString()
        Long since = (Long) OPEN_SINCE.get(key)

        if (isOpen) {
            if (since == null) { since = t; OPEN_SINCE.put(key, since) }
            LEFT_OPEN.remove(key)
            result[z] = [since: since, held: bypassed]
        } else if (hold && bypassed) {
            Map left = (Map) LEFT_OPEN.get(key)
            if (since == null && left != null && (t - (left.leftAt as Long)) <= 10000L) {
                since = left.since as Long
                OPEN_SINCE.put(key, since)
            }
            LEFT_OPEN.remove(key)
            if (since != null) result[z] = [since: since, held: true]
        } else if (since != null) {
            OPEN_SINCE.remove(key)
            LEFT_OPEN.put(key, [since: since, leftAt: t])
        }
    }
    return result
}

private String zoneNameOnly(Integer zone) {
    return cachedZoneName(zone) ?: "zone ${zone}".toString()
}

// The same zone appears in several lists on every change, and each name is a call to the app, so names used in
// the lists are kept for a minute. (Event text looks names up afresh: events are rare and should be exact.)
private String cachedZoneName(Integer zone) {
    String key = "${device.id}:${zone}".toString()
    long t = now()
    Map hit = (Map) NAME_CACHE.get(key)
    if (hit != null && (t - (hit.at as Long)) < 60000L) return hit.name as String

    String name = null
    try {
        name = zoneName(zone)
    } catch (ignored) {
    }
    NAME_CACHE.put(key, [name: name, at: t])
    return name
}

private String listLabel(Integer zone) {
    String name = cachedZoneName(zone)
    return name ? "${name} (zone ${zone})".toString() : "zone ${zone}".toString()
}

// Full date and time for rules; for people just the time when it was today, otherwise the date as well.
private String formatTime(Long ms, boolean full) {
    Date d = new Date(ms)
    if (full) return d.format("yyyy-MM-dd HH:mm:ss", location.timeZone)
    boolean today = d.format("yyyy-MM-dd", location.timeZone) == new Date(now()).format("yyyy-MM-dd", location.timeZone)
    return d.format(today ? "HH:mm" : "MM-dd HH:mm", location.timeZone)
}

// With zones assigned to partitions, each partition device gets the lists for its own zones only.
// What was last sent is remembered in memory so an unchanged list isn't sent again.
private void publishPartitionZones(Set<Integer> openSet) {
    partitionZoneMap().each { Integer partition, List<Integer> mine ->
        List events = []
        List<Integer> mineOpen = mine.findAll { Integer z -> openSet.contains(z) }
        String openText = mineOpen ? mineOpen.collect { Integer z -> listLabel(z) }.join(", ") : "none"
        [openZones: openText, bypassedZones: zoneList(mine, state.lastBypassBitmap)].each { String name, String value ->
            String key = "${device.id}:${partition}:${name}".toString()
            if (PARTITION_SEEN.get(key) != value) {
                PARTITION_SEEN.put(key, value)
                events << [name: name, value: value]
            }
        }
        if (!events) return
        try {
            sendPartitionEvents(partition, events)
        } catch (e) {
            logDebug("Failed to forward the zone lists to partition ${partition}: ${e}")
        }
    }
}

// "1:1-8;2:9-16" (or one assignment per line) -> [1: [1..8], 2: [9..16]]. Only configured zones count, and a
// zone listed twice stays with the first partition. The app validates and normalises what it sends.
private Map<Integer, List<Integer>> partitionZoneMap() {
    String text = (settings.partitionZones ?: "").toString().trim()
    if (!text) return [:]

    String key = "pz|${text}|${settings.zoneSet}|${settings.zoneCount}".toString()
    Map<Integer, List<Integer>> cached = (Map<Integer, List<Integer>>) ZONE_CACHE.get(key)
    if (cached != null) return cached

    Set<Integer> valid = configuredZones() as Set<Integer>
    Set<Integer> taken = new HashSet<Integer>()
    Map<Integer, List<Integer>> result = new TreeMap<Integer, List<Integer>>()

    text.split("[;\\n]").each { String line ->
        int colon = line.indexOf(":")
        if (colon < 0) return
        Integer partition = safeParseInt(line.substring(0, colon).trim(), 0)
        if (partition < 1 || partition > 8) return

        List<Integer> zones = parseRange(line.substring(colon + 1), 1, 128).values.findAll { Integer z -> valid.contains(z) && taken.add(z) }
        // A line with no usable zones is not an assignment, or that partition would show nothing forever.
        if (zones) result[partition] = ((result[partition] ?: []) + zones).sort()
    }

    if (ZONE_CACHE.size() > 16) ZONE_CACHE.clear()
    ZONE_CACHE.put(key, result)
    return result
}

private String zoneList(List<Integer> zones, String bitmap) {
    if (!bitmap) return "none"
    List<String> names = zones.findAll { Integer z -> zoneBit(bitmap, z) }.collect { Integer z -> listLabel(z) }
    return names ? names.join(", ") : "none"
}

// While the UNO counts down an exit delay it doesn't say whether the arm is stay or away (spec 3.4 has one
// "exit delay" state). A dashboard that read the previous arm's mode would show the wrong one for the whole delay,
// so armingMode says what was requested (by our own arm command, or a stay-arm Contact ID event), or "unknown"
// when someone armed from a keypad, and "none" outside an exit delay.
private void noteArmRequest(Integer partition, String mode) {
    String current = (state.partitionStatus instanceof Map) ? state.partitionStatus["${partition}"] : null
    // If the arm has already finished (the acknowledgement came after the state change) there is nothing to colour.
    if (current in ["armedStay", "armedAway", "armedAwayZeroEntry"]) return

    Map requests = (state.armRequests instanceof Map) ? state.armRequests : [:]
    requests["${partition}"] = "${mode}|${now()}".toString()
    state.armRequests = requests
    // The request can be confirmed after the exit delay has already started.
    if (current == "exitDelay") publishArmingMode(partition, "exitDelay")
}

private void consumeArmRequest(Integer partition) {
    // remove() needs a real String: a GString has a different hash code and would never match the key.
    if (state.armRequests instanceof Map) state.armRequests.remove(partition.toString())
}

private String requestedArmMode(Integer partition) {
    String value = (state.armRequests instanceof Map) ? state.armRequests["${partition}"] : null
    if (!value || !value.contains("|")) return "unknown"
    List<String> bits = value.split("\\|") as List<String>
    long at = 0L
    try { at = Long.parseLong(bits[1]) } catch (ignored) { }
    // An exit delay can run for minutes; an older request belongs to some earlier arm.
    return (now() - at) <= 300000L ? bits[0] : "unknown"
}

private void publishArmingMode(Integer partition, String status) {
    String mode = status == "exitDelay" ? requestedArmMode(partition) : "none"
    try {
        sendPartitionEvents(partition, [[name: "armingMode", value: mode]])
    } catch (e) {
        logDebug("Failed to forward the arming mode to partition ${partition}: ${e}")
    }
}

// %02 carries one status byte per partition (partition 1 first). Only changes are forwarded.
private void handlePartitionFrame(List<String> parts) {
    if (parts.size() < 2) return

    String bitmap = cleanPayload(parts[1]).toUpperCase()
    if (!(bitmap ==~ /[0-9A-F]+/)) {
        logWarn("Ignoring malformed partition frame: ${bitmap.take(64)}")
        return
    }
    state.lastPartitionBitmap = bitmap
    state.partitionStatus = state.partitionStatus ?: [:]

    for (int idx = 0; idx < 8 && (idx * 2 + 2) <= bitmap.length(); idx++) {
        Integer partition = idx + 1
        String status = decodePartitionStatus(bitmap.substring(idx * 2, idx * 2 + 2))
        String before = state.partitionStatus["${partition}"]
        if (status == before) continue

        state.partitionStatus["${partition}"] = status
        // The exit delay doesn't say whether it is stay or away; say what was asked for, or that it isn't known.
        if (status == "exitDelay" || before == "exitDelay") publishArmingMode(partition, status)
        // A request belongs to one arm: once that arm has finished (or been cancelled) it must not colour the next.
        if (status != "exitDelay") consumeArmRequest(partition)
        if (partition == 1 && status != device.currentValue("alarmSystemStatus")) {
            sendEvent(name: "alarmSystemStatus", value: status)
            logInfo("Alarm status -> ${status}")
        } else if (partition != 1) {
            logInfo("Partition ${partition} status -> ${status}")
        }

        sendPartitionState(partition, status)
    }
}

private String decodePartitionStatus(String code) {
    if (!code || code.length() < 2) return "unknown"

    switch (code.substring(0, 2)) {
        case "00": return "unused"
        case "01": return "disarmed"
        case "02": return "readyBypassed"
        case "03": return "notReady"
        case "04": return "armedStay"
        case "05": return "armedAway"
        case "08": return "exitDelay"
        case "09": return "armedAwayZeroEntry"
        case "0C": return "entryDelay"
        case "11": return "alarm"
        // Other values are transitory states, which the spec says to treat as busy.
        default:   return "busy"
    }
}

private void handleCidEvent(List<String> parts) {
    if (parts.size() < 2) return

    String cid = cleanPayload(parts[1])
    // A Contact ID event is 10 digits. A damaged one must not be read as a different event (say, an arm by a
    // user whose number happened to survive), so anything else is dropped.
    if (!cid || cid.length() < 10 || !(cid.substring(0, 10) ==~ /\d{10}/)) {
        logDebug("Ignoring malformed CID event: ${redact(cid).take(40)}")
        return
    }

    String qualifier = cid.substring(0, 1)
    String eventCode = cid.substring(1, 4)
    Integer partition = safeParseInt(cid.substring(4, 6), 0)
    Integer zone = safeParseInt(cid.substring(6, 9), 0)
    boolean isRestore = (qualifier == "3")
    boolean isEvent = (qualifier == "1")

    String summary = "CID q=${qualifier} code=${eventCode} partition=${partition} zoneOrUser=${zone}"
    state.lastCidEvent = summary
    sendEvent(name: "lastCidEvent", value: summary.take(1024))
    logDebug(summary)

    // "Armed stay" (441, 442) tells us the mode of an arm that didn't come from one of our commands (a keypad).
    if (qualifier == "3" && eventCode in ["441", "442"] && partition >= 1 && partition <= 8) noteArmRequest(partition, "stay")

    // Describe it in plain words (who armed, which sensor alarmed, and so on) without affecting the zone fast path.
    try {
        publishCidEvent(qualifier, eventCode, partition, zone, cid)
    } catch (Exception e) {
        logWarn("Could not describe CID event ${eventCode}: ${e}")
    }

    if (zone <= 0 || !configuredZones().contains(zone)) return

    routeCidZoneEvent(eventCode, zone, isEvent, isRestore)
}

// Applies a Contact ID event that names a configured zone to that zone's sensor.
private void routeCidZoneEvent(String eventCode, Integer zone, boolean isEvent, boolean isRestore) {
    // Tamper, low battery and lost supervision are their own conditions, not the zone opening,
    // so they update an attribute on the zone instead.
    Map condition = ZONE_CONDITIONS[eventCode]
    if (condition && (isEvent || isRestore)) {
        routeZoneCondition(zone, condition, isEvent)
        return
    }

    // Only zone-type codes carry a zone number; for others (e.g. arm/disarm) it is a user ID.
    if (!FAST_PATH_ZONE_CODES.contains(eventCode)) return
    if (!(isEvent || isRestore)) return

    ensureZoneFor(zone, suggestTypeFromCid(eventCode))

    boolean active = isEvent && !isRestore

    CID_SEEN.put("${device.id}:${zone}".toString(), now())

    logInfo("CID fast path: zone ${zone} -> ${active ? 'active/open' : 'restore/closed'} (code ${eventCode})")
    emitZoneFastEvent(zone, active)
    routeZoneUpdate(zone, active)
}

// Contact ID codes that carry a zone number and mean the zone opened (or closed again, as a restoral).
@Field static final Set<String> FAST_PATH_ZONE_CODES = ["110", "113", "130", "131", "132", "134", "154", "162"] as Set

// Contact ID codes that describe a condition of the zone's sensor rather than the zone opening.
// code -> zone attribute, its value while the condition is present and when it clears, and how to say it.
@Field static final Map ZONE_CONDITIONS = [
    "137": [attr: "tamper", on: "detected", off: "clear", text: "tamper"],
    "144": [attr: "tamper", on: "detected", off: "clear", text: "tamper"],
    "383": [attr: "tamper", on: "detected", off: "clear", text: "tamper"],
    "384": [attr: "batteryStatus", on: "low", off: "ok", text: "low battery"],
    "381": [attr: "supervision", on: "lost", off: "ok", text: "supervision"]
]

private String suggestTypeFromCid(String eventCode) {
    switch (eventCode) {
        case "110":
            return "smoke"
        case "154":
            return "water"
        case "162":
            return "co"
        default:
            return null
    }
}

// ---------------------------------------------------------------------------------------------
// Contact ID semantics: turn "%03" events into readable text, history and per-partition attributes.
// Qualifier 1 is the event (or opening / disarm) and 3 is the restoral (or closing / arm). The last field
// is a zone for alarms and sensor troubles, and a user number for open/close and access events.
// ---------------------------------------------------------------------------------------------

// code -> [category, description]
@Field static final Map CID_EVENTS = [
    // Medical, fire, panic, burglary and environmental alarms
    "100": ["alarm", "Medical alarm"], "101": ["alarm", "Personal emergency"], "102": ["alarm", "Failure to report in"],
    "110": ["alarm", "Fire alarm"], "111": ["alarm", "Smoke alarm"], "112": ["alarm", "Combustion alarm"],
    "113": ["alarm", "Water flow alarm"], "114": ["alarm", "Heat alarm"], "115": ["alarm", "Pull station alarm"],
    "116": ["alarm", "Duct alarm"], "117": ["alarm", "Flame alarm"], "118": ["alarm", "Near alarm"],
    "120": ["alarm", "Panic alarm"], "121": ["alarm", "Duress"], "122": ["alarm", "Silent panic"], "123": ["alarm", "Audible panic"],
    "130": ["alarm", "Burglary alarm"], "131": ["alarm", "Perimeter alarm"], "132": ["alarm", "Interior alarm"],
    "133": ["alarm", "24 hour alarm"], "134": ["alarm", "Entry/exit alarm"], "135": ["alarm", "Day/night alarm"],
    "136": ["alarm", "Outdoor alarm"], "137": ["alarm", "Tamper"], "138": ["alarm", "Near alarm"],
    "139": ["alarm", "Intrusion verified"], "140": ["alarm", "General alarm"], "141": ["alarm", "Polling loop open"],
    "142": ["alarm", "Polling loop short"], "143": ["alarm", "Expansion module failure"], "144": ["alarm", "Sensor tamper"],
    "145": ["alarm", "Expansion module tamper"], "146": ["alarm", "Silent burglary"], "147": ["alarm", "Sensor supervision failure"],
    "150": ["alarm", "24 hour non-burglary alarm"], "151": ["alarm", "Gas detected"], "152": ["alarm", "Refrigeration alarm"],
    "153": ["alarm", "Loss of heat"], "154": ["alarm", "Water leakage"], "155": ["alarm", "Foil break"],
    "156": ["alarm", "Day trouble"], "157": ["alarm", "Low bottled gas level"], "158": ["alarm", "High temperature"],
    "159": ["alarm", "Low temperature"], "161": ["alarm", "Loss of air flow"], "162": ["alarm", "Carbon monoxide detected"],
    "163": ["alarm", "Tank level"],
    // Supervisory
    "200": ["supervisory", "Fire supervisory"], "201": ["supervisory", "Low water pressure"], "202": ["supervisory", "Low CO2"],
    "203": ["supervisory", "Gate valve sensor"], "204": ["supervisory", "Low water level"], "205": ["supervisory", "Pump activated"],
    "206": ["supervisory", "Pump failure"],
    // System and sensor troubles
    "300": ["trouble", "System trouble"], "301": ["trouble", "AC power loss"], "302": ["trouble", "Low system battery"],
    "303": ["trouble", "RAM checksum bad"], "304": ["trouble", "ROM checksum bad"], "305": ["trouble", "System reset"],
    "306": ["trouble", "Panel programming changed"], "307": ["trouble", "Self-test failure"], "308": ["trouble", "System shutdown"],
    "309": ["trouble", "Battery test failure"], "310": ["trouble", "Ground fault"], "311": ["trouble", "Battery missing or dead"],
    "312": ["trouble", "Power supply overcurrent"], "313": ["trouble", "Engineer reset"],
    "320": ["trouble", "Sounder or relay trouble"], "321": ["trouble", "Bell 1 trouble"], "322": ["trouble", "Bell 2 trouble"],
    "330": ["trouble", "System peripheral trouble"], "333": ["trouble", "Expansion module failure"],
    "350": ["trouble", "Communication trouble"], "351": ["trouble", "Telephone line 1 fault"], "352": ["trouble", "Telephone line 2 fault"],
    "353": ["trouble", "Radio transmitter fault"], "354": ["trouble", "Failure to communicate"], "355": ["trouble", "Loss of radio supervision"],
    "356": ["trouble", "Loss of central polling"],
    "370": ["trouble", "Protection loop trouble"], "373": ["trouble", "Fire loop trouble"],
    "380": ["trouble", "Sensor trouble"], "381": ["trouble", "Loss of supervision (RF)"], "383": ["trouble", "Sensor tamper trouble"],
    "384": ["trouble", "Sensor low battery"], "389": ["trouble", "Sensor self-test failure"], "393": ["trouble", "Maintenance alert"],
    // Open / close (arm and disarm), remote access and exceptions
    "400": ["arm", "Open/close"], "401": ["arm", "Open/close by user"], "402": ["arm", "Group open/close"],
    "403": ["arm", "Automatic open/close"], "407": ["arm", "Remote arm/disarm"], "408": ["arm", "Quick arm"],
    "409": ["arm", "Keyswitch open/close"], "441": ["arm", "Armed stay"], "442": ["arm", "Keyswitch armed stay"],
    "404": ["exception", "Late open/close"], "405": ["exception", "Deferred open/close"], "406": ["exception", "Cancel"],
    "450": ["exception", "Exception open/close"], "451": ["exception", "Early open/close"], "452": ["exception", "Late open/close"],
    "453": ["exception", "Failed to open"], "454": ["exception", "Failed to close"], "455": ["exception", "Auto-arm failed"],
    "456": ["exception", "Partial arm"], "457": ["exception", "Exit error"], "458": ["exception", "User on premises"],
    "459": ["exception", "Recent close"], "463": ["exception", "Re-arm after alarm"], "464": ["exception", "Auto-arm time extended"],
    "465": ["exception", "Panic alarm reset"], "466": ["exception", "Service on/off premises"],
    "411": ["access", "Callback request made"], "412": ["access", "Successful download/access"], "413": ["access", "Unsuccessful access"],
    "414": ["access", "System shutdown"], "415": ["access", "Dialer shutdown"], "416": ["access", "Successful upload"],
    "421": ["access", "Access denied"], "422": ["access", "Access report by user"],
    "461": ["access", "Wrong code entered"], "462": ["access", "Valid code entered"],
    // Bypass and disable
    "520": ["bypass", "Sounder or relay disabled"], "551": ["bypass", "Dialer disabled"], "552": ["bypass", "Radio transmitter disabled"],
    "570": ["bypass", "Zone bypassed"], "571": ["bypass", "Fire zone bypassed"], "572": ["bypass", "24 hour zone bypassed"],
    "573": ["bypass", "Burglary zone bypassed"], "574": ["bypass", "Group bypass"],
    // Tests
    "601": ["test", "Manual test"], "602": ["test", "Periodic test"], "603": ["test", "Periodic RF transmission"],
    "604": ["test", "Fire test"], "605": ["test", "Status report to follow"], "607": ["test", "Walk test mode"],
    "608": ["test", "Periodic test (trouble present)"], "611": ["test", "Point tested OK"], "612": ["test", "Point not tested"],
    "613": ["test", "Intrusion zone walk tested"], "614": ["test", "Fire zone walk tested"], "615": ["test", "Panic zone walk tested"],
    "616": ["test", "Service request"]
]

// Codes where qualifier 3 (closing) means armed and 1 (opening) means disarmed, with a hint of how.
@Field static final Map CID_ARM_SUFFIX = [
    "400": "", "401": "", "402": " (group)", "403": " (automatic)", "407": " (remote)", "408": " (quick arm)",
    "409": " (keyswitch)", "441": " (stay)", "442": " (keyswitch, stay)"
]

private String cidField(String code, String category) {
    Integer n = safeParseInt(code, 0)
    switch (category) {
        case "alarm":
        case "bypass":
        case "supervisory":
            return "zone"
        case "trouble":
            return (n >= 300 && n < 360) ? "none" : "zone"
        case "access":
        case "exception":
            return "user"
        case "test":
            return (n >= 611 && n <= 615) ? "zone" : "none"
        default:
            return "none"
    }
}

private String userLabel(Integer user) {
    if (user == null || user <= 0) return "the system"
    String name = null
    try {
        name = parent?.userName(user)
    } catch (ignored) {
    }
    return name ?: "user ${user}"
}

private String zoneLabel(Integer zone) {
    String name = null
    try {
        name = zoneName(zone)
    } catch (ignored) {
    }
    return name ? "${name} (zone ${zone})" : "zone ${zone}"
}

// Returns [category, text, who]. Arm and disarm are their own categories so rules can react to them.
private Map describeCid(String qualifier, String code, Integer zoneOrUser) {
    boolean restore = (qualifier == "3")

    if (CID_ARM_SUFFIX.containsKey(code)) {
        boolean armed = restore
        String suffix = (armed || !(code in ["441", "442"])) ? CID_ARM_SUFFIX[code] : ""
        String who = userLabel(zoneOrUser)
        return [category: armed ? "arm" : "disarm", text: "${armed ? 'Armed' : 'Disarmed'}${suffix} by ${who}".toString(), who: who, zone: null]
    }

    List info = CID_EVENTS[code]
    String category = info ? info[0] : "other"
    String text = info ? info[1] : "Event ${code}"
    String who = null

    // Only alarm-like events have a meaningful "restored"; for the rest the qualifier just marks the other edge.
    if (restore && category in ["alarm", "trouble", "bypass", "supervisory"]) text += " restored"

    String field = cidField(code, category)
    Integer zone = null
    if (field == "zone" && zoneOrUser > 0) {
        zone = zoneOrUser
        text += ": " + zoneLabel(zoneOrUser)
    } else if (field == "user" && zoneOrUser > 0) {
        who = userLabel(zoneOrUser)
        text += " by ${who}"
    }

    return [category: category, text: text.toString(), who: who, zone: zone]
}

// Qualifier 6 means "previously reported" (a status report replaying an old event), so the open/close
// direction isn't known and it must not look like a new arm, disarm or alarm.
private Map describePrevious(String code, Integer zoneOrUser) {
    if (CID_ARM_SUFFIX.containsKey(code)) {
        String who = userLabel(zoneOrUser)
        return [category: "report", text: "Previously reported: open/close by ${who}".toString(), who: who, zone: null]
    }
    Map d = describeCid("1", code, zoneOrUser)
    return d + [category: "report", text: "Previously reported: ${d.text}".toString()]
}

// Publishes a Contact ID event for people and for rules: the sentence, every field as its own attribute, and a
// location event. Qualifier 1 is the event, 3 its restoral and 6 a replay of an older event (shown, but never
// recorded, notified or counted as an arm, disarm or alarm).
private void publishCidEvent(String qualifier, String code, Integer partition, Integer zoneOrUser, String raw = "") {
    if (!(qualifier in ["1", "3", "6"])) return
    boolean previous = (qualifier == "6")

    Map d = previous ? describePrevious(code, zoneOrUser) : describeCid(qualifier, code, zoneOrUser)

    // Periodic and manual test reports are routine noise unless asked for.
    if (d.category == "test" && settings.recordTestEvents != true) {
        logDebug("Not recording test report ${code}")
        return
    }

    String at = new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)

    publishCidAttributes(qualifier, code, partition, d, raw)
    if (!previous) recordEventHistory([at: at, partition: partition, code: code, type: d.category, text: d.text])

    if (!previous) {
        try {
            parent?.notifyEvent([category: d.category, text: d.text, partition: partition, restore: qualifier == "3"])
        } catch (e) {
            logDebug("Failed to send notification for CID ${code}: ${e}")
        }
    }

    try {
        sendLocationEvent(
            name: "unoEvent",
            value: "${d.category}:${code}".toString(),
            descriptionText: d.text,
            isStateChange: true,
            data: JsonOutput.toJson([partition: partition, code: code, qualifier: qualifier, category: d.category,
                                      zoneOrUser: zoneOrUser, zone: d.zone, user: d.who, raw: raw, text: d.text])
        )
    } catch (e) {
        logDebug("Failed to emit unoEvent for CID ${code}: ${e}")
    }

    if (previous || partition < 1 || partition > 8) return

    sendPartitionEvents(partition, partitionEventsFor(qualifier, code, zoneOrUser, d, at))
}

// Each field is its own attribute so a rule can test the code, zone or user directly. They are set first and
// lastEvent last, so a rule triggered by lastEvent always sees the details of that same event.
private void publishCidAttributes(String qualifier, String code, Integer partition, Map d, String raw) {
    sendEvent(name: "lastEventRaw", value: raw.take(64), isStateChange: true)
    sendEvent(name: "lastEventCode", value: code, isStateChange: true)
    sendEvent(name: "lastEventQualifier", value: qualifier, isStateChange: true)
    sendEvent(name: "lastEventPartition", value: partition, isStateChange: true)
    sendEvent(name: "lastEventZone", value: (d.zone ?: 0), isStateChange: true)
    sendEvent(name: "lastEventUser", value: (d.who ?: "none"), isStateChange: true)
    sendEvent(name: "lastEventType", value: d.category, isStateChange: true)
    sendEvent(name: "lastEvent", value: d.text.take(255), isStateChange: true)
}

// The attributes of the partition device that an event updates: the last event, and who armed or disarmed
// or which alarm went off.
private List partitionEventsFor(String qualifier, String code, Integer zoneOrUser, Map d, String at) {
    List events = [
        [name: "lastEvent", value: d.text.take(255), isStateChange: true],
        [name: "lastEventType", value: d.category, isStateChange: true]
    ]
    if (d.category == "arm") {
        events << [name: "lastArmedBy", value: d.who, isStateChange: true] << [name: "lastArmedAt", value: at, isStateChange: true]
    } else if (d.category == "disarm") {
        events << [name: "lastDisarmedBy", value: d.who, isStateChange: true] << [name: "lastDisarmedAt", value: at, isStateChange: true]
    } else if (d.category == "alarm" && qualifier == "1") {
        // Which alarm and where, ready for rules and dashboards (the restoral is not an alarm).
        String zoneText = (cidField(code, "alarm") == "zone" && zoneOrUser > 0) ? zoneLabel(zoneOrUser) : "none"
        events << [name: "lastAlarmType", value: (CID_EVENTS[code] ? CID_EVENTS[code][1] : "Event ${code}"), isStateChange: true]
        events << [name: "lastAlarmZone", value: zoneText, isStateChange: true] << [name: "lastAlarmAt", value: at, isStateChange: true]
    }
    return events
}

// Keeps the last 25 events in memory (the hub restarting empties it) and shows the newest 10 as JSON in
// the eventHistory attribute, which survives a restart and seeds the list again.
private List historyList() {
    String id = "${device.id}".toString()
    List history = (List) HISTORY.get(id)
    if (history == null) {
        history = []
        try {
            def parsed = new JsonSlurper().parseText((device.currentValue("eventHistory") ?: "[]").toString())
            if (parsed instanceof List) history.addAll(parsed)
        } catch (ignored) {
        }
        HISTORY.put(id, history)
    }
    return history
}

private void recordEventHistory(Map entry) {
    List history = historyList()
    history.add(0, entry)
    while (history.size() > 25) history.remove(history.size() - 1)
    publishEventHistory()
}

private void publishEventHistory() {
    List recent = historyList().take(10)
    sendEvent(name: "eventHistory", value: recent ? JsonOutput.toJson(recent) : "[]")
}

def clearEventHistory() {
    historyList().clear()
    publishEventHistory()
    logInfo("Cleared event history")
}

// %04 is a zone bitmap (same layout as %01) where a set bit means the zone is bypassed.
private void handleBypassBitmap(List<String> parts) {
    if (parts.size() < 2) return

    String bitmap = cleanPayload(parts[1]).toUpperCase()
    if (!(bitmap ==~ /[0-9A-F]+/)) {
        logWarn("Ignoring malformed bypass bitmap: ${bitmap.take(64)}")
        return
    }

    String previous = state.lastBypassBitmap
    if (previous == bitmap) return
    state.lastBypassBitmap = bitmap

    for (Integer zone : configuredZones()) {
        if (previous != null && byteAt(previous, zone) == byteAt(bitmap, zone)) continue

        boolean bypassed = zoneBit(bitmap, zone)
        if (previous == null || bypassed != zoneBit(previous, zone)) {
            if (previous != null) logInfo("Zone ${zone} ${bypassed ? 'bypassed' : 'no longer bypassed'}")
            routeBypassUpdate(zone, bypassed)
        }
    }

    publishZoneSummary()
}

// Conditions only update existing zone devices; they never create new ones.
private void routeZoneCondition(Integer zone, Map condition, boolean present) {
    def child = childOrLegacy("uno-zone-${zone}")
    if (!child) return

    String value = present ? condition.on : condition.off
    logInfo("Zone ${zone} ${condition.text} ${present ? 'detected' : 'cleared'}")
    try {
        child.sendEvent(
            name: condition.attr,
            value: value,
            descriptionText: "Zone ${zone} ${condition.text} ${present ? 'detected' : 'cleared'}",
            isStateChange: true
        )
    } catch (e) {
        logDebug("Failed to update ${condition.attr} for zone ${zone}: ${e}")
    }
}

// Bypass changes only update existing zone devices; they never create new ones.
private void routeBypassUpdate(Integer zone, boolean bypassed) {
    def child = childOrLegacy("uno-zone-${zone}")
    if (!child) return

    try {
        child.sendEvent(
            name: "bypassed",
            value: bypassed ? "true" : "false",
            descriptionText: "Zone ${zone} ${bypassed ? 'bypassed' : 'not bypassed'}"
        )
    } catch (e) {
        logDebug("Failed to update bypass state for zone ${zone}: ${e}")
    }
}

private void handlePanelInfo(List<String> parts) {
    if (parts.size() < 6) return

    // %05,MAC,TYPE,VERSION,PARTITION,TIME - the version string may itself contain commas,
    // so anchor on the fixed fields at both ends.
    String mac = cleanPayload(parts[1])
    String model = cleanPayload(parts[2])
    String panelTime = cleanPayload(parts[parts.size() - 1])
    String defaultPartition = cleanPayload(parts[parts.size() - 2])
    String fw = parts.subList(3, parts.size() - 2).collect { cleanPayload(it) }.join(",").trim()

    if (mac) sendEvent(name: "panelMac", value: mac)
    if (model) sendEvent(name: "panelModel", value: model)
    if (fw) sendEvent(name: "firmwareVersion", value: fw)
    if (panelTime && settings.diagnosticEvents == true) sendEvent(name: "panelTime", value: panelTime)
    checkFirmware(model, fw)

    logDebug("Panel info: model=${model}, fw=${fw}, partition=${defaultPartition}, mac=${mac}, time=${panelTime}")
}

// The oldest firmware the TPI document (v1.08) lists for each product. Older firmware may lack commands or frames.
@Field static final Map MIN_FIRMWARE = [UNO: [1, 0, 154], SOLO: [1, 0, 146], EVL4MAX: [1, 0, 220], DUO: [1, 0, 104]]

// Reads the first x.y.z in a version string (so "1.0.154", "01.00.154A" and "v1.0.154-beta" all work).
private List<Integer> parseFirmware(String text) {
    def m = (text ?: "") =~ /(\d+)\.(\d+)\.(\d+)/
    if (!m.find()) return null
    try {
        return [Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))]
    } catch (ignored) {
        return null
    }
}

// Returns a warning when the panel is a known product running firmware older than the document's minimum, otherwise
// "none". A model or version it can't recognise gets no warning: guessing would only cry wolf.
private String firmwareWarning(String model, String fw) {
    List<Integer> have = parseFirmware(fw)
    String upper = (model ?: "").toUpperCase()
    String product = MIN_FIRMWARE.keySet().find { String p -> upper.contains(p) }
    if (!have || !product) return "none"

    List<Integer> need = MIN_FIRMWARE[product] as List<Integer>
    for (int i = 0; i < 3; i++) {
        if (have[i] > need[i]) return "none"
        if (have[i] < need[i]) return "Firmware ${have.join('.')} is older than ${need.join('.')}, the minimum the TPI document lists for ${product}. Some commands or frames may not work".toString()
    }
    return "none"
}

private void checkFirmware(String model, String fw) {
    String warning = firmwareWarning(model ?: device.currentValue("panelModel"), fw ?: device.currentValue("firmwareVersion"))
    if (device.currentValue("firmwareWarning") == warning) return
    sendEvent(name: "firmwareWarning", value: warning)
    if (warning != "none") logWarn(warning)
}

// Spec section 3.5: BIT 1..8 map to the low..high bit of each partition's trouble byte.
private List<Map> troubleDefinitions() {
    return [
        [attr: "serviceRequired",     text: "Service required"],
        [attr: "acFailure",           text: "AC failure"],
        [attr: "lowBattery",          text: "Wireless device low battery"],
        [attr: "serverOffline",       text: "Server offline"],
        [attr: "zoneTrouble",         text: "Zone trouble (fault/tamper)"],
        [attr: "batteryOvercurrent",  text: "System battery overcurrent"],
        [attr: "sirenFault",          text: "Bell/siren absent or faulted"],
        [attr: "supervisoryFault",    text: "Wireless device supervisory fault"]
    ]
}

private Map troubleAttributes(Integer bits) {
    Map attrs = [:]
    List<String> active = []

    troubleDefinitions().eachWithIndex { Map t, int idx ->
        boolean on = ((bits >> idx) & 0x01) == 1
        attrs[t.attr as String] = on ? "active" : "clear"
        if (on) active << (t.text as String)
    }

    attrs.acPower = attrs.acFailure == "active" ? "failed" : "present"
    attrs.troubleDetail = active ? active.join(", ") : "none"
    attrs.troubleState = active ? "trouble" : "clear"
    return attrs
}

// %06 carries one trouble byte per partition. Partition 1 is mirrored on this device; every
// partition's device gets its own copy.
private void handleTroubleBitmap(List<String> parts) {
    if (parts.size() < 2) return

    String bitmap = cleanPayload(parts[1]).toUpperCase()
    if (!(bitmap ==~ /[0-9A-F]+/)) {
        logWarn("Ignoring malformed trouble frame: ${bitmap.take(64)}")
        return
    }
    state.lastTroubleBitmap = bitmap
    state.troubleBytes = state.troubleBytes ?: [:]
    boolean anyChange = false

    for (int idx = 0; idx < 8 && (idx * 2 + 2) <= bitmap.length(); idx++) {
        Integer partition = idx + 1
        String byteHex = bitmap.substring(idx * 2, idx * 2 + 2)
        if (byteHex == state.troubleBytes["${partition}"]) continue
        state.troubleBytes["${partition}"] = byteHex
        anyChange = true

        Map attrs = troubleAttributes(safeParseHex(byteHex, 0))

        if (partition == 1) {
            String previousTrouble = device.currentValue("troubleState")
            attrs.each { String name, String value ->
                if (value != device.currentValue(name)) {
                    sendEvent(name: name, value: value)
                }
            }
            if (attrs.troubleState != previousTrouble) {
                logInfo("Trouble state -> ${attrs.troubleState}: ${attrs.troubleDetail}")
            }
        } else if (attrs.troubleState == "trouble") {
            logInfo("Partition ${partition} trouble: ${attrs.troubleDetail}")
        }

        sendPartitionEvents(partition, attrs.collect { String name, String value -> [name: name, value: value] })
    }

    if (anyChange) {
        String summary = troubleSummary()
        if (device.currentValue("troubleSummary") != summary) {
            sendEvent(name: "troubleSummary", value: summary)
            try {
                forwardPartitionSummary([troubleSummary: summary])
                parent?.notifyPanelSummary([troubleSummary: summary])
            } catch (e) {
                logDebug("Failed to forward the trouble summary: ${e}")
            }
        }
    }
}

// Every partition's active troubles in one line: "AC failure" when only partition 1 has any, otherwise "P1: ...; P3: ...".
private String troubleSummary() {
    Map bytes = (state.troubleBytes instanceof Map) ? state.troubleBytes : [:]
    List<String> perPartition = []
    (1..8).each { Integer p ->
        String detail = troubleAttributes(safeParseHex((bytes["${p}"] ?: "00").toString(), 0)).troubleDetail
        if (detail != "none") perPartition << "${p}|${detail}".toString()
    }
    if (!perPartition) return "none"
    if (perPartition.size() == 1 && perPartition[0].startsWith("1|")) return perPartition[0].substring(2)
    return perPartition.collect { String s -> "P${s.substring(0, 1)}: ${s.substring(2)}".toString() }.join("; ")
}

private Integer safeParseHex(String s, Integer fallback = 0) {
    try {
        return Integer.parseInt(s, 16)
    } catch (ignored) {
        return fallback
    }
}

// Temperatures arrive as one byte in Envisacor format, C = byte / 2 - 40, where 00 means unknown or not applicable.
private BigDecimal envisacorToCelsius(String byteHex) {
    return (safeParseHex(byteHex, 0) / 2.0G) - 40.0G
}

// Converted to the hub's temperature scale, with the unit Hubitat expects.
private Map temperatureEvent(String name, BigDecimal celsius) {
    return [name: name, value: convertTemperatureIfNeeded(celsius, "C", 1), unit: "°${location.temperatureScale ?: 'C'}".toString()]
}

// %07 carries one temperature byte per zone (up to 128), for RF sensors that report it. Only zones whose
// reading changed are forwarded, and only to existing zone devices.
private void handleZoneTemperatures(List<String> parts) {
    if (parts.size() < 2) return

    String payload = cleanPayload(parts[1]).toUpperCase()
    if (!(payload ==~ /[0-9A-F]+/)) {
        logWarn("Ignoring malformed zone temperature frame: ${payload.take(64)}")
        return
    }

    for (Integer zone : configuredZones()) {
        int idx = (zone - 1) * 2
        if (idx + 2 > payload.length()) break

        String byteHex = payload.substring(idx, idx + 2)
        String seenKey = "${device.id}:z${zone}".toString()
        if (byteHex == "00" || byteHex == TEMP_SEEN.get(seenKey)) continue
        TEMP_SEEN.put(seenKey, byteHex)

        def child = childOrLegacy("uno-zone-${zone}")
        if (!child) continue
        try {
            child.sendEvent(temperatureEvent("temperature", envisacorToCelsius(byteHex)))
        } catch (e) {
            logDebug("Failed to update temperature for zone ${zone}: ${e}")
        }
    }
}

// %08 carries the inside temperatures of partitions 1-8, then the outside temperatures of partitions 1-8.
private void handlePartitionTemperatures(List<String> parts) {
    if (parts.size() < 2) return

    String payload = cleanPayload(parts[1]).toUpperCase()
    if (!(payload ==~ /[0-9A-F]+/)) {
        logWarn("Ignoring malformed partition temperature frame: ${payload.take(64)}")
        return
    }

    for (int idx = 0; idx < 8 && (idx * 2 + 2) <= payload.length(); idx++) {
        Integer partition = idx + 1
        List events = []

        [[kind: "inside", attr: "temperature", offset: idx * 2], [kind: "outside", attr: "outsideTemperature", offset: (8 + idx) * 2]].each { Map t ->
            if (t.offset + 2 > payload.length()) return
            String byteHex = payload.substring(t.offset as int, (t.offset as int) + 2)
            String seenKey = "${device.id}:p${partition}-${t.kind}".toString()
            if (byteHex == "00" || byteHex == TEMP_SEEN.get(seenKey)) return
            TEMP_SEEN.put(seenKey, byteHex)
            events << temperatureEvent(t.attr as String, envisacorToCelsius(byteHex))
        }

        if (events) sendPartitionEvents(partition, events)
    }
}

// %09: a one-time keypad sound. B = that many beeps, Z = a continuous tone for that many seconds (the value is hex).
// Sounds change constantly, so they are only published when diagnostic events are on.
private void handleKeypadSound(List<String> parts) {
    if (parts.size() < 4) return

    Integer partition = safeParseInt(cleanPayload(parts[1]), 0)
    String kind = cleanPayload(parts[2]).toUpperCase()
    Integer value = safeParseHex(cleanPayload(parts[3]), -1)
    if (partition < 1 || partition > 8 || !(kind in ["B", "Z"]) || value < 0) {
        logDebug("Ignoring malformed keypad sound frame: ${parts}")
        return
    }

    publishKeypadSound(partition, kind == "B" ? "${value} beep${value == 1 ? '' : 's'}" : "tone for ${value}s")
}

// %0A: a persistent keypad sound: <partition>,<idle on 1/off 0>,<beeps>,<interval seconds>. All zero cancels it.
private void handlePersistentSound(List<String> parts) {
    if (parts.size() < 5) return

    Integer partition = safeParseInt(cleanPayload(parts[1]), 0)
    String idle = cleanPayload(parts[2])
    Integer beeps = safeParseHex(cleanPayload(parts[3]), -1)
    Integer interval = safeParseHex(cleanPayload(parts[4]), -1)
    if (partition < 1 || partition > 8 || !(idle in ["0", "1"]) || beeps < 0 || interval < 0) {
        logDebug("Ignoring malformed persistent sound frame: ${parts}")
        return
    }

    String text
    if (idle == "0" && beeps == 0 && interval == 0) {
        text = "stopped"
    } else if (idle == "1" && beeps == 0 && interval == 0) {
        text = "continuous tone"
    } else {
        text = "${beeps} beep${beeps == 1 ? '' : 's'} every ${interval}s"
    }
    publishKeypadSound(partition, text)
}

private void publishKeypadSound(Integer partition, String text) {
    logDebug("Keypad sound, partition ${partition}: ${text}")
    if (settings.diagnosticEvents != true) return

    sendEvent(name: "lastKeypadSound", value: text, isStateChange: true)
    sendPartitionEvents(partition, [[name: "lastKeypadSound", value: text, isStateChange: true]])
}

// %FF: 128 little-endian 16 bit zone timers (512 hex characters). A timer counts down from FFFF (open) in
// 5 second ticks, and 0000 means the zone closed too long ago to remember.
private void handleZoneTimers(List<String> parts) {
    if (parts.size() < 2) return

    String payload = cleanPayload(parts[1]).toUpperCase()
    if (!(payload ==~ /[0-9A-F]+/)) {
        logWarn("Ignoring malformed zone timer frame: ${payload.take(64)}")
        return
    }

    Map timers = [:]
    for (Integer zone : configuredZones()) {
        int idx = (zone - 1) * 4
        if (idx + 4 > payload.length()) break

        // Little endian: "FEFF" is 0xFFFE.
        int word = Integer.parseInt(payload.substring(idx + 2, idx + 4) + payload.substring(idx, idx + 2), 16)
        if (word == 0xFFFF) {
            timers["${zone}"] = "open"
        } else if (word > 0) {
            timers["${zone}"] = (0xFFFF - word) * 5
        }
    }

    // Seconds since each zone last closed (or "open"); zones not seen for about 3.8 days are left out.
    sendEvent(name: "zoneTimers", value: timers ? JsonOutput.toJson(timers) : "{}")
}

private void handleChime(List<String> parts) {
    // %10 carries one chime-type digit (0-7) per partition; 0 means no chime for that partition.
    String payload = parts.size() > 1 ? cleanPayload(parts[1]) : ""
    logDebug("Chime frame received: ${payload}")

    for (int idx = 0; idx < 8 && idx < payload.length(); idx++) {
        String type = payload.substring(idx, idx + 1)
        if (!(type ==~ /[1-7]/)) continue

        Integer partition = idx + 1
        String value = "type ${type}"
        if (partition == 1) sendEvent(name: "lastChime", value: value, isStateChange: true)
        sendPartitionEvents(partition, [[name: "lastChime", value: value, isStateChange: true]])
    }
}

private void emitZoneFastEvent(Integer zoneNum, boolean active) {
    try {
        String value = zoneNum + ":" + (active ? "open" : "closed")
        sendLocationEvent(
            name: "unoZoneEvent",
            value: value,
            descriptionText: "UNO zone ${zoneNum} ${active ? 'opened' : 'closed'}",
            isStateChange: true
        )
    } catch (e) {
        logDebug("Failed to emit unoZoneEvent for zone ${zoneNum}: ${e}")
    }
}

private void routeZoneUpdate(Integer zoneNum, boolean active) {
    String dni = "uno-zone-${zoneNum}"

    def child = childOrLegacy(dni)

    if (!child) {
        ensureZoneFor(zoneNum, null)
        child = childOrLegacy(dni)
    }

    if (!child) {
        String message = "Zone ${zoneNum} device not found: ${dni}"
        if (creationBlocked(dni)) logDebug(message) else logWarn(message)
        return
    }

    updateZoneChild(child, zoneNum, active)
}

// The zone driver knows its own capability, so one call replaces a capability check and an event per type.
private void updateZoneChild(child, Integer zoneNum, boolean active) {
    try {
        child.applyZoneState(active)
    } catch (e) {
        logWarn("Zone ${zoneNum} did not accept the update; update all UNO zone drivers to the same version: ${e}")
    }
}

// ---------------------------------------------------------------------------------------------
// Child devices. The partitions and the zones are children of this device, so the Devices page lists them under it.
// The app owns the settings (names, hints, the UNO's own names, the prefix) and says what each device should be called
// and what type each zone is. This device creates and names them, and sends them their updates.
// ---------------------------------------------------------------------------------------------

private List children() {
    try {
        return getChildDevices() ?: []
    } catch (ignored) {
        return []
    }
}

private List partitionChildren() {
    return children().findAll { dev -> (dev.deviceNetworkId ?: "").startsWith("uno-partition-") }
}

private Integer zoneNumberOf(String dni) {
    if (!dni || !dni.startsWith("uno-zone-")) return null
    return safeParseInt(dni.substring("uno-zone-".length()), 0) ?: null
}

// What the app says the devices should be: the prefix, the partitions (number -> name), whether to pre-create the
// configured zones, and the default zone type.
private Map devicePlan() {
    try {
        return parent?.devicePlan() as Map
    } catch (e) {
        logWarn("Could not get the device plan from the app: ${e}")
        return null
    }
}

// What the app knows about one zone: the name chosen for it, the name the UNO has for it, and the type chosen. Null when
// the app can't be asked, which must never be mistaken for "nothing is configured".
private Map zoneSpec(Integer zone) {
    try {
        return parent?.zoneSpec(zone) as Map
    } catch (ignored) {
        return null
    }
}

// Creates the devices that are missing and brings the others up to date (names, prefix, zone types). The app calls
// this after it has saved, and the button on this device does the same.
def syncDevices() {
    // An explicit sync tries again for anything that failed before.
    String failedPrefix = "${device.id}:".toString()
    CREATE_FAILED.keySet().findAll { String key -> key.startsWith(failedPrefix) }.each { String key -> CREATE_FAILED.remove(key) }

    Map plan = devicePlan()
    if (plan == null) return

    String prefix = (plan.prefix ?: "").toString()
    boolean changed = false
    Map partitions = (plan.partitions ?: [:]) as Map
    partitions.each { def number, def base ->
        changed = ensurePartitionDevice(number as Integer, base.toString(), prefix) || changed
    }
    // Remembered because a partition taken out of the set keeps its device, which must not count as a second one.
    state.plannedPartitions = partitions.keySet().collect { it as Integer }.sort()

    Set<Integer> zones = new TreeSet<Integer>()
    if (plan.autoCreate != false) zones.addAll(configuredZones())
    children().each { dev ->
        Integer existing = zoneNumberOf(dev.deviceNetworkId)
        if (existing) zones.add(existing)
    }
    zones.each { Integer zone -> changed = ensureZoneDevice(zone, null, plan, prefix) || changed }

    state.appliedPrefix = prefix
    copySummaryToSinglePartition()
    // New devices know nothing yet, so have the panel announce everything again.
    if (changed) runIn(1, "resyncStates")
}

private boolean ensurePartitionDevice(Integer partition, String base, String prefix) {
    String dni = "uno-partition-${partition}"
    String label = prefixedLabel(base, prefix)
    def existing = getChildDevice(dni)

    if (existing) {
        try {
            if (existing.label != label) {
                existing.setLabel(label)
                logInfo("Updated partition ${partition} label to ${label}")
            }
        } catch (e) {
            logWarn("Unable to update the label of partition ${partition}: ${e}")
        }
        return false
    }

    try {
        applyHealth(addChildDevice("uno", "UNO Partition", dni, [name: base, label: label, isComponent: true]))
        logInfo("Created partition device: ${label}")
        return true
    } catch (e) {
        noteCreateFailure(dni, "partition ${partition}", "UNO Partition", e)
        return false
    }
}

// A device that can't be made is not tried again for five minutes (a zone event would otherwise try on every change),
// until Sync devices is pressed.
private boolean creationBlocked(String dni) {
    Long at = (Long) CREATE_FAILED.get("${device.id}:${dni}".toString())
    return at != null && (now() - at) < 300000L
}

private void noteCreateFailure(String dni, String what, String driver, def e) {
    CREATE_FAILED.put("${device.id}:${dni}".toString(), now())
    if (e.toString().contains("DuplicateDNI") || e.toString().contains("already exists")) {
        logWarn("Could not create ${what}: a device with the id ${dni} already exists, most likely the old copy the app made before version 1.4.0, " +
                "which Hubitat would not delete (something still uses it). Updates keep going to the old one for now. Stop using it in rules and dashboards, " +
                "press Done in the app (or delete it on the Devices page), then press Sync devices here. Hubitat said: ${e}")
    } else {
        logWarn("Could not create ${what} (is the '${driver}' driver installed?): ${e}")
    }
}

// While the old copy of a device (made by the app before 1.4.0) can't be removed, its id is taken and the new device
// can't be made, so updates keep going to the old one and anything that uses it keeps working.
private def childOrLegacy(String dni) {
    def own = getChildDevice(dni)
    if (own || !creationBlocked(dni)) return own
    try {
        return parent?.getChildDevice(dni)
    } catch (ignored) {
        return null
    }
}

// A zone event can arrive before the zone has a device, and can say what kind of sensor it is (smoke, water, CO).
private void ensureZoneFor(Integer zone, String suggestedType) {
    if (creationBlocked("uno-zone-${zone}")) return
    if (!suggestedType && getChildDevice("uno-zone-${zone}")) return
    ensureZoneDevice(zone, suggestedType, devicePlan() ?: [:], (state.appliedPrefix ?: "").toString())
}

// Creates a zone's device, or renames it and changes its driver to match what the app says. True if a device was made.
private boolean ensureZoneDevice(Integer zoneNum, String suggestedType, Map plan, String prefix) {
    if (zoneNum == null || zoneNum <= 0) return false

    Map spec = zoneSpec(zoneNum)
    def existing = getChildDevice("uno-zone-${zoneNum}")
    // Without the app's answer a device that exists is left exactly as it is (a guess could change its type or name);
    // a zone that has no device yet is still made, so its updates aren't lost.
    if (spec == null && existing) return false
    spec = spec ?: [:]

    String chosenLabel = spec.label
    String hintedType = spec.type
    String nameForInference = chosenLabel ?: spec.panel
    String defaultType = (plan.defaultZoneType ?: "contact").toString()

    String label = prefixedLabel(zoneBaseLabel(zoneNum, chosenLabel, spec.panel as String, spec.legacy as String, existing, prefix), prefix)

    if (!existing) {
        String driver = driverNameForType(canonicalType(hintedType ?: suggestedType ?: inferTypeFromName(nameForInference) ?: defaultType))
        boolean created = addZoneDevice(zoneNum, driver, label)
        if (created) logDebug("Created zone ${zoneNum}: ${label} (${driver})")
        return created
    }

    String currentDriver = deviceTypeName(existing)
    String desired = desiredDriverForExisting(currentDriver, hintedType, suggestedType, nameForInference, defaultType)
    if (currentDriver && currentDriver != desired) return recreateZone(zoneNum, desired, label)

    relabelZone(existing, zoneNum, label)
    return false
}

private boolean addZoneDevice(Integer zoneNum, String driver, String label) {
    try {
        applyHealth(addChildDevice("uno", driver, "uno-zone-${zoneNum}", [name: "UNO Zone ${zoneNum}", label: label, isComponent: true]))
        return true
    } catch (e) {
        noteCreateFailure("uno-zone-${zoneNum}", "zone ${zoneNum}", driver, e)
        return false
    }
}

private boolean recreateZone(Integer zoneNum, String driver, String label) {
    try {
        deleteChildDevice("uno-zone-${zoneNum}")
    } catch (e) {
        logWarn("Could not recreate zone ${zoneNum}; the device is in use: ${e}")
        return false
    }

    boolean created = addZoneDevice(zoneNum, driver, label)
    if (created) logInfo("Recreated zone ${zoneNum}: ${label} (${driver})")
    return created
}

private void relabelZone(def zoneDevice, Integer zoneNum, String label) {
    try {
        if (zoneDevice.label != label) {
            zoneDevice.setLabel(label)
            logDebug("Updated zone ${zoneNum} label to ${label}")
        }
    } catch (e) {
        logWarn("Unable to relabel zone ${zoneNum}: ${e}")
    }
}

// A device made after the connection came up still needs to know whether the panel is reachable.
private void applyHealth(def dev) {
    try {
        dev?.sendEvent(name: "healthStatus", value: state.health ?: "offline")
    } catch (ignored) {
    }
}

private String deviceTypeName(def dev) {
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

// ---- Names and prefix ----

// The label a device gets: the prefix, a space, then its own name. Hubitat doesn't document a limit for a device
// label; its app labels stop at 255 characters, so that is used here.
private String prefixedLabel(String base, String prefix) {
    String label = prefix ? "${prefix} ${base}".toString() : base
    if (label.length() > 255) {
        logWarn("Device name cut to 255 characters: ${label.take(60)}...")
        return label.take(255)
    }
    return label
}

// A label with the prefix removed that this device put on it: the one in force before this sync, or the current one
// (a device made earlier in the same sync already carries it). Putting the prefix back gives the same label.
private String unprefixedLabel(String label, String prefix = "") {
    if (!label) return label
    for (String candidate : [(state.appliedPrefix ?: "").toString(), prefix ?: ""]) {
        if (candidate && label.startsWith(candidate + " ")) return label.substring(candidate.length() + 1)
    }
    return label
}

// The name for a zone, without the prefix. A name chosen in the app always applies. The UNO's name replaces the label
// only while that is still the default or what was set last time, so a name changed on the Devices page is kept. A zone
// with no device yet takes the label its old device had (from before the devices moved under this one), if there was one.
private String zoneBaseLabel(Integer zoneNum, String chosen, String fromPanel, String legacy, def existing, String prefix) {
    String key = "${zoneNum}".toString()
    Map applied = (state.appliedLabels instanceof Map) ? state.appliedLabels : [:]
    String current = existing ? unprefixedLabel(existing.label, prefix) : legacy
    boolean untouched = !current || current == "Zone ${zoneNum}" || current == applied[key]

    String base = chosen ?: ((fromPanel && untouched) ? fromPanel : (current ?: "Zone ${zoneNum}"))

    // Only a name set from the app or the UNO is remembered; a hand-made one must never look like ours.
    if ((base == chosen || base == fromPanel) && applied[key] != base) {
        Map updated = new LinkedHashMap(applied)
        updated[key] = base
        state.appliedLabels = updated
    }
    return base
}

// A zone's own name, without the prefix. A zone that still has its default name has no real name to show.
private String zoneName(Integer zone) {
    String label = unprefixedLabel(getChildDevice("uno-zone-${zone}")?.label)
    return (label && label != "Zone ${zone}") ? label : null
}

// ---- Zone types ----

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

// A type chosen in the app always wins. Otherwise a device still on the default driver may be upgraded from an event
// or its name; a device already on a specific driver is left alone to avoid flip-flopping.
private String desiredDriverForExisting(String currentDriver, String hintedType, String suggestedType, String nameForInference, String defaultType) {
    if (hintedType) return driverNameForType(canonicalType(hintedType))

    String defaultDriver = driverNameForType(canonicalType(defaultType))
    if (currentDriver && currentDriver != defaultDriver) return currentDriver

    String learned = suggestedType ?: inferTypeFromName(nameForInference)
    return learned ? driverNameForType(canonicalType(learned)) : currentDriver
}

// ---- Updates for the partitions ----

private void sendPartitionState(Integer partition, String status) {
    try {
        childOrLegacy("uno-partition-${partition}")?.setAlarmState(status)
    } catch (e) {
        logDebug("Failed to update partition ${partition}: ${e}")
    }
}

// Partitions without a device (not in the partition set) are ignored.
private void sendPartitionEvents(Integer partition, List events) {
    def dev = childOrLegacy("uno-partition-${partition}")
    if (!dev) return
    events?.each { Map e -> dev.sendEvent(e) }
}

// With one partition and no zones assigned to partitions, that partition's device shows the open and bypassed zones
// too. (The UNO doesn't say which partition a zone is in, so with several they stay on this device.)
private List singlePartitionTarget() {
    List<Integer> planned = (state.plannedPartitions instanceof List) ? (state.plannedPartitions as List<Integer>) : null
    List partitions = planned != null ? planned.collect { Integer p -> getChildDevice("uno-partition-${p}") }.findAll { it } : partitionChildren()
    boolean single = (planned != null ? planned.size() : partitions.size()) == 1
    return (single && partitions.size() == 1 && partitionZoneMap().isEmpty()) ? partitions : []
}

private void forwardPartitionSummary(Map changed) {
    List target = singlePartitionTarget()
    if (!target) return
    changed.findAll { String n, def v -> n in ["openZones", "bypassedZones"] }.each { String n, def v -> target[0].sendEvent(name: n, value: v) }
}

private void copySummaryToSinglePartition() {
    List target = singlePartitionTarget()
    if (!target) return
    ["openZones", "bypassedZones"].each { String n ->
        def v = device.currentValue(n)
        if (v != null) target[0].sendEvent(name: n, value: v)
    }
}

// ---- Stale sensors ----

// The app asks once a day, and is told which zones have had no activity in that many days. A zone that has never
// triggered counts only once the check itself has been running that long ("since"), because there is no way to know how
// old its sensor is.
def checkStaleSensors(Integer days, Long since) {
    long cutoff = now() - days * 86400000L
    List<String> stale = []
    children().each { dev ->
        Integer zone = zoneNumberOf(dev.deviceNetworkId)
        if (!zone) return

        Long last = parseFaultTime(dev.currentValue("lastFault"))
        boolean isStale = (last != null) ? last < cutoff : (since ?: now()) < cutoff
        if (isStale) stale << (zoneName(zone) ?: "Zone ${zone}").toString()
    }
    parent?.staleSensorResult(days, stale.sort())
}

private Long parseFaultTime(def text) {
    if (!text) return null
    try {
        def fmt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        fmt.setTimeZone(location.timeZone)
        return fmt.parse(text.toString()).time
    } catch (ignored) {
        return null
    }
}

private String cleanPayload(String s) {
    return (s ?: "").replace('$', ' ').trim()
}

private void logInfo(String msg) {
    log.info msg
}

private void logWarn(String msg) {
    log.warn msg
}

private void logDebug(String msg) {
    if (settings.debugLogging) {
        log.debug msg
    }
}
