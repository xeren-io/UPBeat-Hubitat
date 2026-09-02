/*
 * Hubitat Driver: UPB Powerline Interface Module
 * Description: Network driver for Universal Powerline Bus communication
 * Copyright: 2026 UPBeat Automation
 * Licensed: Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International License
 * Author: UPBeat Automation
 */
import hubitat.helper.HexUtils
import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
#include UPBeat.UPBeatLogger
#include UPBeat.UPBeatDriverLib
#include UPBeat.UPBProtocolLib

metadata {
    definition(name: "UPB Powerline Interface Module", namespace: "UPBeat", author: "UPBeat Automation") {
        capability "Initialize"
        attribute "Network", "string"
        attribute "PIM", "string"
        attribute "status", "enum", ["ok", "error"]
    }

    preferences {
        input name: "logLevel", type: "enum", options: LOG_LEVELS, title: "Log Level", defaultValue: LOG_DEFAULT_LEVEL, required: true
        input name: "ipAddress", type: "text", title: "IP Address", description: "IP Address of Serial to Network Device", required: true
        input name: "portNumber", type: "number", title: "Port", description: "Port of Serial to Network Device", required: true, range: "0..65535"
        input name: "maxRetry", type: "number", title: "Retries", description: "Number of retries for PIM busy messages", required: true, range: "1..60", defaultValue: 10
        input name: "maxProcessingTime", type: "number", title: "Timeout", description: "Timeout for messages being sent", required: true, range: "0..60000", defaultValue: 10000
        input name: "reconnectInterval", type: "number", title: "Reconnect", description: "Reconnect Interval", required: true, range: "0..60000", defaultValue: 60
    }
}

/***************************************************************************
 * Global Static Data
 ***************************************************************************/
@Field static ConcurrentHashMap deviceMutexes = new ConcurrentHashMap()
@Field static ConcurrentHashMap currentOutgoing = new ConcurrentHashMap()

@Field static final byte WRITE_REGISTER = 0x17
@Field static final byte READ_REGISTER = 0x12
@Field static final byte TRANSMIT_MESSAGE = 0x14
@Field static final byte EOM = 0x0D

/***************************************************************************
 * Helper Functions
 ***************************************************************************/
/**
 * Called by socket lifecycle handlers.
 * Publishes the current TCP connection state for the PIM child device.
 */
private void setNetworkStatus(String state, String reason = '') {
    logDebug("[${device.deviceNetworkId}] Status: Setting network state to %s%s.", state, reason ? ": ${reason}" : "")
    String msg = "${device} is ${state.toLowerCase()}${reason ? ': ' + reason : ''}"
    sendEvent(name: "Network", value: state, descriptionText: msg, isStateChange: true)
}

/**
 * Called by PIM configuration and socket handlers.
 * Publishes whether the powerline interface is ready for UPB communication.
 */
private void setModuleStatus(String state, String reason = '') {
    logDebug("[${device.deviceNetworkId}] Status: Setting PIM state to %s%s.", state, reason ? ": ${reason}" : "")
    String msg = "${device} is ${state.toLowerCase()}${reason ? ': ' + reason : ''}"
    sendEvent(name: "PIM", value: state, descriptionText: msg, isStateChange: true)
}

/**
 * Called by most PIM driver operations.
 * Publishes driver-level health while allowing callers to force error state changes.
 */
private void setDeviceStatus(String state, String reason = '', boolean forceEvent = false) {
    logDebug("[${device.deviceNetworkId}] Status: Setting device state to %s%s.", state, reason ? ": ${reason}" : "")
    String msg = reason ?: "${device} is ${state.toLowerCase()}"
    sendEvent(name: "status", value: state, descriptionText: msg, isStateChange: forceEvent)
}

/***************************************************************************
 * Core Driver Functions
 ***************************************************************************/
/**
 * Called by Hubitat when the PIM child device is created.
 * Verifies the parent app relationship and initializes status.
 */
def installed() {
    logTrace("[${device.deviceNetworkId}] installed: Entering.")
    try {
        isCorrectParent()
        setDeviceStatus("ok")
        logInfo("[${device.deviceNetworkId}] installed: Driver installed successfully.")
    } catch (IllegalStateException e) {
        logError("[${device.deviceNetworkId}] installed: Illegal state: %s.", e.message)
        setDeviceStatus("error", e.message, true)
    } catch (Exception e) {
        logError("[${device.deviceNetworkId}] installed: Unexpected error: %s.", e.message)
        setDeviceStatus("error", e.message, true)
    }
    logTrace("[${device.deviceNetworkId}] installed: Exiting.")
}

/**
 * Called by Hubitat when the PIM child device is removed.
 * Closes the TCP socket and clears static transaction state for this device.
 */
def uninstalled() {
    logTrace("[${device.deviceNetworkId}] uninstalled: Entering.")
    try {
        isCorrectParent()
        closeSocket()
        logInfo("[${device.deviceNetworkId}] uninstalled: Removing mutex and response buffer for %s.", device.deviceNetworkId)
        deviceMutexes.remove(device.deviceNetworkId)
        currentOutgoing.remove(device.deviceNetworkId)
        setDeviceStatus("ok")
        logInfo("[${device.deviceNetworkId}] uninstalled: Driver uninstalled successfully.")
    } catch (IllegalStateException e) {
        logError("[${device.deviceNetworkId}] uninstalled: Illegal state: %s.", e.message)
        setDeviceStatus("error", e.message, true)
    } catch (Exception e) {
        logError("[${device.deviceNetworkId}] uninstalled: Unexpected error: %s.", e.message)
        setDeviceStatus("error", e.message, true)
    }
    logTrace("[${device.deviceNetworkId}] uninstalled: Exiting.")
}

