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
    state.rxBuffer = ""
    state.commandQueue = []
    state.commandInFlight = null
    state.lastZoneBitmap = null
    state.lastPartitionBitmap = null
    state.lastBypassBitmap = null
    state.lastTroubleBitmap = null
    state.loggedIn = false
    state.loginSent = false
    state.lastFrameTs = 0L
    state.lastCidByZone = [:]
    state.lastCidEvent = ""
    state.restStartupDone = false
    state.authFailed = false

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

    try {
        interfaces.rawSocket.close()
    } catch (ignored) {
    }

    state.loggedIn = false
    state.loginSent = false
    state.commandInFlight = null
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
        scheduleReconnect(30)
    }
}

private void scheduleReconnect(Integer seconds) {
    logDebug("Reconnect scheduled in ${seconds}s")
    runIn(seconds, "reconnect")
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
        state.commandInFlight = null
        // Back off after a rejected password so retries can't lock the panel out.
        scheduleReconnect(state.authFailed ? 300 : 5)
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

def armStay() {
    enqueueCommand("08", "1", "armStay")
}

def armAway() {
    enqueueCommand("09", "1", "armAway")
}

// TPI command 10: <partition>,<0|1>. Not available on EVL4MAX-SA / DUO-SA.
def chimeOn() {
    enqueueCommand("10", "1,1", "chimeOn")
}

def chimeOff() {
    enqueueCommand("10", "1,0", "chimeOff")
}

def disarm(String suppliedCode = null) {
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
    enqueueCommand("12", "1,${code}", "disarm")
}

def clearCommandQueue() {
    state.commandQueue = []
    state.commandInFlight = null
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

private String tpiCommand(String cc, String data = "") {
    return "^" + cc + "," + (data ?: "") + '$'
}

private void enqueueCommand(String cc, String data, String name) {
    state.commandQueue = state.commandQueue ?: []
    state.commandQueue << [cc: cc, data: (data ?: ""), name: name, ts: now()]
    logDebug("Queued ${name}")
    processQueue()
}

private void processQueue() {
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
        cc     : next.cc,
        data   : next.data,
        name   : next.name,
        sentAt : now(),
        retries: 0
    ]

    String cmd = tpiCommand(next.cc as String, next.data as String)
    logInfo("Sending ${next.name}")
    rawSend(cmd + "\r\n")
    runIn(4, "commandTimeoutCheck")
}

def commandTimeoutCheck() {
    def inflight = state.commandInFlight
    if (!inflight) return

    long ageMs = now() - (inflight.sentAt as Long)
    if (ageMs < 3500L) return

    if ((inflight.retries as Integer) < 1) {
        inflight.retries = ((inflight.retries as Integer) + 1)
        inflight.sentAt = now()
        state.commandInFlight = inflight

        String retryCmd = tpiCommand(inflight.cc as String, inflight.data as String)
        logWarn("Retrying ${inflight.name}")
        rawSend(retryCmd + "\r\n")
        runIn(4, "commandTimeoutCheck")
    } else {
        logWarn("Command timeout: ${inflight.name}")
        sendEvent(name: "lastAck", value: "timeout:${inflight.name}")
        state.commandInFlight = null
        processQueue()
    }
}

private void rawSend(String msg) {
    if (settings.traceLogging) {
        log.debug "TX >>> ${msg}"
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

        if (frame) parseFrame(frame)
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
        scheduleReconnect(30)
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

    Map snapshot = restGetJson("A")
    if (!snapshot) return

    def partitions = snapshot["partitions"] instanceof List ? snapshot["partitions"] : []
    if (partitions && partitions[0] instanceof Map) {
        Integer pStatus = safeInt(partitions[0]["status"], -1)
        String mapped = mapRestPartitionStatus(pStatus)
        if (mapped) {
            sendEvent(name: "alarmSystemStatus", value: mapped)
            parent?.notifyPartitionState(mapped)
        }
    }

    def zoneStatus = snapshot["zoneStatus"] instanceof List ? snapshot["zoneStatus"] : []
    if (zoneStatus && zoneStatus[0] instanceof List) {
        (zoneStatus[0] as List).each { z ->
            if (!(z instanceof Map)) return
            Integer zoneNum = safeInt(z["n"], 0)
            Integer t = safeInt(z["t"], 0)
            if (zoneNum <= 0 || zoneNum > configuredZoneCount()) return

            boolean active = isRestZoneActive(t)
            routeZoneUpdate(zoneNum, active, active ? "1" : "0")
        }
    }

    state.restStartupDone = true
    sendEvent(name: "restStartupSync", value: "ok")
    logInfo("REST startup sync complete")
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

    if (code != 0) {
        logWarn("Command ${inflight.name} rejected: ${tpiResponseText(code)} (code ${ee})")
        sendEvent(name: "lastAck", value: "error:${inflight.name}:${ee}", isStateChange: true)
    } else if (inflight.name == "chimeOn" || inflight.name == "chimeOff") {
        // The TPI doesn't report chime state, so this reflects the last accepted command.
        sendEvent(name: "chime", value: inflight.name == "chimeOn" ? "on" : "off")
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
    int loopMax = configuredZoneCount()

    for (int zone = 1; zone <= loopMax; zone++) {
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

private void handlePartitionFrame(List<String> parts) {
    if (parts.size() < 2) return

    String bitmap = cleanPayload(parts[1]).toUpperCase()
    state.lastPartitionBitmap = bitmap

    String status = decodePartitionStatus(bitmap)
    if (status != device.currentValue("alarmSystemStatus")) {
        sendEvent(name: "alarmSystemStatus", value: status)
        logInfo("Alarm status -> ${status}")
    }

    parent?.notifyPartitionState(status)
}

private String decodePartitionStatus(String bitmap) {
    if (!bitmap || bitmap.length() < 2) return "unknown"

    String p1 = bitmap.substring(0, 2)
    switch (p1) {
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

    if (zone <= 0 || zone > configuredZoneCount()) return

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

private void handleBypassBitmap(List<String> parts) {
    if (parts.size() < 2) return
    String bitmap = cleanPayload(parts[1]).toUpperCase()
    state.lastBypassBitmap = bitmap
    logDebug("Bypass bitmap updated")
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

private void handleTroubleBitmap(List<String> parts) {
    if (parts.size() < 2) return

    String bitmap = cleanPayload(parts[1]).toUpperCase()
    state.lastTroubleBitmap = bitmap

    // %06 carries one trouble byte per partition; this integration tracks partition 1 (first byte).
    if (bitmap.length() < 2) return
    Integer bits = safeParseHex(bitmap.substring(0, 2), 0)

    // Spec section 3.5: BIT 1..8 map to the low..high bit of the byte.
    List<Map> troubles = [
        [attr: "serviceRequired",     text: "Service required"],
        [attr: "acFailure",           text: "AC failure"],
        [attr: "lowBattery",          text: "Wireless device low battery"],
        [attr: "serverOffline",       text: "Server offline"],
        [attr: "zoneTrouble",         text: "Zone trouble (fault/tamper)"],
        [attr: "batteryOvercurrent",  text: "System battery overcurrent"],
        [attr: "sirenFault",          text: "Bell/siren absent or faulted"],
        [attr: "supervisoryFault",    text: "Wireless device supervisory fault"]
    ]

    List<String> active = []
    troubles.eachWithIndex { Map t, int idx ->
        boolean on = ((bits >> idx) & 0x01) == 1
        if (on) active << (t.text as String)
        String value = on ? "active" : "clear"
        if (value != device.currentValue(t.attr as String)) {
            sendEvent(name: t.attr, value: value, descriptionText: "${t.text} ${value}")
        }
    }

    String detail = active ? active.join(", ") : "none"
    if (detail != device.currentValue("troubleDetail")) {
        sendEvent(name: "troubleDetail", value: detail)
    }

    String trouble = active ? "trouble" : "clear"
    if (trouble != device.currentValue("troubleState")) {
        sendEvent(name: "troubleState", value: trouble)
        logInfo("Trouble state -> ${trouble}: ${detail}")
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
    String type = payload ? payload.substring(0, 1) : "0"
    logDebug("Chime frame received: ${payload}")

    if (type != "0") {
        sendEvent(name: "lastChime", value: "type ${type}", isStateChange: true)
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
