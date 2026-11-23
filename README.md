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
- Arm with bypass (`armStayBypass` / `armAwayBypass`): bypasses whatever is open, then arms
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
- Per-zone `tamper` attribute (`TamperAlert`), so a tamper never looks like a door opening
- Per-zone `batteryStatus` (`ok`/`low`) and `supervision` (`ok`/`lost`) from the UNO's low-battery and lost-supervision events
- Lists of the open and bypassed zones by name (`openZones`, `bypassedZones`) on the connection device, and per partition when you assign zones to partitions (or when there is only one partition)
- Per-zone `temperature` for wireless zones that report one, and inside/outside temperature on the partition
- Fast location events for low-latency automations: `unoZoneEvent`
- CID fast-path for zone alarm events, ahead of the next zone bitmap

**PGM outputs**
- Optional `UNO PGM` button devices (PGM 1-14), for example a garage door. Pressing one sends the UNO's toggle/activate command

**Notifications**
- Optional push notifications from the app for alarms, troubles and arm/disarm, so a rule isn't needed for the basics
- Quiet hours (alarms always get through), a partition filter, repeat suppression for troubles, and an alert if the UNO connection is lost for too long

**Status device (optional)**
- A `UNO Status` device with `openZones`, `bypassedZones`, `troubleSummary` and `staleSensors`, for one dashboard tile. Optionally reports sensors with no activity for a number of days

