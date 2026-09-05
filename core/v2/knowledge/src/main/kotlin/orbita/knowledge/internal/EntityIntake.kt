// Загрузка материала и сборка задания.
//
// Граница честности: факты не выдумываются здесь. Детерминированная часть —
// снимок материала, канон и хранение фактов; атомизация и план приходят
// разбором (пакетом или службой) через `ai`. Пока разбора нет, задание
// говорит об этом прямо, а не показывает пустой список как результат.
package orbita.knowledge.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.EntityStore
import orbita.kernel.api.Provenance
import orbita.knowledge.api.Fact
import orbita.knowledge.api.Intake
import orbita.knowledge.api.IntakeTask
import orbita.knowledge.api.PlannedAction
import orbita.knowledge.api.SourceMark

class EntityIntake(
    private val store: EntityStore,
    private val mapper: ObjectMapper = ObjectMapper(),
) : Intake {

    /** Каталог заданий (поставка §3): семантика, не свобода. */
    private val каталог = mapOf(
        "разбери по сущностям" to "полный урожай по классам",
        "положи на полку как типовой" to "обобщение в библиотеку",
        "рассмотрим как базовую" to "кандидат базового решения в проекте",
        "сравни с нашим" to "диф фактов против текущих значений",
        "обнови параметры" to "параметрический импорт",
        "это норматив — заведи" to "норматив с редакцией и пунктами",
        "просто в контекст" to "без действий: блоки в промпт, фактов не создаём",
    )

    override fun putMaterial(
        project: String,
        name: String,
        kind: String,
        text: String,
        author: String,
    ): String {
        val область = Area.Project(project)
        val занято = store.list(область, "material").mapNotNull {
            Regex("^SD-(\\d+)$").find(it.code)?.groupValues?.get(1)?.toIntOrNull()
        }
        val код = "SD-%04d".format((занято.maxOrNull() ?: 0) + 1)
        val документ = mapper.createObjectNode()
        документ.put("name", name)
        документ.put("kind", kind)
        документ.put("text", text)
        документ.put("chars", text.length)
        val материал = store.create(
            код, "material", область, "2", документ,
            Provenance(Channel.MANUAL, author, source = name),
        )
        return материал.code
    }

    override fun plan(project: String, material: String, intent: String, author: String): IntakeTask {
        val область = Area.Project(project)
        val карточка = store.byCode(область, material)
            ?: error("материала «$material» нет в проекте")

        val факты = facts(project).filter { it.material == material }
        val намерение = каталог.entries.firstOrNull { intent.contains(it.key.substringBefore(" ‹")) }

        val действия = when {
            факты.isEmpty() -> emptyList()
            намерение == null -> emptyList()
            else -> действияПоНамерению(намерение.key, факты)
        }

        val примечание = when {
            факты.isEmpty() ->
                "материал «${карточка.doc.path("name").asText(material)}» ещё не разобран: " +
                    "фактов нет, а выдумывать их нельзя. Разбор идёт службой либо вставкой пакета."
            намерение == null ->
                "задание «$intent» вне каталога. Ближайшее по смыслу: " +
                    каталог.keys.joinToString("; ") + ". Уточните — гадать система не станет."
            else -> "план собран из ${факты.size} фактов по заданию «${намерение.key}»: ${намерение.value}"
        }

        val задание = store.create(
            "IT-" + карточка.code.removePrefix("SD-"),
            "intake_task", область, "2",
            mapper.createObjectNode()
                .put("material", material)
                .put("intent", intent)
                .put("facts", факты.size)
                .put("note", примечание),
            Provenance(Channel.MANUAL, author, source = material),
        )

        return IntakeTask(задание.code, material, intent, факты, действия, примечание)
    }

    private fun действияПоНамерению(намерение: String, факты: List<Fact>): List<PlannedAction> =
        when (намерение) {
            "положи на полку как типовой" -> listOf(
                PlannedAction(
                    "shelf_put",
                    "положить на полку типовой компонент",
                    "появится запись полки с ${факты.size} параметрами и ссылкой на источник",
                    факты.map { it.id },
                ),
            )
            "рассмотрим как базовую" -> listOf(
                PlannedAction(
                    "candidate_node",
                    "завести узел состава кандидатом",
                    "появится узел с параметрами из фактов и решением «кандидат»",
                    факты.map { it.id },
                ),
                PlannedAction(
                    "check_constraints",
                    "сверить с ограничениями проекта",
                    "покажет расхождения с Р-кодами до того, как кандидат станет базовым",
                    факты.map { it.id },
                ),
            )
            "это норматив — заведи" -> listOf(
                PlannedAction(
                    "normative",
                    "завести норматив с редакцией",
                    "появится норматив; пункты станут основаниями требований",
                    факты.map { it.id },
                ),
            )
            "просто в контекст" -> emptyList()
            else -> listOf(
                PlannedAction(
                    "harvest",
                    "разложить факты по разделам постановки",
                    "кандидаты появятся в своих разделах и будут приняты по одному",
                    факты.map { it.id },
                ),
            )
        }

    override fun accept(project: String, task: String, chosen: List<Int>, author: String): List<String> {
        val область = Area.Project(project)
        val задание = store.byCode(область, task) ?: error("задания «$task» нет")
        // Выполнение действий штатными каналами — волна 2 продолжается; пока
        // фиксируем решение человека, чтобы оно не потерялось.
        store.update(
            задание.id,
            (задание.doc.deepCopy<JsonNode>() as com.fasterxml.jackson.databind.node.ObjectNode)
                .put("accepted", chosen.joinToString(",")),
            Provenance(Channel.MANUAL, author),
            status = "accepted",
        )
        return chosen.map { "действие $it принято" }
    }

    override fun facts(project: String): List<Fact> =
        store.list(Area.Project(project), "fact").map { сущность ->
            Fact(
                id = сущность.code,
                subject = сущность.doc.path("subject").asText(""),
                predicate = сущность.doc.path("predicate").asText(""),
                value = сущность.doc.path("value").asText(""),
                unit = сущность.doc.path("unit").asText("").ifBlank { null },
                anchor = сущность.doc.path("anchor").asText("").ifBlank { null },
                mark = runCatching {
                    SourceMark.valueOf(сущность.doc.path("mark").asText("И"))
                }.getOrDefault(SourceMark.И),
                confidence = сущность.doc.path("confidence").takeIf { it.isNumber }?.asDouble(),
                material = сущность.doc.path("material").asText(""),
            )
        }
}
