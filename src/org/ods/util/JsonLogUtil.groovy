package org.ods.util

import com.cloudbees.groovy.cps.NonCPS
import groovy.json.JsonOutput
import groovy.transform.TypeChecked

@TypeChecked
class JsonLogUtil {

    static String debug(ILogger logger, String msg, Object jsonObject) {
        if (logger.debugMode) {
            if (msg) {
                logger.debug(msg)
            }
            if (jsonObject) {
                logger.debug(jsonToString(jsonObject))
            }
        }
    }

    @NonCPS
    static String jsonToString(Object jsonObject) {
        return JsonOutput.prettyPrint(JsonOutput.toJson(jsonObject))
    }

}