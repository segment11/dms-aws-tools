package cli.runner

import aws.AwsResourceManager
import org.slf4j.LoggerFactory

def h = cli.CommandTaskRunnerHolder.instance
def log = LoggerFactory.getLogger(this.getClass())

h.add('create subnet runner') { cmd ->
    '''
createSubnet
'''.readLines().collect { it.trim() }.findAll { it }.any {
        cmd.hasOption(it)
    }
} { cmd ->
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
