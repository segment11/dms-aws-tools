package cli.runner

import aws.AwsCaller
import aws.AwsResourceManager
import com.amazonaws.services.ec2.model.*
import com.segment.common.Conf
import common.Event
import deploy.DeploySupport
import deploy.OneCmd
import deploy.RemoteInfo
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

    def keyName = 'dms-node-only-one'
    def manager = AwsResourceManager.instance
    def keyPair = manager.getKeyPair(region, vpcId, keyName)

    def groupId = caller.getDefaultGroupId(region, vpcId)

    def isNeedPublicIp = cmd.hasOption('publicIpv4')
    def networkInterface = new InstanceNetworkInterfaceSpecification().
            withAssociatePublicIpAddress(isNeedPublicIp).
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
        def itemValueWaitTimes = Conf.instance.getInt('ec2.launch.wait.times', 12)
        // 2min
        int maxWaitTimes = itemValueWaitTimes ? itemValueWaitTimes as int : 12
        int count = 0
        while (count <= maxWaitTimes) {
            count++
            Thread.currentThread().sleep(10000)

            def stateCode = caller.getEc2InstanceStatus(region, instanceInfo.id)
            if (runningStateCode == stateCode) {
                instanceInfo.stateCode = runningStateCode
                Event.builder().type(Event.Type.ec2).
                        reason('instance running').
                        result(name).
                        build().log(instanceInfo.toString()).add()
                break
            } else {
                log.info 'instance is not running yet. ip: {}', instanceInfo.ipv4
            }
        }
    }

    if (!instanceInfo.running()) {
        throw new AwsResourceCreateException('ec2 wait running state too long time, instance id: ' + instanceInfo.id)
    }

    // need wait so can ssh connect
    def waitSeconds = Conf.instance.getInt('ec2.launch.running.wait.seconds', 15)
    log.info 'wait seconds: {}, then ssh connect', waitSeconds
    Thread.sleep(waitSeconds * 1000)

    // init dms server env
    if (isNeedPublicIp) {
        def ec2Instance = caller.getEc2Instance(region, name)
        def publicIpv4 = ec2Instance.publicIpAddress

        // init env
        def info = new RemoteInfo()
        info.host = publicIpv4
        info.port = 22
        info.user = 'admin'
        info.isUsePass = false
        info.privateKeySuffix = '.pem'
        info.privateKeyContent = keyPair.keyMaterial

        def deploy = DeploySupport.instance

        List<OneCmd> cmdList = []
        cmdList << new OneCmd(cmd: 'wget http://segInfra.cloud/docker.tar -O /home/admin/docker.tar')
        cmdList << new OneCmd(cmd: 'wget http://segInfra.cloud/jdk8.tar.gz -O /home/admin/jdk8.tar.gz')
        cmdList << new OneCmd(cmd: 'wget http://segInfra.cloud/agentV1.tar.gz -O /home/admin/agentV1.tar.gz')
        def result = deploy.exec(info, cmdList, 90)
        log.info 'wget files result: {}', result

        def privateIpPrefix = ec2Instance.privateIpAddress.split('.')[0] + '.'
        def confFileContent = """
dbDataFile=/data/dms/db;FILE_LOCK=socket
agent.javaCmd=../jdk8/zulu8.64.0.19-ca-jdk8.0.345-linux_x64/bin/java -Xms128m -Xmx256m
localIpFilterPre=${privateIpPrefix}
"""
        new File('/tmp/dms.conf.properties').text = confFileContent.toString()
        deploy.send(info, '/tmp/dms.conf.properties', '/home/admin/conf.properties')
    }

    return
}
