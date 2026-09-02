/*
 * Hubitat Driver: UPB Single-Speed Fan
 * Description: Universal Powerline Bus UPB Single-Speed Fan Driver (On/Off only)
 * Copyright: 2026 UPBeat Automation
 * Licensed: Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International License
 * Author: UPBeat Automation
 */
#include UPBeat.UPBeatLogger
#include UPBeat.UPBeatLib
#include UPBeat.UPBeatDriverLib
import groovy.json.JsonSlurper
import groovy.json.JsonOutput

metadata {
    definition(name: "UPB Single-Speed Fan", namespace: "UPBeat", author: "UPBeat Automation", importUrl: "") {
        capability "Switch"
        capability "FanControl"
        capability "Refresh"
        command "setSpeed", [[name:"speed", type: "ENUM", constraints: SPEED_TO_LEVEL.keySet().toList(), description: "Set the fan speed (${SPEED_TO_LEVEL.keySet().join(", ")})"]]
        attribute "status", "enum", ["ok", "error"]
        attribute "speed", "enum", SPEED_TO_LEVEL.keySet().toList()
    }

    preferences {
        input name: "logLevel", type: "enum", options: LOG_LEVELS, title: "Log Level", defaultValue: LOG_DEFAULT_LEVEL, required: true
        input name: "networkId", type: "number", title: "Network ID", description: "UPB Network ID (0-255)", required: true, range: "0..255"
        input name: "deviceId", type: "number", title: "Device ID", description: "UPB Device ID (1-250)", required: true, range: "1..250"
        input name: "channelId", type: "number", title: "Channel ID", description: "UPB Channel ID (1-255)", required: true, range: "1..255"
        input name: "receiveComponent1", type: "text", title: "Receive Component 1", description: "Format: linkId:level"
        input name: "receiveComponent2", type: "text", title: "Receive Component 2", description: "Format: linkId:level"
        input name: "receiveComponent3", type: "text", title: "Receive Component 3", description: "Format: linkId:level"
        input name: "receiveComponent4", type: "text", title: "Receive Component 4", description: "Format: linkId:level"
        input name: "receiveComponent5", type: "text", title: "Receive Component 5", description: "Format: linkId:level"
        input name: "receiveComponent6", type: "text", title: "Receive Component 6", description: "Format: linkId:level"
        input name: "receiveComponent7", type: "text", title: "Receive Component 7", description: "Format: linkId:level"
        input name: "receiveComponent8", type: "text", title: "Receive Component 8", description: "Format: linkId:level"
        input name: "receiveComponent9", type: "text", title: "Receive Component 9", description: "Format: linkId:level"
        input name: "receiveComponent10", type: "text", title: "Receive Component 10", description: "Format: linkId:level"
        input name: "receiveComponent11", type: "text", title: "Receive Component 11", description: "Format: linkId:level"
        input name: "receiveComponent12", type: "text", title: "Receive Component 12", description: "Format: linkId:level"
        input name: "receiveComponent13", type: "text", title: "Receive Component 13", description: "Format: linkId:level"
        input name: "receiveComponent14", type: "text", title: "Receive Component 14", description: "Format: linkId:level"
        input name: "receiveComponent15", type: "text", title: "Receive Component 15", description: "Format: linkId:level"
        input name: "receiveComponent16", type: "text", title: "Receive Component 16", description: "Format: linkId:level"
    }
}

/***************************************************************************
 * Global Static Data
 ***************************************************************************/
@Field static final Map SPEED_TO_LEVEL = [
        "off": 0,
        "high": 100
]

/***************************************************************************
 * Helper Functions
 ***************************************************************************/
/**
 * Called by fan command and receive handlers.
 * Converts a UPB dimmer level into the FanControl speed bucket used by this driver.
 */
