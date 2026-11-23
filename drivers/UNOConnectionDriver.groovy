metadata {
    definition(name: "UNO Connection", namespace: "uno", author: "Eric Hess") {
        capability "Initialize"
        capability "Refresh"
        capability "Actuator"
        capability "Sensor"

        attribute "connectionState", "string"
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
        attribute "panelTime", "string"
        attribute "panelMac", "string"
        attribute "lastAck", "string"
        attribute "lastSystemMessage", "string"
        attribute "lastRawMessage", "string"
        attribute "zoneBitmapLength", "number"
        attribute "lastHeartbeat", "string"
        attribute "lastCidEvent", "string"
        attribute "restStartupSync", "string"

        command "armStay"
        command "armAway"
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
        command "poll"
        command "restStartupSync"
    }

    preferences {
        input name: "ip", type: "text", title: "UNO IP Address", required: true
        input name: "port", type: "number", title: "UNO Port", defaultValue: 4025, required: true
        input name: "password", type: "text", title: "UNO TPI Password", required: true
        input name: "restUsername", type: "text", title: "UNO REST Username", defaultValue: "user", required: false
        input name: "masterCode", type: "text", title: "UNO Master Code", required: false
        input name: "zoneCount", type: "number", title: "Configured Zone Count", defaultValue: 27, required: true
        input name: "zoneSet", type: "text", title: "Zone set (optional, e.g. 1-8,12,20-27; overrides zone count)", required: false
        input name: "enablePanic", type: "bool", title: "Allow panic commands (contacts emergency services if the UNO is monitored)", defaultValue: false
        input name: "requireCodeToArm", type: "bool", title: "Require the master code to arm (checked locally; the UNO's arm commands take no code)", defaultValue: false
        input name: "useRestMetadata", type: "bool", title: "Enable REST metadata/startup sync", defaultValue: true
        input name: "debugLogging", type: "bool", title: "Enable debug logging", defaultValue: false
        input name: "traceLogging", type: "bool", title: "Enable trace logging", defaultValue: false
        input name: "heartbeatMinutes", type: "enum", title: "Heartbeat interval", options: ["1":"Every 1 minute", "5":"Every 5 minutes", "10":"Every 10 minutes"], defaultValue: "1", required: true
        input name: "watchdogMinutes", type: "enum", title: "Reconnect if no frames seen for", options: ["3":"3 minutes", "5":"5 minutes", "10":"10 minutes"], defaultValue: "3", required: true
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
    state.troubleBytes = [:]
    state.lastBypassBitmap = null
    state.lastTroubleBitmap = null
    state.loggedIn = false
    state.loginSent = false
    state.lastFrameTs = 0L
    state.lastCidByZone = [:]
    state.lastCidEvent = ""
    state.restStartupDone = false
    state.authFailed = false

    Map zoneSelection = parseRange(settings.zoneSet, 1, 128)
    if (zoneSelection.invalid) logWarn("Ignoring invalid zone set entries: ${zoneSelection.invalid.join(', ')}")
    if (settings.zoneSet && !zoneSelection.values) logWarn("Zone set has no valid zones; using zone count ${configuredZoneCount()}")

    sendEvent(name: "connectionState", value: "disconnected")
    if (!device.currentValue("alarmSystemStatus")) sendEvent(name: "alarmSystemStatus", value: "unknown")
    if (!device.currentValue("troubleState")) sendEvent(name: "troubleState", value: "unknown")

    scheduleHealthChecks()
    reconnect()
}

private void scheduleHealthChecks() {
    String hb = (settings.heartbeatMinutes ?: "1").toString()

    if (hb == "1") {
        runEvery1Minute("heartbeatCheck")
    } else if (hb == "5") {
        runEvery5Minutes("heartbeatCheck")
    } else {
        runEvery10Minutes("heartbeatCheck")
    }

    // The check runs every minute; the configured "no frames" threshold is applied inside watchdogCheck.
    runEvery1Minute("watchdogCheck")
    logDebug("Scheduled heartbeat=${hb}m watchdog=${settings.watchdogMinutes ?: '3'}m")
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

def socketStatus(String message) {
    logDebug("socketStatus: ${message}")

    String m = (message ?: "").toLowerCase()

    // Check failure words first: "disconnected" and "failed to connect" both contain "connect".
    if (m.contains("close") || m.contains("disconnect") || m.contains("error") || m.contains("fail")) {
        logWarn("Socket disconnected: ${message}")
        if (!state.authFailed) {
            sendEvent(name: "connectionState", value: "disconnected")
        }
        state.loggedIn = false
        state.loginSent = false
        failOutstanding("connection lost")
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
    if (state.loggedIn) {
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
    enqueueCommand("12", "${p},${code}", "disarm", [log: "${p},${'*' * code.length()}"])
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
        enqueueCommand("03", "${p},${k.substring(i, i + 1)}", "keypress", [log: "${p},*"])
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

    long lastTs = (state.lastFrameTs ?: 0L) as Long
    if (lastTs == 0L) return

    // Never trip faster than the heartbeat, or an idle panel would be reconnected needlessly.
    Integer wdMinutes = Math.max(safeInt(settings.watchdogMinutes, 3), safeInt(settings.heartbeatMinutes, 1) + 1)

    long ageSeconds = (now() - lastTs) / 1000L
    if (ageSeconds > (wdMinutes * 60)) {
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
private List<Integer> configuredZones() {
    Map parsed = parseRange(settings.zoneSet, 1, 128)
    if (parsed.values) return parsed.values
    return (1..configuredZoneCount()).collect { it as Integer }
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
    ["log", "noRetry", "partition"].each { String key ->
        if (opts[key] != null) entry[key] = opts[key]
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

// A command that hasn't been sent within this time is dropped rather than run late.
private Integer queueMaxAgeSeconds() {
    return 30
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

private void processQueue() {
    expireStaleCommands()

    if (!state.loggedIn) {
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

    String cmd = tpiCommand(inflight.cc as String, inflight.data as String)
    String logCmd = tpiCommand(inflight.cc as String, (inflight.log ?: inflight.data) as String)
    rawSend(cmd + "\r\n", logCmd + "\r\n")
    runIn(4, "commandTimeoutCheck")
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
    if (ageMs < 3500L) return

    if (!inflight.noRetry && (inflight.retries as Integer) < 1) {
        inflight.retries = ((inflight.retries as Integer) + 1)
        inflight.sentAt = now()
        state.commandInFlight = inflight

        logWarn("Retrying ${inflight.name}")
        sendInFlight()
    } else {
        logWarn("Command timeout: ${inflight.name}" + (inflight.noRetry ? " (not retried)" : ""))
        sendEvent(name: "lastAck", value: "timeout:${inflight.name}")
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
    interfaces.rawSocket.sendMessage(msg)
}

def parse(String message) {
    if (message == null) return

    String decoded = decodeIncoming(message)
    if (decoded == null) return

    state.lastFrameTs = now()
    sendEvent(name: "lastHeartbeat", value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone))

    state.rxBuffer = (state.rxBuffer ?: "") + decoded

    // The prompt has no terminator, so drop it from the buffer or it would prefix the "OK" reply.
    if (!state.loggedIn && !state.loginSent) {
        String rx = state.rxBuffer
        int idx = rx.toLowerCase().indexOf("login")
        if (idx >= 0) {
            int end = idx + 5
            if (rx.length() > end && rx.substring(end, end + 1) == ":") end++
            state.rxBuffer = rx.substring(end)
            logInfo("UNO presented login prompt")
            sendEvent(name: "connectionState", value: "loginPrompt")
            login()
        }
    }

    while (true) {
        String buffer = state.rxBuffer ?: ""
        int idxDollar = buffer.indexOf('$')
        int idxLf = buffer.indexOf('\n')
        int idxCr = buffer.indexOf('\r')

        List<Integer> candidates = [idxDollar, idxLf, idxCr].findAll { it >= 0 }
        if (!candidates) break

        int endIdx = candidates.min()
        String frame = buffer.substring(0, endIdx).trim()
        state.rxBuffer = buffer.substring(endIdx + 1)

        if (frame) {
            // One bad frame must not stop the rest of the buffer from being processed.
            try {
                parseFrame(frame)
            } catch (Exception e) {
                logWarn("Error handling frame '${redact(frame).take(80)}': ${e}")
            }
        }
    }

    // Login replies are plain words; accept them even if the panel doesn't terminate them.
    if (state.loginSent && !state.loggedIn) {
        String pending = (state.rxBuffer ?: "").trim()
        if (pending.equalsIgnoreCase("OK") || pending.equalsIgnoreCase("FAILED") || pending.equalsIgnoreCase("Timed Out!") || pending.equalsIgnoreCase("Timed Out")) {
            state.rxBuffer = ""
            parseFrame(pending)
        }
    }
}

private String decodeIncoming(String message) {
    try {
        byte[] data = hubitat.helper.HexUtils.hexStringToByteArray(message)
        String decoded = new String(data)
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
    sendEvent(name: "lastRawMessage", value: frame.take(1024))

    if (frame.equalsIgnoreCase("OK")) {
        state.loggedIn = true
        state.loginSent = false
        state.authFailed = false
        state.authenticatedAt = now()
        logInfo("UNO login successful")
        sendEvent(name: "connectionState", value: "authenticated")

        if (settings.useRestMetadata == true && state.restStartupDone != true) {
            try {
                restStartupSync()
            } catch (e) {
                logWarn("REST startup sync failed: ${e}")
            }
        }

        // The panel sends host information (%05) on its own after login, so only request the state dump.
        enqueueCommand("0C", "", "refreshAll")
        processQueue()
        return
    }

    if (frame.equalsIgnoreCase("FAILED")) {
        state.loggedIn = false
        state.loginSent = false
        state.authFailed = true
        logWarn("UNO login failed")
        sendEvent(name: "connectionState", value: "auth_failed")
        scheduleReconnect(300)
        return
    }

    if (frame.equalsIgnoreCase("Timed Out!") || frame.equalsIgnoreCase("Timed Out")) {
        state.loginSent = false
        logWarn("UNO login timed out")
        sendEvent(name: "connectionState", value: "loginTimedOut")
        scheduleReconnect(nextReconnectDelay())
        return
    }

    if (frame.equalsIgnoreCase("Login:")) {
        logDebug("Ignoring login prompt frame after handling")
        return
    }

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

    String code = parts[0]
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
        case "09":
            handleSystemMessage(parts)
            break
        case "0A":
            handlePersistentSound(parts)
            break
        case "10":
            handleChime(parts)
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
        parent?.notifyPartitionState(partitionNum, mapped)
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
            routeZoneUpdate(zoneNum, active, active ? "1" : "0")
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

    Integer code = safeParseInt(ee, -1)
    String ack = cc + "," + ee
    sendEvent(name: "lastAck", value: ack)
    logDebug("ACK: ${ack}")

    def inflight = state.commandInFlight
    // The ack echoes the command it answers; ignore acks for anything other than the in-flight command.
    if (!inflight || !cc.equalsIgnoreCase(inflight.cc as String)) {
        return
    }

    // Response code 1 means the panel was still busy and did not run the command (spec 3.7), so it is safe to
    // send it again after a growing delay: 1s, 2s, 4s.
    if (code == 1 && safeInt(inflight.overruns, 0) < 3) {
        Integer attempt = safeInt(inflight.overruns, 0) + 1
        Integer delay = 1 << (attempt - 1)
        inflight.overruns = attempt
        inflight.waiting = true
        state.commandInFlight = inflight
        unschedule("commandTimeoutCheck")
        logWarn("${inflight.name}: the UNO was busy (receive buffer overrun); retry ${attempt} of 3 in ${delay}s")
        runIn(delay, "retryInFlight")
        return
    }

    if (code != 0) {
        logWarn("Command ${inflight.name} rejected: ${tpiResponseText(code)} (code ${ee})")
        sendEvent(name: "lastAck", value: "error:${inflight.name}:${ee}", isStateChange: true)
    } else if (inflight.name == "chimeOn" || inflight.name == "chimeOff") {
        // The TPI doesn't report chime state, so this reflects the last accepted command.
        String chime = inflight.name == "chimeOn" ? "on" : "off"
        Integer chimePartition = safeInt(inflight.partition, 1)
        if (chimePartition == 1) sendEvent(name: "chime", value: chime)
        parent?.notifyPartitionEvents(chimePartition, [[name: "chime", value: chime]])
    }
    state.commandInFlight = null

    processQueue()
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
    sendEvent(name: "zoneBitmapLength", value: bitmapHex.length())

    String previous = state.lastZoneBitmap
    state.lastZoneBitmap = bitmapHex

    Map<Integer, Boolean> currentMap = decodeZoneBitmap(bitmapHex)
    Map<Integer, Boolean> previousMap = previous ? decodeZoneBitmap(previous) : [:]

    // Bitmaps are padded to whole bytes; only the configured zones are real.
    for (Integer zone : configuredZones()) {
        boolean newState = currentMap.get(zone, false)
        boolean oldState = previousMap.get(zone, false)

        if (previous == null || newState != oldState) {
            boolean suppressDebug = false
            Long lastCidTs = null

            if (state.lastCidByZone instanceof Map) {
                lastCidTs = state.lastCidByZone["${zone}"] as Long
            }

            if (lastCidTs != null) {
                long ageMs = now() - lastCidTs
                if (ageMs >= 0 && ageMs <= 1500L) {
                    suppressDebug = true
                }
            }

            if (!suppressDebug) {
                logDebug("Zone ${zone} -> ${newState ? 'active/open' : 'inactive/closed'}")
            }

            emitZoneFastEvent(zone, newState)
            routeZoneUpdate(zone, newState, newState ? "1" : "0")
        }
    }
}

private Map<Integer, Boolean> decodeZoneBitmap(String hex) {
    Map<Integer, Boolean> result = [:]
    if (!hex) return result

    int zoneNumber = 1
    for (int byteIndex = 0; byteIndex < hex.length(); byteIndex += 2) {
        if (byteIndex + 2 > hex.length()) break
        String byteHex = hex.substring(byteIndex, byteIndex + 2)
        int value = Integer.parseInt(byteHex, 16)

        for (int bit = 0; bit < 8; bit++) {
            boolean open = ((value >> bit) & 0x01) == 1
            result[zoneNumber] = open
            zoneNumber++
        }
    }

    return result
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
        if (status == state.partitionStatus["${partition}"]) continue

        state.partitionStatus["${partition}"] = status
        if (partition == 1 && status != device.currentValue("alarmSystemStatus")) {
            sendEvent(name: "alarmSystemStatus", value: status)
            logInfo("Alarm status -> ${status}")
        } else if (partition != 1) {
            logInfo("Partition ${partition} status -> ${status}")
        }

        parent?.notifyPartitionState(partition, status)
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
    if (!cid || cid.length() < 10) {
        logDebug("Ignoring malformed CID event: ${cid}")
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

    if (zone <= 0 || !configuredZones().contains(zone)) return

    Set<String> fastPathZoneCodes = [
        "110", "113", "130", "131", "132", "134", "137", "144", "154", "162"
    ] as Set

    // Only zone-type codes carry a zone number; for others (e.g. arm/disarm) it is a user ID.
    if (!fastPathZoneCodes.contains(eventCode)) return
    if (!(isEvent || isRestore)) return

    parent?.upsertDiscoveredZone(zone, suggestTypeFromCid(eventCode), null)

    boolean active = isEvent && !isRestore

    state.lastCidByZone = state.lastCidByZone ?: [:]
    state.lastCidByZone["${zone}"] = now()

    logInfo("CID fast path: zone ${zone} -> ${active ? 'active/open' : 'restore/closed'} (code ${eventCode})")
    emitZoneFastEvent(zone, active)
    routeZoneUpdate(zone, active, active ? "1" : "0")
}

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

// %04 is a zone bitmap (same layout as %01) where a set bit means the zone is bypassed.
private void handleBypassBitmap(List<String> parts) {
    if (parts.size() < 2) return

    String bitmap = cleanPayload(parts[1]).toUpperCase()
    if (!(bitmap ==~ /[0-9A-F]+/)) {
        logWarn("Ignoring malformed bypass bitmap: ${bitmap.take(64)}")
        return
    }

    String previous = state.lastBypassBitmap
    state.lastBypassBitmap = bitmap

    Map<Integer, Boolean> current = decodeZoneBitmap(bitmap)
    Map<Integer, Boolean> before = previous ? decodeZoneBitmap(previous) : [:]

    for (Integer zone : configuredZones()) {
        boolean bypassed = current.get(zone, false)
        if (previous == null || bypassed != before.get(zone, false)) {
            if (previous != null) logInfo("Zone ${zone} ${bypassed ? 'bypassed' : 'no longer bypassed'}")
            routeBypassUpdate(zone, bypassed)
        }
    }
}

// Bypass changes only update existing zone devices; they never create new ones.
private void routeBypassUpdate(Integer zone, boolean bypassed) {
    def child = findZoneChild("uno-zone-${zone}")
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
    if (panelTime) sendEvent(name: "panelTime", value: panelTime)

    logDebug("Panel info: model=${model}, fw=${fw}, partition=${defaultPartition}, mac=${mac}, time=${panelTime}")
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

    for (int idx = 0; idx < 8 && (idx * 2 + 2) <= bitmap.length(); idx++) {
        Integer partition = idx + 1
        String byteHex = bitmap.substring(idx * 2, idx * 2 + 2)
        if (byteHex == state.troubleBytes["${partition}"]) continue
        state.troubleBytes["${partition}"] = byteHex

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

        parent?.notifyPartitionEvents(partition, attrs.collect { String name, String value -> [name: name, value: value] })
    }
}

private Integer safeParseHex(String s, Integer fallback = 0) {
    try {
        return Integer.parseInt(s, 16)
    } catch (ignored) {
        return fallback
    }
}

private void handleSystemMessage(List<String> parts) {
    String msg = parts.drop(1).join(",")
    sendEvent(name: "lastSystemMessage", value: msg.take(1024))
    logDebug("System message: ${msg}")
}

private void handlePersistentSound(List<String> parts) {
    logDebug("Persistent sound frame received")
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
        parent?.notifyPartitionEvents(partition, [[name: "lastChime", value: value, isStateChange: true]])
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

private void routeZoneUpdate(Integer zoneNum, boolean active, String digit) {
    String dni = "uno-zone-${zoneNum}"

    def child = findZoneChild(dni)

    if (!child) {
        parent?.upsertDiscoveredZone(zoneNum, null, null)
        child = findZoneChild(dni)
    }

    if (!child) {
        logWarn("Zone ${zoneNum} device not found via parent app: ${dni}")
        return
    }

    updateZoneChild(child, zoneNum, active, digit)
}

private findZoneChild(String dni) {
    def child = null
    try {
        child = parent?.getChildDevice(dni)
    } catch (ignored) {
    }

    if (!child) {
        try {
            child = parent?.getChildDevices()?.find { it.deviceNetworkId == dni }
        } catch (ignored) {
        }
    }

    return child
}

private void updateZoneChild(child, Integer zoneNum, boolean active, String digit) {
    try {
        child.sendEvent(name: "zoneStateDigit", value: digit, isStateChange: true)
    } catch (ignored) {
    }

    if (active) {
        try {
            child.sendEvent(
                name: "lastFault",
                value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone),
                isStateChange: true
            )
        } catch (ignored) {
        }
    }

    if (hasCapabilitySafe(child, "ContactSensor")) {
        String value = active ? "open" : "closed"
        child.sendEvent(
            name: "contact",
            value: value,
            type: "physical",
            descriptionText: "Zone ${zoneNum} ${value}",
            isStateChange: true
        )
    }

    if (hasCapabilitySafe(child, "MotionSensor")) {
        String value = active ? "active" : "inactive"
        child.sendEvent(
            name: "motion",
            value: value,
            type: "physical",
            descriptionText: "Zone ${zoneNum} motion ${value}",
            isStateChange: true
        )
    }

    if (hasCapabilitySafe(child, "SmokeDetector")) {
        String value = active ? "detected" : "clear"
        child.sendEvent(
            name: "smoke",
            value: value,
            type: "physical",
            descriptionText: "Zone ${zoneNum} smoke ${value}",
            isStateChange: true
        )
    }

    if (hasCapabilitySafe(child, "CarbonMonoxideDetector")) {
        String value = active ? "detected" : "clear"
        child.sendEvent(
            name: "carbonMonoxide",
            value: value,
            type: "physical",
            descriptionText: "Zone ${zoneNum} carbon monoxide ${value}",
            isStateChange: true
        )
    }

    if (hasCapabilitySafe(child, "WaterSensor")) {
        String value = active ? "wet" : "dry"
        child.sendEvent(
            name: "water",
            value: value,
            type: "physical",
            descriptionText: "Zone ${zoneNum} water ${value}",
            isStateChange: true
        )
    }
}

private boolean hasCapabilitySafe(child, String cap) {
    try {
        return child.hasCapability(cap)
    } catch (ignored) {
        return false
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
