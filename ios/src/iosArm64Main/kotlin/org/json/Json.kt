package org.json

// iOS 没有 org.json：照它的接口写一份够用的（解析 / 序列化 / opt 系列宽松取值）

class JSONException(msg: String) : RuntimeException(msg)

private fun wrap(v: Any?): Any? = when (v) {
    null -> JSONObject.NULL
    is JSONObject, is JSONArray, is String, is Boolean, is Int, is Long, is Double -> v
    is Float -> v.toDouble()
    is Short -> v.toInt()
    is Byte -> v.toInt()
    is Number -> v.toDouble()
    is Map<*, *> -> JSONObject(v)
    is Collection<*> -> JSONArray(v)
    is Array<*> -> JSONArray(v.toList())
    is Char -> v.toString()
    else -> v.toString()
}

private fun asInt(v: Any?): Int? = when (v) {
    is Number -> v.toInt()
    is String -> v.trim().toIntOrNull() ?: v.trim().toDoubleOrNull()?.toInt()
    is Boolean -> null
    else -> null
}
private fun asLong(v: Any?): Long? = when (v) {
    is Number -> v.toLong()
    is String -> v.trim().toLongOrNull() ?: v.trim().toDoubleOrNull()?.toLong()
    else -> null
}
private fun asDouble(v: Any?): Double? = when (v) {
    is Number -> v.toDouble()
    is String -> v.trim().toDoubleOrNull()
    else -> null
}
private fun asBool(v: Any?): Boolean? = when (v) {
    is Boolean -> v
    is String -> when (v.lowercase()) { "true" -> true; "false" -> false; else -> null }
    else -> null
}
private fun asStr(v: Any?): String? = when (v) {
    null -> null
    JSONObject.NULL -> null
    is String -> v
    else -> valueToString(v)
}

internal fun quote(s: String, sb: StringBuilder) {
    sb.append('"')
    for (c in s) when (c) {
        '"' -> sb.append("\\\"")
        '\\' -> sb.append("\\\\")
        '\n' -> sb.append("\\n")
        '\r' -> sb.append("\\r")
        '\t' -> sb.append("\\t")
        '\b' -> sb.append("\\b")
        '\u000C' -> sb.append("\\f")
        else -> if (c < ' ') { sb.append("\\u"); sb.append(c.code.toString(16).padStart(4, '0')) } else sb.append(c)
    }
    sb.append('"')
}

private fun numStr(d: Double): String {
    if (d.isNaN() || d.isInfinite()) return "null"
    if (d == kotlin.math.floor(d) && kotlin.math.abs(d) < 1e15) return d.toLong().toString()
    return d.toString()
}

internal fun write(v: Any?, sb: StringBuilder) {
    when (v) {
        null, JSONObject.NULL -> sb.append("null")
        is String -> quote(v, sb)
        is JSONObject -> v.writeTo(sb)
        is JSONArray -> v.writeTo(sb)
        is Double -> sb.append(numStr(v))
        is Float -> sb.append(numStr(v.toDouble()))
        is Number, is Boolean -> sb.append(v.toString())
        else -> quote(v.toString(), sb)
    }
}

internal fun valueToString(v: Any?): String = StringBuilder().also { write(v, it) }.toString().let { if (v is String) v else it }

class JSONObject() {
    internal val m = LinkedHashMap<String, Any?>()

    constructor(text: String?) : this() {
        val p = Parser(text ?: throw NullPointerException()); val v = p.value()
        if (v !is JSONObject) throw JSONException("A JSONObject text must begin with '{'")
        m.putAll(v.m)
    }
    constructor(map: Map<*, *>) : this() { for ((k, v) in map) if (v != null) m[k.toString()] = wrap(v) }

    fun put(k: String, v: Any?): JSONObject { if (v == null) m.remove(k) else m[k] = wrap(v); return this }
    fun put(k: String, v: Int): JSONObject { m[k] = v; return this }
    fun put(k: String, v: Long): JSONObject { m[k] = v; return this }
    fun put(k: String, v: Double): JSONObject { m[k] = v; return this }
    fun put(k: String, v: Boolean): JSONObject { m[k] = v; return this }
    fun putOpt(k: String?, v: Any?): JSONObject { if (k != null && v != null) put(k, v); return this }
    fun has(k: String) = m.containsKey(k)
    fun isNull(k: String) = m[k].let { it == null || it == NULL }
    fun opt(k: String): Any? = m[k]
    fun get(k: String): Any = m[k] ?: throw JSONException("JSONObject[\"$k\"] not found.")
    fun remove(k: String): Any? = m.remove(k)
    fun length() = m.size
    fun keys(): Iterator<String> = m.keys.toList().iterator()
    fun keySet(): Set<String> = m.keys
    fun names(): JSONArray? = if (m.isEmpty()) null else JSONArray(m.keys.toList())

