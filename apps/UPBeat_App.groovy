/*
* Hubitat App: UPBeat App
* Description: Hubitat App for Univeral Powerline Bus Support
* Copyright: 2025 UPBeat Automation
* Licensed: Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International License
* Author: UPBeat Automation
*/
import groovy.json.JsonBuilder
import groovy.json.JsonOutput
import hubitat.helper.HexUtils
import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap
import java.security.MessageDigest
import java.util.UUID

#include UPBeat.UPBeatLogger
#include UPBeat.UPBeatLib
#include UPBeat.UPBProtocolLib

@Field static Map DEVICE_TYPES = [
        "non_dimming_switch": [
                displayName: "UPB Non-Dimming Switch",
                driverName: "UPB Non-Dimming Switch",
                category: "device",
                requiredInputs: [
                        [name: "deviceId", type: "number", title: "Device ID", range: "1..250", required: true],
                        [name: "channelId", type: "number", title: "Channel ID", range: "0..255", defaultValue: 1, required: true]
                ]
        ],
        "single_speed_fan": [
                displayName: "UPB Single-Speed Fan",
                driverName: "UPB Single-Speed Fan",
                category: "device",
                requiredInputs: [
                        [name: "deviceId", type: "number", title: "Device ID", range: "1..250", required: true],
                        [name: "channelId", type: "number", title: "Channel ID", range: "0..255", defaultValue: 1, required: true]
                ]
        ],
        "dimming_switch": [
                displayName: "UPB Dimming Switch",
                driverName: "UPB Dimming Switch",
                category: "device",
                requiredInputs: [
                        [name: "deviceId", type: "number", title: "Device ID", range: "1..250", required: true],
                        [name: "channelId", type: "number", title: "Channel ID", range: "0..255", defaultValue: 1, required: true]
                ]
        ],
        "multi_speed_fan": [
                displayName: "UPB Multi-Speed Fan",
                driverName: "UPB Multi-Speed Fan",
                category: "device",
                requiredInputs: [
                        [name: "deviceId", type: "number", title: "Device ID", range: "1..250", required: true],
                        [name: "channelId", type: "number", title: "Channel ID", range: "0..255", defaultValue: 1, required: true]
                ]
        ],
        "scene_switch": [
                displayName: "UPB Scene Switch",
                driverName: "UPB Scene Switch",
                category: "scene",
                requiredInputs: [
                        [name: "linkId", type: "number", title: "Link ID", range: "1..250", required: true]
                ]
        ],
        "scene_actuator": [
                displayName: "UPB Scene Actuator",
                driverName: "UPB Scene Actuator",
                category: "scene",
                requiredInputs: [
                        [name: "linkId", type: "number", title: "Link ID", range: "1..250", required: true]
                ]
        ]
]

definition(
        name: "UPBeat App",
        namespace: "UPBeat",
        author: "UPBeat Automation",
        description: "Configure Hubitat for UPB Support",
        category: "Convenience",
        iconUrl: "",
        iconX2Url: "",
        iconX3Url: "",
        singleInstance: true
)

preferences {
    page(name: "mainPage")
    page(name: "addDevicePage")
    page(name: "createDevice")
    page(name: "bulkImportPage")
    page(name: "bulkImport")
}

mappings {
    path("/status") {
        action: [
                GET: "handleStatus"
        ]
    }
    path("/device") {
        action: [
                POST: "handleAddDevice"
        ]
    }
    path("/scene") {
        action: [
                POST: "handleAddScene"
        ]
    }
    path("/pim") {
        action: [
                POST: "handleUpdatePowerlineInterface"
        ]
    }
}

/***************************************************************************
 * Custom Application Configuration Pages
 ***************************************************************************/
def addDevicePage() {
    dynamicPage(name: "addDevicePage", title: "Manually Add Device", install: false, uninstall: false, nextPage: "createDevice") {
        section("Create a New Device") {
            // Generate enum options for deviceType
            def deviceTypeOptions = DEVICE_TYPES.collectEntries { key, config -> [(key): config.displayName] }
            input name: "deviceType", type: "enum", title: "Device Type", options: deviceTypeOptions, required: true, submitOnChange: true

            if (settings.deviceType && DEVICE_TYPES[settings.deviceType]) {
                // Common inputs for all device types
                input name: "deviceName", type: "text", title: "Device Name", required: true, submitOnChange: false
                input name: "voiceName", type: "text", title: "Voice Name", required: false, submitOnChange: false
                input name: "networkId", type: "number", title: "Network ID", required: true, range: "0..255", submitOnChange: false

                // Dynamically render inputs based on device type
                DEVICE_TYPES[settings.deviceType].requiredInputs.each { inputConfig ->
                    input(inputConfig + [submitOnChange: false])
                }
            }
        }
    }
}

def bulkImportPage() {
    dynamicPage(name: "bulkImportPage", title: "Bulk Import", install: false, uninstall: false, nextPage: "bulkImport") {
        section() {
            input name: "upeFileData",
                    type: "textarea",
                    title: "UPE File Data",
                    description: "Paste UPStart UPE file here",
                    defaultValue: "",
                    required: true,
                    rows: 20,
                    cols: 80,
                    submitOnChange: false
        }
    }
}

private String formatUpeDisplayName(String sourceName, String fallbackName) {
    def displayName = "${sourceName ?: ''}".trim().tokenize().collect { it.capitalize() }.join(' ')
    return displayName ?: fallbackName
}

private boolean isSupportedUpeModule(Map module) {
    def deviceKindInfo = UPE_DEVICE_KINDS[module.deviceKind]
    return deviceKindInfo && deviceKindInfo.supported == true
}

private String getUpeDeviceKindName(Integer deviceKind) {
    def deviceKindInfo = UPE_DEVICE_KINDS[deviceKind]
    return deviceKindInfo ? deviceKindInfo.name : "Unknown"
}

private String describeUpeModule(Map module) {
    def name = "${module.roomName ?: ''} ${module.deviceName ?: ''}".trim()
    return "module ${module.moduleId ?: '?'}${name ? " (${name})" : ""}"
}

private String getUpeImportTimestamp() {
    def timeZone = location?.timeZone ?: TimeZone.getTimeZone("UTC")
    return new Date().format("yyyy-MM-dd'T'HH:mm:ssZ", timeZone)
}

/*
 * UPE metadata marks child devices that bulk import is allowed to update or
 * delete during later syncs. User-facing names and labels are only set when a
 * child is created.
 */
