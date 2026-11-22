// Connection driver: frames sent by the UNO (spec section 3.3).

k.section("Zone bitmap (command 01)") {
    def d = k.authed(zoneCount: 128)
    k.feed(d, "%01,01000000000000800000000000000000\$")   // spec example: zones 1 and 64 open
    MockApp app = d.parent
    k.check("zone 1 open", app.children["uno-zone-1"]?.last("contact") == "open")
    k.check("zone 64 open (spec example)", app.children["uno-zone-64"]?.last("contact") == "open")
    k.check("zone 2 closed", app.children["uno-zone-2"]?.last("contact") == "closed")
    k.check("location events emitted", d.locationEvents.any { it.value == "1:open" } && d.locationEvents.any { it.value == "64:open" })
    d.locationEvents.clear()
    k.feed(d, "%01,00000000000000000000000000000000\$")
    k.check("only changed zones re-emitted", d.locationEvents*.value.sort() == ["1:closed", "64:closed"], d.locationEvents*.value)
    k.check("zone 1 closed again", app.children["uno-zone-1"].last("contact") == "closed")

    def c = k.authed(zoneCount: 27)
    k.feed(c, "%01,01000000000000800000000000000000\$")
    k.check("zones above configured count ignored", c.parent.children["uno-zone-64"] == null && c.parent.children["uno-zone-27"] != null)
    k.check("device lookup creates each zone once", c.parent.upserts.size() == 27, c.parent.upserts.size())
    c.parent.upserts.clear()
    k.feed(c, "%01,00000000000000000000000000000000\$")
    k.check("steady-state events don't call upsert", c.parent.upserts.isEmpty(), c.parent.upserts)

    def m = k.authed()
    k.feed(m, "%01,ZZ\$")
    k.check("malformed bitmap ignored with a warning, state untouched",
            m.warns().any { it.contains("malformed zone bitmap") } && m.state.lastZoneBitmap == null, m.warns())
}

k.section("Zone last-fault timestamp") {
    def d = k.authed(zoneCount: 4)
    k.feed(d, "%01,01000000000000000000000000000000\$")
    def z1 = d.parent.children["uno-zone-1"]
    def z2 = d.parent.children["uno-zone-2"]
    k.check("opening a zone records lastFault", z1.last("lastFault") ==~ /\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}/, z1.last("lastFault"))
    k.check("a zone that never opened has no lastFault", z2.last("lastFault") == null)
    int before = z1.events.count { it.name == "lastFault" }
    k.feed(d, "%01,00000000000000000000000000000000\$")
    k.check("closing a zone doesn't touch lastFault", z1.events.count { it.name == "lastFault" } == before)
}

k.section("Zone set (e.g. 1-3,5)") {
    def d = k.authed(zoneSet: "1-3,5", zoneCount: 27)
    k.feed(d, "%01,FF" + ("0" * 30) + "\$")   // zones 1-8 open; only 1,2,3 and 5 are configured
    k.check("only configured zones get devices", d.parent.children.keySet().sort() == ["uno-zone-1", "uno-zone-2", "uno-zone-3", "uno-zone-5"], d.parent.children.keySet())
    k.check("zone 4 (not in set) ignored", d.parent.children["uno-zone-4"] == null)
    k.feed(d, "%03,1130010040\$")
    k.check("CID for a zone outside the set ignored", d.parent.children["uno-zone-4"] == null)
    k.feed(d, "%03,1130010050\$")
    k.check("CID for a zone inside the set handled", d.parent.children["uno-zone-5"]?.last("contact") == "open")

    def bad = k.newConn(zoneSet: "abc,200,3-1", zoneCount: 4)
    bad.initialize()
    k.check("invalid zone set entries are reported", bad.warns().any { it.contains("invalid zone set entries") }, bad.warns())
    k.check("falls back to zone count when nothing is valid", bad.warns().any { it.contains("using zone count 4") }, bad.warns())
    k.check("fallback zones are 1..count", bad.configuredZones() == [1, 2, 3, 4], bad.configuredZones())
}

