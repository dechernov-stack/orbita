// Текстовый индекс базы знаний (шип 4 §2, РЕШЕНИЕ-СЛОВАРЬ-И-БАЗА-ЗНАНИЙ §3):
// полнотекст Postgres (русская конфигурация) в таблице ядра и вектор pgvector,
// когда расширение есть. Гибридная выборка: лексическая оценка ts_rank_cd и
// косинусная близость вектора, свёрнутые в одну; фильтры — точное совпадение
// полей блока (проект · роль документа · ранг · сцена · класс).
//
// Без расширения (образ postgres без pgvector) индекс честно работает
// лексически: `vectorReady = false`, семантической оценки у находок нет.
package orbita.kernel.internal

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.kernel.api.IndexBlock
import orbita.kernel.api.IndexHit
import orbita.kernel.api.TextIndex
import java.sql.Connection
import java.sql.ResultSet

class PgTextIndex(
    private val conn: Connection,
    private val mapper: ObjectMapper = ObjectMapper(),
    override val vectorDim: Int = 1024,
) : TextIndex {

    override val vectorReady: Boolean = подготовитьВектор()

    /** Расширение pgvector: колонка вектора появляется, когда оно доступно; иначе — лексика. */
    private fun подготовитьВектор(): Boolean {
        val доступно = conn.createStatement().use { st ->
            st.executeQuery("SELECT count(*) FROM pg_available_extensions WHERE name = 'vector'").use { rs -> rs.next() && rs.getInt(1) > 0 }
        }
        if (!доступно) return false
        return runCatching {
            val было = conn.autoCommit
            conn.createStatement().use { st ->
                st.execute("CREATE EXTENSION IF NOT EXISTS vector")
                st.execute("ALTER TABLE orbita_kernel.index_block ADD COLUMN IF NOT EXISTS embedding vector($vectorDim)")
            }
            if (!было) conn.commit()
            true
        }.getOrElse { runCatching { if (!conn.autoCommit) conn.rollback() }; false }
    }

    override fun upsert(blocks: List<IndexBlock>): Int {
        if (blocks.isEmpty()) return 0
        var записано = 0
        conn.prepareStatement(
            """
            INSERT INTO orbita_kernel.index_block (key, kind, area, ref, title, text, filters, fingerprint, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, now())
            ON CONFLICT (key) DO UPDATE SET
                kind = EXCLUDED.kind, area = EXCLUDED.area, ref = EXCLUDED.ref, title = EXCLUDED.title,
                text = EXCLUDED.text, filters = EXCLUDED.filters, fingerprint = EXCLUDED.fingerprint, updated_at = now()
                ${if (vectorReady) ", embedding = NULL" else ""}
            WHERE orbita_kernel.index_block.fingerprint IS DISTINCT FROM EXCLUDED.fingerprint
            """.trimIndent(),
        ).use { st ->
            blocks.forEach { б ->
                st.setString(1, б.key); st.setString(2, б.kind); st.setString(3, б.area); st.setString(4, б.ref)
                st.setString(5, б.title); st.setString(6, б.text)
                st.setString(7, mapper.writeValueAsString(б.filters)); st.setString(8, б.fingerprint)
                записано += st.executeUpdate()
            }
        }
        return записано
    }

    override fun removeMissing(area: String, kind: String, keep: Set<String>): Int {
        val ключи = conn.prepareStatement("SELECT key FROM orbita_kernel.index_block WHERE area = ? AND kind = ?").use { st ->
            st.setString(1, area); st.setString(2, kind)
            st.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
        }
        val лишние = ключи.filter { it !in keep }
        if (лишние.isEmpty()) return 0
        conn.prepareStatement("DELETE FROM orbita_kernel.index_block WHERE key = ?").use { st ->
            лишние.forEach { st.setString(1, it); st.addBatch() }
            st.executeBatch()
        }
        return лишние.size
    }

    override fun withoutVector(area: String?, limit: Int): List<IndexBlock> {
        if (!vectorReady) return emptyList()
        val sql = "SELECT key, kind, area, ref, title, text, filters, fingerprint FROM orbita_kernel.index_block " +
            "WHERE embedding IS NULL" + (if (area != null) " AND area = ?" else "") + " ORDER BY updated_at LIMIT ?"
        return conn.prepareStatement(sql).use { st ->
            var i = 1
            if (area != null) st.setString(i++, area)
            st.setInt(i, limit)
            st.executeQuery().use { rs -> generateSequence { if (rs.next()) блок(rs) else null }.toList() }
        }
    }

    override fun setVector(key: String, vector: FloatArray) {
        if (!vectorReady) return
        conn.prepareStatement("UPDATE orbita_kernel.index_block SET embedding = ?::vector WHERE key = ?").use { st ->
            st.setString(1, vector.joinToString(",", "[", "]")); st.setString(2, key); st.executeUpdate()
        }
    }

    override fun search(
        query: String, areas: List<String>, filters: Map<String, String>, kinds: List<String>, vector: FloatArray?, limit: Int,
    ): List<IndexHit> {
        if (areas.isEmpty()) return emptyList()
        // Слова запроса — ИЛИ по основам: «интервал передачи» находит и пункт с
        // «интервалом», и факт с «интервалом передачи»; оба слова — выше рангом.
        val слова = тсЗапрос(query)
        val семантика = vectorReady && vector != null
        if (слова.isBlank() && !семантика) return emptyList()
        val условия = mutableListOf("area = ANY (?)")
        val параметры = mutableListOf<Any>(areas)
        if (kinds.isNotEmpty()) { условия += "kind = ANY (?)"; параметры.add(kinds) }
        if (filters.isNotEmpty()) { условия += "filters @> ?::jsonb"; параметры.add(mapper.writeValueAsString(filters)) }
        val лексика = if (слова.isBlank()) "0.0" else "ts_rank_cd(tsv, to_tsquery('russian', ?))"
        val смысл = if (семантика) "1 - (embedding <=> ?::vector)" else "NULL"
        val где = if (слова.isBlank()) "" else if (семантика) "AND (tsv @@ to_tsquery('russian', ?) OR embedding IS NOT NULL)" else "AND tsv @@ to_tsquery('russian', ?)"
        val sql = """
            SELECT key, kind, area, ref, title, text, filters,
                   $лексика AS lexical, $смысл AS semantic
            FROM orbita_kernel.index_block
            WHERE ${условия.joinToString(" AND ")} $где
            ORDER BY (coalesce($лексика, 0) * 2 + coalesce($смысл, 0)) DESC
            LIMIT ?
        """.trimIndent()
        return conn.prepareStatement(sql).use { st ->
            var i = 1
            fun строка(v: String) { st.setString(i++, v) }
            fun массив(v: List<String>) { st.setArray(i++, conn.createArrayOf("text", v.toTypedArray())) }
            // порядок ? в SQL: SELECT (лексика?, смысл?), WHERE (области, виды?, фильтры?, слова?), ORDER BY (лексика?, смысл?), LIMIT
            if (слова.isNotBlank()) строка(слова)
            if (семантика) строка(vector!!.joinToString(",", "[", "]"))
            параметры.forEach { п -> if (п is List<*>) массив(п.map { it.toString() }) else строка(п.toString()) }
            if (слова.isNotBlank()) строка(слова)
            if (слова.isNotBlank()) строка(слова)
            if (семантика) строка(vector!!.joinToString(",", "[", "]"))
            st.setInt(i, limit)
            st.executeQuery().use { rs ->
                generateSequence {
                    if (!rs.next()) null else {
                        val лекс = rs.getDouble("lexical")
                        val сем = rs.getObject("semantic")?.let { (it as Number).toDouble() }
                        val текст = rs.getString("text") ?: ""
                        IndexHit(
                            key = rs.getString("key"), kind = rs.getString("kind"), area = rs.getString("area"), ref = rs.getString("ref"),
                            title = rs.getString("title") ?: "", snippet = отрывок(текст, query),
                            score = лекс * 2 + (сем ?: 0.0), lexical = лекс, semantic = сем,
                            filters = фильтры(rs.getString("filters")),
                        )
                    }
                }.toList()
            }
        }
    }

    override fun count(area: String): Map<String, Int> =
        conn.prepareStatement("SELECT kind, count(*) FROM orbita_kernel.index_block WHERE area = ? GROUP BY kind").use { st ->
            st.setString(1, area)
            st.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) to rs.getInt(2) else null }.toMap() }
        }

    /**
     * Запрос tsquery: слова через ИЛИ, каждое — основой с префиксом `:*`.
     * Стеммер русского режет по-разному падежи одного слова («интервал» →
     * `интерва`, «интервалом» → `интервал`); префикс по основе запроса ловит
     * обе формы. Слова из двух букв — точно, без префикса.
     */
    private fun тсЗапрос(query: String): String =
        query.lowercase().replace('ё', 'е').split(Regex("[^\\p{L}\\p{N}]+"))
            .map { it.trim() }.filter { it.length > 1 }.distinct()
            .joinToString(" | ") { if (it.length >= 3) "$it:*" else it }

    private fun блок(rs: ResultSet) = IndexBlock(
        key = rs.getString("key"), kind = rs.getString("kind"), area = rs.getString("area"), ref = rs.getString("ref"),
        title = rs.getString("title") ?: "", text = rs.getString("text") ?: "", filters = фильтры(rs.getString("filters")),
        fingerprint = rs.getString("fingerprint"),
    )

    private fun фильтры(json: String?): Map<String, String> =
        if (json.isNullOrBlank()) emptyMap()
        else mapper.readTree(json).properties().associate { (к, з) -> к to з.asText("") }

    /** Отрывок вокруг первого слова запроса — человеку видно, за что нашлось. */
    private fun отрывок(текст: String, слова: String): String {
        val игла = слова.split(Regex("\\s+")).map { it.trim().lowercase() }.filter { it.length > 3 }
        val нижний = текст.lowercase()
        val позиция = игла.map { нижний.indexOf(it.take(5)) }.filter { it >= 0 }.minOrNull() ?: 0
        val от = (позиция - 80).coerceAtLeast(0)
        val до = (позиция + 160).coerceAtMost(текст.length)
        return (if (от > 0) "…" else "") + текст.substring(от, до).trim() + (if (до < текст.length) "…" else "")
    }
}