/**
 * Called by Hubitat after PIM preferences are saved.
 * Reinitializes the TCP connection using the current IP, port, and retry settings.
 */
def updated() {
    logTrace("[${device.deviceNetworkId}] updated: Entering.")
    try {
        isCorrectParent()
        initialize()
        setDeviceStatus("ok")
        logInfo("[${device.deviceNetworkId}] updated: Driver updated successfully.")
    } catch (IllegalStateException e) {
        logError("[${device.deviceNetworkId}] updated: Illegal state: %s.", e.message)
        setModuleStatus("Inactive", e.message)
        setDeviceStatus("error", e.message, true)
    } catch (Exception e) {
        logError("[${device.deviceNetworkId}] updated: Unexpected error: %s.", e.message)
        setModuleStatus("Inactive", e.message)
        setDeviceStatus("error", e.message, true)
    }
    logTrace("[${device.deviceNetworkId}] updated: Exiting.")
}

/**
 * Called by Hubitat Initialize, updated(), and reconnect flows.
 * Opens the socket and places the PIM into message mode.
 */
def initialize() {
    logTrace("[${device.deviceNetworkId}] initialize: Entering.")
    try {
        isCorrectParent()
        logInfo("[${device.deviceNetworkId}] initialize: Configuring driver with DNI=%s, host=%s:%d, maxRetry=%d, maxProcessingTime=%d, reconnectInterval=%d, logLevel=%s.",
                device.deviceNetworkId, ipAddress, portNumber, maxRetry, maxProcessingTime, reconnectInterval, LOG_LEVELS[logLevel.toInteger()])
        deviceMutexes.put(device.deviceNetworkId, new Object())
        currentOutgoing.put(device.deviceNetworkId, [:])

        closeSocket()
        if (!openSocket()) {
            logError("[${device.deviceNetworkId}] initialize: Failed to open socket.")
            setModuleStatus("Inactive", "Failed to open socket")
            throw new Exception("Failed to open socket during initialization")
        }
        if (!setPIMCommandMode()) {
            logError("[${device.deviceNetworkId}] initialize: Failed to set PIM command mode.")
            throw new Exception("Failed to set PIM command mode during initialization")
        }
        setDeviceStatus("ok")
        logInfo("[${device.deviceNetworkId}] initialize: Driver initialized successfully.")
    } catch (IllegalStateException e) {
        logError("[${device.deviceNetworkId}] initialize: Illegal state: %s.", e.message)
        setModuleStatus("Inactive", e.message)
        setDeviceStatus("error", e.message, true)
    } catch (Exception e) {
        logError("[${device.deviceNetworkId}] initialize: Unexpected error: %s.", e.message)
        setModuleStatus("Inactive", e.message)
        setDeviceStatus("error", e.message, true)
    }
    logTrace("[${device.deviceNetworkId}] initialize: Exiting.")
}

/***************************************************************************
 * Web Socket User Defined
 ***************************************************************************/
/**
 * Called by Hubitat's raw socket implementation when connection status changes.
 * Updates connection state and schedules reconnects after socket failures.
 */
def socketStatus(message) {
    logTrace("[${device.deviceNetworkId}] socketStatus: Entering with message=%s.", message)
    try {
        isCorrectParent()
        if (message.contains('error: Stream closed') || message.contains('error: Connection timed out') || message.contains("receive error: Connection reset")) {
            logError("[${device.deviceNetworkId}] socketStatus: Socket error: %s.", message)
            setNetworkStatus("Disconnected")
            setModuleStatus("Inactive")
            setDeviceStatus("error", message, true)
            logInfo("[${device.deviceNetworkId}] socketStatus: Scheduling reconnect in %d seconds.", reconnectInterval)
            runIn(reconnectInterval, reconnectSocket)
        }
    } catch (IllegalStateException e) {
        logError("[${device.deviceNetworkId}] socketStatus: Illegal state: %s.", e.message)
        setModuleStatus("Inactive", e.message)
        setDeviceStatus("error", e.message, true)
    } catch (Exception e) {
        logError("[${device.deviceNetworkId}] socketStatus: Unexpected error: %s.", e.message)
        setModuleStatus("Inactive", e.message)
        setDeviceStatus("error", e.message, true)
    }
    logTrace("[${device.deviceNetworkId}] socketStatus: Exiting.")
}

/**
 * Called by Hubitat's raw socket implementation when bytes arrive from the PIM.
 * Parses PIM response frames and dispatches asynchronous UPB message reports.
 */