k.section("Partition status (command 02, spec 3.4)") {
    def d = k.authed()
    Map expected = ["00": "unused", "01": "disarmed", "02": "readyBypassed", "03": "notReady", "04": "armedStay",
                    "05": "armedAway", "08": "exitDelay", "09": "armedAwayZeroEntry", "0C": "entryDelay", "11": "alarm",
                    "06": "busy", "07": "busy", "20": "busy"]
    expected.each { code, want ->
        def one = k.authed()
        k.feed(one, "%02,${code}00000000000000\$")
        k.check("partition code ${code} => ${want}", one.parent.partitionStates.find { it[0] == 1 } == [1, want], one.parent.partitionStates)
    }

    def m = k.authed()
    k.feed(m, "%02,0104000000000000\$")
    k.check("every partition byte is decoded", m.parent.partitionStates.contains([1, "disarmed"]) && m.parent.partitionStates.contains([2, "armedStay"]) && m.parent.partitionStates.contains([3, "unused"]), m.parent.partitionStates)
    k.check("connection attribute mirrors partition 1 only", m.lastAttr("alarmSystemStatus") == "disarmed")
    int before = m.parent.partitionStates.size()
    k.feed(m, "%02,0104000000000000\$")
    k.check("an unchanged frame forwards nothing", m.parent.partitionStates.size() == before)
    k.feed(m, "%02,0105000000000000\$")
    k.check("only the changed partition is forwarded", m.parent.partitionStates.size() == before + 1 && m.parent.partitionStates.last() == [2, "armedAway"], m.parent.partitionStates)
    k.check("partition 1 attribute unchanged by partition 2", m.lastAttr("alarmSystemStatus") == "disarmed")

    def all = k.authed()
    k.feed(all, "%02,0102030405080C11\$")
    k.check("all eight partitions decoded", all.parent.partitionStates*.get(0) == [1, 2, 3, 4, 5, 6, 7, 8], all.parent.partitionStates)

    def partial = k.authed()
    k.feed(partial, "%02,01\$")
    k.check("a short frame only updates the partitions it carries", partial.parent.partitionStates == [[1, "disarmed"]], partial.parent.partitionStates)

    def bad = k.authed()
    k.feed(bad, "%02,QQ\$")
    k.check("malformed partition frame ignored", bad.parent.partitionStates.isEmpty() && bad.warns().any { it.contains("malformed partition") })
}

k.section("Trouble bitfield (command 06, spec 3.5)") {
    def d = k.authed()
    k.feed(d, "%06,0000000000000000\$")
    k.check("no troubles => clear/none", d.lastAttr("troubleState") == "clear" && d.lastAttr("troubleDetail") == "none")
    k.check("acPower present when AC is fine", d.lastAttr("acPower") == "present")
    k.feed(d, "%06,0200000000000000\$")
    k.check("bit 2 => acFailure active", d.lastAttr("acFailure") == "active" && d.lastAttr("troubleState") == "trouble", d.currentVals)
    k.check("acPower failed on AC failure", d.lastAttr("acPower") == "failed")
    k.check("detail text lists AC failure", d.lastAttr("troubleDetail") == "AC failure", d.lastAttr("troubleDetail"))
    k.feed(d, "%06,0500000000000000\$")
    k.check("bits 1+3 => serviceRequired + lowBattery, acFailure cleared",
            d.lastAttr("serviceRequired") == "active" && d.lastAttr("lowBattery") == "active" && d.lastAttr("acFailure") == "clear")
    k.feed(d, "%06,FF00000000000000\$")
    k.check("all 8 bits active", ["serviceRequired", "acFailure", "lowBattery", "serverOffline", "zoneTrouble",
                                  "batteryOvercurrent", "sirenFault", "supervisoryFault"].every { d.lastAttr(it) == "active" })
    k.feed(d, "%06,0000000000000000\$")
    k.check("returns to clear", d.lastAttr("troubleState") == "clear" && d.lastAttr("sirenFault") == "clear")
    k.feed(d, "%06,00FF000000000000\$")
    k.check("partition 2's troubles don't change the connection attributes", d.lastAttr("troubleState") == "clear")

    def m = k.authed()
    k.feed(m, "%06,0200040000000000\$")
    k.check("partition 1 flags reach partition 1", m.parent.partitionAttr(1, "acFailure") == "active" && m.parent.partitionAttr(1, "troubleState") == "trouble")
    k.check("partition 2 is clear", m.parent.partitionAttr(2, "troubleState") == "clear")
    k.check("partition 3 flags reach partition 3", m.parent.partitionAttr(3, "lowBattery") == "active" && m.parent.partitionAttr(3, "troubleDetail") == "Wireless device low battery", m.parent.partitionEvents[3])
    int count = m.parent.partitionEvents[3].size()
    k.feed(m, "%06,0200040000000000\$")
    k.check("an unchanged frame forwards nothing", m.parent.partitionEvents[3].size() == count)
    k.feed(m, "%06,0200000000000000\$")
    k.check("clearing partition 3 is forwarded", m.parent.partitionAttr(3, "lowBattery") == "clear" && m.parent.partitionAttr(3, "troubleState") == "clear")

    def bad = k.authed()
    k.feed(bad, "%06,XYZ\$")
    k.check("malformed trouble frame ignored", bad.warns().any { it.contains("malformed trouble") })
}

