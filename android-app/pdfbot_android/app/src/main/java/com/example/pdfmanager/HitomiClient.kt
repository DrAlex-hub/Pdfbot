package com.example.pdfmanager

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Obtiene metadatos públicos de Hitomi.la a partir del nombre del PDF.
 * Solo devuelve coincidencias suficientemente parecidas y descarta galerías
 * que incluyan etiquetas asociadas a menores.
 */
object HitomiClient {
    private val forbidden = setOf(
        "loli", "lolicon", "shota", "shotacon", "underage", "child",
        "child_abuse", "young", "schoolboy", "schoolgirl"
    )

    data class Match(val title: String, val tags: List<String>, val url: String)

    fun findByPdfName(fileName: String): Match? {
        val query = cleanTitle(fileName)
        if (query.length < 3) return null
        val encoded = URLEncoder.encode(query, "UTF-8")
        val html = request("https://hitomi.la/search.html?q=$encoded") ?: return null
        val ids = Regex("""-(\\d{3,})\\.html""")
            .findAll(html).map { it.groupValues[1] }.distinct().take(12).toList()
        if (ids.isEmpty()) return null

        val wanted = tokens(query)
        var best: Match? = null
        var bestScore = 0.0
        for (id in ids) {
            val raw = request("https://ltn.hitomi.la/galleries/$id.js") ?: continue
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start < 0 || end <= start) continue
            val info = try { JSONObject(raw.substring(start, end + 1)) } catch (_: Exception) { continue }
            val title = info.optString("title").ifBlank { info.optString("japanese_title") }
            if (title.isBlank()) continue
            val score = similarity(wanted, tokens(title))
            if (score > bestScore) {
                val tags = extractTags(info)
                if (tags.any { tag -> val n=normalize(tag); forbidden.any { n.contains(it) } }) continue
                bestScore = score
                best = Match(title, tags, "https://hitomi.la/reader/$id.html")
            }
        }
        return if (bestScore >= 0.42) best else null
    }

    private fun request(address: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(address).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12000
                readTimeout = 18000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                setRequestProperty("Referer", "https://hitomi.la/")
            }
            if (connection.responseCode !in 200..299) null
            else connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun extractTags(info: JSONObject): List<String> {
        val result = linkedSetOf<String>()
        val arrays = listOf("tags", "artists", "groups", "parodys", "characters")
        for (key in arrays) {
            val values = info.optJSONArray(key) ?: continue
            for (i in 0 until values.length()) {
                val item = values.optJSONObject(i)
                val value = item?.optString("tag")?.takeIf { !it.isNullOrBlank() }
                    ?: item?.optString("name")?.takeIf { !it.isNullOrBlank() }
                    ?: values.optString(i).takeIf { it.isNotBlank() }
                if (value != null) result.add(value)
            }
        }
        return result.toList()
    }

    private fun cleanTitle(fileName: String): String {
        return fileName.substringBeforeLast('.', fileName)
            .replace(Regex("""[._]+"""), " ")
            .replace(Regex("""\\[[^]]*]"""), " ")
            .replace(Regex("""\\([^)]*\\)"""), " ")
            .replace(Regex("""[^\\p{L}\\p{N} ]"""), " ")
            .replace(Regex("""\\s+"""), " ").trim()
    }

    private fun tokens(value: String): Set<String> =
        normalize(value).split(Regex("""[^a-z0-9]+"""))
            .filter { it.length >= 2 }.toSet()

    private fun normalize(value: String): String =
        java.text.Normalizer.normalize(value.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD)
            .replace(Regex("""\\p{Mn}+"""), "")

    private fun similarity(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return a.intersect(b).size.toDouble() / a.union(b).size.toDouble()
    }

    fun hashtags(tags: List<String>): String {
        return tags.mapNotNull { tag ->
            val safe = normalize(tag).replace(Regex("""[^a-z0-9]+"""), "_").trim('_')
            if (safe.length < 2 || forbidden.contains(safe)) null else "#$safe"
        }.distinct().take(25).joinToString(" ")
    }
}
