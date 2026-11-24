// Sets the release version everywhere it is written, so it can't be missed in one place.
//
//   groovy tests/bump-version.groovy 1.4.3
//
// The version lives in packageManifest.json (that is what the Hubitat Package Manager compares to offer an update).
// The app and the connection driver also report it (appVersion() and driverVersion()), because a Hubitat script can't
// read the manifest or share a constant with another script. This changes all three and the release date. The tests
// fail if they ever disagree. You still write the release notes.

String version = args ? args[0] : null
if (!(version ==~ /\d+\.\d+\.\d+/)) {
    println "Usage: groovy tests/bump-version.groovy <x.y.z>"
    System.exit(2)
}

File repo = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile.parentFile
String today = new Date().format("yyyy-MM-dd")

// Reads and writes the file as it is (line endings included) so only the version changes.
def edit = { String path, Closure change ->
    File f = new File(repo, path)
    String before = f.getText("UTF-8")
    String after = change(before)
    if (after == before) {
        println "  ${path}: already ${version}"
    } else {
        f.setText(after, "UTF-8")
        println "  ${path}: updated"
    }
}

println "Setting version ${version} (${today})"

edit("packageManifest.json") { String text ->
    text.replaceFirst(/("version"\s*:\s*")[^"]+(")/, "\$1${version}\$2").replaceFirst(/("dateReleased"\s*:\s*")[^"]+(")/, "\$1${today}\$2")
}
[["apps/UNOIntegrationApp.groovy", "appVersion"], ["drivers/UNOConnectionDriver.groovy", "driverVersion"]].each { List pair ->
    edit(pair[0] as String) { String text ->
        text.replaceFirst(/(String ${pair[1]}\(\)\s*\{\s*return ")[^"]+(")/, "\$1${version}\$2")
    }
}

boolean noted = new groovy.json.JsonSlurper().parse(new File(repo, "packageManifest.json")).releaseNotes.toString().contains(version)
println noted ? "Release notes already mention ${version}." : "Remember to add a '${version}: ...' entry to the releaseNotes in packageManifest.json (the tests fail until it is there)."