**Monitoring and diagnostics**
- Plain-language event descriptions from the UNO's Contact ID events: who armed or disarmed ("Armed by Alice"), which sensor alarmed, troubles and restorals
- Event history (the last 10 events as JSON) and per-partition `lastArmedBy` / `lastDisarmedBy` with times
- `lastAlarmType`, `lastAlarmZone` and `lastAlarmAt` on the partition, for rules about what alarmed and where
- A location event `unoEvent` for every Contact ID event, and the raw event, code, qualifier, partition, zone and user as attributes, for rules
- Panel trouble decoding per partition (AC failure, low battery, server offline, zone trouble, siren fault and more), and a one-line `troubleSummary`
- Panel model, firmware, MAC address and time
- Zone timers (`dumpZoneTimers`) and, with diagnostic events on, keypad sounds (`lastKeypadSound`)
- Status section on the app page: connection state, panel details, and whether the REST credentials were accepted
- Keepalive polling (down to every 30 seconds), a configurable command timeout, and watchdog reconnect with exponential back-off (see [Connection behaviour](#connection-behaviour))
- Online/offline `healthStatus` and `HealthCheck` (`ping`) on every device, driven by the connection to the UNO
- A command never runs late: anything still queued when the connection drops or reconnects is dropped and logged
- A busy panel (TPI response code 1) is retried with back-off
- Passwords and codes are masked in trace logs, and a disarm code is held in memory only, never in device state
- A `debugDump` command that logs a redacted snapshot of the integration for support
- High-churn attributes (heartbeat, raw message, panel time) and Contact ID test reports are off by default to spare the hub's database

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
| One per PGM (`uno-pgm-N`) | UNO PGM | For each output in **PGM outputs to create** (none by default). The driver is optional in HPM |
| `uno-status` | UNO Status | When **Create a UNO Status device** is on. The driver is optional in HPM |

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
| User names | One per line: `userNumber:Name`, for example `2:Alice`. Used to say who armed or disarmed. Unnamed users show as `user N` |
| Allow panic commands | Off by default. See [Panic commands](#panic-commands) |
| PGM outputs to create / PGM names | e.g. `1,3-4` and `1:Garage Door`. See [PGM outputs](#pgm-outputs) |
| Notifications | Devices to notify, and whether to send alarms (on by default), troubles (on by default) and arm/disarm (off by default). Also quiet hours, a partition filter and the connection-lost alert. See [Notifications](#notifications) |
| Create a UNO Status device / Stale sensor days | Off by default. See [Status device](#status-device) |
| Debug / trace logging | Trace logs show the TPI traffic with passwords and codes masked |
| Publish diagnostic events | Off by default. Publishes `lastHeartbeat`, `lastRawMessage`, `panelTime` and `lastKeypadSound`, which change constantly |
| Record Contact ID test reports | Off by default. The UNO's periodic test reports (code 602) are otherwise left out of the history and events |
| Keepalive / Reconnect if no frames seen for / Wait for a command's reply for | See [Connection behaviour](#connection-behaviour) |
| Zones per partition | Optional, one per line as `partition:zones`, e.g. `1:1-8` and `2:9-16`. See [Zones per partition](#zones-per-partition) |

Partition names are chosen in this order: the **Partition names** hint, the **Partition 1 device name** (partition 1 only), the name configured on the UNO, then `House Alarm` / `Partition N`.

Zone types are chosen in this order: your hint, a CID event type (smoke, water, CO), the zone's label, then the default type. A zone that already has a specific driver is never changed by inference, only by an explicit hint.

## Commands

| Where | Command | Notes |
|---|---|---|
| Partition | `armStay`, `armAway` | Fail if **Require the master code to arm** is on, because they carry no code |
| Partition | `armStayWithCode(code)`, `armAwayWithCode(code)` | Arm with a code, for when a code is required |
| Partition | `armStayBypass(code)`, `armAwayBypass(code)` | Bypass the open zones, then arm. The code is only needed when arming requires one, and is checked before anything is bypassed. Whether the UNO accepts the arm straight after the bypass is untested |
| Partition | `armHome`, `armNight`, `armAway` | `SecurityKeypad` / HSM entry points. Night arms stay (the UNO has no night mode). A request for the state the partition is already in is skipped |
| Partition | `disarm(code)` | Uses the master code if no code is given. Code must be 4-6 digits |
| Partition | `chimeOn`, `chimeOff` | |
| Partition | `bypassOpenZones`, `unbypassAllZones` | |
| Partition | `sendKeys(keys)` | 1-12 characters from `0-9`, `*`, `#` |
| Partition | `panicFire`, `panicMedical`, `panicPolice` | Ignored unless panic is enabled in the app |
| Partition | `refresh` | Requests a full state dump |
| Partition, zone, PGM | `ping` | Health check: asks the connection to refresh the UNO state. `healthStatus` is `online` while the connection is up |
| Zone | `bypass`, `unbypass` | |
| PGM | `push`, `toggle` | Triggers the PGM output |
| Connection | `triggerPgm(number)` | PGM 1-14 |
| Connection | `bypassZone(zone)`, `unbypassZone(zone)` | Zone 1-128 |
| Connection | `sendKeys(keys, partition)`, `panic(type, partition)` | For rules that address a partition by number |
| Connection | `armStay`, `armAway`, `armStayBypass`, `armAwayBypass`, `chimeOn`, `chimeOff`, `bypassOpenZones`, `unbypassAllZones` | Partition 1 from the device page |
| Connection | `disarm`, `refreshAll`, `poll`, `reconnect`, `restStartupSync`, `clearCommandQueue`, `clearEventHistory` | Maintenance and manual control |
| Connection | `dumpZoneTimers` | Asks the UNO for its zone timers and publishes them as `zoneTimers` (see [Attributes](#attributes)) |
| Connection | `debugDump` | Logs a redacted snapshot (settings, connection, queue, partitions, recent events) for support. Passwords and codes are never included |

> **Warning:** the partition device uses Hubitat's `Alarm` capability, and its `off` command **disarms** the partition. Anything that turns the "alarm" off (a rule, a dashboard tile, a voice assistant) will disarm your system. `siren`, `strobe` and `both` do nothing.

Commands are sent one at a time. Each waits for the panel's acknowledgement. If the panel answers that it is busy (response code 1) the command is re-sent after 1, 2 and then 4 seconds. If there is no reply at all within **Wait for a command's reply for** (4 seconds by default) it is re-sent once, and if there is still none the integration reconnects, because silence means the link is dead.

### Zones per partition

The UNO doesn't say which partition a zone belongs to, so by default `openZones` and `bypassedZones` are panel-wide. Fill in **Zones per partition** in the app, one line per partition, to tell the integration:

```
1:1-8
2:9-16,20
```

Each partition device then shows only its own open and bypassed zones. The connection device and the status device still show every zone. The app checks what you enter and shows the result under the box: a zone can't be in two partitions, a zone must be one of your configured zones, and the partition must be one you create. Anything that fails those checks is ignored and reported, never guessed at. Zones you leave out appear only in the panel-wide lists, and a partition with no line shows nothing.

Changing the partitions or zones later can never stop you saving. If an assignment then refers to a partition you no longer create, or a zone that is no longer one of yours, that part is ignored, the preview under the box says why (it refreshes as you change the setting), and the Status section at the top warns. What you typed is left as it is, so the assignment comes back by itself if you restore the partition or zone. This is only about showing zones; it doesn't change how zones or partitions work on the UNO.

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

### PGM outputs

Fill in **PGM outputs to create** in the app (for example `1`, or `1,3-4`) and each one becomes a `UNO PGM` device with a button. Pressing it (`push`, or `toggle`) sends the UNO's PGM command, which toggles or pulses the output depending on how that output is programmed on the panel. Give them names with **PGM names** (`1:Garage Door`).

The UNO reports no state for a PGM, so the device is a button, not a switch: `lastTriggered` records the last press. A PGM command is never re-sent after silence, because a repeat could undo the toggle; it is only re-sent if the UNO says it was busy and did not run it.

### Notifications

Choose one or more notification devices in the app to get messages without writing a rule:

- **Alarms** (on by default): burglary, fire, panic, CO, water and so on. Restorals are not sent.
- **Troubles** (on by default): AC loss, low battery, tamper and their restorals.
- **Arm / disarm** (off by default): including who did it, for example `Armed by Alice`.

With more than one partition, the message starts with the partition name. Contact ID test reports are never sent. A failing notification device is logged and doesn't affect the integration.

Options that shape what is sent:

- **Quiet hours**: between the start and end times (which may cross midnight), troubles and arm/disarm notices are skipped. **Alarms are always sent.**
- **Only for these partitions**: for example `2`. Panel-wide events (partition 0) are always included. Blank means all.
- **Repeat suppression**: the same trouble (a low battery that keeps reporting, say) is sent once in 6 hours. Its restoral is always sent and lets the next occurrence through. Alarms are never suppressed.
- **Connection lost**: if the UNO connection is down for 5, 15, 30 or 60 minutes (default 5, or never), you get a message, and another when it is back. A recovery before then sends nothing. Because the app is what notifies you, an outage of the hub itself can't be reported.

### Status device

Switch on **Create a UNO Status device** (it needs the `UNO Status` driver, which HPM lists as optional) for one device with `openZones`, `bypassedZones`, `troubleSummary` and `staleSensors`, handy for a dashboard tile. With a single partition and no zone assignments the partition device also gets `openZones` and `bypassedZones`. With [Zones per partition](#zones-per-partition) filled in, each partition gets its own list instead. With several partitions and no assignments the lists are panel-wide and stay on the status and connection devices.

Set **Report sensors with no activity for this many days** to be told about zones that may have a dead sensor. It checks every day at 9:00, using each zone's `lastFault`. A zone that has never triggered is only reported once that many days have passed since you switched the check on, because there is no way to know how old its sensor is. A changed list is sent as a notification and is always shown on the status device.

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
| `lastArmedBy`, `lastArmedAt`, `lastDisarmedBy`, `lastDisarmedAt` | Who armed or disarmed this partition and when, from the UNO's Contact ID events (a user name, `user N`, or `the system`) |
| `lastArmMode` | `stay` or `away`, from the last armed state |
| `lastEvent`, `lastEventType` | The last event for this partition in words, and its type (see [Event descriptions](#event-descriptions)) |
| `lastAlarmType`, `lastAlarmZone`, `lastAlarmAt` | The last alarm (not its restoral): for example `Burglary alarm`, `Front Door (zone 5)` (or `none` when the event has no zone) and when |
| `openZones`, `bypassedZones` | Zones by name, or `none`. With one partition and no assignments, every zone; with [Zones per partition](#zones-per-partition), that partition's zones; otherwise not set (see [Status device](#status-device)) |
| `lastKeypadSound` | Keypad beeps and tones, only when **Publish diagnostic events** is on |
| `temperature`, `outsideTemperature` | Inside and outside temperature, when the UNO reports them (converted to the hub's temperature scale) |
| `healthStatus` | `online` / `offline`, from the connection to the UNO |
| `securityKeypad` | `disarmed`, `armed home`, `armed away`, `unknown` (the `SecurityKeypad` capability) |
| `codeLength`, `maxCodes`, `lockCodes` | Fixed `6`, `0` and `{}`: user codes are managed on the UNO |

Unrecognised or transitory panel states are reported as `busy`, as the TPI document recommends.

**Zone device**: the capability attribute (`contact`, `motion`, `smoke`, `carbonMonoxide` or `water`), `zoneStateDigit` (`1` open/active, `0` closed), `lastFault` (when it last opened), `bypassed` (`true`/`false`), `tamper` (`detected`/`clear`), `batteryStatus` (`ok`/`low`), `supervision` (`ok`/`lost`), `temperature` (wireless zones that report one) and `healthStatus` (`online`/`offline`). The tamper, battery and supervision attributes come from the UNO's Contact ID events (137/144/383, 384 and 381), so they only change for sensors that send them.

**PGM device**: `lastTriggered` and `healthStatus`.

**Status device**: `openZones`, `bypassedZones`, `troubleSummary` (`none`, or the active troubles, labelled `P1: ...; P3: ...` when more than one partition has any), `staleSensors` and `healthStatus`.

**Connection device**: `connectionState`, `lastAck`, `panelModel`, `firmwareVersion`, `panelMac`, `restStartupSync`, `lastCidEvent` (the raw event), `lastEvent` / `lastEventType` (the last event from any partition, in words), `eventHistory`, `lastEventRaw`, `lastEventCode`, `lastEventQualifier`, `lastEventPartition`, `lastEventZone`, `lastEventUser`, `openZones`, `bypassedZones`, `troubleSummary`, `zoneTimers`, `healthStatus`, plus mirrors of partition 1's state, trouble and chime attributes (`alarmSystemStatus`, `troubleState` and the rest). `panelTime`, `lastHeartbeat`, `lastRawMessage` and `lastKeypadSound` are published only when **Publish diagnostic events** is on.

`zoneTimers` is JSON from the last `dumpZoneTimers`: for each zone with a timer, `"open"` or the seconds since it closed, for example `{"1":"open","2":5,"4":327625}`. The UNO's timers tick every 5 seconds and forget a zone after about 3.8 days, so older zones are left out.

`connectionState` is one of `connecting`, `connected`, `loginPrompt`, `authenticated`, `auth_failed`, `loginTimedOut`, `error` or `disconnected`. `lastAck` shows `error:<command>:<code>` when the panel rejects a command, `timeout:<command>` when it never answers, `rejected:<command>:code` when a required arming code was missing or wrong, and `dropped:<command>` when a command was discarded instead of run late.

## Event descriptions

The UNO reports what happens (an arm, an alarm, a trouble) as Contact ID events. The integration turns each one into a sentence, keeps a history, and publishes it, for example:

- `Armed by Alice`, `Disarmed by user 3`, `Armed (automatic) by the system`, `Armed (stay) by user 2`
- `Burglary alarm: Front Door (zone 5)`, `Burglary alarm restored: Front Door (zone 5)`
- `AC power loss`, `Sensor low battery: zone 7`, `Zone bypassed: zone 3`, `Wrong code entered by user 4`

Zone names come from the zone devices' labels, and user names from the **User names** setting. About 110 Contact ID codes are described; any other code is still reported as `Event 999` so nothing is hidden.

`lastEventType` (and the `unoEvent` category) is one of `arm`, `disarm`, `alarm`, `trouble`, `supervisory`, `bypass`, `access`, `exception`, `test`, `report` or `other`.

- **Arm / disarm:** an opening (qualifier 1) is a disarm and a closing (qualifier 3) is an arm, matching the example in the TPI document (`3441010020`: closing, armed stay, partition 1, user 2). The arm *mode* comes from the partition state and is kept in `lastArmMode`.
- **History:** the `eventHistory` attribute holds the newest 10 events as JSON (`at`, `partition`, `code`, `type`, `text`), newest first. The last 25 are kept in the driver's memory rather than the hub database, so after a hub restart only the 10 in the attribute are remembered. Use `clearEventHistory` to empty it.
- **Alarm restorals** are described as `... restored`.
- **Who:** the last field of an arm or access event is a user number. User 0 is shown as `the system`.
- **Previously reported (qualifier 6):** a status report that replays an older event is shown as `Previously reported: ...` with type `report`. It updates the event attributes and `unoEvent`, but is never recorded in the history, notified, or counted as an arm, disarm or alarm, and it never opens a zone. A replayed open/close doesn't say whether it was an arm or a disarm, because the direction isn't known.

### Every field as an attribute

The connection device publishes each part of the latest Contact ID event as its own attribute, so a rule can test it directly instead of parsing the sentence:

| Attribute | Value |
|---|---|
| `lastEventRaw` | The event exactly as the UNO sent it, for example `3441010020` |
| `lastEventCode` | The Contact ID event code, for example `441` or `130` |
| `lastEventQualifier` | `1` event (or opening), `3` restoral (or closing), `6` previously reported |
| `lastEventPartition` | The partition number (0 for a panel-wide event) |
| `lastEventZone` | The zone for alarms, bypasses and sensor troubles, or `0` when the event has none |
| `lastEventUser` | The user for arms, disarms and access events (the name you gave them, `user N`, or `the system`), or `none` |
| `lastEventType`, `lastEvent` | The category and the sentence |

`lastEvent` is set last, and every attribute fires on every event even when its value repeats. A rule triggered by a change to `lastEvent` therefore always sees the code, zone and user of that same event.

Contact ID test reports (602 and the like) change none of these unless **Record Contact ID test reports** is on. The raw `lastCidEvent` summary still updates for every frame.

## Automations

Zone changes are published as location events so rules can react immediately:

- Event name `unoZoneEvent`, value `<zone>:open` or `<zone>:closed` (for example `17:open`)

Every Contact ID event is also published as a location event, so a custom app or WebCoRE can react to who armed or what alarmed:

- Event name `unoEvent`, value `<category>:<code>` (for example `arm:401`, `disarm:401`, `alarm:130`, `trouble:301`, `report:130`)
- `descriptionText` is the sentence, and `data` is JSON with `partition`, `code`, `qualifier`, `category`, `zoneOrUser`, `zone` (the zone number, or null), `user` (the user's name, or null), `raw` and `text`

As far as I know Rule Machine can't trigger on a custom location event. For Rule Machine, trigger on a change to the connection device's `lastEvent` and test `lastEventCode`, `lastEventZone` or `lastEventUser` (see [Every field as an attribute](#every-field-as-an-attribute)). For example, "`lastEvent` changed AND `lastEventType` is `disarm` AND `lastEventUser` is `Alice`".

See [docs/example-rule-machine.md](docs/example-rule-machine.md) and [docs/example-zone-config.md](docs/example-zone-config.md).

## Connection behaviour

- **Login:** the password is sent once, followed by a CR, when the UNO shows its `Login` prompt. It is never sent again while logged in, because outside a `^...$` packet the TPI would read digits as keypad presses.
- **Keepalive:** a poll every 30 seconds, 1, 5 or 10 minutes (your setting, a minute by default) proves the link is alive. One schedule that runs every minute drives both the keepalive and the watchdog; 30 seconds adds a second poll half a minute later. A shorter keepalive notices a dead link sooner, at the cost of a little more traffic.
- **Watchdog:** the connection is dropped and re-opened if no frames arrive for the time you choose (3, 5 or 10 minutes, never shorter than the keepalive plus one minute). It also reconnects if the connection has not authenticated for 2 minutes.
- **Reconnect delays:** exponential, 2, 4, 8 and so on up to 128 seconds, for a dropped socket, a failed connect or a login timeout. The delay starts over once a connection has stayed up for 2 minutes, or when the connection is restarted. After the UNO rejects the password it waits 5 minutes (to avoid locking you out). Pressing **reconnect** retries immediately.
- **Saving the app:** pressing **Done** only restarts the connection when something it depends on changed (address, port, passwords, zone settings, keepalive or watchdog) or the app was updated. Changing names, hints, zone assignments, the command timeout or notification options leaves the connection, and any queued command, alone; behaviour settings such as panic or diagnostic events are pushed without a restart. If devices were created or recreated, the UNO is asked to announce its state again, without reconnecting. Use **reconnect** on the connection device to force a restart.
- **Commands and reconnects:** queued commands are discarded when the connection drops, reconnects or is re-initialised, and a command that has waited more than 30 seconds to be sent is discarded too (longer if you raise the command timeout: four times the timeout, when that is more than 30). Each one is logged and recorded in `lastAck` as `dropped:<command>`, so an arm or disarm requested during an outage never fires minutes later. Resend it once the connection is back.
- **No reply:** a command that gets no answer within the command timeout is re-sent once; if there is still none, the connection is reset.
- **Health:** every device reports `healthStatus` `online` once the UNO has accepted the login. If the connection stays down for 30 seconds the devices go `offline`, so a brief reconnect doesn't flap them.
- **Errors:** a malformed or unexpected frame is logged and skipped; it does not stop the frames behind it.
- **Truncated data:** if a frame is cut short and the next one starts before it ended, only the damaged frame is lost, not the good one that follows. Data with no frame terminator is dropped (with a warning) once it passes 4,096 characters, so damaged data can't grow the hub's state without limit. Damaged data never disconnects.
- **Corrupted bytes:** bytes the panel garbles (for example `0xBA` in the middle of a frame) are read one byte per character, so they can't change how a frame is split. A damaged frame is dropped rather than guessed at: a Contact ID event must be 10 digits and an acknowledgement a two-digit command and a numeric code, so garbage can't become a false "armed by Alice" or write nonsense into `lastAck`.
- **Failed sends:** if the socket can't be written to, that is treated as a lost connection: the command that couldn't be sent is dropped and logged, and a reconnect is scheduled, rather than leaving the command blocking the queue.
- **Keepalive can't be switched off.** Some panels drop a connection that has been idle for a long time without telling the client (reported for the EVL-4), and the first command afterwards then fails. A poll every 30 seconds to 10 minutes avoids that.

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
| 00 | Poll | Implemented (keepalive, `poll`) |
| 01 | Change default partition | Not implemented (not needed: commands carry their partition) |
| 02 | Dump zone timers | Implemented (`dumpZoneTimers`) |
| 03 | Keypress to a partition | Implemented (`sendKeys`) |
| 04 / 05 | Bypass / un-bypass a zone | Implemented |
| 06 / 07 | Bypass open zones / un-bypass all | Implemented |
| 08 / 09 | Stay-arm / away-arm | Implemented, all partitions |
| 0A | Toggle / activate a PGM output (1-14) | Implemented (`triggerPgm`, `UNO PGM` devices) |
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
| 03 | Realtime CID event | Zone events (burglary, perimeter, interior, entry/exit, fire, water, CO) update zones, and tamper events set the zone's `tamper` attribute. Every event is also described in words, recorded in the history and published as `unoEvent` (see [Event descriptions](#event-descriptions)) |
| 04 | Zone bypass state | Implemented |
| 05 | Host information | Implemented |
| 06 | Partition trouble | Implemented, all 8 partitions |
| 07 | Zone temperatures | Implemented: the zone's `temperature` (a zero byte means no reading and is ignored) |
| 08 | Partition temperatures | Implemented: the partition's `temperature` (inside) and `outsideTemperature` |
| 09 / 0A | Keypad one-time / persistent sounds | Implemented as `lastKeypadSound`, only with diagnostic events on (they are not decoded into a sound output) |
| 10 | Partition chime | Implemented (`lastChime`) |
| FF | Zone timer dump | Implemented (the `zoneTimers` attribute) |

**Response codes (section 3.7):** every code is reported. Code 1 (receive buffer overrun, meaning the panel was busy and did not run the command) is retried after 1, 2 and 4 seconds before it is reported as a failure. The other error codes are not retried.

**Interpretation notes**
- The trouble bitfield's BIT 1-8 are read as the low-to-high bits of each partition's byte.
- The spec describes the disarm code as "4 or 6 digits" in the text and "4-6" in the data column; 4 to 6 digits are accepted.
- Tamper CID codes (137, 144, 383) set the zone's `tamper` attribute and do not open or close it; the spec doesn't define them. If the UNO also shows a tampered zone as open in the zone bitmap, the zone will still read open. Likewise 384 sets `batteryStatus` and 381 sets `supervision`.
- Zone timers: a word of `FFFF` is open, each lower value is one 5 second tick since it closed, and `0000` is forgotten (spec 3.3, command FF).
- The TPI document does not list the Contact ID codes. The descriptions follow the standard Contact ID code list, and which codes the UNO actually sends has only been checked against the one example in the document.

## Known limitations

- One UNO per hub.
- The UNO does not report door chime state, so `chime` only reflects the last command accepted.
- A disarm code (or key presses) waiting in the command queue is held in the connection driver's memory, never in device state. If the hub restarts while a command is queued, the command is dropped with a warning.
- The event history list (25 entries) lives in memory too; only the newest 10 survive a hub restart, in the `eventHistory` attribute.
- All UNO drivers and the app should be updated together: the connection driver now tells each zone driver its state with one call, and a zone driver from an older version logs a warning and stops updating.
- The UNO doesn't say which partition a zone belongs to, so `openZones` and `bypassedZones` are panel-wide unless you assign zones to partitions in the app.
- Arm with bypass sends the bypass and then the arm as two commands; whether the UNO accepts the arm immediately afterwards has not been tried on a real panel.
- The UNO reports no PGM state, so PGM devices are buttons only.
- Nothing here has been run on a real hub yet; the tests use a mock Hubitat runtime.
- Hubitat apps can't share code with drivers, so the range parser exists in both the app and the connection driver. A test keeps them identical.

## Tests

```powershell
.\tests\run-tests.ps1     # or: groovy tests/run.groovy
```

CI runs the tests on Groovy 4.0 and on Groovy 2.4.21, the line Hubitat uses, and checks that every file parses on both.

The tests load the real driver and app scripts with a mock Hubitat runtime, replay the spec's own examples and check the exact bytes of every command. See [tests/README.md](tests/README.md). They can't replace testing on a real hub.
