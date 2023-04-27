package cli.runner

import aws.AwsCaller
import aws.AwsResourceManager
import com.segment.common.Conf
import deploy.*
import model.MontJobCheckDTO
import org.slf4j.LoggerFactory
import support.CacheSession

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
    def instanceIdShort = cmd.getOptionValue('instanceId')
    def keyPairName = cmd.getOptionValue('keyPairName')
    def user = cmd.getOptionValue('user')
    def type = cmd.getOptionValue('type')
    if (!instanceIdShort || !keyPairName || !user || !type) {
        log.warn 'instanceId, keyPairName, user and type required'
        return
    }

    def instanceId = CacheSession.instance.instanceList.find { it.instanceId.endsWith(instanceIdShort) }?.instanceId
    if (!instanceId) {
        log.warn 'no instance id match found'
        return
    }

    def instance = AwsCaller.instance.getEc2InstanceById(region, instanceId)
    if (!instance) {
        log.warn 'instance not exists'
        return
    }

    def manager = AwsResourceManager.instance
    def keyPair = manager.getKeyPair(region, keyPairName)
    if (!keyPair) {
        log.warn 'key pair already created, the primary key content is in another H2 local file'
        return
    }

    def publicIpv4 = instance.publicIpAddress
    def privateIpv4 = instance.privateIpAddress

    def info = new RemoteInfo()
    // public ip is limited, so use private ip, run this tool in the same vpc
    info.host = publicIpv4 ?: privateIpv4
    info.port = 22
    info.user = user
    info.isUsePass = false
    info.privateKeySuffix = '.pem'
    info.privateKeyContent = keyPair.keyMaterial

    def support = new InitAgentEnvSupport(info)
    MontJobCheckDTO.doJobOnce('ec2-reset-root-password-' + privateIpv4) {
        def r = support.resetRootPassword()
        if (!r) {
            log.warn 'reset root password fail'
            return false
        } else {
            return true
        }
    }
    info.rootPass = support.initRootPass

    if ('tar' == type) {
        List<OneCmd> cmdList = []
        cmdList << new OneCmd(cmd: 'wget -N https://www.montplex.com/static-dl/docker.tar -O ' + support.dockerTarFile, checker: OneCmd.keyword('saved'))
        cmdList << new OneCmd(cmd: 'wget -N https://www.montplex.com/static-dl/jdk8.tar.gz -O ' + support.jdkTarFile, checker: OneCmd.keyword('saved'))
        cmdList << new OneCmd(cmd: 'wget -N https://www.montplex.com/static-dl/agentV1.tar.gz -O ' + support.agentTarFile, checker: OneCmd.keyword('saved'))

        def deploy = DeploySupport.instance
        def result = deploy.exec(info, cmdList, 3 * 60, true)
        log.info 'wget files result: {}', result

        if (!support.unTar(support.jdkTarFile)) {
            log.warn 'un tar jdk fail'
        }
        if (!support.unTar(support.agentTarFile)) {
            log.warn 'un tar agent fail'
        }

        log.info 'done'
        return
    }

    if ('dmsServer' == type) {
        log.info 'ip private: {}', privateIpv4
        def privateIpPrefix = privateIpv4.split(/\./)[0] + '.'
        def confFileContent = """
dbDataFile=/data/dms/db;FILE_LOCK=socket
agent.javaCmd=../jdk8/zulu8.64.0.19-ca-jdk8.0.345-linux_x64/bin/java -Xms128m -Xmx256m
localIpFilterPre=${privateIpPrefix}
"""
        new File('/tmp/dms.conf.properties').text = confFileContent.toString()

        def deploy = DeploySupport.instance
        deploy.send(info, '/tmp/dms.conf.properties', support.userHomeDir + '/conf.properties')
        log.info 'send dms.conf.properties success'

        // pull dms docker image
        if (!support.pullDockerImageList(['key232323/dms:latest'])) {
            log.warn 'pull dms docker image fail'
            return
        }

        c.put('dms.cluster.host', privateIpv4)

        def startDmsServerCmd = """
docker run -d --name=dms -v ${support.userHomeDir}/conf.properties:/opt/dms/conf.properties -v /opt/log:/opt/log -v /data/dms:/data/dms --net=host key232323/dms
""".toString()
        def commandList = support.cmdAsRoot new OneCmd(cmd: startDmsServerCmd, checker: OneCmd.any())

        def result = deploy.exec(info, commandList, 10, true)
        log.info 'start dms server result: {}', result
        return
    }

    if ('dmsAgent' == type) {
        def clusterId = c.getInt('dms.cluster.id', 1)
        def secret = c.getString('dms.cluster.secret', '1')
        def dmsServerHost = c.getString('dms.cluster.host', privateIpv4)

        def agentConf = new AgentConf()
        agentConf.serverHost = dmsServerHost
        agentConf.clusterId = clusterId
        agentConf.secret = secret
        agentConf.localIpFilterPre = privateIpv4.split(/\./)[0] + '.'

        support.initAgentConf(agentConf)
        support.startAgentCmd()

        return
    }

    if ('uploadToolsJar' == type) {
        def destBaseDir = support.userHomeDir + '/dms-aws-tools'
        def isOk = support.mkdir(destBaseDir)
        if (!isOk) {
            log.warn 'mkdir fail'
            return
        }

        def deploy = DeploySupport.instance
        def confLocalFilePath = Conf.instance.projectPath('/conf.properties')
        def jarLocalFilePath = Conf.instance.projectPath('/build/libs/dms-aws-tools-1.0.jar')

        deploy.send(info, confLocalFilePath, destBaseDir + '/conf.properties')
        deploy.send(info, jarLocalFilePath, destBaseDir + '/dms-aws-tools-1.0.jar')

        log.info 'done'
        return
    }

    if ('copyLocalTar' == type) {
        def deploy = DeploySupport.instance
        deploy.send(info, support.dockerTarFile, support.dockerTarFile)
        deploy.send(info, support.jdkTarFile, support.jdkTarFile)
        deploy.send(info, support.agentTarFile, support.agentTarFile)

        support.unTar(support.jdkTarFile)
        support.unTar(support.agentTarFile)

        log.info 'done'
        return
    }

    if ('docker' == type) {
        if (!support.unTar(support.dockerTarFile)) {
            return false
        }

        if (!support.initDockerClient()) {
            log.warn 'init docker client fail'
            return
        }

        if (!support.initDockerDaemon()) {
            log.warn 'init docker daemon fail'
            return
        }

        if ('root' != info.user) {
            def commandList = support.cmdAsRoot new OneCmd(cmd: '/usr/sbin/usermod -a -G docker ' + info.user, checker: OneCmd.any())
            def deploy = DeploySupport.instance
            def result = deploy.exec(info, commandList, 10, true)
            log.info 'add user to group docker result: {}', result
        }

        log.info 'done'
        return
    }

    if ('dockerImage' == type) {
        List<String> imageList = ['key232323/zookeeper:3.6.4']
        if (!support.pullDockerImageList(imageList)) {
            log.warn 'pull docker image fail'
            return
        }

        return
    }

    log.warn 'type not support: ' + type
    return
}