def parse(hexMessage) {
    logTrace("[${device.deviceNetworkId}] parse: Entering with message=0x%s.", hexMessage)
    deviceMutexes.putIfAbsent(device.deviceNetworkId, new Object())
    currentOutgoing.putIfAbsent(device.deviceNetworkId, [:])

    try {
        isCorrectParent()
        logDebug("[${device.deviceNetworkId}] parse: Received message 0x%s.", hexMessage)

        byte[] messageBytes = HexUtils.hexStringToByteArray(hexMessage)
        if (messageBytes.size() == 0 || messageBytes[messageBytes.length - 1] != EOM) {
            logError("[${device.deviceNetworkId}] parse: Invalid message: No EOL found in 0x%s.", hexMessage)
            setDeviceStatus("error", "Message parsing failed: No EOL found", true)
            return
        }
        messageBytes = messageBytes[0..-2]
        logTrace("[${device.deviceNetworkId}] parse: EOL removed, bytes=0x%s.", HexUtils.byteArrayToHexString(messageBytes))

        if (messageBytes.size() < 2) {
            logError("[${device.deviceNetworkId}] parse: Invalid message: Data length too short in 0x%s.", HexUtils.byteArrayToHexString(messageBytes))
            setDeviceStatus("error", "Message parsing failed: Invalid data length", true)
            return
        }

        def asciiMessage = new String(messageBytes)
        logTrace("[${device.deviceNetworkId}] parse: Converted to string: %s.", asciiMessage)

        byte[] messageTypeBytes = messageBytes[0..1]
        String messageType = new String(messageTypeBytes)
        logDebug("[${device.deviceNetworkId}] parse: Processing message type=%s.", messageType)

        byte[] messageData = messageBytes.size() > 2 ? messageBytes[2..-1] : new byte[0]
        if (messageType in ['PU', 'PR']) {
            String messageDataString = new String(messageData)
            try {
                messageData = HexUtils.hexStringToByteArray(messageDataString)
                logTrace("[${device.deviceNetworkId}] parse: Parsed %s data: 0x%s.", messageType, HexUtils.byteArrayToHexString(messageData))
            } catch (Exception e) {
                logError("[${device.deviceNetworkId}] parse: Failed to parse %s data: %s.", messageType, e.message)
                setDeviceStatus("error", "${messageType} data parsing failed: ${e.message}", true)
                return
            }
        }

        def outgoingMessage = currentOutgoing.get(device.deviceNetworkId)
        switch (messageType) {
            case 'PA':
                logDebug("[${device.deviceNetworkId}] parse: Processing PA response.")
                if (outgoingMessage.containsKey('pim_response')) {
                    logDebug("[${device.deviceNetworkId}] parse: Storing PA response for command.")
                    outgoingMessage.pim_response = 'PA'
                    outgoingMessage.pim_response_semaphore.release()
                }
                break
            case 'PE':
                logError("[${device.deviceNetworkId}] parse: Processing PE response.")
                if (outgoingMessage.containsKey('pim_response')) {
                    logDebug("[${device.deviceNetworkId}] parse: Storing PE response for command.")
                    outgoingMessage.pim_response = 'PE'
                    outgoingMessage.pim_response_semaphore.release()
                }
                break
            case 'PB':
                logWarn("[${device.deviceNetworkId}] parse: Processing PB response.")
                if (outgoingMessage.containsKey('pim_response')) {
                    logDebug("[${device.deviceNetworkId}] parse: Storing PB response for command.")
                    outgoingMessage.pim_response = 'PB'
                    outgoingMessage.pim_response_semaphore.release()
                }
                break
            case 'PK':
                logDebug("[${device.deviceNetworkId}] parse: Processing PK response.")
                if (outgoingMessage.containsKey('pim_ack')) {
                    outgoingMessage.pim_ack = 'PK'
                    outgoingMessage.pim_ack_semaphore.release()
                }
                break
            case 'PN':
                logDebug("[${device.deviceNetworkId}] parse: Processing PN response.")
                if (outgoingMessage.containsKey('pim_ack')) {
                    outgoingMessage.pim_ack = 'PN'
                    outgoingMessage.pim_ack_semaphore.release()
                }
                break
            case 'PR':
                logDebug("[${device.deviceNetworkId}] parse: Processing PR response with data=0x%s.", HexUtils.byteArrayToHexString(messageData))
                def register_data = null
                try {
                    register_data = parseRegisterReport(messageData)
                } catch (IllegalArgumentException e) {
                    logError("[${device.deviceNetworkId}] parse: Failed to parse PR data: %s.", e.message)
                }
                if (outgoingMessage.containsKey('pim_response')) {
                    logDebug("[${device.deviceNetworkId}] parse: Storing PR response for command.")
                    outgoingMessage.pim_response = 'PR'
                    outgoingMessage.pim_response_data = register_data
                    outgoingMessage.pim_response_semaphore.release()
                }
                break
            case 'PU':
                logDebug("[${device.deviceNetworkId}] parse: Processing PU response.")
                def packet = null
                try {
                    packet = parsePacket(messageData)
                    runIn(0, "asyncParseMessageReport", [data: [messageData: messageData]])
                } catch (IllegalArgumentException e) {
                    logError("[${device.deviceNetworkId}] parse: Failed to parse PU data: %s.", e.message)
                }
                if (outgoingMessage.containsKey('response_data')) {
                    logDebug("[${device.deviceNetworkId}] parse: Checking PU response: networkId=0x%02X, sourceId=0x%02X.", packet?.networkId, packet?.sourceId)
                    if (outgoingMessage.networkId == packet?.networkId && outgoingMessage.destinationId == packet?.sourceId) {
                        logDebug("[${device.deviceNetworkId}] parse: Storing matching UPB_DEVICE_STATE report: args=%s.", packet?.messageArgs)
                        outgoingMessage.response_data = packet
                        outgoingMessage.response_data_semaphore.release()
                    } else {
                        logDebug("[${device.deviceNetworkId}] parse: PU response mismatch: expected networkId=0x%02X, sourceId=0x%02X; got networkId=0x%02X, sourceId=0x%02X.",
                                outgoingMessage.networkId, outgoingMessage.destinationId, packet?.networkId, packet?.sourceId)
                    }
                } else {
                    logDebug("[${device.deviceNetworkId}] parse: Received UPB_DEVICE_STATE report: args=%s, no command waiting.", packet?.messageArgs)
                }
                break
            default:
                logError("[${device.deviceNetworkId}] parse: Unknown message type: %s.", messageType)
                setDeviceStatus("error", "Message parsing failed: Unknown message type: ${messageType}", true)
                return
        }
        setModuleStatus("Active")
        setDeviceStatus("ok")
    } catch (IllegalStateException e) {
        logError("[${device.deviceNetworkId}] parse: Failed due to illegal state: %s.", e.message)
        setModuleStatus("Inactive", e.message)
        setDeviceStatus("error", e.message, true)
    } catch (Exception e) {
        logError("[${device.deviceNetworkId}] parse: Unexpected error: %s.", e.message)
        setModuleStatus("Inactive", e.message)
        setDeviceStatus("error", "Unexpected error: ${e.message}", true)
    }
    logTrace("[${device.deviceNetworkId}] parse: Exiting.")
}

