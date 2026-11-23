// Robustness: empty, malformed and missing settings, and missing state, must never crash setup or frame handling.
// Regression guard for a bug reported against envisalink_new, where an empty or invalid partition spec made a
// helper return None and the fallback then called .append() on it, so every zone stayed unavailable.

def bitmap = { List<Integer> zones ->
    byte[] b = new byte[16]
    zones.each { int z -> b[(z - 1).intdiv(8)] |= (1 << ((z - 1) % 8)) }
    b.collect { String.format("%02X", it & 0xFF) }.join()
}

// Range-style settings that are empty, whitespace, out of range, reversed or just junk.
def badSpecs = [null, "", "   ", "0", "9", "129", "abc", "1,,2", "-", "1-", "-3", "3-1", ",", "1;2", "1-2-3", "99999999999", "٣"]

k.section("App setup survives bad partition sets") {
    badSpecs.each { spec ->
        def (a, env) = k.newApp([zoneCount: 3, partitionSet: spec])
        boolean ok = true
        try { a.initialize() } catch (Throwable t) { ok = false; println "    partitionSet=${spec}: ${t}" }
        k.check("partitionSet ${spec == null ? 'null' : '\"' + spec + '\"'}: setup completes", ok)
        if (ok && !(spec in ["1;2", "1,,2"])) {
            // Unusable input falls back to partition 1 (and the zones still get created).
            boolean usable = spec != null && spec.replaceAll("\\s", "") ==~ /\d+(-\d+)?(,\d+(-\d+)?)*/ && !(spec in ["0", "9", "129", "3-1", "99999999999"])
            if (!usable) {
                k.check("  falls back to partition 1 and still creates the zones", env.children.containsKey("uno-partition-1") && env.children.keySet().findAll { it.startsWith("uno-partition-") }.size() == 1 && env.children.containsKey("uno-zone-3"), env.children.keySet())
            }
        }
    }
}

k.section("App setup survives bad zone, PGM and assignment settings") {
    badSpecs.each { spec ->
        [zoneSet: spec, pgmSet: spec, partitionZones: spec, notifyPartitions: spec].each { String name, def value ->
            def (a, env) = k.newApp([zoneCount: 3, (name): value])
            boolean ok = true
            try { a.initialize(); a.partitionZonesPreview(); a.notifyEvent([category: "alarm", text: "x", partition: 1, restore: false]) } catch (Throwable t) { ok = false; println "    ${name}=${value}: ${t}" }
            k.check("${name} ${value == null ? 'null' : '\"' + value + '\"'}: setup and preview complete", ok)
        }
    }

    [null, 0, -5, "abc", "", 999, 128.5].each { count ->
        def (a, env) = k.newApp([zoneCount: count])
        boolean ok = true
        try { a.initialize() } catch (Throwable t) { ok = false; println "    zoneCount=${count}: ${t}" }
        k.check("zoneCount ${count}: setup completes", ok)
    }

    def (z, envZ) = k.newApp([zoneCount: 4, partitionSet: "1", partitionZones: "1:\n:3\n1:1-2:3\n  \n;;"])
    boolean ok = true
    try { z.initialize() } catch (Throwable t) { ok = false }
    k.check("empty and half-written assignment lines are skipped", ok && z.parsePartitionZones(z.settings.partitionZones).assignments.isEmpty() && z.normalizedPartitionZones() == "", z.normalizedPartitionZones())
}

k.section("Driver handles bad settings") {
    badSpecs.each { spec ->
        [zoneSet: spec, partitionZones: spec].each { String name, def value ->
            boolean ok = true
            def d = null
            try {
                d = k.authed([(name): value])
                k.feed(d, "%01," + bitmap([1, 2]) + "\$")
                k.feed(d, "%04," + bitmap([2]) + "\$")
                d.debugDump()
            } catch (Throwable t) { ok = false; println "    ${name}=${value}: ${t}" }
            k.check("${name} ${value == null ? 'null' : '\"' + value + '\"'}: frames and debugDump are handled", ok && d.warns().every { !it.contains("Error handling frame") }, d?.warns())
        }
    }

    [null, 0, -5, "abc", "", 999].each { count ->
        boolean ok = true
        def d = null
        try { d = k.authed([zoneCount: count]); k.feed(d, "%01," + bitmap([1]) + "\$"); d.configuredZones() } catch (Throwable t) { ok = false; println "    zoneCount=${count}: ${t}" }
        k.check("zoneCount ${count}: the zone list is never empty or null", ok && d.configuredZones() && d.configuredZones().size() >= 1 && d.configuredZones().size() <= 128, d?.configuredZones()?.size())
    }

    [[keepaliveSeconds: null], [keepaliveSeconds: ""], [keepaliveSeconds: "-1"], [commandTimeoutSeconds: null], [commandTimeoutSeconds: "abc"], [watchdogMinutes: "zzz"], [heartbeatMinutes: "zzz"], [port: "abc"], [port: null]].each { Map s ->
        boolean ok = true
        try { def d = k.authed(s); d.minuteCheck(); d.armStay(); d.watchdogCheck(); d.commandTimeoutCheck() } catch (Throwable t) { ok = false; println "    ${s}: ${t}" }
        k.check("${s}: timers and commands still work", ok)
    }
}

