package cli.runner

import aws.AwsCaller
import aws.AwsResourceManager
import com.segment.common.Conf
import model.MontAwsResourceDTO
import model.json.ExtendParams
import org.slf4j.LoggerFactory
import support.CacheSession

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

        // redis proxy server port
        int proxyPort = Conf.instance.getInt('proxy.port', 8125)

        def manager = AwsResourceManager.instance
        def vpcInfo = manager.createVpcIfNotExists(region, cidrBlock)
        log.info 'vpc info: {}', vpcInfo
        manager.initSecurityGroupRules(vpcInfo, proxyPort, 22, 80, 81, 5010)

        def igwId = manager.addInternetGateway(vpcInfo)
        manager.addIgwRoute(region, vpcInfo.id, vpcInfo.routeTableId, igwId)

        return
    }

    if ('subnet' == type) {
        def region = cmd.getOptionValue('region')
        def vpcIdShort = cmd.getOptionValue('vpcId')
        def cidrBlock = cmd.getOptionValue('cidrBlock')
        def az = cmd.getOptionValue('az')
        if (!vpcIdShort || !cidrBlock || !az) {
            log.warn 'vpcId, cidrBlock and az required'
            return
        }

        def vpcId = CacheSession.instance.vpcList.find { it.vpcId.endsWith(vpcIdShort) }?.vpcId
        if (!vpcId) {
            log.warn 'no vpc id match found'
            return
        }

        def manager = AwsResourceManager.instance
        manager.createSubnet(region, vpcId, cidrBlock, az)

        return
    }

    if ('keyPairLoadFromLocal' == type) {
        def region = cmd.getOptionValue('region')
        def vpcIdShort = cmd.getOptionValue('vpcId')
        def keyPairName = cmd.getOptionValue('keyPairName')
        if (!vpcIdShort || !keyPairName) {
            log.warn 'vpcId and keyPairName required'
            return
        }

        def vpcId = CacheSession.instance.vpcList.find { it.vpcId.endsWith(vpcIdShort) }?.vpcId
        if (!vpcId) {
            log.warn 'no vpc id match found'
            return
        }

        def userHomeDir = System.getProperty('user.home')
        def f = new File(userHomeDir + '/.ssh/' + keyPairName + '.pem')
        if (!f.exists()) {
            log.warn 'file not exists, file path: {}', f.absolutePath
            return
        }
        def content = f.text

        def keyPairInfo = AwsCaller.instance.getKeyPair(region, keyPairName)
        if (!keyPairInfo) {
            log.warn 'key pair not exists'
            return
        }

        Map<String, String> params = [:]
        params.keyFingerprint = keyPairInfo.keyFingerprint

        params.keyName = keyPairName
        // aliyun no keyPairId
        params.keyPairId = keyPairInfo.keyPairId
        params.keyMaterial = content

        new MontAwsResourceDTO(
                vpcId: vpcId,
                region: region,
                type: MontAwsResourceDTO.Type.kp.name(),
                arn: keyPairInfo.keyPairId,
                subKey: keyPairName,
                extendParams: new ExtendParams(params: params)).add()
        log.info 'add to local db success'
        return
    }

    log.warn 'type not support: ' + type
    return
}
