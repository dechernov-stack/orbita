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
// Обмены между шагами (exchange) — тем же вызовом и тем же пакетом (§2.2b,
// CODE-30-09 §1): модель отвечает функциями И обменами, обмен ссылается на
// функции ЛОКАЛЬНЫМ КЛЮЧОМ (их кодов до приёма нет). Приём разрешает ключ в
// код заведённой функции — «функции первыми, затем обмены»; конец не принят →
// обмен откладывается, а не отказ (см. SynthesisRoutes.обмены).
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
        // Основания функций по локальному ключу — на них ссылается обмен: его
        // основание есть шаг функции-отправителя.
        val основанияФункций = linkedMapOf<String, Basis>()
        корень.path("functions").forEachIndexed { номер, узелОтвета ->
            runCatching { предложение(узелОтвета, область) }
                .onSuccess { п ->
                    предложения += п
                    п.localKey?.let { ключ -> п.basis.firstOrNull()?.let { основанияФункций[ключ] = it } }
                }
                .onFailure { отказы += "функция ${имя(узелОтвета, номер)}: ${it.message}" }
        }
        // Обмены — тем же пакетом (§2.2b): ссылаются на функции локальными ключами.
        var обменов = 0
        корень.path("exchanges").forEachIndexed { номер, узелОтвета ->
            runCatching { обмен(узелОтвета, основанияФункций) }
                .onSuccess { предложения += it; обменов += 1 }
                .onFailure { отказы += "обмен ${имя(узелОтвета, номер)}: ${it.message}" }
        }

        return synthesizer.record(
            project, author, отпечаток,
            "функции из сценариев: ${цепочки.size} цепочек, ${шаги.size} шагов, " +
                "${предложения.size - обменов} функ., $обменов обм.",
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
            // Локальный ключ функции — по нему обмен ссылается на неё до приёма
            // (кода сущности ещё нет). Модель называет его полем `id`.
            localKey = узел.path("id").asText("").trim().ifBlank { null },
        )
    }

    /**
     * Обмен между функциями соседних шагов из ответа модели (§2.2b, CODE-30-09
     * §1). Ссылается на функции ЛОКАЛЬНЫМИ КЛЮЧАМИ пакета: их кодов до приёма
     * нет, приём разрешит ключи в коды заведённых функций. Основание обмена —
     * шаг функции-отправителя (провенанс: обмен родился из этого сценария).
     * Ворота из понятия `exchange` (`must_link: source_function · target`):
     * оба конца названы; проверку «конец принят» делает приём, не деривер.
     */
    private fun обмен(узел: JsonNode, основанияФункций: Map<String, Basis>): FormationProposal {
        val источник = узел.path("source").asText("").trim()
        require(источник.isNotBlank()) { "обмен без функции-источника (source): не на что ссылаться" }
        val приёмник = узел.path("target").asText("").trim()
        require(приёмник.isNotBlank()) { "обмен без получателя (target): второй конец не назван" }
        val имя = узел.path("name").asText("").trim()
        require(имя.isNotBlank()) { "обмен без имени: назовите, что передаётся" }
        // Основание — шаг функции-отправителя: обмен ссылается на функцию этого
        // же пакета. Ключа нет среди функций — обмен не к чему привязать.
        val основа = основанияФункций[источник]
            ?: throw IllegalArgumentException(
                "обмен ссылается на функцию «$источник» вне пакета: источник обмена — функция этого же прогона",
            )

        val payload = linkedMapOf<String, String>()
        payload["name"] = имя
        // Концы обмена — ЛОКАЛЬНЫМИ КЛЮЧАМИ; приём заменит их кодами функций.
        // Поле непусто → ворота `must_link` закрыты формой (вид ссылки null:
        // Reconciler проверяет лишь непустоту, а разрешение в код — за приёмом).
        payload["source_function"] = источник
        payload["target"] = приёмник
        узел.path("payload").asText("").trim().ifBlank { null }?.let { payload["payload"] = it }

        val понятие = GeneratedOntology.of("exchange")
        return FormationProposal(
            concept = "exchange",
            payload = payload,
            basis = listOf(основа),
            verdict = Verdict.NEW,
            sourceMark = SourceMark.П,
            confidence = узел.path("confidence").takeIf { it.isNumber }?.asDouble(),
            missing = FormationRules.нехватка(понятие, payload),
            // Ссылки на соседей: приём разрешает ключ в код заведённой функции;
            // не принята → обмен ОТКЛАДЫВАЕТСЯ, а не отказ (CODE-30-09 §1).
            refs = mapOf("source_function" to источник, "target" to приёмник),
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
            appendLine("1) Для каждого шага с узлом назови function: id (локальный ключ, f1, f2…),")
            appendLine("   chain (код сценария из списка), step (что шага), node (код узла-участника")
            appendLine("   из списка), name (глагол + объект). Узел не придумывай — только из списка.")
            appendLine("2) Между функциями СОСЕДНИХ шагов с разными участниками назови exchanges:")
            appendLine("   id (x1, x2…), source (локальный ключ функции-отправителя, f1),")
            appendLine("   target (локальный ключ функции-получателя, f2), name (что передаётся),")
            appendLine("   payload (содержимое словами). Ссылайся на функции ТОЛЬКО их ключами f-…")
            appendLine("   из п.1 — кодов у функций ещё нет. Нет пары соседних шагов — обменов нет.")
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