private String levelToSpeed(int level) {
    logTrace("[${device.deviceNetworkId}] levelToSpeed: Entering with level=%d.", level)
    def sortedSpeeds = SPEED_TO_LEVEL.sort { it.value }
    def prevLevel = -1
    def selectedSpeed = sortedSpeeds.keySet().last()
    sortedSpeeds.each { speed, currLevel ->
        if (speed == "off" && level == 0) {
            selectedSpeed = speed
        } else if (level > prevLevel && level <= currLevel) {
            selectedSpeed = speed
        }
        prevLevel = currLevel
    }
    logTrace("[${device.deviceNetworkId}] levelToSpeed: Exiting with speed=%s.", selectedSpeed)
    return selectedSpeed
}

/**
 * Called by UPB receive handlers when this virtual FanControl is backed by a dimmer.
 * Updates Hubitat state directly when the observed UPB level is already a supported
 * fan speed; otherwise sends one command to normalize the physical dimmer level.
 */
private Map normalizeFanLevel(int level, String caller) {
    def speed = levelToSpeed(level)
    def expectedLevel = SPEED_TO_LEVEL[speed]
    def switchValue = (speed == "off") ? "off" : "on"

    if (expectedLevel != level) {
        logInfo("[${device.deviceNetworkId}] %s: Normalizing level=%d to switch=%s, speed=%s (level=%d).", caller, level, switchValue, speed, expectedLevel)
        return setSpeed(speed)
    }

    logDebug("[${device.deviceNetworkId}] %s: Received normalized level=%d, updating switch=%s, speed=%s.", caller, level, switchValue, speed)
    sendEvent(name: "switch", value: switchValue, isStateChange: true)
    sendEvent(name: "speed", value: speed, isStateChange: true)
    return [result: true]
}

/***************************************************************************
 * Core Driver Functions
 ***************************************************************************/
/**
 * Called by Hubitat when this child device is created.
 * Initializes fan state, stored receive-component data, and status.
 */
void installed() {
    logTrace("[${device.deviceNetworkId}] installed: Entering.")
    try {
        isCorrectParent()
        logInfo("[${device.deviceNetworkId}] installed: Installing UPB Single-Speed Fan.")
        device.updateDataValue("receiveComponents", JsonOutput.toJson([:]))
        sendEvent(name: "switch", value: "off", isStateChange: true)
        sendEvent(name: "speed", value: "off", isStateChange: true)
        sendEvent(name: "status", value: "ok", isStateChange: false)
        logInfo("[${device.deviceNetworkId}] installed: Driver installed successfully.")
    } catch (IllegalStateException e) {
        logError("[${device.deviceNetworkId}] installed: Illegal state: %s.", e.message)
        sendEvent(name: "status", value: "error", descriptionText: e.message, isStateChange: true)
    } catch (Exception e) {
        logError("[${device.deviceNetworkId}] installed: Unexpected error: %s.", e.message)
        sendEvent(name: "status", value: "error", descriptionText: e.message, isStateChange: true)
    }
    logTrace("[${device.deviceNetworkId}] installed: Exiting.")
}

/**
 * Called by Hubitat after device preferences are saved.
 * Lets the parent app validate/rename the child DNI and refreshes link receive-component data.
 */
def updated() {
    logTrace("[${device.deviceNetworkId}] updated: Entering.")
    try {
        isCorrectParent()
        logDebug("[${device.deviceNetworkId}] updated: Validating settings: networkId=%d, deviceId=%d, channelId=%d.",
                settings.networkId, settings.deviceId, settings.channelId)

        def result = parent.updateDeviceSettings(device, settings)
        if (result.success) {
            logInfo("[${device.deviceNetworkId}] updated: Device settings updated successfully.")
            sendEvent(name: "status", value: "ok", isStateChange: false)
        } else {
            logError("[${device.deviceNetworkId}] updated: Failed to update device settings: %s.", result.error)
            sendEvent(name: "status", value: "error", descriptionText: result.error, isStateChange: true)
            return
        }

        def components = getReceiveComponents()
        logDebug("[${device.deviceNetworkId}] updated: Parsed receive components: %s.", components)
        device.updateDataValue("receiveComponents", JsonOutput.toJson(components))

        state.clear()
        logInfo("[${device.deviceNetworkId}] updated: Cleared state.")
    } catch (IllegalStateException e) {
        logError("[${device.deviceNetworkId}] updated: Illegal state: %s.", e.message)
        sendEvent(name: "status", value: "error", descriptionText: e.message, isStateChange: true)
    } catch (Exception e) {
        logWarn("[${device.deviceNetworkId}] updated: Failed to update: %s.", e.message)
        sendEvent(name: "status", value: "error", descriptionText: e.message, isStateChange: true)
    }
    logTrace("[${device.deviceNetworkId}] updated: Exiting.")
}

