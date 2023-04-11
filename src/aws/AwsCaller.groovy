package aws

import com.amazonaws.auth.AWSCredentials
import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.Regions
import com.amazonaws.services.ec2.AmazonEC2
import com.amazonaws.services.ec2.AmazonEC2AsyncClient
import com.amazonaws.services.ec2.model.*
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
        void refresh() {
        }
    }

    synchronized AmazonEC2 getEc2Client(String regionName) {
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
    }

    static List<AwsRegion> getRegions() {
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
    List<InstanceTypeInfo> getInstanceTypeListInRegion(String regionName) {
        def client = getEc2Client(regionName)
        def request = new DescribeInstanceTypesRequest()
        def result = client.describeInstanceTypes(request)
        def r = result.instanceTypes
        r.sort { it.instanceType }
    }

    @Memoized
    List<Image> getImageListInRegion(String regionName) {
        def client = getEc2Client(regionName)
        def request = new DescribeImagesRequest()
        def result = client.describeImages(request)
        def r = result.images
        r.sort { it.name }
    }

    private Map<String, List<AvailabilityZone>> zonesCached = [:]

    List<AvailabilityZone> getAvailabilityZoneList(String regionName, boolean isCache) {
        if (isCache) {
            def cached = zonesCached[regionName]
            if (cached) {
                return cached
            }
        }

        def client = getEc2Client(regionName)
        def request = new DescribeAvailabilityZonesRequest().withAllAvailabilityZones(true)
        def result = client.describeAvailabilityZones(request)
        def list = result.availabilityZones
        zonesCached[regionName] = list
        list
    }

    List<Vpc> listVpc(String regionName) {
        def client = getEc2Client(regionName)
        def result = client.describeVpcs()
        result.vpcs
    }

    String getDefaultSg(String regionName, String vpcId) {
        def client = getEc2Client(regionName)
        def request = new DescribeSecurityGroupsRequest().
                withFilters(new Filter('vpc-id').withValues(vpcId))
        def result = client.describeSecurityGroups(request)
        result.securityGroups[0].groupId
    }

    List<Subnet> listSubnet(String regionName, String vpcId) {
        def client = getEc2Client(regionName)
        def request = new DescribeSubnetsRequest().
                withFilters(new Filter('vpc-id').withValues(vpcId))
        def result = client.describeSubnets(request)
        result.subnets
    }

    Subnet getSubnet(String regionName, String vpcId, String cidrBlock, String zone) {
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
            throw new IllegalStateException('subnet with the cidr block already exists: ' +
                    cidrBlock + ' but zone not match: ' + zone)
        }
        subnet
    }

    Subnet getSubnetById(String regionName, String subnetId){
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
        def client = getEc2Client(regionName)
        def request = new CreateSubnetRequest().
                withVpcId(vpcId).
                withCidrBlock(cidrBlock).
                withAvailabilityZone(zone)
        def result = client.createSubnet(request)
        result.subnet
    }

    void deleteSubnet(String regionName, String subnetId) {
        def client = getEc2Client(regionName)
        def request = new DeleteSubnetRequest().
                withSubnetId(subnetId)
        client.deleteSubnet(request)
    }

    // *** ec2
    void deleteKeyPair(String regionName, String keyName) {
        def client = getEc2Client(regionName)
        def request = new DeleteKeyPairRequest().
                withKeyName(keyName)
        client.deleteKeyPair(request)
    }

    KeyPair createKeyPair(String regionName, String keyName) {
        def client = getEc2Client(regionName)
        def request = new CreateKeyPairRequest().
                withKeyName(keyName).
                withKeyType('rsa').
                withKeyFormat('pem')
        def result = client.createKeyPair(request)
        // need save pem file
        result.keyPair
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

    Instance runEc2Instance(String regionName, RunInstancesRequest request, boolean isWaitUntilRunning = false) {
        def client = getEc2Client(regionName)
        def result = client.runInstances(request)
//        def waiters = client.waiters()
//        waiters.instanceRunning()
        result.reservation.instances[0]
    }

    List<InstanceStateChange> stopEc2Instance(String regionName, String instanceId) {
        def client = getEc2Client(regionName)
        def request = new StopInstancesRequest().withInstanceIds(instanceId)
        def result = client.stopInstances(request)
        result.stoppingInstances
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
}
