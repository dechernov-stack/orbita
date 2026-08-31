// Г-01: пакет между проектами — СОПОСТАВЛЕНИЕ ссылок вместо отказа.
//
// Срез пакета из журнала несёт ссылки исходного проекта. Изоляция режет их
// честно (ADR-022) — и правильно: кросс-проектная связь не пишется никогда.
// Но инженер оставался с ручной правкой JSON: система знала, что ссылка
// чужая, и молчала о том, чем её заменить.
//
// Здесь: для каждой чужой ссылки подбирается соответствие В ЦЕЛЕВОМ ПРОЕКТЕ
// по смыслу — совпадению формулировки. Предложение не применяется само:
// его подтверждает инженер, а несопоставленное остаётся без связи и честно
// показывается разрывом трассировки.
package orbita.com.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.mod.store.StoredObject

object LinkMapping {

    private val mapper = ObjectMapper()

    /** Порог автопредложения: ниже — показываем кандидатов, но не предлагаем. */
    private const val СХОЖЕСТЬ_ПОРОГ = 0.30

    /** Слова, которые есть в каждой второй формулировке и смысла не несут. */
    private val ПУСТЫЕ = setOf(
        "и", "в", "на", "для", "с", "по", "от", "до", "как", "что", "или",
        "нужда", "нужен", "нужна", "нужно", "требуется", "необходимо",
        "система", "сервис", "данные", "данных", "это",
    )

    /**
     * Окончания, которые отбрасываются при сравнении. Не морфология, а
     * огрубление: «перевозчик» и «перевозчику», «телеметрия» и «телеметрию»
     * обязаны считаться одним словом, иначе совпадение по смыслу теряется
     * на падежах (проверено: 0.2 там, где формулировки говорят одно и то же).
     */
    private val ОКОНЧАНИЯ = listOf(
        "ами", "ями", "ого", "его", "ому", "ему", "ыми", "ими", "ая", "яя",
        "ое", "ее", "ые", "ие", "ой", "ей", "ом", "ем", "ах", "ях", "ов", "ев",
        "ую", "юю", "ии", "ия", "ию", "иe", "а", "я", "у", "ю", "ы", "и", "е", "о",
    )

    /** Основа слова — грубо: слово без узнаваемого окончания. */
    private fun основа(слово: String): String {
        if (слово.length <= 4) return слово
        val хвост = ОКОНЧАНИЯ.firstOrNull { слово.endsWith(it) && слово.length - it.length >= 4 }
        return if (хвост == null) слово else слово.dropLast(хвост.length)
    }

    /** Значимые слова формулировки: нижний регистр, без пунктуации и пустых. */
    private fun слова(текст: String): Set<String> =
        текст.lowercase()
            .replace(Regex("[^\\p{L}\\p{Nd}]+"), " ")
            .split(' ')
            .filter { it.length > 2 && it !in ПУСТЫЕ }
            .map { основа(it) }
            .toSet()

    /** Мера схожести формулировок: доля общих значимых слов (Жаккар). */
    fun схожесть(a: String, b: String): Double {
        val x = слова(a)
        val y = слова(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        val общих = x.intersect(y).size.toDouble()
        return общих / (x.size + y.size - общих)
    }

    /** Формулировка объекта — то, по чему его узнаёт человек. */
    fun формулировка(doc: JsonNode): String =
        listOf("statement", "name", "title")
            .firstNotNullOfOrNull { doc.path(it).asText("").ifBlank { null } } ?: ""

    /** Кандидат замены: объект целевого проекта и мера совпадения. */
    data class Кандидат(val id: String, val текст: String, val мера: Double)

    /** Чужая ссылка и что с ней делать. */
    data class Ссылка(
        val ref: String,
        val изПроекта: String?,
        val текст: String,
        val вид: String,
        val предложение: Кандидат?,
        val кандидаты: List<Кандидат>,
    )

    /**
     * Чужие ссылки пакета и предложения замены. Пакет не меняется: решение
     * за инженером, здесь только разбор и подсказка.
     */
    fun разобрать(boundary: Boundary, items: JsonNode, projectId: String): List<Ссылка> {
        val свои = boundary.objects.listCurrent(projectId)
            .filter { it.status.name != "Cancelled" }
        val идПакета = items.mapNotNull { it.path("id").asText("").ifBlank { null } }.toSet()
        val чужие = LinkedHashMap<String, MutableList<JsonNode>>()

        fun обойти(node: JsonNode) {
            when {
                node.isTextual -> {
                    val v = node.asText()
                    if (Regex("^[A-Z]{2,3}-[0-9]{4}$").matches(v) && v !in идПакета) {
                        чужие.getOrPut(v) { mutableListOf() }
                    }
                }
                node.isArray -> node.forEach { обойти(it) }
                node.isObject -> node.properties().forEach { (name, child) ->
                    if (name != "id") обойти(child)
                }
            }
        }
        items.forEach { обойти(it) }

        return чужие.keys.mapNotNull { ref ->
            val объект = boundary.objects.current(ref) ?: return@mapNotNull Ссылка(
                ref, null, "", "", null, emptyList(),
            )
            // ссылка на объект ЭТОГО проекта — не чужая, разбирать нечего
            if (объект.projectId == projectId) return@mapNotNull null
            val текст = формулировка(объект.doc)
            val кандидаты = свои.filter { it.type == объект.type }
                .map { Кандидат(it.id, формулировка(it.doc), схожесть(текст, формулировка(it.doc))) }
                .sortedByDescending { it.мера }
                .take(5)
            Ссылка(
                ref = ref,
                изПроекта = объект.projectId,
                текст = текст,
                вид = объект.type,
                предложение = кандидаты.firstOrNull { it.мера >= СХОЖЕСТЬ_ПОРОГ },
                кандидаты = кандидаты,
            )
        }
    }

    /** Применить подтверждённое сопоставление: ссылки заменяются в пакете. */
    fun применить(items: JsonNode, карта: Map<String, String>): JsonNode {
        if (карта.isEmpty()) return items
        var текст = mapper.writeValueAsString(items)
        карта.forEach { (старый, новый) ->
            текст = текст.replace(Regex("\\b" + Regex.escape(старый) + "\\b"), новый)
        }
        return mapper.readTree(текст)
    }

    fun toJson(ссылки: List<Ссылка>): ObjectNode {
        val out = mapper.createObjectNode()
        out.put("foreign", ссылки.size)
        out.put(
            "summary",
            when {
                ссылки.isEmpty() -> "чужих ссылок нет — пакет ложится как есть"
                ссылки.all { it.предложение != null } ->
                    "чужих ссылок ${ссылки.size}, для каждой есть предложение по смыслу — подтвердите"
                else ->
                    "чужих ссылок ${ссылки.size}; часть без предложения — выберите вручную либо оставьте без связи"
            },
        )
        val arr = out.putArray("links")
        ссылки.forEach { л ->
            val n = arr.addObject()
            n.put("ref", л.ref)
            n.put("from_project", л.изПроекта)
            n.put("text", л.текст)
            n.put("kind", л.вид)
            л.предложение?.let {
                n.putObject("suggested").put("id", it.id).put("text", it.текст).put("score", it.мера)
            }
            val cand = n.putArray("candidates")
            л.кандидаты.forEach { k ->
                cand.addObject().put("id", k.id).put("text", k.текст).put("score", k.мера)
            }
        }
        return out
    }
}
