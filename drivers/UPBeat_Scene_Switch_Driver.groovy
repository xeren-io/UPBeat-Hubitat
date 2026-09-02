/*
 * Hubitat Driver: UPB Scene Switch
 * Description: Universal Powerline Bus Scene Switch Driver
 * Copyright: 2025 UPBeat Automation
 * Licensed: Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International License
 * Author: UPBeat Automation
 */
#include UPBeat.UPBeatLogger
#include UPBeat.UPBeatDriverLib

metadata {
    definition(name: "UPB Scene Switch", namespace: "UPBeat", author: "UPBeat Automation", importUrl: "", canAddDevice: false) {
        capability "Switch"
        attribute "status", "enum", ["ok", "error"]
    }
}

preferences {
    input name: "logLevel", type: "enum", options: LOG_LEVELS, title: "Log Level", defaultValue: LOG_DEFAULT_LEVEL, required: true
    input name: "networkId", type: "number", title: "Network ID", description: "UPB Network ID (0-255)", required: true, range: "0..255"
    input name: "linkId", type: "number", title: "Link ID", description: "UPB Link ID (1-250)", required: true, range: "1..250"
    input name: "forcedState", type: "enum", title: "Forced Switch State", description: "Choose how the driver reports switch state:<br>" +
            "• Normal – Report actual scene activity<br>" +
            "• Always on – Always show switch as ON<br>" +
            "• Always off – Always show switch as OFF<br>", options: [ "normal": "Normal (typical switch behavior)", "off": "Always show as off", "on": "Always show as on" ] , defaultValue: "normal", required: true
}

/***************************************************************************
 * Core Driver Functions
 ***************************************************************************/
/**
 * Called by Hubitat when this scene switch child is created.
 * Initializes status for the virtual switch that represents one UPB link.
 */
