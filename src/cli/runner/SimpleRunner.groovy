package cli.runner

import aws.AwsCaller
import cli.TablePrinter
import model.MontAwsResourceDTO
import org.segment.d.Record
import org.slf4j.LoggerFactory

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
        AwsCaller.regions.each {
            log.info it.name + ', ' + it.des
        }
        return
    }

    if ('az' == type) {
        def region = cmd.getOptionValue('region')
        def list = AwsCaller.instance.getAvailabilityZoneList(region, true)
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
        def list = AwsCaller.instance.listVpc(region)
        if (!list) {
            log.warn 'no vpc found'
            return
        }

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
        def vpcId = cmd.getOptionValue('vpcId')
        if (!vpcId) {
            log.warn 'no vpc id given'
            return
        }

        def list = AwsCaller.instance.listSubnet(region, vpcId)
        if (!list) {
            log.warn 'no subnet found'
            return
        }

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
        def vpcId = cmd.getOptionValue('vpcId')
        if (!vpcId) {
            log.warn 'no vpc id given'
            return
        }

        def list = AwsCaller.instance.listInstance(region, vpcId)
        if (!list) {
            log.warn 'no instance found'
            return
        }

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

    if ('instanceType' == type) {
        def region = cmd.getOptionValue('region')
        def list = AwsCaller.instance.getInstanceTypeListInRegion(region)
        def keyword = cmd.getOptionValue('keyword')
        def filterList = keyword ? list.findAll { it.instanceType.contains(keyword) } : list
        if (!filterList) {
            log.warn 'no instance type found'
            return
        }

        for (one in filterList) {
            log.info one.toString()
        }
        return
    }

    if ('image' == type) {
        def region = cmd.getOptionValue('region')
        def list = AwsCaller.instance.getImageListInRegion(region)
        def keyword = cmd.getOptionValue('keyword')
        def filterList = keyword ? list.findAll { it.name.contains(keyword) } : list
        if (!filterList) {
            log.warn 'no image found'
            return
        }

        for (one in filterList) {
            log.info one.toString()
        }
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
}
