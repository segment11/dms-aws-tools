package cli.runner

import aws.AwsCaller
import org.slf4j.LoggerFactory

def h = cli.CommandTaskRunnerHolder.instance
def log = LoggerFactory.getLogger(this.getClass())

h.add('delete runner') { cmd ->
    '''
delete
'''.readLines().collect { it.trim() }.findAll { it }.any {
        cmd.hasOption(it)
    }
} { cmd ->
    def type = cmd.getOptionValue('type')
    def caller = AwsCaller.instance

    if ('vpc' == type) {
        def region = cmd.getOptionValue('region')
        def vpcId = cmd.getOptionValue('vpcId')
        def vpc = caller.getVpcById(region, vpcId)
        if (!vpc) {
            log.warn 'no vpc found'
            return
        }

        // check if has any subnet
        def subnetList = caller.listSubnet(region, vpcId)
        if (subnetList) {
            log.warn 'vpc has subnet, please delete them first'
            return
        }

        // delete igw
        def igw = caller.getInternetGatewayByVpcId(region, vpcId)
        if (igw) {
            caller.detachInternetGateway(region, vpcId, igw.internetGatewayId)
            log.warn 'igw detached'
            caller.deleteInternetGateway(region, igw.internetGatewayId)
            log.warn 'igw deleted'
        }

        caller.deleteVpc(region, vpcId)
        log.warn 'vpc deleted'
        return
    }

    if ('subnet' == type) {
        def region = cmd.getOptionValue('region')
        def subnetId = cmd.getOptionValue('subnetId')
        if (!subnetId) {
            log.warn 'no subnet id given'
            return
        }

        def subnet = caller.getSubnetById(region, subnetId)
        if (!subnet) {
            log.warn 'no subnet found'
            return
        }

        // check if has any ec2 instance
        def ec2List = caller.listInstance(region, subnet.vpcId)
        if (ec2List.any {
            it.subnetId == subnetId && it.state.code != 48
        }) {
            log.warn 'subnet has ec2 instance, please delete them first'
            return
        }

        caller.deleteSubnet(region, subnetId)
        log.warn 'subnet deleted'
        return
    }

    if ('instance' == type) {
        def region = cmd.getOptionValue('region')
        def instanceId = cmd.getOptionValue('instanceId')
        if (!instanceId) {
            log.warn 'no instance id given'
            return
        }

        def ec2Instance = caller.getEc2InstanceById(region, instanceId)
        if (!ec2Instance) {
            log.warn 'no instance found'
            return
        }

        // check state
        def state = ec2Instance.state.name
        def ip = ec2Instance.privateIpAddress
        if ('running' == state) {
            log.warn 'instance is running, please stop it first'
            def result = caller.stopEc2Instance(region, instanceId)
            log.info 'stop result: {}', result
            def isWaitOk = caller.waitUntilInstanceStateCode(region, instanceId, 80)
            if (!isWaitOk) {
                log.info 'instance is not stopped yet. ip: {}', ip
                return
            }

            def result2 = caller.terminateEc2Instance(region, instanceId)
            log.info 'terminate result: {}', result2
            def isWaitOk2 = caller.waitUntilInstanceStateCode(region, instanceId, 48)
            if (!isWaitOk2) {
                log.info 'instance is not terminated yet. ip: {}', ip
                return
            }

            log.info 'instance is terminated. ip: {}', ip
            return
        }

        return
    }

    log.warn 'type not support: ' + type
    return
}