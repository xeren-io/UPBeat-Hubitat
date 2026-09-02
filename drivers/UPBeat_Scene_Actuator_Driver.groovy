/*
 * Hubitat Driver: UPB Scene Actuator
 * Description: Universal Powerline Bus Scene Actuator Driver
 * Copyright: 2026 UPBeat Automation
 * Licensed: Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International License
 * Author: UPBeat Automation
 */
#include UPBeat.UPBeatLogger
#include UPBeat.UPBeatDriverLib

metadata {
    definition(name: "UPB Scene Actuator", namespace: "UPBeat", author: "UPBeat Automation", importUrl: "", canAddDevice: false) {
        capability "Actuator"
        command "activate"
        command "deactivate"
        attribute "lastTrigger", "string"
        attribute "status", "enum", ["ok", "error"]
    }
    preferences {
        input name: "logLevel", type: "enum", options: LOG_LEVELS, title: "Log Level", defaultValue: LOG_DEFAULT_LEVEL, required: true
        input name: "networkId", type: "number", title: "Network ID", description: "UPB Network ID (0-255)", required: true, range: "0..255"
        input name: "linkId", type: "number", title: "Link ID", description: "UPB Link ID (1-250)", required: true, range: "1..250"
    }
}

/***************************************************************************
 * Core Driver Functions
 ***************************************************************************/
/**
 * Called by Hubitat when this scene actuator child is created.
 * Initializes the lastTrigger attribute and marks the driver healthy.
 */
