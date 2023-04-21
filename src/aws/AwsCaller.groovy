package aws

import com.amazonaws.auth.AWSCredentials
import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.Regions
import com.amazonaws.services.ec2.AmazonEC2
import com.amazonaws.services.ec2.AmazonEC2AsyncClient
import com.amazonaws.services.ec2.model.*
import com.segment.common.Conf
import groovy.transform.CompileStatic
import groovy.transform.Memoized
import groovy.util.logging.Slf4j

@CompileStatic
@Slf4j
@Singleton
class AwsCaller {
    private String accessKey
    private String secretKey

    void init(String accessKey, String secretKey) {
        this.accessKey = accessKey
        this.secretKey = secretKey
    }

    boolean isAliyun = false
    // default use aws sdk api
    boolean isAws = true

    private Map<String, AmazonEC2> clientCached = new HashMap<>()

    @CompileStatic
    static class LocalProvider implements AWSCredentialsProvider {
        private String accessKey
        private String secretKey

        LocalProvider(String accessKey, String secretKey) {
            this.accessKey = accessKey
            this.secretKey = secretKey
        }

        @Override
        AWSCredentials getCredentials() {
            return new BasicAWSCredentials(accessKey, secretKey)
        }

        @Override
        void refresh() {}
    }

    private synchronized AmazonEC2 getEc2Client(String regionName) {
        def client = clientCached[regionName]
        if (client) {
            return client
        }
        def clientNew = AmazonEC2AsyncClient.builder().
                withCredentials(new LocalProvider(accessKey, secretKey)).
                withRegion(Regions.fromName(regionName)).
                build()
        clientCached[regionName] = clientNew
        return clientNew
    }

    synchronized void shutdown() {
        clientCached.each { k, v ->
            log.info 'ready shutdown ec2 client for region {}', k
            v.shutdown()
            log.info 'done shutdown ec2 client for region {}', k
        }

        if (isAliyun) {
            AliyunCaller.instance.shutdown()
        }
    }

    List<AwsRegion> getRegions() {
        if (isAliyun) {
            def r = AliyunCaller.instance.getRegions()
            return r.collect {
                return new AwsRegion(name: it.regionId, des: it.localName)
            }
        }

        List<Regions> r = []
        for (region in Regions.values()) {
            r << region
        }
        r.remove(Regions.GovCloud)
        r.remove(Regions.US_GOV_EAST_1)
        r.collect {
            new AwsRegion(name: it.name, des: it.description)
        }
    }

    @Memoized
    List<InstanceTypeInfo2> getInstanceTypeListInRegion(String regionName, String instanceTypePattern, String architecture) {
        if (isAliyun) {
            def r = AliyunCaller.instance.getInstanceTypeList(instanceTypePattern, architecture)
            return r.collect {
                def memorySize = it.memorySize
                def memMB = memorySize * 1024
                return new InstanceTypeInfo2(it.instanceTypeId, memMB.longValue(), it.cpuCoreCount, it.cpuArchitecture)
            }
        }

        def client = getEc2Client(regionName)
        def request = new DescribeInstanceTypesRequest()
                .withFilters(new Filter('instance-type', [instanceTypePattern + '*']),)
        if (architecture) {
            request.withFilters(new Filter('processor-info.supported-architecture', [architecture]))
        }
        def result = client.describeInstanceTypes(request)
        def r = result.instanceTypes

        r.collect {
            new InstanceTypeInfo2(it.instanceType, it.memoryInfo.sizeInMiB, it.VCpuInfo.defaultVCpus,
                    it.processorInfo.supportedArchitectures.join(','))
        }.sort { it.instanceType }
    }

    @Memoized
    List<ImageInfo> getImageListInRegion(String regionName, String name, String architecture) {
        if (isAliyun) {
            def r = AliyunCaller.instance.getImageListInRegion(regionName, name, architecture)
            return r.collect {
                return new ImageInfo(it.imageId, it.imageName, it.architecture)
            }
        }

        def client = getEc2Client(regionName)
        def request = new DescribeImagesRequest().withFilters(new Filter('name', [name]))
        if (architecture) {
            request.withFilters(new Filter('architecture', [architecture]))
        }
        def result = client.describeImages(request)
        def r = result.images
        r.collect {
            new ImageInfo(it.imageId, it.name, it.architecture)
        }.sort { it.name }
    }

    private Map<String, List<AvailabilityZone>> zonesCached = [:]

