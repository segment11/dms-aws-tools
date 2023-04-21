package aws

import com.aliyun.ecs20140526.models.*
import com.aliyun.teaopenapi.models.Config
import com.aliyun.vpc20160428.models.*
import groovy.transform.CompileStatic
import groovy.transform.Memoized
import groovy.util.logging.Slf4j

@CompileStatic
@Slf4j
@Singleton
class AliyunCaller {
    private com.aliyun.vpc20160428.Client vpcClient

    private com.aliyun.ecs20140526.Client ecsClient

    void init(String accessKey, String secretKey) {
        if (!vpcClient) {
            def vpcClientConfig = new Config()
                    .setAccessKeyId(accessKey)
                    .setAccessKeySecret(secretKey)
                    .setEndpoint('vpc.aliyuncs.com')
            vpcClient = new com.aliyun.vpc20160428.Client(vpcClientConfig)
        }

        if (!ecsClient) {
            def ecsClientConfig = new Config()
                    .setAccessKeyId(accessKey)
                    .setAccessKeySecret(secretKey)
                    .setEndpoint('ecs.aliyuncs.com')
            ecsClient = new com.aliyun.ecs20140526.Client(ecsClientConfig)
        }
    }

    @Memoized
    List<com.aliyun.vpc20160428.models.DescribeRegionsResponseBody.DescribeRegionsResponseBodyRegionsRegion> getRegions() {
        def request = new com.aliyun.vpc20160428.models.DescribeRegionsRequest()
        def result = vpcClient.describeRegions(request)
        def body = result.body
        return body.regions.region
    }

    @Memoized
    List<com.aliyun.vpc20160428.models.DescribeZonesResponseBody.DescribeZonesResponseBodyZonesZone> getAvailabilityZoneList(String regionId) {
        def request = new com.aliyun.vpc20160428.models.DescribeZonesRequest()
                .setRegionId(regionId)
        def result = vpcClient.describeZones(request)
        def body = result.body
        return body.zones.zone
    }

    List<DescribeInstanceTypesResponseBody.DescribeInstanceTypesResponseBodyInstanceTypesInstanceType> getInstanceTypeList(String instanceTypePattern, String architecture) {
        def request = new DescribeInstanceTypesRequest().setInstanceTypeFamily(instanceTypePattern).setCpuArchitecture(architecture)
        def result = ecsClient.describeInstanceTypes(request)
        def body = result.body
        return body.instanceTypes.instanceType
    }

    List<DescribeInstanceTypeFamiliesResponseBody.DescribeInstanceTypeFamiliesResponseBodyInstanceTypeFamiliesInstanceTypeFamily> getInstanceTypeFamilyList(String regionId) {
        def request = new DescribeInstanceTypeFamiliesRequest().setRegionId(regionId)
        def result = ecsClient.describeInstanceTypeFamilies(request)
        def body = result.body
        return body.instanceTypeFamilies.instanceTypeFamily
    }

    List<DescribeImagesResponseBody.DescribeImagesResponseBodyImagesImage> getImageListInRegion(String regionId, String name, String architecture) {
        def request = new DescribeImagesRequest().setRegionId(regionId).setImageName(name).setArchitecture(architecture)
        def result = ecsClient.describeImages(request)
        def body = result.body
        return body.images.image
    }

    // max 50
    List<com.aliyun.vpc20160428.models.DescribeVpcsResponseBody.DescribeVpcsResponseBodyVpcsVpc> listVpc(String regionId) {
        def request = new com.aliyun.vpc20160428.models.DescribeVpcsRequest()
                .setRegionId(regionId)
                .setPageSize(50)
                .setPageNumber(1)
        def result = vpcClient.describeVpcs(request)
        def body = result.body
        if (body.totalCount == 0) {
            return []
        }
        return body.vpcs.vpc
    }

    com.aliyun.vpc20160428.models.DescribeVpcsResponseBody.DescribeVpcsResponseBodyVpcsVpc getVpcById(String regionId, String vpcId) {
        def request = new com.aliyun.vpc20160428.models.DescribeVpcsRequest()
                .setRegionId(regionId)
                .setVpcId(vpcId)
        def result = vpcClient.describeVpcs(request)
        def body = result.body
        if (body.totalCount == 0) {
            return null
        }
        body.vpcs.vpc[0]
    }

