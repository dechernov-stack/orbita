// Эталон постановки → предложения (ПМИ-7, предусловия владельца 17.09).
//
// «Чего контур не добрал против эталона — добирается из
// ПОСТАНОВКА-ИЗ-ЗАПИСКИ.json предложениями; эталон в целом — страховка: если
// чтение сорвалось, вставляется как принятая постановка». Эталон разобран
// внешним контуром руками; провенанс — «разбор внешним контуром: SD-0001,
// якоря §N»: каждая строка несёт якорь записки, и трассировка к
// первоисточнику сохраняется.
//
// Эталон ложится ТЕМ ЖЕ путём, что и чтение: след-факт у каждой строки,
// запуск постановки с предложениями, вердикт выносит сверка при принятии —
// узнанное чтением станет «дополнить»/«подтверждено», недобранное — «новое».
// Так добор и происходит: пакетом, а не руками.
//
// Якоря эталона — секционные, по разделам ШАБЛОНА записки (§0–§11); на канон
// стенда они ремапятся здесь по номеру раздела в заголовке. Цитат у эталона
// нет — у факта внешнего контура `quote` = null, как у факта эксперта.
package orbita.ai.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.ai.api.Basis
import orbita.ai.api.FormationProposal
import orbita.ai.api.SynthesisRun
import orbita.ai.api.Verdict
import orbita.kernel.api.Area
import orbita.kernel.api.EntityStore
import orbita.knowledge.api.FormationRules
import orbita.knowledge.api.Intake
import orbita.knowledge.api.SourceMark
import orbita.knowledge.schema.GeneratedOntology

