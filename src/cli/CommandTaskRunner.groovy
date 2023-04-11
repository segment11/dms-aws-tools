package cli

import groovy.transform.CompileStatic
import org.apache.commons.cli.CommandLine

@CompileStatic
interface CommandTaskRunner {
    void run(CommandLine cmd)
}
