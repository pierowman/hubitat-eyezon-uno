// Zone names from the UNO, renaming them in the app, and keeping names changed on the Devices page.

def meta = { List names -> ["zone labels": names, "partition labels": []] }
def labels = { env -> env.children.collectEntries { String dni, c -> [(dni): c.label] }.findAll { it.key.startsWith("uno-zone-") } }

k.section("The UNO's names are used when the zones are created") {
    def (a, env) = k.newApp([zoneCount: 2, useRestMetadata: true], meta(["Basement Back Do", "Foyer Motion Sen"]))
    a.initialize()
    k.check("each zone gets its stored name", labels(env) == ["uno-zone-1": "Basement Back Do", "uno-zone-2": "Foyer Motion Sen"], labels(env))
    k.check("a name with 'motion' in it makes a motion zone", env.children["uno-zone-2"].typeName == "UNO Zone Motion", env.children["uno-zone-2"].typeName)
}

k.section("The button loads the names before setup is finished") {
    def (a, env) = k.newApp([zoneCount: 2, useRestMetadata: false], meta(["Basement Back Do", "Foyer Motion Sen"]))
    k.check("before it, nothing is loaded", a.zoneNamesStatus().contains("No zone names loaded"), a.zoneNamesStatus())
    a.appButtonHandler("loadZoneNames")
    k.check("after it, the names are known", a.state.restZoneLabels == ["1": "Basement Back Do", "2": "Foyer Motion Sen"], a.state.restZoneLabels)
    k.check("and the page says how many", a.zoneNamesStatus().contains("2 zone name(s) loaded") && a.zoneNamesStatus().contains("REST: ok"), a.zoneNamesStatus())
    k.check("no devices were created by pressing it", env.created.isEmpty(), env.created)

    def (b, env2) = k.newApp([zoneCount: 1, ip: ""], meta(["X"]))
    b.appButtonHandler("loadZoneNames")
    k.check("without an address it says what is missing", b.zoneNamesStatus().contains("enter the IP address and password first"), b.zoneNamesStatus())

    def (c, env3) = k.newApp([zoneCount: 1])
    c.httpGetImpl = { Map p -> throw new IOException("no route") }
    c.appButtonHandler("loadZoneNames")
    k.check("an unreachable UNO is reported, not thrown", c.zoneNamesStatus().contains("unreachable"), c.zoneNamesStatus())

    def (d, env4) = k.newApp([zoneCount: 1])
    d.httpGetImpl = { Map p -> [status: 200, data: null] }
    d.appButtonHandler("loadZoneNames")
    k.check("an empty answer is reported", d.zoneNamesStatus().contains("no answer from the UNO"), d.zoneNamesStatus())

    a.state.restStatus = "marker"
    a.appButtonHandler("something else")
    k.check("other buttons are ignored", a.state.restStatus == "marker", a.state.restStatus)
}

k.section("A name typed for a zone wins, and clearing it goes back to the UNO's name") {
    def (a, env) = k.newApp([zoneCount: 2, useRestMetadata: true, zoneName_1: "Basement Back Door", zoneHints: "1:From Hints:contact"], meta(["Basement Back Do", "Foyer Motion Sen"]))
    a.initialize()
    k.check("it beats both the UNO's name and the hint", labels(env)["uno-zone-1"] == "Basement Back Door", labels(env))
    k.check("a zone with no name typed keeps the UNO's", labels(env)["uno-zone-2"] == "Foyer Motion Sen", labels(env))

    a.settings.zoneName_1 = "  "
    a.initialize()
    k.check("blank means the hint then", labels(env)["uno-zone-1"] == "From Hints", labels(env))

    a.settings.zoneHints = ""
    a.initialize()
    k.check("and then the UNO's name", labels(env)["uno-zone-1"] == "Basement Back Do", labels(env))
}

k.section("A type chosen for a zone wins") {
    def (a, env) = k.newApp([zoneCount: 3, useRestMetadata: true, zoneType_1: "smoke", zoneType_2: "auto", zoneType_3: "water"],
                            meta(["Hall Thing", "Foyer Motion Sen", "Kitchen Thing"]))
    a.initialize()
    k.check("smoke and water as chosen", env.children["uno-zone-1"].typeName == "UNO Zone Smoke" && env.children["uno-zone-3"].typeName == "UNO Zone Water", [env.children["uno-zone-1"].typeName, env.children["uno-zone-3"].typeName])
    k.check("automatic still reads the name", env.children["uno-zone-2"].typeName == "UNO Zone Motion", env.children["uno-zone-2"].typeName)

    a.settings.zoneType_1 = "motion"
    a.initialize()
    k.check("changing it swaps the driver", env.children["uno-zone-1"].typeName == "UNO Zone Motion", env.children["uno-zone-1"].typeName)
}

