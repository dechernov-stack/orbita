// Функции из шагов сценариев (§2.2 шипа 6) — помощник Phase A на том же
// каркасе, что деривация требований (RequirementDeriver, CODE-28-09-ОДИН-ПУТЬ):
// машина находит срез (шаги сценариев с участником-узлом), модель ФОРМУЛИРУЕТ
// функцию (глагол + объект), предложения ложатся ручным прогоном и принимаются
// той же сверкой. Своего приёма нет.
//
// Основание предложения — ШАГ СЦЕНАРИЯ (Basis.Entity вида functional_chain,
// anchor — шаг): провенанс, не связь. Носитель функции — узел-участник шага
// (allocated_to полем; сверка требует ≥1 — «функция без узла не предлагается»).
//
// Обмены между шагами (exchange) — следующий заход (§2.2b): обмен ссылается на
// функции, а их коды известны только после приёма; сток-сверка требует
// «функции сначала, обмены потом». Здесь только функции.
package orbita.ai.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.ai.api.AiService
import orbita.ai.api.Basis
import orbita.ai.api.FormationProposal
import orbita.ai.api.SynthesisRun
import orbita.ai.api.Verdict
import orbita.kernel.api.Area
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.knowledge.api.FormationRules
import orbita.knowledge.api.Intake
import orbita.knowledge.api.SourceMark
import orbita.knowledge.schema.GeneratedOntology
import java.security.MessageDigest

