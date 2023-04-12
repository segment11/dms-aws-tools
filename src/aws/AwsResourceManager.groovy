package aws

import com.amazonaws.services.ec2.model.*
import com.segment.common.Conf
import com.segment.common.Utils
import common.Event
import ex.AwsResourceCreateException
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import model.MontAwsResourceDTO
import model.json.ExtendParams

@CompileStatic
@Slf4j
@Singleton
class AwsResourceManager {
    private AwsCaller awsCaller = AwsCaller.instance

    final String allCidrIp = '0.0.0.0/0'

    final static Integer EC2_STOPPED_STATUS = -1

    int cidrBlockThirdByZone(String region, String zoneName) {
        // will change, use static better
        def allAzList = awsCaller.getAvailabilityZoneList(region, true).collect { it.zoneName }
        // one zone one subnet, 1 + zone index -> 172.48.1.0/24, 172.48.2.0/24, etc
        // 172.48.0.0/** left for engula mange
        1 + allAzList.indexOf(zoneName)
    }

    synchronized VpcInfo createVpcIfNotExists(String region, String cidrBlock) {
        def oneList = new MontAwsResourceDTO(
                region: region,
                type: MontAwsResourceDTO.Type.vpc.name()).loadList()
        if (oneList) {
            for (one in oneList) {
                def vpcInfo = VpcInfo.from(one.extendParams)
                if (vpcInfo.cidrBlock == cidrBlock) {
                    log.info('skip as using old one: {}', one.arn)
                    return vpcInfo
                }
            }
        }

        Vpc targetVpc
        def vpcList = awsCaller.listVpc(region)
        if (vpcList) {
            for (vpcExists in vpcList) {
                if (vpcExists.cidrBlock == cidrBlock) {
                    targetVpc = vpcList[0]
                }
            }
        }

        if (!targetVpc) {
            Event.builder().type(Event.Type.vpc).reason('create vpc').
                    result('region: ' + region).build().log('with cidr block: ' + cidrBlock).add()

            targetVpc = awsCaller.createVpc(region, cidrBlock)

            // wait a while and then create subnet / igw etc.
            def waitSeconds = Conf.instance.getInt('vpc.created.wait.seconds', 5)
            log.warn 'wait after create new vpc, seconds: {}', waitSeconds
            Thread.sleep(waitSeconds * 1000)
        }

        Map<String, String> params = [:]
        params.id = targetVpc.vpcId
        params.region = region
        params.cidrBlock = targetVpc.cidrBlock
        params.groupId = awsCaller.getDefaultSg(region, targetVpc.vpcId)
        params.routeTableId = awsCaller.getDefaultRouteTable(region, targetVpc.vpcId)

        new MontAwsResourceDTO(
                vpcId: targetVpc.vpcId,
                region: region,
                arn: targetVpc.vpcId,
                type: MontAwsResourceDTO.Type.vpc.name(),
                extendParams: new ExtendParams(params: params)).add()

        return VpcInfo.from(params)
    }

