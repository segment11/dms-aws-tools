package cli.runner

import aws.AwsResourceManager
import com.segment.common.Conf
import org.slf4j.LoggerFactory

def h = cli.CommandTaskRunnerHolder.instance
def log = LoggerFactory.getLogger(this.getClass())

h.add('create runner') { cmd ->
    '''
create
'''.readLines().collect { it.trim() }.findAll { it }.any {
        cmd.hasOption(it)
    }
} { cmd ->
    def type = cmd.getOptionValue('type')

    if ('vpc' == type) {
        def region = cmd.getOptionValue('region')
        def cidrBlock = cmd.getOptionValue('cidrBlock')
        if (!cidrBlock) {
            log.warn 'cidrBlock required'
            return
        }

        int proxyPort = Conf.instance.getInt('proxy.port', 8125)

        def manager = AwsResourceManager.instance
        def vpcInfo = manager.createVpcIfNotExists(region, cidrBlock)
        manager.initSecurityGroupRules(vpcInfo, proxyPort)

        def igwId = manager.addInternetGateway(vpcInfo)
        manager.addIgwRoute(region, vpcInfo.id, vpcInfo.routeTableId, igwId)

        return
    }

    if ('subnet' == type) {
        def region = cmd.getOptionValue('region')
        def vpcId = cmd.getOptionValue('vpcId')
        def cidrBlock = cmd.getOptionValue('cidrBlock')
        def az = cmd.getOptionValue('az')
        if (!vpcId || !cidrBlock || !az) {
            log.warn 'vpcId, cidrBlock and az required'
            return
        }

        def manager = AwsResourceManager.instance
        manager.createSubnet(region, vpcId, cidrBlock, az)

        return
    }

    log.warn 'type not support: ' + type

    return
}
