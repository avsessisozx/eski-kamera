package app.eskikamera

/** Bağımlılıksız küçük JSON ayrıştırıcı (Android ve JVM'de aynı çalışır). */
object MiniJson {
    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.skipWs()
        if (p.i != text.length) throw IllegalArgumentException("JSON sonunda fazla veri var (konum ${p.i})")
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            skipWs()
            if (i >= s.length) throw IllegalArgumentException("JSON beklenmedik şekilde bitti")
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> num()
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw IllegalArgumentException("Geçersiz JSON değeri (konum $i)")
            i += word.length
            return v
        }

        private fun num(): Double {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] == '-' || s[i] == '+' || s[i] == '.' || s[i] == 'e' || s[i] == 'E')) i++
            if (st == i) throw IllegalArgumentException("Geçersiz JSON karakteri '${s[i]}' (konum $i)")
            return s.substring(st, i).toDouble()
        }

        private fun str(): String {
            i++ // açılış tırnağı
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw IllegalArgumentException("Kapanmamış JSON metni")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        val e = s[i++]
                        when (e) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun arr(): List<Any?> {
            i++
            val list = ArrayList<Any?>()
            skipWs()
            if (s[i] == ']') {
                i++
                return list
            }
            while (true) {
                list.add(value())
                skipWs()
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return list
                    else -> throw IllegalArgumentException("Dizide ',' veya ']' bekleniyordu (konum ${i - 1})")
                }
            }
        }

        private fun obj(): Map<String, Any?> {
            i++
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (s[i] == '}') {
                i++
                return map
            }
            while (true) {
                skipWs()
                if (s[i] != '"') throw IllegalArgumentException("Anahtar bekleniyordu (konum $i)")
                val k = str()
                skipWs()
                if (s[i++] != ':') throw IllegalArgumentException("':' bekleniyordu (konum ${i - 1})")
                map[k] = value()
                skipWs()
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return map
                    else -> throw IllegalArgumentException("Nesnede ',' veya '}' bekleniyordu (konum ${i - 1})")
                }
            }
        }
    }
}
