// Величина факта — ОБЪЕКТОМ measure{op?, value, unit} (истина: fact.value:
// measure{…} | text), §0.2 шипа 6. Приём терял оператор: величину писали
// строкой `value` + отдельным top-level `unit`, а оператор («≤», «≥») — никуда.
// Здесь одно место записи (положить) и чтения (мера · текст), понимающее обе
// формы: новую (объект) и старую (строка + unit) — до миграции.
package orbita.knowledge.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

internal object ВеличинаФакта {

    /** Операторы величины (истина measure.op); «≤ ≥» приводятся к ASCII. */
    private val ПРЕФИКСЫ = listOf("<=", "≤", ">=", "≥", "<", ">", "=")

    private fun нормОп(о: String): String = when (о.trim()) {
        "≤" -> "<="; "≥" -> ">="; else -> о.trim()
    }

    /** Оператор и число из строки: явный оператор сильнее; иначе — префикс «≤180». */
    fun разобрать(значение: String, оператор: String?): Pair<String?, String> {
        оператор?.trim()?.ifBlank { null }?.let { return нормОп(it) to значение.trim() }
        val з = значение.trim()
        for (о in ПРЕФИКСЫ) if (з.startsWith(о)) return нормОп(о) to з.removePrefix(о).trim()
        return null to з
    }

    /**
     * Положить величину в документ факта: единица + число → объект measure
     * {op?, value, unit} (истина); иначе — текстом строкой `value`. top-level
     * `unit` больше не пишется — единица живёт внутри величины.
     */
    fun положить(документ: ObjectNode, mapper: ObjectMapper, значение: String, единица: String?, оператор: String?) {
        val ед = единица?.trim().orEmpty()
        val (оп, числоТекст) = разобрать(значение, оператор)
        val число = числоТекст.replace(',', '.').toDoubleOrNull()
        if (ед.isNotBlank() && число != null) {
            val мера = mapper.createObjectNode()
            оп?.takeIf { it.isNotBlank() }?.let { мера.put("op", it) }
            // Целое — как Long (90, не 90.0): показ и ключи читают число asText()
            // без хвостового ноля, а сравнение всё равно берёт asDouble().
            if (число == Math.floor(число) && !число.isInfinite()) мера.put("value", число.toLong()) else мера.put("value", число)
            мера.put("unit", ед)
            документ.set<JsonNode>("value", мера)
        } else {
            // Не число или без единицы — текст (истина: value: measure | text).
            документ.put("value", значение.trim())
        }
    }

    /**
     * Числовая величина факта объектом {op?, value, unit} для сравнения —
     * из новой формы (value-объект) либо старой (value-строка + top-level unit).
     * null — величина не числовая (перечень, текст).
     */
    fun мера(doc: JsonNode, mapper: ObjectMapper): ObjectNode? {
        val v = doc.path("value")
        val числоТекст: String
        val единица: String
        val оп: String
        if (v.isObject) {
            числоТекст = v.path("value").asText("")
            единица = v.path("unit").asText("")
            оп = v.path("op").asText("")
        } else {
            числоТекст = v.asText("")
            единица = doc.path("unit").asText("")
            оп = ""
        }
        val ед = единица.trim().ifBlank { return null }
        val число = числоТекст.trim().replace(',', '.').toDoubleOrNull() ?: return null
        val узел = mapper.createObjectNode().put("value", число).put("unit", ед)
        оп.trim().ifBlank { null }?.let { узел.put("op", it) }
        return узел
    }

    /** Величина словами: «≤ 180 мин» — для показа, индекса, обоснования (обе формы). */
    fun текст(doc: JsonNode): String {
        val v = doc.path("value")
        val части = if (v.isObject) {
            val зн = v.path("value").let { if (it.isNumber) число(it.asDouble()) else it.asText("") }
            listOf(v.path("op").asText(""), зн, v.path("unit").asText(""))
        } else {
            listOf(v.asText(""), doc.path("unit").asText(""))
        }
        return части.map { it.trim() }.filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun число(d: Double): String =
        if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString()
}
