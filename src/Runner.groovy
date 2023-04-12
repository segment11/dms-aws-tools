import aws.AwsCaller
import cli.CommandTaskRunnerHolder
import com.segment.common.Conf
import org.apache.commons.cli.CommandLine
import org.apache.commons.cli.DefaultParser
import org.apache.commons.cli.HelpFormatter
import org.apache.commons.cli.Options
import org.segment.d.D
import org.segment.d.Ds
import org.segment.d.MySQLDialect
import org.segment.web.RouteRefreshLoader
import org.segment.web.common.CachedGroovyClassLoader
import org.slf4j.LoggerFactory

def log = LoggerFactory.getLogger(this.getClass())

// project work directory set
String[] x = super.binding.getProperty('args') as String[]
def c = Conf.instance.resetWorkDir().load().loadArgs(x)
log.info c.toString()

def accessKey = c.get('accessKey')
def secretKey = c.get('secretKey')
if (!accessKey || !secretKey) {
    def f = new File(c.projectPath('/aws.credential'))
    if (!f.exists()) {
        log.warn 'accessKey and secretKey required'
        return
    }
    def lines = f.readLines()
    accessKey = lines.find { it.contains('key_id') }.split('=')[-1].trim()
    secretKey = lines.find { it.contains('secret') }.split('=')[-1].trim()
}
AwsCaller.instance.init(accessKey, secretKey)

// check if running in jar not groovy
def projectDir = new File(c.projectPath('/'))
projectDir.eachFile {
    if (it.name.endsWith('.jar') && it.name.startsWith('dms-aws-tools')) {
        c.on('cli.runtime.jar')
        log.info 'running in jar'
    }
}

// init db access
def dbDataFile = c.getString('db.data.file', c.projectPath('/dms-aws-tools-data'))
def ds = Ds.h2LocalWithPool(dbDataFile, 'default_ds')
def d = new D(ds, new MySQLDialect())
// check if need create table first
List<String> tableNameList = d.query("show tables", String).collect { it.toUpperCase() }
if (!('MONT_EVENT' in tableNameList)) {
    def ddl = '''
create table mont_aws_resource (
    id int auto_increment primary key,
    region varchar(20),
    vpc_id varchar(50),
    arn varchar(200), -- aws resource name id
    type varchar(50), -- vpc/subnet etc.
    sub_key varchar(100),
    status int, -- only for ec2: 0 ok, -1 stopped
    refer_arn varchar(200), -- for query, ec2 in subset id, vpc peering etc.
    extend_params varchar(2000),
    updated_date timestamp default current_timestamp
);
create index idx_mont_aws_resource_vpc_id on mont_aws_resource(vpc_id);
create index idx_mont_aws_resource_type on mont_aws_resource(type);
create unique index idx_mont_aws_resource_arn on mont_aws_resource(arn);

create table mont_event (
    id int auto_increment primary key,
    type varchar(20),
    reason varchar(100),
    result varchar(100),
    message text,
    created_date timestamp
);
create index idx_mont_event_type_reason on mont_event(type, reason);
create index idx_mont_event_created_date on mont_event(created_date);

create table mont_job_check (
    id int auto_increment primary key,
    job_key varchar(50),
    updated_date timestamp default current_timestamp
);
create index idx_mont_job_check_job_key on mont_job_check(job_key);
'''
    ddl.trim().split(';').each {
        try {
            d.exe(it.toString())
        } catch (Exception e) {
            log.error('create table fail, ex: ' + e.message)
        }
    }
}

// groovy class loader init
def srcDirPath = c.projectPath('/src')
def resourceDirPath = c.projectPath('/resources')
def loader = CachedGroovyClassLoader.instance
loader.init(c.class.classLoader, srcDirPath + ':' + resourceDirPath)

// load command line runner dyn in target dir
def refreshLoader = RouteRefreshLoader.create(loader.gcl).addClasspath(srcDirPath).addClasspath(resourceDirPath).
        addDir(c.projectPath('/src/cli/runner')).jarLoad(c.isOn('cli.runtime.jar'))
refreshLoader.refresh()
refreshLoader.start()

