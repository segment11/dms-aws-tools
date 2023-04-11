package model.json

import groovy.transform.CompileStatic
import groovy.transform.ToString
import org.segment.d.json.JSONFiled

@CompileStatic
@ToString(includeNames = true)
class ExtendParams implements JSONFiled {
    Map<String, String> params = [:]

    String get(String key) {
        if (!params) {
            return null
        }
        params[key]
    }

    void put(String key, String value) {
        params[key] = value
    }

    boolean asBoolean() {
        params != null && params.size() > 0
    }
}
