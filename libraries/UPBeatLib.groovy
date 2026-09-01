/*
* Hubitat Library: UPBeatLib
* Description: Universal Powerline Bus Helper Library for Hubitat
* Copyright: 2025 UPBeat Automation
* Licensed: Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International License
* Author: UPBeat Automation
*/
library(
        name: "UPBeatLib",
        namespace: "UPBeat",
        author: "UPBeat Automation",
        description: "Helper functions for UPB device and scene network ID encoding/decoding and packet sending",
        category: "Utilities",
        importUrl: ""
)

/**
 * Called by apps and drivers that need the Hubitat child DNI for a UPB module channel.
 * Encodes network, unit, and channel into the stable UPBeat child deviceNetworkId format.
 */
def buildDeviceNetworkId(int networkId, int unitId, int channel) {
    logTrace("buildDeviceNetworkId(networkId=%d,unitId=%d,channel=%d)", networkId, unitId, channel)
    // Validate inputs (0-255 for each field)
    if (networkId < 0 || networkId > 255) {
        throw new IllegalArgumentException("NetworkId must be 0-255, got: ${networkId}")
    }
    if (unitId < 1 || unitId > 250) {
        throw new IllegalArgumentException("UnitId must be 1-250, got: ${unitId}")
    }
    if (channel < 0 || channel > 255) {
        throw new IllegalArgumentException("Channel must be 0-255, got: ${channel}")
    }

    String deviceNetworkId = String.format("UPBeat_%02X%02X%02X", networkId, unitId, channel)

    logTrace("DeviceNetworkId: ${deviceNetworkId} (networkId=${networkId}, unitId=${unitId}, channel=${channel})")

    return deviceNetworkId
}

/**
 * Called by apps that need the Hubitat child DNI for a UPB link/scene.
 * Encodes network and link into the stable UPBeat scene child deviceNetworkId format.
 */
def buildSceneNetworkId(int networkId, int linkId) {
    logTrace("buildSceneNetworkId(networkId=%d,linkId=%d)", networkId, linkId)
    // Validate inputs (0-255 for each field)
    if (networkId < 0 || networkId > 255) {
        throw new IllegalArgumentException("NetworkId must be 0-255, got: ${networkId}")
    }
    if (linkId < 1 || linkId > 250) {
        throw new IllegalArgumentException("LinkId must be 1-250, got: ${linkId}")
    }

    String sceneNetworkId = String.format("UPBeat_%02X%02X", networkId, linkId)

    logTrace("SceneNetworkId: ${sceneNetworkId} (networkId=${networkId}, linkId=${linkId})")

    return sceneNetworkId
}

@Field static final int UPE_FILE_VERSION = 5

@Field static final Map minFieldCounts = [
        '0': 6, '1': 1, '2': 3, '3': 14, '4': 7, '5': 14, '6': 15, '7': 10,
        '8': 5, '9': 5, '10': 12, '11': 12, '12': 3, '13': 7, '14': 10,
        '17': 5,
        '18': 3, '19': 3
]

/**
 * Called by processUpeFile() before UPE record interpretation.
 * Parses the UPStart export CSV format without relying on platform CSV libraries.
 */
static List<List<String>> parse_csv(byte[] csvBytes) {
    def rows = []
    def currentRow = []
    def field = ''
    def insideQuotes = false
    def multilineField = false

    csvBytes.each { b ->
        def ch = (char) b
        if (ch == '"') {
            if (insideQuotes) {
                insideQuotes = false
                if (field.endsWith('"')) field += '"'
                else { field += '"'; multilineField = true }
            } else {
                insideQuotes = true
            }
        } else if (ch == ',' && !insideQuotes) {
            if (multilineField) field += '\n'
            else { currentRow.add(field); field = '' }
        } else if (ch == '\n' && !insideQuotes) {
            if (multilineField) {
                field += '\n'
                multilineField = false
                currentRow.add(field)
                field = ''
            } else {
                currentRow.add(field)
                rows.add(currentRow)
                currentRow = []
                field = ''
            }
        } else {
            field += ch
        }
    }

    if (field) currentRow.add(field)
    if (currentRow) rows.add(currentRow)
    return rows
}

