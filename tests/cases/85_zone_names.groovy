// Zone names from the UNO, renaming them in the app, and keeping names changed on the Devices page. The app says what
// each zone is called; the connection device, which owns the zone devices, applies it.

def meta = { List names -> ["zone labels": names, "partition labels": []] }
def labels = { conn -> conn.host.children.collectEntries { String dni, c -> [(dni): c.label] }.findAll { it.key.startsWith("uno-zone-") } }

k.section("The UNO's names are used when the zones are created") {
    def (a, conn, env) = k.newPair([zoneCount: 2, useRestMetadata: true], meta(["Basement Back Do", "Foyer Motion Sen"]))
    conn.save()
    k.check("each zone gets its stored name", labels(conn) == ["uno-zone-1": "Basement Back Do", "uno-zone-2": "Foyer Motion Sen"], labels(conn))
    k.check("a name with 'motion' in it makes a motion zone", conn.host.children["uno-zone-2"].typeName == "UNO Zone Motion", conn.host.children["uno-zone-2"].typeName)
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
    def (a, conn, env) = k.newPair([zoneCount: 2, useRestMetadata: true, zoneName_1: "Basement Back Door", zoneHints: "1:From Hints:contact"], meta(["Basement Back Do", "Foyer Motion Sen"]))
    conn.save()
    k.check("it beats both the UNO's name and the hint", labels(conn)["uno-zone-1"] == "Basement Back Door", labels(conn))
    k.check("a zone with no name typed keeps the UNO's", labels(conn)["uno-zone-2"] == "Foyer Motion Sen", labels(conn))

    a.settings.zoneName_1 = "  "
    conn.save()
    k.check("blank means the hint then", labels(conn)["uno-zone-1"] == "From Hints", labels(conn))

    a.settings.zoneHints = ""
    conn.save()
    k.check("and then the UNO's name", labels(conn)["uno-zone-1"] == "Basement Back Do", labels(conn))
}

k.section("A type chosen for a zone wins") {
    def (a, conn, env) = k.newPair([zoneCount: 3, useRestMetadata: true, zoneType_1: "smoke", zoneType_2: "auto", zoneType_3: "water"],
                                   meta(["Hall Thing", "Foyer Motion Sen", "Kitchen Thing"]))
    conn.save()
    Map kids = conn.host.children
    k.check("smoke and water as chosen", kids["uno-zone-1"].typeName == "UNO Zone Smoke" && kids["uno-zone-3"].typeName == "UNO Zone Water", [kids["uno-zone-1"].typeName, kids["uno-zone-3"].typeName])
    k.check("automatic still reads the name", kids["uno-zone-2"].typeName == "UNO Zone Motion", kids["uno-zone-2"].typeName)

    a.settings.zoneType_1 = "motion"
    conn.save()
    k.check("changing it swaps the driver", conn.host.children["uno-zone-1"].typeName == "UNO Zone Motion", conn.host.children["uno-zone-1"].typeName)
}

k.section("A name changed on the Devices page is kept") {
    def (a, conn, env) = k.newPair([zoneCount: 2, useRestMetadata: true], meta(["Basement Back Do", "Foyer Motion Sen"]))
    conn.save()
    conn.host.children["uno-zone-1"].label = "Back Door"
    conn.save()
    k.check("saving the app does not put the UNO's name back", labels(conn)["uno-zone-1"] == "Back Door", labels(conn))
    k.check("a zone nobody renamed is unchanged", labels(conn)["uno-zone-2"] == "Foyer Motion Sen", labels(conn))

    a.settings.zoneName_1 = "Basement Back Door"
    conn.save()
    k.check("a name typed in the app still takes over", labels(conn)["uno-zone-1"] == "Basement Back Door", labels(conn))
}

k.section("A change of name on the UNO follows through unless the label was changed by hand") {
    def (a, conn, env) = k.newPair([zoneCount: 2, useRestMetadata: true], meta(["Old Name One", "Old Name Two"]))
    conn.save()
    conn.host.children["uno-zone-2"].label = "My Name"

    a.httpGetImpl = { Map p -> [status: 200, data: meta(["New Name One", "New Name Two"])] }
    conn.save()
    k.check("an untouched zone gets the new name", labels(conn)["uno-zone-1"] == "New Name One", labels(conn))
    k.check("a renamed one keeps its own", labels(conn)["uno-zone-2"] == "My Name", labels(conn))
}

k.section("A name that is just the UNO's own is not a choice") {
    def (a, conn, env) = k.newPair([zoneCount: 1, useRestMetadata: true, zoneName_1: "Old Name", zoneHints: "1:From Hints:contact"], meta(["Old Name"]))
    conn.save()
    k.check("a pre-filled box (saved as if typed) doesn't hide the hint", labels(conn)["uno-zone-1"] == "From Hints", labels(conn))

    a.settings.zoneHints = ""
    a.httpGetImpl = { Map p -> [status: 200, data: meta(["Renamed On UNO"])] }
    a.settings.zoneName_1 = "Renamed On UNO"
    conn.save()
    k.check("and the UNO's name is followed", labels(conn)["uno-zone-1"] == "Renamed On UNO", labels(conn))

    a.httpGetImpl = { Map p -> [status: 200, data: meta(["Renamed Again"])] }
    a.settings.zoneName_1 = null
    conn.save()
    k.check("and again when the box is empty", labels(conn)["uno-zone-1"] == "Renamed Again", labels(conn))
}