/***************************************************************************
 * Custom Driver Functions
 ***************************************************************************/
/**
 * Called by initialize() and reconnectSocket().
 * Opens the raw TCP socket to the serial-to-network adapter configured for the PIM.
 */
def openSocket() {
    logTrace("[${device.deviceNetworkId}] openSocket: Entering.")
    try {
        isCorrectParent()
        try {
            interfaces.rawSocket.connect(settings.ipAddress, settings.portNumber.toInteger(), byteInterface: true, eol: '\r')
            logInfo("[${device.deviceNetworkId}] openSocket: Connected to %s:%d.", ipAddress, portNumber)
            setNetworkStatus("Connected")
            setDeviceStatus("ok")
            return true
        } catch (Exception e) {
            logError("[${device.deviceNetworkId}] openSocket: Connection failed to %s:%d: %s.", ipAddress, portNumber, e.message)
            setNetworkStatus("Connect failed", e.message)
            setModuleStatus("Inactive", "Failed to connect to socket")
            setDeviceStatus("error", "Failed to connect to socket: ${e.message}", true)
            return false
        }
    } catch (IllegalStateException e) {
        logError("[${device.deviceNetworkId}] openSocket: Illegal state: %s.", e.message)
        setModuleStatus("Inactive", e.message)
        setDeviceStatus("error", e.message, true)
        return false
    }
    logTrace("[${device.deviceNetworkId}] openSocket: Exiting.")
}

/**
 * Called by uninstall and reconnect paths.
 * Closes the raw TCP socket and publishes disconnected state.
 */
def closeSocket() {
    logTrace("[${device.deviceNetworkId}] closeSocket: Entering.")
    try {
        interfaces.rawSocket.close()
        logInfo("[${device.deviceNetworkId}] closeSocket: Disconnected socket.")
        setNetworkStatus("Disconnected")
        setModuleStatus("Inactive")
        setDeviceStatus("ok")
        return true
    } catch (Exception e) {
        logWarn("[${device.deviceNetworkId}] closeSocket: Disconnect failed: %s.", e.message)
        setNetworkStatus("Disconnected", e.message)
        setModuleStatus("Inactive", "Failed to disconnect from socket")
        setDeviceStatus("error", "Failed to disconnect from socket: ${e.message}", true)
        return false
    }
    logTrace("[${device.deviceNetworkId}] closeSocket: Exiting.")
}

/**
 * Called by scheduled reconnect attempts after socket errors.
 * Reopens the socket and returns the PIM to message mode.
 */
def reconnectSocket() {
    logTrace("[${device.deviceNetworkId}] reconnectSocket: Entering.")
    try {
        if (openSocket()) {
            if (setPIMCommandMode()) {
                logInfo("[${device.deviceNetworkId}] reconnectSocket: Reconnected successfully.")
                setDeviceStatus("ok")
                return
            }
            logError("[${device.deviceNetworkId}] reconnectSocket: Failed to set PIM command mode.")
            throw new Exception("Failed to set PIM command mode during reconnect")
        }
        logError("[${device.deviceNetworkId}] reconnectSocket: Failed to open socket.")
        throw new Exception("Failed to open socket during reconnect")
    } catch (Exception e) {
        logError("[${device.deviceNetworkId}] reconnectSocket: Reconnect failed: %s.", e.message)
        setModuleStatus("Inactive", e.message)
        setDeviceStatus("error", e.message, true)
        logInfo("[${device.deviceNetworkId}] reconnectSocket: Scheduling reconnect in %d seconds.", reconnectInterval)
        runIn(reconnectInterval, reconnectSocket)
    }
    logTrace("[${device.deviceNetworkId}] reconnectSocket: Exiting.")
}

/**
 * Called by PIM command helpers after a frame has been encoded.
 * Writes raw bytes to Hubitat's socket interface.
 */
private def sendBytes(byte[] bytes) {
    logTrace("[${device.deviceNetworkId}] sendBytes: Sending bytes=0x%s.", HexUtils.byteArrayToHexString(bytes))
    def hexString = HexUtils.byteArrayToHexString(bytes)
    interfaces.rawSocket.sendMessage(hexString)
}

/**
 * Called by the parent app or future configuration API.
 * Updates the configured serial-to-network adapter IP address.
 */
def setIPAddress(String ipAddress) {
    logTrace("[${device.deviceNetworkId}] setIPAddress: Entering with ipAddress=%s.", ipAddress)
    try {
        isCorrectParent()
        logInfo("[${device.deviceNetworkId}] setIPAddress: Setting IP address to %s.", ipAddress)
        device.updateSetting("ipAddress", [value: ipAddress, type: "text"])
        setDeviceStatus("ok")
    } catch (IllegalStateException e) {
        logError("[${device.deviceNetworkId}] setIPAddress: Illegal state: %s.", e.message)
        setModuleStatus("Inactive", e.message)
        setDeviceStatus("error", e.message, true)
    } catch (Exception e) {
        logError("[${device.deviceNetworkId}] setIPAddress: Unexpected error: %s.", e.message)
        setModuleStatus("Inactive", e.message)
        setDeviceStatus("error", e.message, true)
    }
    logTrace("[${device.deviceNetworkId}] setIPAddress: Exiting.")
}