/***************************************************************************
 * Handlers for Driver Data
 ***************************************************************************/
/**
 * Called by the parent app during UPE import or manual sync updates.
 * Stores the UPB network ID preference for this child channel.
 */
def updateNetworkId(Long networkId) {
    logTrace("[${device.deviceNetworkId}] updateNetworkId: Entering with networkId=%d.", networkId)
    try {
        isCorrectParent()
        logInfo("[${device.deviceNetworkId}] updateNetworkId: Updating network ID to %d.", networkId)
        device.updateSetting("networkId", [type: "number", value: networkId])
        sendEvent(name: "status", value: "ok", isStateChange: false)
    } catch (IllegalStateException e) {
        logError("[${device.deviceNetworkId}] updateNetworkId: Illegal state: %s.", e.message)
        sendEvent(name: "status", value: "error", descriptionText: e.message, isStateChange: true)
    } catch (Exception e) {
        logError("[${device.deviceNetworkId}] updateNetworkId: Unexpected error: %s.", e.message)
        sendEvent(name: "status", value: "error", descriptionText: e.message, isStateChange: true)
    }
    logTrace("[${device.deviceNetworkId}] updateNetworkId: Exiting.")
}

/**
 * Called by the parent app during UPE import or manual sync updates.
 * Stores the UPB module/unit ID preference for this child channel.
 */
def updateDeviceId(Long deviceId) {
    logTrace("[${device.deviceNetworkId}] updateDeviceId: Entering with deviceId=%d.", deviceId)
    try {
        isCorrectParent()
        logInfo("[${device.deviceNetworkId}] updateDeviceId: Updating device ID to %d.", deviceId)
        device.updateSetting("deviceId", [type: "number", value: deviceId])
        sendEvent(name: "status", value: "ok", isStateChange: false)
    } catch (IllegalStateException e) {
        logError("[${device.deviceNetworkId}] updateDeviceId: Illegal state: %s.", e.message)
        sendEvent(name: "status", value: "error", descriptionText: e.message, isStateChange: true)
    } catch (Exception e) {
        logError("[${device.deviceNetworkId}] updateDeviceId: Unexpected error: %s.", e.message)
        sendEvent(name: "status", value: "error", descriptionText: e.message, isStateChange: true)
    }
    logTrace("[${device.deviceNetworkId}] updateDeviceId: Exiting.")
}

/**
 * Called by the parent app during UPE import or manual sync updates.
 * Stores the real UPB channel number represented by this child device.
 */
def updateChannelId(Long channelId) {
    logTrace("[${device.deviceNetworkId}] updateChannelId: Entering with channelId=%d.", channelId)
    try {
        isCorrectParent()
        logInfo("[${device.deviceNetworkId}] updateChannelId: Updating channel ID to %d.", channelId)
        device.updateSetting("channelId", [type: "number", value: channelId])
        sendEvent(name: "status", value: "ok", isStateChange: false)
    } catch (IllegalStateException e) {
        logError("[${device.deviceNetworkId}] updateChannelId: Illegal state: %s.", e.message)
        sendEvent(name: "status", value: "error", descriptionText: e.message, isStateChange: true)
    } catch (Exception e) {
        logError("[${device.deviceNetworkId}] updateChannelId: Unexpected error: %s.", e.message)
        sendEvent(name: "status", value: "error", descriptionText: e.message, isStateChange: true)
    }
    logTrace("[${device.deviceNetworkId}] updateChannelId: Exiting.")
}

/**
 * Called by the parent app during UPE import or link configuration updates.
 * Stores one link-to-level receive component slot for later link event routing.
 */
