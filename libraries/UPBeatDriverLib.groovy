/*
* Hubitat Library: UPBeatDriverLib
* Description: Universal Powerline Bus Driver Helper Library for Hubitat
* Copyright: 2026 UPBeat Automation
* Licensed: Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International License
* Author: UPBeat Automation
*/
library(
        name: "UPBeatDriverLib",
        namespace: "UPBeat",
        author: "UPBeat Automation",
        description: "Helper methods shared by child devices",
        category: "Utilities",
        importUrl: ""
)

private void isCorrectParent() {
    def parentApp = getParent()
    if (!parentApp || parentApp.name != "UPBeat App") {
        throw new IllegalStateException("${device.name ?: 'Device'} must be created by the UPBeat App. Manual creation is not supported.")
    }
}

/**
 * Validates numeric Hubitat settings without using Groovy truth, because
 * zero is falsey but valid for settings such as UPB networkId.
 */
private boolean isValidIntegerSetting(value, int minValue, int maxValue) {
    if (value == null) {
        return false
    }

    try {
        int numericValue
        if (value instanceof Number) {
            numericValue = value.intValue()
        } else {
            String valueText = value.toString().trim()
            if (valueText.length() == 0) {
                return false
            }
            numericValue = valueText.toInteger()
        }
        return numericValue >= minValue && numericValue <= maxValue
    } catch (Exception e) {
        return false
    }
}

def getReceiveComponents() {
    def components = [:]
    def hasErrors = false
    def isDimmable = device.hasCapability("SwitchLevel")
    (1..16).each { slot ->
        def slotInput = settings."receiveComponent${slot}"?.trim()
        if (slotInput) {
            def parts = slotInput.split(":")
            if (parts.size() < 2 || parts.size() > 3) {
                logWarn("Invalid format in receiveComponent${slot}: ${slotInput}. Expected linkID:level, setting value removed")
                device.updateSetting("receiveComponent${slot}", "")
                hasErrors = true
                return
            }
            try {
                def linkId = parts[0].toInteger()
                def level = parts[1].toInteger()
                if (linkId < 1 || linkId > 250) {
                    logWarn("Invalid linkId in receiveComponent${slot}: ${linkId}. Must be 1-250, setting value removed")
                    device.updateSetting("receiveComponent${slot}", "")
                    hasErrors = true
                    return
                }
                if (isDimmable) {
                    if (level < 0 || level > 100) {
                        logWarn("Invalid level in receiveComponent${slot}: ${level}. Must be 0-100 for dimmable device, setting value removed")
                        device.updateSetting("receiveComponent${slot}", "")
                        hasErrors = true
                        return
                    }
                } else {
                    if (level != 0 && level != 100) {
                        logWarn("Invalid level in receiveComponent${slot}: ${level}. Must be 0 or 100 for non-dimmable device, setting value removed")
                        device.updateSetting("receiveComponent${slot}", "")
                        hasErrors = true
                        return
                    }
                }
                def linkIdKey = linkId.toString()
                if (components.containsKey(linkIdKey)) {
                    logWarn("Duplicate linkId ${linkId} in receiveComponent${slot}, setting value removed")
                    device.updateSetting("receiveComponent${slot}", "")
                    hasErrors = true
                    return
                }
                components[linkIdKey] = [level: level]
            } catch (NumberFormatException e) {
                logWarn("Invalid number format in receiveComponent${slot}: ${slotInput}, setting value removed")
                device.updateSetting("receiveComponent${slot}", "")
                hasErrors = true
            } catch (Exception e) {
                logWarn("Unexpected error in receiveComponent${slot}: ${e.message}, setting value removed")
                device.updateSetting("receiveComponent${slot}", "")
                hasErrors = true
            }
        }
    }
    if (hasErrors) {
        sendEvent(name: "status", value: "error", descriptionText: "Invalid receiveComponents detected; check logs and verify settings", isStateChange: true)
    } else if (device.currentValue("status") != "ok") {
        sendEvent(name: "status", value: "ok", descriptionText: "All receiveComponents valid", isStateChange: true)
    }
    return components
}