def options = new Options()
options.addOption('l', 'list', false, '')
options.addOption('c', 'create', false, 'create')
options.addOption('t', 'type', true, 'region/az/vpc/subnet/instance/instanceType/image')
options.addOption('r', 'region', true, '--region=ap-northeast-1')
options.addOption('a', 'az', true, '--az=ap-northeast-1a')
options.addOption('v', 'vpcId', true, '--vpcId=vpcId')
options.addOption('s', 'subnetId', true, '--subnetId=subnetId')
options.addOption('b', 'cidrBlock', true, '--cidrBlock=10.1.0.0/16')
options.addOption('k', 'keyword', true, 'for filter, eg. --keyword=c3.')
options.addOption('e', 'ec2', true, 'launch ec2 instance')
options.addOption('p', 'publicIpv4', false, 'set true if launch ec2 instance with a public ipv4')
options.addOption('m', 'imageId', true, 'image id')
options.addOption('i', 'instanceType', true, 'instance type')
options.addOption('j', 'join', true, 'join dms cluster, eg. --join=10.1.0.23')
options.addOption('H', 'help', false, 'args help')
options.addOption('Q', 'quit', false, 'quit console')
options.addOption('x', 'x_session_current_variables', false, 'view args for reuse in current session')

def formatter = new HelpFormatter()
formatter.printHelp('please input follow args to run task', options)
println '----- begin console interact -----'

String globalRegion = c.get('region')
String globalAz
String globalVpcId
String globalSubnetId

def parser = new DefaultParser()

def br = new BufferedReader(new InputStreamReader(System.in))
while (true) {
    def line = br.readLine().trim()

    if (line == 'quit') {
        println 'quit from console...'
        break
    } else if (line == 'help') {
        println 'java -jar dms-aws-tools-1.0.jar'
        println 'you mean --help?'
        continue
    } else if (line.startsWith('-') || line.startsWith('--')) {
        String finalLine = line

        if (globalRegion && !finalLine.contains('-r=') && !finalLine.contains('--region=')) {
            finalLine += (' -r=' + globalRegion)
        }
        if (globalAz && !finalLine.contains('-a=') && !finalLine.contains('--az=')) {
            finalLine += (' -a=' + globalAz)
        }
        if (globalVpcId && !finalLine.contains('-v=') && !finalLine.contains('--vpc=')) {
            finalLine += (' -v=' + globalVpcId)
        }
        if (globalSubnetId && !finalLine.contains('-s=') && !finalLine.contains('--subnet=')) {
            finalLine += (' -s=' + globalSubnetId)
        }

        CommandLine cmd
        try {
            cmd = parser.parse(options, finalLine.split(' '))
            if (cmd.hasOption('quit')) {
                println 'quit...'
                break
            }

            if (cmd.hasOption('help')) {
                formatter.printHelp('please input follow args to run task', options)
                println '----- begin console interact -----'
                continue
            }
        } catch (Exception e) {
            log.error 'args parse fail, input help for more information, ex: ' + e.message
            continue
        }

        if (cmd.hasOption('region')) {
            globalRegion = cmd.getOptionValue('region')
        }

        if (cmd.hasOption('az')) {
            globalAz = cmd.getOptionValue('az')
        }

        if (cmd.hasOption('vpcId')) {
            globalVpcId = cmd.getOptionValue('vpcId')
        }

        if (cmd.hasOption('subnetId')) {
            globalSubnetId = cmd.getOptionValue('subnetId')
        }

        if (cmd.hasOption('x_session_current_variables')) {
            println 'region: '.padRight(20, ' ') + globalRegion
            println 'az: '.padRight(20, ' ') + globalAz
            println 'vpc id: '.padRight(20, ' ') + globalVpcId
            println 'subnet id: '.padRight(20, ' ') + globalSubnetId
            continue
        }

        if (!cmd.hasOption('region') && 'region' != cmd.getOptionValue('type')) {
            log.error 'region required'
            continue
        }

        try {
            def isDone = CommandTaskRunnerHolder.instance.run(cmd)
            if (!isDone) {
                println '--help for valid args'
            }
        } catch (IllegalStateException e) {
            log.warn e.message
        } catch (Exception e) {
            log.error 'run task error', e
        }
    }
}

refreshLoader.stop()
AwsCaller.instance.shutdown()
Ds.disconnectAll()
