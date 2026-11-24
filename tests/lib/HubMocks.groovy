// Mock Hubitat runtime used to load the real driver/app scripts outside a hub.
// These mocks encode our reading of the Hubitat API, so they can't replace testing on a real hub.

class MockLog {
    List entries
    MockLog(List e) { entries = e }
    void info(Object m)  { entries << [lvl: "info",  msg: m.toString()] }
    void warn(Object m)  { entries << [lvl: "warn",  msg: m.toString()] }
    void error(Object m) { entries << [lvl: "error", msg: m.toString()] }
    void debug(Object m) { entries << [lvl: "debug", msg: m.toString()] }
    void trace(Object m) { entries << [lvl: "trace", msg: m.toString()] }
}

class MockSocket {
    List sent
    int closed = 0
    boolean failConnect = false
    List connects = []
    MockSocket(List s) { sent = s }
    void close() { closed++ }
    // Groovy passes named arguments first, Hubitat accepts either form.
    void connect(Map opts, String ip, Integer port) { connect(ip, port, opts) }
    void connect(String ip, Integer port, Map opts) {
        if (failConnect) throw new IOException("connect refused")
        connects << [ip, port]
    }
    void sendMessage(String m) { sent << m }
}

class MockDevice {
    Map vals
    String id = "dev-1"
    String deviceNetworkId = "uno-device"
    MockDevice(Map v) { vals = v }
    def currentValue(String n) { vals[n] }
}

// Stands in for groovyx.net.http.HttpResponseException.
class MockHttpException extends Exception {
    int statusCode
    MockHttpException(int code) { super("HTTP ${code}"); statusCode = code }
}

// A child device as seen by the app / connection driver.
class MockChild {
    String dni, typeName, label, name
    Map props = [:]
    List events = []
    Set caps = []
    List settingsUpdated = []
    int initialized = 0
    List alarmStates = []
    List calls = []
    List callArgs = []
    int labelReads = 0
    String getDeviceNetworkId() { dni }
    String getTypeName() { typeName }
    String getLabel() { labelReads++; label }
    void setLabel(String l) { label = l }
    void sendEvent(Map e) { events << e }
    boolean hasCapability(String c) { caps.contains(c) }
    void updateSetting(String n, Map v) { settingsUpdated << [n, v] }
    void initialize() { initialized++ }
    void setAlarmState(String s) { alarmStates << s }
    def currentValue(String attr) { last(attr) }
    def methodMissing(String name, args) { calls << name; callArgs << [name, args as List]; return null }
    def last(String attr) { events.reverse().find { it.name == attr }?.value }

    // Mirrors what the real zone drivers do with applyZoneState (those are tested in 40_child_drivers).
    List applied = []
    void applyZoneState(def active) {
        applied << active
        boolean on = active == true
        sendEvent(name: "zoneStateDigit", value: on ? "1" : "0", isStateChange: true)
        if (on) sendEvent(name: "lastFault", value: "2026-01-01 00:00:00", isStateChange: true)
        Map byCap = [ContactSensor: ["contact", "open", "closed"], MotionSensor: ["motion", "active", "inactive"],
                     SmokeDetector: ["smoke", "detected", "clear"], CarbonMonoxideDetector: ["carbonMonoxide", "detected", "clear"],
                     WaterSensor: ["water", "wet", "dry"]]
        byCap.each { String cap, List v ->
            if (caps.contains(cap)) sendEvent(name: v[0], value: on ? v[1] : v[2], type: "physical", isStateChange: true)
        }
    }
}

// The app as seen by the drivers (their `parent`). The partitions and zones are the connection driver's own children,
// so `children` is where the driver creates them (a ChildHost writes into this same map).
class MockApp {
    Map children = [:]
    ChildHost host = new ChildHost(children, this)
    List upserts = []                  // zones the driver asked the app about
    List partitionStates = []          // [partition, status]
    Map partitionEvents = [:]          // partition -> events sent to that partition device
    List calls = []
    List callArgs = []
    boolean failPartitionNotify = false
    Map userNames = [:]     // user number -> name, as configured in the app
    Map plan = [prefix: "", partitions: [1: "House Alarm"], autoCreate: true, defaultZoneType: "contact"]
    Map specs = [:]         // zone number -> [label, type, panel] as the app would answer
    // Setting a name here gives that zone a device with the label, which is how the driver looks names up.
    Map zoneNames = new ZoneNames(this)

