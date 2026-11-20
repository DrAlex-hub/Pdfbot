package com.example.pdfmanager

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Busca galerías usando el índice Nozomi de Hitomi y obtiene sus metadatos.
 * No depende del HTML dinámico de search.html: ese HTML no contiene los
 * resultados porque los carga JavaScript después de abrir la página.
 */
object HitomiClient {
    private const val INDEX_HOST = "https://ltn.gold-usergeneratedcontent.net"
    private val forbidden = setOf(
        "loli", "lolicon", "shota", "shotacon", "underage", "child",
        "child_abuse", "young", "schoolboy", "schoolgirl"
    )

    data class Match(val title: String, val tags: List<String>, val url: String)

    fun findByPdfName(fileName: String): Match? {
        val query = cleanTitle(fileName)
        val wanted = tokens(query)
        if (wanted.isEmpty()) return null

        // El índice Nozomi devuelve IDs binarios de 4 bytes, no enlaces HTML.
        // Se consultan varios términos y se priorizan los IDs comunes.
        val terms = wanted.sortedByDescending { it.length }.take(4)
        val resultSets = terms.mapNotNull { term ->
            val bytes = requestBytes("$INDEX_HOST/n/${pathEncode(term)}-all.nozomi") ?: return@mapNotNull null
            decodeIds(bytes).takeIf { it.isNotEmpty() }
        }
        if (resultSets.isEmpty()) return null

        val counts = HashMap<Long, Int>()
        resultSets.forEach { set -> set.forEach { id -> counts[id] = (counts[id] ?: 0) + 1 } }
        val candidates = counts.entries
            .sortedWith(compareByDescending<Map.Entry<Long, Int>> { it.value }.thenByDescending { it.key })
            .take(30)
            .map { it.key }

        var best: Match? = null
        var bestScore = 0.0
        for (id in candidates) {
            val info = fetchGallery(id.toString()) ?: continue
            val title = info.optString("title").ifBlank { info.optString("japanese_title") }
            if (title.isBlank()) continue
            val tags = extractTags(info)
            // Bloqueo conservador: si cualquier etiqueta señala menores, no se publica.
            if (tags.any { tag -> val normalized = normalize(tag); forbidden.any { normalized.contains(it) } }) continue
            val score = similarity(wanted, tokens(title))
            if (score > bestScore) {
                bestScore = score
                best = Match(title, tags, "https://hitomi.la/reader/$id.html")
            }
        }
        return best?.takeIf { bestScore >= 0.25 }
    }

    private fun fetchGallery(id: String): JSONObject? {
        val raw = requestText("$INDEX_HOST/galleries/$id.js") ?: return null
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try { JSONObject(raw.substring(start, end + 1)) } catch (_: Exception) { null }
    }

    private fun requestBytes(address: String): ByteArray? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(address).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12000
                readTimeout = 20000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                setRequestProperty("Referer", "https://hitomi.la/")
                setRequestProperty("Accept-Encoding", "identity")
            }
            if (connection.responseCode !in 200..299) null
            else connection.inputStream.use { it.readBytes() }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun requestText(address: String): String? =
        requestBytes(address)?.toString(Charsets.UTF_8)

    private fun decodeIds(bytes: ByteArray): List<Long> {
        val usable = bytes.size - (bytes.size % 4)
        if (usable == 0) return emptyList()
        val buffer = ByteBuffer.wrap(bytes, 0, usable).order(ByteOrder.BIG_ENDIAN)
        val ids = ArrayList<Long>(usable / 4)
        while (buffer.remaining() >= 4) ids.add(buffer.int.toLong() and 0xffffffffL)
        return ids
    }

    private fun pathEncode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun extractTags(info: JSONObject): List<String> {
        val result = linkedSetOf<String>()
        for (key in listOf("tags", "artists", "groups", "parodys", "characters")) {
            val values = info.optJSONArray(key) ?: continue
            for (i in 0 until values.length()) {
                val item = values.optJSONObject(i)
                val value = item?.optString("tag")?.takeIf { it.isNotBlank() }
                    ?: item?.optString("name")?.takeIf { it.isNotBlank() }
                    ?: values.optString(i).takeIf { it.isNotBlank() }
                if (value != null) result.add(value)
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
        normalize(value).split(Regex("""[^a-z0-9]+""")).filter { it.length >= 2 }.toSet()

    private fun normalize(value: String): String =
        java.text.Normalizer.normalize(value.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")

    private fun similarity(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return a.intersect(b).size.toDouble() / a.union(b).size.toDouble()
    }

    fun hashtags(tags: List<String>): String =
        tags.mapNotNull { tag ->
            val safe = normalize(tag).replace(Regex("""[^a-z0-9]+"""), "_").trim('_')
            if (safe.length < 2 || forbidden.any { safe.contains(it) }) null else "#$safe"
        }.distinct().take(25).joinToString(" ")
}
