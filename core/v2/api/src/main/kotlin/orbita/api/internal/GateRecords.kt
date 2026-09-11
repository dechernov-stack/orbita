// Записи точек: замечания, решения, пройденность — в реестре, не в памяти.
//
// До шипа D пройденные точки жили в памяти api и исчезали с перезапуском:
// зафиксированный MCR «распроходился» после выката. Решение — запись вида
// `decision`, точка получает статус `passed` и ссылку на решение; движок
// читает пройденность отсюда.
package orbita.api.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.kernel.api.Provenance
import orbita.process.api.DecisionView
import orbita.process.api.FindingView
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

class GateRecords(private val store: EntityStore, private val mapper: ObjectMapper) {

    fun findings(project: String): List<FindingView> =
        store.list(Area.Project(project), "finding").map { вид(it) }.sortedBy { it.code }

    fun decisions(project: String): Map<String, DecisionView> =
        store.list(Area.Project(project), "decision")
            .sortedBy { it.createdAt }
            .associate {
                it.doc.path("gate").asText() to DecisionView(
                    by = it.doc.path("by").asText(""),
                    at = it.doc.path("at").asText(""),
                    outcome = it.doc.path("outcome").asText(""),
                    note = it.doc.path("conditions").asText("").ifBlank { null },
                )
            }

    /** Пройденные точки — по статусу записи точки: память api здесь ни при чём. */
    fun passed(project: String): MutableSet<String> =
        store.list(Area.Project(project), "gate").filter { it.status == "passed" }.map { it.code }.toMutableSet()

    fun phaseOf(project: String): String? =
        store.byCode(Area.Project(project), project)?.doc?.path("phase")?.asText("")?.ifBlank { null }

    /** Шаблон фазы проекта (шип G): записан решением точки с `opens_template`. */
    fun phaseTemplateOf(project: String): String? =
        store.byCode(Area.Project(project), project)?.doc?.path("phase_template")?.asText("")?.ifBlank { null }

    fun setPhaseTemplate(project: String, template: String, by: String = "движок") {
        val область = Area.Project(project)
        store.byCode(область, project)?.let { проект ->
            store.update(проект.id, (проект.doc.deepCopy() as ObjectNode).put("phase_template", template), Provenance(Channel.MANUAL, by))
        }
    }

    /**
     * Точки шаблона фазы — записями проекта (как при открытии проекта): без
     * них решение по SRR или KDP-B некуда записать. Уже заведённые не трогаются.
     */
    fun ensureGates(project: String, template: JsonNode, by: String = "движок") {
        val область = Area.Project(project)
        val фаза = template.path("phase").asText("")
        template.path("points").forEach { точка ->
            val ключ = точка.path("key").asText()
            if (store.byCode(область, ключ) != null) return@forEach
            store.create(
                ключ, "gate", область, "A1",
                mapper.createObjectNode()
                    .put("phase", фаза).put("key", ключ)
                    .put("title", точка.path("title").asText(""))
                    .put("kind", точка.path("kind").asText("review"))
                    .put("planned_date", ""),
                Provenance(Channel.MANUAL, by), status = "planned",
            )
        }
    }

    /** Вердикты по позициям экспертизы точки (A12): артефакт → {verdict, note, by, at}. */
    fun positions(project: String, gate: String): JsonNode =
        store.byCode(Area.Project(project), gate)?.doc?.path("positions")?.takeIf { it.isObject } ?: mapper.createObjectNode()