    fun optString(k: String) = optString(k, "")
    fun optString(k: String, d: String?): String = asStr(m[k]) ?: (d ?: "")
    fun optInt(k: String, d: Int = 0) = asInt(m[k]) ?: d
    fun optLong(k: String, d: Long = 0) = asLong(m[k]) ?: d
    fun optDouble(k: String, d: Double = Double.NaN) = asDouble(m[k]) ?: d
    fun optBoolean(k: String, d: Boolean = false) = asBool(m[k]) ?: d
    fun optJSONObject(k: String) = m[k] as? JSONObject
    fun optJSONArray(k: String) = m[k] as? JSONArray

    fun getString(k: String): String = get(k).let { it as? String ?: throw JSONException("JSONObject[\"$k\"] is not a string.") }
    fun getInt(k: String): Int = asInt(get(k)) ?: throw JSONException("JSONObject[\"$k\"] is not an int.")
    fun getLong(k: String): Long = asLong(get(k)) ?: throw JSONException("JSONObject[\"$k\"] is not a long.")
    fun getDouble(k: String): Double = asDouble(get(k)) ?: throw JSONException("JSONObject[\"$k\"] is not a number.")
    fun getBoolean(k: String): Boolean = asBool(get(k)) ?: throw JSONException("JSONObject[\"$k\"] is not a Boolean.")
    fun getJSONObject(k: String): JSONObject = get(k) as? JSONObject ?: throw JSONException("JSONObject[\"$k\"] is not a JSONObject.")
    fun getJSONArray(k: String): JSONArray = get(k) as? JSONArray ?: throw JSONException("JSONObject[\"$k\"] is not a JSONArray.")

    fun toMap(): Map<String, Any?> = m.mapValues { (_, v) -> when (v) { is JSONObject -> v.toMap(); is JSONArray -> v.toList(); NULL -> null; else -> v } }

    internal fun writeTo(sb: StringBuilder) {
        sb.append('{'); var first = true
        for ((k, v) in m) { if (!first) sb.append(','); first = false; quote(k, sb); sb.append(':'); write(v, sb) }
        sb.append('}')
    }
    override fun toString() = StringBuilder().also { writeTo(it) }.toString()
    fun toString(indent: Int) = toString()
    override fun equals(other: Any?) = other is JSONObject && other.m == m
    override fun hashCode() = m.hashCode()

    companion object {
        val NULL: Any = object : Any() {
            override fun toString() = "null"
            override fun equals(other: Any?) = other == null || other === this
            override fun hashCode() = 0
        }
        fun quote(s: String): String = StringBuilder().also { quote(s, it) }.toString()
    }
}

class JSONArray() : Iterable<Any?> {
    internal val l = ArrayList<Any?>()

    constructor(text: String?) : this() {
        val v = Parser(text ?: throw NullPointerException()).value()
        if (v !is JSONArray) throw JSONException("A JSONArray text must start with '['")
        l.addAll(v.l)
    }
    constructor(c: Collection<*>) : this() { for (v in c) l.add(wrap(v)) }

    fun length() = l.size
    fun put(v: Any?): JSONArray { l.add(wrap(v)); return this }
    fun put(v: Int): JSONArray { l.add(v); return this }
    fun put(v: Long): JSONArray { l.add(v); return this }
    fun put(v: Double): JSONArray { l.add(v); return this }
    fun put(v: Boolean): JSONArray { l.add(v); return this }
    fun put(i: Int, v: Any?): JSONArray { while (l.size <= i) l.add(JSONObject.NULL); l[i] = wrap(v); return this }
    fun remove(i: Int): Any? = if (i in l.indices) l.removeAt(i) else null
    fun opt(i: Int): Any? = l.getOrNull(i)
    fun get(i: Int): Any = l.getOrNull(i) ?: throw JSONException("JSONArray[$i] not found.")
    fun isNull(i: Int) = opt(i).let { it == null || it == JSONObject.NULL }
    fun optString(i: Int, d: String = "") = asStr(opt(i)) ?: d
    fun optInt(i: Int, d: Int = 0) = asInt(opt(i)) ?: d
    fun optLong(i: Int, d: Long = 0) = asLong(opt(i)) ?: d
    fun optDouble(i: Int, d: Double = Double.NaN) = asDouble(opt(i)) ?: d
    fun optBoolean(i: Int, d: Boolean = false) = asBool(opt(i)) ?: d
    fun optJSONObject(i: Int) = opt(i) as? JSONObject
    fun optJSONArray(i: Int) = opt(i) as? JSONArray
    fun getString(i: Int): String = get(i) as? String ?: throw JSONException("JSONArray[$i] is not a string.")
    fun getInt(i: Int): Int = asInt(get(i)) ?: throw JSONException("JSONArray[$i] is not a number.")
    fun getLong(i: Int): Long = asLong(get(i)) ?: throw JSONException("JSONArray[$i] is not a number.")
    fun getDouble(i: Int): Double = asDouble(get(i)) ?: throw JSONException("JSONArray[$i] is not a number.")
    fun getBoolean(i: Int): Boolean = asBool(get(i)) ?: throw JSONException("JSONArray[$i] is not a boolean.")
    fun getJSONObject(i: Int): JSONObject = get(i) as? JSONObject ?: throw JSONException("JSONArray[$i] is not a JSONObject.")
    fun getJSONArray(i: Int): JSONArray = get(i) as? JSONArray ?: throw JSONException("JSONArray[$i] is not a JSONArray.")
    fun toList(): List<Any?> = l.map { v -> when (v) { is JSONObject -> v.toMap(); is JSONArray -> v.toList(); JSONObject.NULL -> null; else -> v } }
    override fun iterator(): Iterator<Any?> = l.iterator()