    List<AvailabilityZone> getAvailabilityZoneList(String regionName) {
        if (isAliyun) {
            // always cache
            def r = AliyunCaller.instance.getAvailabilityZoneList(regionName)
            return r.collect {
                return new AvailabilityZone()
                        .withZoneName(it.zoneId)
                        .withState('available')
                        .withRegionName(regionName)
            }
        }

        def cached = zonesCached[regionName]
        if (cached) {
            return cached
        }

        def client = getEc2Client(regionName)
        def request = new DescribeAvailabilityZonesRequest().withAllAvailabilityZones(true)
        def result = client.describeAvailabilityZones(request)
        def list = result.availabilityZones
        zonesCached[regionName] = list
        list
    }

    List<Vpc> listVpc(String regionName) {
        if (isAliyun) {
            def r = AliyunCaller.instance.listVpc(regionName)
            return r.collect {
                new Vpc()
                        .withVpcId(it.vpcId)
                        .withCidrBlock(it.cidrBlock)
                        .withState(it.status)
            }
        }

        def client = getEc2Client(regionName)
        def result = client.describeVpcs()
        result.vpcs
    }

    Vpc createVpc(String regionName, String cidrBlock) {
        if (isAliyun) {
            def r = AliyunCaller.instance.createVpc(regionName, cidrBlock)
            return new Vpc()
                    .withVpcId(r.vpcId)
                    .withCidrBlock(cidrBlock)
        }

        def client = getEc2Client(regionName)
        def tagSpec = new TagSpecification().
                withResourceType(ResourceType.Vpc).
                withTags(new Tag('region', regionName))
        def request = new CreateVpcRequest().
                withCidrBlock(cidrBlock).
                withTagSpecifications(tagSpec)
        def result = client.createVpc(request)
        result.vpc
    }

    Vpc getVpcById(String regionName, String vpcId) {
        if (isAliyun) {
            def r = AliyunCaller.instance.getVpcById(regionName, vpcId)
            return new Vpc()
                    .withVpcId(r.vpcId)
                    .withCidrBlock(r.cidrBlock)
                    .withState(r.status)
        }

        def client = getEc2Client(regionName)
        def request = new DescribeVpcsRequest().
                withVpcIds(vpcId)
        def result = client.describeVpcs(request)
        if (result.vpcs.size() == 0) {
            return null
        }
        result.vpcs[0]
    }

    void deleteVpc(String regionName, String vpcId) {
        if (isAliyun) {
            AliyunCaller.instance.deleteVpc(regionName, vpcId)
            return
        }

        def client = getEc2Client(regionName)
        def request = new DeleteVpcRequest().
                withVpcId(vpcId)
        client.deleteVpc(request)
    }

    String getDefaultGroupId(String regionName, String vpcId) {
        if (isAliyun) {
            def list = AliyunCaller.instance.listSecurityGroup(regionName, vpcId)
            if (!list) {
                throw new IllegalStateException("vpc $vpcId has no router table")
            }
            return list[0].securityGroupId
        }

        def client = getEc2Client(regionName)
        def request = new DescribeSecurityGroupsRequest().
                withFilters(new Filter('vpc-id').withValues(vpcId))
        def result = client.describeSecurityGroups(request)
        result.securityGroups[0].groupId
    }

    Boolean createSgrIngress(String regionName, String groupId, List<IpPermission> ipPermissions) {
        if (isAliyun) {
            def aliyunCaller = AliyunCaller.instance
            for (one in ipPermissions) {
                for (ipv4Range in one.ipv4Ranges) {
                    def isOk = aliyunCaller.createSgrIngress(regionName, groupId,
                            one.ipProtocol, ipv4Range.cidrIp, one.fromPort, one.toPort)
                    if (!isOk) {
                        // ignore
                        log.warn 'create security group ingress rule failed, protocol: {}, cidr: {}, from port: {}, to port: {}',
                                one.ipProtocol, ipv4Range.cidrIp, one.fromPort, one.toPort
                    }
                }
            }
            return true
        }

        def client = getEc2Client(regionName)
        def request = new AuthorizeSecurityGroupIngressRequest().
                withGroupId(groupId).
                withIpPermissions(ipPermissions)
        try {
            def result = client.authorizeSecurityGroupIngress(request)
            result.return
        } catch (AmazonEC2Exception e) {
            def message = e.message
            if (message && message.contains('already exists')) {
                log.warn message
                return true
            } else {
                throw e
            }
        }
    }

