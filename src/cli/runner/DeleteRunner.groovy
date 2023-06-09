package cli.runner

import aws.AwsCaller
import model.MontAwsResourceDTO
import org.slf4j.LoggerFactory
import support.CacheSession

def h = cli.CommandTaskRunnerHolder.instance
def log = LoggerFactory.getLogger(this.getClass())

h.add('delete runner') { cmd ->
    '''
stop
delete
'''.readLines().collect { it.trim() }.findAll { it }.any {
        cmd.hasOption(it)
    }
} { cmd ->
    def type = cmd.getOptionValue('type')
    def caller = AwsCaller.instance

    if ('vpc' == type) {
        def region = cmd.getOptionValue('region')
        def vpcIdShort = cmd.getOptionValue('vpcId')
        if (!vpcIdShort) {
            log.warn 'no vpc id given'
            return
        }

        def vpcId = CacheSession.instance.vpcList.find { it.vpcId.endsWith(vpcIdShort) }?.vpcId
        if (!vpcId) {
            log.warn 'no vpc id match found'
            return
        }

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
        if (caller.isAws) {
            def igw = caller.getInternetGatewayByVpcId(region, vpcId)
            if (igw) {
                caller.detachInternetGateway(region, vpcId, igw.internetGatewayId)
                log.warn 'igw detached'
                caller.deleteInternetGateway(region, igw.internetGatewayId)
                log.warn 'igw deleted'
                new MontAwsResourceDTO(arn: igw.internetGatewayId).delete()
            }
        } else if (caller.isAliyun) {

        }

        caller.deleteVpc(region, vpcId)
        log.warn 'vpc deleted'
        new MontAwsResourceDTO(arn: vpcId).delete()

        return
    }

    if ('subnet' == type) {
        def region = cmd.getOptionValue('region')
        def subnetIdShort = cmd.getOptionValue('subnetId')
        if (!subnetIdShort) {
            log.warn 'no subnet id given'
            return
        }

        def subnetId = CacheSession.instance.subnetList.find { it.subnetId.endsWith(subnetIdShort) }?.subnetId
        if (!subnetId) {
            log.warn 'no subnet id match found'
            return
        }

        def subnet = caller.getSubnetById(region, subnetId)
        if (!subnet) {
            log.warn 'no subnet found'
            return
        }

        // check if has any ec2 instance
        def ec2InstanceList = caller.listInstance(region, subnet.vpcId)
        if (ec2InstanceList.any {
            it.subnetId == subnetId && it.state.code != 48
        }) {
            log.warn 'subnet has ec2 instance, please delete them first'
            return
        }

        caller.deleteSubnet(region, subnetId)
        log.warn 'subnet deleted'
        new MontAwsResourceDTO(arn: subnetId).delete()
        return
    }

    if ('instance' == type) {
        def region = cmd.getOptionValue('region')
        def instanceIdShort = cmd.getOptionValue('instanceId')
        if (!instanceIdShort) {
            log.warn 'no instance id given'
            return
        }

        def instanceId = CacheSession.instance.instanceList.find { it.instanceId.endsWith(instanceIdShort) }?.instanceId
        if (!instanceId) {
            log.warn 'no instance id match found'
            return
        }

        def instance = caller.getEc2InstanceById(region, instanceId)
        if (!instance) {
            log.warn 'no instance found'
            return
        }

        // check state
        def code = instance.state.code
        def ip = instance.privateIpAddress

        if (cmd.hasOption('stop')) {
            if (code == 80) {
                log.warn 'instance is already stopped'
                return
            } else if (code == 48) {
                log.warn 'instance is already terminated'
                return
            }
        }

        def isOperateDelete = cmd.hasOption('delete')
        if (16 == code) {
            if (isOperateDelete) {
                log.warn 'instance is running, please stop it first'
            }
            def result = caller.stopEc2Instance(region, instanceId)
            log.info 'stop result: {}', result
            def isWaitOk = caller.waitUntilInstanceStateCode(region, instanceId, 80)
            if (!isWaitOk) {
                log.info 'instance is not stopped yet. ip: {}', ip
                return
            }

            if (!isOperateDelete) {
                return
            }
        }

        if (isOperateDelete) {
            if (code == 80) {
                // aliyun just delete
                def result2 = caller.terminateEc2Instance(region, instanceId)
                log.info 'terminate result: {}', result2

                // aws terminate instance is async, wait until terminated
                if (caller.isAws) {
                    def isWaitOk2 = caller.waitUntilInstanceStateCode(region, instanceId, 48)
                    if (!isWaitOk2) {
                        log.info 'instance is not terminated yet. ip: {}', ip
                        return
                    }
                }

                log.info 'instance is terminated. ip: {}', ip
                new MontAwsResourceDTO(arn: instanceId).delete()
                return
            } else {
                log.warn 'instance is not stopped yet. ip: {}', ip
                return
            }
        }

        log.warn 'why come here?'
        return
    }

    log.warn 'type not support: ' + type
    return
}