private void markUpeManagedChildDevice(childDevice, Map metadata, String importTimestamp, boolean isNewChild) {
    childDevice.updateDataValue("upeManaged", "true")
    childDevice.updateDataValue("upeSource", "bulkImport")
    if (isNewChild || !childDevice.getDataValue("upeImportedAt")) {
        childDevice.updateDataValue("upeImportedAt", importTimestamp)
    }
    if (!isNewChild) {
        childDevice.updateDataValue("upeUpdatedAt", importTimestamp)
    }

    metadata.each { key, value ->
        childDevice.updateDataValue(key.toString(), value == null ? "" : value.toString())
    }
}

private boolean isPimChildDevice(childDevice) {
    return childDevice.deviceNetworkId == pimDeviceId || childDevice.typeName == "UPB Powerline Interface Module"
}

private boolean isUpeManagedChildDevice(childDevice) {
    return childDevice.getDataValue("upeManaged") == "true"
}

private String describeChildDevice(childDevice) {
    return "${childDevice.label ?: childDevice.name ?: childDevice.deviceNetworkId} (${childDevice.typeName})"
}

private Map getSceneUpeMetadata(Map scenePlan) {
    return [
            upeRecordType: "link",
            upeNetworkId: scenePlan.networkId,
            upeLinkId: scenePlan.linkId,
            upeLinkName: scenePlan.linkName
    ]
}

private Map getDeviceUpeMetadata(Map devicePlan) {
    return [
            upeRecordType: "module",
            upeNetworkId: devicePlan.networkId,
            upeModuleId: devicePlan.moduleId,
            upeChannelId: devicePlan.channelId,
            upeDeviceKind: devicePlan.deviceKind,
            upeDeviceKindName: devicePlan.deviceKindName,
            upeManufacturerId: devicePlan.manufacturerId,
            upeProductId: devicePlan.productId,
            upeRoomName: devicePlan.roomName,
            upeDeviceName: devicePlan.sourceDeviceName
    ]
}

private void clearReceiveComponentSettings(childDevice) {
    (1..16).each { slot ->
        childDevice.updateSetting("receiveComponent${slot}", [type: "string", value: ""])
    }
}

private void applyReceiveComponents(childDevice, Map devicePlan) {
    clearReceiveComponentSettings(childDevice)
    devicePlan.receiveComponents.each { receiveComponent ->
        childDevice.updateReceiveComponentSlot(receiveComponent.slot, receiveComponent.linkId, receiveComponent.level)
    }

    def components = childDevice.getReceiveComponents()
    childDevice.updateDataValue("receiveComponents", JsonOutput.toJson(components))
}

private void applyUpeSceneChildDevice(childDevice, Map scenePlan, String importTimestamp, boolean isNewChild) {
    markUpeManagedChildDevice(childDevice, getSceneUpeMetadata(scenePlan), importTimestamp, isNewChild)
    childDevice.updateNetworkId(scenePlan.networkId)
    childDevice.updateLinkId(scenePlan.linkId)
}

private void applyUpeModuleChildDevice(childDevice, Map devicePlan, String importTimestamp, boolean isNewChild) {
    markUpeManagedChildDevice(childDevice, getDeviceUpeMetadata(devicePlan), importTimestamp, isNewChild)
    childDevice.updateNetworkId(devicePlan.networkId)
    childDevice.updateDeviceId(devicePlan.moduleId)
    childDevice.updateChannelId(devicePlan.channelId)
    applyReceiveComponents(childDevice, devicePlan)
}

private boolean addPlannedDeviceNetworkId(Map plan, Map plannedDeviceNetworkIds, String deviceNetworkId, String description) {
    if (plannedDeviceNetworkIds.containsKey(deviceNetworkId)) {
        plan.errors.add("Duplicate child device network ID ${deviceNetworkId} for ${description}; already planned for ${plannedDeviceNetworkIds[deviceNetworkId]}.")
        return false
    }
    plannedDeviceNetworkIds[deviceNetworkId] = description
    plan.desiredDeviceNetworkIds[deviceNetworkId] = true
    return true
}

private void addPlannedReceiveComponent(Map plan, Map devicePlan, Map usedLinkIds, Map preset, boolean dimEnabled) {
    def slot = preset.componentId + 1
    def linkId = preset.linkId
    def level = preset.presetDimLevel

    if (slot < 1 || slot > 16) {
        plan.errors.add("${devicePlan.deviceNetworkId} preset component ${preset.componentId} maps to receive slot ${slot}; supported slots are 1-16.")
        return
    }
    if (linkId < 1 || linkId > 250) {
        plan.errors.add("${devicePlan.deviceNetworkId} preset slot ${slot} has invalid link ID ${linkId}; valid links are 1-250.")
        return
    }
    if (dimEnabled) {
        if (level < 0 || level > 100) {
            plan.errors.add("${devicePlan.deviceNetworkId} preset slot ${slot} has invalid dim level ${level}; dimming devices support 0-100.")
            return
        }
    } else if (level != 0 && level != 100) {
        plan.errors.add("${devicePlan.deviceNetworkId} preset slot ${slot} has invalid non-dimming level ${level}; non-dimming devices support 0 or 100.")
        return
    }
    if (usedLinkIds.containsKey(linkId)) {
        plan.errors.add("${devicePlan.deviceNetworkId} has duplicate receive link ID ${linkId} in slots ${usedLinkIds[linkId]} and ${slot}.")
        return
    }

    usedLinkIds[linkId] = slot
    devicePlan.receiveComponents.add([slot: slot, linkId: linkId, level: level])
}

/*
 * Build the desired UPE child-device state without changing Hubitat state.
 * Errors added here stop the import before any sync action is applied.
 */