    com.aliyun.vpc20160428.models.CreateVpcResponseBody createVpc(String regionId, String cidrBlock) {
        def request = new com.aliyun.vpc20160428.models.CreateVpcRequest()
                .setRegionId(regionId)
                .setCidrBlock(cidrBlock)
        def result = vpcClient.createVpc(request)
        result.body
    }

    void deleteVpc(String regionId, String vpcId) {
        def request = new com.aliyun.vpc20160428.models.DeleteVpcRequest()
                .setRegionId(regionId)
                .setVpcId(vpcId)
        vpcClient.deleteVpc(request)
    }

    List<DescribeSecurityGroupsResponseBody.DescribeSecurityGroupsResponseBodySecurityGroupsSecurityGroup> listSecurityGroup(String regionId, String vpcId) {
        def request = new DescribeSecurityGroupsRequest()
                .setRegionId(regionId)
                .setPageSize(50)
                .setPageNumber(1)
                .setVpcId(vpcId)
        def result = ecsClient.describeSecurityGroups(request)
        result.body.securityGroups.securityGroup
    }

    boolean createSgrIngress(String regionId, String groupId, String ipProtocol, String sourceCidrIp, int fromPort, int toPort, boolean isDrop = false) {
        def request = new AuthorizeSecurityGroupRequest()
                .setRegionId(regionId)
                .setSecurityGroupId(groupId)
                .setIpProtocol(ipProtocol)
                .setSourceCidrIp(sourceCidrIp)
                .setPortRange(fromPort + '/' + toPort)
                .setPolicy(isDrop ? 'drop' : 'accept')
        def result = ecsClient.authorizeSecurityGroup(request)
        result.statusCode == 200
    }

    boolean createSgrEgress(String regionId, String groupId, String ipProtocol, String destCidrIp, Integer fromPort, Integer toPort, boolean isDrop = false) {
        def request = new AuthorizeSecurityGroupEgressRequest()
                .setRegionId(regionId)
                .setSecurityGroupId(groupId)
                .setIpProtocol(ipProtocol)
                .setDestCidrIp(destCidrIp)
                .setPolicy(isDrop ? 'drop' : 'accept')
        if (fromPort != null && toPort != null) {
            request.setPortRange(fromPort + '/' + toPort)
        }
        def result = ecsClient.authorizeSecurityGroupEgress(request)
        result.statusCode == 200
    }

    List<DescribeRouteTableListResponseBody.DescribeRouteTableListResponseBodyRouterTableListRouterTableListType> listRouteTable(String regionId, String vpcId) {
        def request = new DescribeRouteTableListRequest()
                .setRegionId(regionId)
                .setPageSize(50)
                .setPageNumber(1)
                .setVpcId(vpcId)
        def result = vpcClient.describeRouteTableList(request)
        result.body.routerTableList.routerTableListType
    }

    Boolean createRoute(String regionId, String routeTableId, String cidrBlock, String nextHopId, String nextHopType) {
        def request = new com.aliyun.vpc20160428.models.CreateRouteEntryRequest()
                .setRegionId(regionId)
                .setRouteTableId(routeTableId)
                .setDestinationCidrBlock(cidrBlock)
                .setNextHopType(nextHopType)
                .setNextHopId(nextHopId)
        def result = vpcClient.createRouteEntry(request)
        result.statusCode == 200
    }

    Boolean deleteRoute(String regionId, String routeTableId, String cidrBlock) {
        def request = new com.aliyun.vpc20160428.models.DeleteRouteEntryRequest()
                .setRegionId(regionId)
                .setRouteTableId(routeTableId)
                .setDestinationCidrBlock(cidrBlock)
        def result = vpcClient.deleteRouteEntry(request)
        result.statusCode == 200
    }

    String createIpv4Gateway(String regionId, String vpcId) {
        def request = new CreateIpv4GatewayRequest()
                .setRegionId(regionId).setVpcId(vpcId)
        def result = vpcClient.createIpv4Gateway(request)
        result.body.ipv4GatewayId
    }

    void deleteIpv4Gateway(String regionId, String ipv4GatewayId) {
        def request = new DeleteIpv4GatewayRequest()
                .setRegionId(regionId).setIpv4GatewayId(ipv4GatewayId)
        vpcClient.deleteIpv4Gateway(request)
    }