void installed() {
    logTrace("[${device.deviceNetworkId}] installed: Entering.")
    try {
        isCorrectParent()
        logInfo("[${device.deviceNetworkId}] installed: Installing UPB Scene Switch.")
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
 * Called by Hubitat after scene switch preferences are saved.
 * Validates the UPB link address through the parent and resets the virtual switch state policy.
 */
def updated() {
    logTrace("[${device.deviceNetworkId}] updated: Entering.")
    try {
        isCorrectParent()
        logDebug("[${device.deviceNetworkId}] updated: Validating settings: networkId=%d, linkId=%d.", settings.networkId, settings.linkId)

        if (!isValidIntegerSetting(settings.networkId, 0, 255)) {
            logError("[${device.deviceNetworkId}] updated: Invalid network ID: %d (must be 0-255).", settings.networkId)
            sendEvent(name: "status", value: "error", descriptionText: "Network ID must be 0-255", isStateChange: true)
            return
        }
        if (!isValidIntegerSetting(settings.linkId, 1, 250)) {
            logError("[${device.deviceNetworkId}] updated: Invalid link ID: %d (must be 1-250).", settings.linkId)
            sendEvent(name: "status", value: "error", descriptionText: "Link ID must be 1-250", isStateChange: true)
            return
        }

        def result = parent.updateDeviceSettings(device, settings)
        if (result.success) {
            logInfo("[${device.deviceNetworkId}] updated: Device settings updated successfully.")
            // Since Scene's are not stateful, we will always set the state to off, unless an override exists.
            sendEvent(name: "switch", value: effectiveSwitchState("off") , isStateChange: true)
            sendEvent(name: "status", value: "ok", isStateChange: false)
        } else {
            logError("[${device.deviceNetworkId}] updated: Failed to update device settings: %s.", result.error)
            sendEvent(name: "status", value: "error", descriptionText: result.error, isStateChange: true)
            return
        }

        state.clear()
        logInfo("[${device.deviceNetworkId}] updated: Cleared state.")
        sendEvent(name: "status", value: "ok", isStateChange: false)
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
 * Stores the UPB network ID preference for this scene child.
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
 * Stores the UPB link ID represented by this scene child.
 */
def updateLinkId(Long linkId) {
    logTrace("[${device.deviceNetworkId}] updateLinkId: Entering with linkId=%d.", linkId)
    try {
        isCorrectParent()
        logInfo("[${device.deviceNetworkId}] updateLinkId: Updating link ID to %d.", linkId)
        device.updateSetting("linkId", [type: "number", value: linkId])
        sendEvent(name: "status", value: "ok", isStateChange: false)
    } catch (IllegalStateException e) {
        logError("[${device.deviceNetworkId}] updateLinkId: Illegal state: %s.", e.message)
        sendEvent(name: "status", value: "error", descriptionText: e.message, isStateChange: true)
    } catch (Exception e) {
        logError("[${device.deviceNetworkId}] updateLinkId: Unexpected error: %s.", e.message)
        sendEvent(name: "status", value: "error", descriptionText: e.message, isStateChange: true)
    }
    logTrace("[${device.deviceNetworkId}] updateLinkId: Exiting.")
}

/**
 * Called by scene switch command and receive handlers.
 * Applies the optional forced-state preference to the displayed Hubitat switch value.
 */
def String effectiveSwitchState(String requested) {
    switch (settings.forcedState ?: "normal") {
        case "on":  return "on"
        case "off": return "off"
        default:    return requested
    }
}

/***************************************************************************
 * Handlers for Driver Capabilities
 ***************************************************************************/
/**
 * Called by Hubitat Switch capability commands.
 * Sends a UPB Activate Link command and locally routes the resulting scene effect.
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

    if (!isValidIntegerSetting(settings.networkId, 0, 255)) {
        logError("[${device.deviceNetworkId}] on: Invalid network ID: %d (must be 0-255).", settings.networkId)
        sendEvent(name: "status", value: "error", descriptionText: "Network ID must be 0-255", isStateChange: true)
        return [result: false, reason: "Network ID must be 0-255"]
    }
    if (!isValidIntegerSetting(settings.linkId, 1, 250)) {
        logError("[${device.deviceNetworkId}] on: Invalid link ID: %d (must be 1-250).", settings.linkId)
        sendEvent(name: "status", value: "error", descriptionText: "Link ID must be 1-250", isStateChange: true)
        return [result: false, reason: "Link ID must be 1-250"]
    }

    def networkId = settings.networkId.intValue()
    def linkId = settings.linkId.intValue()
    logDebug("[${device.deviceNetworkId}] on: Sending activate command to networkId=0x%02X, linkId=%d.", networkId, linkId)
    def result = getParent().activateScene(networkId, linkId, 0)

    if (result.result) {
        logInfo("[${device.deviceNetworkId}] on: Scene activation succeeded for linkId=%d.", linkId)
        getParent().handleLinkEvent("user", "UPB_ACTIVATE_LINK", networkId, 0, linkId)
        sendEvent(name: "status", value: "ok", isStateChange: false)
    } else {
        logError("[${device.deviceNetworkId}] on: Scene activation failed: %s.", result.reason)
        sendEvent(name: "status", value: "error", descriptionText: result.reason, isStateChange: true)
    }
    logTrace("[${device.deviceNetworkId}] on: Exiting with result=%s.", result)
    return result
}

/**
 * Called by Hubitat Switch capability commands.
 * Sends a UPB Deactivate Link command and locally routes the resulting scene effect.
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

    if (!isValidIntegerSetting(settings.networkId, 0, 255)) {
        logError("[${device.deviceNetworkId}] off: Invalid network ID: %d (must be 0-255).", settings.networkId)
        sendEvent(name: "status", value: "error", descriptionText: "Network ID must be 0-255", isStateChange: true)
        return [result: false, reason: "Network ID must be 0-255"]
    }
    if (!isValidIntegerSetting(settings.linkId, 1, 250)) {
        logError("[${device.deviceNetworkId}] off: Invalid link ID: %d (must be 1-250).", settings.linkId)
        sendEvent(name: "status", value: "error", descriptionText: "Link ID must be 1-250", isStateChange: true)
        return [result: false, reason: "Link ID must be 1-250"]
    }

    def networkId = settings.networkId.intValue()
    def linkId = settings.linkId.intValue()
    logDebug("[${device.deviceNetworkId}] off: Sending deactivate command to networkId=0x%02X, linkId=%d.", networkId, linkId)
    def result = getParent().deactivateScene(networkId, linkId, 0)

    if (result.result) {
        logInfo("[${device.deviceNetworkId}] off: Scene deactivation succeeded for linkId=%d.", linkId)
        getParent().handleLinkEvent("user", "UPB_DEACTIVATE_LINK", networkId, 0, linkId)
        sendEvent(name: "status", value: "ok", isStateChange: false)
    } else {
        logError("[${device.deviceNetworkId}] off: Scene deactivation failed: %s.", result.reason)
        sendEvent(name: "status", value: "error", descriptionText: result.reason, isStateChange: true)
    }
    logTrace("[${device.deviceNetworkId}] off: Exiting with result=%s.", result)
    return result
}

/***************************************************************************
 * UPB Receive Handlers
 ***************************************************************************/
/**
 * Called by the parent app when a matching UPB link packet is observed or user-generated.
 * Updates the virtual switch state while honoring the forced-state display preference.
 */
def handleLinkEvent(String eventSource, String eventType, int networkId, int sourceId, int linkId) {
    logTrace("[${device.deviceNetworkId}] handleLinkEvent: Entering with eventSource=%s, eventType=%s, networkId=0x%02X, sourceId=0x%02X, linkId=%d.",
            eventSource, eventType, networkId, sourceId, linkId)
    try {
        isCorrectParent()
        if (settings.networkId != networkId || settings.linkId != linkId) {
            logDebug("[${device.deviceNetworkId}] handleLinkEvent: Ignoring event for networkId=0x%02X, linkId=%d (expected networkId=0x%02X, linkId=%d).",
                    networkId, linkId, settings.networkId, settings.linkId)
            return
        }
        boolean success = false
        switch (eventType) {
            case "UPB_ACTIVATE_LINK":
                logInfo("[${device.deviceNetworkId}] handleLinkEvent: Activating scene for linkId=%d. Effective state: %s", settings.linkId, effectiveSwitchState("on"))
                sendEvent(name: "switch", value: "on" , isStateChange: true)
                pauseExecution(50)
                sendEvent(name: "switch", value: effectiveSwitchState("on") , isStateChange: false)
                success = true
                break
            case "UPB_DEACTIVATE_LINK":
                logInfo("[${device.deviceNetworkId}] handleLinkEvent: Deactivating scene for linkId=%d. Effective state: %s", settings.linkId, effectiveSwitchState("off"))
                sendEvent(name: "switch", value: "off" , isStateChange: true)
                pauseExecution(50)
                sendEvent(name: "switch", value: effectiveSwitchState("off") , isStateChange: false)
                success = true
                break
            default:
                logWarn("[${device.deviceNetworkId}] handleLinkEvent: Unknown event type: %s.", eventType)
                sendEvent(name: "status", value: "error", descriptionText: "Unknown Link Event type: ${eventType}", isStateChange: true)
                return
        }
        if (success) {
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
