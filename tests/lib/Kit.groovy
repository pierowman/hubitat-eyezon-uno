import org.codehaus.groovy.control.CompilerConfiguration

// Shared helpers for the test cases: assertions plus factories that load the real scripts with mocks.
class Kit {
    File repo
    GroovyClassLoader loader
    boolean verbose = false
    int passed = 0
    List<String> failures = []
    int sectionPassed = 0
    int sectionFailed = 0

    Kit(File repo, GroovyClassLoader loader) {
        this.repo = repo
        this.loader = loader
    }

    boolean check(String name, boolean cond, Object detail = null) {
        if (cond) {
            passed++
            sectionPassed++
            if (verbose) println "  PASS  ${name}"
        } else {
            sectionFailed++
            failures << (detail != null ? "${name}  [${detail}]" : name)
            println "  FAIL  ${name}" + (detail != null ? "  -> ${detail}" : "")
        }
        return cond
    }

    void section(String title, Closure body) {
        sectionPassed = 0
        sectionFailed = 0
        if (verbose) println "\n== ${title}"
        try {
            body.call()
        } catch (Throwable t) {
            check("${title}: unexpected exception", false, t.toString())
            t.printStackTrace()
        }
        println "${sectionFailed ? 'FAIL' : 'ok  '}  ${title}  (${sectionPassed} passed${sectionFailed ? ', ' + sectionFailed + ' failed' : ''})"
    }

    // Loads a script from the repo with the Hubitat mocks as its base class.
    HubBase load(String rel) {
        def cc = new CompilerConfiguration()
        cc.scriptBaseClass = "HubBase"
        def shell = new GroovyShell(loader, new Binding(), cc)
        HubBase script = (HubBase) shell.parse(new File(repo, rel))
        script.run()
        return script
    }

    static String hex(String s) { s.getBytes("US-ASCII").encodeHex().toString().toUpperCase() }

    // ---- connection driver ----
    HubBase newConn(Map over = [:]) {
        HubBase d = load("drivers/UNOConnectionDriver.groovy")
        d.settings.putAll([ip: "1.2.3.4", port: 4025, password: "pw", zoneCount: 27, useRestMetadata: false,
                           heartbeatMinutes: "1", watchdogMinutes: "3", debugLogging: true] + over)
        d.parent = new MockApp()
        return d
    }

    void login(HubBase d) {
        d.initialize()
        d.parse(hex("Login:"))
        d.parse(hex("OK\r\n"))
    }

    // A connection driver that has already logged in, with the login traffic (and whatever it queued) cleared.
    HubBase authed(Map over = [:]) {
        def d = newConn(over)
        login(d)
        d.sent.clear()
        d.state.commandInFlight = null
        d.state.commandQueue = []
        return d
    }

    void feed(HubBase d, String text) { d.parse(hex(text)) }

    // Acknowledges whatever command is currently in flight (the panel's ^CC,0$ reply).
    void ack(HubBase d, String cc, String code = "0") { feed(d, "^${cc},${code}\$") }

    // ---- app ----
    // Returns [script, env]; env records the child devices the app creates and deletes.
    List newApp(Map settings, Map restMeta = null) {
        HubBase a = load("apps/UNOIntegrationApp.groovy")
        AppEnv env = new AppEnv()
        a.metaClass.getChildDevice = { String dni -> env.children[dni] }
        a.metaClass.getChildDevices = { -> env.children.values() as List }
        a.metaClass.deleteChildDevice = { String dni -> env.deleted << dni; env.children.remove(dni) }
        a.metaClass.addChildDevice = { String ns, String type, String dni, Map props ->
            env.created << [dni, type]
            def c = new MockChild(dni: dni, typeName: type, label: props.label, name: props.name)
            env.children[dni] = c
            return c
        }
        a.settings.putAll([ip: "1.2.3.4", port: 4025, password: "pw", zoneCount: 5,
                           useRestMetadata: false, autoCreateConfiguredZones: true, defaultUnknownZoneType: "contact"] + settings)
        if (restMeta != null) { a.httpGetImpl = { Map p -> [status: 200, data: restMeta] } }
        return [a, env]
    }
}