/**
 * Called by the parent app or future configuration API.
 * Updates the configured serial-to-network adapter TCP port.
 */
def setPortNumber(int portNumber) {
    logTrace("[${device.deviceNetworkId}] setPortNumber: Entering with portNumber=%d.", portNumber)
    try {
        isCorrectParent()
        logInfo("[${device.deviceNetworkId}] setPortNumber: Setting port number to %d.", portNumber)
        device.updateSetting("portNumber", [value: portNumber, type: "number"])
        setDeviceStatus("ok")
    } catch (IllegalStateException e) {
        logError("[${device.deviceNetworkId}] setPortNumber: Illegal state: %s.", e.message)
        setModuleStatus("Inactive", e.message)
        setDeviceStatus("error", e.message, true)
    } catch (Exception e) {
        logError("[${device.deviceNetworkId}] setPortNumber: Unexpected error: %s.", e.message)
        setModuleStatus("Inactive", e.message)
        setDeviceStatus("error", e.message, true)
    }
    logTrace("[${device.deviceNetworkId}] setPortNumber: Exiting.")
}

/**
 * Called after socket initialization and reconnects.
 * Writes the PIM register value needed for UPB message mode.
 */
def setPIMCommandMode() {
    logTrace("[${device.deviceNetworkId}] setPIMCommandMode: Entering.")
    def result = writePimRegister((byte) 0x70, [0x02] as byte[])
    if (result.result) {
        logInfo("[${device.deviceNetworkId}] setPIMCommandMode: PIM set to Message Mode successfully.")
        setModuleStatus("Active")
        setDeviceStatus("ok")
        logTrace("[${device.deviceNetworkId}] setPIMCommandMode: Exiting with success.")
        return true
    } else {
        logError("[${device.deviceNetworkId}] setPIMCommandMode: Failed to set PIM to Message Mode: %s.", result.reason)
        setModuleStatus("Inactive")
        setDeviceStatus("error", "Failed to set PIM to Message Mode: ${result.reason}", true)
        logTrace("[${device.deviceNetworkId}] setPIMCommandMode: Exiting with failure.")
        return false
    }
}

/**
 * Called by PIM setup and diagnostic flows.
 * Sends a PIM register read command and waits for the matching PR/PB/PE response.
 */
def readPimRegister(byte register, int numRegisters) {
    logTrace("[${device.deviceNetworkId}] readPimRegister: Entering with register=0x%02X, numRegisters=%d.", register, numRegisters)

    if (numRegisters < 1 || numRegisters > 16) {
        logError("[${device.deviceNetworkId}] readPimRegister: Invalid number of registers: %d (must be 1-16).", numRegisters)
        return [result: false, reason: "Invalid number of registers: ${numRegisters} (must be 1-16)"]
    }

    deviceMutexes.putIfAbsent(device.deviceNetworkId, new Object())
    currentOutgoing.putIfAbsent(device.deviceNetworkId, [:])

    def packet = new ByteArrayOutputStream()
    packet.write([register, numRegisters] as byte[])
    packet.write(checksum(packet.toByteArray()))
    byte[] encodedPacket = HexUtils.byteArrayToHexString(packet.toByteArray()).getBytes()
    logDebug("[${device.deviceNetworkId}] readPimRegister: Encoded packet: %s.", new String(encodedPacket))

    def message = new ByteArrayOutputStream()
    message.write(READ_REGISTER)
    message.write(encodedPacket)
    message.write(EOM)
    byte[] bytes = message.toByteArray()

    synchronized (deviceMutexes.get(device.deviceNetworkId)) {
        def outgoing = [
                pim_response: null,
                pim_response_data: null,
                pim_response_semaphore: new Semaphore(0)
        ]
        Map result = [result: false, reason: "Unknown failure"]
        def retry = 0
        loop: while (true) {
            outgoing.pim_response = null
            outgoing.register_data = null
            outgoing.pim_response_semaphore.drainPermits()
            currentOutgoing.put(device.deviceNetworkId, outgoing)

            logDebug("[${device.deviceNetworkId}] readPimRegister: Sending message, attempt %d/%d.", retry + 1, maxRetry)
            sendBytes(bytes)

            if (outgoing.pim_response_semaphore.tryAcquire(maxProcessingTime, TimeUnit.MILLISECONDS)) {
                switch (outgoing.pim_response) {
                    case 'PR':
                        def registerData = outgoing.pim_response_data
                        if (registerData?.register != null && registerData?.values != null) {
                            if (registerData.register == register && registerData.values.length == numRegisters) {
                                logDebug("[${device.deviceNetworkId}] readPimRegister: Received valid PR response: register=0x%02X, values=%s.", registerData.register, registerData.values)
                                result = [result: true, reason: "Success", data: registerData]
                            } else {
                                logError("[${device.deviceNetworkId}] readPimRegister: Invalid PR response: register=0x%02X (expected 0x%02X), values.length=%d (expected %d).",
                                        registerData.register, register, registerData.values.length, numRegisters)
                                result = [result: false, reason: "PR response register or values mismatch"]
                            }
                        } else {
                            logError("[${device.deviceNetworkId}] readPimRegister: Invalid or failed PR response.")
                            result = [result: false, reason: "Invalid or failed PR response"]
                        }
                        break loop
                    case 'PB':
                        logWarn("[${device.deviceNetworkId}] readPimRegister: PIM busy (PB), retrying %d/%d.", retry + 1, maxRetry)
                        break
                    case 'PE':
                        logError("[${device.deviceNetworkId}] readPimRegister: PIM rejected message (PE).")
                        result = [result: false, reason: "PIM rejected message (PE)"]
                        break loop
                    default:
                        logWarn("[${device.deviceNetworkId}] readPimRegister: Invalid or no PR/PB/PE response, retrying %d/%d.", retry + 1, maxRetry)
                        break
                }
            } else {
                logWarn("[${device.deviceNetworkId}] readPimRegister: Timeout waiting for PR/PB/PE, retrying %d/%d.", retry + 1, maxRetry)
            }

            if (++retry >= maxRetry) {
                logError("[${device.deviceNetworkId}] readPimRegister: Transaction failed: Maximum retries reached for register=0x%02X.", register)
                result = [result: false, reason: "Maximum retries reached"]
                break loop
            }
            pauseExecution(100)
        }

        currentOutgoing.put(device.deviceNetworkId, [:])
        logDebug("[${device.deviceNetworkId}] readPimRegister: Cleared outgoing state.")
        logTrace("[${device.deviceNetworkId}] readPimRegister: Exiting with result=%s.", result)
        return result
    }
}

