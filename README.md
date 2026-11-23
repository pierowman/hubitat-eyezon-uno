# EyezOn UNO Hubitat Integration
![Hubitat](https://img.shields.io/badge/Hubitat-Compatible-orange)
![Protocol](https://img.shields.io/badge/Protocol-UNO%20TPI-green)
![Install](https://img.shields.io/badge/Install-HPM-blue)
![License](https://img.shields.io/badge/License-MIT-lightgrey)

A Hubitat integration for the **EyezOn UNO Alarm System**.

> **Important:** The EyezOn **UNO is a stand-alone alarm system**. It is **not** just an interface to another panel such as DSC or Honeywell. This integration communicates **directly with the UNO itself** over the **UNO TPI** TCP socket protocol on port **4025**, following the *EnvisaLink UNO TPI Programmer's Document v1.08*.

> **Heads up:** section 2.1 of the TPI document says the UNO accepts only one client on port 4025 (section 2.3 mentions up to 8 sessions, so the document disagrees with itself). If another system such as Home Assistant is already connected and the UNO refuses this integration, disconnect the other client first.

## Features

**Alarm control**
- One partition device per configured partition (1-8), each with its own state, trouble flags and commands
- Arm Stay, Arm Away and Disarm per partition
- Optional **Require the master code to arm**, checked locally (off by default)
- Hubitat `SecurityKeypad` capability on each partition device: a keypad tile on dashboards, and usable as an HSM keypad
- Door chime on/off per partition
- Keypress command (`sendKeys`) to send a key sequence to a partition
- Optional panic commands (fire / medical / police), **off by default**

**Zones**
- Contact, Motion, Smoke, CO and Water child devices, created and reconciled automatically
- Zone sets such as `1-8,12,20-27`, so only the zones you actually have are created (UNO supports up to 128)
- Friendly names and types from `zoneHints`, the UNO's own zone labels (REST), or best-effort name inference
- Per-zone `bypassed` attribute with `bypass` / `unbypass` commands; bypass-all-open and un-bypass-all per partition
- Per-zone `lastFault` timestamp
- Fast location events for low-latency automations: `unoZoneEvent`
- CID fast-path for zone alarm events, ahead of the next zone bitmap

**Monitoring and diagnostics**
- Panel trouble decoding per partition (AC failure, low battery, server offline, zone trouble, siren fault and more)
- Panel model, firmware, MAC address and time
- Status section on the app page: connection state, panel details, and whether the REST credentials were accepted
- Heartbeat polling and watchdog reconnect with exponential back-off (see [Connection behaviour](#connection-behaviour))
- A command never runs late: anything still queued when the connection drops or reconnects is dropped and logged
- A busy panel (TPI response code 1) is retried with back-off
- Passwords and codes are masked in trace logs

**Setup help**
- REST metadata for zone labels, partition names and the system name
- REST startup sync so zone and partition states are correct right after a reconnect

## Install

See [INSTALL.md](INSTALL.md). In short: install the drivers, install the app, add **EyezOn UNO Integration**, enter your UNO details, choose partitions and zones, and press **Done**.

## What gets created

| Device | Driver | When |
|---|---|---|
| `UNO Connection` | UNO Connection | Always (one per app). Owns the socket, queue and panel-wide diagnostics |
| One per partition (`uno-partition-N`) | UNO Partition | For each partition in **Partitions to create** (partition 1 by default) |
| One per zone (`uno-zone-N`) | UNO Zone Contact / Motion / Smoke / CO / Water | For each zone in the zone set (or 1..Zone Count) |

Device network IDs are fixed, so existing rules and dashboards keep working across upgrades. Only one UNO per hub is supported.

## App settings

| Setting | Purpose |
|---|---|
| UNO IP, Port, Local/TPI Password | Connection details. The password is the one you use for the UNO's local web page |
| REST Username | Defaults to `user` |
| Master Code | Optional. Used by `disarm` when no code is passed (4-6 digits), and to check the code when **Require the master code to arm** is on |
| Require the master code to arm | Off by default. See [Requiring a code to arm](#requiring-a-code-to-arm) |
| Partitions to create | e.g. `1`, `1-2` or `1,3`. Blank means partition 1 only |
| Partition 1 device name / Partition names | Names for the partition devices. Blank uses the names configured on the UNO |
| Enable REST metadata and startup sync | Uses the UNO REST interface for labels and startup state |
| Configured Zone Count | Zones `1..N` are created (default 27, max 128) |
| Zone set | e.g. `1-8,12,20-27`. When filled in it **replaces** Zone Count |
| Default type for unknown zones | Used when a zone has no hint and its label doesn't suggest a type |
| Zone Hints | One zone per line: `zoneNumber:Label:type` (`contact`, `motion`, `smoke`, `co`, `water`) |
| Allow panic commands | Off by default. See [Panic commands](#panic-commands) |
| Debug / trace logging | Trace logs show the TPI traffic with passwords and codes masked |
| Heartbeat interval / Reconnect if no frames seen for | See [Connection behaviour](#connection-behaviour) |

Partition names are chosen in this order: the **Partition names** hint, the **Partition 1 device name** (partition 1 only), the name configured on the UNO, then `House Alarm` / `Partition N`.

Zone types are chosen in this order: your hint, a CID event type (smoke, water, CO), the zone's label, then the default type. A zone that already has a specific driver is never changed by inference, only by an explicit hint.

## Commands

| Where | Command | Notes |
|---|---|---|
| Partition | `armStay`, `armAway` | Fail if **Require the master code to arm** is on, because they carry no code |
| Partition | `armStayWithCode(code)`, `armAwayWithCode(code)` | Arm with a code, for when a code is required |
| Partition | `armHome`, `armNight`, `armAway` | `SecurityKeypad` / HSM entry points. Night arms stay (the UNO has no night mode). A request for the state the partition is already in is skipped |
| Partition | `disarm(code)` | Uses the master code if no code is given. Code must be 4-6 digits |
| Partition | `chimeOn`, `chimeOff` | |
| Partition | `bypassOpenZones`, `unbypassAllZones` | |
| Partition | `sendKeys(keys)` | 1-12 characters from `0-9`, `*`, `#` |
| Partition | `panicFire`, `panicMedical`, `panicPolice` | Ignored unless panic is enabled in the app |
| Partition | `refresh` | Requests a full state dump |
| Zone | `bypass`, `unbypass` | |
| Connection | `bypassZone(zone)`, `unbypassZone(zone)` | Zone 1-128 |
| Connection | `sendKeys(keys, partition)`, `panic(type, partition)` | For rules that address a partition by number |
| Connection | `armStay`, `armAway`, `chimeOn`, `chimeOff`, `bypassOpenZones`, `unbypassAllZones` | Partition 1 from the device page |
| Connection | `disarm`, `refreshAll`, `poll`, `reconnect`, `restStartupSync`, `clearCommandQueue` | Maintenance and manual control |

> **Warning:** the partition device uses Hubitat's `Alarm` capability, and its `off` command **disarms** the partition. Anything that turns the "alarm" off (a rule, a dashboard tile, a voice assistant) will disarm your system. `siren`, `strobe` and `both` do nothing.

Commands are sent one at a time. Each waits for the panel's acknowledgement. If the panel answers that it is busy (response code 1) the command is re-sent after 1, 2 and then 4 seconds. If there is no reply at all it is re-sent once, and if there is still none the integration reconnects, because silence means the link is dead.

### Requiring a code to arm

Turn on **Require the master code to arm** in the app and arming only works when a code that matches the master code is passed. Use `armStayWithCode` / `armAwayWithCode` on a partition, or pass the code when calling the connection or app commands from a rule.

The UNO's arm commands take no code, so this check is done here, not by the UNO. That has two consequences:

- It blocks anything that arms without sending a code, including HSM and the dashboard keypad tile.
- It needs a master code to compare against. With none configured, arming is blocked and the reason is logged.

Disarming always needs a code and is checked by the UNO itself, so this setting does not affect it.

### Keypad and HSM

Each partition device has Hubitat's `SecurityKeypad` capability, so it can be shown as a keypad tile and chosen as a keypad in HSM. `securityKeypad` follows the partition (`disarmed`, `armed home`, `armed away`, `unknown`) and keeps its last stable value during entry and exit delays and alarms. User codes are managed on the UNO, so `setCode`, `deleteCode` and `setCodeLength` do nothing but log that. The integration does not send `armingIn` events, so changing the UNO's state does not tell HSM to arm or disarm; HSM can only drive the UNO, not the other way round.

### Panic commands

Panic commands are disabled until you turn on **Allow panic commands** in the app. If your UNO is professionally monitored, a panic command contacts emergency services. A panic command is placed ahead of any queued commands and is always logged as a warning. It is never re-sent because of silence (it may or may not have run); it is only re-sent if the UNO replies that it was busy and did not run it. A panic that could not be sent is logged as an error.

## Attributes

**Partition device**

| Attribute | Values |
|---|---|
| `alarmSystemStatus`, `partitionState` | `disarmed`, `readyBypassed`, `notReady`, `armedStay`, `armedAway`, `armedAwayZeroEntry`, `exitDelay`, `entryDelay`, `alarm`, `unused`, `busy`, `unknown` |
| `securitySystemStatus` | `disarmed`, `armed home`, `armed away`, `arming` (entry/exit delay), `alarm`, `not ready`, `busy`; other states (`readyBypassed`, `unused`, `unknown`) are passed through unchanged |
| `ready` | `true` when disarmed or ready with bypassed zones |
| `alarm` | `both` while in alarm, otherwise `off` |
| `troubleState`, `troubleDetail` | `clear` / `trouble`, and a readable list such as `AC failure, Wireless device low battery` |
| `acPower` | `present` / `failed` |
| `serviceRequired`, `acFailure`, `lowBattery`, `serverOffline`, `zoneTrouble`, `batteryOvercurrent`, `sirenFault`, `supervisoryFault` | `active` / `clear` |
| `chime`, `lastChime` | `chime` is the last accepted `chimeOn`/`chimeOff` (the UNO doesn't report chime state); `lastChime` is the type of the last chime frame |
| `lastUserAction` | The last command issued from this device |
| `securityKeypad` | `disarmed`, `armed home`, `armed away`, `unknown` (the `SecurityKeypad` capability) |
| `codeLength`, `maxCodes`, `lockCodes` | Fixed `6`, `0` and `{}`: user codes are managed on the UNO |

Unrecognised or transitory panel states are reported as `busy`, as the TPI document recommends.

**Zone device**: the capability attribute (`contact`, `motion`, `smoke`, `carbonMonoxide` or `water`), `zoneStateDigit` (`1` open/active, `0` closed), `lastFault` (when it last opened) and `bypassed` (`true`/`false`).

**Connection device**: `connectionState`, `lastAck`, `panelModel`, `firmwareVersion`, `panelMac`, `panelTime`, `restStartupSync`, `lastCidEvent`, `lastHeartbeat`, `lastSystemMessage`, `lastRawMessage`, plus mirrors of partition 1's state, trouble and chime attributes (`alarmSystemStatus`, `troubleState` and the rest).

`connectionState` is one of `connecting`, `connected`, `loginPrompt`, `authenticated`, `auth_failed`, `loginTimedOut`, `error` or `disconnected`. `lastAck` shows `error:<command>:<code>` when the panel rejects a command, `timeout:<command>` when it never answers, `rejected:<command>:code` when a required arming code was missing or wrong, and `dropped:<command>` when a command was discarded instead of run late.

## Automations

Zone changes are published as location events so rules can react immediately:

- Event name `unoZoneEvent`, value `<zone>:open` or `<zone>:closed` (for example `17:open`)

See [docs/example-rule-machine.md](docs/example-rule-machine.md) and [docs/example-zone-config.md](docs/example-zone-config.md).

## Connection behaviour

- **Login:** the password is sent once, followed by a CR, when the UNO shows its `Login` prompt. It is never sent again while logged in, because outside a `^...$` packet the TPI would read digits as keypad presses.
- **Heartbeat:** a poll every 1, 5 or 10 minutes (your setting) proves the link is alive.
- **Watchdog:** the connection is dropped and re-opened if no frames arrive for the time you choose (3, 5 or 10 minutes, never shorter than the heartbeat plus one minute). It also reconnects if the connection has not authenticated for 2 minutes.
- **Reconnect delays:** exponential, 2, 4, 8 and so on up to 128 seconds, for a dropped socket, a failed connect or a login timeout. The delay starts over once a connection has stayed up for 2 minutes, or when you save the app. After the UNO rejects the password it waits 5 minutes (to avoid locking you out). Pressing **reconnect** or saving the app retries immediately.
- **Commands and reconnects:** queued commands are discarded when the connection drops, reconnects or is re-initialised, and a command that has waited more than 30 seconds to be sent is discarded too. Each one is logged and recorded in `lastAck` as `dropped:<command>`, so an arm or disarm requested during an outage never fires minutes later. Resend it once the connection is back.
- **No reply:** a command that gets no answer is re-sent once; if there is still none, the connection is reset.
- **Errors:** a malformed or unexpected frame is logged and skipped; it does not stop the frames behind it.

## REST metadata and startup sync

The integration can optionally use the UNO local REST interface for:

- zone labels
- partition names
- system name
- startup zone and partition state synchronization

Default REST username is `user`. TPI remains the real-time event and control path. If the UNO answers HTTP 401, the app logs that the REST credentials were rejected and shows it in the Status section.

## TPI coverage

How this integration maps to the *EnvisaLink UNO TPI Programmer's Document v1.08*.

**Commands to the UNO (section 3.2)**

| Code | Command | Status |
|---|---|---|
| 00 | Poll | Implemented (heartbeat, `poll`) |
| 01 | Change default partition | Not implemented (not needed: commands carry their partition) |
| 02 | Dump zone timers | Not implemented |
| 03 | Keypress to a partition | Implemented (`sendKeys`) |
| 04 / 05 | Bypass / un-bypass a zone | Implemented |
| 06 / 07 | Bypass open zones / un-bypass all | Implemented |
| 08 / 09 | Stay-arm / away-arm | Implemented, all partitions |
| 0A | Toggle / activate a PGM output (1-14) | Not implemented |
| 0C | Initial state dump | Implemented (on login and `refreshAll`) |
| 0D | Request host information | Not sent; the UNO sends it after login and with each poll |
| 10 | Door chime on/off | Implemented |
| 11 | Panic alarm | Implemented, opt-in |
| 12 | Disarm with code | Implemented, all partitions |

**Frames from the UNO (section 3.3)**

| Code | Frame | Status |
|---|---|---|
| 01 | Zone state change | Implemented (128 zones) |
| 02 | Partition state change | Implemented, all 8 partitions |
| 03 | Realtime CID event | Zone events (burglary, perimeter, interior, entry/exit, tamper, fire, water, CO) update zones; other events are recorded in `lastCidEvent` only |
| 04 | Zone bypass state | Implemented |
| 05 | Host information | Implemented |
| 06 | Partition trouble | Implemented, all 8 partitions |
| 07 | Zone temperatures | Not implemented |
| 08 | Partition temperatures | Not implemented |
| 09 / 0A | Keypad one-time / persistent sounds | Not implemented (keypad emulation only) |
| 10 | Partition chime | Implemented (`lastChime`) |
| FF | Zone timer dump | Not implemented |

**Response codes (section 3.7):** every code is reported. Code 1 (receive buffer overrun, meaning the panel was busy and did not run the command) is retried after 1, 2 and 4 seconds before it is reported as a failure. The other error codes are not retried.

**Interpretation notes**
- The trouble bitfield's BIT 1-8 are read as the low-to-high bits of each partition's byte.
- The spec describes the disarm code as "4 or 6 digits" in the text and "4-6" in the data column; 4 to 6 digits are accepted.
- Tamper CID codes (137, 144) are treated as the zone opening and restoring, which the spec does not define.

## Known limitations

- One UNO per hub.
- The UNO does not report door chime state, so `chime` only reflects the last command accepted.
- A disarm code that is waiting in the command queue is held in the connection device's `state` until it is sent.
- Hubitat apps can't share code with drivers, so the range parser exists in both the app and the connection driver. A test keeps them identical.

## Tests

```powershell
.\tests\run-tests.ps1     # or: groovy tests/run.groovy
```

The tests load the real driver and app scripts with a mock Hubitat runtime, replay the spec's own examples and check the exact bytes of every command. See [tests/README.md](tests/README.md). They can't replace testing on a real hub.