    Boolean createSgrEgress(String regionName, String groupId, List<IpPermission> ipPermissions) {
        if (isAliyun) {
            def aliyunCaller = AliyunCaller.instance
            for (one in ipPermissions) {
                for (ipv4Range in one.ipv4Ranges) {
                    def isOk = aliyunCaller.createSgrEgress(regionName, groupId,
                            one.ipProtocol, ipv4Range.cidrIp, one.fromPort, one.toPort)
                    if (!isOk) {
                        // ignore
                        log.warn 'create security group egress rule failed, protocol: {}, cidr: {}, from port: {}, to port: {}',
                                one.ipProtocol, ipv4Range.cidrIp, one.fromPort, one.toPort
                    }
                }
            }
            return true
        }

        def client = getEc2Client(regionName)
        def request = new AuthorizeSecurityGroupEgressRequest().
                withGroupId(groupId).
                withIpPermissions(ipPermissions)
        try {
            def result = client.authorizeSecurityGroupEgress(request)
            result.return
        } catch (AmazonEC2Exception e) {
            def message = e.message
            if (message && message.contains('already exists')) {
                log.warn message
                return true
            } else {
                throw e
            }
        }
    }

    String getDefaultRouteTableId(String regionName, String vpcId) {
        if (isAliyun) {
            def list = AliyunCaller.instance.listRouteTable(regionName, vpcId)
            if (!list) {
                throw new IllegalStateException("vpc $vpcId has no router table")
            }
            return list[0].routeTableId
        }

        def client = getEc2Client(regionName)
        def request = new DescribeRouteTablesRequest().
                withFilters(new Filter('vpc-id').withValues(vpcId))
        def result = client.describeRouteTables(request)
        result.routeTables[0].routeTableId
    }

    Boolean createRoute(String regionName, String routeTableId, String cidrBlock, String nextHopId, String nextHopType = 'internet-gateway') {
        if (isAliyun) {
            return AliyunCaller.instance.createRoute(regionName, routeTableId, cidrBlock, nextHopId, nextHopType)
        }

        def client = getEc2Client(regionName)
        def request = new CreateRouteRequest().
                withRouteTableId(routeTableId).
                withDestinationCidrBlock(cidrBlock)

        if (nextHopType == 'internet-gateway') {
            request.withGatewayId(nextHopId)
        } else if (nextHopType == 'nat-gateway') {
            request.withNatGatewayId(nextHopId)
        } else if (nextHopType == 'vpc-peering-connection') {
            request.withVpcPeeringConnectionId(nextHopId)
        } else {
            throw new IllegalArgumentException("not implemented yet: $nextHopType")
        }

        def result = client.createRoute(request)
        result.return
    }

    void deleteRoute(String regionName, String routeTableId, String cidrBlock) {
        if (isAliyun) {
            AliyunCaller.instance.deleteRoute(regionName, routeTableId, cidrBlock)
            return
        }

        def client = getEc2Client(regionName)
        def request = new DeleteRouteRequest().
                withRouteTableId(routeTableId).
                withDestinationCidrBlock(cidrBlock)

        client.deleteRoute(request)
    }

    String createInternetGateway(String regionName) {
        if (isAliyun) {
            throw new IllegalStateException('aliyun create and attach is one step, use createIpv4Gateway instead')
        }

        def client = getEc2Client(regionName)
        def request = new CreateInternetGatewayRequest()
        def result = client.createInternetGateway(request)
        result.internetGateway.internetGatewayId
    }

    void attachInternetGateway(String regionName, String vpcId, String gatewayId) {
        if (isAliyun) {
            throw new IllegalStateException('aliyun create and attach is one step, use createIpv4Gateway instead')
        }

        def client = getEc2Client(regionName)
        def request = new AttachInternetGatewayRequest().
                withVpcId(vpcId).
                withInternetGatewayId(gatewayId)
        client.attachInternetGateway(request)
    }

    InternetGateway getInternetGatewayByVpcId(String regionName, String vpcId) {
        def client = getEc2Client(regionName)
        def request = new DescribeInternetGatewaysRequest().
                withFilters(new Filter('attachment.vpc-id').withValues(vpcId))
        def result = client.describeInternetGateways(request)
        result.internetGateways[0]
    }

    void detachInternetGateway(String regionName, String vpcId, String gatewayId) {
        def client = getEc2Client(regionName)
        def request = new DetachInternetGatewayRequest().
                withVpcId(vpcId).
                withInternetGatewayId(gatewayId)
        client.detachInternetGateway(request)
    }