k.section("A name changed on the Devices page is kept") {
    def (a, env) = k.newApp([zoneCount: 2, useRestMetadata: true], meta(["Basement Back Do", "Foyer Motion Sen"]))
    a.initialize()
    env.children["uno-zone-1"].label = "Back Door"
    a.initialize()
    k.check("saving the app does not put the UNO's name back", labels(env)["uno-zone-1"] == "Back Door", labels(env))
    k.check("a zone nobody renamed is unchanged", labels(env)["uno-zone-2"] == "Foyer Motion Sen", labels(env))

    a.settings.zoneName_1 = "Basement Back Door"
    a.initialize()
    k.check("a name typed in the app still takes over", labels(env)["uno-zone-1"] == "Basement Back Door", labels(env))
}

k.section("A change of name on the UNO follows through unless the label was changed by hand") {
    def (a, env) = k.newApp([zoneCount: 2, useRestMetadata: true], meta(["Old Name One", "Old Name Two"]))
    a.initialize()
    env.children["uno-zone-2"].label = "My Name"

    a.httpGetImpl = { Map p -> [status: 200, data: meta(["New Name One", "New Name Two"])] }
    a.initialize()
    k.check("an untouched zone gets the new name", labels(env)["uno-zone-1"] == "New Name One", labels(env))
    k.check("a renamed one keeps its own", labels(env)["uno-zone-2"] == "My Name", labels(env))
}

k.section("A zone that was created before the UNO's names were known gets its name") {
    def (a, env) = k.newApp([zoneCount: 2, useRestMetadata: false])
    a.initialize()
    k.check("it starts with the default", labels(env)["uno-zone-1"] == "Zone 1", labels(env))

    a.state.restZoneLabels = ["1": "Front Door"]
    a.initialize()
    k.check("and takes the name once it is loaded", labels(env)["uno-zone-1"] == "Front Door", labels(env))
}

k.section("The prefix works with all of it") {
    def (a, env) = k.newApp([zoneCount: 2, useRestMetadata: true, devicePrefix: "Alarm", zoneName_2: "Side Door"], meta(["Front", "Foyer Motion Sen"]))
    a.initialize()
    k.check("UNO names and typed names are both prefixed", labels(env) == ["uno-zone-1": "Alarm Front", "uno-zone-2": "Alarm Side Door"], labels(env))

    env.children["uno-zone-1"].label = "Alarm Porch"
    a.initialize()
    k.check("a hand-made name is kept with the prefix, once", labels(env)["uno-zone-1"] == "Alarm Porch", labels(env))
}

k.section("Saving again writes nothing new") {
    def (a, env) = k.newApp([zoneCount: 3, useRestMetadata: true], meta(["A", "B", "C"]))
    a.initialize()
    def before = a.state.appliedLabels
    a.initialize()
    k.check("the remembered names are the same", a.state.appliedLabels == before && before == ["1": "A", "2": "B", "3": "C"], a.state.appliedLabels)
    k.check("and no device was created or deleted the second time", env.created.size() == 5 && env.deleted.isEmpty(), [env.created.size(), env.deleted])
}
k.section("The settings page offers a name and type for each configured zone") {
    def (a, env) = k.newApp([zoneCount: 5, zoneSet: "1-2,5", useRestMetadata: false], meta(["Basement Back Do", "Foyer Motion Sen"]))
    def inputs = [:]
    a.metaClass.dynamicPage = { Map m, Closure c -> c() }
    a.metaClass.section = { Object... args -> (args.last() as Closure).call() }
    a.metaClass.paragraph = { Object... args -> null }
    // Named arguments reach the method as a leading Map.
    a.metaClass.input = { Object... args -> inputs[args.find { it instanceof CharSequence }.toString()] = (args.find { it instanceof Map } ?: [:]) }
    a.appButtonHandler("loadZoneNames")
    a.mainPage()

    k.check("one name and one type per zone in the zone set, and none for the rest",
        inputs.keySet().findAll { it.startsWith("zoneName_") || it.startsWith("zoneType_") }.sort() == ["zoneName_1", "zoneName_2", "zoneName_5", "zoneType_1", "zoneType_2", "zoneType_5"], inputs.keySet())
    k.check("each shows the UNO's name", inputs["zoneName_1"].description == "UNO name: Basement Back Do" && inputs["zoneName_5"].description == "No name stored on the UNO", [inputs["zoneName_1"].description, inputs["zoneName_5"].description])
    k.check("the button is on the page", inputs.containsKey("loadZoneNames"))
}