def updateReceiveComponentSlot(int slot, int linkId, int level) {
    logTrace("[${device.deviceNetworkId}] updateReceiveComponentSlot: Entering with slot=%d, linkId=%d, level=%d.", slot, linkId, level)
    try {
        isCorrectParent()
        logInfo("[${device.deviceNetworkId}] updateReceiveComponentSlot: Updating slot %d to linkId=%d, level=%d.", slot, linkId, level)
        device.updateSetting("receiveComponent${slot}", [type: "string", value: "${linkId}:${level}"])
        sendEvent(name: "status", value: "ok", isStateChange: false)
    } catch (IllegalStateException e) {
        logError("[${device.deviceNetworkId}] updateReceiveComponentSlot: Illegal state: %s.", e.message)
        sendEvent(name: "status", value: "error", descriptionText: e.message, isStateChange: true)
    } catch (Exception e) {
        logError("[${device.deviceNetworkId}] updateReceiveComponentSlot: Unexpected error: %s.", e.message)
        sendEvent(name: "status", value: "error", descriptionText: e.message, isStateChange: true)
    }
    logTrace("[${device.deviceNetworkId}] updateReceiveComponentSlot: Exiting.")
}

/***************************************************************************
 * Handlers for Driver Capabilities
 ***************************************************************************/
/**
 * Called by Hubitat Refresh or the parent app's refresh-all action.
 * Requests a UPB state report from the physical module backing this child.
 */
def refresh() {
    logTrace("[${device.deviceNetworkId}] refresh: Entering.")
    try {
        isCorrectParent()
    } catch (IllegalStateException e) {
        logError("[${device.deviceNetworkId}] refresh: Illegal state: %s.", e.message)
        sendEvent(name: "status", value: "error", descriptionText: e.message, isStateChange: true)
        return [result: false, reason: e.message]
    }

    if (!isValidIntegerSetting(settings.networkId, 0, 255)) {
        logError("[${device.deviceNetworkId}] refresh: Invalid network ID: %d (must be 0-255).", settings.networkId)
        sendEvent(name: "status", value: "error", descriptionText: "Network ID must be 0-255", isStateChange: true)
        return [result: false, reason: "Network ID must be 0-255"]
    }
    if (!isValidIntegerSetting(settings.deviceId, 1, 250)) {
        logError("[${device.deviceNetworkId}] refresh: Invalid device ID: %d (must be 1-250).", settings.deviceId)
        sendEvent(name: "status", value: "error", descriptionText: "Device ID must be 1-250", isStateChange: true)
        return [result: false, reason: "Device ID must be 1-250"]
    }

    logDebug("[${device.deviceNetworkId}] refresh: Requesting device state for networkId=0x%02X, deviceId=0x%02X.", settings.networkId, settings.deviceId)
    def result = getParent().requestDeviceState(settings.networkId.intValue(), settings.deviceId.intValue(), 0)
    if (result.result) {
        logInfo("[${device.deviceNetworkId}] refresh: Device state request succeeded.")
        sendEvent(name: "status", value: "ok", isStateChange: false)
    } else {
        logError("[${device.deviceNetworkId}] refresh: Device state request failed: %s.", result.reason)
        sendEvent(name: "status", value: "error", descriptionText: result.reason, isStateChange: true)
    }
    logTrace("[${device.deviceNetworkId}] refresh: Exiting with result=%s.", result)
    return result
}

/**
 * Called by Hubitat Switch capability commands.
 * Delegates to setSpeed("high") for the single supported on speed.
 */
def on() {
    logTrace("[${device.deviceNetworkId}] on: Entering.")
    try {
        isCorrectParent()
    } catch (IllegalStateException e) {
        logError("[${device.deviceNetworkId}] on: Illegal state: %s.", e.message)
        sendEvent(name: "status", value: "error", descriptionText: e.message, isStateChange: true)
        return [result: false, reason: e.message]
    }
    logDebug("[${device.deviceNetworkId}] on: Sending ON to deviceId=0x%02X.", settings.deviceId)
    def result = setSpeed("high")
    logTrace("[${device.deviceNetworkId}] on: Exiting with result=%s.", result)
    return result
}