/**
 * Called by PIM setup flows such as setPIMCommandMode().
 * Sends a PIM register write command and waits for the PA/PB/PE response.
 */
def writePimRegister(byte register, byte[] values) {
    logTrace("[${device.deviceNetworkId}] writePimRegister: Entering with register=0x%02X, values=%s.", register, values)

    if (values.length < 1 || values.length > 16) {
        logError("[${device.deviceNetworkId}] writePimRegister: Invalid number of values: %d (must be 1-16).", values.length)
        return [result: false, reason: "Invalid number of values: ${values.length} (must be 1-16)"]
    }

    deviceMutexes.putIfAbsent(device.deviceNetworkId, new Object())
    currentOutgoing.putIfAbsent(device.deviceNetworkId, [:])

    def packet = new ByteArrayOutputStream()
    packet.write(register)
    packet.write(values)
    packet.write(checksum(packet.toByteArray()))
    byte[] encodedPacket = HexUtils.byteArrayToHexString(packet.toByteArray()).getBytes()
    logDebug("[${device.deviceNetworkId}] writePimRegister: Encoded packet: %s.", new String(encodedPacket))

    def message = new ByteArrayOutputStream()
    message.write(WRITE_REGISTER)
    message.write(encodedPacket)
    message.write(EOM)
    byte[] bytes = message.toByteArray()

    synchronized (deviceMutexes.get(device.deviceNetworkId)) {
        def outgoing = [
                pim_response: null,
                pim_response_semaphore: new Semaphore(0)
        ]
        Map result = [result: false, reason: "Unknown failure"]
        def retry = 0
        loop: while (true) {
            outgoing.pim_response = null
            outgoing.pim_response_semaphore.drainPermits()
            currentOutgoing.put(device.deviceNetworkId, outgoing)

            logDebug("[${device.deviceNetworkId}] writePimRegister: Sending message, attempt %d/%d.", retry + 1, maxRetry)
            sendBytes(bytes)

            if (outgoing.pim_response_semaphore.tryAcquire(maxProcessingTime, TimeUnit.MILLISECONDS)) {
                switch (outgoing.pim_response) {
                    case 'PA':
                        logDebug("[${device.deviceNetworkId}] writePimRegister: PIM accepted message (PA).")
                        result = [result: true, reason: "Success"]
                        break loop
                    case 'PB':
                        logWarn("[${device.deviceNetworkId}] writePimRegister: PIM busy (PB), retrying %d/%d.", retry + 1, maxRetry)
                        break
                    case 'PE':
                        logError("[${device.deviceNetworkId}] writePimRegister: PIM rejected message (PE).")
                        result = [result: false, reason: "PIM rejected message (PE)"]
                        break loop
                    default:
                        logWarn("[${device.deviceNetworkId}] writePimRegister: Invalid or no PA/PB/PE response, retrying %d/%d.", retry + 1, maxRetry)
                        break
                }
            } else {
                logWarn("[${device.deviceNetworkId}] writePimRegister: Timeout waiting for PA/PB/PE, retrying %d/%d.", retry + 1, maxRetry)
            }

            if (++retry >= maxRetry) {
                logError("[${device.deviceNetworkId}] writePimRegister: Transaction failed: Maximum retries reached for register=0x%02X.", register)
                result = [result: false, reason: "Maximum retries reached"]
                break loop
            }
            pauseExecution(100)
        }

        currentOutgoing.put(device.deviceNetworkId, [:])
        logDebug("[${device.deviceNetworkId}] writePimRegister: Cleared outgoing state.")
        logTrace("[${device.deviceNetworkId}] writePimRegister: Exiting with result=%s.", result)
        return result
    }
}

/**
 * Called by the parent app to send a UPB packet through the PIM.
 * Serializes one PIM transmit transaction and waits for PA/PB/PE plus PK/PN/PU as required.
 */
