// Syntax-only check: parses every app/driver with the running Groovy.
// CI runs it on Groovy 2.4 (the line Hubitat uses) to catch newer-Groovy-only syntax.
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.Phases

def root = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile.parentFile
def files = []
['apps', 'drivers'].each { dir ->
    new File(root, dir).eachFileMatch(~/.*\.groovy/) { files << it }
}

def failures = 0
files.sort { it.name }.each { f ->
    try {
        def unit = new CompilationUnit()
        unit.addSource(f)
        unit.compile(Phases.CONVERSION)
        println "ok    ${f.name}"
    } catch (Throwable t) {
        failures++
        println "FAIL  ${f.name}: ${t.message}"
    }
}
println "Groovy ${GroovySystem.version}: ${files.size() - failures}/${files.size()} files parsed"
System.exit(failures ? 1 : 0)
