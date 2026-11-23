metadata {
    definition(name: "UNO Status", namespace: "uno", author: "Eric Hess",
               importUrl: "https://raw.githubusercontent.com/pierowman/hubitat-eyezon-uno/main/drivers/UNOStatusDriver.groovy") {
        capability "Sensor"
        capability "HealthCheck"

        attribute "openZones", "string"
        attribute "bypassedZones", "string"
        attribute "troubleSummary", "string"
        attribute "staleSensors", "string"
        attribute "healthStatus", "enum", ["online", "offline"]
    }
}

def installed() {
    sendEvent(name: "openZones", value: "none")
    sendEvent(name: "bypassedZones", value: "none")
    sendEvent(name: "troubleSummary", value: "none")
    sendEvent(name: "staleSensors", value: "none")
}

// HealthCheck: ask the panel for a full state refresh.
def ping() { parent?.refreshAll() }
