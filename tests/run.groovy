import groovy.transform.Field

// Test runner for the Hubitat UNO integration.
//
//   groovy tests/run.groovy            run everything, print failures and a per-section summary
//   groovy tests/run.groovy -v         also print every passing check
//   groovy tests/run.groovy app conn   only run case files whose name contains "app" or "conn"
//
// It loads the real driver/app scripts with mocked Hubitat APIs (see tests/lib/HubMocks.groovy).

@Field String HEX_SRC = '''
package hubitat.helper
class HexUtils {
    static byte[] hexStringToByteArray(String s) {
        if (s.length() % 2 != 0) throw new NumberFormatException("odd length")
        byte[] out = new byte[s.length() / 2]
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16)
        }
        return out
    }
}
'''

File testsDir = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile
File repo = testsDir.parentFile

def loader = new GroovyClassLoader(this.class.classLoader)
loader.parseClass(HEX_SRC)   // Hubitat's hubitat.helper.HexUtils, used by the connection driver
["HubMocks", "Kit", "StaticChecks"].each { loader.parseClass(new File(testsDir, "lib/${it}.groovy")) }

def kit = loader.loadClass("Kit").getConstructor(File, GroovyClassLoader).newInstance(repo, loader)
kit.verbose = args.contains("-v")
List<String> filters = args.findAll { !it.startsWith("-") } as List

List<File> caseFiles = new File(testsDir, "cases").listFiles().findAll { it.name.endsWith(".groovy") }.sort { it.name }
if (filters) caseFiles = caseFiles.findAll { f -> filters.any { f.name.contains(it) } }

caseFiles.each { File f ->
    println "\n##### ${f.name}"
    try {
        new GroovyShell(loader, new Binding([k: kit])).evaluate(f)
    } catch (Throwable t) {
        kit.failures << "${f.name} crashed: ${t.message?.readLines()?.take(3)?.join(' | ')}".toString()
        println "CRASH ${f.name}: ${t.message?.readLines()?.take(6)?.join('\n')}"
        if (kit.verbose) t.printStackTrace()
    }
}

println "\n=========================================="
println "PASSED: ${kit.passed}   FAILED: ${kit.failures.size()}"
kit.failures.each { println "  - ${it}" }
System.exit(kit.failures ? 1 : 0)
