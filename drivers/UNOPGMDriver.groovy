metadata {
    definition(name: "UNO PGM", namespace: "uno", author: "Eric Hess",
               importUrl: "https://raw.githubusercontent.com/pierowman/hubitat-eyezon-uno/main/drivers/UNOPGMDriver.groovy") {
        capability "Actuator"
        capability "Momentary"
        capability "HealthCheck"

        attribute "lastTriggered", "string"
        attribute "healthStatus", "enum", ["online", "offline"]

        command "toggle"
    }
}

// The UNO reports no state for a programmable output, so this is a button, not a switch: it toggles or
// pulses the output depending on how the output is programmed on the panel.
def push() { trigger() }
def toggle() { trigger() }

// HealthCheck: ask the panel for a full state refresh.
def ping() { parent?.refreshAll() }

// The PGM number comes from the device network ID (uno-pgm-N) set by the app.
private Integer pgmNumber() {
    def m = ((device.deviceNetworkId ?: "") =~ /uno-pgm-(\d+)/)
    return m.find() ? Integer.parseInt(m.group(1)) : null
}

private void trigger() {
    Integer n = pgmNumber()
    if (n == null) {
        log.warn "Can't tell which PGM this is; the device network ID should be uno-pgm-N"
        return
    }
    parent?.triggerPgm(n)
}