    void deleteInternetGateway(String regionName, String gatewayId) {
        if (isAliyun) {
            AliyunCaller.instance.deleteIpv4Gateway(regionName, gatewayId)
            return
        }

        def client = getEc2Client(regionName)
        def request = new DeleteInternetGatewayRequest().
                withInternetGatewayId(gatewayId)
        client.deleteInternetGateway(request)
    }

    List<Subnet> listSubnet(String regionName, String vpcId) {
        if (isAliyun) {
            def r = AliyunCaller.instance.listVSwitch(regionName, vpcId)
            return r.collect {
                new Subnet()
                        .withSubnetId(it.vSwitchId)
                        .withCidrBlock(it.cidrBlock)
                        .withAvailabilityZone(it.zoneId)
                        .withState(it.status)
            }
        }

        def client = getEc2Client(regionName)
        def request = new DescribeSubnetsRequest().
                withFilters(new Filter('vpc-id').withValues(vpcId))
        def result = client.describeSubnets(request)
        result.subnets
    }

    Subnet getSubnet(String regionName, String vpcId, String cidrBlock, String zone) {
        if (isAliyun) {
            def r = AliyunCaller.instance.getVSwitch(regionName, vpcId, cidrBlock)
            if (!r) {
                return null
            }
            return new Subnet()
                    .withSubnetId(r.vSwitchId)
                    .withCidrBlock(r.cidrBlock)
                    .withAvailabilityZone(r.zoneId)
                    .withState(r.status)
        }

        def client = getEc2Client(regionName)
        def request = new DescribeSubnetsRequest().
                withFilters(new Filter('cidr-block').withValues(cidrBlock),
                        new Filter('vpc-id').withValues(vpcId))
        def result = client.describeSubnets(request)
        def list = result.subnets
        if (!list) {
            return null
        }
        def subnet = list[0]
        if (subnet.availabilityZone != zone) {
            throw new IllegalStateException('subnet with the cidr block already exists: ' + cidrBlock + ' but zone not match: ' + zone)
        }
        subnet
    }

    Subnet getSubnetById(String regionName, String subnetId) {
        if (isAliyun) {
            def r = AliyunCaller.instance.getVSwitchById(regionName, subnetId)
            if (!r) {
                return null
            }
            return new Subnet()
                    .withSubnetId(r.vSwitchId)
                    .withCidrBlock(r.cidrBlock)
                    .withAvailabilityZone(r.zoneId)
                    .withState(r.status)
        }

        def client = getEc2Client(regionName)
        def request = new DescribeSubnetsRequest().withSubnetIds(subnetId)
        def result = client.describeSubnets(request)
        def list = result.subnets
        if (!list) {
            return null
        }
        def subnet = list[0]
        subnet
    }

    Subnet createSubnet(String regionName, String vpcId, String cidrBlock, String zone) {
        if (isAliyun) {
            def r = AliyunCaller.instance.createVSwitch(regionName, vpcId, cidrBlock, zone)
            return new Subnet()
                    .withSubnetId(r.vSwitchId)
                    .withCidrBlock(cidrBlock)
                    .withAvailabilityZone(zone)
                    .withState('Pending')
        }

        def client = getEc2Client(regionName)
        def request = new CreateSubnetRequest().
                withVpcId(vpcId).
                withCidrBlock(cidrBlock).
                withAvailabilityZone(zone)
        def result = client.createSubnet(request)
        result.subnet
    }

    void deleteSubnet(String regionName, String subnetId) {
        if (isAliyun) {
            AliyunCaller.instance.deleteVSwitch(regionName, subnetId)
            return
        }

        def client = getEc2Client(regionName)
        def request = new DeleteSubnetRequest().
                withSubnetId(subnetId)
        client.deleteSubnet(request)
    }

    // *** ec2
    void deleteKeyPair(String regionName, String keyName) {
        if (isAliyun) {
            AliyunCaller.instance.deleteKeyPair(regionName, keyName)
            return
        }

        def client = getEc2Client(regionName)
        def request = new DeleteKeyPairRequest().
                withKeyName(keyName)
        client.deleteKeyPair(request)
    }

    KeyPair createKeyPair(String regionName, String keyName) {
        if (isAliyun) {
            def r = AliyunCaller.instance.createKeyPair(regionName, keyName)
            return new KeyPair()
                    .withKeyPairId(r.keyPairId)
                    .withKeyName(r.keyPairName)
                    .withKeyFingerprint(r.keyPairFingerPrint)
                    .withKeyMaterial(r.privateKeyBody)
        }

        def client = getEc2Client(regionName)
        def request = new CreateKeyPairRequest().
                withKeyName(keyName).
                withKeyType('rsa').
                withKeyFormat('pem')
        def result = client.createKeyPair(request)
        // need save pem file
        result.keyPair
    }

