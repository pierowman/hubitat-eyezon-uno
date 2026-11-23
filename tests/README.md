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
| `cases/50_app.groovy` | Device creation, hints, partitions, zone sets, forwarding, setup checks |
| `cases/55_app_features.groovy` | Selective save, status device, connection-lost alert, quiet hours, partition filter, repeat suppression, stale sensors, zones per partition, keepalive and timeout settings |
| `cases/60_range_parity.groovy` | The app and the connection driver parse range strings identically |
| `cases/70_spec_conformance.groovy` | The TPI spec's own examples, the exact bytes of every command, response codes and partition states |
| `cases/75_robustness.groovy` | Empty, malformed and missing settings and state never crash setup or frame handling (regression guard for an envisalink_new bug where an empty partition spec made a fallback call `.append()` on `None`) |
| `cases/76_truncated_data.groovy` | Truncated and garbled frames lose only themselves, the receive buffer is bounded, nothing disconnects, bypass flags always clear, unlisted partition states are busy |

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

## Limits

The mocks encode our reading of the Hubitat API. They can't prove things that depend on the real hub, such as
the exact text of `socketStatus` messages, how the hub delivers raw-socket data, or the sandbox's restrictions.
Hubitat runs Groovy 2.4, which the tests now cover, but its sandbox is stricter than a plain Groovy, so test on a hub before releasing.
