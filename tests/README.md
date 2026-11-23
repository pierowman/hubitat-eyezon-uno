# Tests

These tests load the real driver and app scripts with a mock Hubitat runtime, replay frames from the
EnvisaLink UNO TPI spec and exercise the app, so most behaviour can be checked without a hub.

## Running

You need Java 11+ and Groovy 4 (`winget install --id Apache.Groovy.4 -e` on Windows).

```powershell
.\tests\run-tests.ps1            # Windows wrapper (finds Groovy for you)
groovy tests/run.groovy          # any platform, from the repository root
groovy tests/run.groovy -v       # also list every passing check
groovy tests/run.groovy app conn # only case files whose name contains "app" or "conn"
```

The exit code is non-zero if anything fails. The same command runs in GitHub Actions
(`.github/workflows/tests.yml`).

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
| `cases/25_connection_events.groovy` | Contact ID semantics: who armed or disarmed, alarm and trouble text, history, `unoEvent` |
| `cases/30_connection_commands.groovy` | Commands to the UNO: queue and acks, arm, disarm, bypass, keypress, panic, REST sync |
| `cases/40_child_drivers.groovy` | Partition and zone drivers |
| `cases/50_app.groovy` | Device creation, hints, partitions, zone sets, forwarding, setup checks |
| `cases/60_range_parity.groovy` | The app and the connection driver parse range strings identically |
| `cases/70_spec_conformance.groovy` | The TPI spec's own examples, the exact bytes of every command, response codes and partition states |

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
Hubitat also runs an older Groovy than the 4.x used here, so test on a hub before releasing.
