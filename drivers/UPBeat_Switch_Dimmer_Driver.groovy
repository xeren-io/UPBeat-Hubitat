/*
 * Hubitat Driver: UPB Dimming Switch
 * Description: Universal Powerline Bus Dimming Switch Driver
 * Copyright: 2025 UPBeat Automation
 * Licensed: Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International License
 * Author: UPBeat Automation
 */
#include UPBeat.UPBeatLogger
#include UPBeat.UPBeatLib
#include UPBeat.UPBeatDriverLib

import groovy.transform.Field
import groovy.json.JsonSlurper
import groovy.json.JsonOutput

metadata {
    definition(name: "UPB Dimming Switch", namespace: "UPBeat", author: "UPBeat Automation", importUrl: "") {
        capability "Switch"
        capability "SwitchLevel"
        capability "Refresh"
        command "setLevel", [
                [name: "level", type: "NUMBER", description: "Set level (0-100)", constraints: ["MIN": 0, "MAX": 100]],
                [name: "duration", type: "ENUM", description: "Set the fade rate", constraints: FADE_RATE_MAPPING.keySet()]
        ]
        attribute "status", "enum", ["ok", "error"]
    }

    preferences {
        input name: "logLevel", type: "enum", options: LOG_LEVELS, title: "Log Level", defaultValue: LOG_DEFAULT_LEVEL, required: true
        input name: "networkId", type: "number", title: "Network ID", description: "UPB Network ID (0-255)", required: true, range: "0..255"
        input name: "deviceId", type: "number", title: "Device ID", description: "UPB Device ID (1-250)", required: true, range: "1..250"
        input name: "channelId", type: "number", title: "Channel ID", description: "UPB Channel ID (1-255)", required: true, range: "1..255"
        input name: "fadeRate", type: "enum", title: "Default Fade Rate", options: FADE_RATE_MAPPING.keySet(), defaultValue: "Default", required: false
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

@Field static Map FADE_RATE_MAPPING = [
        "Snap": 0,  // Instant
        "0.3s": 1,  // 0.3 seconds
        "0.5s": 2,  // 0.5 seconds
        "1s": 3,    // 1 second
        "2s": 4,    // 2 seconds
        "5s": 5,    // 5 seconds
        "10s": 6,   // 10 seconds
        "20s": 7,   // 20 seconds
        "30s": 8,   // 30 seconds
        "40s": 9,   // 40 seconds
        "60s": 10,  // 1 minute
        "90s": 11,  // 1.5 minutes
        "2min": 12, // 2 minutes
        "5min": 13, // 5 minutes
        "30min": 14,// 30 minutes
        "1hr": 15,  // 1 hour
        "Default": 255 // Device programmed default
]

/***************************************************************************
 * Core Driver Functions
 ***************************************************************************/
/**
 * Called by Hubitat when this child device is created.
 * Initializes stored receive-component data and marks the driver healthy.
 */
void installed() {
    logTrace("[${device.deviceNetworkId}] installed: Entering.")
    try {
        isCorrectParent()
        logInfo("[${device.deviceNetworkId}] installed: Installing UPB Dimming Switch.")
        device.updateDataValue("receiveComponents", JsonOutput.toJson([:]))
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
 * Normalizes fade-rate settings, validates the child DNI through the parent, and refreshes receive-component data.
 */
def updated() {
    logTrace("[${device.deviceNetworkId}] updated: Entering.")
    try {
        isCorrectParent()
        logDebug("[${device.deviceNetworkId}] updated: Validating settings: networkId=%d, deviceId=%d, channelId=%d, fadeRate=%s.",
                settings.networkId, settings.deviceId, settings.channelId, settings.fadeRate ?: "Default")

        if (settings.fadeRate && !(settings.fadeRate in FADE_RATE_MAPPING.keySet())) {
            logWarn("[${device.deviceNetworkId}] updated: Invalid fade rate: %s. Using device default.", settings.fadeRate)
            device.updateSetting("fadeRate", [value: "Default", type: "string"])
        }

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
        logError("[${device.deviceNetworkId}] updated: Unexpected error: %s.", e.message)
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
def updateNetworkId(int networkId) {
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
def updateDeviceId(int deviceId) {
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
def updateChannelId(int channelId) {
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
 * Delegates to setLevel(100) so dimmer validation and fade handling stay centralized.
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
    def result = setLevel(100)
    logTrace("[${device.deviceNetworkId}] on: Exiting with result=%s.", result)
    return result
}

/**
 * Called by Hubitat Switch capability commands.
 * Delegates to setLevel(0) so dimmer validation and fade handling stay centralized.
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
    def result = setLevel(0)
    logTrace("[${device.deviceNetworkId}] off: Exiting with result=%s.", result)
    return result
}

/**
 * Called by Hubitat SwitchLevel capability commands and by on()/off().
 * Sends a UPB Goto command for this child channel using the requested or default fade rate.
 */
def setLevel(value, duration = null) {
    logTrace("[${device.deviceNetworkId}] setLevel: Entering with value=%d, duration=%s.", value, duration ?: "null")
    try {
        isCorrectParent()
    } catch (IllegalStateException e) {
        logError("[${device.deviceNetworkId}] setLevel: Illegal state: %s.", e.message)
        sendEvent(name: "status", value: "error", descriptionText: e.message, isStateChange: true)
        return [result: false, reason: e.message]
    }

    if (!isValidIntegerSetting(settings.networkId, 0, 255)) {
        logError("[${device.deviceNetworkId}] setLevel: Invalid network ID: %d (must be 0-255).", settings.networkId)
        sendEvent(name: "status", value: "error", descriptionText: "Network ID must be 0-255", isStateChange: true)
        return [result: false, reason: "Network ID must be 0-255"]
    }
    if (!isValidIntegerSetting(settings.deviceId, 1, 250)) {
        logError("[${device.deviceNetworkId}] setLevel: Invalid device ID: %d (must be 1-250).", settings.deviceId)
        sendEvent(name: "status", value: "error", descriptionText: "Device ID must be 1-250", isStateChange: true)
        return [result: false, reason: "Device ID must be 1-250"]
    }
    if (!isValidIntegerSetting(settings.channelId, 1, 255)) {
        logError("[${device.deviceNetworkId}] setLevel: Invalid channel ID: %d (must be 1-255).", settings.channelId)
        sendEvent(name: "status", value: "error", descriptionText: "Channel ID must be 1-255", isStateChange: true)
        return [result: false, reason: "Channel ID must be 1-255"]
    }

    def level = value.toInteger()
    level = Math.max(0, Math.min(level, 100))

    def rate
    if (duration != null && FADE_RATE_MAPPING.containsKey(duration)) {
        rate = FADE_RATE_MAPPING[duration]
    } else {
        rate = settings.fadeRate && FADE_RATE_MAPPING.containsKey(settings.fadeRate) ? FADE_RATE_MAPPING[settings.fadeRate] : 255
        if (duration != null) {
            logWarn("[${device.deviceNetworkId}] setLevel: Invalid duration: %s. Using default fade rate: %s (rate=%d).", duration, settings.fadeRate ?: "Default", rate)
        }
    }

    logDebug("[${device.deviceNetworkId}] setLevel: Sending command to networkId=0x%02X, deviceId=0x%02X, level=%d, rate=%d, channelId=0x%02X.",
            settings.networkId, settings.deviceId, level, rate, settings.channelId)
    def result = getParent().gotoLevel(settings.networkId.intValue(), settings.deviceId.intValue(), 0xFF, level, rate, settings.channelId.intValue())

    if (result.result) {
        logInfo("[${device.deviceNetworkId}] setLevel: Command succeeded: switch=%s, level=%d.", level > 0 ? "on" : "off", level)
        sendEvent(name: "switch", value: level > 0 ? "on" : "off", isStateChange: true)
        sendEvent(name: "level", value: level, unit: "%", isStateChange: true)
        sendEvent(name: "status", value: "ok", isStateChange: false)
    } else {
        logError("[${device.deviceNetworkId}] setLevel: Command failed: %s.", result.reason)
        sendEvent(name: "status", value: "error", descriptionText: result.reason, isStateChange: true)
    }
    logTrace("[${device.deviceNetworkId}] setLevel: Exiting with result=%s.", result)
    return result
}

/***************************************************************************
 * UPB Receive Handlers
 ***************************************************************************/
/**
 * Called by the parent app after a UPB link packet is routed to this child.
 * Applies the programmed receive component level as Hubitat switch and level state.
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
            def level = component.level
            def slot = component.slot
            logDebug("[${device.deviceNetworkId}] handleLinkEvent: Found component for linkId=%d: level=%d, slot=%d.", linkId, level, slot)
            switch (eventType) {
                case "UPB_ACTIVATE_LINK":
                    logInfo("[${device.deviceNetworkId}] handleLinkEvent: Processing UPB_ACTIVATE_LINK, setting switch=%s, level=%d.", level == 0 ? "off" : "on", level)
                    sendEvent(name: "switch", value: level == 0 ? "off" : "on", isStateChange: true)
                    sendEvent(name: "level", value: level, isStateChange: true)
                    break
                case "UPB_DEACTIVATE_LINK":
                    logInfo("[${device.deviceNetworkId}] handleLinkEvent: Processing UPB_DEACTIVATE_LINK, setting switch=off, level=0.")
                    sendEvent(name: "switch", value: "off", isStateChange: true)
                    sendEvent(name: "level", value: 0, isStateChange: true)
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
 * Mirrors the observed level into Hubitat switch and level state without sending another command.
 */
def handleGotoEvent(String eventSource, String eventType, int networkId, int sourceId, int destinationId, int level, int rate, int channel) {
    logTrace("[${device.deviceNetworkId}] handleGotoEvent: Entering with eventSource=%s, eventType=%s, networkId=0x%02X, sourceId=0x%02X, destinationId=0x%02X, level=%d, rate=%d, channel=0x%02X.",
            eventSource, eventType, networkId, sourceId, destinationId, level, rate, channel)
    try {
        isCorrectParent()
        def switchValue = (level == 0) ? "off" : "on"
        logInfo("[${device.deviceNetworkId}] handleGotoEvent: Updating switch=%s, level=%d for deviceId=0x%02X.", switchValue, level, settings.deviceId)
        sendEvent(name: "switch", value: switchValue, isStateChange: true)
        sendEvent(name: "level", value: level, isStateChange: true)
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
 * Reads this child's configured channel from the report and updates switch and level state.
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
        def switchValue = (level == 0) ? "off" : "on"
        logInfo("[${device.deviceNetworkId}] handleDeviceStateReport: Updating switch=%s, level=%d for deviceId=0x%02X.", switchValue, level, settings.deviceId)
        sendEvent(name: "switch", value: switchValue, isStateChange: true)
        sendEvent(name: "level", value: level, isStateChange: true)
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
