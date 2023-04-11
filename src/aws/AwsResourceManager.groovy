package aws

import com.amazonaws.services.ec2.model.KeyPair
import com.amazonaws.services.ec2.model.RunInstancesRequest
import com.amazonaws.services.ec2.model.Subnet
import com.segment.common.Conf
import common.Event
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import model.MontAwsResourceDTO
import model.json.ExtendParams

@CompileStatic
@Slf4j
@Singleton
class AwsResourceManager {
    private AwsCaller awsCaller = AwsCaller.instance

    private String awsAccountId

    final String allCidrIp = '0.0.0.0/0'

    final static Integer EC2_STOPPED_STATUS = -1

    int cidrBlockThirdByZone(String region, String zoneName) {
        // will change, use static better
        def allAzList = awsCaller.getAvailabilityZoneList(region, true).collect { it.zoneName }
        // one zone one subnet, 1 + zone index -> 172.48.1.0/24, 172.48.2.0/24, etc
        // 172.48.0.0/** left for engula mange
        1 + allAzList.indexOf(zoneName)
    }

    int waitSeconds(String region, int defaultWaitSeconds) {
        int waitSecondsFinal
        def controllerRegion = Conf.instance.get('controllerRegion')
        if (controllerRegion != region) {
            // cross region
            waitSecondsFinal = defaultWaitSeconds * 2
        } else {
            waitSecondsFinal = defaultWaitSeconds
        }
        waitSecondsFinal
    }

    // cidrBlockSuffix -> 1.0/24
    synchronized SubnetInfo createSubnet(String region, String vpcId, String cidrBlock, String az) {
        String subKey = cidrBlock

        def one = new MontAwsResourceDTO(
                vpcId: vpcId,
                type: MontAwsResourceDTO.Type.subnet.name(),
                subKey: subKey).one()
        if (one) {
            log.info('skip as using old one: {}', one.arn)
            return SubnetInfo.from(one.extendParams)
        }

        Event.builder().type(Event.Type.vpc).reason('create subnet').
                result('vpcId: ' + vpcId).build().log('region: ' + region).add()

        Subnet subnet
        def subnetExists = awsCaller.getSubnet(region, vpcId, cidrBlock, az)
        if (subnetExists) {
            subnet = subnetExists
        } else {
            subnet = awsCaller.createSubnet(region, vpcId, cidrBlock, az)
        }

        Map<String, String> params = [:]
        params.id = subnet.subnetId
        params.cidrBlock = cidrBlock
        params.region = region
        params.vpcId = vpcId
        params.zone = az

        new MontAwsResourceDTO(
                awsAccountId: awsAccountId,
                vpcId: vpcId,
                region: region,
                type: MontAwsResourceDTO.Type.subnet.name(),
                arn: subnet.subnetId,
                subKey: subKey,
                referArn: vpcId,
                extendParams: new ExtendParams(params: params)).add()

        SubnetInfo.from(params)
    }

    synchronized void deleteSubnet(String region, String subnetId) {
        Event.builder().type(Event.Type.vpc).reason('delete subnet').
                result('subnetId: ' + subnetId).build().log('region: ' + region).add()

        awsCaller.deleteSubnet(region, subnetId)
    }

    synchronized void deleteKeyPair(String region, String keyName) {
        Event.builder().type(Event.Type.ec2).reason('delete kp').
                result('region: ' + region).build().log('keyName: ' + keyName).add()

        awsCaller.deleteKeyPair(region, keyName)
    }

    synchronized KeyPair getKeyPair(String region, String vpcId, String keyName) {
        // get key pair
        String subKey = keyName
        def one = new MontAwsResourceDTO(
                vpcId: vpcId,
                type: MontAwsResourceDTO.Type.kp.name(),
                subKey: subKey).one()
        if (one) {
            log.info('skip as using old one: {}', one.arn)
            def extendParams = one.extendParams

            def keyPair = new KeyPair()
            keyPair.keyFingerprint = extendParams.get('keyFingerprint')
            keyPair.keyMaterial = extendParams.get('keyMaterial')
            keyPair.keyName = extendParams.get('keyName')
            keyPair.keyPairId = extendParams.get('keyPairId')
            return keyPair
        }

        Event.builder().type(Event.Type.ec2).reason('create kp').
                result('vpcId: ' + vpcId).build().log('subKey: ' + subKey).add()

        def keyPair = awsCaller.createKeyPair(region, keyName)

        Map<String, String> params = [:]
        params.keyFingerprint = keyPair.keyFingerprint
        params.keyMaterial = keyPair.keyMaterial
        params.keyName = keyPair.keyName
        params.keyPairId = keyPair.keyPairId

        new MontAwsResourceDTO(
                awsAccountId: awsAccountId,
                vpcId: vpcId,
                region: region,
                type: MontAwsResourceDTO.Type.kp.name(),
                arn: keyPair.keyPairId,
                subKey: subKey,
                extendParams: new ExtendParams(params: params)).add()

        keyPair
    }

    void stopEc2Instance(String region, String instanceId) {
        Event.builder().type(Event.Type.ec2).reason('stop ec2').
                result('region: ' + region).build().log('instanceId: ' + instanceId).add()

        def stateList = awsCaller.stopEc2Instance(region, instanceId)
        log.warn stateList.toString()
    }

    void terminateEc2Instance(String region, String instanceId) {
        Event.builder().type(Event.Type.ec2).reason('terminate ec2').
                result('region: ' + region).build().log('instanceId: ' + instanceId).add()

        // check if already be terminated by user in aws console
        def instanceStatus = awsCaller.getEc2InstanceStatus(region, instanceId)
        if (instanceStatus == null) {
            log.warn 'already terminated by user in aws console, instance id: {}', instanceId
            return
        }

        def stateList = awsCaller.terminateEc2Instance(region, instanceId)
        log.warn stateList.toString()
    }

    InstanceInfo runEc2Instance(String region, String az, String vpcId, String subnetId,
                                RunInstancesRequest request, String subKey) {
        def one = new MontAwsResourceDTO(
                vpcId: vpcId,
                type: MontAwsResourceDTO.Type.ec2.name(),
                subKey: subKey).one()
        if (one) {
            log.info('skip as using old one: {}', one.arn)
            return InstanceInfo.from(one.extendParams)
        }

        Event.builder().type(Event.Type.ec2).reason('create ec2').
                result('vpcId: ' + vpcId).build().log('subKey: ' + subKey).add()

        def instanceCreated = awsCaller.getEc2Instance(region, subKey)
        def instance = instanceCreated ?: awsCaller.runEc2Instance(region, request)

        Map<String, String> params = [:]
        params.id = instance.instanceId
        params.region = region
        params.az = az
        params.vpcId = vpcId
        params.subnetId = subnetId
        params.ipv4 = instance.privateIpAddress
        params.stateCode = instance.state.code

        new MontAwsResourceDTO(
                awsAccountId: awsAccountId,
                vpcId: vpcId,
                region: region,
                arn: instance.instanceId,
                referArn: subnetId,
                type: MontAwsResourceDTO.Type.ec2.name(),
                subKey: subKey,
                extendParams: new ExtendParams(params: params)).add()

        return InstanceInfo.from(params)
    }
}
