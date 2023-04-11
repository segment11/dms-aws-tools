package aws

import groovy.transform.CompileStatic
import groovy.transform.ToString
import model.MontAwsResourceDTO
import model.json.ExtendParams

@CompileStatic
@ToString(includeNames = true, includeSuper = false)
class VpcInfo {
    String id

    String region

    String cidrBlock

    // security group id
    String groupId

    String routeTableId

    // 172.48.0.0/16 -> 172.48
    String cidrBlockPrefix() {
        cidrBlock.split('\\.')[0..1].join('.')
    }

    void update() {
        def one = new MontAwsResourceDTO(arn: id).queryFields('id').one()
        assert one

        Map<String, String> params = [:]
        params.id = id
        params.region = region
        params.cidrBlock = cidrBlock
        params.groupId = groupId
        params.routeTableId = routeTableId

        new MontAwsResourceDTO(id: one.id, extendParams: new ExtendParams(params: params)).update()
    }

    static VpcInfo from(ExtendParams extendParams) {
        from(extendParams.params)
    }

    static VpcInfo from(Map<String, String> params) {
        def one = new VpcInfo()
        one.id = params.id
        one.region = params.region
        one.cidrBlock = params.cidrBlock
        one.groupId = params.groupId
        one.routeTableId = params.routeTableId
        one
    }
}
