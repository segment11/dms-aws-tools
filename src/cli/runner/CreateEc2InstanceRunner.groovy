package cli.runner

import aws.AwsCaller
import aws.AwsResourceManager
import com.amazonaws.services.ec2.model.*
import com.segment.common.Conf
import common.Event
import ex.AwsResourceCreateException
import org.slf4j.LoggerFactory

def h = cli.CommandTaskRunnerHolder.instance
def log = LoggerFactory.getLogger(this.getClass())

h.add('create ec2 instance runner') { cmd ->
    '''
ec2
'''.readLines().collect { it.trim() }.findAll { it }.any {
        cmd.hasOption(it)
    }
} { cmd ->
    def region = cmd.getOptionValue('region')
    def vpcId = cmd.getOptionValue('vpcId')
    def subnetId = cmd.getOptionValue('subnetId')
    if (!vpcId || !subnetId) {
        log.warn 'vpcId and subnetId required'
        return
    }

    def imageId = cmd.getOptionValue('imageId')
    def instanceType = cmd.getOptionValue('instanceType')
    if (!imageId || !instanceType) {
        log.warn 'imageId and instanceType required'
        return
    }

    def name = cmd.getOptionValue('ec2')
    def caller = AwsCaller.instance
    def instance = caller.getEc2Instance(region, name)
    if (instance) {
        log.warn 'instance already exists'
        return
    }

    def subnet = caller.getSubnetById(region, subnetId)
    def az = subnet.availabilityZone

    def keyName = Conf.instance.getString('default.ec2.key.pair.name', 'dms-node-only-one')
    def manager = AwsResourceManager.instance
    manager.getKeyPair(region, keyName)

    def groupId = caller.getDefaultGroupId(region, vpcId)

    def networkInterface = new InstanceNetworkInterfaceSpecification().
            withAssociatePublicIpAddress(true).
            withDeleteOnTermination(true).
            withGroups(groupId).
            withSubnetId(subnetId).
            withDeviceIndex(0)

    def tagSpec = new TagSpecification().
            withResourceType(ResourceType.Instance).
            withTags(new Tag('name', name))

    def request = new RunInstancesRequest().
            withKeyName(keyName).
            withImageId(imageId).
            withMinCount(1).
            withMaxCount(1).
            withInstanceType(instanceType).
            withNetworkInterfaces(networkInterface).
            withTagSpecifications(tagSpec)

    def instanceInfo = manager.runEc2Instance(region, az, vpcId, subnetId, request, name)

    final int runningStateCode = 16
    if (!instanceInfo.running()) {
        def isWaitOk = caller.waitUntilInstanceStateCode(region, instanceInfo.id, runningStateCode)
        if (isWaitOk) {
            instanceInfo.stateCode = runningStateCode
            Event.builder().type(Event.Type.ec2).
                    reason('instance running').
                    result(name).
                    build().log(instanceInfo.toString()).add()
        } else {
            log.info 'instance is not running yet. ip: {}', instanceInfo.ipv4
        }
    }

    if (!instanceInfo.running()) {
        throw new AwsResourceCreateException('ec2 wait running state too long time, instance id: ' + instanceInfo.id)
    }

    // need wait so can ssh connect
    def waitSeconds = Conf.instance.getInt('ec2.launch.running.wait.seconds', 15)
    log.info 'wait seconds: {}, then ssh connect', waitSeconds
    Thread.sleep(waitSeconds * 1000)

    return
}
