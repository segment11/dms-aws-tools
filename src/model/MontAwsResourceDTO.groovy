package model

import groovy.transform.CompileStatic
import groovy.transform.ToString
import model.json.ExtendParams

@CompileStatic
@ToString(includeNames = true, includeSuper = false)
class MontAwsResourceDTO extends BaseRecord<MontAwsResourceDTO> {

    @CompileStatic
    static enum Type {
        ec2, vpc, igw, sg, sgr, subnet, routeTable, routes, pcx, kp, targetGroup, nlb, nlbListener, endpointService
    }

    Integer id

    String region

    String vpcId

    String awsAccountId

    String arn

    String type

    // type is same, use key to diff, eg. routeTable routes
    String subKey

    Integer jobId

    Integer status

    String referArn

    ExtendParams extendParams

    Date updatedDate
}
