/*
 * Hubitat Library: UPBeatLogger
 * Description: Logger library used by Apps and Devices
 * Copyright: 2026 UPBeat Automation
 * Licensed: Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International License
 * Author: UPBeat Automation
 */
import groovy.transform.Field

library (
        name: "UPBeatLogger",
        namespace: "UPBeat",
        category: "Convenience",
        description: "Logger library used by Apps and Devices",
        author: "UPBeat Automation"
)

@Field static final Map LOG_LEVELS = [0:"Off", 1:"Error", 2:"Warn", 3:"Info", 4:"Debug", 5:"Trace"]
@Field static final int LOG_DEFAULT_LEVEL = 3

String sprintf(String format, Object arg){
    if (arg instanceof Byte) {
        arg & 0xFF
    } else if (arg instanceof byte[]) {
        arg = "[${arg.collect { String.format("0x%02X", it & 0xFF) }.join(", ")}]"
    } else {
        arg
    }
    String.format(format, arg)
}

String sprintf(String format, Object... args) {
    def processedArgs = args.collect { arg ->
        if (arg instanceof Byte) {
            arg & 0xFF
        } else if (arg instanceof byte[]) {
            arg = "[${arg.collect { String.format("0x%02X", it & 0xFF) }.join(", ")}]"
        } else {
            arg
        }
    }
    String.format(format, processedArgs as Object[])
}

void logError(String format, Object arg) {
    if (logLevel.toInteger() >= 1) {
        log.error(arg ? sprintf(format, arg) : format)
    }
}

void logWarn(String format, Object arg) {
    if (logLevel.toInteger() >= 2) {
        log.warn(arg ? sprintf(format, arg) : format)
    }
}

void logInfo(String format, Object arg) {
    if (logLevel.toInteger() >= 3) {
        log.info(arg ? sprintf(format, arg) : format)
    }
}

void logDebug(String format, Object arg) {
    if (logLevel.toInteger() >= 4) {
        log.debug arg ? sprintf(format, arg) : format
    }
}

void logTrace(String format, Object arg) {
    if (logLevel.toInteger() >= 5) {
        log.trace arg ? sprintf(format, arg) : format
    }
}

void logError(String format, Object... args) {
    if (logLevel.toInteger() >= 1) {
        log.error(args ? sprintf(format, args) : format)
    }
}

void logWarn(String format, Object... args) {
    if (logLevel.toInteger() >= 2) {
        log.warn(args ? sprintf(format, args) : format)
    }
}

void logInfo(String format, Object... args) {
    if (logLevel.toInteger() >= 3) {
        log.info(args ? sprintf(format, args) : format)
    }
}

void logDebug(String format, Object... args) {
    if (logLevel.toInteger() >= 4) {
        log.debug args ? sprintf(format, args) : format
    }
}

void logTrace(String format, Object... args) {
    if (logLevel.toInteger() >= 5) {
        log.trace args ? sprintf(format, args) : format
    }
}