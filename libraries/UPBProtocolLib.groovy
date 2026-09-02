/*
 * Hubitat Library: UPBProtocolLib
 * Description: Universal Powerline Bus Helper Library for Hubitat
 * Copyright: 2026 UPBeat Automation
 * Licensed: Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International License
 * Author: UPBeat Automation
 */
library(
        name: "UPBProtocolLib",
        namespace: "UPBeat",
        author: "UPBeat Automation",
        description: "Helper functions for UPB packet encoding and decoding",
        category: "Utilities",
        importUrl: ""
)

import hubitat.helper.HexUtils
import groovy.transform.Field

// Control word bit masks
@Field static final short CTRL_LNK_MASK = 0x8000    // Bit 15
@Field static final short CTRL_REPRQ_MASK = 0x6000  // Bits 14-13
@Field static final short CTRL_LEN_MASK = 0x1F00    // Bits 12-8
@Field static final short CTRL_RSV_MASK = 0x0080    // Bit 7
@Field static final short CTRL_ACKRQ_MASK = 0x0070  // Bits 6-4
@Field static final short CTRL_CNT_MASK = 0x000C    // Bits 3-2
@Field static final short CTRL_SEQ_MASK = 0x0003    // Bits 1-0
@Field static final int UPB_MESSAGE_PACKET_MIN_LENGTH = 7 // Header + MDID + checksum
@Field static final int UPB_PACKET_MAX_LENGTH = 24

// Control word values
@Field static final byte LNK_DIRECT = 0           // Direct packet
@Field static final byte LNK_LINK = 1             // Link packet
@Field static final byte REPRQ_NONE = 0           // No repeater
@Field static final byte REPRQ_ONE = 1            // One repeater
@Field static final byte REPRQ_TWO = 2            // Two repeaters
@Field static final byte REPRQ_HALT = 3           // Halt repeaters
@Field static final byte ACKRQ_NONE = 0           // No acknowledgment requests (bits 6-4 = 000)
@Field static final byte ACKRQ_MSG = 0x40         // Message ACK (bit 6 = 1)
@Field static final byte ACKRQ_ID = 0x20          // ID pulse ACK (bit 5 = 1)
@Field static final byte ACKRQ_PULSE = 0x10       // Pulse ACK (bit 4 = 1)
@Field static final byte TX_CNT_ONE = 0           // Transmit packet 1 time
@Field static final byte TX_CNT_TWO = 1           // Transmit packet 2 times
@Field static final byte TX_CNT_THREE = 2         // Transmit packet 3 times
@Field static final byte TX_CNT_FOUR = 3          // Transmit packet 4 times
@Field static final byte TX_SEQ_FIRST = 0         // 1st packet transmission
@Field static final byte TX_SEQ_SECOND = 1        // 2nd packet transmission
@Field static final byte TX_SEQ_THIRD = 2         // 3rd packet transmission
@Field static final byte TX_SEQ_FOURTH = 3        // 4th packet transmission


// MSID Mapping
@Field static final byte UPB_CORE_COMMAND = 0x00
@Field static final byte UPB_DEVICE_CONTROL_COMMAND = 0x01
@Field static final byte UPB_RESERVED_COMAND_SET_1 = 0x02
@Field static final byte UPB_RESERVED_COMAND_SET_2 = 0x03
@Field static final byte UPB_CORE_REPORTS = 0x04
@Field static final byte UPB_RESERVED_REPORT_SET_1 = 0x05
@Field static final byte UPB_RESERVED_REPORT_SET_2 = 0x06
@Field static final byte UPB_EXTENDED_MESSAGE_SET = 0x07

// Core Commands
@Field static final byte UPB_NULL_COMMAND = 0x00
@Field static final byte UPB_WRITE_ENABLED_COMMAND = 0x01
@Field static final byte UPB_WRITE_PROTECT_COMMAND = 0x02
@Field static final byte UPB_START_SETUP_MODE_COMMAND = 0x03
@Field static final byte UPB_STOP_SETUP_MODE_COMMAND = 0x04
@Field static final byte UPB_GET_SETUP_TIME_COMMAND = 0x05
@Field static final byte UPB_AUTO_ADDRESS_COMMAND = 0x06
@Field static final byte UPB_GET_DEVICE_STATUS_COMMAND = 0x07
@Field static final byte UPB_SET_DEVICE_CONTROL_COMMAND = 0x08
@Field static final byte UPB_ADD_LINK_COMMAND = 0x0B
@Field static final byte UPB_DEL_LINK_COMMAND = 0x0C
@Field static final byte UPB_TRANSMIT_MESSAGE_COMMAND = 0x0D
@Field static final byte UPB_DEVICE_RESET_COMMAND = 0x0E
@Field static final byte UPB_GET_DEVICE_SIG_COMMAND = 0x0F
@Field static final byte UPB_GET_REGISTER_VALUE_COMMAND = 0x10
@Field static final byte UPB_SET_REGISTER_VALUE_COMMAND = 0x11

