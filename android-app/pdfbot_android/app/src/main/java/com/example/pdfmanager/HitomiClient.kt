package com.example.pdfmanager

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale

/**
 * Busca títulos con el mismo índice B-tree que usa el buscador de Hitomi.
 * El índice se consulta con HTTP Range; luego se descargan los metadatos .js.
 */
object HitomiClient {
    private const val HOST = "https://ltn.gold-usergeneratedcontent.net"
    private const val BRANCHING = 16
    private val forbidden = setOf(
        "loli", "lolicon", "shota", "shotacon", "underage", "child",
        "child_abuse", "young", "schoolboy", "schoolgirl"
    )

    data class Match(val title: String, val tags: List<String>, val url: String)
    private data class Pointer(val offset: Long, val length: Int)
    private data class Node(
        val keys: List<ByteArray>,
        val data: List<Pointer>,
        val children: List<Long>
    )

    fun findByPdfName(fileName: String): Match? {
        val wanted = tokens(cleanTitle(fileName))
        if (wanted.isEmpty()) return null
        val version = requestText("$HOST/galleriesindex/version")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null

        val idSets = wanted.take(8).map { term ->
            val pointer = findPointer(version, shaPrefix(term)) ?: return@map emptySet<Long>()
            readGalleryIds(version, pointer)
        }.filter { it.isNotEmpty() }
        if (idSets.isEmpty()) return null

        val candidates = idSets.reduce { acc, ids -> acc.intersect(ids) }
            .take(30)
        if (candidates.isEmpty()) return null

        var best: Match? = null
        var bestScore = 0.0
        for (id in candidates) {
            val info = fetchGallery(id.toString()) ?: continue
            val title = info.optString("title").ifBlank { info.optString("japanese_title") }
            if (title.isBlank()) continue
            val tags = extractTags(info)
            if (tags.any { tag ->
                val normalized = normalize(tag)
                forbidden.any { normalized.contains(it) }
            }) continue
            val score = similarity(wanted, tokens(title))
            if (score > bestScore) {
                bestScore = score
                best = Match(title, tags, "https://hitomi.la/reader/$id.html")
            }
        }
        return best?.takeIf { bestScore >= 0.25 }
    }

    private fun shaPrefix(term: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(term.toByteArray(Charsets.UTF_8)).copyOfRange(0, 4)

    private fun findPointer(version: String, key: ByteArray): Pointer? {
        val indexUrl = "$HOST/galleriesindex/galleries.$version.index"
        var address = 0L
        repeat(40) {
            val bytes = requestRange(indexUrl, address, 4095) ?: return null
            val node = parseNode(bytes) ?: return null
            var childIndex = 0
            for (i in node.keys.indices) {
                val cmp = compareUnsigned(key, node.keys[i])
                if (cmp == 0) return node.data.getOrNull(i)
                if (cmp < 0) { childIndex = i; break }
                childIndex = i + 1
            }
            val next = node.children.getOrNull(childIndex) ?: return null
            if (next == 0L) return null
            address = next
        }
        return null
    }

    private fun parseNode(bytes: ByteArray): Node? = try {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val keyCount = b.int
        if (keyCount !in 0..64) return null
        val keys = ArrayList<ByteArray>(keyCount)
        repeat(keyCount) {
            val size = b.int
            if (size !in 1..128 || size > b.remaining()) return null
            val key = ByteArray(size); b.get(key); keys.add(key)
        }
        val dataCount = b.int
        if (dataCount !in 0..64) return null
        val data = ArrayList<Pointer>(dataCount)
        repeat(dataCount) { data.add(Pointer(b.long, b.int)) }
        val children = ArrayList<Long>(BRANCHING + 1)
        repeat(BRANCHING + 1) { children.add(b.long) }
        Node(keys, data, children)
    } catch (_: Exception) { null }

    private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val x = a[i].toInt() and 255
            val y = b[i].toInt() and 255
            if (x != y) return x.compareTo(y)
        }
        return a.size.compareTo(b.size)
    }

    private fun readGalleryIds(version: String, pointer: Pointer): Set<Long> {
        if (pointer.length !in 4..2_000_000) return emptySet()
        val bytes = requestRange(
            "$HOST/galleriesindex/galleries.$version.data",
            pointer.offset,
            pointer.length - 1
        ) ?: return emptySet()
        if (bytes.size < 4) return emptySet()
        return try {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val count = b.int
            if (count < 0 || count > (bytes.size - 4) / 4) return emptySet()
            buildSet { repeat(count) { add(b.int.toLong() and 0xffffffffL) } }
        } catch (_: Exception) { emptySet() }
    }

    private fun fetchGallery(id: String): JSONObject? {
        val raw = requestText("$HOST/galleries/$id.js") ?: return null
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try { JSONObject(raw.substring(start, end + 1)) } catch (_: Exception) { null }
    }

    private fun requestRange(address: String, start: Long, length: Int): ByteArray? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(address).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12000
                readTimeout = 20000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                setRequestProperty("Referer", "https://hitomi.la/")
                setRequestProperty("Range", "bytes=$start-${start + length}")
            }
            if (connection.responseCode !in 200..299) null
            else connection.inputStream.use { it.readBytes() }
        } catch (_: Exception) { null } finally { connection?.disconnect() }
    }

    private fun requestText(address: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(address).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12000
                readTimeout = 20000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                setRequestProperty("Referer", "https://hitomi.la/")
            }
            if (connection.responseCode !in 200..299) null
            else connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) { null } finally { connection?.disconnect() }
    }

    private fun extractTags(info: JSONObject): List<String> {
        val result = linkedSetOf<String>()
        val fields = mapOf(
            "tags" to "tag", "artists" to "artist", "groups" to "group",
            "parodys" to "parody", "characters" to "character"
        )
        for ((arrayName, valueName) in fields) {
            val values = info.optJSONArray(arrayName) ?: continue
            for (i in 0 until values.length()) {
                val item = values.optJSONObject(i) ?: continue
                val value = item.optString(valueName).takeIf { it.isNotBlank() } ?: continue
                result.add(value)
            }
        }
        return result.toList()
    }

    private fun cleanTitle(fileName: String): String =
        fileName.substringBeforeLast('.', fileName)
            .replace(Regex("""[._]+"""), " ")
            .replace(Regex("""\[[^]]*]"""), " ")
            .replace(Regex("""\([^)]*\)"""), " ")
            .replace(Regex("""[^\p{L}\p{N} ]"""), " ")
            .replace(Regex("""\s+"""), " ").trim()

    private fun tokens(value: String): Set<String> =
        normalize(value).split(Regex("""[^\\p{L}\\p{N}]+""")).filter { it.length >= 2 }.toSet()

    private fun normalize(value: String): String =
        java.text.Normalizer.normalize(value.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")

    private fun similarity(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return a.intersect(b).size.toDouble() / a.union(b).size.toDouble()
    }

    fun hashtags(tags: List<String>): String =
        tags.mapNotNull { tag ->
            val safe = normalize(tag).replace(Regex("""[^\\p{L}\\p{N}]+"""), "_").trim('_')
            if (safe.length < 2 || forbidden.any { safe.contains(it) }) null else "#$safe"
        }.distinct().take(25).joinToString(" ")
}