/**
 * Called by Hubitat Switch capability commands.
 * Delegates to setSpeed("off") so command validation stays centralized.
 */
def off() {
    logTrace("[${device.deviceNetworkId}] off: Entering.")
    try {
        isCorrectParent()
    } catch (IllegalStateException e) {
        logError("[${device.deviceNetworkId}] off: Illegal state: %s.", e.message)
        sendEvent(name: "status", value: "error", descriptionText: e.message, isStateChange: true)
        return [result: false, reason: e.message]
    }
    logDebug("[${device.deviceNetworkId}] off: Sending OFF to deviceId=0x%02X.", settings.deviceId)
    def result = setSpeed("off")
    logTrace("[${device.deviceNetworkId}] off: Exiting with result=%s.", result)
    return result
}

/**
 * Called by Hubitat FanControl capability commands.
 * Advances between off and high for the single-speed fan model.
 */
def cycleSpeed() {
    logTrace("[${device.deviceNetworkId}] cycleSpeed: Entering.")
    try {
        isCorrectParent()
    } catch (IllegalStateException e) {
        logError("[${device.deviceNetworkId}] cycleSpeed: Illegal state: %s.", e.message)
        sendEvent(name: "status", value: "error", descriptionText: e.message, isStateChange: true)
        return [result: false, reason: e.message]
    }

    def currentSpeed = device.currentValue("speed") ?: "off"
    logDebug("[${device.deviceNetworkId}] cycleSpeed: Current speed: %s.", currentSpeed)
    def speeds = SPEED_TO_LEVEL.keySet().toList()
    def speedIndex = speeds.indexOf(currentSpeed)
    def nextSpeedIndex = (speedIndex + 1) % speeds.size()
    def nextSpeed = speeds[nextSpeedIndex]
    def result = setSpeed(nextSpeed)
    logTrace("[${device.deviceNetworkId}] cycleSpeed: Exiting with result=%s.", result)
    return result
}

/**
 * Called by Hubitat FanControl commands and by on()/off().
 * Sends the matching UPB dimmer level for the requested fan speed.
 */
def setSpeed(String speed) {
    logTrace("[${device.deviceNetworkId}] setSpeed: Entering with speed=%s.", speed)
    try {
        isCorrectParent()
    } catch (IllegalStateException e) {
        logError("[${device.deviceNetworkId}] setSpeed: Illegal state: %s.", e.message)
        sendEvent(name: "status", value: "error", descriptionText: e.message, isStateChange: true)
        return [result: false, reason: e.message]
    }

    if (!isValidIntegerSetting(settings.networkId, 0, 255)) {
        logError("[${device.deviceNetworkId}] setSpeed: Invalid network ID: %d (must be 0-255).", settings.networkId)
        sendEvent(name: "status", value: "error", descriptionText: "Network ID must be 0-255", isStateChange: true)
        return [result: false, reason: "Network ID must be 0-255"]
    }
    if (!isValidIntegerSetting(settings.deviceId, 1, 250)) {
        logError("[${device.deviceNetworkId}] setSpeed: Invalid device ID: %d (must be 1-250).", settings.deviceId)
        sendEvent(name: "status", value: "error", descriptionText: "Device ID must be 1-250", isStateChange: true)
        return [result: false, reason: "Device ID must be 1-250"]
    }
    if (!isValidIntegerSetting(settings.channelId, 1, 255)) {
        logError("[${device.deviceNetworkId}] setSpeed: Invalid channel ID: %d (must be 1-255).", settings.channelId)
        sendEvent(name: "status", value: "error", descriptionText: "Channel ID must be 1-255", isStateChange: true)
        return [result: false, reason: "Channel ID must be 1-255"]
    }
    if (!SPEED_TO_LEVEL.containsKey(speed)) {
        logError("[${device.deviceNetworkId}] setSpeed: Invalid speed: %s (supported: %s).", speed, SPEED_TO_LEVEL.keySet().join(", "))
        sendEvent(name: "status", value: "error", descriptionText: "Invalid speed: ${speed}", isStateChange: true)
        return [result: false, reason: "Invalid speed: ${speed}"]
    }

    def level = SPEED_TO_LEVEL[speed]
    logDebug("[${device.deviceNetworkId}] setSpeed: Setting speed=%s (level=%d%%) for networkId=0x%02X, deviceId=0x%02X, channelId=0x%02X.",
            speed, level, settings.networkId, settings.deviceId, settings.channelId)
    def result = getParent().gotoLevel(settings.networkId.intValue(), settings.deviceId.intValue(), 0, level, 0, settings.channelId.intValue())

    if (result.result) {
        logInfo("[${device.deviceNetworkId}] setSpeed: Command succeeded: switch=%s, speed=%s.", speed == "off" ? "off" : "on", speed)
        def switchValue = (speed == "off") ? "off" : "on"
        sendEvent(name: "switch", value: switchValue, isStateChange: true)
        sendEvent(name: "speed", value: speed, isStateChange: true)
        sendEvent(name: "status", value: "ok", isStateChange: false)
    } else {
        logError("[${device.deviceNetworkId}] setSpeed: Command failed: %s.", result.reason)
        sendEvent(name: "status", value: "error", descriptionText: result.reason, isStateChange: true)
    }
    logTrace("[${device.deviceNetworkId}] setSpeed: Exiting with result=%s.", result)
    return result
}

