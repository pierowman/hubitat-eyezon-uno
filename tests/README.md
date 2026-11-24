# Tests

These tests load the real driver and app scripts with a mock Hubitat runtime, replay frames from the
EnvisaLink UNO TPI spec and exercise the app, so most behaviour can be checked without a hub.

## Running

You need Java 8+ and Groovy 2.4 or later (`winget install --id Apache.Groovy.4 -e` on Windows). CI runs Groovy 4.0 and Groovy 2.4.21, the line Hubitat uses; see `.github/workflows/tests.yml`.

```powershell
.\tests\run-tests.ps1            # Windows wrapper (finds Groovy for you)
groovy tests/run.groovy          # any platform, from the repository root
groovy tests/run.groovy -v       # also list every passing check
groovy tests/run.groovy app conn # only case files whose name contains "app" or "conn"
groovy tests/syntax-check.groovy # parse-only check of every app and driver
```

The exit code is non-zero if anything fails.

## Lint and spelling

Two checks run in CI alongside the tests (the `lint` job in `.github/workflows/tests.yml`). Both are also easy to run locally.

```powershell
groovy tests/lint.groovy                       # CodeNarc over apps/ and drivers/ (needs Groovy 4 and Java 11+; downloads CodeNarc the first time)
pip install codespell; codespell README.md INSTALL.md docs tests apps drivers packageManifest.json .github
```

- **CodeNarc** uses the rules in `tests/codenarc.groovy`. They are chosen for real mistakes (unused private methods, variables and imports, `println`, insecure `Random`, risky APIs) and a cyclomatic complexity limit of 20 per method, and not for style, and every one passes today, so a new violation is a regression. The file explains the few exemptions: the partition driver's capability stubs, and the broad `catch` blocks that are deliberate (one bad frame must not stop the rest). To see what another rule family would say, pass your own ruleset file: `groovy tests/lint.groovy my-rules.groovy`. The runner also fails if it checks fewer files than exist, so it can't pass by matching nothing.
- **codespell** reads `.codespellrc` and needs no ignore list.

CodeNarc can't run on Groovy 2.4, so it is a Groovy 4 job. The 2.4 parse check and test run stay in the `test` job.

## Layout