def transmitMessage(short controlWord, byte networkId, byte destinationId, byte sourceId, byte messageDataId, byte[] messageArgument) {
    logTrace("[${device.deviceNetworkId}] transmitMessage: Entering with controlWord=0x%04X, networkId=0x%02X, destinationId=0x%02X, sourceId=0x%02X, messageDataId=0x%02X, messageArgument=%s.",
            controlWord, networkId & 0xFF, destinationId & 0xFF, sourceId & 0xFF, messageDataId & 0xFF, messageArgument ? "0x${HexUtils.byteArrayToHexString(messageArgument)}" : "null")
    logDebug("[${device.deviceNetworkId}] transmitMessage: ACK flags: pulse=%s, msg=%s, id=%s.", (controlWord & ACKRQ_PULSE) != 0, (controlWord & ACKRQ_MSG) != 0, (controlWord & ACKRQ_ID) != 0)

    deviceMutexes.putIfAbsent(device.deviceNetworkId, new Object())
    currentOutgoing.putIfAbsent(device.deviceNetworkId, [:])

    byte[] packet = buildPacket(controlWord, networkId, destinationId, sourceId, messageDataId, messageArgument)
    byte[] encodedPacket = HexUtils.byteArrayToHexString(packet).getBytes()
    logDebug("[${device.deviceNetworkId}] transmitMessage: Encoded packet: %s.", new String(encodedPacket))

    def message = new ByteArrayOutputStream()
    message.write(TRANSMIT_MESSAGE)
    message.write(encodedPacket)
    message.write(EOM)
    byte[] bytesToSend = message.toByteArray()

    synchronized (deviceMutexes.get(device.deviceNetworkId)) {
        def expectsPU = (messageDataId == UPB_REPORT_STATE)
        def expectsAck = (controlWord & ACKRQ_PULSE) != 0

        def outgoing = [
                pim_response: null,
                pim_response_semaphore: new Semaphore(0),
                pim_ack: null,
                pim_ack_semaphore: new Semaphore(0),
                controlWord: controlWord
        ]
        if (expectsPU) {
            outgoing.response_data = null
            outgoing.response_data_semaphore = new Semaphore(0)
            outgoing.networkId = networkId
            outgoing.destinationId = destinationId
        }

        Map result = [result: false, reason: "Transaction failed after all retries."]
        int retry = 0

        try {
            loop: while (retry < maxRetry) {
                outgoing.pim_response = null
                outgoing.pim_ack = null
                outgoing.pim_response_semaphore.drainPermits()
                outgoing.pim_ack_semaphore.drainPermits()
                if (expectsPU) {
                    outgoing.response_data = null
                    outgoing.response_data_semaphore?.drainPermits()
                }
                currentOutgoing.put(device.deviceNetworkId, outgoing)

                logDebug("[${device.deviceNetworkId}] transmitMessage: Sending message, attempt %d/%d.", retry + 1, maxRetry)
                sendBytes(bytesToSend)

                if (!outgoing.pim_response_semaphore.tryAcquire(maxProcessingTime, TimeUnit.MILLISECONDS)) {
                    logWarn("[${device.deviceNetworkId}] transmitMessage: Timeout waiting for PA/PB/PE, retrying %d/%d.", retry + 1, maxRetry)
                    retry++
                    pauseExecution(100)
                    continue loop
                }

                switch (outgoing.pim_response) {
                    case 'PA':
                        logDebug("[${device.deviceNetworkId}] transmitMessage: PIM accepted message (PA).")
                        break
                    case 'PB':
                        logWarn("[${device.deviceNetworkId}] transmitMessage: PIM busy (PB), retrying %d/%d.", retry + 1, maxRetry)
                        retry++
                        pauseExecution(100)
                        continue loop
                    case 'PE':
                        logError("[${device.deviceNetworkId}] transmitMessage: PIM rejected message (PE).")
                        result = [result: false, reason: "PIM rejected message (PE)"]
                        break loop
                    default:
                        logError("[${device.deviceNetworkId}] transmitMessage: Invalid PIM response: %s.", outgoing.pim_response)
                        result = [result: false, reason: "Invalid PIM response: ${outgoing.pim_response}"]
                        break loop
                }

                if (!outgoing.pim_ack_semaphore.tryAcquire(maxProcessingTime, TimeUnit.MILLISECONDS)) {
                    logWarn("[${device.deviceNetworkId}] transmitMessage: Timeout waiting for PK/PN, retrying %d/%d.", retry + 1, maxRetry)
                    retry++
                    pauseExecution(100)
                    continue loop
                }

                if (outgoing.pim_ack == 'PK' && !expectsAck) {
                    logError("[${device.deviceNetworkId}] transmitMessage: Unexpected PK received, expected PN as ACKRQ_PULSE not set.")
                    result = [result: false, reason: "Unexpected PK received"]
                    break loop
                }
                if (outgoing.pim_ack == 'PN' && expectsAck) {
                    logError("[${device.deviceNetworkId}] transmitMessage: Received NAK (PN), expected ACK pulse.")
                    result = [result: false, reason: "NoAck: Device did not acknowledge message (PN)"]
                    break loop
                }
                logDebug("[${device.deviceNetworkId}] transmitMessage: Transmission completed with %s.", outgoing.pim_ack)

                if (expectsPU) {
                    if (!outgoing.response_data_semaphore.tryAcquire(maxProcessingTime, TimeUnit.MILLISECONDS)) {
                        logWarn("[${device.deviceNetworkId}] transmitMessage: Timeout waiting for PU, retrying %d/%d.", retry + 1, maxRetry)
                        retry++
                        pauseExecution(100)
                        continue loop
                    }

                    def response = outgoing.response_data
                    if (response && response.networkId == outgoing.networkId &&
                            response.sourceId == outgoing.destinationId &&
                            response.messageDataId == UPB_DEVICE_STATE) {
                        logDebug("[${device.deviceNetworkId}] transmitMessage: Received valid UPB_DEVICE_STATE report: args=%s.", response.messageArgs)
                        result = [result: true, reason: "Success", data: response.messageArgs]
                    } else {
                        logError("[${device.deviceNetworkId}] transmitMessage: Invalid or mismatched PU response: %s.", response)
                        result = [result: false, reason: "Invalid data response (PU)"]
                    }
                } else {
                    result = [result: true, reason: "Success"]
                }
                break loop
            }

            if (!result.result && retry >= maxRetry) {
                logError("[${device.deviceNetworkId}] transmitMessage: Transaction failed: Maximum retries reached.")
                result.reason = "Transaction failed: Maximum retries reached."
            }
        } finally {
            currentOutgoing.put(device.deviceNetworkId, [:])
            logDebug("[${device.deviceNetworkId}] transmitMessage: Cleared outgoing state.")
        }
        logTrace("[${device.deviceNetworkId}] transmitMessage: Exiting with result=%s.", result)
        return result
    }
}

