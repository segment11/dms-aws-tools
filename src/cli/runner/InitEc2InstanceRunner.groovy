package cli.runner

import aws.AwsCaller
import aws.AwsResourceManager
import com.segment.common.Conf
import deploy.*
import model.MontJobCheckDTO
import org.slf4j.LoggerFactory

import static deploy.InitAgentEnvSupport.*

def h = cli.CommandTaskRunnerHolder.instance
def log = LoggerFactory.getLogger(this.getClass())

h.add('init ec2 instance runner') { cmd ->
    '''
ec2Init
'''.readLines().collect { it.trim() }.findAll { it }.any {
        cmd.hasOption(it)
    }
} { cmd ->
    def c = Conf.instance

    def region = cmd.getOptionValue('region')
    def instanceId = cmd.getOptionValue('instanceId')

    def ec2Instance = AwsCaller.instance.getEc2InstanceById(region, instanceId)
    if (!ec2Instance) {
        log.warn 'instance not exists'
        return
    }

    def keyName = c.getString('default.ec2.key.pair.name', 'dms-node-only-one')
    def manager = AwsResourceManager.instance
    def keyPair = manager.getKeyPair(region, keyName)
    if (!keyPair) {
        log.warn 'key pair already created, the primary key content is in another H2 local file'
        return
    }

    def publicIpv4 = ec2Instance.publicIpAddress
    if (!publicIpv4) {
        log.warn 'instance not has public ip'
        return
    }

    def info = new RemoteInfo()
    info.host = publicIpv4
    info.port = 22
    info.user = 'admin'
    info.isUsePass = false
    info.privateKeySuffix = '.pem'
    info.privateKeyContent = keyPair.keyMaterial

    def support = new InitAgentEnvSupport()
    MontJobCheckDTO.doJobOnce('ec2-reset-root-password-' + publicIpv4) {
        def r = support.resetRootPassword(info)
        if (!r) {
            log.warn 'reset root password fail'
            return false
        } else {
            return true
        }
    }
    info.rootPass = INIT_ROOT_PASS

    def type = cmd.getOptionValue('type')
    if ('tar' == type) {
        List<OneCmd> cmdList = []
        cmdList << new OneCmd(cmd: 'wget -N https://www.montplex.com/static-dl/docker.tar -O ' + DOCKER_RUNTIME_FILE, checker: OneCmd.keyword('saved'))
        cmdList << new OneCmd(cmd: 'wget -N https://www.montplex.com/static-dl/jdk8.tar.gz -O ' + JDK_FILE, checker: OneCmd.keyword('saved'))
        cmdList << new OneCmd(cmd: 'wget -N https://www.montplex.com/static-dl/agentV1.tar.gz -O ' + JDK_FILE, checker: OneCmd.keyword('saved'))

        def deploy = DeploySupport.instance
        def result = deploy.exec(info, cmdList, 3 * 60, true)
        log.info 'wget files result: {}', result

        if (!support.unTar(info, JDK_FILE)) {
            log.warn 'un tar jdk fail'
        }
        if (!support.unTar(info, AGENT_FILE)) {
            log.warn 'un tar agent fail'
        }
        return
    }

    def ipPrivate = ec2Instance.privateIpAddress
    if ('dmsServer' == type) {
        log.info 'ip private: {}', ipPrivate
        def privateIpPrefix = ipPrivate.split(/\./)[0] + '.'
        def confFileContent = """
dbDataFile=/data/dms/db;FILE_LOCK=socket
agent.javaCmd=../jdk8/zulu8.64.0.19-ca-jdk8.0.345-linux_x64/bin/java -Xms128m -Xmx256m
localIpFilterPre=${privateIpPrefix}
"""
        new File('/tmp/dms.conf.properties').text = confFileContent.toString()

        def deploy = DeploySupport.instance
        deploy.send(info, '/tmp/dms.conf.properties', '/home/admin/conf.properties')
        log.info 'send dms.conf.properties success'

        // pull dms docker image
        if (!support.pullDockerImageList(info, ['key232323/dms:latest'])) {
            log.warn 'pull dms docker image fail'
            return
        }

        c.put('dms.cluster.host', ipPrivate)

        List<OneCmd> cmdList = [
                new OneCmd(cmd: 'pwd', checker: OneCmd.keyword(info.user + '@')),
                new OneCmd(cmd: 'su', checker: OneCmd.keyword('Password:')),
                new OneCmd(cmd: info.rootPass, showCmdLog: false,
                        checker: OneCmd.keyword('root@').failKeyword('failure'))
        ]
        def startDmsServerCmd = """
docker run -d --name=dms --cpus="1" -v /home/admin/conf.properties:/opt/dms/conf.properties -v /opt/log:/opt/log -v /data/dms:/data/dms --net=host key232323/dms
"""
        cmdList << new OneCmd(cmd: startDmsServerCmd, checker: OneCmd.any())

        def result = deploy.exec(info, cmdList, 10, true)
        log.info 'start dms server result: {}', result
        return
    }

    if ('dmsAgent' == type) {
        def clusterId = c.getInt('dms.cluster.id', 1)
        def secret = c.getString('dms.cluster.secret', '1')
        def dmsServerHost = c.get('dms.cluster.host')

        def agentConf = new AgentConf()
        agentConf.serverHost = dmsServerHost
        agentConf.clusterId = clusterId
        agentConf.secret = secret
        agentConf.localIpFilterPre = ipPrivate.split(/\./)[0] + '.'

        support.initAgentConf(info, agentConf)
        support.startAgentCmd(info)

        support.getSteps(info).each { log.info it }
        support.clearSteps(info)
        return
    }

    if ('docker' == type) {
        if (!support.unTar(info, DOCKER_RUNTIME_FILE)) {
            return false
        }

        support.getSteps(info).each { log.info it }
        support.clearSteps(info)

        if (!support.initDockerClient(info)) {
            log.warn 'init docker client fail'
            return
        }

        support.getSteps(info).each { log.info it }
        support.clearSteps(info)

        if (!support.initDockerDaemon(info)) {
            log.warn 'init docker daemon fail'
            return
        }

        support.getSteps(info).each { log.info it }
        support.clearSteps(info)

        return
    }

    if ('dockerImage' == type) {
        List<String> imageList = ['key232323/zookeeper:3.6.4']
        if (!support.pullDockerImageList(info, imageList)) {
            log.warn 'pull docker image fail'
            return
        }

        return
    }

    log.warn 'type not support: ' + type
    return
}
