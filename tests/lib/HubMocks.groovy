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
    String dni, typeName, label
    List events = []
    Set caps = []
    List settingsUpdated = []
    int initialized = 0
    List alarmStates = []
    List calls = []
    List callArgs = []
    String getDeviceNetworkId() { dni }
    String getTypeName() { typeName }
    String getLabel() { label }
    void setLabel(String l) { label = l }
    void sendEvent(Map e) { events << e }
    boolean hasCapability(String c) { caps.contains(c) }
    void updateSetting(String n, Map v) { settingsUpdated << [n, v] }
    void initialize() { initialized++ }
    void setAlarmState(String s) { alarmStates << s }
    def currentValue(String attr) { last(attr) }
    def methodMissing(String name, args) { calls << name; callArgs << [name, args as List]; return null }
    def last(String attr) { events.reverse().find { it.name == attr }?.value }
}

// The app as seen by the drivers (their `parent`).
class MockApp {
    Map children = [:]
    List upserts = []
    List partitionStates = []          // [partition, status]
    Map partitionEvents = [:]          // partition -> events forwarded to that partition device
    List calls = []
    List callArgs = []
    boolean failPartitionNotify = false
    Map userNames = [:]     // user number -> name, as configured in the app
    Map zoneNames = [:]     // zone number -> label

    def userName(Integer user) { userNames[user] }
    def zoneName(Integer zone) { zoneNames[zone] }

    def getChildDevice(String dni) { children[dni] }
    def getChildDevices() { children.values() as List }
    def upsertDiscoveredZone(Integer z, String type, String name) {
        upserts << [z, type, name]
        String dni = "uno-zone-${z}"
        if (!children[dni]) children[dni] = new MockChild(dni: dni, typeName: "UNO Zone Contact", caps: ["ContactSensor"] as Set)
    }
    def notifyPartitionState(Integer p, String s) {
        if (failPartitionNotify) throw new IllegalStateException("boom")
        partitionStates << [p, s]
    }
    def notifyPartitionEvents(Integer p, List events) { partitionEvents.get(p, []).addAll(events) }
    def methodMissing(String n, a) { calls << n; callArgs << [n, a as List]; null }

    def partitionAttr(Integer p, String name) { partitionEvents[p]?.reverse()?.find { it.name == name }?.value }
}

class AppEnv {
    Map children = [:]
    List created = []
    List deleted = []
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
    Map location = [timeZone: TimeZone.getDefault()]
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
    void sendEvent(Map e) { events << e; currentVals[e.name] = e.value }
    void sendLocationEvent(Map e) { locationEvents << e }
    void runIn(Integer s, String h, Map o = null) { pending[h] = s; scheduleCalls << [h, s] }
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
    void attribute(String n, String t) {}
    void command(Object... a) {}
    void preferences(Closure c) { c.delegate = this; c() }
    void input(Map m) {}
    void page(Map m) {}

    // test helpers
    List warns() { logs.findAll { it.lvl == "warn" }*.msg }
    List errors() { logs.findAll { it.lvl == "error" }*.msg }
    String lastAttr(String n) { currentVals[n] }
}