class FunctionDeriver(
    private val store: EntityStore,
    private val intake: Intake,
    private val service: AiService,
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    /** Шаг сценария с участником-узлом: из него выводится функция узла. */
    private data class Шаг(val цепочка: String, val индекс: Int, val что: String, val актор: String, val узел: String)

    /**
     * Вывести функции узлов из шагов сценариев предложениями в РУЧНОЙ прогон
     * постановки. Кнопка сцены 9 ведёт в «Предложения» по этому прогону; приём
     * — та же сверка (вердикт каждому — ею, оттого здесь все «новые»).
     */
    fun deriveInto(project: String, author: String, synthesizer: Synthesizer): SynthesisRun {
        require(author.isNotBlank()) { "у деривации нет автора: решение принимает человек" }
        val область = Area.Project(project)
        val цепочки = store.list(область, "functional_chain").filter { it.status !in СНЯТЫЕ }.sortedBy { it.code }
        // Шаги с участником-узлом: participant шага хранится прозой
        // {what, actor, component}; component — код узла состава, когда актор
        // разрешился в узел (ArchRoutes.завестиСценарий). Без узла шаг функции
        // не даёт — «функция без узла не предлагается».
        val шаги = цепочки.flatMap { ц ->
            ц.doc.path("steps").mapIndexedNotNull { i, ш ->
                val узел = ш.path("component").asText("").trim().ifBlank { return@mapIndexedNotNull null }
                Шаг(ц.code, i, ш.path("what").asText("").trim(), ш.path("actor").asText("").trim(), узел)
            }
        }

        val промпт = промпт(шаги)
        val отпечаток = отпечаток(project, промпт)

        if (шаги.isEmpty()) {
            return synthesizer.record(
                project, author, отпечаток,
                "функции из сценариев: нет шагов сценариев с участником-узлом — выводить не из чего",
                emptyList(), listOf("нет шагов с узлом"), trigger = РУЧНОЙ, пакет = ПАКЕТ,
            )
        }

        val ответ = service.ask(project, KIND, промпт, maxTokens = БЮДЖЕТ, schema = AnswerSchemas.функции(mapper))
        val корень = mapper.readTree(
            ответ.text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim(),
        ).takeIf { it.isObject } ?: return synthesizer.record(
            project, author, отпечаток, "функции из сценариев: ответ модели не разобран",
            emptyList(), listOf("ответ модели не разобран"), trigger = РУЧНОЙ, пакет = ПАКЕТ,
        )

        val предложения = mutableListOf<FormationProposal>()
        val отказы = mutableListOf<String>()
        корень.path("functions").forEachIndexed { номер, узелОтвета ->
            runCatching { предложение(узелОтвета, область) }
                .onSuccess { предложения += it }
                .onFailure { отказы += "функция ${имя(узелОтвета, номер)}: ${it.message}" }
        }

        return synthesizer.record(
            project, author, отпечаток,
            "функции из сценариев: ${цепочки.size} цепочек, ${шаги.size} шагов, ${предложения.size} предл.",
            предложения, отказы, trigger = РУЧНОЙ, пакет = ПАКЕТ,
        )
    }

    /**
     * Одна функция из ответа модели — с воротами. Носитель — узел-участник
     * шага (allocated_to полем-массивом, идентификатором: сверка считает ≥1,
     * «функция без узла не предлагается»). Основание — шаг сценария.
     */
    private fun предложение(узел: JsonNode, область: Area): FormationProposal {
        val цепочка = узел.path("chain").asText("").trim()
        require(цепочка.isNotBlank()) { "функция без сценария-основания: не назван chain" }
        // Ворота: у функции есть узел-носитель, и он существует в составе.
        val кодУзла = узел.path("node").asText("").trim()
        require(кодУзла.isNotBlank()) { "функция без узла не предлагается (must_link allocated_to)" }
        val носитель = store.byCode(область, кодУзла)?.takeIf { it.kind == "component" }
            ?: throw IllegalArgumentException("узла «$кодУзла» нет в составе: носитель функции не найден")
        val имя = узел.path("name").asText("").trim()
        require(имя.isNotBlank()) { "функция без имени: назовите глагол + объект" }

        val шаг = узел.path("step").asText("").trim().ifBlank { null }
        val payload = linkedMapOf<String, String>()
        payload["name"] = имя
        // Слой: SA по умолчанию; LA только при уже распределённом узле LA —
        // этой тонкости здесь нет, ставим SA (модель может уточнить).
        payload["layer"] = узел.path("layer").asText("SA").ifBlank { "SA" }
        // Носитель — идентификатором в массиве (allocated_to: ref[] component,
        // хранится id, как пишет FunctionAllocationDistributor). значениеУзлом
        // разберёт строку-массив обратно; сверка увидит ≥1 значение.
        payload["allocated_to"] = mapper.createArrayNode().add(носитель.id).toString()

        val понятие = GeneratedOntology.of("function")
        return FormationProposal(
            concept = "function",
            payload = payload,
            basis = listOf(Basis.Entity(kind = "functional_chain", code = цепочка, anchor = шаг)),
            verdict = Verdict.NEW,
            // [П]: предложение модели по шагу сценария, не свидетельство документа.
            sourceMark = SourceMark.П,
            confidence = узел.path("confidence").takeIf { it.isNumber }?.asDouble(),
            missing = FormationRules.нехватка(понятие, payload),
        )
    }

    private fun отпечаток(project: String, промпт: String): String =
        MessageDigest.getInstance("SHA-256").digest("$project/$промпт".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }.take(16)

    private fun имя(узел: JsonNode, номер: Int): String =
        узел.path("id").asText("").ifBlank { "#$номер" }

    private fun промпт(шаги: List<Шаг>): String {
        val шапка = buildString {
            appendLine("Ты — системный инженер. Из шагов сценариев выведи ФУНКЦИИ узлов-участников.")
            appendLine("Имя функции — глагол + объект («передать сообщение терминала»), со стороны узла.")
            appendLine()
            appendLine("Шаги сценариев с участником-узлом (chain · шаг · участник):")
        }
        val список = шаги.joinToString("\n") { ш ->
            "- ${ш.цепочка} · шаг ${ш.индекс}: «${ш.что}» · участник ${ш.актор} (узел ${ш.узел})"
        }
        val хвост = buildString {
            appendLine()
            appendLine("Для каждого шага с узлом назови function: chain (код сценария из списка), step")
            appendLine("(что шага), node (код узла-участника из списка), name (глагол + объект). Узел не")
            appendLine("придумывай — только из списка. Обмены между шагами не называй — это отдельный шаг.")
            appendLine("Ответ — по схеме.")
        }
        return шапка + список + хвост
    }

    private companion object {
        /** Пакет помощника: им, не кодом понятия, отличают функции-из-сценариев среди прогонов. */
        const val ПАКЕТ = "function_from_scenario"

        /** Вид вызова в журнале — свой у помощника. */
        const val KIND = "function_from_scenario"

        /** Причина запуска: ручной прогон помощника (истина synthesis_run.trigger). */
        const val РУЧНОЙ = "manual"

        const val БЮДЖЕТ = 16_000

        /** Снятые с учёта статусы — из списка не берём. */
        val СНЯТЫЕ = setOf("cancelled", "rejected")
    }
}
