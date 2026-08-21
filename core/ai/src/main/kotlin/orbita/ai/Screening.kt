// Структурный фильтр предложений (STEP-5 §1.3) — ключевое отличие контура:
// предложение проходит те же правила, что и рукописное требование, ДО показа
// инженеру. До человека доходит только состоятельное; остальное возвращается
// в очередь переделки с перечнем замечаний, пригодным для повторного пакета.
//
// Здесь НЕТ ни одного собственного правила. Каждая строка ниже — вызов функции
// из core/req, той же самой, что применяется к рукописному вводу. Отдельная
// «облегчённая» проверка для предложений ИИ была бы дефектом: именно там
// и завелось бы расхождение между сгенерированными и рукописными требованиями
// (STEP-5, ловушка 1).
package orbita.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.req.ProductNode
import orbita.req.QualityControl
import orbita.req.allocationConsistent
import orbita.req.interfaceAllocationValid
import orbita.req.residualOk
import orbita.req.riskIssues
import orbita.req.rollupCheck
import orbita.req.verificationPlanIssues

/**
 * Что нужно правилам сверх самого предложения: дерево изделия для проверки
 * области родителя и родительское требование для свёртки бюджета.
 */
data class ScreeningContext(
    val productTree: Map<String, ProductNode> = emptyMap(),
    val parentRequirement: JsonNode? = null,
    /** Уже принятые потомки того же родителя — свёртка считается по всей группе. */
    val siblings: List<JsonNode> = emptyList(),
)

/** Отбракованное предложение с замечаниями, пригодными для повторного пакета. */
data class Screened(val item: JsonNode, val issues: List<String>)

/**
 * Отчёт по пакету: сколько предложено, сколько отбраковано и по каким правилам
 * (STEP-5 §1.3). Показывает не только принятое, но и отброшенное — иначе
 * генерация в секундах даёт реестр, который никто не проверял (ловушка 3).
 */
data class ScreenReport(
    val shown: List<JsonNode>,
    val rework: List<Screened>,
) {
    val proposed: Int get() = shown.size + rework.size

    /** Сколько предложений отбраковано по каждому источнику правил. */
    val byRule: Map<String, Int>
        get() = rework.flatMap { s -> s.issues.map { it.substringBefore(':') }.distinct() }
            .groupingBy { it }.eachCount().toSortedMap()

    /**
     * Контекст повторного промпт-пакета: отбракованные предложения вместе
     * с замечаниями. Инженер этого не видит — переделка идёт к модели.
     */
    fun reworkContext(mapper: ObjectMapper = ObjectMapper()): ObjectNode {
        val root = mapper.createObjectNode()
        root.put("proposed", proposed)
        root.put("rejected", rework.size)
        val arr = root.putArray("rework")
        rework.forEach { s ->
            val n = arr.addObject()
            n.set<ObjectNode>("item", s.item)
            val issues = n.putArray("issues")
            s.issues.forEach { issues.add(it) }
        }
        return root
    }
}

class ProposalScreening(private val quality: QualityControl = QualityControl()) {

    /**
     * Замечания к предложению риска (шаг 7). Вызывается тот же riskIssues,
     * что применяется к рукописной записи реестра: своей упрощённой версии
     * для предложений ИИ нет и здесь (STEP-7-9, ловушка 3).
     */
    fun riskProposalIssues(item: JsonNode): List<String> {
        val issues = riskIssues(item).map { "риск: $it" }.toMutableList()
        if (!residualOk(item)) issues += "риск: остаточный риск выше исходного"
        return issues
    }

    /**
     * Замечания к одному предложению. Каждая ветка — вызов правила core/req;
     * названия источников соответствуют таблице STEP-5 §1.3.
     */
    fun issues(item: JsonNode, ctx: ScreeningContext = ScreeningContext()): List<String> {
        // Запись реестра рисков — не требование: к ней применимы правила риска,
        // а правила формулировки требования неприменимы.
        if (item.path("id").asText("").startsWith("RSK-")) return riskProposalIssues(item)

        val issues = mutableListOf<String>()

        // requirements_semantics + constraint_semantics: качество формулировки,
        // оператор условия, единица, согласованность формулировки с оператором
        issues += quality.check(item).map { "качество: $it" }

        // verification_semantics: описан подход, указано средство, уровень
        // проверки, закрывающее событие — по КАЖДОМУ событию плана.
        //
        // Здесь был вызов verificationIssues(item), читающего блок `verification`.
        // CR-003 заменил его массивом `verification_events`, и поля с таким именем
        // в схеме требования больше нет: правило возвращало «метод верификации
        // не назначен» ЛЮБОМУ требованию нынешней формы, поэтому ни одно
        // предложение по требованию до инженера не доходило вовсе (шаг 15 §3).
        // verificationIssues остаётся правилом отдельного события и применяется
        // к нему в verificationPlanIssues, а не к требованию целиком.
        //
        // Пустой план замечания не даёт — и не должен: полнота верификации есть
        // условие БАЗИРОВАНИЯ, а не сохранения (CR-002). Рукописное требование
        // с пустым планом тоже сохраняется и блокируется только на базировании,
        // а фильтр обязан применять к предложению ТЕ ЖЕ правила, не строже.
        issues += verificationPlanIssues(item).map { "верификация: $it" }

        // traceability_semantics: распределение внутри области родителя,
        // интерфейсное требование — на интерфейс с двумя сторонами
        ctx.parentRequirement?.let { parent ->
            val (ok, reason) = allocationConsistent(parent, item, ctx.productTree)
            if (!ok) issues += "трассировка: $reason"
        }
        if (ctx.productTree.isNotEmpty()) {
            val (ok, reason) = interfaceAllocationValid(item, ctx.productTree)
            if (!ok) issues += "трассировка: $reason"
        }

        // constraint_semantics: свёртка бюджета по группе распределённых потомков
        ctx.parentRequirement?.path("mop")?.takeIf { !it.isMissingNode && !it.isEmpty }?.let { parentMop ->
            // listOf(item), а не + item: JsonNode — Iterable, и «список + узел»
            // приклеил бы ДЕТЕЙ узла вместо самого узла
            val childMops = (ctx.siblings + listOf(item)).map { it.path("mop") }
                .filter { !it.isMissingNode && !it.isEmpty }
            val roll = rollupCheck(parentMop, childMops)
            if (roll.applicable) {
                roll.error?.let { issues += "свёртка: $it" }
                if (roll.consistent == false) {
                    issues += "свёртка: сумма потомков ${roll.aggregate} не укладывается в бюджет ${roll.limit}"
                }
            }
        }
        return issues
    }

    /** Предложение с замечаниями инженеру не показывается. */
    fun screen(items: List<JsonNode>, ctx: ScreeningContext = ScreeningContext()): ScreenReport {
        val shown = mutableListOf<JsonNode>()
        val rework = mutableListOf<Screened>()
        items.forEach { item ->
            val found = issues(item, ctx)
            if (found.isEmpty()) shown.add(item) else rework += Screened(item, found)
        }
        return ScreenReport(shown, rework)
    }

    /**
     * Замечания, которые находятся ПРАВИЛАМИ и не требуют обращения к модели
     * (STEP-5 §2.2, ловушка 4): отсутствующий оператор, неизмеримое определение,
     * конъюнкция. К модели уходит только то, что требует переформулирования.
     */
    fun deterministicIssues(item: JsonNode): List<String> = quality.check(item)

    /** Предложения, по которым имеет смысл платить за обращение к модели. */
    fun needsModel(items: List<JsonNode>): List<JsonNode> =
        items.filter { deterministicIssues(it).isNotEmpty() }
}