private Map buildBulkImportPlan(Map data) {
    def plan = [
            errors: [],
            scenes: [],
            devices: [],
            skippedModules: [],
            desiredDeviceNetworkIds: [:]
    ]
    def plannedDeviceNetworkIds = [:]

    if (!data?.systemInfo || data.systemInfo.networkId == null) {
        plan.errors.add("UPE file is missing system network information.")
        return plan
    }

    def networkId = data.systemInfo.networkId

    (data.links ?: []).each { link ->
        try {
            def deviceNetworkId = buildSceneNetworkId(networkId, link.linkId)
            def sceneName = formatUpeDisplayName(link.name, "UPB Link ${link.linkId}")
            if (addPlannedDeviceNetworkId(plan, plannedDeviceNetworkIds, deviceNetworkId, "link ${link.linkId}")) {
                plan.scenes.add([
                        deviceNetworkId: deviceNetworkId,
                        sceneName: sceneName,
                        networkId: networkId,
                        linkId: link.linkId,
                        linkName: link.name ?: ""
                ])
            }
        } catch (IllegalArgumentException e) {
            plan.errors.add("Link ${link.linkId ?: '?'} cannot be imported: ${e.message}")
        }
    }

    (data.modules ?: []).each { module ->
        def deviceKind = module.deviceKind
        if (deviceKind == null) {
            plan.errors.add("${describeUpeModule(module)} is missing a UPE device kind.")
        } else if (!isSupportedUpeModule(module)) {
            plan.skippedModules.add([
                    moduleId: module.moduleId,
                    deviceKind: deviceKind,
                    deviceKindName: getUpeDeviceKindName(deviceKind),
                    manufacturerId: module.manufacturerId,
                    productId: module.productId,
                    name: "${module.roomName ?: ''} ${module.deviceName ?: ''}".trim()
            ])
        } else if (!module.channelInfo) {
            plan.errors.add("${describeUpeModule(module)} is supported but has no channel info records.")
        } else {
            module.channelInfo.each { channel ->
                try {
                    if (channel.dimEnabled != 0 && channel.dimEnabled != 1) {
                        plan.errors.add("${describeUpeModule(module)} channel ${channel.channelId} has invalid dim flag ${channel.dimEnabled}; expected 0 or 1.")
                    } else {
                        def channelId = channel.channelId + 1
                        def deviceNetworkId = buildDeviceNetworkId(module.networkId, module.moduleId, channelId)
                        def deviceName = formatUpeDisplayName("${module.roomName ?: ''} ${module.deviceName ?: ''}", "UPB Device ${module.moduleId}")
                        def dimEnabled = (channel.dimEnabled == 1)
                        def devicePlan = [
                                deviceNetworkId: deviceNetworkId,
                                driverName: dimEnabled ? "UPB Dimming Switch" : "UPB Non-Dimming Switch",
                                deviceName: deviceName,
                                networkId: module.networkId,
                                moduleId: module.moduleId,
                                channelId: channelId,
                                deviceKind: module.deviceKind,
                                deviceKindName: getUpeDeviceKindName(module.deviceKind),
                                manufacturerId: module.manufacturerId,
                                productId: module.productId,
                                roomName: module.roomName ?: "",
                                sourceDeviceName: module.deviceName ?: "",
                                dimEnabled: dimEnabled,
                                receiveComponents: []
                        ]

                        if (addPlannedDeviceNetworkId(plan, plannedDeviceNetworkIds, deviceNetworkId, "${describeUpeModule(module)} channel ${channel.channelId}")) {
                            def usedLinkIds = [:]
                            module.presetInfo.each { preset ->
                                if (preset.channelId == channel.channelId && preset.linkId != 255 && preset.presetDimLevel != 255) {
                                    addPlannedReceiveComponent(plan, devicePlan, usedLinkIds, preset, dimEnabled)
                                }
                            }
                            plan.devices.add(devicePlan)
                        }
                    }
                } catch (IllegalArgumentException e) {
                    plan.errors.add("${describeUpeModule(module)} channel ${channel.channelId ?: '?'} cannot be imported: ${e.message}")
                }
            }
        }
    }

    return plan
}

/*
 * Compare the desired UPE state with existing children without changing
 * Hubitat state. Existing unmanaged children or driver mismatches are skipped
 * per child so the rest of the import can continue.
 */
private void addBulkImportSyncAction(Map plan, Map childPlan, String driverName, List createPlans, List updatePlans) {
    def existingDevice = getChildDevice(childPlan.deviceNetworkId)

    if (!existingDevice) {
        createPlans.add(childPlan)
        return
    }

    if (!isUpeManagedChildDevice(existingDevice)) {
        plan.skippedChildDevices.add([
                deviceNetworkId: childPlan.deviceNetworkId,
                reason: "conflicts with existing unmanaged child ${describeChildDevice(existingDevice)}"
        ])
        return
    }

    if (existingDevice.typeName != driverName) {
        plan.skippedChildDevices.add([
                deviceNetworkId: childPlan.deviceNetworkId,
                reason: "exists as ${existingDevice.typeName}; UPE import expects ${driverName}. Driver replacement is not supported by sync import yet"
        ])
        return
    }

    updatePlans.add(childPlan)
}

private void addBulkImportSyncActions(Map plan) {
    plan.createScenes = []
    plan.updateScenes = []
    plan.createDevices = []
    plan.updateDevices = []
    plan.deleteStaleDeviceNetworkIds = []
    plan.skippedChildDevices = []

    if (plan.errors) {
        return
    }

    plan.scenes.each { scenePlan ->
        addBulkImportSyncAction(plan, scenePlan, "UPB Scene Switch", plan.createScenes, plan.updateScenes)
    }

    plan.devices.each { devicePlan ->
        addBulkImportSyncAction(plan, devicePlan, devicePlan.driverName, plan.createDevices, plan.updateDevices)
    }

    if (plan.errors) {
        return
    }

    app.getChildDevices().each { childDevice ->
        if (!isPimChildDevice(childDevice) &&
                isUpeManagedChildDevice(childDevice) &&
                !plan.desiredDeviceNetworkIds.containsKey(childDevice.deviceNetworkId)) {
            plan.deleteStaleDeviceNetworkIds.add(childDevice.deviceNetworkId)
        }
    }
}