// Device Control Commands
@Field static final byte UPB_ACTIVATE_LINK = 0x20
@Field static final byte UPB_DEACTIVATE_LINK = 0x21
@Field static final byte UPB_GOTO = 0x22
@Field static final byte UPB_FADE_START = 0x23
@Field static final byte UPB_FADE_STOP = 0x24
@Field static final byte UPB_BLINK = 0x25
@Field static final byte UPB_INDICATE = 0x26
@Field static final byte UPB_TOGGLE = 0x27
@Field static final byte UPB_REPORT_STATE = 0x30
@Field static final byte UPB_STORE_STATE = 0x31

// Core Report Set
@Field static final byte UPB_ACK_RESPONSE = 0x80
@Field static final byte UPB_SETUP_TIME = 0x85
@Field static final byte UPB_DEVICE_STATE = 0x86
@Field static final byte UPB_DEVICE_STATUS = 0x87
@Field static final byte UPB_DEVICE_SIG = 0x8F
@Field static final byte UPB_REGISTER_VALUES = 0x90
@Field static final byte UPB_RAM_VALUES = 0x91
@Field static final byte UPB_RAW_DATA = 0x92
@Field static final byte UPB_HEARTBEAT = 0x93

/**
 * Parses a 16-bit UPB control word into its constituent fields.
 * @param controlWord The 16-bit control word to parse.
 * @return A map containing the parsed fields: lnk, reprq, len, rsv, ackMsg, ackId, ackPulse, cnt, seq.
 */
static Map parseControlWord(short controlWord) {
    def lnk = (controlWord & CTRL_LNK_MASK) >> 15
    def reprq = (controlWord & CTRL_REPRQ_MASK) >> 13
    def len = (controlWord & CTRL_LEN_MASK) >> 8
    def rsv = (controlWord & CTRL_RSV_MASK) >> 7
    def ackMsg = (controlWord & 0x0040) >> 6
    def ackId = (controlWord & 0x0020) >> 5
    def ackPulse = (controlWord & 0x0010) >> 4
    def cnt = (controlWord & CTRL_CNT_MASK) >> 2
    def seq = controlWord & CTRL_SEQ_MASK

    return [
            lnk: lnk,
            reprq: reprq,
            len: len,
            rsv: rsv,
            ackMsg: ackMsg,
            ackId: ackId,
            ackPulse: ackPulse,
            cnt: cnt,
            seq: seq
    ]
}

/**
 * Encodes UPB control word fields into a 16-bit control word, excluding LEN.
 * @param lnk Link bit (0 = Direct, 1 = Link)
 * @param reprq Repeater Request (0-3)
 * @param ackFlags Acknowledgment flags (bitwise OR of ACKRQ_MSG, ACKRQ_ID, ACKRQ_PULSE)
 * @param cnt Encoded transmission count: use TX_CNT_ONE through TX_CNT_FOUR.
 * @param seq Encoded transmission sequence: use TX_SEQ_FIRST through TX_SEQ_FOURTH.
 * @return The encoded 16-bit control word as a short, with LEN set to 0.
 * @throws IllegalArgumentException if inputs are invalid.
 */
static short encodeControlWord(int lnk, int reprq, int ackFlags, int cnt, int seq) {
    if (lnk < 0 || lnk > 1) throw new IllegalArgumentException("LNK must be 0 or 1, got $lnk")
    if (reprq < 0 || reprq > 3) throw new IllegalArgumentException("REPRQ must be 0-3, got $reprq")
    if (ackFlags < 0 || (ackFlags & ~(ACKRQ_MSG | ACKRQ_ID | ACKRQ_PULSE)) != 0) {
        throw new IllegalArgumentException("Invalid ackFlags: $ackFlags, must be a combination of ACKRQ_MSG, ACKRQ_ID, ACKRQ_PULSE")
    }
    if (cnt < 0 || cnt > 3) throw new IllegalArgumentException("CNT must be 0-3, got $cnt")
    if (seq < 0 || seq > 3) throw new IllegalArgumentException("SEQ must be 0-3, got $seq")

    short controlWord = 0
    controlWord |= (lnk << 15)
    controlWord |= (reprq << 13)
    controlWord |= (ackFlags & (ACKRQ_MSG | ACKRQ_ID | ACKRQ_PULSE))
    controlWord |= (cnt << 2)
    controlWord |= seq

    return controlWord
}

/**
 * Calculates the UPB packet checksum.
 * @param data The packet bytes (excluding checksum for computation).
 * @return The 8-bit checksum byte.
 */
