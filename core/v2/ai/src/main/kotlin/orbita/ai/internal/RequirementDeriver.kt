// Деривация требований вниз (§2.1 шипа 6) — ОБРАЗЕЦ помощника Phase A.
//
// Путь один и общий пяти помощникам (диф владельца 28.09,
// CODE-28-09-ОДИН-ПУТЬ): машина находит срез и ставит ворота, модель
// ФОРМУЛИРУЕТ, предложения ложатся прогоном постановки и принимаются той же
// сверкой. Своего приёма у помощника нет.
//
// Здесь: узел состава + требования, несомые узлом или его предком, →
// дочерние требования узла. Основание предложения — РОДИТЕЛЬСКОЕ ТРЕБОВАНИЕ
// (Basis.Entity), не факт: при приёме оно ложится в requirement.source
// (model_basis_rule).
//
// Уровень дочернего требования модель НЕ выбирает (диф 29.09,
// requirement_level_rule): он выводится из ВИДА УЗЛА-носителя. Ворота —
// машинные: узел даёт ступень; родитель существует среди требований узла или
// предков; родитель уровнем строго ВЫШЕ по порядку ступеней ПРАВИЛА, не
// перечня. Единицу из справочника и «сумму долей бюджета ≤ родителя» ставит
// помощник мер (§2.1-C2) — здесь их ещё нет (долг NEXT.md).
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
import orbita.knowledge.api.Bound
import orbita.knowledge.api.Budgets
import orbita.knowledge.api.FormationRules
import orbita.knowledge.api.Intake
import orbita.knowledge.api.Measures
import orbita.knowledge.api.SourceMark
import orbita.knowledge.schema.GeneratedOntology
import java.security.MessageDigest

