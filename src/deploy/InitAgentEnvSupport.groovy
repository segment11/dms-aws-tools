package deploy

import com.segment.common.Conf
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.apache.commons.io.FileUtils

@CompileStatic
@Slf4j
class InitAgentEnvSupport {
    private RemoteInfo info

    InitAgentEnvSupport(RemoteInfo info) {
        this.info = info
        this.userHomeDir = info.user == 'root' ? '/root' : '/home/' + info.user
        this.dockerTarFile = userHomeDir + '/docker.tar'
        this.jdkTarFile = userHomeDir + '/jdk8.tar.gz'
        this.agentTarFile = userHomeDir + '/agentV1.tar.gz'
    }

    String userHomeDir

    String dockerTarFile

    String jdkTarFile

    String agentTarFile

    String initRootPass = 'Test1234'

    boolean resetRootPassword() {
        List<OneCmd> commandList = [
                new OneCmd(cmd: 'pwd', checker: OneCmd.keyword(info.user + '@')),
                new OneCmd(cmd: 'sudo passwd root', checker: OneCmd.keyword('New password:')),
                new OneCmd(cmd: initRootPass, checker: OneCmd.keyword('Retype'), showCmdLog: false),
                new OneCmd(cmd: initRootPass, checker: OneCmd.any(), showCmdLog: false)
        ]

        def deploy = DeploySupport.instance
        deploy.exec(info, commandList, 10, true)
        commandList.every { it.ok() }
    }

    List<OneCmd> cmdAsRoot(OneCmd... extList) {
        def list = [new OneCmd(cmd: 'pwd', checker: OneCmd.keyword(info.user + '@'))]

        if ('root' != info.user) {
            list << new OneCmd(cmd: 'su', checker: OneCmd.keyword('Password:'))
            list << new OneCmd(cmd: info.rootPass, showCmdLog: false,
                    checker: OneCmd.keyword('root@').failKeyword('failure'))
        }
        for (one in extList) {
            list << one
        }
        list
    }

    boolean mount(String devFilePath, String dir) {
        List<OneCmd> commandList = cmdAsRoot new OneCmd(cmd: "/usr/sbin/mkfs.ext4 ${devFilePath}".toString(),
                maxWaitTimes: 300,
                checker: OneCmd.keyword('superblocks', 'Creating journal', 'Proceed anyway?')),
                new OneCmd(cmd: 'N', checker: OneCmd.any()),
                new OneCmd(cmd: "mkdir -p ${dir}".toString(),
                        checker: OneCmd.any()),
                new OneCmd(cmd: "mount ${devFilePath} ${dir}".toString(),
                        checker: OneCmd.any())

        def deploy = DeploySupport.instance
        deploy.exec(info, commandList, 1200, true)
        commandList.every { it.ok() }
    }

    boolean mkdir(String dir) {
        List<OneCmd> commandList = cmdAsRoot new OneCmd(cmd: "mkdir -p ${dir}".toString(), checker: OneCmd.any())

        def deploy = DeploySupport.instance
        deploy.exec(info, commandList, 10, true)
        commandList.every { it.ok() }
    }

    boolean copyFileIfNotExists(String localFilePath,
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
            }

