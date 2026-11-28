metadata {
    definition(name: "UNO Zone Smoke", namespace: "uno", author: "Eric Hess",
               importUrl: "https://raw.githubusercontent.com/pierowman/hubitat-eyezon-uno/main/drivers/UNOZoneSmokeDriver.groovy") {
        capability "Sensor"
        capability "SmokeDetector"
        capability "TamperAlert"
        capability "HealthCheck"

        // Not the TemperatureMeasurement capability: that makes Alexa and other capability pickers list every door and
        // motion zone as a temperature sensor. The attribute is still published for zones that report a temperature.
        attribute "temperature", "number"

        attribute "zoneStateDigit", "string"
        attribute "lastFault", "string"
        attribute "bypassed", "string"
        attribute "batteryStatus", "enum", ["ok", "low"]
        attribute "supervision", "enum", ["ok", "lost"]
        attribute "healthStatus", "enum", ["online", "offline"]

        command "bypass"
        command "unbypass"
    }
}

def installed() {
    sendEvent(name: "smoke", value: "clear")
    sendEvent(name: "zoneStateDigit", value: "0")
    sendEvent(name: "bypassed", value: "false")
    sendEvent(name: "tamper", value: "clear")
    sendEvent(name: "batteryStatus", value: "ok")
    sendEvent(name: "supervision", value: "ok")
}

// HealthCheck: ask the panel for a full state refresh.
def ping() { parent?.refreshAll() }

// The zone number comes from the device network ID (uno-zone-N) set by the app.
private Integer zoneNumber() {
    def m = ((device.deviceNetworkId ?: "") =~ /uno-zone-(\d+)/)
    return m.find() ? Integer.parseInt(m.group(1)) : null
}

def bypass() { parent?.bypassZone(zoneNumber()) }
def unbypass() { parent?.unbypassZone(zoneNumber()) }

// Called by the connection driver with the zone's new state, so it costs one call instead of one per capability.
def applyZoneState(def active) {
    boolean on = (active == true || active == "true")
    sendEvent(name: "zoneStateDigit", value: on ? "1" : "0", isStateChange: true)
    if (on) sendEvent(name: "lastFault", value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone), isStateChange: true)
    String value = on ? "detected" : "clear"
    sendEvent(name: "smoke", value: value, type: "physical", descriptionText: "Zone ${zoneNumber()} smoke ${value}", isStateChange: true)
}
