// Чек-лист обзора (NASA SEH App. C): ИНСПЕКЦИЯ ЛЮДЕЙ.
//
// Вся система устроена так, что состояние вычисляется, а не отмечается
// галочкой: ручное «сделано» — это обещание, а не факт. Здесь — оговорённое
// исключение: «прочитать формулировку вслух двумя инженерами и понять
// одинаково» машине не поручить. Поэтому отметка существует, но она несёт
// автора, время и, если есть, замечание словами — как tailoring готовности.
//
// Всё, что вычислимо (трассировка, методы верификации, TBD без владельца),
// живёт разрывами готовности и пометами линта. Чек-лист их не дублирует —
// он ведёт к ним адресом «где смотреть».
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.mod.store.ObjectStore

object ReviewChecklist {

    private val mapper = ObjectMapper()

    /** Чек-листы полки, относящиеся к точке или операции. */
    fun forGate(boundary: Boundary, gate: String?): List<orbita.mod.store.StoredObject> =
        boundary.objects.listCurrent(ObjectStore.LIBRARY_PROJECT)
            .filter { it.type == "review_checklist" && it.status.name != "Cancelled" }
            .filter { gate.isNullOrBlank() || it.doc.path("gate").asText("") == gate }
            .sortedBy { it.id }

    /** Чек-лист с состоянием пунктов: кто отметил и когда. */
    fun view(boundary: Boundary, projectId: String, gate: String?): ObjectNode {
        val паспорт = boundary.objects.current(projectId)?.doc ?: mapper.createObjectNode()
        val отметки = паспорт.path("review_checks")
        val out = mapper.createObjectNode()
        val arr = out.putArray("checklists")
        var всего = 0
        var отмечено = 0
        forGate(boundary, gate).forEach { чек ->
            val n = arr.addObject()
            n.put("id", чек.id)
            n.put("name", чек.doc.path("name").asText(чек.id))
            n.put("gate", чек.doc.path("gate").asText(""))
            чек.doc.path("source").asText("").takeIf { it.isNotBlank() }?.let { n.put("source", it) }
            val items = n.putArray("items")
            чек.doc.path("items").forEach { пункт ->
                val ключ = пункт.path("key").asText("")
                val отметка = отметки.firstOrNull {
                    it.path("checklist").asText() == чек.id && it.path("item").asText() == ключ
                }
                всего += 1
                if (отметка != null) отмечено += 1
                val i = items.addObject()
                i.put("key", ключ)
                i.put("title", пункт.path("title").asText(""))
                пункт.path("hint").asText("").takeIf { it.isNotBlank() }?.let { i.put("hint", it) }
                пункт.path("screen").asText("").takeIf { it.isNotBlank() }?.let { i.put("screen", it) }
                пункт.path("evidence").asText("").takeIf { it.isNotBlank() }?.let { i.put("evidence", it) }
                i.put("checked", отметка != null)
                отметка?.let {
                    i.put("author", it.path("author").asText(""))
                    i.put("at", it.path("at").asText(""))
                    it.path("note").asText("").takeIf { s -> s.isNotBlank() }?.let { s -> i.put("note", s) }
                }
            }
        }
        out.put("total", всего)
        out.put("checked", отмечено)
        out.put(
            "summary",
            when {
                всего == 0 -> "чек-листов для этой точки на полке нет"
                отмечено == 0 -> "инспекция не начата: $всего пунктов ждут проверки человеком"
                отмечено < всего -> "проверено $отмечено из $всего — остальное ждёт инженера"
                else -> "инспекция пройдена: все $всего пунктов отмечены с автором и временем"
            },
        )
        return out
    }
}