/***************************************************************************
 * UPB Receive Handlers
 ***************************************************************************/
/**
 * Called by the parent app after a UPB link packet is routed to this child.
 * Applies the programmed receive component level through fan speed normalization.
 */
def handleLinkEvent(String eventSource, String eventType, int networkId, int sourceId, int linkId) {
    logTrace("[${device.deviceNetworkId}] handleLinkEvent: Entering with eventSource=%s, eventType=%s, networkId=0x%02X, sourceId=0x%02X, linkId=%d.",
            eventSource, eventType, networkId, sourceId, linkId)
    try {
        isCorrectParent()
        def receiveComponents = [:]
        def jsonData = device.getDataValue("receiveComponents")
        if (jsonData) {
            logTrace("[${device.deviceNetworkId}] handleLinkEvent: Parsing receive components: %s.", jsonData)
            receiveComponents = new JsonSlurper().parseText(jsonData)
        }

        def linkIdKey = linkId.toString()
        def component = receiveComponents?.get(linkIdKey)
        if (component) {
            logDebug("[${device.deviceNetworkId}] handleLinkEvent: Found component for linkId=%d: level=%d.", linkId, component.level)
            switch (eventType) {
                case "UPB_ACTIVATE_LINK":
                    logInfo("[${device.deviceNetworkId}] handleLinkEvent: Processing UPB_ACTIVATE_LINK, applying level=%d.", component.level)
                    def result = normalizeFanLevel(component.level.toInteger(), "handleLinkEvent")
                    if (!result.result) {
                        return
                    }
                    break
                case "UPB_DEACTIVATE_LINK":
                    logInfo("[${device.deviceNetworkId}] handleLinkEvent: Processing UPB_DEACTIVATE_LINK, setting switch=off, speed=off.")
                    sendEvent(name: "switch", value: "off", isStateChange: true)
                    sendEvent(name: "speed", value: "off", isStateChange: true)
                    break
                default:
                    logError("[${device.deviceNetworkId}] handleLinkEvent: Unknown event type: %s.", eventType)
                    sendEvent(name: "status", value: "error", descriptionText: "Unknown event type: ${eventType}", isStateChange: true)
                    return
            }
            sendEvent(name: "status", value: "ok", isStateChange: false)
        } else {
            logDebug("[${device.deviceNetworkId}] handleLinkEvent: No action defined for linkId=%d.", linkId)
            sendEvent(name: "status", value: "ok", isStateChange: false)
        }
    } catch (IllegalStateException e) {
        logError("[${device.deviceNetworkId}] handleLinkEvent: Illegal state: %s.", e.message)
        sendEvent(name: "status", value: "error", descriptionText: e.message, isStateChange: true)
    } catch (Exception e) {
        logError("[${device.deviceNetworkId}] handleLinkEvent: Unexpected error: %s.", e.message)
        sendEvent(name: "status", value: "error", descriptionText: e.message, isStateChange: true)
    }
    logTrace("[${device.deviceNetworkId}] handleLinkEvent: Exiting.")
}