k.section("Driver handles missing state (as after an upgrade, or a state reset)") {
    def d = k.authed(zoneCount: 5)
    ["partitionStatus", "troubleBytes", "zoneTemps", "partitionTemps", "commandQueue", "commandInFlight", "lastZoneBitmap", "lastBypassBitmap",
     "lastTroubleBitmap", "lastPartitionBitmap", "reconnectDelay", "rxBuffer", "health", "lastFrameTs", "authenticatedAt", "heartbeatTick", "offlineCheckDueAt"].each { d.state.remove(it) }

    boolean ok = true
    try {
        k.feed(d, "%01," + bitmap([1]) + "\$")
        k.feed(d, "%02,0100000000000000\$")
        k.feed(d, "%04," + bitmap([2]) + "\$")
        k.feed(d, "%06,0100000000000000\$")
        k.feed(d, "%07," + ("80" * 128) + "\$")
        k.feed(d, "%08," + ("50" * 16) + "\$")
        k.feed(d, "%03,1130010010\$")
        k.feed(d, "%10,01000000\$")
        k.feed(d, "%FF," + ("FFFF" * 128) + "\$")
        d.armStay(); d.disarm("1234"); d.bypassZone(2); d.minuteCheck(); d.watchdogCheck(); d.offlineCheck(); d.debugDump(); d.resyncStates()
        d.clearEventHistory(); d.expireQueue(); d.commandTimeoutCheck(); d.retryInFlight(); d.heartbeatCheck()
    } catch (Throwable t) { ok = false; println "    ${t}" }
    k.check("every handler and command copes with state that isn't there", ok)
    k.check("and no frame was reported as an error", d.warns().every { !it.contains("Error handling frame") }, d.warns())

    def n = k.authed()
    n.state.partitionStatus = null; n.state.troubleBytes = null
    boolean ok2 = true
    try { k.feed(n, "%02,0100000000000000\$"); k.feed(n, "%06,0200000000000000\$") } catch (Throwable t) { ok2 = false }
    k.check("state set to null (not just removed) is also fine", ok2 && n.state.partitionStatus["1"] == "disarmed", n.state.partitionStatus)
}

k.section("Frames for devices that don't exist yet") {
    // The app creates devices from its settings, and frames can arrive before (or without) them.
    def d = k.newConn(zoneCount: 5)
    d.parent = null
    d.initialize(); d.parse(k.hex("Login:")); d.parse(k.hex("OK\r\n"))
    boolean ok = true
    try {
        k.feed(d, "%01," + bitmap([1, 3]) + "\$")
        k.feed(d, "%02,0100000000000000\$")
        k.feed(d, "%03,1301010010\$")
        k.feed(d, "%06,0200000000000000\$")
        d.armStay(); d.debugDump()
    } catch (Throwable t) { ok = false; println "    ${t}" }
    k.check("with no parent app at all nothing crashes", ok && d.lastAttr("alarmSystemStatus") == "disarmed", d.warns())

    def (a, env) = k.newApp([zoneCount: 3, partitionSet: "1"])
    a.initialize()
    boolean ok2 = true
    try {
        (1..8).each { p -> a.notifyPartitionState(p, "armedAway"); a.notifyPartitionEvents(p, [[name: "lastEvent", value: "x"]]) }
        a.notifyPgmEvents(7, [[name: "lastTriggered", value: "now"]])
        a.notifyPanelSummary([openZones: "zone 1"])
        a.notifyHealth("online")
    } catch (Throwable t) { ok2 = false; println "    ${t}" }
    k.check("updates for partitions and PGMs that have no device are ignored", ok2)
    k.check("partition 1 still got its update", env.children["uno-partition-1"].alarmStates == ["armedAway"], env.children["uno-partition-1"].alarmStates)
}

k.section("Range parsers reject numbers too long to be valid (they used to throw)") {
    def (a, env) = k.newApp([zoneCount: 1])
    def d = k.newConn()
    [a, d].each { s ->
        String who = s.is(a) ? "app" : "driver"
        Map r = s.parseRange("99999999999", 1, 128)
        k.check("${who}: one huge number is invalid, not an exception", r.values == [] && r.invalid == ["99999999999"], r)
        Map m = s.parseRange("1,99999999999,3,5-99999999999,7", 1, 128)
        k.check("${who}: the rest of the list is still used", m.values == [1, 3, 7] && m.invalid == ["99999999999", "5-99999999999"], m)
        k.check("${who}: five digits is already out of range", s.parseRange("00001-00003", 1, 128).values == [] && s.parseRange("0001-0003", 1, 128).values == [1, 2, 3])
    }
}

k.section("A partition line with no usable zones is not an assignment") {
    def (a, env) = k.newApp([zoneCount: 4, partitionSet: "1-2"])
    Map p = a.parsePartitionZones("1:\n2:9\n1:abc")
    k.check("none of them create an assignment", p.assignments.isEmpty() && a.hasPartitionZones() == false, p)
    k.check("and each is reported", p.problems.any { it.contains("zone 9 is not one of your zones") } && p.problems.any { it.contains("partition 2 has no zones") || it.contains("partition 1 has no zones") } && p.problems.any { it.contains("not valid zone numbers: abc") }, p.problems)

    def d = k.newConn(partitionZones: "1:;2:99")
    k.check("the driver ignores them too", d.partitionZoneMap().isEmpty(), d.partitionZoneMap())
}
