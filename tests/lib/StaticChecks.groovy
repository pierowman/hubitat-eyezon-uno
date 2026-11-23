import groovy.json.JsonSlurper
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.control.SourceUnit

// Checks that don't need the mock runtime: syntax, misspelled calls and package metadata.
class StaticChecks {

    // Hubitat-provided names that legitimately have no definition in our files.
    static final Set HUBITAT_API = ["sendEvent", "sendLocationEvent", "runIn", "runEvery1Minute", "runEvery5Minutes",
        "runEvery10Minutes", "runEvery30Minutes", "unschedule", "schedule", "now", "httpGet", "addChildDevice",
        "getChildDevice", "getChildDevices", "deleteChildDevice", "definition", "preferences", "page", "dynamicPage",
        "section", "input", "paragraph", "metadata", "capability", "attribute", "command", "println", "convertTemperatureIfNeeded"] as Set

    static List<File> groovyFiles(File repo) {
        List<File> files = []
        ["apps", "drivers"].each { dir ->
            new File(repo, dir).listFiles()?.findAll { it.name.endsWith(".groovy") }?.sort { it.name }?.each { files << it }
        }
        return files
    }

    static void run(Kit k) {
        List<File> files = groovyFiles(k.repo)

        k.section("Static: every file compiles") {
            files.each { File f ->
                try {
                    def cu = new CompilationUnit()
                    cu.addSource(f)
                    // CONVERSION builds the AST, which catches syntax errors without resolving Hubitat-only APIs.
                    cu.compile(Phases.CONVERSION)
                    k.check("syntax ${f.name}", true)
                } catch (Throwable e) {
                    k.check("syntax ${f.name}", false, e.message)
                }
            }
        }

        k.section("Static: no undefined method calls") {
            files.each { File f ->
                Map result = undefinedCalls(f)
                k.check("undefined calls in ${f.name}", result.unknown.isEmpty(), result.unknown)
            }
        }

        k.section("Static: package manifests") {
            def manifest = parseJson(k, new File(k.repo, "packageManifest.json"))
            parseJson(k, new File(k.repo, "repository.json"))
            if (manifest) {
                List entries = (manifest.apps ?: []) + (manifest.drivers ?: [])
                Set listed = [] as Set
                entries.each { Map entry ->
                    String loc = entry.location
                    String rel = loc.substring(loc.indexOf("/main/") + 6)
                    listed << rel
                    k.check("manifest entry exists: ${rel}", new File(k.repo, rel).isFile())
                    k.check("manifest entry has no placeholder: ${entry.name}", !loc.contains("<") && !loc.contains(">"))
                    // Each file's importUrl lets the hub update it in place, so it must point at the same file.
                    String source = new File(k.repo, rel).isFile() ? new File(k.repo, rel).text : ""
                    k.check("${rel} has an importUrl matching the manifest", source.contains("importUrl: \"${loc}\""), loc)
                }
                files.each { File f ->
                    String rel = f.parentFile.name + "/" + f.name
                    k.check("${rel} is listed in packageManifest.json", listed.contains(rel))
                }
                k.check("licenseFile has no placeholder", !manifest.licenseFile.toString().contains("<"))
            }
        }
    }

    static def parseJson(Kit k, File f) {
        try {
            def parsed = new JsonSlurper().parse(f)
            k.check("${f.name} is valid JSON", true)
            return parsed
        } catch (Throwable e) {
            k.check("${f.name} is valid JSON", false, e.message)
            return null
        }
    }

    // Implicit-this calls (name(...)) that are neither defined in the file nor provided by Hubitat.
    static Map undefinedCalls(File f) {
        def cu = new CompilationUnit()
        cu.addSource(f)
        cu.compile(Phases.SEMANTIC_ANALYSIS)
        def scriptClass = cu.AST.modules[0].classes.find { it.script }

        Set defined = scriptClass.methods*.name as Set
        Map callsites = [:]
        def visitor = new ClassCodeVisitorSupport() {
            SourceUnit getSourceUnit() { null }
            void visitMethodCallExpression(MethodCallExpression call) {
                if (call.implicitThis && call.methodAsString && call.lineNumber > 0) {
                    callsites.get(call.methodAsString, new TreeSet()) << call.lineNumber
                }
                super.visitMethodCallExpression(call)
            }
        }
        scriptClass.methods.each { it.code?.visit(visitor) }

        List unknown = callsites.keySet().findAll { !(it in defined) && !(it in HUBITAT_API) }.collect { "${it} (lines ${callsites[it]})".toString() }
        return [unknown: unknown, defined: defined]
    }
}
