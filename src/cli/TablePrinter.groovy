package cli

import groovy.transform.CompileStatic
import org.segment.d.Record

@CompileStatic
class TablePrinter {
    static void printRecord(Record row) {
        printRecordList([row])
    }

    private static Map<String, Integer> colors = [:]

    @CompileStatic
    static enum Color {
        red(31), yellow(32), orange(33), blue(34), purple(35), green(36)

        int n

        Color(int n) {
            this.n = n
        }
    }

    @CompileStatic
    static enum Style {
        bold(1), italic(3), underscore(4)

        int n

        Style(int n) {
            this.n = n
        }
    }

    static String colorFormat(String content, Color color, Style style = null) {
        if (!content) {
            return content
        }

        if (!style) {
            return String.format("\033[%dm%s\033[0m", color ? color.n : Color.red.n, content)
        }

        String.format("\033[%d;%dm%s\033[0m", color ? color.n : Color.red.n, style ? style.n : Style.bold.n, content)
    }

    static void printRecordList(List<Record> list) {
        List<List<String>> r = []
        if (list) {
            def first = list[0]
            def props = first.rawProps()
            List<String> subList = []
            for (key in props.keySet()) {
                subList << key
            }
            r << subList

            for (one in list) {
                List<String> subValueList = []
                def propsValue = one.rawProps()
                propsValue.each { k, v ->
                    subValueList << (v == null ? '' : v.toString())
                }
                r << subValueList
            }
        }
        print(r)
    }

    static void print(List<List<String>> table) {
        def first = table[0]

        List<Integer> maxLengths = []
        for (i in 0..<first.size()) {
            maxLengths << table.collect { it[i] }.max { it == null ? 0 : it.length() }.length()
        }

        def sbRow = new StringBuilder()
        for (maxLength in maxLengths) {
            sbRow << '+' << ('-' * (maxLength + 2))
        }
        sbRow << '+'
        def rowSplit = sbRow.toString()

        def sb = new StringBuilder()
        sb << colorFormat(rowSplit, Color.blue)
        sb << '\n'

        table.eachWithIndex { List<String> row, int j ->
            sb << colorFormat('|', Color.green)

            for (i in 0..<first.size()) {
                def maxLength = maxLengths[i]
                def val = row[i]
                def valStr = val == null ? 'null' : val
                def valLine = ' ' + valStr.padRight(maxLength + 1, ' ')
                sb << (j == 0 ? colorFormat(valLine, Color.orange) : valLine)
                sb << colorFormat('|', Color.green)
            }
            sb << '\n'
            sb << ((j == 0 || j == table.size() - 1) ? colorFormat(rowSplit, Color.blue) : rowSplit)
            sb << '\n'
        }
        println sb.toString()
    }
}