def bulkImport() {
    return dynamicPage(name: "bulkImport", title: "Device Import Results", install: false, uninstall: false, nextPage: "mainPage") {
        def importStarted = false
        try {
            def data = processUpeFile(settings.upeFileData)
            def plan = buildBulkImportPlan(data)
            addBulkImportSyncActions(plan)
            def importTimestamp = getUpeImportTimestamp()

            section() {
                if (plan.errors) {
                    paragraph "Import was not applied. Existing devices were not changed."
                    plan.errors.each { importError ->
                        paragraph "Import Error: ${importError}"
                    }
                } else {
                    importStarted = true

                    /*
                     * Apply sync actions in a conservative order: create
                     * missing children, update matching managed children, then
                     * delete stale managed children last.
                     */
                    plan.createScenes.each { scenePlan ->
                        paragraph "Creating link device [${scenePlan.deviceNetworkId}] with scene name [${scenePlan.sceneName}]"
                        def childDevice = addChildDevice("UPBeat", "UPB Scene Switch", scenePlan.deviceNetworkId, [name: scenePlan.sceneName, label: scenePlan.sceneName])
                        applyUpeSceneChildDevice(childDevice, scenePlan, importTimestamp, true)
                    }

                    plan.createDevices.each { devicePlan ->
                        paragraph "Creating ${devicePlan.driverName.toLowerCase()} [${devicePlan.deviceNetworkId}] with device name [${devicePlan.deviceName}]"
                        def childDevice = addChildDevice("UPBeat", devicePlan.driverName, devicePlan.deviceNetworkId, [name: devicePlan.deviceName, label: devicePlan.deviceName])
                        applyUpeModuleChildDevice(childDevice, devicePlan, importTimestamp, true)
                    }

                    plan.updateScenes.each { scenePlan ->
                        paragraph "Updating link device [${scenePlan.deviceNetworkId}]"
                        def childDevice = getChildDevice(scenePlan.deviceNetworkId)
                        applyUpeSceneChildDevice(childDevice, scenePlan, importTimestamp, false)
                    }

                    plan.updateDevices.each { devicePlan ->
                        paragraph "Updating ${devicePlan.driverName.toLowerCase()} [${devicePlan.deviceNetworkId}]"
                        def childDevice = getChildDevice(devicePlan.deviceNetworkId)
                        applyUpeModuleChildDevice(childDevice, devicePlan, importTimestamp, false)
                    }

                    plan.deleteStaleDeviceNetworkIds.each { deviceNetworkId ->
                        paragraph "Deleting stale UPE-managed child device [${deviceNetworkId}]"
                        deleteChildDevice(deviceNetworkId)
                    }

                    plan.skippedChildDevices.each { skippedChildDevice ->
                        def skippedMessage = "Skipped child device [${skippedChildDevice.deviceNetworkId}]: ${skippedChildDevice.reason}."
                        logWarn(skippedMessage)
                        paragraph skippedMessage
                    }

                    plan.skippedModules.each { skippedModule ->
                        def skippedName = skippedModule.name ? " ${skippedModule.name}" : ""
                        def skippedMessage = "Skipped unsupported module ${skippedModule.moduleId}${skippedName} (kind ${skippedModule.deviceKind}: ${skippedModule.deviceKindName}, manufacturer ${skippedModule.manufacturerId}, product ${skippedModule.productId})"
                        logInfo(skippedMessage)
                        paragraph skippedMessage
                    }

                    def createdCount = plan.createScenes.size() + plan.createDevices.size()
                    def updatedCount = plan.updateScenes.size() + plan.updateDevices.size()
                    def deletedCount = plan.deleteStaleDeviceNetworkIds.size()
                    paragraph "Import sync completed successfully: ${createdCount} created, ${updatedCount} updated, ${deletedCount} deleted, ${plan.skippedChildDevices.size()} child conflicts skipped, ${plan.skippedModules.size()} unsupported modules skipped."
                    app.removeSetting("upeFileData")
                }
            }

        } catch(Exception e) {
            section() {
                paragraph "Import Error: ${e.message}"
                if (!importStarted) {
                    paragraph "Existing devices were not changed."
                } else {
                    paragraph "Import failed after device changes started. Review child devices before retrying."
                }
            }
        }
    }
}

def createDevice() {
    logTrace("createDevice")

    // Validate common inputs
    if (!settings.deviceType || !settings.deviceName || !settings.networkId) {
        return dynamicPage(name: "createDevice", title: "Device Creation Failed", nextPage: "mainPage") {
            section("Error") {
                paragraph "Device Type, Device Name, and Network ID are required."
            }
        }
    }

    // Validate device-type-specific inputs
    def deviceConfig = DEVICE_TYPES[settings.deviceType]
    if (!deviceConfig) {
        return dynamicPage(name: "createDevice", title: "Device Creation Failed", nextPage: "mainPage") {
            section("Error") {
                paragraph "Invalid Device Type selected."
            }
        }
    }

    def missingInputs = deviceConfig.requiredInputs.findAll { inputConfig -> !settings[inputConfig.name] }
    if (missingInputs) {
        return dynamicPage(name: "createDevice", title: "Device Creation Failed", nextPage: "mainPage") {
            section("Error") {
                paragraph "Missing required inputs: ${missingInputs.collect { it.title }.join(', ')}."
            }
        }
    }

    // Generate deviceNetworkId based on device category
    def deviceNetworkId
    if (deviceConfig.category == "scene") {
        deviceNetworkId = buildSceneNetworkId(settings.networkId.intValue(), settings.linkId.intValue())
    } else {
        deviceNetworkId = buildDeviceNetworkId(settings.networkId.intValue(), settings.deviceId.intValue(), settings.channelId.intValue())
    }

    // Check for duplicate device
    def existingDevice = getChildDevice(deviceNetworkId)
    if (existingDevice) {
        return dynamicPage(name: "createDevice", title: "Device Creation Failed", nextPage: "mainPage") {
            section("Error") {
                paragraph "A device with Network ID ${settings.networkId}, ${deviceConfig.category == 'scene' ? 'Link ID' : 'Device ID'} ${settings[deviceConfig.category == 'scene' ? 'linkId' : 'deviceId']}, and Channel ID ${settings.channelId ?: 'N/A'} already exists."
            }
        }
    }

    // Create the device
    def childDevice
    try {
        childDevice = addChildDevice("UPBeat", deviceConfig.driverName, deviceNetworkId, [name: settings.deviceName, label: settings.voiceName ?: settings.deviceName])
    } catch (Exception e) {
        logError("Failed to create device: ${e.message}")
        return dynamicPage(name: "createDevice", title: "Device Creation Failed", nextPage: "mainPage") {
            section("Error") {
                paragraph "Failed to create the device: ${e.message}"
            }
        }
    }

    // Configure the device based on category
    childDevice.updateNetworkId(settings.networkId.intValue())
    if (deviceConfig.category == "scene") {
        childDevice.updateLinkId(settings.linkId.intValue())
    } else {
        childDevice.updateDeviceId(settings.deviceId.intValue())
        childDevice.updateChannelId(settings.channelId.intValue())
    }

    // Retrieve the device to get its numerical ID
    def createdDevice = getChildDevice(deviceNetworkId)
    if (!createdDevice) {
        logError("Failed to retrieve newly created device with network ID ${deviceNetworkId}")
        return dynamicPage(name: "createDevice", title: "Device Creation Failed", nextPage: "mainPage") {
            section("Error") {
                paragraph "Failed to retrieve the newly created device."
            }
        }
    }

    // Construct the device page URL using the device's numerical ID
    def deviceId = createdDevice.id
    def devicePageUrl = "/device/edit/${deviceId}"

    // Clear all settings
    app.removeSetting("deviceName")
    app.removeSetting("voiceName")
    app.removeSetting("networkId")
    app.removeSetting("deviceType")
    deviceConfig.requiredInputs.each { app.removeSetting(it.name) }

    // Display a confirmation page with a link to the device page
    return dynamicPage(name: "createDevice", title: "Device Created Successfully", nextPage: "mainPage") {
        section("Device Created") {
            paragraph "The device '${settings.deviceName}' has been created successfully."
            href(name: "devicePageLink", title: "Go to Device Page", url: devicePageUrl, description: "Click here to view and configure the newly created device.")
        }
    }
}

