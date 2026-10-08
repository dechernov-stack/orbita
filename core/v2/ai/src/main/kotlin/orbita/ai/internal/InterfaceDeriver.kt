// Стыки из обменов (§2.3 шипа 6) — помощник Phase A на общем каркасе
// (CODE-28-09-ОДИН-ПУТЬ): машина находит срез (обмены, пересекающие границу
// узла, и внешних участников сценариев узла), модель ФОРМУЛИРУЕТ стык
// (стороны, тип и направление словами истины, имя — строка ICD), предложения
// ложатся РУЧНЫМ прогоном постановки и принимаются той же сверкой. Своего
// приёма нет.
//
// Основание предложения — ОБМЕН (Basis.Entity вида exchange) либо ШАГ
// СЦЕНАРИЯ (functional_chain) для внешнего участника: провенанс, не связь.
//
// Машинные ворота (тест на отказ у каждого, ЗАДАНИЕ-ШИП-6 §2):
//  · сторона a — этот узел, сторона b — из среза (узел состава либо заведённая
//    внешняя сторона); иначе отказ поимённо;
//  · тип и направление — из перечня истины схем (вид `interface`);
//  · повтор существующего стыка (та же неупорядоченная пара сторон и тип) —
//    вердикт confirm на принятый стык, а не второй экземпляр.
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