            deploy.send(info, localFilePath, destFilePath)
        } else {
            log.info 'skip scp {}', destFilePath
        }

        if (!isTarX) {
            return true
        }

        unTar(destFilePath)
    }

    boolean unTar(String destFilePath) {
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
            return commandList.every { it.ok() }
        } else {
            log.info 'skip tar {}', destFilePath
            return true
        }
    }

    boolean initDockerDaemon() {
        if (!info.rootPass) {
            throw new DeployException('root password need init - ' + info.host)
        }

        def deploy = DeploySupport.instance

        List<OneCmd> commandList = cmdAsRoot new OneCmd(cmd: 'docker ps',
                checker: OneCmd.keyword('CONTAINER ID').failKeyword('Cannot connect', 'command not'))

        deploy.exec(info, commandList, 20, true)
        if (commandList.every { it.ok() }) {
            log.info 'skip init docker engine'
            return true
        }

        String destDockerDir = dockerTarFile.replace('.tar', '')
        def engineInstallCmd = "apt install -y ${destDockerDir}/docker-ce_20.10.21_3-0_debian-bullseye_amd64.deb".toString()

        List<OneCmd> finalCommandList = cmdAsRoot new OneCmd(cmd: engineInstallCmd, maxWaitTimes: 300,
                checker: OneCmd.keyword(' 0 newly installed', 'Processing triggers', ':' + userHomeDir)
                        .failKeyword('Permission', 'broken packages')),
                new OneCmd(cmd: 'systemctl enable docker.service', checker: OneCmd.any())

        deploy.exec(info, finalCommandList, 300, true)
        finalCommandList.every { it.ok() }
    }

    boolean initDockerClient() {
        if (!info.rootPass) {
            throw new DeployException('root password need init - ' + info.host)
        }

        def deploy = DeploySupport.instance
        def one = OneCmd.simple('docker -v')
        deploy.exec(info, one)
        if (one.result?.contains('Docker version')) {
            log.info 'skip init docker client'
            return true
        }

        String destDockerDir = dockerTarFile.replace('.tar', '')
        def containerdInstallCmd = "apt install -y ${destDockerDir}/containerd.io_1.6.10-1_amd64.deb".toString()
        def clientInstallCmd = "apt install -y ${destDockerDir}/docker-ce-cli_20.10.21_3-0_debian-bullseye_amd64.deb".toString()

        // 200ms once 600 times -> 120s -> 2m
        List<OneCmd> commandList = cmdAsRoot new OneCmd(cmd: 'chmod -R 777 ' + destDockerDir, checker: OneCmd.any()),
                new OneCmd(cmd: 'apt update', checker: OneCmd.keyword('apt list --upgradable', ':' + userHomeDir)),
                new OneCmd(cmd: containerdInstallCmd, maxWaitTimes: 300,
                        checker: OneCmd.keyword(' 0 newly installed', 'Processing triggers', ':' + userHomeDir)
                                .failKeyword('Permission', 'broken packages')),
                new OneCmd(cmd: clientInstallCmd, maxWaitTimes: 600,
                        checker: OneCmd.keyword(' 0 newly installed', 'Processing triggers', ':' + userHomeDir)
                                .failKeyword('Permission', 'broken packages'))

        def isExecOk = deploy.exec(info, commandList, 1200, true)
        if (!isExecOk) {
            return false
        }
        commandList.every { it.ok() }
    }

    boolean pullDockerImageList(List<String> imageList) {
        if (!info.rootPass) {
            throw new DeployException('root password need init - ' + info.host)
        }

        def deploy = DeploySupport.instance
        List<OneCmd> commandList = cmdAsRoot()

        for (image in imageList) {
            // 200ms once 600 times -> 120s -> 2m
            commandList << new OneCmd(cmd: 'docker pull ' + image, maxWaitTimes: 600,
                    checker: OneCmd.keyword('Downloaded newer image', 'Image is up to date').
                            failKeyword('not found'))
        }

        def isExecOk = deploy.exec(info, commandList, 120 * imageList.size(), true)
        if (!isExecOk) {
            return false
        }
        commandList.every { it.ok() }
    }

    boolean copyAndLoadDockerImage(String imageTarGzName) {
        if (!info.rootPass) {
            throw new DeployException('root password need init - ' + info.host)
        }

        String localFilePath = userHomeDir + '/images/' + imageTarGzName

        boolean isCopyDone = copyFileIfNotExists(localFilePath, false, true)
        if (!isCopyDone) {
            return isCopyDone
        }
        boolean isLoadOk = loadDockerImage(localFilePath)
        isLoadOk
    }

    boolean loadDockerImage(String localFilePath) {
        if (!info.rootPass) {
            throw new DeployException('root password need init - ' + info.host)
        }

        def deploy = DeploySupport.instance

        String loadCmd = "gunzip -c ${localFilePath}|docker load".toString()

        List<OneCmd> commandList = cmdAsRoot new OneCmd(cmd: loadCmd, maxWaitTimes: 300, checker: OneCmd.keyword('Loaded image'))

        def isExecOk = deploy.exec(info, commandList, 600, true)
        if (!isExecOk) {
            return false
        }
        commandList.every { it.ok() }
    }

    void initAgentConf(AgentConf agentConf) {
        String destAgentDir = agentTarFile.replace('.tar.gz', '')

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
    }

    boolean stopAgent() {
        if (!info.rootPass) {
            throw new DeployException('root password need init - ' + info.host)
        }

        String stopCommand = "pgrep -f dms_agent|xargs kill -s 15"
        List<OneCmd> commandList = cmdAsRoot new OneCmd(cmd: stopCommand, checker: OneCmd.any())

        DeploySupport.instance.exec(info, commandList, 30, true)
        commandList.every { it.ok() }
    }

    boolean startAgentCmd() {
        if (!info.rootPass) {
            throw new DeployException('root password need init - ' + info.host)
        }

        String destAgentDir = agentTarFile.replace('.tar.gz', '')

        String javaCmd = Conf.instance.getString('agent.java.cmd',
                '../jdk8/zulu8.66.0.15-ca-jdk8.0.352-linux_x64/bin/java -Xms128m -Xmx256m')
        String startCommand = "nohup ${javaCmd} ".toString() +
                "-Djava.library.path=. -cp . -jar dms_agent-1.0.jar > dmc.log 2>&1 &"
        List<OneCmd> commandList = cmdAsRoot new OneCmd(cmd: 'cd ' + destAgentDir, checker: OneCmd.keyword('agentV1')),
                new OneCmd(cmd: startCommand, checker: OneCmd.any())

        DeploySupport.instance.exec(info, commandList, 30, true)
        commandList.every { it.ok() }
    }
}