class RequirementDeriver(
    private val store: EntityStore,
    private val intake: Intake,
    private val service: AiService,
    private val measures: Measures,
    private val budgets: Budgets? = null,
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    /**
     * Вывести требования узла предложениями в РУЧНОЙ прогон постановки.
     *
     * Кнопка карточки узла ведёт в предложения требований этого узла (шип 5
     * §4, отбор носитель=узел). Приёма своего нет: вердикт каждому выносит
     * сверка, оттого здесь все «новые».
     */
    fun deriveInto(project: String, node: String, author: String, synthesizer: Synthesizer): SynthesisRun {
        require(author.isNotBlank()) { "у деривации нет автора: решение принимает человек" }
        val область = Area.Project(project)
        val узел = store.byCode(область, node) ?: error("узла «$node» нет в проекте")
        require(узел.kind == "component") { "«$node» — это ${узел.kind}, а не узел состава" }

        // Уровень дочерних требований — из ВИДА УЗЛА (диф 29.09,
        // requirement_level_rule), модель его не выбирает. Узел вида не из
        // перечня узлов ступени не даёт — выводить нечем.
        val видУзла = узел.doc.path("kind").asText("")
        val дочернийУровень = видКУровню[видУзла]

        // Срез: требования, несомые узлом или его предком по составу. Носитель
        // хранится идентификатором (requirement.carrier = id), предки — по
        // ссылке component.parent вверх до корня.
        val предки = предки(область, узел)
        val родители = store.list(область, "requirement")
            .filter { it.status !in СНЯТЫЕ }
            .filter { val носитель = it.doc.path("carrier").asText(""); носитель.isNotBlank() && носитель in предки }
            .sortedBy { it.code }

        val промпт = промпт(узел, дочернийУровень, родители)
        val отпечаток = отпечаток(project, node, промпт)

        // Ворота узла: вид не даёт ступени — прогон пустой, с причиной.
        if (дочернийУровень == null) {
            return synthesizer.record(
                project, author, отпечаток,
                "деривация «$node»: вид узла «$видУзла» не даёт ступени требования (requirement_level_rule)",
                emptyList(), listOf("узел без ступени"),
                trigger = РУЧНОЙ, пакет = ПАКЕТ,
            )
        }
        // Ворота родителя: без родительского требования выводить вниз не из
        // чего — прогон пустой, с причиной, модель не зовём.
        if (родители.isEmpty()) {
            return synthesizer.record(
                project, author, отпечаток,
                "деривация «$node»: у узла и его предков нет требований — выводить вниз не из чего",
                emptyList(), listOf("нет родительского требования"),
                trigger = РУЧНОЙ, пакет = ПАКЕТ,
            )
        }

        val ответ = service.ask(
            project, KIND, промпт, maxTokens = БЮДЖЕТ, schema = AnswerSchemas.деривация(mapper),
        )
        val корень = mapper.readTree(
            ответ.text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim(),
        ).takeIf { it.isObject } ?: return synthesizer.record(
            project, author, отпечаток, "деривация «$node»: ответ модели не разобран",
            emptyList(), listOf("ответ модели не разобран"), trigger = РУЧНОЙ, пакет = ПАКЕТ,
        )

        val поКоду = родители.associateBy { it.code }
        val предложения = mutableListOf<FormationProposal>()
        val отказы = mutableListOf<String>()
        корень.path("requirements").forEachIndexed { номер, узелОтвета ->
            runCatching { предложение(узелОтвета, поКоду, носитель = узел.id, уровень = дочернийУровень) }
                .onSuccess { предложения += it }
                .onFailure { отказы += "требование ${имя(узелОтвета, номер)}: ${it.message}" }
        }
        // Бюджет (диф 29.09-b): у бюджетного родителя — ПОМЕТА о сумме долей
        // (принятые по связи derives со всех узлов + предлагаемые против потолка
        // × (1 − резерв)). Не отказ: как делить бюджет, решает человек; отказ
        // доли сверх потолка встаёт при ПРИЁМЕ (SynthesisRoutes).
        val пометы = budgets?.let { пометыБюджета(project, предложения, it) }.orEmpty()
        val заметка = (listOf("деривация «$node» (${дочернийУровень}): ${родители.size} родит., ${предложения.size} предл.") + пометы)
            .joinToString(" · ")

        return synthesizer.record(project, author, отпечаток, заметка, предложения, отказы, trigger = РУЧНОЙ, пакет = ПАКЕТ)
    }

    /**
     * Пометы бюджета: по каждому бюджетному родителю — сумма принятых долей
     * (порт `Budgets`) + предлагаемых в этом прогоне против потолка с резервом.
     * Только словами, без отказа: доли делит человек, отказ — при приёме.
     */
    private fun пометыБюджета(
        project: String,
        предложения: List<FormationProposal>,
        budgets: Budgets,
    ): List<String> {
        val пометы = mutableListOf<String>()
        предложения.groupBy { (it.basis.firstOrNull() as? Basis.Entity)?.code }.forEach { (код, дети) ->
            val потолок = код?.let { budgets.forCeiling(project, it) } ?: return@forEach
            val предложено = дети.mapNotNull { п ->
                п.payload["measure"]?.let { runCatching { measures.canonical(mapper.readTree(it)) }.getOrNull() }
            }.filter { it.dimension == потолок.dimension }.sumOf { it.value }
            if (предложено <= 0.0) return@forEach
            val сумма = потолок.accepted + предложено
            if (сумма > потолок.cap + ДОПУСК) {
                пометы += "бюджет «$код»: принято ${число(потолок.accepted)} + предложено ${число(предложено)} = " +
                    "${число(сумма)} ${потолок.unit} при потолке ${число(потолок.cap)}" +
                    (if (!потолок.reserveKnown) " (резерв не задан числом)" else "")
            }
        }
        return пометы
    }

    /**
     * Одно дочернее требование из ответа модели — с воротами. Негодное
     * отбивается поимённо (см. вызов), годное становится предложением с
     * основанием-родителем (Basis.Entity): при приёме родитель ложится в
     * requirement.source, а не в ссылку на факт. Уровень — узла, не из ответа.
     */
    private fun предложение(
        узел: JsonNode,
        поКоду: Map<String, Entity>,
        носитель: String,
        уровень: String,
    ): FormationProposal {
        val кодРодителя = узел.path("derives_from").asText("").trim()
        require(кодРодителя.isNotBlank()) { "деривация без родителя: не назван derives_from" }
        // Ворота: родитель существует среди требований узла или предков.
        val родитель = поКоду[кодРодителя]
            ?: throw IllegalArgumentException("родителя «$кодРодителя» нет среди требований узла или предков")
        // Ворота: родитель ступенью строго ВЫШЕ узла по порядку правила
        // (requirement_level_rule), не по порядку значений перечня.
        val уровеньРодителя = родитель.doc.path("level").asText("")
        require(выше(уровеньРодителя, уровень)) {
            "уровень родителя «$уровеньРодителя» не выше ступени узла «$уровень» — это не деривация вниз"
        }

        // Ворота единицы: мерное требование несёт единицу из справочника
        // (диф 29.09). Единицу вне справочника ворота отбивают — число без
        // известной единицы сравнивать и сводить в бюджет нечем.
        val мера = узел.path("measure")
        if (мера.isObject && мера.path("unit").asText("").isNotBlank()) {
            val единица = мера.path("unit").asText("").trim()
            require(measures.dimension(единица) != null) {
                "единица «$единица» вне справочника единиц — мерное требование не принимается"
            }
        }

        // Ворота «не мягче»: у мерного родителя требование узла той же
        // размерности и не за его границей (диф 29.09-бюджета). Бюджет, где
        // доли складываются, — отдельная ветвь по budget.requirement (ждёт
        // диф владельца); до неё мерный родитель проверяется этой границей.
        val родБаунд = measures.bound(родитель.doc.path("measure"))
        if (родБаунд != null && мера.isObject) {
            val дитяБаунд = measures.bound(мера)
                ?: throw IllegalArgumentException("показатель ребёнка не сводится к границе (единица или значение)")
            require(дитяБаунд.dimension == родБаунд.dimension) {
                "размерность показателя ребёнка (${дитяБаунд.unit}) не совпадает с родителем (${родБаунд.unit})"
            }
            require(неМягче(дитяБаунд, родБаунд)) {
                "граница ребёнка (${граница(дитяБаунд)}) мягче родителя (${граница(родБаунд)}) — требование узла родителя не ослабляет"
            }
        }

        // Ворота адекватности цены (ответ владельца 14.10): денежная мера
        // ребёнка вне порядка, адекватного изделиям проекта, — почти всегда
        // искажённая размерность (живой отбой: «стоимость терминала
        // ≤ 1e-6 млн $» — доллар за терминал, унаследовано от мусорной меры
        // раздачи целей SR-0039, в чьей формулировке числа нет вовсе). Пороги
        // сравниваются в базовой валюте единицы: кросс-курсы не сводим, шкала
        // своя у $/€, своя у ₽, приставка (млрд · млн · тыс) — множителем.
        // Немонетарные меры ворота не трогают.
        if (мера.isObject && мера.path("unit").asText("").isNotBlank() &&
            measures.dimension(мера.path("unit").asText().trim()) == ДЕНЕЖНАЯ_РАЗМЕРНОСТЬ
        ) {
            val цена = measures.bound(мера)
                ?: throw IllegalArgumentException("денежный показатель не сводится к границе (единица или значение)")
            require(ценаАдекватна(цена)) {
                val вБазе = ценаВБазе(цена)?.let { (значение, валюта) -> " ≈ ${число(значение)} $валюта" } ?: ""
                "ценовая граница ${граница(цена)}$вБазе вне адекватного порядка " +
                    "для изделия проекта — похоже на искажение размерности денежной меры"
            }
        }

        val payload = payload(узел, носитель, уровень, мера)
        val понятие = GeneratedOntology.of("requirement")
        return FormationProposal(
            concept = "requirement",
            payload = payload,
            basis = listOf(Basis.Entity(kind = "requirement", code = родитель.code)),
            verdict = Verdict.NEW,
            // Метка достоверности выводного требования — [П]: это предложение
            // модели по принятому требованию, не свидетельство из документа.
            sourceMark = SourceMark.П,
            confidence = узел.path("confidence").takeIf { it.isNumber }?.asDouble(),
            missing = FormationRules.нехватка(понятие, payload),
        )
    }

    /** Поля дочернего требования: уровень — узла (не модели), носитель — этот узел (id). */
    private fun payload(узел: JsonNode, носитель: String, уровень: String, мера: JsonNode): Map<String, String> {
        val поля = linkedMapOf<String, String>()
        поля["level"] = уровень
        val формулировка = узел.path("statement").asText("").trim()
        поля["statement"] = формулировка
        поля["title"] = узел.path("title").asText("").ifBlank { формулировка.take(60) }
        узел.path("category").asText("").ifBlank { null }?.let { поля["category"] = it }
        узел.path("ears_pattern").asText("").ifBlank { null }?.let { поля["ears_pattern"] = it }
        // Показатель — объектом-строкой (значениеУзлом разберёт обратно): единица
        // проверена воротами выше. Без единицы показатель в поле не кладётся.
        if (мера.isObject && мера.path("unit").asText("").isNotBlank()) поля["measure"] = мера.toString()
        // Носитель — этот узел. Хранится идентификатором: carrier читается через
        // store.byId (EntityRequirements), кодом не находится.
        поля["carrier"] = носитель
        return поля
    }

    /** Отпечаток среза — по промпту: тот же срез → тот же отпечаток → кэш вызова. */
    private fun отпечаток(project: String, node: String, промпт: String): String =
        MessageDigest.getInstance("SHA-256").digest("$project/$node/$промпт".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }.take(16)

    /** Предки узла по составу (id): сам узел и вся цепочка component.parent вверх. */
    private fun предки(область: Area, узел: Entity): Set<String> {
        val ids = linkedSetOf(узел.id)
        var текущий = узел
        while (true) {
            val ссылка = текущий.doc.path("parent").asText("").trim()
            if (ссылка.isEmpty()) break
            val родитель = store.byId(ссылка) ?: store.byCode(область, ссылка) ?: break
            if (!ids.add(родитель.id)) break // страховка от цикла в составе
            текущий = родитель
        }
        return ids
    }

    /**
     * Ступень «а» строго выше ступени «б» по порядку ПРАВИЛА
     * (requirement_level_rule), не по порядку значений перечня. Ступень вне
     * главной цепочки (scenario — ветвь, interface — род носителя) выше не
     * бывает: деривацию узла из неё ворота не пропускают.
     */
    private fun выше(а: String, б: String): Boolean {
        val ia = ступени.indexOf(а)
        val ib = ступени.indexOf(б)
        return ia in 0 until ib
    }

    /**
     * Ребёнок не мягче родителя: тот же знак и значение не за его границей
     * (родитель `≤ 180` → ребёнок `≤` и ≤ 180; родитель `≥ 0,9` → ребёнок `≥`
     * и ≥ 0,9). Противоположный знак — мягче (отказ). Родитель без оператора
     * (точное значение) направление не сторожит.
     */
    private fun неМягче(ребёнок: Bound, родитель: Bound): Boolean = when (родитель.op) {
        "<=", "<" -> (ребёнок.op == "<=" || ребёнок.op == "<") && ребёнок.value <= родитель.value + ДОПУСК
        ">=", ">" -> (ребёнок.op == ">=" || ребёнок.op == ">") && ребёнок.value >= родитель.value - ДОПУСК
        else -> true
    }

    // Адекватный порядок цены — инженерная константа ответа владельца 14.10,
    // не перечень истины: изделие проекта не стоит денежной мелочи и не
    // стоит больше миллиарда долларов (рублевая шкала шире на порядок курса;
    // € — с долларом одним порядком). «деньги» — строка-размерность полки
    // единиц (данные библиотеки, генерированного перечня нет — как БЮДЖЕТ).
    private val ДЕНЕЖНАЯ_РАЗМЕРНОСТЬ = "деньги"

    private val ПОРОГИ_ЦЕНЫ: Map<String, Pair<Double, Double>> = mapOf(
        "$" to (10.0 to 1e9), "€" to (10.0 to 1e9), "₽" to (1e3 to 1e11),
    )

    /** Денежные приставки → множитель к базовой валюте (латиница — коды полки: musd · mrub · brub). */
    private val ДЕНЕЖНЫЕ_ПРИСТАВКИ: List<Pair<String, Double>> = listOf(
        "млрд" to 1e9, "млн" to 1e6, "тыс" to 1e3, "b" to 1e9, "m" to 1e6, "k" to 1e3,
    )

    /** Значение границы в базовой валюте единицы и сама валюта; null — валюта не опознана. */
    private fun ценаВБазе(b: Bound): Pair<Double, String>? {
        val единица = b.unit.lowercase()
        val валюта = when {
            "$" in единица || "долл" in единица || "usd" in единица -> "$"
            "₽" in единица || "руб" in единица || "rub" in единица -> "₽"
            "€" in единица || "евро" in единица || "eur" in единица -> "€"
            else -> return null
        }
        val приставка = ДЕНЕЖНЫЕ_ПРИСТАВКИ.firstOrNull { единица.startsWith(it.first) }?.second ?: 1.0
        return b.value * приставка to валюта
    }

    /** Валюта не опознана — ворота молчат (осторожность: не отбиваем то, что не поняли). */
    private fun ценаАдекватна(b: Bound): Boolean {
        val (база, валюта) = ценаВБазе(b) ?: return true
        val (низ, верх) = ПОРОГИ_ЦЕНЫ.getValue(валюта)
        return база in низ..верх
    }

    private fun граница(b: Bound): String =
        listOfNotNull(b.op, число(b.value), b.unit).joinToString(" ")

    private fun число(d: Double): String =
        if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString()

    private fun имя(узел: JsonNode, номер: Int): String =
        узел.path("id").asText("").ifBlank { "#$номер" }

    private fun промпт(узел: Entity, уровень: String?, родители: List<Entity>): String {
        val шапка = buildString {
            appendLine("Ты — системный инженер. Выведи требования УЗЛА состава вниз из требований выше.")
            appendLine()
            appendLine("Узел: ${узел.code} «${узел.doc.path("name").asText(узел.code)}», вид ${узел.doc.path("kind").asText("")}.")
            уровень?.let { appendLine("Ступень требований этого узла: $it (её ставит система, тебе выбирать не нужно).") }
            appendLine()
            appendLine("Требования, из которых выводишь (родительские, узла или его предка):")
        }
        val список = родители.joinToString("\n") { р ->
            val уровеньР = р.doc.path("level").asText("")
            val текст = р.doc.path("statement").asText("").ifBlank { р.doc.path("title").asText("") }
            // Показатель — в каноне размерности (долг живой модели 10.10):
            // сырым «[≤ 180 Вт·с]» мы звали модель в размерность энергии, а
            // ворота у родителя-потолка держат мощность — 5 отказов было
            // сводимостью «Вт·с» против «Вт», хотя речь шла об одном бюджете.
            // Каноническая единица — та, в какой ворота сравнивают границы;
            // модели она же подсказывает, в какой размерности отвечать.
            val мера = р.doc.path("measure").takeIf { it.isObject }?.let {
                val канон = measures.canonical(it)
                if (канон != null) " [${число(канон.value)} ${канон.unit}]"
                else " [${it.path("value").asText("")}${it.path("unit").asText("")}]"
            } ?: ""
            "- ${р.code} (ступень $уровеньР) $текст$мера"
        }
        val хвост = buildString {
            appendLine()
            appendLine("Для каждого выводимого требования назови derives_from (код родителя из списка),")
            appendLine("формулировку узла шаблоном EARS и, если требование мерное, measure (доля бюджета")
            appendLine("родителя) с единицей из справочника — в той же размерности, что показатель")
            appendLine("родителя (он дан в канонической единице). Уровень не указывай — его ставит")
            appendLine("система по узлу. derives_from — только из списка, код не придумывай. Ответ — по схеме.")
        }
        return шапка + список + хвост
    }

    // --- порядок ступеней и вид узла → ступень: ЧИТАЮТСЯ ИЗ ПРАВИЛА -----------
    //
    // Истина одна — requirement_level_rule (диф 29.09). Второй копии порядка в
    // коде нет: и цепочка ступеней, и карта «вид узла → ступень» разбираются
    // из текста правила. Разошлась бы формулировка — разбор дал бы пусто, и
    // тест деривации упал бы сразу (ворота отбили бы всё).

    /** Главная цепочка ступеней: «project → system → segment → element → subsystem». */
    private val ступени: List<String> by lazy {
        GeneratedOntology.requirementLevelRule.substringBefore(";").substringAfter("—")
            .split("→").map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Вид узла → ступень: «system→system · subsystem|assembly|unit→subsystem». */
    private val видКУровню: Map<String, String> by lazy {
        val карта = linkedMapOf<String, String>()
        val хвост = GeneratedOntology.requirementLevelRule
            .substringAfter("выводится из вида узла:", "").substringBefore(";")
        for (пара in хвост.split("·")) {
            val части = пара.split("→")
            if (части.size == 2) {
                val уровень = части[1].trim()
                for (вид in части[0].split("|")) карта[вид.trim()] = уровень
            }
        }
        карта
    }

    private companion object {
        /** Пакет помощника: им, а не кодом понятия, отличают деривацию среди прогонов. */
        const val ПАКЕТ = "requirement_derivation"

        /** Вид вызова в журнале — свой у помощника, отдельно от синтеза поля. */
        const val KIND = "requirement_derivation"

        /** Причина запуска: ручной прогон помощника (истина synthesis_run.trigger). */
        const val РУЧНОЙ = "manual"

        const val БЮДЖЕТ = 16_000

        /** Допуск сравнения границ: числовой шум, не ослабление. */
        const val ДОПУСК = 1e-6

        /** Снятые с учёта статусы — из списка не берём (те же, что у отбора синтеза). */
        val СНЯТЫЕ = setOf("cancelled", "rejected")
    }
}
