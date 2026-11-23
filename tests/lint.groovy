// Static analysis of the apps and drivers with CodeNarc. Needs Groovy 4 and Java 11+, and downloads CodeNarc the first
// time it runs (CI does this on Groovy 4 only: CodeNarc itself doesn't run on Groovy 2.4).
//
//   groovy tests/lint.groovy            check apps/ and drivers/ against tests/codenarc.groovy
//   groovy tests/lint.groovy -v         also show the rule documentation links
//
// The exit code is non-zero if anything is found.
@Grab('org.codenarc:CodeNarc:3.7.0-groovy-4.0')
@Grab('org.slf4j:slf4j-simple:2.0.13')
// The Groovy modules CodeNarc needs are part of the Groovy distribution that is already running this script.
@GrabExclude(group = 'org.apache.groovy', module = '*')
import org.codenarc.CodeNarcRunner
import org.codenarc.analyzer.FilesystemSourceAnalyzer
import org.codenarc.report.TextReportWriter
import org.codenarc.results.Results

File repo = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile.parentFile
// Optionally name another ruleset to try: groovy tests/lint.groovy path/to/rules.groovy
File rules = args ? new File(args[0]).absoluteFile : new File(repo, 'tests/codenarc.groovy')

def analyzer = new FilesystemSourceAnalyzer(baseDirectory: repo.absolutePath, includes: 'apps/*.groovy,drivers/*.groovy')
def runner = new CodeNarcRunner(
    sourceAnalyzer: analyzer,
    ruleSetFiles: "file:${rules.toURI().path}",
    reportWriters: [new TextReportWriter(writeToStandardOut: true, maxPriority: 3)]
)
Results results = runner.execute()

int files = results.getTotalNumberOfFiles(true)
int violations = results.getNumberOfViolationsWithPriority(1, true) +
        results.getNumberOfViolationsWithPriority(2, true) +
        results.getNumberOfViolationsWithPriority(3, true)
println "CodeNarc: ${violations} violation(s) in ${files} file(s)"

// A pattern that matches nothing would "pass" without checking anything.
int expected = new File(repo, 'apps').listFiles().count { it.name.endsWith('.groovy') } + new File(repo, 'drivers').listFiles().count { it.name.endsWith('.groovy') }
if (files != expected) {
    println "ERROR: expected to check ${expected} files but checked ${files}"
    System.exit(2)
}
System.exit(violations ? 1 : 0)