/**
 * Called by runIn(0) from parse() for PU message reports.
 * Processes observed UPB packets outside the immediate socket parser callback.
 */
def asyncParseMessageReport(Map data) {
    logTrace("[${device.deviceNetworkId}] asyncParseMessageReport: Entering.")
    try {
        def messageData = data?.messageData
        if (messageData) {
            logDebug("[${device.deviceNetworkId}] asyncParseMessageReport: Processing message data.")
            processMessage(messageData.collect { it as byte } as byte[])
        } else {
            logWarn("[${device.deviceNetworkId}] asyncParseMessageReport: No message data provided.")
        }
    } catch (Exception e) {
        logError("[${device.deviceNetworkId}] asyncParseMessageReport: Unexpected error: %s.", e.message)
    }
    logTrace("[${device.deviceNetworkId}] asyncParseMessageReport: Exiting.")
}

/**
 * Called by asyncParseMessageReport() for each parsed UPB message packet.
 * Routes link events, direct device events, and state reports to the parent app.
 */
def processMessage(byte[] data) {
    logTrace("[${device.deviceNetworkId}] processMessage: Entering with data=0x%s.", HexUtils.byteArrayToHexString(data))
    def messageDataString = HexUtils.byteArrayToHexString(data)

    try {
        def packet = parsePacket(data)
        def controlWord = packet.controlWord
        def networkId = packet.networkId
        def destinationId = packet.destinationId
        def sourceId = packet.sourceId
        def messageDataId = packet.messageDataId
        def messageSetId = packet.messageSetId
        def messageArgs = packet.messageArgs

        logDebug("[${device.deviceNetworkId}] processMessage: Handling %s (0x%02X): controlWord=0x%04X, networkId=0x%02X, sourceId=0x%02X, destinationId=0x%02X, messageDataId=0x%02X, args=%s.",
                getMsidName(messageSetId), messageSetId, controlWord, networkId, sourceId, destinationId, messageDataId, messageArgs)

        def args = messageArgs.collect { it & 0xFF } as int[]
        switch (messageSetId) {
            case UPB_CORE_COMMAND:
                handleCommand(getMdidName(messageDataId), networkId, sourceId, destinationId, args)
                break
            case UPB_DEVICE_CONTROL_COMMAND:
                if (messageDataId in [UPB_ACTIVATE_LINK, UPB_DEACTIVATE_LINK]) {
                    logDebug("[${device.deviceNetworkId}] processMessage: Handling link event: %s.", getMdidName(messageDataId))
                    getParent().handleLinkEvent("pim", getMdidName(messageDataId), networkId & 0xFF, sourceId & 0xFF, destinationId & 0xFF)
                } else if (messageDataId in [UPB_GOTO, UPB_REPORT_STATE]) {
                    logDebug("[${device.deviceNetworkId}] processMessage: Handling device event: %s.", getMdidName(messageDataId))
                    getParent().handleDeviceEvent("pim", getMdidName(messageDataId), networkId & 0xFF, sourceId & 0xFF, destinationId & 0xFF, args)
                } else {
                    handleCommand(getMdidName(messageDataId), networkId, sourceId, destinationId, args)
                }
                break
            case UPB_CORE_REPORTS:
                if (messageDataId == UPB_DEVICE_STATE && args.size() >= 1) {
                    logDebug("[${device.deviceNetworkId}] processMessage: Handling device event: %s.", getMdidName(messageDataId))
                    getParent().handleDeviceEvent("pim", getMdidName(messageDataId), networkId & 0xFF, sourceId & 0xFF, destinationId & 0xFF, args)
                } else {
                    handleCommand(getMdidName(messageDataId), networkId, sourceId, destinationId, args)
                }
                break
            case UPB_EXTENDED_MESSAGE_SET:
                logWarn("[${device.deviceNetworkId}] processMessage: Unsupported extended message: MDID=0x%02X.", messageDataId)
                break
        }
    } catch (IllegalArgumentException e) {
        logError("[${device.deviceNetworkId}] processMessage: Failed to process message 0x%s: %s.", messageDataString, e.message)
    }
    logTrace("[${device.deviceNetworkId}] processMessage: Exiting.")
}

/**
 * Called by processMessage() for observed UPB traffic this integration does not act on.
 * Keeps unsupported but valid network chatter visible at debug level.
 */
private void handleCommand(String commandName, byte networkId, byte sourceId, byte destinationId, int[] args) {
    logDebug("[${device.deviceNetworkId}] handleCommand: Handling %s: networkId=0x%02X, sourceId=0x%02X, destinationId=0x%02X, args=%s.",
            commandName, networkId, sourceId, destinationId, args)
}
