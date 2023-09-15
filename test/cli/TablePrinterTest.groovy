package cli

String uuid(String pre = '', int len = 5) {
    def rand = new Random()
    List az = 0..9
    int size = az.size()
    def sb = new StringBuilder(pre)
    for (int i = 0; i < len; i++) {
        sb << az[rand.nextInt(size)]
    }
    sb.toString()
}

List<List<String>> list = []

10.times { i ->
    List<String> row = []
    10.times { j ->
        row << uuid('', 20 + i + j)
    }
    list << row
}

TablePrinter.print(list)