k.section("Zone bypass state (command 04)") {
    def d = k.authed(zoneCount: 8)
    k.feed(d, "%01,00000000000000000000000000000000\$")   // creates the zone devices
    def z1 = d.parent.children["uno-zone-1"]
    def z3 = d.parent.children["uno-zone-3"]

    k.feed(d, "%04,05000000000000000000000000000000\$")   // zones 1 and 3 bypassed
    k.check("bypassed zones flagged", z1.last("bypassed") == "true" && z3.last("bypassed") == "true", z1.events)
    k.check("other zones reported as not bypassed", d.parent.children["uno-zone-2"].last("bypassed") == "false")
    int before = z1.events.count { it.name == "bypassed" }
    k.feed(d, "%04,05000000000000000000000000000000\$")
    k.check("unchanged bypass frame sends no events", z1.events.count { it.name == "bypassed" } == before)
    k.feed(d, "%04,01000000000000000000000000000000\$")
    k.check("un-bypassing zone 3 is reported", z3.last("bypassed") == "false" && z1.last("bypassed") == "true")

    def n = k.authed(zoneCount: 8)
    k.feed(n, "%04,01000000000000000000000000000000\$")
    k.check("bypass frames never create zone devices", n.parent.children.isEmpty() && n.parent.upserts.isEmpty())

    def bad = k.authed()
    k.feed(bad, "%04,NOPE\$")
    k.check("malformed bypass frame ignored", bad.warns().any { it.contains("malformed bypass") })
}

k.section("CID events (command 03)") {
    def d = k.authed(zoneCount: 27)
    k.feed(d, "%03,3441010020\$")   // spec example: restoral of stay arm by user 2
    k.check("arm/disarm (user id) does NOT create a zone", d.parent.upserts.isEmpty() && d.parent.children.isEmpty(), d.parent.upserts)
    k.check("lastCidEvent set", d.lastAttr("lastCidEvent")?.contains("code=441"), d.lastAttr("lastCidEvent"))
    k.feed(d, "%03,1401010990\$")
    k.check("user number 99 doesn't create zone 99", d.parent.children["uno-zone-99"] == null)
    k.feed(d, "%03,1130010050\$")
    k.check("burglary event opens zone 5", d.parent.children["uno-zone-5"]?.last("contact") == "open")
    k.feed(d, "%03,3130010050\$")
    k.check("restoral closes zone 5", d.parent.children["uno-zone-5"]?.last("contact") == "closed")
    k.feed(d, "%03,1110010070\$")
    k.check("fire code passes smoke suggestion to upsert", d.parent.upserts.any { it[0] == 7 && it[1] == "smoke" }, d.parent.upserts)
    k.feed(d, "%03,1130019990\$")
    k.check("zone above count ignored", d.parent.children["uno-zone-999"] == null)
    k.feed(d, "%03,12\$")
    k.check("short CID ignored without exception", d.warns().isEmpty())
}

k.section("Host information (command 05)") {
    def d = k.authed()
    k.feed(d, "%05,001C2A45F894,UNO, 01.00.117 - BETA,1,20250923183203\$")
    k.check("model/mac/time", d.lastAttr("panelModel") == "UNO" && d.lastAttr("panelMac") == "001C2A45F894" && d.lastAttr("panelTime") == "20250923183203", d.currentVals)
    k.check("firmware string", d.lastAttr("firmwareVersion") == "01.00.117 - BETA", d.lastAttr("firmwareVersion"))
    k.feed(d, "%05,001C2A45F894,UNO,01,00,117,1,20250923183203\$")
    k.check("comma-separated version kept whole", d.lastAttr("firmwareVersion") == "01,00,117", d.lastAttr("firmwareVersion"))
}

k.section("Chime frames (command 10)") {
    def d = k.authed()
    k.feed(d, "%10,01000005\$")
    k.check("partition 1 type 0 => no lastChime on the connection", d.lastAttr("lastChime") == null)
    k.check("partition 2 chime forwarded", d.parent.partitionAttr(2, "lastChime") == "type 1", d.parent.partitionEvents)
    k.check("partition 8 chime forwarded", d.parent.partitionAttr(8, "lastChime") == "type 5")
    k.feed(d, "%10,10000000\$")
    k.check("partition 1 chime sets lastChime", d.lastAttr("lastChime") == "type 1" && d.parent.partitionAttr(1, "lastChime") == "type 1")
}
