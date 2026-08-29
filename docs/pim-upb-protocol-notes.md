# PIM and UPB Protocol Notes

Sources: `PimComm1.5a.pdf`, `UPB_Description_v1.4.pdf`

These notes summarize the parts of the PIM and UPB protocol documents that
matter to the current Hubitat driver. They are not a full copy of either PDF.

## PIM Host Link

- The PIM host connection is RS-232 at 4800 baud, N-8-1.
- PIM/host messages are ASCII and end with carriage return (`0x0D`).
- The current driver talks through a serial-to-network adapter using Hubitat raw
  sockets with `byteInterface: true`. Because the current installation is known
  working, do not change the `sendBytes()` encoding path without testing on the
  hub and adapter.

## PIM Modes

- Message Mode reports only complete valid UPB messages (`PU...`) plus command
  status and transmit completion responses.
- Pulse Mode reports the raw pulse stream and requires the host to assemble and
  validate UPB packets. The current driver does not implement Pulse Mode.
- PIM setup register `0x70`, bit 1 selects the mode. Bit 1 set means Message
  Mode.
- The documented command for Message Mode is `CTL-W + 70028E + CR`. Current
  `setPIMCommandMode()` writes register `0x70` with value `0x02`, which matches
  the spec.

## Host To PIM Commands

All command payload bytes after the control character are ASCII hex.

| Command | Prefix | Payload | Current Function |
| --- | --- | --- | --- |
| Transmit UPB message | `0x14` (`CTL-T`) | `UU..KK` UPB packet bytes as ASCII hex | `transmitMessage()` |
| Read PIM registers | `0x12` (`CTL-R`) | register, count, checksum | `readPimRegister()` |
| Write PIM registers | `0x17` (`CTL-W`) | register, 1-16 values, checksum | `writePimRegister()` |

The PIM validates the command checksum before acting. `PA` means accepted, `PB`
means busy, and `PE` means rejected.

## PIM To Host Responses

- `PU` reports a valid UPB packet in Message Mode.
- `PR` reports PIM register data.
- `PK` means transmit completed and the PIM observed an ACK pulse.
- `PN` means transmit completed and the PIM did not observe an ACK pulse.

For transmit commands in Message Mode, waiting for `PK` or `PN` after `PA` is
expected. `PN` is normal for link commands sent with no ACK pulse request. If a
future log ever shows `PK` after a no-ACK link command, that should not be
treated as a PIM command failure; it means the packet was transmitted and an ACK
pulse was seen anyway.

## UPB Packet Shape

The UPB packet passed to the PIM excludes the preamble and includes:

1. Control word (`CTL`), 2 bytes
2. Network ID (`NID`), 1 byte
3. Destination ID (`DID`), 1 byte
4. Source ID (`SID`), 1 byte
5. Optional UPB message, 0-18 bytes
6. Checksum (`CHK`), 1 byte

Every command/report handled by the current code has at least one UPB message
byte, the message data ID (`MDID`). For those packets, the practical length is
7-24 bytes.

The `LEN` field in the control word is the total UPB packet byte count, excluding
the preamble and including checksum. The checksum is the two's complement of
the packet bytes before the checksum; summing the whole packet including
checksum should produce low byte `0x00`.

Current `buildPacket()` follows this shape for the command/report packets used
by the app. Current `parsePacket()` should be tightened before deeper refactors:

- reject null and empty input explicitly;
- reject command/report packets shorter than 7 bytes;
- reject packets longer than 24 bytes;
- compare parsed `LEN` to the actual byte count;
- return an empty argument array only when actual length is exactly 7.

## Addressing Rules

- Network ID field values are `0..255`; `0` is the global network ID.
- Link packet destination IDs are valid only from `1..250`; `0` and `251..255`
  are invalid link IDs.
- Direct packet destination IDs normally target unit IDs `1..250`.
- Direct destination `0` is broadcast, `253` targets write-enabled devices,
  `254` targets setup-mode devices, and `255` is reserved for default devices.
- Source ID represents the originator. The UPB examples for host-originated
  packets use `0xFF`. Current drivers use a mix of `0` and `0xFF`; since the
  installation is working, treat this as a behavior to verify on hardware before
  changing it.

## Device Control Commands Used Here

- Activate Link (`0x20`) must be sent as a Link Packet and has no arguments.
- Deactivate Link (`0x21`) must be sent as a Link Packet and has no arguments.
- Goto (`0x22`) may be sent as a Link Packet or Direct Packet. Current direct
  use sends level, rate, and channel. Channel `0` means all channels; `1` means
  channel 1, etc.
- Report State (`0x30`) must be sent as a Direct Packet and has no arguments.
- Device State Report (`0x86`) returns up to 17 state bytes. The spec does not
  define a universal meaning for every returned byte; device drivers should keep
  their current conservative interpretation unless a device-specific map is
  added.

## Review Implications

- Packet parsing validation is the safest first runtime change: it is isolated
  and spec-backed.
- Runtime validators should use the spec ranges, especially link IDs `1..250`
  and normal child-device unit IDs `1..250`.
- Existing preference checks using `!settings.networkId` should become explicit
  null checks so valid zero values are not accidentally rejected.
- The transmit-count constants now use names that reflect real packet behavior:
  `TX_CNT_ONE` encodes one transmission, `TX_CNT_TWO` encodes two transmissions,
  etc. The transmit-sequence constants do the same: `TX_SEQ_FIRST` encodes the
  first transmission, `TX_SEQ_SECOND` the second, etc. Current app calls use
  `TX_CNT_TWO` with `TX_SEQ_FIRST` to preserve the packet bytes that were
  previously produced by the old encoded-value constants.
- The current source ID mix (`0` and `0xFF`) should be made deliberate later,
  ideally by introducing a single `HOST_SOURCE_ID` constant after hardware
  verification.
