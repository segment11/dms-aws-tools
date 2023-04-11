package support

import groovy.transform.CompileStatic

@CompileStatic
@Singleton
class CacheSession {
    Map<String, Object> cached = [:]

    void put(String key, Object val) {
        cached[key] = val
    }

    Object get(String key) {
        cached[key]
    }
}
