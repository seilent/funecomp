import java.io.File
import java.security.MessageDigest

class GenException(message: String) : Exception(message)

object Gen {
    val pinned = listOf("proto.txt", "vectors.txt", "tokens.txt", "tokens-v1.txt")
    private val keywords = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is",
        "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof",
        "val", "var", "when", "while", "enum", "open", "data", "inner", "value", "const",
    )
    const val pkg = "net.seilent.funecomp.proto.gen"

    fun sha256(f: File): String =
        MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    class Pin(val spec: String, val tokens: String, val sums: Map<String, String>)

    fun readPin(f: File): Pin {
        var spec: String? = null
        var tokens: String? = null
        val sums = LinkedHashMap<String, String>()
        f.readLines().filter { it.isNotBlank() }.forEach { line ->
            val p = line.trim().split(Regex("\\s+"))
            if (p.size != 2) throw GenException("PIN: bad line '$line'")
            when (p[0]) {
                "commit.spec" -> spec = p[1]
                "commit.tokens" -> tokens = p[1]
                else -> sums[p[0]] = p[1]
            }
        }
        return Pin(spec ?: throw GenException("PIN: no commit.spec line"), tokens ?: throw GenException("PIN: no commit.tokens line"), sums)
    }

    fun checkPin(dir: File, allowDraft: Boolean) {
        val pin = readPin(File(dir, "PIN"))
        for (name in pinned) {
            val f = File(dir, name)
            val want = pin.sums[name] ?: throw GenException("checkPin: PIN has no sha256 for third_party/funeos/$name")
            if (!f.isFile) throw GenException("checkPin: third_party/funeos/$name is missing")
            val got = sha256(f)
            if (got != want) throw GenException("checkPin: third_party/funeos/$name sha256 $got does not match PIN $want")
        }
        if (!allowDraft && (pin.spec == "draft" || pin.tokens == "draft"))
            throw GenException("checkPin: PIN commit is draft, run tools/pin.sh <funeos commit>")
    }

    private fun fields(f: File) = f.readLines().mapIndexedNotNull { i, line ->
        val p = line.trim().split(Regex("\\s+"))
        if (line.isBlank()) null else Pair(i + 1, p)
    }

    private fun camel(name: String) = name.split('_', '.', '-').filter { it.isNotEmpty() }
        .joinToString("") { seg -> (if (seg == seg.uppercase()) seg.lowercase() else seg).replaceFirstChar(Char::uppercase) }

    private fun ident(name: String) = if (name in keywords) "`$name`" else name

    private fun intLiteral(v: String): Long? = when {
        v.startsWith("0x") -> v.substring(2).toLongOrNull(16)
        else -> v.toLongOrNull()
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""

    private val bases = setOf("u8", "u16", "u32", "u64", "i32", "i64", "bool", "str", "bytes")

    fun protoKt(dir: File): String {
        val pin = readPin(File(dir, "PIN"))
        val consts = ArrayList<Pair<String, String>>()
        val msgs = ArrayList<List<String>>()
        val errs = ArrayList<Pair<String, Int>>()
        val features = ArrayList<Pair<String, Int>>()
        val enums = LinkedHashMap<String, MutableList<Pair<Int, String>>>()
        val scopes = LinkedHashMap<String, MutableList<List<String>>>()
        File(dir, "proto.txt").readLines().forEachIndexed { i, line ->
            fun bad(why: String): Nothing = throw GenException("proto.txt:${i + 1}: $why")
            val p = line.split(' ')
            if (p.any { it.isEmpty() }) bad("empty field or blank line")
            val want = mapOf("const" to 3, "err" to 3, "feature" to 3, "enum" to 4, "msg" to 5, "tag" to 6)[p[0]]
                ?: bad("unknown line kind ${p[0]}")
            if (p.size != want) bad("${p[0]} needs $want fields, has ${p.size}")
            when (p[0]) {
                "const" -> consts += p[1] to p[2]
                "err" -> errs += p[1] to (p[2].toIntOrNull()?.takeIf { it in 1..255 } ?: bad("code ${p[2]}"))
                "feature" -> features += p[1] to (p[2].toIntOrNull()?.takeIf { it in 0..63 } ?: bad("bit ${p[2]}"))
                "enum" -> enums.getOrPut(p[1]) { ArrayList() } += (p[2].toIntOrNull()?.takeIf { it in 0..255 } ?: bad("value ${p[2]}")) to p[3]
                "msg" -> {
                    intLiteral(p[2])?.takeIf { it in 0..255 } ?: bad("id ${p[2]}")
                    if (p[3] !in setOf("app", "dev", "both")) bad("dir ${p[3]}")
                    if (p[4] !in setOf("req", "push")) bad("mode ${p[4]}")
                    msgs += p.drop(1)
                }
                "tag" -> {
                    p[2].toIntOrNull()?.takeIf { it in 1..255 } ?: bad("tag ${p[2]}")
                    if (p[5] !in setOf("one", "list")) bad("card ${p[5]}")
                    scopes.getOrPut(p[1]) { ArrayList() } += p.drop(2)
                }
            }
        }
        fun unique(what: String, items: List<Any>) {
            val dup = items.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            if (dup.isNotEmpty()) throw GenException("proto.txt: duplicate $what ${dup.joinToString()}")
        }
        unique("err name", errs.map { it.first })
        unique("err code", errs.map { it.second })
        unique("feature name", features.map { it.first })
        unique("feature bit", features.map { it.second })
        for ((name, values) in enums) {
            unique("$name value", values.map { it.first })
            unique("$name word", values.map { it.second })
        }
        val msgNames = msgs.map { it[0] }.toSet()
        if (msgNames.size != msgs.size || msgs.map { intLiteral(it[1]) }.toSet().size != msgs.size)
            throw GenException("proto.txt: duplicate msg name or id")
        fun isRecord(s: String) = s.first().isUpperCase() && s.any { it.isLowerCase() }
        for ((scope, tags) in scopes) {
            val base = scope.removeSuffix(".reply")
            if (!(scope == "error" || isRecord(scope) || base in msgNames))
                throw GenException("proto.txt: scope $scope is no msg, reply, error or record")
            if (tags.map { it[0] }.toSet().size != tags.size || tags.map { it[1] }.toSet().size != tags.size)
                throw GenException("proto.txt: duplicate tag or field in $scope")
            for (t in tags) {
                val type = t[2]
                val ok = type in bases || type in enums || (isRecord(type) && type in scopes)
                if (!ok) throw GenException("proto.txt: $scope ${t[1]} has unknown type $type")
            }
        }
        val objects = scopes.keys.map { camel(it) }
        if (objects.toSet().size != objects.size) throw GenException("proto.txt: scope names collide in Kotlin")

        val o = StringBuilder()
        o.append("package $pkg\n\n")
        o.append("enum class Base { U8, U16, U32, U64, I32, I64, BOOL, STR, BYTES, ENUM, RECORD }\n\n")
        o.append("class Field(val tag: Int, val name: String, val base: Base, val ref: String?, val list: Boolean)\n\n")
        o.append("class MsgInfo(val id: Int, val name: String, val dir: String, val push: Boolean)\n\n")
        o.append("object Pin {\n")
        o.append("    const val COMMIT = ${quote(pin.spec)}\n")
        for (name in pinned) o.append("    const val ${camel(name.removeSuffix(".txt")).uppercase()}_SHA256 = ${quote(pin.sums[name] ?: "")}\n")
        o.append("}\n\n")
        o.append("object Proto {\n")
        for ((k, v) in consts) {
            val n = intLiteral(v)
            when {
                n != null && n in Int.MIN_VALUE..Int.MAX_VALUE -> o.append("    const val ${ident(k)} = $v\n")
                n != null -> o.append("    const val ${ident(k)} = ${v}L\n")
                Regex("^[0-9]+(,[0-9]+)+$").matches(v) -> o.append("    val ${ident(k)}: List<Long> = listOf(${v.split(',').joinToString(", ") { it + "L" }})\n")
                else -> o.append("    const val ${ident(k)} = ${quote(v)}\n")
            }
        }
        o.append("}\n\n")
        o.append("object Msg {\n")
        for (m in msgs) o.append("    const val ${m[0]} = ${m[1]}\n")
        o.append("    val info: Map<Int, MsgInfo> = mapOf(\n")
        for (m in msgs) o.append("        ${m[0]} to MsgInfo(${m[0]}, ${quote(m[0])}, ${quote(m[2])}, ${m[3] == "push"}),\n")
        o.append("    )\n")
        o.append("    val byName: Map<String, Int> = info.values.associate { it.name to it.id }\n")
        o.append("}\n\n")
        o.append("object Err {\n")
        for ((name, code) in errs) o.append("    const val ${ident(name)} = $code\n")
        o.append("    val names: Map<Int, String> = mapOf(${errs.joinToString(", ") { "${it.second} to ${quote(it.first)}" }})\n")
        o.append("}\n\n")
        o.append("object Feature {\n")
        for ((name, bit) in features) o.append("    const val ${ident(name)} = $bit\n")
        o.append("    val names: Map<Int, String> = mapOf(${features.joinToString(", ") { "${it.second} to ${quote(it.first)}" }})\n")
        o.append("}\n\n")
        o.append("object Enums {\n")
        for ((name, values) in enums) {
            o.append("    object ${camel(name)} {\n")
            for ((v, word) in values) o.append("        const val ${ident(word)} = $v\n")
            o.append("        val names: Map<Int, String> = mapOf(${values.joinToString(", ") { "${it.first} to ${quote(it.second)}" }})\n")
            o.append("    }\n")
        }
        o.append("    val byName: Map<String, Map<Int, String>> = mapOf(\n")
        for (name in enums.keys) o.append("        ${quote(name)} to ${camel(name)}.names,\n")
        o.append("    )\n")
        o.append("}\n\n")
        o.append("object Tags {\n")
        for ((scope, tags) in scopes) {
            o.append("    object ${camel(scope)} {\n")
            for (t in tags) o.append("        const val ${ident(t[1])} = ${t[0]}\n")
            o.append("    }\n")
        }
        o.append("    val scopes: Map<String, Map<Int, Field>> = mapOf(\n")
        for ((scope, tags) in scopes) {
            o.append("        ${quote(scope)} to mapOf(\n")
            for (t in tags) {
                val type = t[2]
                val (base, ref) = when {
                    type in bases -> type.uppercase() to "null"
                    type in enums -> "ENUM" to quote(type)
                    else -> "RECORD" to quote(type)
                }
                o.append("            ${t[0]} to Field(${t[0]}, ${quote(t[1])}, Base.$base, $ref, ${t[3] == "list"}),\n")
            }
            o.append("        ),\n")
        }
        o.append("    )\n")
        o.append("}\n")
        return o.toString()
    }

    private class Tokens(val main: List<List<String>>, val v1: List<List<String>>, val board: Set<String>)

    private val number = Regex("^-?[0-9]+(\\.[0-9]+)?$")
    private val color = Regex("^#[0-9A-Fa-f]{6}$")

    private class Lit(val type: String, val text: String)

    private fun lit(v: String) = when {
        number.matches(v) -> Lit("Float", "${v}f")
        color.matches(v) -> Lit("Long", "0xFF${v.substring(1).uppercase()}L")
        else -> Lit("String", quote(v))
    }

    private fun tokens(dir: File): Tokens {
        val main = fields(File(dir, "tokens.txt"))
        val v1 = fields(File(dir, "tokens-v1.txt"))
        for ((n, p) in main + v1) if (p.size != 3) throw GenException("tokens: line $n has ${p.size} fields, needs 3")
        if (main.map { it.second[0] } != v1.map { it.second[0] })
            throw GenException("tokens: tokens.txt and tokens-v1.txt name different tokens or order them differently")
        val board = main.indices.filter { main[it].second != v1[it].second }.map { main[it].second[0] }.toSet()
        return Tokens(main.map { it.second }, v1.map { it.second }, board)
    }

    fun boardTokens(dir: File): Set<String> = tokens(dir).board

    private fun tokId(name: String) = ident(name.replace('.', '_'))

    fun tokKt(dir: File): String {
        val t = tokens(dir)
        val o = StringBuilder()
        o.append("package $pkg\n\n")
        o.append("object Tok {\n")
        for (p in t.main) {
            if (p[0] in t.board) continue
            o.append("    const val ${tokId(p[0])} = ${lit(p[1]).text}\n")
        }
        o.append("}\n\n")
        o.append("object TokUnit {\n")
        for (p in t.main) {
            if (p[0] in t.board) continue
            o.append("    const val ${tokId(p[0])} = ${quote(p[2])}\n")
        }
        o.append("}\n")
        return o.toString()
    }

    fun tokBoardKt(dir: File): String {
        val t = tokens(dir)
        val main = t.main.filter { it[0] in t.board }
        val v1 = t.v1.filter { it[0] in t.board }
        val types = main.indices.map { i -> lit(main[i][1]).type.takeIf { it == lit(v1[i][1]).type } ?: "String" }
        fun board(rows: List<List<String>>) = rows.indices.joinToString("") { i ->
            val v = rows[i][1]
            "        ${tokId(rows[i][0])} = ${if (types[i] == "String") quote(v) else lit(v).text},\n"
        }
        val o = StringBuilder()
        o.append("package $pkg\n\n")
        o.append("object TokBoard {\n")
        o.append("    class Board(\n")
        for (i in main.indices) o.append("        val ${tokId(main[i][0])}: ${types[i]},\n")
        o.append("    )\n\n")
        o.append("    val ap80 = Board(\n${board(main)}    )\n\n")
        o.append("    val v1 = Board(\n${board(v1)}    )\n\n")
        o.append("    fun of(board: String): Board = when (board) {\n")
        o.append("        \"ap80\" -> ap80\n")
        o.append("        \"v1\" -> v1\n")
        o.append("        else -> throw IllegalArgumentException(\"board \$board\")\n")
        o.append("    }\n")
        o.append("}\n")
        return o.toString()
    }
}
