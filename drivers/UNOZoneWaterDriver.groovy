metadata {
    definition(name: "UNO Zone Water", namespace: "uno", author: "Eric Hess",
               importUrl: "https://raw.githubusercontent.com/pierowman/hubitat-eyezon-uno/main/drivers/UNOZoneWaterDriver.groovy") {
        capability "Sensor"
        capability "WaterSensor"
        capability "TamperAlert"
        capability "TemperatureMeasurement"
        capability "HealthCheck"

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
    sendEvent(name: "water", value: "dry")
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
    String value = on ? "wet" : "dry"
    sendEvent(name: "water", value: value, type: "physical", descriptionText: "Zone ${zoneNumber()} water ${value}", isStateChange: true)
}
