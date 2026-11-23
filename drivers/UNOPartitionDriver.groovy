metadata {
    definition(name: "UNO Partition", namespace: "uno", author: "Eric Hess",
               importUrl: "https://raw.githubusercontent.com/pierowman/hubitat-eyezon-uno/main/drivers/UNOPartitionDriver.groovy") {
        capability "Actuator"
        capability "Sensor"
        capability "Refresh"
        capability "HealthCheck"
        capability "TemperatureMeasurement"
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
        attribute "lastEvent", "string"
        attribute "lastEventType", "string"
        attribute "lastArmedBy", "string"
        attribute "lastArmedAt", "string"
        attribute "lastDisarmedBy", "string"
        attribute "lastDisarmedAt", "string"
        attribute "lastArmMode", "string"
        attribute "armingMode", "enum", ["none", "stay", "away", "unknown"]
        attribute "outsideTemperature", "number"
        attribute "lastAlarmType", "string"
        attribute "lastAlarmZone", "string"
        attribute "lastAlarmAt", "string"
        attribute "lastKeypadSound", "string"
        attribute "openZones", "string"
        attribute "bypassedZones", "string"
        attribute "healthStatus", "enum", ["online", "offline"]
        command "armStay"
        command "armAway"
        command "armStayBypass", [[name: "Code (only if arming requires one)", type: "STRING"]]
        command "armAwayBypass", [[name: "Code (only if arming requires one)", type: "STRING"]]
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
    sendEvent(name: "armingMode", value: "none")
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
// HealthCheck: ask the panel for a full state refresh.
def ping() { parent?.refreshAll() }
def armStay() { parent?.armStay(partitionNumber()); sendEvent(name: "lastUserAction", value: "armStay", isStateChange: true) }
def armStayWithCode(String code) { parent?.armStay(partitionNumber(), code); sendEvent(name: "lastUserAction", value: "armStay", isStateChange: true) }
def armAwayWithCode(String code) { parent?.armAway(partitionNumber(), code); sendEvent(name: "lastUserAction", value: "armAway", isStateChange: true) }

// Bypasses whatever is open, then arms.
def armStayBypass(String code = null) { parent?.armStayBypass(partitionNumber(), code); sendEvent(name: "lastUserAction", value: "armStayBypass", isStateChange: true) }
def armAwayBypass(String code = null) { parent?.armAwayBypass(partitionNumber(), code); sendEvent(name: "lastUserAction", value: "armAwayBypass", isStateChange: true) }

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

    // How it was last armed comes from the partition state; who did it comes from the Contact ID event.
    if (value == "armedStay") sendEvent(name: "lastArmMode", value: "stay")
    if (value in ["armedAway", "armedAwayZeroEntry"]) sendEvent(name: "lastArmMode", value: "away")

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
