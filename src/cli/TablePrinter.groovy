package cli

import groovy.transform.CompileStatic
import org.segment.d.Record

@CompileStatic
class TablePrinter {
    static void printRecord(Record row) {
        printRecordList([row])
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
            sbRow << '+' << '-' * (maxLength + 2)
        }
        sbRow << '+'
        def rowSplit = sbRow.toString()

        def sb = new StringBuilder()
        sb << rowSplit
        sb << '\n'

        table.eachWithIndex { List<String> row, int j ->
            sb << '|'

            for (i in 0..<first.size()) {
                def maxLength = maxLengths[i]
                def val = row[i]
                sb << ' ' + (val == null ? 'null' : val).padRight(maxLength + 1, ' ')
                sb << '|'
            }
            sb << '\n'
            sb << rowSplit
            sb << '\n'
        }
        println sb.toString()
    }
}
