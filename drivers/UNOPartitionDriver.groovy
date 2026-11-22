metadata {
    definition(name: "UNO Partition", namespace: "uno", author: "Eric Hess") {
        capability "Actuator"
        capability "Sensor"
        capability "Refresh"
        capability "Alarm"
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
}

// The partition number comes from the device network ID (uno-partition-N) set by the app.
private Integer partitionNumber() {
    def m = ((device.deviceNetworkId ?: "") =~ /uno-partition-(\d+)/)
    return m.find() ? Integer.parseInt(m.group(1)) : 1
}

def refresh() { parent?.refreshAll() }
def armStay() { parent?.armStay(partitionNumber()); sendEvent(name: "lastUserAction", value: "armStay", isStateChange: true) }
def armAway() { parent?.armAway(partitionNumber()); sendEvent(name: "lastUserAction", value: "armAway", isStateChange: true) }
def disarm(String code = null) { parent?.disarm(code, partitionNumber()); sendEvent(name: "lastUserAction", value: "disarm", isStateChange: true) }
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