    def userName(Integer user) { userNames[user] }
    def devicePlan() { plan }
    def zoneSpec(Integer zone) { upserts << [zone]; specs[zone] ?: [:] }
    def methodMissing(String n, a) { calls << n; callArgs << [n, a as List]; null }

    def partitionAttr(Integer p, String name) { partitionEvents[p]?.reverse()?.find { it.name == name }?.value }

    // The zone devices the driver has made (the partition devices are left out).
    Map zoneChildren() { children.findAll { String dni, def c -> dni.startsWith("uno-zone-") } }
}

// A zone name table: putting a name creates (or relabels) the zone's device.
class ZoneNames extends LinkedHashMap {
    MockApp app
    ZoneNames(MockApp a) { app = a }
    Object put(Object zone, Object label) {
        String dni = "uno-zone-${zone}"
        def child = app.children[dni] ?: app.host.make("UNO Zone Contact", dni, [name: "UNO Zone ${zone}", label: label])
        child.label = label
        return super.put(zone, label)
    }
}

// Where a script's child devices live in the tests. Wires the Hubitat child-device calls onto a script.
class ChildHost {
    Map children
    List created = []
    List deleted = []
    MockApp recorder     // when set, partition children also record into it, and a partition is made on first use

    ChildHost(Map children = [:], MockApp recorder = null) {
        this.children = children
        this.recorder = recorder
    }

    static Set capsFor(String type) {
        Map byType = ["UNO Zone Contact": "ContactSensor", "UNO Zone Motion": "MotionSensor", "UNO Zone Smoke": "SmokeDetector",
                      "UNO Zone CO": "CarbonMonoxideDetector", "UNO Zone Water": "WaterSensor"]
        return byType[type] ? [byType[type]] as Set : ([] as Set)
    }

    MockChild make(String type, String dni, Map props) {
        MockChild c = (recorder != null && dni.startsWith("uno-partition-")) ?
            new RecordingPartition(app: recorder, partition: dni.substring("uno-partition-".length()) as Integer) : new MockChild()
        c.dni = dni
        c.typeName = type
        c.label = props.label
        c.name = props.name
        c.props = props
        c.caps = capsFor(type)
        children[dni] = c
        created << [dni, type]
        return c
    }

    def find(String dni) {
        def c = children[dni]
        if (c == null && recorder != null && dni.startsWith("uno-partition-")) c = make("UNO Partition", dni, [label: "Partition", name: "Partition"])
        return c
    }

    void wire(HubBase script) {
        script.metaClass.getChildDevice = { String dni -> find(dni) }
        script.metaClass.getChildDevices = { -> children.values() as List }
        script.metaClass.addChildDevice = { String ns, String type, String dni, Map props ->
            if (children.containsKey(dni)) throw new IllegalStateException("device ${dni} already exists")
            make(type, dni, props)
        }
        script.metaClass.deleteChildDevice = { String dni -> deleted << dni; children.remove(dni) }
    }
}

// A partition device that also reports to the MockApp, which older driver tests read.
class RecordingPartition extends MockChild {
    MockApp app
    Integer partition
    void setAlarmState(String s) {
        if (app.failPartitionNotify) throw new IllegalStateException("boom")
        alarmStates << s
        app.partitionStates << [partition, s]
    }
    void sendEvent(Map e) {
        if (app.failPartitionNotify) throw new IllegalStateException("boom")
        events << e
        app.partitionEvents.get(partition, []) << e
    }
}