def mainPage() {
    getHubUrl()
    dynamicPage(install: true, uninstall: true) {
        /*
		// Section removed until the configuration app is ready.
        section("UPBeat Configuration") {
            if (enableConfig) {
                if (!state.accessToken) {
                    try {
                        createAccessToken()
                    }
                    catch (Exception e) {
                        paragraph("Opps. ${e.message}")
                    }
                }
                if (state.accessToken) {
                    paragraph("""<table style="padding:0px; white-space: nowrap">
                                    <tr>
                                        <td style="text-align: right; padding-right: 10px;">
                                            <strong>API Url:</strong>
                                        </td>
                                        <td style="text-align: left; padding-left: 10px;">
                                            ${getFullLocalApiServerUrl()}
                                        </td>
                                    </tr>
                                    <tr>
                                        <td style="text-align: right; padding-right: 10px;">
                                            <strong>API Token:</strong>
                                        </td>
                                        <td style="text-align: left; padding-left: 10px;">
                                            ${state.accessToken}
                                        </td>
                                    </tr>
                                </table>""")
                }
            } else {
                state.remove("accessToken")
                paragraph("Before enabling Remote Configuration, please be sure you have enabled OAuth.")
                paragraph("The setting is located under \"Apps Code\" > \"UPBeat App\" in the code view.")
            }
            input name: "enableConfig", type: "bool", title: "Enable Remote Configuration", defaultValue: false, submitOnChange: true
        }
        */
        if (app.getInstallationState() == "COMPLETE") {
            section("Bulk Device Actions"){
                input "refreshAllDeviceStates", "button", title: "Refresh All Device States"

                def logLevels = [:]

                LOG_LEVELS.values().each { level ->
                    logLevels.putIfAbsent(level, 0)
                }

                def devices = app.getChildDevices()
                devices.each { device ->
                    logLevels[LOG_LEVELS[device.getSetting("logLevel").toInteger()]] += 1
                }

                def formattedLogLevels = logLevels.collect { level, count -> "- ${level}: ${count}"}.join("\n")
                paragraph "Device count by log level:\n${formattedLogLevels}"

                input name: "logLevelGlobal", type: "enum", options: LOG_LEVELS, title: "Global Log Level", description: "Select a log level for all devices", required: false, submitOnChange: true
                if(logLevelGlobal){
                    input "setLogLevelGlobal", "button", title: "Apply Log Level Globally"
                }
            }
            section("Device Management") {
                href(name: "manualAddHref", title: "Manually Add Device", page: "addDevicePage", description: "Add a device manually")
                href(name: "manualAddHref", title: "Bulk Import", page: "bulkImportPage", description: "Import UPStart export file")
            }
        } else {
            section(){
                paragraph "Please finish the app installation by clicking 'Done'. You can manage the app once it's been installed."
            }
        }
        section("Troubleshooting") {
            input name: "logLevel", type: "enum", options: LOG_LEVELS, title: "Log Level", defaultValue: LOG_DEFAULT_LEVEL, required: true
        }
    }
}
/***************************************************************************
 * Global Static Data
 ***************************************************************************/
@Field static String pimDeviceId = "UPBeat_PIM"
@Field static final Map UPE_DEVICE_KINDS = [
        0: [name: "Other", supported: false],
        1: [name: "Keypad", supported: false],
        2: [name: "Switch", supported: true],
        3: [name: "Module", supported: true],
        4: [name: "Input Module", supported: false],
        5: [name: "Input-Output Module", supported: false],
        6: [name: "VPM", supported: false],
        7: [name: "VHC", supported: false],
        8: [name: "Thermostat", supported: false],
        9: [name: "XPW", supported: false],
        10: [name: "RFI", supported: false]
]

/***************************************************************************
 * Core App Functions
 ***************************************************************************/
void installed() {
    logTrace("installed()")
    initialize()
}

void uninstalled() {
    logTrace("uninstalled()")
    unsubscribe()
}

void updated() {
    logTrace("updated()")
    initialize()
}

def initialize() {
    logTrace("initialize()")
    getPimDevice()
    // Clear existing subscriptions to prevent duplicates
    unsubscribe()
}

/***************************************************************************
 * App Helper Functions
 ***************************************************************************/
void appButtonHandler(button) {
    logTrace("appButtonHandler(%s)", button)
    switch(button) {
        case "addDeviceBtn":
            logTrace("createDevice")
            // Validate inputs based on device type
            if (!settings.deviceType || !settings.deviceName || !settings.networkId) {
                logError("Device Type, Device Name, and Network ID are required.")
                return
            }
            if (settings.deviceType != "UPB Scene") {
                if (!settings.deviceId || !settings.channelId) {
                    logError("Device ID and Channel ID are required for ${settings.deviceType} devices.")
                    return
                }
            } else {
                if (!settings.linkId) {
                    logError("Link ID is required for UPB Scene devices.")
                    return
                }
            }

            // Generate deviceNetworkId based on device type
            def deviceNetworkId
            if (settings.deviceType == "UPB Scene") {
                deviceNetworkId = buildSceneNetworkId(settings.networkId, settings.linkId)
            } else {
                deviceNetworkId = buildDeviceNetworkId(settings.networkId, settings.deviceId, settings.channelId)
            }

            // Check for duplicate device
            def existingDevice = getChildDevice(deviceNetworkId)
            if (existingDevice) {
                logError("A device with Network ID ${settings.networkId}, Device/Link ID ${settings.deviceType == 'UPB Scene' ? settings.linkId : settings.deviceId}, and Channel ID ${settings.channelId ?: 'N/A'} already exists.")
                return
            }

            // Create the device
            def childDevice
            try {
                childDevice = addChildDevice("UPBeat", settings.deviceType, deviceNetworkId, [name: settings.deviceName, label: settings.voiceName ?: settings.deviceName])
            } catch (Exception e) {
                logError("Failed to create device: ${e.message}")
                return
            }

            // Configure the device based on type
            if (settings.deviceType == "UPB Scene") {
                childDevice.updateNetworkId(settings.networkId)
                childDevice.updateLinkId(settings.linkId)
            } else {
                childDevice.updateNetworkId(settings.networkId)
                childDevice.updateDeviceId(settings.deviceId)
                childDevice.updateChannelId(settings.channelId)
            }

            // Clear the form settings
            app.removeSetting("deviceName")
            app.removeSetting("voiceName")
            app.removeSetting("networkId")
            app.removeSetting("deviceId")
            app.removeSetting("channelId")
            app.removeSetting("deviceType")
            app.removeSetting("linkId")
            break
        case "refreshAllDeviceStates":
            refreshAllDeviceStates()
            break
        case "setLogLevelGlobal":
            setLogLevelGlobal()
            break
    }
}

