package ai.techtroy.clockcanvas.io

/**
 * Deliberately tiny JSON reader/writer for the design files.
 *
 * Gson/org.json/Room are not used on purpose: designs are small flat records,
 * the app keeps working offline with zero third-party code, and the payload
 * stays human-inspectable (`files/clockcanvas/designs/<id>.json`) so the
 * export/share/import round-trip can be debugged by reading it.
 */
object MiniJson {

    class Obj {
        private val values = LinkedHashMap<String, Any?>()

        fun put(key: String, value: Any?) { values[key] = value }

        fun raw(key: String): Any? = values[key]

        fun has(key: String) = values.containsKey(key)

        fun str(key: String, fallback: String = ""): String {
            val v = values[key] ?: return fallback
            return if (v is String) v else v.toString()
        }

        fun strOrNull(key: String): String? {
            val v = values[key] ?: return null
            return if (v is String) v else v.toString()
        }

        fun int(key: String, fallback: Int): Int = when (val v = values[key]) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: fallback
            else -> fallback
        }

        fun long(key: String, fallback: Long): Long = when (val v = values[key]) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull() ?: fallback
            else -> fallback
        }

        fun float(key: String, fallback: Float): Float = when (val v = values[key]) {
            is Number -> v.toFloat()
            is String -> v.toFloatOrNull() ?: fallback
            else -> fallback
        }

        fun bool(key: String, fallback: Boolean): Boolean = when (val v = values[key]) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> v == "true" || v == "1"
            else -> fallback
        }

        fun obj(key: String): Obj? = values[key] as? Obj

        @Suppress("UNCHECKED_CAST")
        fun arr(key: String): List<Obj>? = values[key] as? List<Obj>

        fun snapshot(): List<Pair<String, Any?>> = values.entries.map { it.key to it.value }
    }

    fun write(obj: Obj): String {
        val sb = StringBuilder(512)
        writeObj(sb, obj)
        return sb.toString()
    }

    fun writeList(items: List<Obj>): String {
        val sb = StringBuilder(1024)
        sb.append('[')
        for ((index, item) in items.withIndex()) {
            if (index > 0) sb.append(',')
            writeObj(sb, item)
        }
        sb.append(']')
        return sb.toString()
    }

    private fun writeObj(sb: StringBuilder, obj: Obj) {
        sb.append('{')
        var first = true
        for ((k, v) in obj.snapshot()) {
            if (!first) sb.append(',')
            first = false
            writeString(sb, k)
            sb.append(':')
            writeValue(sb, v)
        }
        sb.append('}')
    }

    private fun writeValue(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> writeString(sb, v)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int -> sb.append(Integer.toString(v))
            is Long -> sb.append(java.lang.Long.toString(v))
            is Float -> sb.append(if (v.isFinite()) v.toString() else "0.0")
            is Double -> sb.append(if (v.isFinite()) v.toString() else "0.0")
            is Obj -> writeObj(sb, v)
            is List<*> -> {
                sb.append('[')
                var first = true
                for (item in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeValue(sb, item)
                }
                sb.append(']')
            }
            else -> writeString(sb, v.toString())
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append(Q)
        for (element in s) {
            when (element) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (element.code < 0x20) sb.append("\\u").append(String.format("%04x", element.code))
                else sb.append(element)
            }
        }
        sb.append(Q)
    }

    /** Tolerant parse: malformed input yields null instead of an exception. */
    fun parse(text: String?): Obj? {
        if (text == null) return null
        return try {
            val p = Parser(text)
            p.skipWs()
            p.readValue() as? Obj
        } catch (e: Throwable) {
            null
        }
    }

    fun parseList(text: String?): List<Obj> {
        if (text == null) return emptyList()
        return try {
            val p = Parser(text)
            p.skipWs()
            @Suppress("UNCHECKED_CAST")
            (p.readValue() as? List<Obj>) ?: emptyList()
        } catch (e: Throwable) {
            emptyList()
        }
    }

    private const val Q = '"'

    private class Parser(private val s: String) {
        private var i = 0

        fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        fun readValue(): Any? {
            skipWs()
            if (i >= s.length) throw IllegalStateException("eof")
            val c = s[i]
            return when {
                c == '{' -> readObject()
                c == '[' -> readArray()
                c == Q -> readString()
                c == 't' -> readLiteral("true", true)
                c == 'f' -> readLiteral("false", false)
                c == 'n' -> readLiteral("null", null)
                c == '-' || (c in '0'..'9') -> readNumber()
                else -> throw IllegalStateException("char $c")
            }
        }

        private fun readLiteral(text: String, value: Any?): Any? {
            if (!s.regionMatches(i, text, 0, text.length)) throw IllegalStateException("literal")
            i += text.length
            return value
        }

        private fun readNumber(): Any {
            val start = i
            if (i < s.length && s[i] == '-') i++
            while (i < s.length && s[i] in '0'..'9') i++
            var floating = false
            if (i < s.length && s[i] == '.') {
                floating = true
                i++
                while (i < s.length && s[i] in '0'..'9') i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                floating = true
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i] in '0'..'9') i++
            }
            val text = s.substring(start, i)
            if (floating) return text.toDouble()
            val l = text.toLong()
            return if (l in Int.MIN_VALUE..Int.MAX_VALUE) l.toInt() else l
        }

        private fun readObject(): Obj {
            val obj = Obj()
            i++
            skipWs()
            if (i < s.length && s[i] == '}') {
                i++
                return obj
            }
            while (i < s.length) {
                skipWs()
                val key = readString()
                skipWs()
                if (i >= s.length || s[i] != ':') throw IllegalStateException("colon")
                i++
                obj.put(key, readValue())
                skipWs()
                if (i < s.length && s[i] == ',') {
                    i++
                    continue
                }
                if (i < s.length && s[i] == '}') {
                    i++
                    return obj
                }
                throw IllegalStateException("object")
            }
            throw IllegalStateException("object eof")
        }

        private fun readArray(): List<Any?> {
            val list = ArrayList<Any?>()
            i++
            skipWs()
            if (i < s.length && s[i] == ']') {
                i++
                return list
            }
            while (i < s.length) {
                list.add(readValue())
                skipWs()
                if (i < s.length && s[i] == ',') {
                    i++
                    continue
                }
                if (i < s.length && s[i] == ']') {
                    i++
                    return list
                }
                throw IllegalStateException("array")
            }
            throw IllegalStateException("array eof")
        }

        private fun readString(): String {
            if (i >= s.length || s[i] != Q) throw IllegalStateException("open")
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i]
                if (c == Q) {
                    i++
                    return sb.toString()
                }
                if (c == '\\') {
                    i++
                    if (i >= s.length) throw IllegalStateException("escape")
                    val e = s[i]
                    i++
                    when (e) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 > s.length) throw IllegalStateException("unicode")
                            sb.append(Integer.parseInt(s.substring(i, i + 4), 16).toChar())
                            i += 4
                        }
                        else -> sb.append(e)
                    }
                } else {
                    sb.append(c)
                    i++
                }
            }
            throw IllegalStateException("string eof")
        }
    }
}