// The real connection driver as the app sees its child device.
class ConnAdapter {
    HubBase script
    String label = "UNO Connection"
    List settingsUpdated = []
    List callArgs = []
    String getDeviceNetworkId() { "uno-connection" }
    String getTypeName() { "UNO Connection" }
    String getLabel() { label }
    void setLabel(String l) { label = l }
    void updateSetting(String n, Map v) { settingsUpdated << [n, v]; script.settings[n] = v.value }
    def currentValue(String attr) { script.currentVals[attr] }
    void sendEvent(Map e) { script.sendEvent(e) }
    // Everything else is a call to the real driver; what the app asked for is recorded.
    def methodMissing(String name, args) { callArgs << [name, args as List]; script.invokeMethod(name, args) }
}

class AppEnv {
    Map children = [:]
    List created = []
    List deleted = []
}

// A notification device (capability.notification).
class MockNotifier {
    List messages = []
    boolean fail = false
    void deviceNotification(String text) {
        if (fail) throw new IllegalStateException("notifier down")
        messages << text
    }
}

abstract class HubBase extends Script {
    Map state = [:]
    Map settings = [:]
    List events = []
    List logs = []
    List locationEvents = []
    List sent = []
    Map pending = [:]
    List unscheduleCalls = []
    List scheduleCalls = []
    Map currentVals = [:]
    long nowMs = 1_000_000L
    def parent = null
    Map location = [timeZone: TimeZone.getDefault(), temperatureScale: "C"]
    MockLog log
    MockSocket socket
    def interfaces
    MockDevice device
    Closure httpGetImpl = null

    HubBase() {
        log = new MockLog(logs)
        socket = new MockSocket(sent)
        interfaces = [rawSocket: socket]
        device = new MockDevice(currentVals)
    }

    long now() { nowMs }

    // Groovy 2.4's Script.setProperty/getProperty use the binding even for declared fields,
    // so route real properties straight to the field to behave the same on every version.
    void setProperty(String name, Object value) {
        def mp = getMetaClass().hasProperty(this, name)
        if (mp != null && name != "binding" && name != "metaClass") mp.setProperty(this, value) else super.setProperty(name, value)
    }
    Object getProperty(String name) {
        def mp = getMetaClass().hasProperty(this, name)
        return (mp != null && name != "binding" && name != "metaClass") ? mp.getProperty(this) : super.getProperty(name)
    }

    // Hubitat converts to the hub's temperature scale; a hub set to Fahrenheit gets Fahrenheit.
    def convertTemperatureIfNeeded(def value, String scale, Integer precision) {
        BigDecimal v = value as BigDecimal
        if (location.temperatureScale == "F" && scale == "C") v = v * 9 / 5 + 32
        return v.setScale(precision, java.math.RoundingMode.HALF_UP)
    }
    void sendEvent(Map e) { events << e; currentVals[e.name] = e.value }
    void sendLocationEvent(Map e) { locationEvents << e }
    void runIn(Integer s, String h, Map o = null) { pending[h] = s; scheduleCalls << [h, s] }
    void schedule(String cron, String h) { scheduleCalls << [h, cron] }
    void unschedule() { pending.clear(); unscheduleCalls << "*" }
    void unschedule(String h) { pending.remove(h); unscheduleCalls << h }
    def methodMissing(String name, Object args) {
        if (name.startsWith("runEvery")) { scheduleCalls << [args[0], name]; return null }
        throw new MissingMethodException(name, this.class, args as Object[])
    }
    void httpGet(Map p, Closure c) {
        if (httpGetImpl == null) throw new IOException("no http")
        c.call(httpGetImpl.call(p))
    }

    // metadata / definition DSL (no-ops)
    void metadata(Closure c) { c.delegate = this; c() }
    void definition(Map m, Closure c = null) { if (c) { c.delegate = this; c() } }
    void capability(String n) {}
    void attribute(Object... a) {}
    void command(Object... a) {}
    void preferences(Closure c) { c.delegate = this; c() }
    void input(Map m) {}
    void page(Map m) {}

    // test helpers
    List warns() { logs.findAll { it.lvl == "warn" }*.msg }
    List errors() { logs.findAll { it.lvl == "error" }*.msg }
    String lastAttr(String n) { currentVals[n] }
}