def getPimDevice()
{
    logTrace("getPimDevice()")

    def pim = getChildDevice(pimDeviceId)

    if (pim == null) {
        logDebug("Creating PIM device")
        pim = addChildDevice("UPBeat", "UPB Powerline Interface Module", pimDeviceId, [name: "UPB Powerline Interface Module"])
    }

    return pim
}

private String makeUri(String extraPath) {
    logTrace("makeUri()")
    return getFullLocalApiServerUrl() + extraPath + "?access_token=${state.accessToken}"
}

String getHubUrl() {
    def localIP = location.hub.localIP
    def hubUrl = "https://${localIP}"
    return hubUrl
}

void updatePIMDevice(String ipAddress, int portNumber) {
    logTrace("updatePIMDevice()")
    def pim = getPimDevice()
    // Set the device IP
    device.updateSetting("ipAddress", [value: ipAddress, type: "text"])
    device.updateSetting("portNumber", [value: portNumber, type: "number"])
    device.updated()
}

void deleteAllDevices() {
    logTrace("deleteAllDevices()")
    def devices = app.getChildDevices()
    // Delete all child devices except PIM
    devices.each { device ->
        if (device.typeName != "UPB Powerline Interface Module") {
            logDebug("Deleting ${device.deviceNetworkId}")
            deleteChildDevice(device.deviceNetworkId)
        }
    }
}

def refreshAllDeviceStates() {
    logTrace("refreshAllDeviceStates()")
    def devices = app.getChildDevices()
    devices.each { device ->
        if (device.typeName != "UPB Powerline Interface Module" && !device.typeName.contains("Scene")) {
            logInfo("Refreshing ${device.deviceNetworkId} [${device.name}]")
            device.refresh()
        }
    }
}

def setLogLevelGlobal() {
    logTrace("setLogLevelGlobal(${logLevelGlobal})")
    if (!LOG_LEVELS.containsKey(logLevelGlobal.toInteger())) {
        logError("Invalid global log level: ${logLevelGlobal}")
        return [result: false, reason: "Invalid global log level"]
    }
    def devices = app.getChildDevices()
    devices.each { device ->
        logInfo("Setting log level [${device.name}] to ${LOG_LEVELS[logLevelGlobal.toInteger()]}")
        device.updateSetting("logLevel", [value: logLevelGlobal, type: "enum"])
    }
}

/***************************************************************************
 * Web Service Handlers for Configuration Application
 ***************************************************************************/
void handleStatus() {
    logTrace("handleStatus()")

    def data = [
            message: "UPBeat is alive an well."
    ]

    // Using JsonBuilder to convert the data map to a JSON string
    def json = new JsonBuilder(data).toPrettyString()

    render contentType: "application/json", data: json, status: 200
}

void handleAddDevice() {
    logTrace("handleAddDevice()")

    def postData = request.JSON

    logDebug("Received POST data: ${postData}")

    if ('DeviceInfo' in postData) {
        def result = addDevice(postData['DeviceInfo'])
        def data = [
                message: result
        ]
        def json = new JsonBuilder(data).toPrettyString()
        render contentType: "application/json", data: json, status: 200
    } else {
        def data = [
                error: "Invalid data received."
        ]
        def json = new JsonBuilder(data).toPrettyString()
        render contentType: "application/json", data: json, status: 400
    }
}

void handleAddScene() {
    logTrace("handleAddScene()")

    def postData = request.JSON

    logDebug("Received POST data: ${postData}")

    if ('LinkInfo' in postData) {
        def data = [
                message: "Data received successfully."
        ]
        def json = new JsonBuilder(data).toPrettyString()
        render contentType: "application/json", data: json, status: 200
    } else {
        def data = [
                error: "Invalid data received."
        ]
        def json = new JsonBuilder(data).toPrettyString()
        render contentType: "application/json", data: json, status: 400
    }
}

void handleUpdatePowerlineInterface() {
    logTrace("handleUpdatePowerlineInterface()")

    def postData = request.JSON

    logDebug("Received POST data: ${postData}")

    if ('PowerlineInterfaceInfo' in postData) {
        updatePIMDevice(postData['PowerlineInterfaceInfo']['IpAddress'], postData['PowerlineInterfaceInfo']['PortNumber'])

        def data = [
                message: "PIM Updated to ${postData['PowerlineInterfaceInfo']['IpAddress']}:${postData['PowerlineInterfaceInfo']['PortNumber']}"
        ]
        def json = new JsonBuilder(data).toPrettyString()
        render contentType: "application/json", data: json, status: 200
    } else {
        def data = [
                error: "Invalid data received."
        ]
        def json = new JsonBuilder(data).toPrettyString()
        render contentType: "application/json", data: json, status: 400
    }
}

/***************************************************************************
 * Custom App Functions
 ***************************************************************************/
def activateScene(Integer networkId, Integer linkId, Integer sourceId) {
    logTrace("activateScene(networkId=0x%02X, linkId=0x%02X, sourceId=0x%02X)", networkId, linkId, sourceId)

    // Validate inputs
    if (networkId < 0 || networkId > 255) {
        logError("Network ID ${networkId} is out of range (0-255)")
        return [result: false, reason: "Network ID must be 0-255"]
    }
    if (linkId < 0 || linkId > 255) {
        logError("Link ID ${linkId} is out of range (0-255)")
        return [result: false, reason: "Link ID must be 0-255"]
    }
    if (sourceId < 0 || sourceId > 255) {
        logError("Source ID ${sourceId} is out of range (0-255)")
        return [result: false, reason: "Source ID must be 0-255"]
    }

    def controlWord = encodeControlWord(LNK_LINK, REPRQ_NONE, ACKRQ_NONE, TX_CNT_TWO, TX_SEQ_FIRST)
    logDebug("Activating scene with controlWord=0x%04X", controlWord)
    def result = pimDevice.transmitMessage(controlWord, (byte) networkId, (byte) linkId, (byte) sourceId, UPB_ACTIVATE_LINK, null)

    if (result.result) {
        logDebug("Scene activation succeeded")
    } else {
        logError("Scene activation failed: %s", result.reason)
    }
    return result
}