void installed() {
    logTrace("[${device.deviceNetworkId}] installed: Entering.")
    try {
        isCorrectParent()
        logInfo("[${device.deviceNetworkId}] installed: Installing UPB Scene Actuator.")
        sendEvent(name: "lastTrigger", value: "None (not triggered yet)")
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
 * Called by Hubitat after scene actuator preferences are saved.
 * Validates the UPB link address through the parent app and clears transient state.
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

/***************************************************************************
 * Handlers for Driver Capabilities
 ***************************************************************************/
/**
 * Called by Hubitat custom activate commands.
 * Sends a UPB Activate Link command and locally routes the resulting scene effect.
 */
def activate() {
    logTrace("[${device.deviceNetworkId}] activate: Entering.")
    try {
        isCorrectParent()
    } catch (IllegalStateException e) {
        logError("[${device.deviceNetworkId}] activate: Illegal state: %s.", e.message)
        sendEvent(name: "status", value: "error", descriptionText: e.message, isStateChange: true)
        return [result: false, reason: e.message]
    }

    if (!isValidIntegerSetting(settings.networkId, 0, 255)) {
        logError("[${device.deviceNetworkId}] activate: Invalid network ID: %d (must be 0-255).", settings.networkId)
        sendEvent(name: "status", value: "error", descriptionText: "Network ID must be 0-255", isStateChange: true)
        return [result: false, reason: "Network ID must be 0-255"]
    }
    if (!isValidIntegerSetting(settings.linkId, 1, 250)) {
        logError("[${device.deviceNetworkId}] activate: Invalid link ID: %d (must be 1-250).", settings.linkId)
        sendEvent(name: "status", value: "error", descriptionText: "Link ID must be 1-250", isStateChange: true)
        return [result: false, reason: "Link ID must be 1-250"]
    }

    def networkId = settings.networkId.intValue()
    def linkId = settings.linkId.intValue()
    logDebug("[${device.deviceNetworkId}] activate: Sending activate command to networkId=0x%02X, linkId=%d.", networkId, linkId)
    def result = getParent().activateScene(networkId, linkId, 0)

    if (result.result) {
        logInfo("[${device.deviceNetworkId}] activate: Scene activation succeeded for linkId=%d.", linkId)
        getParent().handleLinkEvent("user", "UPB_ACTIVATE_LINK", networkId, 0, linkId)
        sendEvent(name: "status", value: "ok", isStateChange: false)
    } else {
        logError("[${device.deviceNetworkId}] activate: Scene activation failed: %s.", result.reason)
        sendEvent(name: "status", value: "error", descriptionText: result.reason, isStateChange: true)
    }
    logTrace("[${device.deviceNetworkId}] activate: Exiting with result=%s.", result)
    return result
}

/**
 * Called by Hubitat custom deactivate commands.
 * Sends a UPB Deactivate Link command and locally routes the resulting scene effect.
 */
def deactivate() {
    logTrace("[${device.deviceNetworkId}] deactivate: Entering.")
    try {
        isCorrectParent()
    } catch (IllegalStateException e) {
        logError("[${device.deviceNetworkId}] deactivate: Illegal state: %s.", e.message)
        sendEvent(name: "status", value: "error", descriptionText: e.message, isStateChange: true)
        return [result: false, reason: e.message]
    }

    if (!isValidIntegerSetting(settings.networkId, 0, 255)) {
        logError("[${device.deviceNetworkId}] deactivate: Invalid network ID: %d (must be 0-255).", settings.networkId)
        sendEvent(name: "status", value: "error", descriptionText: "Network ID must be 0-255", isStateChange: true)
        return [result: false, reason: "Network ID must be 0-255"]
    }
    if (!isValidIntegerSetting(settings.linkId, 1, 250)) {
        logError("[${device.deviceNetworkId}] deactivate: Invalid link ID: %d (must be 1-250).", settings.linkId)
        sendEvent(name: "status", value: "error", descriptionText: "Link ID must be 1-250", isStateChange: true)
        return [result: false, reason: "Link ID must be 1-250"]
    }

    def networkId = settings.networkId.intValue()
    def linkId = settings.linkId.intValue()
    logDebug("[${device.deviceNetworkId}] deactivate: Sending deactivate command to networkId=0x%02X, linkId=%d.", networkId, linkId)
    def result = getParent().deactivateScene(networkId, linkId, 0)

    if (result.result) {
        logInfo("[${device.deviceNetworkId}] deactivate: Scene deactivation succeeded for linkId=%d.", linkId)
        getParent().handleLinkEvent("user", "UPB_DEACTIVATE_LINK", networkId, 0, linkId)
        sendEvent(name: "status", value: "ok", isStateChange: false)
    } else {
        logError("[${device.deviceNetworkId}] deactivate: Scene deactivation failed: %s.", result.reason)
        sendEvent(name: "status", value: "error", descriptionText: result.reason, isStateChange: true)
    }
    logTrace("[${device.deviceNetworkId}] deactivate: Exiting with result=%s.", result)
    return result
}

/***************************************************************************
 * UPB Receive Handlers
 ***************************************************************************/
/**
 * Called by the parent app when a matching UPB link packet is observed or user-generated.
 * Records when the scene actuator was triggered and whether it was activated or deactivated.
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
        String timestamp = new Date().format("yyyy-MM-dd HH:mm:ss")
        switch (eventType) {
            case "UPB_ACTIVATE_LINK":
                logInfo("[${device.deviceNetworkId}] handleLinkEvent: Activating scene for linkId=%d.", settings.linkId)
                def lastTriggerValue = "Activated at ${timestamp} by ${(eventSource == "user") ? eventSource : sourceId}"
                logDebug("[${device.deviceNetworkId}] handleLinkEvent: Setting lastTrigger: %s.", lastTriggerValue)
                sendEvent(name: "lastTrigger", value: lastTriggerValue)
                success = true
                break
            case "UPB_DEACTIVATE_LINK":
                logInfo("[${device.deviceNetworkId}] handleLinkEvent: Deactivating scene for linkId=%d.", settings.linkId)
                def lastTriggerValue = "Deactivated at ${timestamp} by ${(eventSource == "user") ? eventSource : sourceId}"
                logDebug("[${device.deviceNetworkId}] handleLinkEvent: Setting lastTrigger: %s.", lastTriggerValue)
                sendEvent(name: "lastTrigger", value: lastTriggerValue)
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