static byte checksum(byte[] data) {
    int sum = 0
    data.each { b -> sum += (b & 0xFF) } // Unsigned summation
    return (~sum + 1) & 0xFF // Two's complement, truncated to 8 bits
}

/**
 * Builds a UPB packet from the provided components.
 * @param controlWord 16-bit control word from encodeControlWord (LEN ignored).
 * @param networkId Network ID (0-255).
 * @param destinationId Destination device ID (0-255).
 * @param sourceId Source device ID (0-255).
 * @param messageDataId Message Data ID (MDID, 0-255).
 * @param messageArgument Optional message arguments (null or 0+ bytes).
 * @return The complete UPB packet as a byte array.
 * @throws IllegalArgumentException if packet length exceeds maximum 24 bytes.
 */
static byte[] buildPacket(short controlWord, byte networkId, byte destinationId, byte sourceId, byte messageDataId, byte[] messageArgument) {
    // Handle null messageArgument
    byte[] args = messageArgument ?: new byte[0]

    // Calculate packet length
    int expectedLength = 6 + args.length + 1 // 6 header bytes + args + 1 checksum
    if (expectedLength > 24) {
        throw new IllegalArgumentException("Total packet length $expectedLength exceeds maximum 24 bytes")
    }

    // Update LEN field in control word (bits 12-8)
    short updatedControlWord = (controlWord & ~CTRL_LEN_MASK) | ((expectedLength & 0x1F) << 8)

    // Build packet
    def packet = new ByteArrayOutputStream()
    packet.write((updatedControlWord >> 8) & 0xFF) // High byte
    packet.write(updatedControlWord & 0xFF)       // Low byte
    packet.write(networkId)
    packet.write(destinationId)
    packet.write(sourceId)
    packet.write(messageDataId)
    if (args.length > 0) {
        packet.write(args)
    }
    byte checksum = checksum(packet.toByteArray())
    packet.write(checksum)

    return packet.toByteArray()
}

/**
 * Parses a UPB packet and extracts header and message fields.
 * @param data The raw packet bytes.
 * @return A map with parsed fields.
 * @throws IllegalArgumentException if the packet is invalid.
 */
static Map parsePacket(byte[] data) {
    if (data == null) {
        throw new IllegalArgumentException("Invalid UPB packet: null")
    }

    int packetLength = data.length
    if (packetLength < UPB_MESSAGE_PACKET_MIN_LENGTH) {
        throw new IllegalArgumentException("Invalid UPB packet: length ${packetLength} < ${UPB_MESSAGE_PACKET_MIN_LENGTH} bytes")
    }
    if (packetLength > UPB_PACKET_MAX_LENGTH) {
        throw new IllegalArgumentException("Invalid UPB packet: length ${packetLength} > ${UPB_PACKET_MAX_LENGTH} bytes")
    }

    short controlWord = (short) (((data[0] & 0xFF) << 8) | (data[1] & 0xFF))
    int declaredLength = (controlWord & CTRL_LEN_MASK) >> 8
    if (declaredLength != packetLength) {
        throw new IllegalArgumentException("Invalid UPB packet: CTL LEN ${declaredLength} != actual length ${packetLength}")
    }

    int sum = 0
    data.each { b -> sum = (sum + (b & 0xFF)) & 0xFF }
    if (sum != 0) {
        throw new IllegalArgumentException("Invalid UPB packet: checksum sum 0x${String.format('%02X', sum)}")
    }

    byte networkId = data[2]
    byte destinationId = data[3]
    byte sourceId = data[4]
    byte messageDataId = data[5]
    int unsignedMessageDataId = messageDataId & 0xFF
    byte messageSetId = (byte) ((unsignedMessageDataId >> 5) & 0x07)
    byte messageId = (byte) (unsignedMessageDataId & 0x1F)
    byte[] messageArgs = new byte[0]
    if (packetLength > UPB_MESSAGE_PACKET_MIN_LENGTH) {
        messageArgs = data[6..(packetLength - 2)] as byte[]
    }

    return [
            controlWord: controlWord,
            networkId: networkId,
            destinationId: destinationId,
            sourceId: sourceId,
            messageDataId: messageDataId,
            messageSetId: messageSetId,
            messageId: messageId,
            messageArgs: messageArgs
    ]
}

/**
 * Parses a PIM register report (PR) payload into a JSON-compatible map.
 * @param data The raw payload bytes (RRVV, where RR is the starting register and VV are the values).
 * @return A map with parsed fields: [register: byte, values: byte[]].
 * @throws IllegalArgumentException if the payload is invalid (null or empty).
 */
static Map parseRegisterReport(byte[] data) {
    if (data == null || data.length < 1) {
        throw new IllegalArgumentException("Invalid register report: ${data == null ? 'null' : 'empty'} payload")
    }

    byte register = data[0]
    byte[] values = data.length > 1 ? data[1..-1] : new byte[0]

    return [
            register: register,
            values: values
    ]
}