    void associateRouteTableWithGateway(String regionId, String routeTableId, String ipv4GatewayId) {
        def request = new AssociateRouteTableWithGatewayRequest().
                setRegionId(regionId).
                setRouteTableId(routeTableId).
                setGatewayId(ipv4GatewayId)
        vpcClient.associateRouteTableWithGateway(request)
    }

    List<com.aliyun.vpc20160428.models.DescribeVSwitchesResponseBody.DescribeVSwitchesResponseBodyVSwitchesVSwitch> listVSwitch(String regionId, String vpcId) {
        def request = new com.aliyun.vpc20160428.models.DescribeVSwitchesRequest()
                .setRegionId(regionId)
                .setVpcId(vpcId)
                .setPageSize(50)
                .setPageNumber(1)
        def result = vpcClient.describeVSwitches(request)
        def body = result.body
        if (body.totalCount == 0) {
            return []
        }
        body.vSwitches.vSwitch
    }

    com.aliyun.vpc20160428.models.DescribeVSwitchesResponseBody.DescribeVSwitchesResponseBodyVSwitchesVSwitch getVSwitchById(String regionId, String vSwitchId) {
        def request = new com.aliyun.vpc20160428.models.DescribeVSwitchesRequest()
                .setRegionId(regionId)
                .setVSwitchId(vSwitchId)
        def result = vpcClient.describeVSwitches(request)
        def body = result.body
        if (body.totalCount == 0) {
            return null
        }
        body.vSwitches.vSwitch[0]
    }

    com.aliyun.vpc20160428.models.DescribeVSwitchesResponseBody.DescribeVSwitchesResponseBodyVSwitchesVSwitch getVSwitch(String regionId, String vpcId, String cidrBlock) {
        def request = new com.aliyun.vpc20160428.models.DescribeVSwitchesRequest()
                .setRegionId(regionId)
                .setVpcId(vpcId)
                .setVSwitchName(cidrBlock)
        def result = vpcClient.describeVSwitches(request)
        def body = result.body
        if (body.totalCount == 0) {
            return null
        }
        body.vSwitches.vSwitch[0]
    }

    com.aliyun.vpc20160428.models.CreateVSwitchResponseBody createVSwitch(String regionId, String vpcId, String cidrBlock, String zoneId) {
        def request = new com.aliyun.vpc20160428.models.CreateVSwitchRequest()
                .setRegionId(regionId)
                .setVpcId(vpcId)
                .setCidrBlock(cidrBlock)
                .setZoneId(zoneId)
                .setVSwitchName(cidrBlock)
        def result = vpcClient.createVSwitch(request)
        result.body
    }

    void deleteVSwitch(String regionId, String vSwitchId) {
        def request = new com.aliyun.vpc20160428.models.DeleteVSwitchRequest()
                .setRegionId(regionId)
                .setVSwitchId(vSwitchId)
        vpcClient.deleteVSwitch(request)
    }

    void deleteKeyPair(String regionId, String keyPairName) {
        def request = new com.aliyun.ecs20140526.models.DeleteKeyPairsRequest()
                .setRegionId(regionId)
                .setKeyPairNames(keyPairName)
        ecsClient.deleteKeyPairs(request)
    }

    CreateKeyPairResponseBody createKeyPair(String regionId, String keyPairName) {
        def request = new com.aliyun.ecs20140526.models.CreateKeyPairRequest()
                .setRegionId(regionId)
                .setKeyPairName(keyPairName)
        def result = ecsClient.createKeyPair(request)
        result.body
    }

    DescribeKeyPairsResponseBody.DescribeKeyPairsResponseBodyKeyPairsKeyPair getKeyPair(String regionId, String keyPairName) {
        def request = new com.aliyun.ecs20140526.models.DescribeKeyPairsRequest()
                .setRegionId(regionId)
                .setKeyPairName(keyPairName)
        def result = ecsClient.describeKeyPairs(request)
        def body = result.body
        if (body.totalCount == 0) {
            return null
        }
        body.keyPairs.keyPair[0]
    }

    List<DescribeInstancesResponseBody.DescribeInstancesResponseBodyInstancesInstance> listInstance(String regionId, String vpcId) {
        def request = new DescribeInstancesRequest()
                .setRegionId(regionId)
                .setPageSize(50)
                .setPageNumber(1)
                .setVpcId(vpcId)
        def result = ecsClient.describeInstances(request)
        result.body.instances.instance
    }

    void shutdown() {
        // ignore
    }
}