def deactivateScene(Integer networkId, Integer linkId, Integer sourceId) {
    logTrace("deactivateScene(networkId=0x%02X, linkId=0x%02X, sourceId=0x%02X)", networkId, linkId, sourceId)

    // Validate inputs
    if (networkId < 0 || networkId > 255) {
        logError("Network ID ${networkId} is out of range (0-255)")
        return [result: false, reason: "Network ID must be 0-255"]
    }
    if (linkId < 0 || linkId > 255) {
        logError("Link ID ${linkId} is out of range (0-255)")
        return [result: false, reason: "Link ID must be 0-255"]
    }
    if (sourceId < 0 || sourceId > 255) {
        logError("Source ID ${sourceId} is out of range (0-255)")
        return [result: false, reason: "Source ID must be 0-255"]
    }

    def controlWord = encodeControlWord(LNK_LINK, REPRQ_NONE, ACKRQ_NONE, TX_CNT_TWO, TX_SEQ_FIRST)
    logDebug("Deactivating scene with controlWord=0x%04X", controlWord)
    def result = pimDevice.transmitMessage(controlWord, (byte) networkId, (byte) linkId, (byte) sourceId, UPB_DEACTIVATE_LINK, null)

    if (result.result) {
        logDebug("Scene deactivation succeeded")
    } else {
        logError("Scene deactivation failed: %s", result.reason)
    }
    return result
}

def gotoLevel(Integer networkId, Integer deviceId, Integer sourceId, Integer level, Integer duration, Integer channel) {
    logTrace("gotoLevel(networkId=0x%02X, deviceId=0x%02X, sourceId=0x%02X, level=%d, duration=%d, channel=%d)",
            networkId, deviceId, sourceId, level, duration, channel)

    // Validate inputs
    if (networkId < 0 || networkId > 255) {
        logError("Network ID ${networkId} is out of range (0-255)")
        return [result: false, reason: "Network ID must be 0-255"]
    }
    if (deviceId < 0 || deviceId > 255) {
        logError("Device ID ${deviceId} is out of range (0-255)")
        return [result: false, reason: "Device ID must be 0-255"]
    }
    if (sourceId < 0 || sourceId > 255) {
        logError("Source ID ${sourceId} is out of range (0-255)")
        return [result: false, reason: "Source ID must be 0-255"]
    }
    if (level < 0 || level > 100) {
        logError("Level ${level} is out of range (0-100)")
        return [result: false, reason: "Level must be 0-100"]
    }
    if (duration < 0 || duration > 255) {
        logError("Duration ${duration} is out of range (0-255)")
        return [result: false, reason: "Duration must be 0-255"]
    }
    if (channel < 0 || channel > 255) {
        logError("Channel ${channel} is out of range (0-255)")
        return [result: false, reason: "Channel must be 0-255"]
    }

    def controlWord = encodeControlWord(LNK_DIRECT, REPRQ_NONE, ACKRQ_PULSE, TX_CNT_TWO, TX_SEQ_FIRST)
    logDebug("Setting level with controlWord=0x%04X", controlWord)
    def result = pimDevice.transmitMessage(controlWord, (byte) networkId, (byte) deviceId, (byte) sourceId, UPB_GOTO, [(byte) level, (byte) duration, (byte) channel] as byte[])

    if (result.result) {
        logDebug("Goto level succeeded")
    } else {
        logError("Goto level failed: %s", result.reason)
    }
    return result
}

def blink(Integer networkId, Integer deviceId, Integer sourceId, Integer rate, Integer channel) {
    logTrace("gotoLevel(networkId=0x%02X, deviceId=0x%02X, sourceId=0x%02X, rate=%d, channel=%d)",
            networkId, deviceId, sourceId, rate, channel)

    // Validate inputs
    if (networkId < 0 || networkId > 255) {
        logError("Network ID ${networkId} is out of range (0-255)")
        return [result: false, reason: "Network ID must be 0-255"]
    }
    if (deviceId < 0 || deviceId > 255) {
        logError("Device ID ${deviceId} is out of range (0-255)")
        return [result: false, reason: "Device ID must be 0-255"]
    }
    if (sourceId < 0 || sourceId > 255) {
        logError("Source ID ${sourceId} is out of range (0-255)")
        return [result: false, reason: "Source ID must be 0-255"]
    }
    if (rate < 0 || rate > 255) {
        logError("Rate ${rate} is out of range (0-255)")
        return [result: false, reason: "Rate must be 0-255"]
    }
    if (channel < 0 || channel > 255) {
        logError("Channel ${channel} is out of range (0-255)")
        return [result: false, reason: "Channel must be 0-255"]
    }

    def controlWord = encodeControlWord(LNK_DIRECT, REPRQ_NONE, ACKRQ_PULSE, TX_CNT_TWO, TX_SEQ_FIRST)
    logDebug("Setting level with controlWord=0x%04X", controlWord)
    def result = pimDevice.transmitMessage(controlWord, (byte) networkId, (byte) deviceId, (byte) sourceId, UPB_BLINK, [(byte) rate, (byte) channel] as byte[])

    if (result.result) {
        logDebug("Blink succeeded")
    } else {
        logError("Blink failed: %s", result.reason)
    }
    return result
}

def requestDeviceState(Integer networkId, Integer deviceId, Integer sourceId) {
    logTrace("requestDeviceState(networkId=0x%02X, deviceId=0x%02X, sourceId=0x%02X)", networkId, deviceId, sourceId)

    // Validate inputs
    if (networkId < 0 || networkId > 255) {
        logError("Network ID ${networkId} is out of range (0-255)")
        return [result: false, reason: "Network ID must be 0-255"]
    }
    if (deviceId < 0 || deviceId > 255) {
        logError("Device ID ${deviceId} is out of range (0-255)")
        return [result: false, reason: "Device ID must be 0-255"]
    }
    if (sourceId < 0 || sourceId > 255) {
        logError("Source ID ${sourceId} is out of range (0-255)")
        return [result: false, reason: "Source ID must be 0-255"]
    }

    def controlWord = encodeControlWord(LNK_DIRECT, REPRQ_NONE, ACKRQ_PULSE, TX_CNT_TWO, TX_SEQ_FIRST)
    logDebug("Requesting device state with controlWord=0x%04X", controlWord)
    def result = pimDevice.transmitMessage(controlWord, (byte) networkId, (byte) deviceId, (byte) sourceId, UPB_REPORT_STATE, null)

    if (result.result) {
        logDebug("Device state request succeeded")
    } else {
        logError("Device state request failed: %s", result.reason)
    }
    return result
}

/***************************************************************************
 * Custom App Functions
 ***************************************************************************/