k.section("A zone that was created before the UNO's names were known gets its name") {
    def (a, conn, env) = k.newPair([zoneCount: 2, useRestMetadata: false])
    conn.save()
    k.check("it starts with the default", labels(conn)["uno-zone-1"] == "Zone 1", labels(conn))

    a.state.restZoneLabels = ["1": "Front Door"]
    conn.save()
    k.check("and takes the name once it is loaded", labels(conn)["uno-zone-1"] == "Front Door", labels(conn))
}

k.section("The prefix works with all of it") {
    def (a, conn, env) = k.newPair([zoneCount: 2, useRestMetadata: true, devicePrefix: "Alarm", zoneName_2: "Side Door"], meta(["Front", "Foyer Motion Sen"]))
    conn.save()
    k.check("UNO names and typed names are both prefixed", labels(conn) == ["uno-zone-1": "Alarm Front", "uno-zone-2": "Alarm Side Door"], labels(conn))

    conn.host.children["uno-zone-1"].label = "Alarm Porch"
    conn.save()
    k.check("a hand-made name is kept with the prefix, once", labels(conn)["uno-zone-1"] == "Alarm Porch", labels(conn))
}

k.section("Saving again writes nothing new") {
    def (a, conn, env) = k.newPair([zoneCount: 3, useRestMetadata: true], meta(["A", "B", "C"]))
    conn.save()
    def before = conn.state.appliedLabels
    conn.host.created.clear()
    conn.save()
    k.check("the remembered names are the same", conn.state.appliedLabels == before && before == ["1": "A", "2": "B", "3": "C"], conn.state.appliedLabels)
    k.check("and no device was created or deleted the second time", conn.host.created.isEmpty() && conn.host.deleted.isEmpty(), [conn.host.created, conn.host.deleted])
}

k.section("The settings page offers a name and type for each configured zone") {
    def (a, env) = k.newApp([zoneCount: 5, zoneSet: "1-2,5", useRestMetadata: false], meta(["Basement Back Do", "Foyer Motion Sen"]))
    def inputs = [:]
    a.metaClass.dynamicPage = { Map m, Closure c -> c() }
    def titles = []
    a.metaClass.section = { Object... args ->
        titles << args.find { it instanceof CharSequence }.toString().replaceAll("<[^>]+>", "")
        (args.last() as Closure).call()
    }
    a.metaClass.paragraph = { Object... args -> null }
    // Hubitat's input takes its named arguments (a Map) first, then the name and type: a Map given last doesn't match
    // and the page fails to open on a real hub. The stand-in refuses that shape too.
    a.metaClass.input = { Object... args ->
        if (!(args[0] instanceof Map)) throw new MissingMethodException("input", delegate.getClass(), args)
        inputs[args.find { it instanceof CharSequence }?.toString() ?: args[0].name.toString()] = args[0]
    }
    a.appButtonHandler("loadZoneNames")
    a.mainPage()

    k.check("one name and one type per zone in the zone set, and none for the rest",
        inputs.keySet().findAll { it.startsWith("zoneName_") || it.startsWith("zoneType_") }.sort() == ["zoneName_1", "zoneName_2", "zoneName_5", "zoneType_1", "zoneType_2", "zoneType_5"], inputs.keySet())
    k.check("each name box is pre-filled with the UNO's name, with no 'UNO name:' label", inputs["zoneName_1"].defaultValue == "Basement Back Do" && inputs["zoneName_2"].defaultValue == "Foyer Motion Sen" && inputs["zoneName_1"].description == null, [inputs["zoneName_1"]])
    k.check("a zone the UNO has no name for is left empty", inputs["zoneName_5"].defaultValue == null && inputs["zoneName_5"].description == "No name stored on the UNO", inputs["zoneName_5"])
    k.check("the button is on the page", inputs.containsKey("loadZoneNames"))

    // The page is long, so each section is a titled, numbered bar.
    k.check("the sections are in order, each with a plain title", titles == ["Status", "1. Connect to your UNO", "2. Names and startup state from the UNO (optional)", "3. Partitions", "4. Zones to track",
        "5. Zone names and types (optional)", "6. Zone hints (the older way to name zones, optional)", "7. Name prefix for partitions and zones (optional)", "8. Users (optional)", "9. PGM outputs (optional)",
        "10. Status device and stale sensors (optional)", "11. Notifications (optional)", "12. Safety: panic commands and arming codes", "13. Connection tuning and logging"], titles)
    k.check("the numbers run without a gap", titles.drop(1).withIndex().every { String t, int i -> t.startsWith("${i + 1}. ") }, titles)
    k.check("a title is drawn as a bar, and a section hint as grey text", a.heading("X").contains("background-color") && a.hint("Y").contains("color:#666666"))
}