/**
 * Gets the name of a Message Set ID (MSID).
 * @param msid The MSID value.
 * @return The human-readable name.
 */
static String getMsidName(int msid) {
    switch (msid) {
        case UPB_CORE_COMMAND: return "UPB_CORE_COMMAND"
        case UPB_DEVICE_CONTROL_COMMAND: return "UPB_DEVICE_CONTROL_COMMAND"
        case UPB_RESERVED_COMAND_SET_1: return "UPB_RESERVED_COMAND_SET_1"
        case UPB_RESERVED_COMAND_SET_2: return "UPB_RESERVED_COMAND_SET_2"
        case UPB_CORE_REPORTS: return "UPB_CORE_REPORTS"
        case UPB_RESERVED_REPORT_SET_1: return "UPB_RESERVED_REPORT_SET_1"
        case UPB_RESERVED_REPORT_SET_2: return "UPB_RESERVED_REPORT_SET_2"
        case UPB_EXTENDED_MESSAGE_SET: return "UPB_EXTENDED_MESSAGE_SET"
        default: return "UNKNOWN_MSID"
    }
}

/**
 * Gets the name of a Message Data ID (MDID).
 * @param mdid The MDID value.
 * @return The human-readable name.
 */
static String getMdidName(int mdid) {
    switch (mdid) {
        case UPB_NULL_COMMAND: return "UPB_NULL_COMMAND"
        case UPB_WRITE_ENABLED_COMMAND: return "UPB_WRITE_ENABLED_COMMAND"
        case UPB_WRITE_PROTECT_COMMAND: return "UPB_WRITE_PROTECT_COMMAND"
        case UPB_START_SETUP_MODE_COMMAND: return "UPB_START_SETUP_MODE_COMMAND"
        case UPB_STOP_SETUP_MODE_COMMAND: return "UPB_STOP_SETUP_MODE_COMMAND"
        case UPB_GET_SETUP_TIME_COMMAND: return "UPB_GET_SETUP_TIME_COMMAND"
        case UPB_AUTO_ADDRESS_COMMAND: return "UPB_AUTO_ADDRESS_COMMAND"
        case UPB_GET_DEVICE_STATUS_COMMAND: return "UPB_GET_DEVICE_STATUS_COMMAND"
        case UPB_SET_DEVICE_CONTROL_COMMAND: return "UPB_SET_DEVICE_CONTROL_COMMAND"
        case UPB_ADD_LINK_COMMAND: return "UPB_ADD_LINK_COMMAND"
        case UPB_DEL_LINK_COMMAND: return "UPB_DEL_LINK_COMMAND"
        case UPB_TRANSMIT_MESSAGE_COMMAND: return "UPB_TRANSMIT_MESSAGE_COMMAND"
        case UPB_DEVICE_RESET_COMMAND: return "UPB_DEVICE_RESET_COMMAND"
        case UPB_GET_DEVICE_SIG_COMMAND: return "UPB_GET_DEVICE_SIG_COMMAND"
        case UPB_GET_REGISTER_VALUE_COMMAND: return "UPB_GET_REGISTER_VALUE_COMMAND"
        case UPB_SET_REGISTER_VALUE_COMMAND: return "UPB_SET_REGISTER_VALUE_COMMAND"
        case UPB_ACTIVATE_LINK: return "UPB_ACTIVATE_LINK"
        case UPB_DEACTIVATE_LINK: return "UPB_DEACTIVATE_LINK"
        case UPB_GOTO: return "UPB_GOTO"
        case UPB_FADE_START: return "UPB_FADE_START"
        case UPB_FADE_STOP: return "UPB_FADE_STOP"
        case UPB_BLINK: return "UPB_BLINK"
        case UPB_INDICATE: return "UPB_INDICATE"
        case UPB_TOGGLE: return "UPB_TOGGLE"
        case UPB_REPORT_STATE: return "UPB_REPORT_STATE"
        case UPB_STORE_STATE: return "UPB_STORE_STATE"
        case UPB_ACK_RESPONSE: return "UPB_ACK_RESPONSE"
        case UPB_SETUP_TIME: return "UPB_SETUP_TIME"
        case UPB_DEVICE_STATE: return "UPB_DEVICE_STATE"
        case UPB_DEVICE_STATUS: return "UPB_DEVICE_STATUS"
        case UPB_DEVICE_SIG: return "UPB_DEVICE_SIG"
        case UPB_REGISTER_VALUES: return "UPB_REGISTER_VALUES"
        case UPB_RAM_VALUES: return "UPB_RAM_VALUES"
        case UPB_RAW_DATA: return "UPB_RAW_DATA"
        case UPB_HEARTBEAT: return "UPB_HEARTBEAT"
        default: return "UNKNOWN_MDID"
    }
}
