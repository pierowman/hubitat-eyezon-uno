metadata {
    definition(name: "UNO Zone Contact", namespace: "uno", author: "Eric Hess",
               importUrl: "https://raw.githubusercontent.com/pierowman/hubitat-eyezon-uno/main/drivers/UNOZoneContactDriver.groovy") {
        capability "Sensor"
        capability "ContactSensor"
        capability "TamperAlert"
        capability "TemperatureMeasurement"
        capability "HealthCheck"

        attribute "zoneStateDigit", "string"
        attribute "lastFault", "string"
        attribute "bypassed", "string"
        attribute "healthStatus", "enum", ["online", "offline"]

        command "bypass"
        command "unbypass"
    }
}

def installed() {
    sendEvent(name: "contact", value: "closed")
    sendEvent(name: "zoneStateDigit", value: "0")
    sendEvent(name: "bypassed", value: "false")
    sendEvent(name: "tamper", value: "clear")
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
