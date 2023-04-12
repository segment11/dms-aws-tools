package deploy

import com.segment.common.Conf
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.apache.commons.io.FileUtils

@CompileStatic
@Slf4j
class InitAgentEnvSupport {

    // debian 11 default admin user
    final static String BASE_DIR = '/home/admin'

    final static String DOCKER_RUNTIME_FILE = BASE_DIR + '/docker.tar'

    final static String JDK_FILE = BASE_DIR + '/jdk8.tar.gz'

    final static String AGENT_FILE = BASE_DIR + '/agentV1.tar.gz'

    static String INIT_ROOT_PASS = 'Test1234'

    private LimitQueue<String> steps = new LimitQueue<>(1000)

    private addStep(RemoteInfo info, String step, String content, OneCmd cmd = null) {
        def now = new Date().format('yyyy-MM-dd HH:mm:ss')
        steps << "${info.host} - ${step} - ${now} - ${content} - ${cmd ? cmd.toString() : ''}".toString()
    }

    List<String> getSteps(RemoteInfo info) {
        steps.findAll { it.startsWith(info.host + ' - ') }
    }

    void clearSteps(RemoteInfo info) {
        steps.removeIf { it.startsWith(info.host + ' - ') }
    }

    boolean resetRootPassword(RemoteInfo info) {
        List<OneCmd> commandList = [
                new OneCmd(cmd: 'pwd', checker: OneCmd.keyword(info.user + '@')),
                new OneCmd(cmd: 'sudo passwd root', checker: OneCmd.keyword('New password:')),
                new OneCmd(cmd: INIT_ROOT_PASS, checker: OneCmd.keyword('Retype'), showCmdLog: false),
                new OneCmd(cmd: INIT_ROOT_PASS, checker: OneCmd.any(), showCmdLog: false)
        ]

        def deploy = DeploySupport.instance
        deploy.exec(info, commandList, 10, true)
        commandList.every { it.ok() }
    }

    boolean mount(RemoteInfo info, String devFilePath, String dir) {
        List<OneCmd> commandList = [
                new OneCmd(cmd: 'pwd', checker: OneCmd.keyword(info.user + '@')),
                new OneCmd(cmd: 'su', checker: OneCmd.keyword('Password:')),
                new OneCmd(cmd: info.rootPass, showCmdLog: false,
                        checker: OneCmd.keyword('root@').failKeyword('failure')),
                new OneCmd(cmd: "/usr/sbin/mkfs.ext4 ${devFilePath}".toString(),
                        maxWaitTimes: 300,
                        checker: OneCmd.keyword('superblocks', 'Creating journal', 'Proceed anyway?')),
                new OneCmd(cmd: 'N', checker: OneCmd.any()),
                new OneCmd(cmd: "mkdir -p ${dir}".toString(),
                        checker: OneCmd.any()),
                new OneCmd(cmd: "mount ${devFilePath} ${dir}".toString(),
                        checker: OneCmd.any()),
        ]

        def deploy = DeploySupport.instance
        deploy.exec(info, commandList, 1200, true)
        commandList.every { it.ok() }
    }

    boolean mkdir(RemoteInfo info, String dir) {
        List<OneCmd> commandList = [
                new OneCmd(cmd: 'pwd', checker: OneCmd.keyword(info.user + '@')),
                new OneCmd(cmd: 'su', checker: OneCmd.keyword('Password:')),
                new OneCmd(cmd: info.rootPass, showCmdLog: false,
                        checker: OneCmd.keyword('root@').failKeyword('failure')),
                new OneCmd(cmd: "mkdir -p ${dir}".toString(),
                        checker: OneCmd.any())
        ]

        def deploy = DeploySupport.instance
        deploy.exec(info, commandList, 10, true)
        commandList.every { it.ok() }
    }