class StatementImporter(
    private val store: EntityStore,
    private val intake: Intake,
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    /**
     * Положить эталон запуском постановки. `requirements` эталона идут
     * предложениями тоже — это ожидание сцены 8, и принимать их здесь нельзя:
     * по истине требования уровня проекта ОБРАЗУЮТСЯ на сцене 8 решением
     * инженера, а не читаются.
     */
    fun import(project: String, material: String, statement: JsonNode, author: String, synthesizer: Synthesizer): SynthesisRun {
        val область = Area.Project(project)
        require(store.byCode(область, material) != null) { "материала «$material» в проекте нет" }
        val разделы = разделыКанона(project, material)
        val пункты = пункты(statement, разделы)

        val следы = mapper.createObjectNode()
        val массив = следы.putArray("facts")
        пункты.forEach { массив.add(след(it, material)) }
        val принято = intake.putFacts(project, material, mapper.writeValueAsString(следы), author)
        val свободные = принято.accepted.toMutableList()

        val предложения = пункты.mapNotNull { пункт ->
            val след = свободные.firstOrNull { ф ->
                ф.anchor == пункт.anchor && ф.subject == пункт.субъект && ф.value == пункт.имя
            }?.also { свободные.remove(it) } ?: return@mapNotNull null
            val факт = store.byCode(область, след.id) ?: return@mapNotNull null
            val поля = пункт.поля()
            FormationProposal(
                concept = пункт.concept,
                payload = поля,
                basis = listOf(
                    Basis(
                        factId = след.id, material = material, anchor = пункт.anchor,
                        authority = факт.doc.path("authority").asText("").ifBlank { null },
                        mark = пункт.метка,
                    ),
                ),
                verdict = Verdict.NEW,
                sourceMark = пункт.метка,
                confidence = null,
                missing = GeneratedOntology.byCode[пункт.concept]?.let { FormationRules.нехватка(it, поля) }.orEmpty(),
            )
        }
        val счёт = предложения.groupingBy { it.concept }.eachCount().entries
            .joinToString(" · ") { (к, n) -> "${СЛОВА[к] ?: к} $n" }
        val заметка = "эталон внешнего контура: $счёт" +
            (if (принято.refused.isEmpty()) "" else "; отбито ${принято.refused.size}")
        return synthesizer.record(
            project, author, "эталон:${intake.fingerprint(project, material)}", заметка, предложения, принято.refused,
        )
    }

    // --- разбор эталона -----------------------------------------------------

    private class Пункт(
        val localId: String,
        val concept: String,
        val узел: JsonNode,
        val anchor: String,
        val субъект: String,
        val имя: String,
        val метка: SourceMark,
    ) {
        fun поля(): Map<String, String> = linkedMapOf<String, String>().also { поля ->
            узел.properties().forEach { (ключ, значение) ->
                if (ключ in СЛУЖЕБНЫЕ) return@forEach
                val текст = when {
                    значение.isValueNode -> значение.asText("")
                    // Ссылка эталона на нужду — формулировкой (need_ref.statement).
                    ключ == "need_ref" -> значение.path("statement").asText("")
                    // Грань замысла в эталоне — {text, anchors}: в поле идёт текст.
                    concept == "intent" && значение.has("text") -> значение.path("text").asText("")
                    значение.isArray && ключ == "interest" -> значение.joinToString("; ") { it.asText("") }
                    else -> значение.toString()
                }
                if (текст.isNotBlank()) поля[ключ] = текст
            }
            if (concept == "milestone") {
                // Этап записки — программный этап (истина: «веха из устава — только
                // этап проекта (program_stage)»); «span» эталона — «range» схемы.
                поля.putIfAbsent("kind", "program_stage")
                узел.path("span").takeIf { it.isObject }?.let { п ->
                    поля["range"] = mapOf(
                        "start" to п.path("min").asText(""), "end" to п.path("max").asText(""), "unit" to п.path("unit").asText(""),
                    ).let { д -> "{" + д.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" } + "}" }
                    поля.remove("span")
                }
            }
            // Мера снижения эталона — поле «measures» схемы; вероятность и
            // последствия эталон пишет словами, а схема ждёт 1–5 от человека.
            if (concept == "risk") поля.remove("mitigation")?.let { поля["measures"] = it }
            if (concept == "service" && поля["need_ref"] != null) {
                поля["needs"] = поля.remove("need_ref")!!
            }
            // Роль и влияние эталон пишет по-русски; истина схем — кодами.
            поля["role"]?.let { поля["role"] = РОЛИ[it] ?: it }
            // Влияние считает система по карте истины, силу ставит человек:
            // значения эталона снимаются здесь, как и у чтения.
            поля.remove("influence")
            поля.remove("power")
        }
    }

    private fun пункты(эталон: JsonNode, разделы: Map<Int, String>): List<Пункт> {
        val итог = mutableListOf<Пункт>()
        var номер = 0
        fun добавить(concept: String, узел: JsonNode, имя: String, субъект: String, якорь: String, метка: SourceMark) {
            номер += 1
            итог += Пункт("e$номер", concept, узел, якорь, субъект, имя, метка)
        }
        val якорьПоУмолчанию = { раздел: Int -> разделы[раздел] ?: разделы.values.firstOrNull().orEmpty() }

        эталон.path("intent").path("intent").takeIf { it.isObject }?.let { з ->
            добавить("intent", з, з.path("what").path("text").asText(""), "проект", якорьПоУмолчанию(1), SourceMark.И)
        }
        эталон.path("stakeholders").forEach { с ->
            добавить("stakeholder", с, с.path("name").asText(""), с.path("name").asText(""), якорьПоУмолчанию(3), SourceMark.И)
        }
        эталон.path("needs").forEach { н ->
            val метка = метка(н.path("mark").asText("И"))
            добавить("need", н, н.path("statement").asText(""), н.path("stakeholder").asText(""), якорьПоУмолчанию(2), метка)
        }
        эталон.path("goals").forEach { ц ->
            добавить("goal", ц, ц.path("statement").asText(""), "проект", якорь(ц, разделы, 5), метка(ц))
        }
        эталон.path("services").forEach { с ->
            добавить("service", с, с.path("name").asText(""), "система", якорь(с, разделы, 4), метка(с))
        }
        эталон.path("constraints").forEach { р ->
            добавить("constraint", р, р.path("statement").asText(""), "проект", якорь(р, разделы, 9), SourceMark.И)
        }
        эталон.path("stages").forEach { э ->
            добавить("milestone", э, э.path("name").asText(""), "проект", якорь(э, разделы, 7), SourceMark.И)
        }
        эталон.path("risks").forEach { р ->
            добавить("risk", р, р.path("statement").asText(""), "проект", якорь(р, разделы, 10), SourceMark.И)
        }
        эталон.path("requirements").forEach { т ->
            добавить("requirement", т, т.path("statement").asText(""), "система", якорь(т, разделы, 5), метка(т))
        }
        return итог
    }

    /** Якорь пункта: секционный якорь эталона (s5) → якорь канона по номеру раздела. */
    private fun якорь(узел: JsonNode, разделы: Map<Int, String>, раздел: Int): String {
        val свой = (узел.path("anchors").firstOrNull() ?: узел.path("source")).path("anchor").asText("")
        val номер = Regex("^s(\\d+)$").find(свой)?.groupValues?.get(1)?.toIntOrNull() ?: раздел
        return разделы[номер] ?: разделы[раздел] ?: разделы.values.firstOrNull().orEmpty()
    }

    private fun метка(узел: JsonNode): SourceMark {
        val метки = узел.path("source_mark").map { it.asText("") }
        return метка(метки.firstOrNull().orEmpty())
    }

    /** «И1» → И, «В10» → В, «П» → П: буква — метка, номер уезжает в след отдельно. */
    private fun метка(текст: String): SourceMark =
        SourceMark.entries.firstOrNull { текст.trim().startsWith(it.name) } ?: SourceMark.И

    private fun след(пункт: Пункт, material: String): ObjectNode {
        val узел = mapper.createObjectNode()
        узел.put("kind", ВИД_ФАКТА[пункт.concept] ?: "framing")
        узел.put("subject", пункт.субъект.ifBlank { "проект" })
        узел.put("predicate", ПРЕДИКАТ[пункт.concept] ?: "назван эталоном")
        узел.put("value", пункт.имя)
        узел.putNull("quote")
        узел.put("anchor", пункт.anchor)
        узел.put("source_mark", пункт.метка.name)
        // Номер источника из метки эталона (И1 · В10) — отдельным полем.
        val сырая = (пункт.узел.path("source_mark").firstOrNull() ?: пункт.узел.path("mark")).asText("")
        if (сырая.length > 1) узел.put("source_mark_no", сырая)
        (пункт.узел.path("measure").takeIf { it.isObject } ?: пункт.узел.path("mop").takeIf { it.isObject })
            ?.path("unit")?.asText("")?.ifBlank { null }?.let { узел.put("unit", it) }
        return узел
    }

    /**
     * Разделы канона по номеру из заголовка: «## 3. Потребители» → 3 → s10.
     * Считается на месте, по канону ЭТОГО материала: нумерация документа и
     * нумерация канона не совпадают, а якорь обязан указывать на блок.
     */
    private fun разделыКанона(project: String, material: String): Map<Int, String> =
        intake.canon(project, material)
            .filter { it.kind == "heading" }
            .mapNotNull { блок ->
                НОМЕР_РАЗДЕЛА.find(блок.text)?.groupValues?.get(1)?.toIntOrNull()?.let { it to блок.anchor }
            }
            .toMap()

    private companion object {
        val НОМЕР_РАЗДЕЛА: Regex = Regex("^#*\\s*(\\d+)\\.\\s")
        val СЛУЖЕБНЫЕ: Set<String> = setOf(
            "anchors", "source", "source_mark", "mark", "shared", "class", "measure_text", "target_text",
            "note", "code", "priority", "verification_method", "rationale", "acceptance_criteria", "tags",
            "probability", "impact", "result",
        )
        val РОЛИ: Map<String, String> = mapOf(
            "заказчик" to "customer", "регулятор" to "regulator", "оператор" to "operator",
            "потребитель" to "consumer", "поставщик" to "supplier", "партнёр" to "partner",
            "учреждаемый" to "established",
        )
        val ВИД_ФАКТА: Map<String, String> = mapOf(
            "intent" to "framing", "stakeholder" to "relation", "need" to "framing", "goal" to "quantity",
            "service" to "capability", "constraint" to "obligation", "milestone" to "event",
            "risk" to "assessment", "requirement" to "obligation",
        )
        val ПРЕДИКАТ: Map<String, String> = mapOf(
            "intent" to "замышляется как", "stakeholder" to "участвует в проекте", "need" to "нуждается в",
            "goal" to "достичь", "service" to "даёт", "constraint" to "обязывает", "milestone" to "наступает",
            "risk" to "рискует", "requirement" to "требуется",
        )
        val СЛОВА: Map<String, String> = mapOf(
            "intent" to "замысел", "stakeholder" to "стороны", "need" to "нужды", "goal" to "цели",
            "service" to "сервисы", "constraint" to "рамки", "milestone" to "вехи", "risk" to "риски",
            "requirement" to "требования",
        )
    }
}
