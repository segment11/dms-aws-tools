package aws

import groovy.transform.CompileStatic
import groovy.transform.ToString
import model.json.ExtendParams

@CompileStatic
@ToString(includeNames = true, includeSuper = false)
class SubnetInfo {
    // subnetId not arn
    String id

    String region

    String zone

    String vpcId

    String cidrBlock

    static SubnetInfo from(ExtendParams extendParams) {
        from(extendParams.params)
    }

    static SubnetInfo from(Map<String, String> params) {
        def one = new SubnetInfo()
        one.id = params.id
        one.region = params.region
        one.zone = params.zone
        one.vpcId = params.vpcId
        one.cidrBlock = params.cidrBlock
        one
    }
}