    boolean copyFileIfNotExists(RemoteInfo info, String localFilePath,
                                boolean isTarX = true, boolean isCreateDir = false) {
        String destFilePath = localFilePath

        def deploy = DeploySupport.instance
        def one = OneCmd.simple('ls ' + destFilePath)
        deploy.exec(info, one)
        if (!one.ok()) {
            // do not use root to scp
            if (isCreateDir) {
                String mkdirCommand = 'mkdir -p ' + destFilePath.split(/\//)[0..-2].join('/')
                def mkdirCmd = OneCmd.simple(mkdirCommand)
                deploy.exec(info, mkdirCmd)
                addStep(info, 'mkdir', 'for file: ' + destFilePath + ' - ' + mkdirCmd.result)
            }

            deploy.send(info, localFilePath, destFilePath)
            addStep(info, 'copy file', 'dest: ' + destFilePath)
        } else {
            log.info 'skip scp {}', destFilePath
            addStep(info, 'skip copy file', 'dest: ' + destFilePath)
        }

        if (!isTarX) {
            return true
        }

        unTar(info, destFilePath)
    }

    boolean unTar(RemoteInfo info, String destFilePath) {
        String suf
        String tarOpts
        if (destFilePath.endsWith('.tar')) {
            suf = '.tar'
            tarOpts = '-xvf'
        } else {
            suf = '.tar.gz'
            tarOpts = '-zxvf'
        }

        def destDir = destFilePath.replace(suf, '')

        // tar
        def two = OneCmd.simple('ls ' + destDir)

        def deploy = DeploySupport.instance
        deploy.exec(info, two)
        if (!two.ok()) {
            List<OneCmd> commandList = [OneCmd.simple('mkdir -p ' + destDir),
                                        OneCmd.simple("tar ${tarOpts} ${destFilePath} -C ${destDir}".toString())]
            deploy.exec(info, commandList)
            addStep(info, 'tar x file', 'file: ' + destFilePath)
            return commandList.every { it.ok() }
        } else {
            log.info 'skip tar {}', destFilePath
            addStep(info, 'skip tar x file', 'file: ' + destFilePath)
            return true
        }
    }

    boolean initDockerDaemon(RemoteInfo info) {
        if (!info.rootPass) {
            throw new DeployException('root password need init - ' + info.host)
        }

        def deploy = DeploySupport.instance

        List<OneCmd> cmdList = [
                new OneCmd(cmd: 'pwd', checker: OneCmd.keyword(info.user + '@')),
                new OneCmd(cmd: 'su', checker: OneCmd.keyword('Password:')),
                new OneCmd(cmd: info.rootPass, showCmdLog: false,
                        checker: OneCmd.keyword('root@').failKeyword('failure')),
                new OneCmd(cmd: 'docker ps',
                        checker: OneCmd.keyword('CONTAINER ID').failKeyword('Cannot connect', 'command not'))
        ]
        deploy.exec(info, cmdList, 20, true)
        if (cmdList.every { it.ok() }) {
            log.info 'skip init docker engine'
            addStep(info, 'skip init docker engine', 'already done')
            return true
        }

        String destDockerDir = DOCKER_RUNTIME_FILE.replace('.tar', '')
        def engineInstallCmd = "apt install -y ${destDockerDir}/docker-ce_20.10.21_3-0_debian-bullseye_amd64.deb".toString()

        List<OneCmd> finalCmdList = [
                new OneCmd(cmd: 'pwd', checker: OneCmd.keyword(info.user + '@')),
                new OneCmd(cmd: 'su', checker: OneCmd.keyword('Password:')),
                new OneCmd(cmd: info.rootPass, showCmdLog: false,
                        checker: OneCmd.keyword('root@').failKeyword('failure')),
                new OneCmd(cmd: engineInstallCmd, maxWaitTimes: 600,
                        checker: OneCmd.keyword('0 newly installed', 'Processing triggers', ':/home/admin')),
                new OneCmd(cmd: 'systemctl enable docker.service', checker: OneCmd.any())
        ]
        deploy.exec(info, finalCmdList, 1200, true)
        addStep(info, 'init docker engine', '', finalCmdList[-1])
        finalCmdList.every { it.ok() }
    }

    boolean initDockerClient(RemoteInfo info) {
        if (!info.rootPass) {
            throw new DeployException('root password need init - ' + info.host)
        }

        def deploy = DeploySupport.instance
        def one = OneCmd.simple('docker -v')
        deploy.exec(info, one)
        if (one.result?.contains('Docker version')) {
            log.info 'skip init docker client'
            addStep(info, 'skip init docker client', 'already done')
            return true
        }

        String destDockerDir = DOCKER_RUNTIME_FILE.replace('.tar', '')
        def containerdInstallCmd = "apt install -y ${destDockerDir}/containerd.io_1.6.10-1_amd64.deb".toString()
        def clientInstallCmd = "apt install -y ${destDockerDir}/docker-ce-cli_20.10.21_3-0_debian-bullseye_amd64.deb".toString()

        List<OneCmd> cmdList = [
                new OneCmd(cmd: 'pwd', checker: OneCmd.keyword(info.user + '@')),
                new OneCmd(cmd: 'su', checker: OneCmd.keyword('Password:')),
                new OneCmd(cmd: info.rootPass, showCmdLog: false,
                        checker: OneCmd.keyword('root@').failKeyword('failure')),
                // 200ms once 600 times -> 120s -> 2m
                new OneCmd(cmd: containerdInstallCmd, maxWaitTimes: 600,
                        checker: OneCmd.keyword('0 newly installed', 'Processing triggers', ':/home/admin')),
                new OneCmd(cmd: clientInstallCmd, maxWaitTimes: 600,
                        checker: OneCmd.keyword('0 newly installed', 'Processing triggers', ':/home/admin'))
        ]
        def isExecOk = deploy.exec(info, cmdList, 1200, true)
        if (!isExecOk) {
            return false
        }
        addStep(info, 'init docker client', '', cmdList[-1])
        cmdList.every { it.ok() }
    }

    boolean pullDockerImageList(RemoteInfo info, List<String> imageList) {
        if (!info.rootPass) {
            throw new DeployException('root password need init - ' + info.host)
        }

        def deploy = DeploySupport.instance
        List<OneCmd> cmdList = [
                new OneCmd(cmd: 'pwd', checker: OneCmd.keyword(info.user + '@')),
                new OneCmd(cmd: 'su', checker: OneCmd.keyword('Password:')),
                new OneCmd(cmd: info.rootPass, showCmdLog: false,
                        checker: OneCmd.keyword('root@').failKeyword('failure'))
        ]
        for (image in imageList) {
            // 200ms once 600 times -> 120s -> 2m
            cmdList << new OneCmd(cmd: 'docker pull ' + image, maxWaitTimes: 600,
                    checker: OneCmd.keyword('Downloaded newer image', 'Image is up to date'))
        }

        def isExecOk = deploy.exec(info, cmdList, 120 * imageList.size(), true)
        if (!isExecOk) {
            return false
        }
        addStep(info, 'pull docker images', imageList.toString())
        cmdList.every { it.ok() }
    }

    boolean copyAndLoadDockerImage(RemoteInfo info, String imageTarGzName) {
        if (!info.rootPass) {
            throw new DeployException('root password need init - ' + info.host)
        }

        String localFilePath = InitAgentEnvSupport.BASE_DIR + '/images/' + imageTarGzName

        boolean isCopyDone = copyFileIfNotExists(info, localFilePath, false, true)
        if (!isCopyDone) {
            return isCopyDone
        }
        boolean isLoadOk = loadDockerImage(info, localFilePath)
        isLoadOk
    }

    boolean loadDockerImage(RemoteInfo info, String localFilePath) {
        if (!info.rootPass) {
            throw new DeployException('root password need init - ' + info.host)
        }

        def deploy = DeploySupport.instance

        String loadCmd = "gunzip -c ${localFilePath}|docker load".toString()

        List<OneCmd> cmdList = [
                new OneCmd(cmd: 'pwd', checker: OneCmd.keyword(info.user + '@')),
                new OneCmd(cmd: 'su', checker: OneCmd.keyword('Password:')),
                new OneCmd(cmd: info.rootPass, showCmdLog: false,
                        checker: OneCmd.keyword('root@').failKeyword('failure')),
                new OneCmd(cmd: loadCmd, maxWaitTimes: 300,
                        checker: OneCmd.keyword('Loaded image'))
        ]
        def isExecOk = deploy.exec(info, cmdList, 600, true)
        if (!isExecOk) {
            return false
        }
        addStep(info, 'load image', '', cmdList[-1])
        cmdList.every { it.ok() }
    }

    void initAgentConf(RemoteInfo info, AgentConf agentConf) {
        String destAgentDir = AGENT_FILE.replace('.tar.gz', '')

        final String tmpLocalDir = '/tmp'
        final String fileName = 'conf.properties'

        def localFilePath = tmpLocalDir + '/' + fileName
        def destFilePath = destAgentDir + '/' + fileName
        def f = new File(localFilePath)
        if (!f.exists()) {
            FileUtils.touch(f)
        }
        f.text = agentConf.generate()

        DeploySupport.instance.send(info, localFilePath, destFilePath)
        addStep(info, 'copy agent config file', 'dest: ' + destFilePath)
    }

    boolean stopAgent(RemoteInfo info) {
        if (!info.rootPass) {
            throw new DeployException('root password need init - ' + info.host)
        }

        String stopCommand = "pgrep -f dms_agent|xargs kill -s 15"
        List<OneCmd> cmdList = [
                new OneCmd(cmd: 'pwd', checker: OneCmd.keyword(info.user + '@')),
                new OneCmd(cmd: 'su', checker: OneCmd.keyword('Password:')),
                new OneCmd(cmd: info.rootPass, showCmdLog: false,
                        checker: OneCmd.keyword('root@').failKeyword('failure')),
                new OneCmd(cmd: stopCommand, checker: OneCmd.any())
        ]
        DeploySupport.instance.exec(info, cmdList, 30, true)
        addStep(info, 'stop agent', '', cmdList[-1])
        cmdList.every { it.ok() }
    }

    boolean startAgentCmd(RemoteInfo info) {
        if (!info.rootPass) {
            throw new DeployException('root password need init - ' + info.host)
        }

        String destAgentDir = AGENT_FILE.replace('.tar.gz', '')

        String javaCmd = Conf.instance.getString('agent.java.cmd',
                '../jdk8/zulu8.66.0.15-ca-jdk8.0.352-linux_x64/bin/java -Xms128m -Xmx256m')
        String startCommand = "nohup ${javaCmd} ".toString() +
                "-Djava.library.path=. -cp . -jar dms_agent-1.0.jar > dmc.log 2>&1 &"
        List<OneCmd> cmdList = [
                new OneCmd(cmd: 'pwd', checker: OneCmd.keyword(info.user + '@')),
                new OneCmd(cmd: 'su', checker: OneCmd.keyword('Password:')),
                new OneCmd(cmd: info.rootPass, showCmdLog: false,
                        checker: OneCmd.keyword('root@').failKeyword('failure')),
                new OneCmd(cmd: 'cd ' + destAgentDir, checker: OneCmd.keyword('agentV1')),
                new OneCmd(cmd: startCommand, checker: OneCmd.any())
        ]
        DeploySupport.instance.exec(info, cmdList, 30, true)
        addStep(info, 'start agent', '', cmdList[-1])
        cmdList.every { it.ok() }
    }

    boolean initOtherNode(RemoteInfo info) {
        if (!copyFileIfNotExists(info, DOCKER_RUNTIME_FILE)) {
            return false
        }
        if (!initDockerClient(info)) {
            return false
        }
        if (!initDockerDaemon(info)) {
            return false
        }

        if (!copyFileIfNotExists(info, JDK_FILE)) {
            return false
        }

        if (!copyFileIfNotExists(info, AGENT_FILE)) {
            return false
        }

        return true
    }
}
