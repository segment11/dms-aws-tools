package deploy

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

@CompileStatic
@Slf4j
class OneCmd {
    String cmd

    long waitMsOnce = 200

    int maxWaitTimes = 5

    String result

    Integer status = -1

    long costT = 0

    boolean showCmdLog = true

    boolean ok() {
        status == 0
    }

    void clear() {
        result = null
        status = -1
        costT = 0
    }

    ContainsChecker checker

    @Override
    String toString() {
        def item = [:]
        item.cmd = showCmdLog ? cmd : '***'
        item.result = result
        item.status = status
        item.costT = costT
        item.toString()
    }

    static OneCmd simple(String cmd) {
        def one = new OneCmd()
        one.cmd = cmd
        one
    }

    private String read(InputStream is) {
        def buf = new StringBuilder(1024)
        def tmp = new byte[1024]
        while (is.available() > 0) {
            int i = is.read(tmp, 0, 1024)
            if (i < 0) {
                break
            }
            buf << new String(tmp, 0, i)
        }
        buf.toString()
    }

    void execInShell(OutputStream os, InputStream is) {
        long beginT = System.currentTimeMillis()
        if (showCmdLog) {
            log.info '<- ' + cmd + ' timeout: ' + maxWaitTimes * waitMsOnce + 'ms'
        }
        os.write("${cmd.trim()}\r".getBytes())
        os.flush()

        int count = 0
        while (true) {
            if (count % 10 == 0) {
                log.info 'wait a while'
            }
            Thread.sleep(waitMsOnce)
            count++
            if (count > maxWaitTimes) {
                costT = System.currentTimeMillis() - beginT
                throw new DeployException('wait max times for cmd: ' + (showCmdLog ? cmd : '***'))
            }
            def readResult = read(is)
            log.info '-> ' + readResult
            result = readResult
            boolean isEnd = checker.isEnd(readResult)
            if (isEnd) {
                status = checker.ok() ? 0 : -1
                costT = System.currentTimeMillis() - beginT
                break
            }
        }
    }

    static ContainsChecker keyword(String... keyword) {
        new ContainsChecker(keyword)
    }

    static ContainsChecker any() {
        new ContainsChecker('*')
    }

    @CompileStatic
    static class ContainsChecker {
        // for json read write, not private
        String[] keyword
        String[] failKeyword

        // for json
        ContainsChecker() {

        }

        ContainsChecker(String... keyword) {
            this.keyword = keyword
        }

        ContainsChecker failKeyword(String... failKeyword) {
            this.failKeyword = failKeyword
            this
        }

        private boolean flag = false

        boolean isEnd(String result) {
            if (keyword.contains('*')) {
                flag = true
                return true
            }

            if (!result) {
                return false
            }
            for (k in keyword) {
                if (result.contains(k)) {
                    flag = true
                    return true
                }
            }
            for (k in failKeyword) {
                if (result.contains(k)) {
                    flag = false
                    return true
                }
            }
            false
        }

        boolean ok() {
            flag
        }
    }
}
