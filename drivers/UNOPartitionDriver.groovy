metadata {
    definition(name: "UNO Partition", namespace: "uno", author: "Eric Hess") {
        capability "Actuator"
        capability "Sensor"
        capability "Refresh"
        capability "Alarm"
        capability "SecurityKeypad"
        attribute "alarmSystemStatus", "string"
        attribute "partitionState", "string"
        attribute "securitySystemStatus", "string"
        attribute "lastUserAction", "string"
        attribute "ready", "string"
        attribute "acPower", "string"
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
        command "armStay"
        command "armAway"
        command "armStayWithCode", [[name: "Code", type: "STRING"]]
        command "armAwayWithCode", [[name: "Code", type: "STRING"]]
        command "disarm", [[name: "Code", type: "STRING"]]
        command "chimeOn"
        command "chimeOff"
        command "bypassOpenZones"
        command "unbypassAllZones"
        command "sendKeys", [[name: "Keys (0-9 * #)", type: "STRING"]]
        command "panicFire"
        command "panicMedical"
        command "panicPolice"
        command "off"
        command "strobe"
        command "siren"
        command "both"
    }
}

def installed() {
    sendEvent(name: "alarmSystemStatus", value: "unknown")
    sendEvent(name: "partitionState", value: "unknown")
    sendEvent(name: "securitySystemStatus", value: "unknown")
    sendEvent(name: "alarm", value: "off")
    sendEvent(name: "securityKeypad", value: "unknown")
    sendKeypadDefaults()
}

// SecurityKeypad attributes for dashboard keypad tiles. Codes are managed on the UNO, not stored here.
private void sendKeypadDefaults() {
    if (device.currentValue("codeLength") == null) sendEvent(name: "codeLength", value: 6)
    if (device.currentValue("maxCodes") == null) sendEvent(name: "maxCodes", value: 0)
    if (device.currentValue("lockCodes") == null) sendEvent(name: "lockCodes", value: "{}")
}

// The partition number comes from the device network ID (uno-partition-N) set by the app.
private Integer partitionNumber() {
    def m = ((device.deviceNetworkId ?: "") =~ /uno-partition-(\d+)/)
    return m.find() ? Integer.parseInt(m.group(1)) : 1
}

def refresh() { parent?.refreshAll() }
def armStay() { parent?.armStay(partitionNumber()); sendEvent(name: "lastUserAction", value: "armStay", isStateChange: true) }
def armStayWithCode(String code) { parent?.armStay(partitionNumber(), code); sendEvent(name: "lastUserAction", value: "armStay", isStateChange: true) }
def armAwayWithCode(String code) { parent?.armAway(partitionNumber(), code); sendEvent(name: "lastUserAction", value: "armAway", isStateChange: true) }

// SecurityKeypad / HSM entry points. HSM passes a delay and can send the same request twice, so a request
// for the state the partition is already in is skipped. Night is mapped to stay because the UNO has no night mode.
def armAway(def delay = null) {
    if (keypadStateIs("armed away")) return
    parent?.armAway(partitionNumber())
    sendEvent(name: "lastUserAction", value: "armAway", isStateChange: true)
}

def armHome(def delay = null) {
    if (keypadStateIs("armed home")) return
    armStay()
}

def armNight(def delay = null) { armHome(delay) }

// A number is an HSM delay (always ignored); a string is a user code.
def disarm(def codeOrDelay = null) {
    boolean fromHsm = codeOrDelay instanceof Number
    if (fromHsm && keypadStateIs("disarmed")) return

    String code = fromHsm ? null : codeOrDelay?.toString()
    parent?.disarm(code, partitionNumber())
    sendEvent(name: "lastUserAction", value: "disarm", isStateChange: true)
}

def setExitDelay(def delay) { logDebug("setExitDelay ignored; delays are configured on the UNO") }
def setEntryDelay(def delay) { logDebug("setEntryDelay ignored; delays are configured on the UNO") }
def setCode(def position, def pincode, def name = null) { codesManagedOnUno("setCode") }
def deleteCode(def position) { codesManagedOnUno("deleteCode") }
def setCodeLength(def length) { codesManagedOnUno("setCodeLength") }
def getCodes() { sendEvent(name: "lockCodes", value: "{}") }

private void codesManagedOnUno(String what) {
    log.warn "${what} ignored: user codes are managed on the UNO, not in Hubitat"
}

private void logDebug(String msg) {
    log.debug msg
}

private boolean keypadStateIs(String value) {
    return device.currentValue("securityKeypad") == value
}

def off() { disarm() }
def chimeOn() { parent?.chimeOn(partitionNumber()) }
def chimeOff() { parent?.chimeOff(partitionNumber()) }
def bypassOpenZones() { parent?.bypassOpenZones(partitionNumber()) }
def unbypassAllZones() { parent?.unbypassAllZones(partitionNumber()) }
def sendKeys(String keys) { parent?.sendKeys(keys, partitionNumber()) }
def panicFire() { panic("fire") }
def panicMedical() { panic("medical") }
def panicPolice() { panic("police") }
def strobe() {}
def siren() {}
def both() {}

private void panic(String type) {
    parent?.panic(type, partitionNumber())
    sendEvent(name: "lastUserAction", value: "panic:${type}", isStateChange: true)
}

def setAlarmState(String state) {
    String value = state ?: "unknown"
    sendEvent(name: "alarmSystemStatus", value: value, isStateChange: true)
    sendEvent(name: "partitionState", value: value, isStateChange: true)
    sendEvent(name: "securitySystemStatus", value: mapSecurityState(value), isStateChange: true)
    sendEvent(name: "alarm", value: mapAlarmCapability(value), isStateChange: true)
    sendEvent(name: "ready", value: (value in ["disarmed", "readyBypassed"]) ? "true" : "false")

    // Delays, alarms and busy states have no SecurityKeypad equivalent, so the last stable value is kept.
    String keypad = mapKeypadState(value)
    if (keypad) sendEvent(name: "securityKeypad", value: keypad)
    sendKeypadDefaults()
}

private String mapKeypadState(String state) {
    switch (state) {
        case "disarmed":
        case "readyBypassed":
        case "notReady": return "disarmed"
        case "armedStay": return "armed home"
        case "armedAway":
        case "armedAwayZeroEntry": return "armed away"
        case "unused":
        case "unknown": return "unknown"
        default: return null
    }
}

private String mapSecurityState(String state) {
    switch (state) {
        case "disarmed": return "disarmed"
        case "armedStay": return "armed home"
        case "armedAway": return "armed away"
        case "armedAwayZeroEntry": return "armed away"
        case "alarm": return "alarm"
        case "entryDelay":
        case "exitDelay": return "arming"
        case "busy": return "busy"
        case "notReady": return "not ready"
        default: return state ?: "unknown"
    }
}

private String mapAlarmCapability(String state) {
    switch (state) {
        case "alarm": return "both"
        default: return "off"
    }
}