    synchronized void initSecurityGroupRules(VpcInfo vpcInfo, Integer proxyPort) {
        final String allIpProtocol = '-1'

        def region = vpcInfo.region
        def vpcId = vpcInfo.id

        String subKeyFromUser = 'user_application' + ':' + vpcInfo.cidrBlock
        def oneFromUser = new MontAwsResourceDTO(
                vpcId: vpcId,
                type: MontAwsResourceDTO.Type.sgr.name(),
                subKey: subKeyFromUser).one()
        if (!oneFromUser) {
            Event.builder().type(Event.Type.vpc).reason('add sgr').
                    result('vpcId: ' + vpcId).build().log('subKey: ' + subKeyFromUser).add()

            // all ip (user application vpc cidr is better) can access this vpc proxy port
            List<IpPermission> ipPermissions = []
            ipPermissions << new IpPermission().
                    withIpProtocol('tcp').
                    withIpv4Ranges([new IpRange().withCidrIp(allCidrIp)]).
                    withFromPort(proxyPort).withToPort(proxyPort)

            final int sshPort = 22
            ipPermissions << new IpPermission().
                    withIpProtocol('tcp').
                    withIpv4Ranges([new IpRange().withCidrIp(allCidrIp)]).
                    withFromPort(sshPort).withToPort(sshPort)

            // may already exists, aws just response warning
            def isOkIngress = awsCaller.createSgrIngress(region, vpcInfo.groupId, ipPermissions)
            if (!isOkIngress) {
                throw new AwsResourceCreateException('create sgr ingress fail! groupId: '
                        + vpcInfo.groupId + ' subKey: ' + subKeyFromUser)
            }

            new MontAwsResourceDTO(
                    vpcId: vpcId,
                    region: region,
                    type: MontAwsResourceDTO.Type.sgr.name(),
                    arn: Utils.uuid('sgr-i-'),
                    subKey: subKeyFromUser).add()
        } else {
            log.info('skip as using old one: {}', oneFromUser.arn)
        }

        // egress visit internet
        String subKey2 = vpcInfo.cidrBlock + ':' + allCidrIp
        def two = new MontAwsResourceDTO(
                vpcId: vpcId,
                type: MontAwsResourceDTO.Type.sgr.name(),
                subKey: subKey2).one()
        if (!two) {
            Event.builder().type(Event.Type.vpc).reason('add sgr').
                    result('vpcId: ' + vpcId).build().log('subKey: ' + subKey2).add()

            List<IpPermission> ipPermissionsEgress = []
            ipPermissionsEgress << new IpPermission().
                    withIpProtocol(allIpProtocol).
                    withIpv4Ranges([new IpRange().withCidrIp(allCidrIp)])
            def isOkEgress = awsCaller.createSgrEgress(region, vpcInfo.groupId, ipPermissionsEgress)
            if (!isOkEgress) {
                throw new AwsResourceCreateException('create sgr egress fail! groupId: '
                        + vpcInfo.groupId + ' cidrIp: ' + isOkEgress)
            }

            new MontAwsResourceDTO(
                    vpcId: vpcId,
                    region: region,
                    type: MontAwsResourceDTO.Type.sgr.name(),
                    arn: Utils.uuid('sgr-e-'),
                    referArn: vpcInfo.groupId,
                    subKey: subKey2).add()
        } else {
            log.info('skip as using old one: {}', two.arn)
        }
    }

    synchronized boolean addIgwRoute(String region, String vpcId, String routeTableId, String igwId) {
        addRoute(region, vpcId, routeTableId, allCidrIp, igwId)
    }

    synchronized boolean addRoute(String region, String vpcId, String routeTableId, String cidrBlock, String gatewayId) {
        String subKey = cidrBlock + ':' + gatewayId

        def one = new MontAwsResourceDTO(
                vpcId: vpcId,
                type: MontAwsResourceDTO.Type.routes.name(),
                subKey: subKey).one()
        if (one) {
            log.info('skip as using old one: {}', one.arn)
            return true
        }

        Event.builder().type(Event.Type.vpc).reason('add route').
                result('vpcId: ' + vpcId).build().log('subKey: ' + subKey).add()

        awsCaller.createRouteByGateway(region, routeTableId, cidrBlock, gatewayId)
        new MontAwsResourceDTO(
                vpcId: vpcId,
                region: region,
                type: MontAwsResourceDTO.Type.routes.name(),
                arn: Utils.uuid('routes-'),
                referArn: routeTableId,
                subKey: subKey).add()
        true
    }

    synchronized String addInternetGateway(VpcInfo vpcInfo) {
        def region = vpcInfo.region
        def vpcId = vpcInfo.id

        String subKey = vpcId + ':internet_gateway'

        def one = new MontAwsResourceDTO(
                vpcId: vpcId,
                type: MontAwsResourceDTO.Type.igw.name(),
                subKey: subKey).one()
        if (one) {
            log.info('skip as using old one: {}', one.arn)
            return one.arn
        }

        Event.builder().type(Event.Type.vpc).reason('add internet gateway').
                result('vpcId: ' + vpcId).build().log('subKey: ' + subKey).add()

        def igw = awsCaller.createInternetGateway(region)
        def igwId = igw.internetGatewayId
        new MontAwsResourceDTO(
                vpcId: vpcId,
                region: region,
                type: MontAwsResourceDTO.Type.igw.name(),
                arn: igwId,
                referArn: vpcId,
                subKey: subKey).add()

        awsCaller.attachInternetGateway(region, vpcId, igwId)
        igwId
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
