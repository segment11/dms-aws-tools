package deploy

import groovy.transform.CompileStatic

@CompileStatic
class RemoteInfo {
    String host
    int port
    String user
    String password
    String rootPass

    boolean isUsePass = true
    String privateKeyContent
    String privateKeySuffix = '.rsa'
}
