# Baseline: sample.upe

This baseline captures the expected behavior of the current importer for
`sample.upe`. It is based on the supported Hubitat child drivers, so unsupported
UPE device kinds are parsed but skipped during import.

Format reference notes are in `docs/upe-format-notes.md`. PIM and UPB packet
notes are in `docs/pim-upb-protocol-notes.md`.

## Source File

- File: `sample.upe`
- Size: 51,130 bytes
- Lines: 1,597
- Encoding: ASCII text with CRLF line endings
- UPE version: 5
- UPB network ID: 135 (`0x87`)
- Declared modules: 54
- Declared links: 44

Do not record or publish the network password from the UPE file.

## Parsed Record Counts

| Record Type | Count |
| --- | ---: |
| 0 | 1 |
| 1 | 1 |
| 2 | 44 |
| 3 | 54 |
| 4 | 848 |
| 5 | 52 |
| 6 | 8 |
| 8 | 54 |
| 10 | 1 |
| 11 | 1 |
| 12 | 435 |
| 13 | 16 |
| 17 | 8 |
| 18 | 20 |
| 19 | 54 |

## Current Import Expectations

Bulk import builds and validates an import plan first. If the plan is valid, it
deletes every non-PIM child device, then recreates supported devices from the
parsed UPE data.

Expected children after importing `sample.upe`:

| Child Type | Count |
| --- | ---: |
| UPB Scene Switch | 44 |
| UPB Dimming Switch | 28 |
| UPB Non-Dimming Switch | 25 |
| Non-PIM total | 97 |
| Total including PIM | 98 |

Generated DNI expectations:

- Scene DNIs use `UPBeat_87LL`, where `LL` is the link ID in hex.
- Device DNIs use `UPBeat_87MMCC`, where `MM` is the module ID in hex and
  `CC` is the one-based channel ID in hex.
- No duplicate scene or device DNIs were found in this sample.

Receive-component expectations:

- 212 receive-component settings are expected to be populated from preset
  records.
- All 212 are valid under the current `getReceiveComponents()` rules.
- No duplicate receive-component link IDs were found per device.
- One parsed module has no preset records: module `151` (`Other New PCS
  KPC(8)`). This is an unsupported 8-button keypad and is skipped.

## Spot Checks

Scenes:

| DNI | Name |
| --- | --- |
| `UPBeat_8701` | Kitchen On |
| `UPBeat_8702` | Kitchen Off |
| `UPBeat_8707` | Dining Room On |
| `UPBeat_8708` | Dining Room Off |
| `UPBeat_870D` | Living Room On |
| `UPBeat_870E` | Living Room Off |
| `UPBeat_8713` | Foyer On |
| `UPBeat_8714` | Foyer Off |
| `UPBeat_87F9` | All Units On |
| `UPBeat_87FA` | All Units Off |

Devices:

| DNI | Driver | Name |
| --- | --- | --- |
| `UPBeat_874C01` | UPB Dimming Switch | Outside Back Door |
| `UPBeat_875A01` | UPB Dimming Switch | 2nd Hallway Back Stairs |
| `UPBeat_875301` | UPB Non-Dimming Switch | Security Backyard |
| `UPBeat_870101` | UPB Dimming Switch | Kitchen Edge Lights |
| `UPBeat_870401` | UPB Non-Dimming Switch | Kitchen Pantry Light |
| `UPBeat_873201` | UPB Dimming Switch | Family Room Lights |

Skipped unsupported modules:

| Module ID | Old DNI | Name | Kind | Manufacturer | Product |
| ---: | --- | --- | ---: | ---: | ---: |
| 151 | `UPBeat_879701` | Other New PCS KPC(8) | 1 | 1 | 66 |

Receive components:

| Device DNI | Expected Slots |
| --- | --- |
| `UPBeat_874C01` | `1=55:100`, `2=56:0`, `3=57:50`, `4=58:0`, `15=249:100`, `16=250:0` |
| `UPBeat_875A01` | `1=67:100`, `2=68:0`, `15=249:100`, `16=250:0` |
| `UPBeat_875301` | `1=61:100`, `2=62:0`, `15=249:100`, `16=250:0` |

## Manual Hubitat Regression Checklist

Run this checklist on a hub with the current code before and after any change.

- Install or open `UPBeat App`; confirm the PIM child device exists with DNI
  `UPBeat_PIM`.
- Configure the PIM child with the known-good ser2net IP and port; save
  preferences; confirm `Network`, `PIM`, and `status` settle to the same values
  as the current working install.
- Bulk import `sample.upe`; confirm import completes without app errors.
- Confirm the child device counts match the table above.
- Confirm the spot-check scene and device DNIs, names, and drivers match.
- Confirm the spot-check receive-component slots match the table above.
- Run `Refresh All Device States`; confirm dimmers/switches update without
  error statuses.
- On a known dimmer, run `on`, `off`, and `setLevel`; confirm switch and level
  events match current working behavior.
- On a known non-dimming switch, run `on` and `off`; confirm switch events match
  current working behavior.
- Activate and deactivate a known scene; confirm the scene device state and
  affected receive-component devices match current working behavior.
- Confirm manually adding a duplicate DNI still fails without altering the
  existing child device.

## Notes For Refactoring

- Preserve the generated DNI formats exactly unless migration code is added.
- Preserve child driver display names because `addChildDevice()` and
  `DEVICE_TYPES` depend on them.
- Preserve existing scene propagation semantics until replacement behavior has
  been tested on the hub.
- Keep API/config-app work gated separately from driver behavior changes.