| Path | Purpose |
|---|---|
| `run.groovy` | Runner: loads the helpers, runs every file in `cases/`, prints a summary |
| `lib/HubMocks.groovy` | Mock Hubitat runtime (`sendEvent`, scheduling, raw socket, child devices, HTTP) |
| `lib/Kit.groovy` | Assertions plus factories that load a driver or the app with the mocks |
| `lib/StaticChecks.groovy` | Syntax, misspelled-call and package-manifest checks |
| `cases/00_static.groovy` | Every file compiles, no undefined calls, manifests valid and complete |
| `cases/10_connection_session.groovy` | Login, reconnect, back-off, watchdog, frame robustness |
| `cases/20_connection_frames.groovy` | Frames from the UNO: zones, partitions, trouble, bypass, CID, host info, chime |
| `cases/25_connection_events.groovy` | Contact ID semantics: who armed or disarmed, alarm and trouble text, history, `unoEvent`, every field as an attribute, previously reported (qualifier 6) events |
| `cases/27_connection_features.groovy` | In-memory codes, tamper, health, diagnostic/test-event settings, PGM, temperatures, notifications, `debugDump` |
| `cases/28_connection_panel.groovy` | Skipped unchanged frames, open/bypassed/trouble summaries, zone conditions, last alarm, arm with bypass, zone timers, keypad sounds, the minute schedule, resync |
| `cases/29_connection_options.groovy` | Keepalive interval, the watchdog's margin, command timeout and queue age, zones per partition |
| `cases/30_connection_commands.groovy` | Commands to the UNO: queue and acks, arm, disarm, bypass, keypress, panic, REST sync |
| `cases/40_child_drivers.groovy` | Partition and zone drivers |
| `cases/45_child_features.groovy` | `applyZoneState` on every zone driver, arm with bypass on the partition, the status driver |
| `cases/50_app.groovy` | Device creation (by the connection device), hints, partitions, zone sets, forwarding, setup checks |
| `cases/55_app_features.groovy` | Selective save, status device, connection-lost alert, quiet hours, partition filter, repeat suppression, stale sensors, zones per partition (including changing partitions or zones afterwards), keepalive and timeout settings |
| `cases/84_device_prefix.groovy` | The name prefix on partitions and zones only, changing it, hand-made names, the length limit |
| `cases/85_zone_names.groovy` | Loading the UNO's zone names, naming and typing each zone in the app, keeping names changed on the Devices page |
| `cases/86_device_tree.groovy` | The partitions and zones live under the connection device, and the move from the earlier layout |
| `cases/83_ack_race.groovy` | A reply that reaches the hub before the command is saved as sent, and no connection before an address is set |
| `cases/60_range_parity.groovy` | The app and the connection driver parse range strings identically |
| `cases/70_spec_conformance.groovy` | The TPI spec's own examples, the exact bytes of every command, response codes and partition states |
| `cases/75_robustness.groovy` | Empty, malformed and missing settings and state never crash setup or frame handling (regression guard for an envisalink_new bug where an empty partition spec made a fallback call `.append()` on `None`) |
| `cases/76_truncated_data.groovy` | Truncated and garbled frames lose only themselves, the receive buffer is bounded, nothing disconnects, bypass flags always clear, unlisted partition states are busy |
| `cases/77_state_stability.groovy` | Arming, entry delay and alarm states don't flap while a standing trouble and other frames keep arriving (regression guard for envisalink_new #255) |
| `cases/78_connection_recovery.groovy` | Recovery never gets stuck: failed connects, closes and sends of any kind, backoff growth and flapping, fused and split messages, mismatched acknowledgements, login timeouts (regression guard for envisalink_new #237/#238) |
| `cases/80_arming_mode.groovy` | The mode shown during an exit delay is what was requested, never the previous arm's (regression guard for envisalink_new #191) |
| `cases/81_open_zones.groovy` | Open zones with when each opened, and keeping a bypassed zone in the list |
| `cases/82_housekeeping.groovy` | Values kept out of hub state, zone names cached for the open lists, the firmware warning |
| `cases/79_corrupted_bytes.groovy` | Garbled bytes in every frame type never throw, disconnect or become bogus events, acknowledgements or panel details (regression guard for envisalink_new #234) |

## Writing a test

Case files are plain Groovy scripts with a `k` variable (the `Kit`). Group checks in `k.section`:

```groovy
k.section("Arm commands") {
    def d = k.authed()                       // connection driver, already logged in
    d.armStay(2)
    k.check("armStay(2) => ^08,2$", d.sent == ["^08,2\$\r\n"], d.sent)
}
```

Useful helpers: `k.newConn(settings)`, `k.authed(settings)`, `k.feed(d, "%02,01...$")` to deliver a frame,
`k.ack(d, "08")` to acknowledge the in-flight command, and `k.newApp(settings, restMeta)` which returns
`[app, env]` where `env` records the devices the app created or deleted.

The partitions and zones are children of the connection driver, so `newConn` gives the driver a mock `parent` whose
`children` map holds them (`d.parent.children["uno-zone-2"]`, `d.parent.zoneChildren()`). To test the app and the
connection driver together, use `k.newPair(settings, restMeta)`, which returns `[app, conn, env]`: the real app and the
real driver wired to each other. `conn.save()` does what the hub does after **Done**, and `conn.host.children` holds the
devices the driver made (`env.children` is only the app's own: connection, PGM, status).

## Limits

The mocks encode our reading of the Hubitat API. They can't prove things that depend on the real hub, such as
the exact text of `socketStatus` messages, how the hub delivers raw-socket data, or the sandbox's restrictions.
Hubitat runs Groovy 2.4, which the tests now cover, but its sandbox is stricter than a plain Groovy, so test on a hub before releasing.