/**
 * Called by the UPBeat app bulk import page after a user pastes UPE file content.
 * Converts supported UPStart export records into a map used by the app's sync planner.
 */
def processUpeFile(String userInput) {
    logDebug("processUpeFile()")

    def rows = parse_csv(userInput.getBytes())
    def data = [
            'systemInfo': [:], 'installer': [:], 'customer': [:],
            'links': [], 'modules': [], 'roomIcons': []
    ]

    def current_device = [:]

    for (int i = 0; i < rows.size(); i++) {
        def row = rows[i]
        def recordType = row[0]

        if (recordType in minFieldCounts && row.size() < minFieldCounts[recordType]) {
            logDebug("Warning: Invalid record ${recordType}: too few fields (${row.size()})")
            continue
        }

        switch (recordType) {
            case "0": // BOF
                data['systemInfo'] = [
                        'version': row[1].toInteger(),
                        'totalModules': row[2].toInteger(),
                        'totalLinks': row[3].toInteger(),
                        'networkId': row[4].toInteger(),
                        'networkPass': row[5].toInteger()
                ]
                break
            case "1": // EOF
                logDebug("End of Configuration")
                break
            case "2": // Link
                data['links'].add(['linkId': row[1].toInteger(), 'name': row[2]])
                break
            case "3": // Module
                current_device = ["moduleId": row[1].toInteger(),
                                  "networkId": row[2].toInteger(),
                                  "manufacturerId": row[3].toInteger(),
                                  "productId": row[4].toInteger(),
                                  "firmwareMajorVersion": row[5].toInteger(),
                                  "firmwareMinorVersion": row[6].toInteger(),
                                  "deviceKind": row[7].toInteger(),
                                  "channels": row[8].toInteger(),
                                  "transmitComponents": row[9].toInteger(),
                                  "receiveComponents": row[10].toInteger(),
                                  "roomName": row[11],
                                  "deviceName": row[12],
                                  "packetType": row[13].toInteger(),
                                  "presetInfo": [],
                                  "rockers": [],
                                  "buttons": [],
                                  "inputs": [],
                                  "channelInfo": [],
                                  "vhcs": [],
                                  "memory": [],
                                  "keypadIndicators": [],
                                  "thermostats": [],
                                  "buttonNames": []]
                data['modules'].add(current_device)
                break
            case "4": // Preset
                current_device['presetInfo'].add([
                        "channelId": row[1].toInteger(),
                        "componentId": row[2].toInteger(),
                        "linkId": row[4].toInteger(),
                        "presetDimLevel": row[5].toInteger(),
                        "presetDimFadeRate": row[6].toInteger()])
                break
            case "5": // Rocker
                current_device['rockers'].add([
                        "channelId": row[1].toInteger(),
                        "componentId": row[2].toInteger(),
                        "topRockerTransmitLinkId": row[4].toInteger(),
                        "topRockerSingleClickAction": row[5].toInteger(),
                        "topRockerDoubleClickAction": row[6].toInteger(),
                        "topRockerHoldAction": row[7].toInteger(),
                        "topRockerReleaseAction": row[8].toInteger(),
                        "bottomRockerTransmitLinkId": row[9].toInteger(),
                        "bottomRockerSingleClickAction": row[10].toInteger(),
                        "bottomRockerDoubleClickAction": row[11].toInteger(),
                        "bottomRockerHoldAction": row[12].toInteger(),
                        "bottomRockerReleaseAction": row[13].toInteger()
                ])
                break
            case "6": // Button
                current_device['buttons'].add([
                        "channelId": row[1].toInteger(),
                        "componentId": row[2].toInteger(),
                        "buttonLinkId": row[4].toInteger(),
                        "singleClickAction": row[5].toInteger(),
                        "doubleClickAction": row[6].toInteger(),
                        "holdAction": row[7].toInteger(),
                        "releaseAction": row[8].toInteger(),
                        "singleClickToggleAction": row[9].toInteger(),
                        "doubleClickToggleAction": row[10].toInteger(),
                        "holdToggleAction": row[11].toInteger(),
                        "releaseToggleAction": row[12].toInteger(),
                        "indicatorLink": row[13].toInteger(),
                        "indicatorByte": row[14].toInteger()
                ])
                break
            case "7": // Input
                current_device['inputs'].add([
                        "channelId": row[1].toInteger(),
                        "componentId": row[2].toInteger(),
                        "openLinkId": row[4].toInteger(),
                        "openCommandId": row[5].toInteger(),
                        "openToggleCommandId": row[6].toInteger(),
                        "closeLinkId": row[7].toInteger(),
                        "closeCommandId": row[8].toInteger(),
                        "closeToggleCommandId": row[9].toInteger()
                ])
                break
            case "8": // Channel Info
                current_device['channelInfo'].add([
                        "channelId": row[1].toInteger(),
                        "dimEnabled": row[3].toInteger(),
                        "defaultFadeRate": row[4].toInteger()
                ])
                break
            case "9": // VHC
                current_device['vhcs'].add([
                        "channelId": row[1].toInteger(),
                        "componentId": row[2].toInteger(),
                        "transmitCommand": row[4].toInteger()
                ])
                break
            case "10": // Installer Info
                data['installer'] = [
                        "company": row[1] ?: "",
                        "name": row[2] ?: "",
                        "address": row[3] ?: "",
                        "city": row[4] ?: "",
                        "state": row[5] ?: "",
                        "zip": row[6] ?: "",
                        "phone": row[7] ?: "",
                        "email": row[8] ?: "",
                        "fax": row[9] ?: "",
                        "pager": row[10] ?: "",
                        "web": row[11] ?: ""
                ]
                break
            case "11": // Customer
                data['customer'] = [
                        "company": row[1] ?: "",
                        "name": row[2] ?: "",
                        "address": row[3] ?: "",
                        "city": row[4] ?: "",
                        "state": row[5] ?: "",
                        "zip": row[6] ?: "",
                        "phone": row[7] ?: "",
                        "email": row[8] ?: "",
                        "fax": row[9] ?: "",
                        "pager": row[10] ?: "",
                        "web": row[11] ?: ""
                ]
                break
            case "12": // Device Memory
                logDebug("Memory (${current_device}): ${row[1..-1]}")
                current_device['memory'].add([
                        "address": row[1],
                        "data": row[2..-1].join(',')
                ])
                break
            case "13": // Keypad Indicator
                current_device['keypadIndicators'].add([
                        "channelId": row[1].toInteger(),
                        "componentId": row[2].toInteger(),
                        "linkId": row[4].toInteger(),
                        "mask1": row[5].toInteger(),
                        "mask2": row[6].toInteger()
                ])
                break
            case "14": // Thermostat
                current_device['thermostats'].add([
                        "channelId": row[1].toInteger(),
                        "componentId": row[2].toInteger(),
                        "firmwareVersion": row[4],
                        "wduVersion": row[5],
                        "units": row[6].toInteger(),
                        "inhibitLink": row[7].toInteger(),
                        "linkBase": row[8].toInteger(),
                        "setpointDelta": row[9].toInteger()
                ])
                break
            case "17": // Keypad Button Name
                current_device['buttonNames'].add([
                        "channelId": row[1].toInteger(),
                        "componentId": row[2].toInteger(),
                        "moduleId": row[3].toInteger(),
                        "name": row[4] ?: ""
                ])
                break
            case "18": // Room Icon
                //logDebug("Room Name: ${row[1]}")
                //logDebug("Icon Name: ${row[2]}")
                //data['roomIcons'].add(["roomName": row[1], "iconName": row[2]])
                break
            case "19": // Device Icon
                //logDebug("Module ID: ${row[1]}")
                //logDebug("Icon Name: ${row[2]}")
                //data['modules'][row[1]].putIfAbsent('deviceIcon', [])
                //data['modules'][row[1]]['deviceIcon'] = ["iconName": row[2..-1].join(',')]
                break
            default:
                logDebug("Unknown Data: ${row[0]}")
                break
        }

        if (data['systemInfo']['version'] && data['systemInfo']['version'] != UPE_FILE_VERSION) {
            error = sprintf("UPE file version is %d must be %d", data['systemInfo']['version'], UPE_FILE_VERSION)
            logError(error)
            throw new IllegalArgumentException(error)
        }
    }
    return data
}