    KeyPairInfo getKeyPair(String regionName, String keyName) {
        if (isAliyun) {
            def r = AliyunCaller.instance.getKeyPair(regionName, keyName)
            if (!r) {
                return null
            }
            return new KeyPairInfo()
                    .withKeyName(r.keyPairName)
                    .withKeyFingerprint(r.keyPairFingerPrint)
        }

        def client = getEc2Client(regionName)
        def request = new DescribeKeyPairsRequest().
                withKeyNames(keyName)
        def result = client.describeKeyPairs(request)
        def list = result.keyPairs
        if (!list) {
            return null
        }
        list[0]
    }

    Instance runEc2Instance(String regionName, RunInstancesRequest request) {
        def client = getEc2Client(regionName)
        def result = client.runInstances(request)
        result.reservation.instances[0]
    }

    Instance getEc2InstanceById(String regionName, String instanceId) {
        def client = getEc2Client(regionName)
        def request = new DescribeInstancesRequest().
                withInstanceIds(instanceId)
        def result = client.describeInstances(request)
        def list = result.reservations
        if (!list) {
            return null
        }
        def instances = list[0].instances
        if (!instances) {
            return null
        }
        instances[0]
    }

    Instance getEc2Instance(String regionName, String name) {
        def client = getEc2Client(regionName)
        def request = new DescribeInstancesRequest().
                withFilters(new Filter('tag:name').withValues(name))
        def result = client.describeInstances(request)
        def list = result.reservations
        if (!list) {
            return null
        }
        def instances = list[0].instances
        if (!instances) {
            return null
        }
        instances[0]
    }

    InstanceStateChange stopEc2Instance(String regionName, String instanceId) {
        def client = getEc2Client(regionName)
        def request = new StopInstancesRequest().withInstanceIds(instanceId)
        def result = client.stopInstances(request)
        result.stoppingInstances[0]
    }

    List<InstanceStateChange> terminateEc2Instance(String regionName, String instanceId) {
        def client = getEc2Client(regionName)
        def request = new TerminateInstancesRequest().withInstanceIds(instanceId)
        def result = client.terminateInstances(request)
        result.terminatingInstances
    }

    InstanceStatus getEc2InstanceStatus(String regionName, String instanceId) {
        def client = getEc2Client(regionName)
        def request = new DescribeInstanceStatusRequest().
                withInstanceIds(instanceId).
                withIncludeAllInstances(true)
        def result = client.describeInstanceStatus(request)
        if (!result.instanceStatuses) {
            return null
        }
        result.instanceStatuses[0]
    }

    /*
0 : pending
16 : running
32 : shutting-down
48 : terminated
64 : stopping
80 : stopped
     */

    boolean waitUntilInstanceStateCode(String regionName, String instanceId, int targetStateCode) {
        def itemValueWaitTimes = Conf.instance.getInt('ec2.state.change.wait.times', 12)
        // 2min
        int maxWaitTimes = itemValueWaitTimes ? itemValueWaitTimes as int : 12
        int count = 0
        while (count <= maxWaitTimes) {
            count++
            Thread.currentThread().sleep(10000)

            def stateCode = getEc2InstanceStatus(regionName, instanceId)
            if (targetStateCode == stateCode) {
                return true
            } else {
                log.info 'instance state is not match yet. instance id: {}, current state: {}', instanceId, stateCode
            }
        }
        false
    }

    List<Instance> listInstance(String regionName, String vpcId) {
        if (isAliyun) {
            def r = AliyunCaller.instance.listInstance(regionName, vpcId)
            return r.collect {
                new Instance()
                        .withInstanceId(it.instanceId)
                        .withInstanceType(it.instanceType)
                        .withPrivateIpAddress(it.innerIpAddress.ipAddress[0])
                        .withPublicIpAddress(it.publicIpAddress.ipAddress[0])
                        .withState(new InstanceState().withName(it.status))
            }
        }

        def client = getEc2Client(regionName)
        def request = new DescribeInstancesRequest().
                withFilters(new Filter('vpc-id').withValues(vpcId))
        def result = client.describeInstances(request)
        if (!result.reservations) {
            return []
        }

        List<Instance> list = []
        result.reservations.each {
            it.instances.each {
                list.add(it)
            }
        }
        list
    }
}
