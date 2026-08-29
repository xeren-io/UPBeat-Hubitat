# UPE Format Notes

Source: `UPStart_Export_File_Description_V5_2.pdf`

These notes summarize the parts of the UPStart export format that matter to the
Hubitat importer. They are not a full copy of the PDF.

## General Rules

- The export is CSV.
- Each row begins with a numeric record type.
- Format version 5 is the current supported format in this repository.
- There are no optional fields in defined records, but newer compatible exports
  may include record types this code does not know about.
- Readers should ignore records they do not expect rather than failing.
- The BOF record declares the number of devices and links.
- Device parsing starts at an ID record, then following records belong to that
  device until the next ID record or EOF.

## Record Types Used By The Importer

| Type | Meaning | Notes |
| --- | --- | --- |
| 0 | BOF | Version, device count, link count, network ID, network password |
| 1 | EOF | No additional fields |
| 2 | Link | Link ID and link name |
| 3 | ID | Starts a device section |
| 4 | Preset | Receive-component link and level data |
| 5 | Rocker | Transmit rocker actions |
| 6 | Button | Keypad/controller button actions |
| 7 | Input | Input module actions |
| 8 | Channel info | Channel number, module ID, dim enabled, default fade rate |
| 9 | VHC | Vacuum handle controller data |
| 10 | Installer info | Contact metadata |
| 11 | Owner info | Contact metadata |
| 12 | Device memory | Hex bytes, up to 16 data bytes per row |
| 13 | Keypad indicator | KPC6/KPC8 receive-component indicator behavior |
| 14 | Thermostat | RCS UPB thermostat data |
| 15 | XPW | X10/UPB bridge mapping |
| 16 | RFI | RFI remote data |
| 17 | Keypad button names | Button engraving text |
| 18 | Room icon | Gateway room icon metadata |
| 19 | Device icon | Gateway device icon metadata |

## Field Details To Preserve

ID record (`3`) fields after the type:

1. Module ID
2. Network ID
3. Manufacturer ID
4. Product ID
5. Firmware major version
6. Firmware minor version
7. Kind
8. Channel count
9. Transmit component count
10. Receive component count
11. Room name
12. Device name
13. Packet type, where `0` is direct and `1` is link

Channel info record (`8`) fields after the type:

1. Channel number, zero-based
2. Module ID
3. Whether dimming is enabled
4. Default fade rate, or zero for non-dimming channels

Preset record (`4`) fields after the type:

1. Channel number, zero-based
2. Component number, zero-based
3. Module ID
4. Link ID
5. Preset dim level
6. Preset dim fade rate

Button record (`6`) fields after the type:

1. Channel number
2. Component number
3. Module ID
4. Button link ID
5. Single click action
6. Double click action
7. Hold action
8. Release action
9. Single click toggle action
10. Double click toggle action
11. Hold toggle action
12. Release toggle action
13. Indicator link
14. Indicator byte

Keypad indicator record (`13`) fields after the type:

1. Channel number
2. Component number, zero-based
3. Module ID
4. Link ID
5. Mask 1
6. Mask 2

Keypad button name record (`17`) fields after the type:

1. Device channel
2. Device component / button number
3. Device module ID
4. Button name

## Device Kinds

| Kind | Meaning |
| --- | --- |
| 0 | Other |
| 1 | Keypad |
| 2 | Switch |
| 3 | Module |
| 4 | Input Module |
| 5 | Input-Output Module |
| 6 | VPM |
| 7 | VHC |
| 8 | Thermostat |
| 9 | XPW |
| 10 | RFI |

Importer support is switch/module channel import plus scene switch creation
from links. Keypads and other kinds are skipped unless explicit support is
added.

## Sample-Specific Notes

`sample.upe` contains one unsupported keypad:

- DNI under the old import rules: `UPBeat_879701`
- UPE ID record: module `151`, network `135`, manufacturer `1`, product `66`,
  kind `1`
- Product `66` is a PCS KPC8 controller/keypad.
- The sample has eight button records, sixteen keypad indicator records, and
  eight keypad button name records for this module.
- Current code skips this module because keypads are not supported child
  devices.

## Parser Notes And Remaining Gaps

- `processUpeFile()` now stores ID fields in spec order: manufacturer first,
  product second.
- `processUpeFile()` stores the ID record kind field as `deviceKind`.
- Module child-record lists are initialized when the ID record is parsed, so
  fields like `channelInfo`, `presetInfo`, `keypadIndicators`, and
  `buttonNames` are always present on module maps.
- Record type `13` is stored as `keypadIndicators`.
- Record type `17` is stored as `buttonNames`.
- `minFieldCounts` now expects seven total fields for preset records, including
  the record type.
- Current parsing associates device child records with the most recent ID
  record. It still does not validate declared module/link/component counts.
- The spec says readers should ignore unexpected/newer records, so stricter
  parsing should still remain forward-compatible.
