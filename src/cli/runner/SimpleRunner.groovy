package cli.runner

import aws.AliyunCaller
import aws.AwsCaller
import cli.TablePrinter
import model.MontAwsResourceDTO
import org.segment.d.Record
import org.slf4j.LoggerFactory
import support.CacheSession

def h = cli.CommandTaskRunnerHolder.instance
def log = LoggerFactory.getLogger(this.getClass())

h.add('simple runner') { cmd ->
    '''
list
'''.readLines().collect { it.trim() }.findAll { it }.any {
        cmd.hasOption(it)
    }
} { cmd ->
    def type = cmd.getOptionValue('type')
    if ('region' == type) {
        List<List<String>> table = []
        List<String> header = ['region name', 'description']
        table << header
        AwsCaller.instance.regions.each {
            List<String> row = [it.name, it.des]
            table << row
        }
        TablePrinter.print(table)
        return
    }

    def caller = AwsCaller.instance

    if ('az' == type) {
        def region = cmd.getOptionValue('region')
        def list = caller.getAvailabilityZoneList(region)
        List<List<String>> table = []
        List<String> header = ['region name', 'zone name', 'state']
        table << header
        list.each {
            List<String> row = [it.regionName, it.zoneName, it.state]
            table << row
        }
        TablePrinter.print(table)
        return
    }

    if ('vpc' == type) {
        def region = cmd.getOptionValue('region')
        def list = caller.listVpc(region)
        if (!list) {
            log.warn 'no vpc found'
            return
        }

        CacheSession.instance.vpcList = list

        List<List<String>> table = []
        List<String> header = ['vpc id', 'cidr block', 'state']
        table << header
        list.each {
            List<String> row = [it.vpcId, it.cidrBlock, it.state]
            table << row
        }
        TablePrinter.print(table)
        return
    }

    if ('subnet' == type) {
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

        def list = caller.listSubnet(region, vpcId)
        if (!list) {
            log.warn 'no subnet found'
            return
        }

        CacheSession.instance.subnetList = list

        List<List<String>> table = []
        List<String> header = ['subnet id', 'cidr block', 'az', 'state']
        table << header
        list.each {
            List<String> row = [it.subnetId, it.cidrBlock, it.availabilityZone, it.state]
            table << row
        }
        TablePrinter.print(table)
        return
    }

    if ('instance' == type) {
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

        def list = caller.listInstance(region, vpcId)
        if (!list) {
            log.warn 'no instance found'
            return
        }

        CacheSession.instance.instanceList = list

        List<List<String>> table = []
        List<String> header = ['instance id', 'instance type', 'state', 'private ip', 'public ip']
        table << header
        list.each {
            List<String> row = [it.instanceId, it.instanceType, it.state.toString(), it.privateIpAddress, it.publicIpAddress]
            table << row
        }
        TablePrinter.print(table)
        return
    }

    if ('instanceTypeFamily' == type) {
        if (!caller.isAliyun) {
            log.warn 'only aliyun support instance type family'
            return
        }

        def region = cmd.getOptionValue('region')

        def list = AliyunCaller.instance.getInstanceTypeFamilyList(region)
        List<List<String>> table = []
        List<String> header = ['instance type family', 'generation']
        table << header
        list.each {
            List<String> row = [it.instanceTypeFamilyId, it.generation]
            table << row
        }
        TablePrinter.print(table)
        return
    }

    if ('instanceType' == type) {
        def region = cmd.getOptionValue('region')
        def keyword = cmd.getOptionValue('keyword')
        if (!keyword) {
            log.warn 'no keyword given'
            return
        }

        def list = caller.getInstanceTypeListInRegion(region, keyword)
        if (!list) {
            log.warn 'no instance type found'
            return
        }

        List<List<String>> table = []
        List<String> header = ['instance type', 'mem MB', 'cpu vCore']
        table << header
        list.each {
            List<String> row = [it.instanceType.toString(), it.memMB.toString(), it.cpuVCore.toString()]
            table << row
        }
        TablePrinter.print(table)
        return
    }

    if ('image' == type) {
        def region = cmd.getOptionValue('region')
        def keyword = cmd.getOptionValue('keyword')
        if (!keyword) {
            log.warn 'no keyword given'
            return
        }

        def list = caller.getImageListInRegion(region, keyword)
        if (!list) {
            log.warn 'no image- found'
            return
        }

        List<List<String>> table = []
        List<String> header = ['image id', 'name', 'architecture']
        table << header
        list.each {
            List<String> row = [it.id, it.name, it.architecture]
            table << row
        }
        TablePrinter.print(table)
        return
    }

    if ('localAwsResource' == type) {
        def list = new MontAwsResourceDTO().noWhere().loadList()
        if (!list) {
            log.warn 'no local aws resource found'
            return
        }

        TablePrinter.printRecordList(list.collect { (Record) it })
        return
    }

    log.warn 'type not support: ' + type
    return
}
