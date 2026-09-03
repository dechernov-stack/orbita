// Предложение привязки способности (ADR-053, ответ владельца 03.09 §2).
//
// Способность приходит с полки С ПОДСКАЗКОЙ («нужды класса A′», «ConOps»,
// «ограничения/нормативы»), а не со связью: какая именно нужда имелась в виду,
// решает инженер. Служба не гадает — она СОПОСТАВЛЯЕТ ПО ТЕКСТУ и показывает
// основание: какие слова совпали и откуда взят кандидат. Принятие связи —
// действие человека; правило «способность ни к чему не привязана» держит
// точку MCR, чтобы предложение не осталось предложением навсегда.
//
// Совпадение считается по значимым словам с грубой нормализацией окончаний:
// «доставка» ≈ «доставлять», «сообщений» ≈ «сообщения». Ранг — доля слов
// способности, нашедшихся в кандидате; порог отсекает случайные пересечения.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.mod.model.Lifecycle
import orbita.mod.store.StoredObject

class CapabilityMatches(private val boundary: Boundary) {
    private val mapper = ObjectMapper()

    /** Служебные слова, которые совпадают у всего и не значат ничего. */
    private val стопСлова = setOf(
        "и", "в", "на", "с", "по", "для", "при", "или", "не", "к", "от", "до", "из", "за",
        "система", "систем", "проект", "должн", "обеспеч", "работ", "данн", "котор",
    )

    /** Грубая нормализация: слово к основе — окончания русского языка отбрасываются. */
    internal fun основа(слово: String): String {
        val ч = слово.lowercase().trim('«', '»', '"', '(', ')', ',', '.', ';', ':', '–', '—', '-', '!', '?')
        if (ч.length <= 4) return ч
        return ч.take(maxOf(5, ч.length - 3))
    }

    internal fun слова(текст: String): Set<String> =
        текст.split(Regex("[^\\p{L}\\p{Nd}′']+"))
            .filter { it.length > 2 }
            .map { основа(it) }
            .filterNot { s -> стопСлова.any { s.startsWith(it) && it.length >= 4 } || s in стопСлова }
            .toSet()

    data class Match(val ref: String, val kind: String, val text: String, val score: Double, val common: List<String>)

    /** Виды кандидатов, названные подсказкой полки; пусто — кандидатов нет честно. */
    private fun видыПоПодсказке(hint: String): List<String> {
        val h = hint.lowercase()
        return buildList {
            if (h.contains("нужд")) add("need")
            if (h.contains("цел")) add("mission_goal")
            if (h.contains("сервис")) add("service")
            if (h.contains("conops") || h.contains("сценар")) add("conops")
            // «ограничения/нормативы» живут в паспорте проекта и на полке Б1 —
            // объектов-кандидатов у них нет, и выдумывать их служба не станет
            if (isEmpty() && h.isNotBlank()) addAll(listOf("need", "mission_goal", "service", "conops"))
        }
    }

    private fun текстОбъекта(o: StoredObject): String = listOf(
        o.doc.path("name").asText(""),
        o.doc.path("statement").asText(""),
        o.doc.path("title").asText(""),
        o.doc.path("goal").asText(""),
    ).filter { it.isNotBlank() }.joinToString(" · ")

    /** Предложения по одной способности: до трёх кандидатов с основанием. */
    fun forCapability(projectId: String, capability: StoredObject, limit: Int = 3): List<Match> {
        val hint = capability.doc.path("traced_to_hint").asText("")
        val виды = видыПоПодсказке(hint)
        if (виды.isEmpty()) return emptyList()
        val искомое = слова(capability.doc.path("name").asText("") + " " + hint)
        if (искомое.isEmpty()) return emptyList()
        return boundary.objects.listCurrent(projectId)
            .filter { it.type in виды && it.status != Lifecycle.Cancelled }
            .mapNotNull { кандидат ->
                val общие = слова(текстОбъекта(кандидат)).intersect(искомое)
                if (общие.size < 2) return@mapNotNull null
                Match(
                    ref = кандидат.id,
                    kind = кандидат.type,
                    text = текстОбъекта(кандидат).take(160),
                    score = общие.size.toDouble() / искомое.size,
                    common = общие.sorted(),
                )
            }
            .sortedByDescending { it.score }
            .take(limit)
    }

    /** Предложения по всем непривязанным способностям проекта. */
    fun view(projectId: String): ObjectNode {
        val out = mapper.createObjectNode()
        val rows = out.putArray("capabilities")
        boundary.objects.listCurrent(projectId)
            .filter { it.type == "capability" && it.status != Lifecycle.Cancelled }
            .sortedBy { it.doc.path("code").asText(it.id) }
            .forEach { cap ->
                val row = rows.addObject()
                    .put("id", cap.id)
                    .put("code", cap.doc.path("code").asText(""))
                    .put("name", cap.doc.path("name").asText(""))
                    .put("hint", cap.doc.path("traced_to_hint").asText(""))
                    .put("linked", cap.doc.path("traced_to").size() > 0)
                val предложения = row.putArray("matches")
                if (cap.doc.path("traced_to").isEmpty) {
                    forCapability(projectId, cap).forEach { m ->
                        val n = предложения.addObject()
                            .put("ref", m.ref).put("kind", m.kind).put("text", m.text)
                            .put("score", Math.round(m.score * 100).toInt())
                        val общие = n.putArray("common")
                        m.common.forEach { общие.add(it) }
                    }
                }
            }
        return out
    }

    /**
     * Принять предложение: связь ставит ЧЕЛОВЕК, поэтому обоснование
     * обязательно и автор — учётка, а не служба.
     */
    fun accept(projectId: String, capabilityId: String, refs: List<Pair<String, String>>, author: String): StoredObject {
        val cap = boundary.objects.current(capabilityId)
            ?: throw NoSuchElementException("способность '$capabilityId' не найдена")
        require(cap.type == "capability") { "'$capabilityId' — не способность" }
        val doc = cap.doc.deepCopy<ObjectNode>()
        val список = doc.putArray("traced_to")
        refs.forEach { (ref, rationale) ->
            boundary.objects.current(ref)
                ?: throw IllegalArgumentException("привязка ведёт на отсутствующий объект $ref")
            val n = список.addObject().put("ref", ref)
            if (rationale.isNotBlank()) n.put("rationale", rationale)
        }
        val changes = mapper.createObjectNode()
        changes.set<com.fasterxml.jackson.databind.JsonNode>("traced_to", список.deepCopy())
        return boundary.editing.update(
            orbita.mod.model.CoreType.Capability, capabilityId, changes, cap.version, author,
        )
    }
}