def updateDeviceSettings(device, settings) {
    logTrace("updateDeviceSettings(${device.deviceNetworkId})")
    if (!settings) {
        logError("Cannot update device ${device.deviceNetworkId}: Settings are null.")
        return [success: false, error: "Settings are null"]
    }
    try {
        // Update deviceNetworkId
        def deviceConfig = DEVICE_TYPES.find { it.value.driverName == device.typeName }?.value
        if (!deviceConfig) {
            logError("Cannot update device ${device.deviceNetworkId}: Unknown device type.")
            return [success: false, error: "Unknown device type"]
        }
        def newDeviceNetworkId
        if (deviceConfig.category == "scene") {
            if (!settings.networkId || !settings.linkId) {

                logError("Cannot update deviceNetworkId for ${device.deviceNetworkId}: Missing networkId or linkId.")
                return [success: false, error: "Missing networkId or linkId"]
            }
            newDeviceNetworkId = buildSceneNetworkId(settings.networkId.intValue(), settings.linkId.intValue())
        } else {
            if (!settings.networkId || !settings.deviceId || !settings.channelId) {

                logError("Cannot update deviceNetworkId for ${device.deviceNetworkId}: Missing networkId, deviceId, or channelId.")
                return [success: false, error: "Missing networkId, deviceId, or channelId"]
            }
            newDeviceNetworkId = buildDeviceNetworkId(settings.networkId.intValue(), settings.deviceId.intValue(), settings.channelId.intValue())
        }
        if (newDeviceNetworkId != device.deviceNetworkId) {
            def existingDevice = getChildDevice(newDeviceNetworkId)
            if (existingDevice && existingDevice.id != device.id) {
                logError("Cannot update deviceNetworkId for ${device.deviceNetworkId}: ${newDeviceNetworkId} conflicts with existing device.")
                return [success: false, error: "Device ID conflict: ${newDeviceNetworkId} is already in use"]
            }
            device.deviceNetworkId = newDeviceNetworkId
            logDebug("Updated deviceNetworkId to ${newDeviceNetworkId} for ${device.deviceNetworkId}")
        }
        logDebug("Updated device ${device.deviceNetworkId} settings")
        return [success: true, error: null]
    } catch (Exception e) {
        logError("Failed to update device ${device.deviceNetworkId}: ${e.message}")
        return [success: false, error: "Failed to update device: ${e.message}"]
    }
}

void addDevice(deviceInfo) {
    deviceInfo['ChannelInfo'].each { channelInfo ->
        // Generate a unique device id based on UPBeat / UPStart Data
        deviceNetworkId = buildDeviceNetworkId(deviceInfo.NetworkId, deviceInfo.ModuleId, channelInfo.ChannelId)

        if (channelInfo.Enabled) {
            deviceFullName = "${deviceInfo.RoomName} ${deviceInfo.DeviceName}${(channelInfo.ChannelId == 0) ? '' : channelInfo.ChannelId}"
            if (channelInfo.VoiceName.isEmpty())
                channelInfo.VoiceName = deviceFullName

            device = getChildDevice(deviceNetworkId)

            if (device == null) {
                if (channelInfo.DimEnabled == 1)
                    addChildDevice("UPBeat", "UPB Dimming Switch", deviceNetworkId, [label: channelInfo.VoiceName, name: deviceFullName, moduleInfo: deviceInfo])
                else
                    addChildDevice("UPBeat", "UPB Non-Dimming Switch", deviceNetworkId, [label: channelInfo.VoiceName, name: deviceFullName, moduleInfo: deviceInfo])

                device = getChildDevice(deviceNetworkId)

                // Let's request the device state in the future
                device.sendEvent(name: "switch", value: "off", isStateChange: false)
                skipEvent = true

            } else {
                logInfo("Device ${deviceNetworkId} already exists")
            }
        } else {
            logInfo("Skipping ${deviceNetworkId} not enabled")
        }
    }
}

def handleLinkEvent(String eventSource, String eventType, int networkId, int sourceId, int linkId) {
    logTrace("handleLinkEvent(eventSource: ${eventSource}, eventType: ${eventType}, networkId: ${networkId}, sourceId: ${sourceId}, linkId: ${linkId})")
    def startTime = now()
    try {
        // Enumerate all child devices, call handleLinkEvent if supported
        def deviceCount = 0
        def processedCount = 0
        getChildDevices().each { device ->
            deviceCount++
            if (device.name != "UPB Powerline Interface Module") {
                try {
                    device.handleLinkEvent(eventSource, eventType, networkId, sourceId, linkId)
                    processedCount++
                    logDebug("Dispatched handleLinkEvent(eventSource: ${eventSource}, eventType: ${eventType}, networkId: ${networkId}, sourceId: ${sourceId}, linkId: ${linkId}) on device ${device.label ?: device.name} (deviceId: ${device.getSetting('deviceId')})")
                } catch (Exception e) {
                    logWarn("Error calling handleLinkEvent on device ${device.label ?: device.name}: ${e.message}")
                }
            } else {
                logDebug("Skipped device ${device.label ?: device.name}: ${device.name == 'UPB Powerline Interface Module' ? 'PIM device' : 'lacks handleLinkEvent method'}")
            }
        }
        def elapsedTime = now() - startTime
        logDebug("Processed link event for linkId ${linkId}: ${processedCount} of ${deviceCount} devices in ${elapsedTime}ms")
    } catch (Exception e) {
        logWarn("Failed to process PIM link event: ${e.message}")
    }
}

def handleDeviceEvent(String eventSource, String eventType, int networkId, int sourceId, int destinationId, int[] messageArgs) {
    logTrace("handleDeviceEvent(eventSource: ${eventSource}, eventType: ${eventType}, networkId: ${networkId}, sourceId: ${sourceId}, destinationId: ${destinationId}, messageArgs: ${messageArgs})")
    switch(eventType){
        case "UPB_GOTO":
            def level = messageArgs[0]
            def rate = messageArgs[1]
            def channel = messageArgs[2]
            def deviceId = buildDeviceNetworkId(networkId, destinationId, channel)
            def device = getChildDevice(deviceId)
            if (device == null) {
                logWarn("No device found for ${deviceId}")
            } else {
                try {
                    device.handleGotoEvent(eventSource, eventType, networkId, sourceId, destinationId, level, rate, channel)
                } catch (Exception e) {
                    logWarn("Failed to call handleGotoEvent on ${deviceId}: ${e.message}")
                }
            }
            break;
        case "UPB_DEVICE_STATE":
            messageArgs.eachWithIndex { level, channel ->
                channel = channel + 1
                // Device report needs to be routed to the source, the destination is broadcasted
                def deviceId = buildDeviceNetworkId(networkId, sourceId, channel)
                def device = getChildDevice(deviceId)
                if (device == null) {
                    logWarn("No device found for ${deviceId}")
                } else {
                    try {
                        device.handleDeviceStateReport(eventSource, eventType, networkId, destinationId, sourceId, messageArgs)
                    } catch (Exception e) {
                        logWarn("Failed to call handleDeviceStateReport on ${deviceId}: ${e.message}")
                    }
                }
            }
            break;
        default:
            logWarn("Unhandled event eventType:${eventType}")
            break;
    }
}
