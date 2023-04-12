package cli.runner

import aws.AwsResourceManager
import com.segment.common.Conf
import deploy.AgentConf
import deploy.InitAgentEnvSupport
import deploy.RemoteInfo
import model.MontJobCheckDTO
import org.slf4j.LoggerFactory

def h = cli.CommandTaskRunnerHolder.instance
def log = LoggerFactory.getLogger(this.getClass())

h.add('join dms runner') { cmd ->
    '''
join
'''.readLines().collect { it.trim() }.findAll { it }.any {
        cmd.hasOption(it)
    }
} { cmd ->
    def region = cmd.getOptionValue('region')
    def vpcId = cmd.getOptionValue('vpcId')
    def ip = cmd.getOptionValue('join')

    if (!vpcId || !ip) {
        log.warn 'vpcId and join required'
        return
    }

    // init env
    def info = new RemoteInfo()
    info.host = ip
    info.port = 22
    info.user = 'admin'
    info.isUsePass = false
    info.privateKeySuffix = '.pem'

    def keyName = 'dms-node-only-one'
    def manager = AwsResourceManager.instance
    def keyPair = manager.getKeyPair(region, vpcId, keyName)
    info.privateKeyContent = keyPair.keyMaterial

    def support = new InitAgentEnvSupport()

    MontJobCheckDTO.doJobOnce('ec2-reset-root-password-' + ip) {
        def r = support.resetRootPassword(info)
        if (!r) {
            log.warn 'reset root password fail'
            return
        }
    }
    info.rootPass = InitAgentEnvSupport.INIT_ROOT_PASS
    support.initOtherNode(info)

    support.getSteps(info).each { log.info it }
    support.clearSteps(info)

    // deploy agent conf and start agent
    def c = Conf.instance
    def clusterId = c.getInt('dms.cluster.id', 1)
    def secret = c.getString('dms.cluster.secret', '1')
    def dmsServerHost = c.get('dms.cluster.host')

    def agentConf = new AgentConf()
    agentConf.serverHost = dmsServerHost
    agentConf.clusterId = clusterId
    agentConf.secret = secret
    agentConf.localIpFilterPre = ip.split(/\./)[0] + '.'

    support.initAgentConf(info, agentConf)
    support.startAgentCmd(info)

    support.getSteps(info).each { log.info it }
    support.clearSteps(info)

    return
}
