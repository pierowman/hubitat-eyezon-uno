// The app and the connection driver each carry their own copy of the range parser (Hubitat has no shared
// code between an app and a driver without libraries), so they must agree.

Map cases = [
    "1-3,7"       : [[1, 2, 3, 7], []],
    " 1 - 3 , 5 " : [[1, 2, 3, 5], []],
    "5"           : [[5], []],
    "5-5"         : [[5], []],
    "1,1,2"       : [[1, 2], []],
    "10,2,5-6"    : [[2, 5, 6, 10], []],
    "3-1"         : [[], ["3-1"]],
    "0,129"       : [[], ["0", "129"]],
    "abc,5"       : [[5], ["abc"]],
    "1-200"       : [[], ["1-200"]],
    "1,,2"        : [[1, 2], []],
    ""            : [[], []],
]
cases[null] = [[], []]   // a map literal would turn a null key into the string "null"

def app = k.newApp([:])[0]
def drv = k.newConn()

k.section("parseRange agrees between app and connection driver (1..128)") {
    cases.each { spec, want ->
        [app: app, driver: drv].each { String who, script ->
            Map got = script.parseRange(spec, 1, 128)
            k.check("${who}: '${spec}' => ${want[0]} invalid ${want[1]}", got.values == want[0] && got.invalid == want[1], got)
        }
    }
}

k.section("parseRange honours the bounds it is given") {
    [app: app, driver: drv].each { String who, script ->
        Map got = script.parseRange("1-9", 1, 8)
        k.check("${who}: partitions 1-9 with max 8 is invalid", got.values == [] && got.invalid == ["1-9"], got)
        k.check("${who}: partitions 1-8 is accepted", script.parseRange("1-8", 1, 8).values == (1..8).toList())
    }
}
