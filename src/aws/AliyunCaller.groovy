package aws

import com.aliyun.ecs20140526.models.*
import com.aliyun.teaopenapi.models.Config
import com.aliyun.vpc20160428.models.DescribeRouteTableListRequest
import com.aliyun.vpc20160428.models.DescribeRouteTableListResponseBody
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

    List<DescribeInstanceTypesResponseBody.DescribeInstanceTypesResponseBodyInstanceTypesInstanceType> getInstanceTypeList(String instanceTypePattern) {
        def request = new DescribeInstanceTypesRequest().setInstanceTypeFamily(instanceTypePattern)
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

    List<DescribeImagesResponseBody.DescribeImagesResponseBodyImagesImage> getImageListInRegion(String regionId, String name) {
        def request = new DescribeImagesRequest().setRegionId(regionId).setImageName(name)
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

    List<DescribeRouteTableListResponseBody.DescribeRouteTableListResponseBodyRouterTableListRouterTableListType> listRouteTable(String regionId, String vpcId) {
        def request = new DescribeRouteTableListRequest()
                .setRegionId(regionId)
                .setPageSize(50)
                .setPageNumber(1)
                .setVpcId(vpcId)
        def result = vpcClient.describeRouteTableList(request)
        result.body.routerTableList.routerTableListType
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
