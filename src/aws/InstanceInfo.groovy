package aws

import groovy.transform.CompileStatic
import groovy.transform.ToString
import model.json.ExtendParams

@CompileStatic
@ToString(includeNames = true, includeSuper = false)
class InstanceInfo {
    String id

    String region

    String az

    String vpcId

    String subnetId

    String ipv4

    String stateCode

    boolean running() {
        '16' == stateCode
    }

    static InstanceInfo from(ExtendParams extendParams) {
        from(extendParams.params)
    }

    static InstanceInfo from(Map<String, String> params) {
        def one = new InstanceInfo()
        one.id = params.id
        one.region = params.region
        one.az = params.az
        one.vpcId = params.vpcId
        one.subnetId = params.subnetId
        one.ipv4 = params.ipv4
        one.stateCode = params.stateCode
        one
    }
}
