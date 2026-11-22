metadata {
    definition(name: "UNO Zone CO", namespace: "uno", author: "Eric Hess") {
        capability "Sensor"
        capability "CarbonMonoxideDetector"

        attribute "zoneStateDigit", "string"
        attribute "lastFault", "string"
        attribute "bypassed", "string"

        command "bypass"
        command "unbypass"
    }
}

def installed() {
    sendEvent(name: "carbonMonoxide", value: "clear")
    sendEvent(name: "zoneStateDigit", value: "0")
    sendEvent(name: "bypassed", value: "false")
}

// The zone number comes from the device network ID (uno-zone-N) set by the app.
private Integer zoneNumber() {
    def m = ((device.deviceNetworkId ?: "") =~ /uno-zone-(\d+)/)
    return m.find() ? Integer.parseInt(m.group(1)) : null
}

def bypass() { parent?.bypassZone(zoneNumber()) }
def unbypass() { parent?.unbypassZone(zoneNumber()) }