/**
 * Called by the parent app when the PIM observes a direct UPB Goto for this child.
 * Converts the observed UPB level into Hubitat switch and fan speed state.
 */
def handleGotoEvent(String eventSource, String eventType, int networkId, int sourceId, int destinationId, int level, int rate, int channel) {
    logTrace("[${device.deviceNetworkId}] handleGotoEvent: Entering with eventSource=%s, eventType=%s, networkId=0x%02X, sourceId=0x%02X, destinationId=0x%02X, level=%d, rate=%d, channel=0x%02X.",
            eventSource, eventType, networkId, sourceId, destinationId, level, rate, channel)
    try {
        isCorrectParent()
        def result = normalizeFanLevel(level, "handleGotoEvent")
        if (!result.result) {
            return
        }
        sendEvent(name: "status", value: "ok", isStateChange: false)
    } catch (IllegalStateException e) {
        logError("[${device.deviceNetworkId}] handleGotoEvent: Illegal state: %s.", e.message)
        sendEvent(name: "status", value: "error", descriptionText: e.message, isStateChange: true)
    } catch (Exception e) {
        logError("[${device.deviceNetworkId}] handleGotoEvent: Unexpected error: %s.", e.message)
        sendEvent(name: "status", value: "error", descriptionText: e.message, isStateChange: true)
    }
    logTrace("[${device.deviceNetworkId}] handleGotoEvent: Exiting.")
}

/**
 * Called by the parent app when the PIM receives a UPB Device State report.
 * Reads this child's configured channel and normalizes the reported level to fan speed.
 */
def handleDeviceStateReport(String eventSource, String eventType, int networkId, int sourceId, int destinationId, int[] messageArgs) {
    logTrace("[${device.deviceNetworkId}] handleDeviceStateReport: Entering with eventSource=%s, eventType=%s, networkId=0x%02X, sourceId=0x%02X, destinationId=0x%02X, args=%s.",
            eventSource, eventType, networkId, sourceId, destinationId, messageArgs)
    try {
        isCorrectParent()
        if (!isValidIntegerSetting(settings.channelId, 1, 255)) {
            logError("[${device.deviceNetworkId}] handleDeviceStateReport: Invalid channel ID: %d (must be 1-255).", settings.channelId)
            sendEvent(name: "status", value: "error", descriptionText: "Channel ID must be 1-255", isStateChange: true)
            return
        }
        int channel = settings.channelId.intValue() - 1
        if (messageArgs == null || channel >= messageArgs.size()) {
            logWarn("[${device.deviceNetworkId}] handleDeviceStateReport: Ignoring report without channelId=%d; args=%s.", settings.channelId, messageArgs)
            sendEvent(name: "status", value: "error", descriptionText: "State report missing configured channel", isStateChange: true)
            return
        }
        int level = Math.min(messageArgs[channel], 100)
        def result = normalizeFanLevel(level, "handleDeviceStateReport")
        if (!result.result) {
            return
        }
        sendEvent(name: "status", value: "ok", isStateChange: false)
    } catch (IllegalStateException e) {
        logError("[${device.deviceNetworkId}] handleDeviceStateReport: Illegal state: %s.", e.message)
        sendEvent(name: "status", value: "error", descriptionText: e.message, isStateChange: true)
    } catch (Exception e) {
        logError("[${device.deviceNetworkId}] handleDeviceStateReport: Unexpected error: %s.", e.message)
        sendEvent(name: "status", value: "error", descriptionText: e.message, isStateChange: true)
    }
    logTrace("[${device.deviceNetworkId}] handleDeviceStateReport: Exiting.")
}