    /** Записать вердикты по позициям: сливаются с прежними, пустой вердикт снимает запись. */
    fun recordPositions(project: String, gate: String, positions: JsonNode, by: String): JsonNode {
        val область = Area.Project(project)
        val точка = store.byCode(область, gate) ?: throw NoSuchElementException("точки «$gate» в проекте $project нет")
        val док = точка.doc.deepCopy() as ObjectNode
        val было = (док.path("positions").takeIf { it.isObject } as? ObjectNode) ?: mapper.createObjectNode()
        positions.forEach { п ->
            val артефакт = п.path("artifact").asText("").trim()
            require(артефакт.isNotBlank()) { "у позиции нужен artifact" }
            val вердикт = п.path("verdict").asText("").trim()
            if (вердикт.isBlank()) { было.remove(артефакт); return@forEach }
            было.set<JsonNode>(
                артефакт,
                mapper.createObjectNode().put("verdict", вердикт).put("note", п.path("note").asText("")).put("by", by)
                    .put("at", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)),
            )
        }
        док.set<JsonNode>("positions", было)
        store.update(точка.id, док, Provenance(Channel.MANUAL, by))
        return было
    }

    /** Решение по точке: запись, статус точки, при approve с фазой — фаза проекта. */
    fun record(project: String, gate: String, by: String, outcome: String, note: String?, opensPhase: String?) {
        val область = Area.Project(project)
        val точка = store.byCode(область, gate) ?: throw NoSuchElementException("точки «$gate» в проекте $project нет")
        val провенанс = Provenance(Channel.MANUAL, by)
        val номер = store.list(область, "decision").count { it.doc.path("gate").asText() == gate } + 1
        val документ = mapper.createObjectNode()
            .put("gate", gate)
            .put("by", by)
            .put("at", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            .put("outcome", outcome)
        note?.takeIf { it.isNotBlank() }?.let { документ.put("conditions", it) }
        val решение = store.create(
            "DEC-$gate-$номер", "decision", область,
            if (gate == "KDP-A") "18" else "16", документ, провенанс, status = "made",
        )
        val докТочки = (точка.doc.deepCopy() as ObjectNode).put("decision", решение.id)
        // День решения — в самой точке: пройденная точка живёт этой датой, а не
        // плановой, которую обогнала (иначе Pre-A «кончается» позже начала Phase A).
        if (outcome == "approve") докТочки.put("decided_at", java.time.LocalDate.now().toString())
        when (outcome) {
            "approve" -> {
                store.update(точка.id, докТочки, провенанс, status = "passed")
                // Переход фазы — только решением точки, которая его открывает.
                opensPhase?.let { фаза ->
                    store.byCode(область, project)?.let { проект ->
                        store.update(проект.id, (проект.doc.deepCopy() as ObjectNode).put("phase", фаза), провенанс)
                    }
                }
            }
            "return" -> store.update(точка.id, докТочки, провенанс, status = "returned")
            else -> store.update(точка.id, докТочки, провенанс)
        }
    }

    fun addFinding(
        project: String,
        gate: String,
        text: String,
        scene: String,
        author: String,
        kind: String,
        question: String?,
        bornIn: String,
    ): FindingView {
        val область = Area.Project(project)
        val префикс = when (kind) { "rfa" -> "RFA"; "rid" -> "RID"; else -> "FND" }
        val номер = store.list(область, "finding").count { it.code.startsWith("$префикс-") } + 1
        val документ = mapper.createObjectNode()
            .put("gate", gate).put("text", text).put("returns_to_scene", scene)
            .put("author", author).put("kind", kind)
        question?.takeIf { it.isNotBlank() }?.let { документ.put("question", it) }
        return вид(
            store.create(
                "$префикс-" + номер.toString().padStart(4, '0'), "finding", область, bornIn,
                документ, Provenance(Channel.MANUAL, author), status = "open",
            ),
        )
    }

    fun close(project: String, code: String, by: String, note: String?): FindingView {
        val область = Area.Project(project)
        val запись = store.byCode(область, code) ?: throw NoSuchElementException("замечания «$code» в проекте нет")
        if (запись.status == "closed") return вид(запись)
        val документ = (запись.doc.deepCopy() as ObjectNode)
            .put("closed_by", by)
            .put("closed_at", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        note?.takeIf { it.isNotBlank() }?.let { документ.put("resolution", it) }
        return вид(store.update(запись.id, документ, Provenance(Channel.MANUAL, by), status = "closed"))
    }

    fun finding(project: String, code: String): FindingView? =
        store.byCode(Area.Project(project), code)?.takeIf { it.kind == "finding" }?.let { вид(it) }

    private fun вид(з: Entity): FindingView = FindingView(
        code = з.code,
        text = з.doc.path("text").asText(""),
        scene = з.doc.path("returns_to_scene").asText(""),
        gate = з.doc.path("gate").asText(""),
        status = з.status,
        author = з.doc.path("author").asText(""),
        kind = з.doc.path("kind").asText("finding"),
        question = з.doc.path("question").asText("").ifBlank { null },
        closedBy = з.doc.path("closed_by").asText("").ifBlank { null },
    )
}