    internal fun writeTo(sb: StringBuilder) {
        sb.append('['); l.forEachIndexed { i, v -> if (i > 0) sb.append(','); write(v, sb) }; sb.append(']')
    }
    override fun toString() = StringBuilder().also { writeTo(it) }.toString()
    fun toString(indent: Int) = toString()
    override fun equals(other: Any?) = other is JSONArray && other.l == l
    override fun hashCode() = l.hashCode()
}

private class Parser(val s: String) {
    var i = 0
    fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
    fun err(m: String): Nothing = throw JSONException("$m at character $i")
    fun value(): Any? {
        ws(); if (i >= s.length) err("End of input")
        return when (val c = s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"', '\'' -> str(c)
            else -> lit()
        }
    }
    fun obj(): JSONObject {
        val o = JSONObject(); i++; ws()
        if (i < s.length && s[i] == '}') { i++; return o }
        while (true) {
            ws()
            val k = if (i < s.length && (s[i] == '"' || s[i] == '\'')) str(s[i]) else lit().toString()
            ws(); if (i >= s.length || (s[i] != ':' && s[i] != '=')) err("Expected a ':' after a key"); i++
            o.m[k] = value(); ws()
            if (i >= s.length) err("Expected a ',' or '}'")
            when (s[i]) { ',', ';' -> { i++; ws(); if (i < s.length && s[i] == '}') { i++; return o } }; '}' -> { i++; return o }; else -> err("Expected a ',' or '}'") }
        }
    }
    fun arr(): JSONArray {
        val a = JSONArray(); i++; ws()
        if (i < s.length && s[i] == ']') { i++; return a }
        while (true) {
            ws()
            if (i < s.length && s[i] == ',') { a.l.add(JSONObject.NULL); i++; continue }
            a.l.add(value()); ws()
            if (i >= s.length) err("Expected a ',' or ']'")
            when (s[i]) { ',' -> { i++; ws(); if (i < s.length && s[i] == ']') { i++; return a } }; ']' -> { i++; return a }; else -> err("Expected a ',' or ']'") }
        }
    }
    fun str(q: Char): String {
        i++; val sb = StringBuilder()
        while (true) {
            if (i >= s.length) err("Unterminated string")
            val c = s[i++]
            when {
                c == q -> return sb.toString()
                c == '\\' -> {
                    if (i >= s.length) err("Unterminated string")
                    when (val e = s[i++]) {
                        'b' -> sb.append('\b'); 't' -> sb.append('\t'); 'n' -> sb.append('\n'); 'f' -> sb.append('\u000C'); 'r' -> sb.append('\r')
                        'u' -> { if (i + 4 > s.length) err("Illegal escape"); sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> sb.append(e)
                    }
                }
                else -> sb.append(c)
            }
        }
    }
    fun lit(): Any? {
        val st = i
        while (i < s.length && s[i] !in ",:]}/\\\"[{;=#" && !s[i].isWhitespace()) i++
        val t = s.substring(st, i)
        if (t.isEmpty()) err("Missing value")
        when (t) { "true" -> return true; "false" -> return false; "null" -> return JSONObject.NULL }
        val c = t[0]
        if (c.isDigit() || c == '-' || c == '.') {
            if (t.none { it == '.' || it == 'e' || it == 'E' }) {
                t.toIntOrNull()?.let { return it }
                t.toLongOrNull()?.let { return it }
            }
            t.toDoubleOrNull()?.let { return it }
        }
        return t
    }
}