class InterfaceDeriver(
    private val store: EntityStore,
    private val intake: Intake,
    private val service: AiService,
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    /** Сосед за границей узла: узел состава либо заведённая внешняя сторона. */
    private sealed class Сосед {
        abstract val код: String
        abstract val имя: String

        /** Узел состава — сторона стыка. */
        data class Узел(val узел: Entity) : Сосед() {
            override val код: String get() = узел.code
            override val имя: String get() = узел.doc.path("name").asText(узел.code)
        }

        /** Внешняя система — стороной (истина 24.09): сторона проекта, имя. */
        data class Внешняя(val сторона: Entity) : Сосед() {
            override val код: String get() = сторона.code
            override val имя: String get() = сторона.doc.path("name").asText(сторона.code)
        }
    }

    /** Одна находка среза: сосед и обмены/шаги, из которых стык выводится. */
    private data class Кандидат(val сосед: Сосед, val основания: MutableList<Basis.Entity> = mutableListOf())

    /**
     * Вывести стыки узла из обменов предложениями в РУЧНОЙ прогон постановки.
     * Кнопка карточки узла (A4/A5, «стыки узла») ведёт в «Предложения» по
     * этому прогону; приём — та же сверка (вердикт каждому выносит она,
     * оттого годные здесь «новые»).
     */
    fun deriveInto(project: String, node: String, author: String, synthesizer: Synthesizer): SynthesisRun {
        require(author.isNotBlank()) { "у деривации нет автора: решение принимает человек" }
        val область = Area.Project(project)
        val узел = store.byCode(область, node) ?: error("узла «$node» нет в проекте")
        require(узел.kind == "component") { "«$node» — это ${узел.kind}, а не узел состава" }

        val кандидаты = срез(область, узел)
        val промпт = промпт(узел, кандидаты)
        val отпечаток = отпечаток(project, node, промпт)

        // Ворота среза: через границу узла ничего не пересекает и внешних
        // участников нет — выводить не из чего: прогон пустой, с причиной,
        // модель не зовём.
        if (кандидаты.isEmpty()) {
            return synthesizer.record(
                project, author, отпечаток,
                "стыки «$node»: нет обменов через границу узла и внешних участников сценариев — выводить не из чего",
                emptyList(), listOf("нет обменов через границу узла"), trigger = РУЧНОЙ, пакет = ПАКЕТ,
            )
        }

        val существующие = существующиеСтыки(область)

        val ответ = service.ask(project, KIND, промпт, maxTokens = БЮДЖЕТ, schema = AnswerSchemas.стыки(mapper))
        val корень = mapper.readTree(
            ответ.text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim(),
        ).takeIf { it.isObject } ?: return synthesizer.record(
            project, author, отпечаток, "стыки «$node»: ответ модели не разобран",
            emptyList(), listOf("ответ модели не разобран"), trigger = РУЧНОЙ, пакет = ПАКЕТ,
        )

        val поКоду = кандидаты.associateBy { it.сосед.код }
        val предложения = mutableListOf<FormationProposal>()
        val отказы = mutableListOf<String>()
        корень.path("interfaces").forEachIndexed { номер, узелОтвета ->
            runCatching { предложение(узелОтвета, узел, поКоду, существующие) }
                .onSuccess { предложения += it }
                .onFailure { отказы += "стык ${имя(узелОтвета, номер)}: ${it.message}" }
        }

        return synthesizer.record(
            project, author, отпечаток,
            "стыки «$node»: ${кандидаты.size} сосед., ${предложения.size} предл.",
            предложения, отказы, trigger = РУЧНОЙ, пакет = ПАКЕТ,
        )
    }

    /**
     * Срез: обмены, пересекающие границу узла, и внешние участники сценариев
     * узла. Обмен уже ссылающийся на стык (поле `interface`) из среза не берём:
     * стык у обмена есть — повторно его не выводим.
     */
    private fun срез(область: Area, узел: Entity): List<Кандидат> {
        val кандидаты = linkedMapOf<String, Кандидат>()
        fun добавь(сосед: Сосед, основание: Basis.Entity) {
            кандидаты.getOrPut(сосед.код) { Кандидат(сосед) }.основания += основание
        }

        // Функции и их узлы-носители: allocated_to хранит идентификаторы
        // (так кладёт деривер функций), ручная форма — тоже id (коды
        // разрешаются приёмом). Разрешаем оба вида адреса.
        val функцииПоКоду = store.list(область, "function").filter { it.status !in СНЯТЫЕ }
            .associateBy { it.code }
        fun узлыФункции(кодИлиИд: String): Set<Entity> =
            (store.byCode(область, кодИлиИд) ?: store.byId(кодИлиИд))
                ?.takeIf { it.kind == "function" }
                ?.let { ф -> функцииПоКоду[ф.code] }
                ?.doc?.path("allocated_to")
                ?.mapNotNull { store.byId(it.asText()) ?: store.byCode(область, it.asText()) }
                ?.filter { it.kind == "component" }
                ?.toSet()
                .orEmpty()

        // Обмены через границу: ровно один конец размещён на узле, второй —
        // на другом узле (однозначно — ровно на одном) либо сторона внешняя.
        store.list(область, "exchange").filter { it.status !in СНЯТЫЕ }
            .filter { it.doc.path("interface").asText("").isBlank() }
            .sortedBy { it.code }
            .forEach { обмен ->
                val запись: (String) -> Entity? = { кодИлиИд ->
                    if (кодИлиИд.isBlank()) null
                    else store.byCode(область, кодИлиИд) ?: store.byId(кодИлиИд)
                }
                val источник = запись(обмен.doc.path("source_function").asText(""))
                val цель = запись(обмен.doc.path("target").asText(""))
                val источникНа = источник?.takeIf { it.kind == "function" }?.let { узлыФункции(it.code) }.orEmpty()
                val цельНа = цель?.takeIf { it.kind == "function" }?.let { узлыФункции(it.code) }.orEmpty()
                val основание = Basis.Entity(kind = "exchange", code = обмен.code)
                when {
                    // Источник на узле, получатель — на ровно одном другом.
                    узел.id in источникНа.map { it.id } && цельНа.size == 1 && узел.id !in цельНа.map { it.id } ->
                        добавь(Сосед.Узел(цельНа.single()), основание)
                    // Источник на узле, получатель — внешняя сторона.
                    узел.id in источникНа.map { it.id } && цель != null && цель.kind == "stakeholder" ->
                        добавь(Сосед.Внешняя(цель), основание)
                    // Получатель на узле, источник — на ровно одном другом.
                    узел.id in цельНа.map { it.id } && источникНа.size == 1 && узел.id !in источникНа.map { it.id } ->
                        добавь(Сосед.Узел(источникНа.single()), основание)
                    // Получатель на узле, источник — внешняя сторона (обмен
                    // входящий: границу узла пересекает он же).
                    узел.id in цельНа.map { it.id } && источник?.kind == "stakeholder" ->
                        добавь(Сосед.Внешняя(источник), основание)
                }
            }

        // Внешние участники сценариев: шаги цепочек узла без участника-узла —
        // актор внешний; стороной стыка он бывает, только если заведён в проекте
        // (ворота «обе стороны — узлы состава или внешняя сторона»).
        val стороныПоИмени = store.list(область, "stakeholder").filter { it.status !in СНЯТЫЕ }
            .associateBy { it.doc.path("name").asText("").trim().lowercase() }
        val наУзле = { кодИлиИд: String ->
            (store.byCode(область, кодИлиИд) ?: store.byId(кодИлиИд))
                ?.takeIf { it.kind == "component" }?.id == узел.id
        }
        store.list(область, "functional_chain").filter { it.status !in СНЯТЫЕ }
            .sortedBy { it.code }
            .forEach { цепочка ->
                val шаги = цепочка.doc.path("steps")
                if (!шаги.any { наУзле(it.path("component").asText("")) }) return@forEach
                шаги.filter {
                    it.path("component").asText("").isBlank() &&
                        it.path("actor").asText("").isNotBlank()
                }.forEach { шаг ->
                    val сторона = стороныПоИмени[шаг.path("actor").asText("").trim().lowercase()] ?: return@forEach
                    добавь(Сосед.Внешняя(сторона), Basis.Entity(kind = "functional_chain", code = цепочка.code, anchor = шаг.path("what").asText("")))
                }
            }
        return кандидаты.values.toList()
    }

    /**
     * Нормализованные ключи существующих стыков: неупорядоченная пара сторон
     * и тип (истина, `interface.identity`). Сторона-объект — внешняя система:
     * ключем служит её имя.
     */
    private fun существующиеСтыки(область: Area): Map<String, String> {
        val карта = mutableMapOf<String, String>()
        store.list(область, "interface").filter { it.status !in СНЯТЫЕ }.forEach { стык ->
            val сторона: (String) -> String = { поле ->
                val значение = стык.doc.path(поле)
                when {
                    // Внешняя система (истина 24.09, external_system_rule).
                    значение.isObject -> "внеш:" + значение.path("name").asText("").trim().lowercase()
                    else -> (store.byId(значение.asText()) ?: store.byCode(область, значение.asText()))?.code
                        ?: значение.asText()
                }
            }
            val тип = стык.doc.path("type").asText("").trim()
            if (тип.isBlank()) return@forEach
            val пара = listOf(сторона("a"), сторона("b")).sorted().joinToString("·")
            карта["$пара|$тип"] = стык.code
        }
        return карта
    }

    /**
     * Один стык из ответа модели — с воротами. Негодное отбивается поимённо
     * (см. вызов), годное — предложением с основанием-обменом (или шагом
     * сценария у внешней стороны). Стороны в payload — идентификаторами, как
     * заводит ручная форма стыка (ArchRoutes.завестиСтык).
     */
    private fun предложение(
        узел: JsonNode,
        узелСостава: Entity,
        поКоду: Map<String, Кандидат>,
        существующие: Map<String, String>,
    ): FormationProposal {
        val кодУзла = узел.path("a").asText("").trim()
        require(кодУзла == узелСостава.code) {
            "сторона a — не этот узел: «$кодУзла» вместо «${узелСостава.code}» (стык выводится из границы узла)"
        }
        // Ворота стороны b: сосед из среза — узел состава либо внешняя сторона.
        val кодСоседа = узел.path("b").asText("").trim()
        require(кодСоседа.isNotBlank()) { "стык без стороны b: второй конец не назван" }
        val кандидат = поКоду[кодСоседа]
            ?: throw IllegalArgumentException("стороны «$кодСоседа» нет в срезе: узел состава или внешняя сторона, других не бывает")
        // Ворота перечней: тип и направление — словами истины схем (вид interface).
        val тип = узел.path("type").asText("").trim()
        require(тип in ТИПЫ_СТЫКА) { "тип стыка «$тип» вне перечня истины схем: ${ТИПЫ_СТЫКА.joinToString(" · ")}" }
        val направление = узел.path("direction").asText("").trim()
        require(направление in НАПРАВЛЕНИЯ_СТЫКА) {
            "направление стыка «$направление» вне перечня истины схем: ${НАПРАВЛЕНИЯ_СТЫКА.joinToString(" · ")}"
        }
        val имя = узел.path("name").asText("").trim()
        require(имя.isNotBlank()) { "стык без имени: назовите, что передаётся (строка ICD)" }

        val payload = linkedMapOf<String, String>()
        payload["a"] = узелСостава.id
        when (val сосед = кандидат.сосед) {
            is Сосед.Узел -> payload["b"] = сосед.узел.id
            // Внешняя система — объектом, как пишет ручная форма: имя и
            // владелец-сторона; значениеУзлом разберёт строку обратно в объект.
            is Сосед.Внешняя -> payload["b"] = mapper.createObjectNode()
                .put("name", сосед.имя)
                .put("owner", сосед.сторона.id)
                .toString()
        }
        payload["name"] = имя
        payload["type"] = тип
        payload["direction"] = направление
        узел.path("standard").asText("").trim().ifBlank { null }?.let { payload["standard"] = it }

        // Ворота повтора: стык этой пары и типа уже принят — confirm на него,
        // а не второй экземпляр. Ключ — как у существующих: пара без порядка.
        val пара = listOf(узелСостава.code, кодСоседа).sorted().joinToString("·")
        val повтор = существующие["$пара|$тип"]

        val понятие = GeneratedOntology.of("interface")
        return FormationProposal(
            concept = "interface",
            payload = payload,
            basis = кандидат.основания.toList(),
            verdict = if (повтор != null) Verdict.CONFIRM else Verdict.NEW,
            sourceMark = SourceMark.П,
            // Подтверждение опирается на существующий стык; поле отличия —
            // тот же тип: пара+тип — ключ идентичности стыка по истине.
            targetRef = повтор,
            diffField = if (повтор != null) "type" else "",
            confidence = узел.path("confidence").takeIf { it.isNumber }?.asDouble(),
            missing = FormationRules.нехватка(понятие, payload),
        )
    }

    /** Отпечаток среза — по промпту: тот же срез → тот же отпечаток → кэш вызова. */
    private fun отпечаток(project: String, node: String, промпт: String): String =
        MessageDigest.getInstance("SHA-256").digest("$project/$node/$промпт".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }.take(16)

    private fun имя(узел: JsonNode, номер: Int): String =
        узел.path("id").asText("").ifBlank { "#$номер" }

    private fun промпт(узел: Entity, кандидаты: List<Кандидат>): String {
        val шапка = buildString {
            appendLine("Ты — системный инженер. Выведи СТЫКИ узла состава с соседями за его границей.")
            appendLine("Стык — между узлом и соседом: стороны, тип и направление — словами истины,")
            appendLine("имя — строка ICD (что передаётся, коротко).")
            appendLine()
            appendLine("Узел: ${узел.code} «${узел.doc.path("name").asText(узел.code)}».")
            appendLine("Соседи за границей узла (код · имя · откуда следует):")
        }
        val список = кандидаты.joinToString("\n") { к ->
            val откуда = к.основания.joinToString("; ") { о ->
                when (о.kind) {
                    "exchange" -> "обмен ${о.code}"
                    else -> "сценарий ${о.code} (шаг: ${о.anchor})"
                }
            }
            "- ${к.сосед.код} · ${к.сосед.имя} · $откуда"
        }
        val хвост = buildString {
            appendLine()
            appendLine("Для каждого соседа, с которым обмен реален, назови interface: id (i1, i2…),")
            appendLine("a (код УЗЛА из шапки), b (код соседа из списка), name (что передаётся — строка ICD),")
            appendLine("type и direction — из перечней схемы. Соседа из списка не вычёркивай и не")
            appendLine("придумывай новых: стык возможен только между узлом и соседом из списка.")
            appendLine("Ответ — по схеме.")
        }
        return шапка + список + хвост
    }

    // Перечни типа и направления стыка — из истины схем (вид «interface»);
    // второй копии в коде нет.
    private val ТИПЫ_СТЫКА: List<String> =
        orbita.kernel.schema.GeneratedKinds.byCode["interface"]?.enums?.get("type").orEmpty()

    private val НАПРАВЛЕНИЯ_СТЫКА: List<String> =
        orbita.kernel.schema.GeneratedKinds.byCode["interface"]?.enums?.get("direction").orEmpty()

    private companion object {
        /** Пакет помощника: им, не кодом понятия, отличают стыки-из-обменов среди прогонов. */
        const val ПАКЕТ = "interface_from_exchange"

        /** Вид вызова в журнале — свой у помощника. */
        const val KIND = "interface_from_exchange"

        /** Причина запуска: ручной прогон помощника (истина synthesis_run.trigger). */
        const val РУЧНОЙ = "manual"

        const val БЮДЖЕТ = 16_000

        /** Снятые с учёта статусы — из списка не берём. */
        val СНЯТЫЕ = setOf("cancelled", "rejected")
    }
}
