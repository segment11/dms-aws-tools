package deploy

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.segment.common.Conf
import common.Event
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.apache.commons.io.FileUtils

@CompileStatic
@Slf4j
@Singleton
class DeploySupport {
    final String userHomeDir = System.getProperty('user.home').replaceAll("\\\\", '/')

    private String keyName(String ip) {
        'dms_auto_' + ip.replaceAll(/\./, '_')
    }

    private com.jcraft.jsch.Session connect(RemoteInfo remoteInfo) {
        def connectTimeoutMillis = Conf.instance.getInt('ssh.sessionConnectTimeoutMillis', 2000)
        final Properties config = new Properties()
        config.StrictHostKeyChecking = 'no'
        config.PreferredAuthentications = 'publickey,gssapi-with-mic,keyboard-interactive,password'

        def jsch = new JSch()
        com.jcraft.jsch.Session session = jsch.getSession(remoteInfo.user, remoteInfo.host, remoteInfo.port)
        session.timeout = connectTimeoutMillis

        if (remoteInfo.isUsePass) {
            session.setPassword(remoteInfo.password)
        } else {
            String privateKeyFileLocation = userHomeDir + '/.ssh/' + keyName(remoteInfo.host) + remoteInfo.privateKeySuffix
            def filePrivateKey = new File(privateKeyFileLocation)
            if (!filePrivateKey.exists()) {
                FileUtils.touch(filePrivateKey)
                filePrivateKey.text = remoteInfo.privateKeyContent
                log.info 'done create private key local file {}', filePrivateKey
            }

            jsch.addIdentity(privateKeyFileLocation)
        }
        session.config = config
        session.connect()
        log.info 'jsch session connected {}', remoteInfo.host
        session
    }

    void send(RemoteInfo remoteInfo, String localFilePath, String remoteFilePath) {
        def f = new File(localFilePath)
        if (!f.exists() || !f.canRead()) {
            throw new IllegalStateException('local file can not read: ' + localFilePath)
        }

        com.jcraft.jsch.Session session
        try {
            session = connect(remoteInfo)

            ChannelSftp channel
            try {
                channel = session.openChannel('sftp') as ChannelSftp
                channel.connect()
                log.info 'sftp channel connected {}', remoteInfo.host

                long beginT = System.currentTimeMillis()
                channel.put(f.absolutePath, remoteFilePath,
                        new FilePutProgressMonitor(f.length()), ChannelSftp.OVERWRITE)

                def costT = System.currentTimeMillis() - beginT
                String message = "scp cost ${costT}ms to ${remoteFilePath}".toString()
                Event.builder().type(Event.Type.ec2).reason('scp').
                        result(remoteInfo.host).build().
                        log(message).add()
            } finally {
                if (channel) {
                    channel.quit()
                    channel.disconnect()
                }
            }
        } finally {
            if (session) {
                session.disconnect()
            }
        }
    }

    boolean exec(RemoteInfo remoteInfo, OneCmd command, long timeoutSeconds = 10) {
        exec(remoteInfo, [command], timeoutSeconds, false)
    }

    boolean exec(RemoteInfo remoteInfo, List<OneCmd> cmdList,
                 long timeoutSeconds = 10, boolean isShell = false) {
        for (one in cmdList) {
            // if user not set maxWaitTimes, use avg
            if (one.maxWaitTimes == 5) {
                one.maxWaitTimes = (timeoutSeconds * 1000 / cmdList.size() / one.waitMsOnce).intValue()
            }
        }

        com.jcraft.jsch.Session session
        try {
            session = connect(remoteInfo)

            def exec = new CmdExecutor()
            exec.host = remoteInfo.host
            exec.session = session
            exec.cmdList = cmdList
            if (isShell) {
                return exec.execShell()
            } else {
                return exec.exec()
            }
        } finally {
            if (session) {
                session.disconnect()
            }
        }
    }

}
