# Installation and Setup

1. Install all drivers from the `drivers/` folder.
2. Install the app from `apps/UNOIntegrationApp.groovy`.
3. Open **Apps** and add **EyezOn UNO Integration**.
4. Enter the UNO IP, port `4025`, UNO password, and optional master code.
5. Choose the partitions to create, e.g. `1` or `1-2`. Leave it blank for partition 1 only. Name the partitions with **Partition 1 device name** and **Partition names**, or leave them blank to use the names configured on the UNO. With more than one partition, fill in **Zones per partition** (e.g. `1:1-8` and `2:9-16`) so each partition shows only its own open and bypassed zones.
6. Set the zones to create: either the **Configured Zone Count**, or a **Zone set** such as `1-8,12,20-27` (the zone set wins when filled in).
7. Paste your zone hints in `zoneNumber:Label:type` format.
8. Leave **Allow panic commands** off unless you really want panic commands available. Turn on **Require the master code to arm** only if you want arming to need a code (see the README for what that blocks).
9. Optional: enter **PGM outputs to create** (e.g. `1`) to get a button per PGM output. This needs the `UNO PGM` driver from `drivers/` (HPM lists it as optional). Choose **Notifications** devices if you want alarm, trouble or arm/disarm messages without writing a rule, and switch on **Create a UNO Status device** (needs the optional `UNO Status` driver) for a dashboard tile showing open zones, bypassed zones and troubles.
10. Click **Done**, then reopen the app. The **Status** section shows the connection state, the panel model, firmware and MAC address, and whether the REST credentials were accepted.

## Upgrading from an earlier version

- Existing zone devices and the `uno-partition-1` device keep their IDs, so Rule Machine rules and dashboards keep working.
- The partition 1 device name used to default to `House Alarm`. It now defaults to the name configured on the UNO (if REST is enabled), and falls back to `House Alarm`. A name you already saved is kept.
- Version 1.2.0 stops publishing `lastHeartbeat`, `lastRawMessage` and `panelTime` by default. Turn on **Publish diagnostic events** if a rule uses them.
- Tamper events now set a zone's `tamper` attribute instead of opening and closing a contact sensor. Update any rule that relied on the old behaviour.
- Version 1.3.0 removes the connection device's `lastSystemMessage` attribute (the frame it came from is really a keypad sound, now `lastKeypadSound` with diagnostic events on). It also keeps the event history in memory, so after upgrading it fills again as events arrive.
- Pressing **Done** no longer reconnects unless a connection setting changed. The first Done after upgrading the app does reconnect once, to start the new schedule. Use **reconnect** on the connection device to force one.
- **Heartbeat interval** is now **Keepalive** (30 seconds, 1, 5 or 10 minutes). Your old choice is kept. There is also a new **Wait for a command's reply for** setting (4 seconds by default, the same as before).
- Update all drivers and the app together (HPM does this), then press **Done** in the app so the new settings reach the connection device.
