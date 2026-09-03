// Пакет передачи в детальное проектирование (TZ-OUT-006, шаг 11.3).
// Правило полноты — эталон spec/presentation_semantics.py, один в один.
//
// Собирается ОДНОЙ операцией. Отсутствующая часть выявляется и называется;
// объекты не в статусе Baseline перечисляются ПРЕДУПРЕЖДЕНИЕМ, а не блокируют
// сборку: на ранних фазах небазированное — норма, а вот незамеченное
// небазированное — нет.
package orbita.out

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

/** Обязательные части пакета передачи (эталон). */
val PACKAGE_PARTS = listOf(
    "requirements",         // базированные требования
    "architecture",         // архитектурная модель с распределением
    "parameters",           // параметры с резервами
    "verification_matrix",  // матрица верификации
    "modeling_reports",     // отчёты моделирования
)

/**
 * Полный состав шага 11.3: части эталона плюс матрица валидации и реестр
 * рисков. Правило полноты то же самое, применённое к большему перечню, —
 * а не второе правило: эталонные проверки проходят на эталонном перечне
 * без изменений.
 */
val FULL_PACKAGE_PARTS = PACKAGE_PARTS + listOf("validation_matrix", "risk_register")

data class TransferPackageResult(
    val complete: Boolean,
    val missing: List<String>,
    /** Идентификаторы объектов не в статусе Baseline. */
    val warnings: List<String>,
)

fun transferPackage(model: JsonNode, parts: List<String> = PACKAGE_PARTS): TransferPackageResult {
    val missing = parts.filterNot { model.has(it) }
    val notBaselined = model.path("requirements")
        .filter { it.path("status").asText("") != "Baseline" }
        .map { it.path("id").asText() }
        .sorted()
    return TransferPackageResult(
        complete = missing.isEmpty(),
        missing = missing,
        warnings = notBaselined,
    )
}

object TransferPackages {

    /**
     * Сборка пакета передачи одной операцией из среза модели и посчитанных
     * матриц. Часть, для которой в модели нет материала, НЕ подставляется
     * пустым списком: пустой список выглядел бы собранной частью, и вердикт
     * полноты перестал бы что-либо выявлять.
     *
     * Статус требования дублируется на верхний уровень записи: правило
     * предупреждений эталона читает `status`, и класть его глубже значило бы
     * разойтись с эталоном на ровном месте.
     */
    fun assemble(
        model: JsonNode,
        verificationMatrix: List<VerificationSummaryRow>,
        validationMatrix: List<ValidationMatrixRow>,
        maturity: MaturityReport,
        mapper: ObjectMapper = ObjectMapper(),
    ): ObjectNode {
        val pkg = mapper.createObjectNode()

        if (!model.path("requirements").isEmpty) {
            val requirements = pkg.putArray("requirements")
            model.path("requirements").forEach { r ->
                val n = (r.deepCopy() as ObjectNode)
                n.put("status", r.path("lifecycle").path("status").asText(""))
                requirements.add(n)
            }
        }

        // Архитектура С РАСПРЕДЕЛЕНИЕМ: дерево элементов и связи требование →
        // элемент. Дерево без распределения — картинка, а не модель.
        if (!model.path("components").isEmpty) {
            val architecture = pkg.putObject("architecture")
            architecture.set<ObjectNode>("components", model.path("components").deepCopy())
            val allocations = architecture.putArray("allocations")
            model.path("requirements").forEach { r ->
                r.path("allocated_to").forEach { a ->
                    allocations.addObject()
                        .put("requirement", r.path("id").asText())
                        .put("component", a.path("component").asText())
                }
            }
        }

        // ADR-050: диаграммы внешней модели — ПРИЛОЖЕНИЕ к пакету обзора, не
        // доказательство покрытия: обзор смотрит на них, готовность — нет.
        val illustrations = model.path("requirements")
            .flatMap { r -> r.path("illustrated_by").map { r.path("id").asText() to it.asText() } }
        if (illustrations.isNotEmpty()) {
            val appendix = pkg.putArray("appendices")
            illustrations.forEach { (rq, me) ->
                appendix.addObject().put("kind", "diagram").put("requirement", rq).put("element", me)
                    .put("note", "иллюстрация к обзору; покрытием не считается")
            }
        }
        // Параметры С РЕЗЕРВАМИ — реестр TPM из посчитанных бюджетов: текущее
        // значение, цель, запас и требуемый запас. Бюджетов нет — части нет.
        model.path("budgets").firstOrNull { it.path("kind").asText() == "tpm" }
            ?.let { pkg.set<ObjectNode>("parameters", it.path("rows").deepCopy()) }

        if (verificationMatrix.isNotEmpty()) {
            val verification = pkg.putArray("verification_matrix")
            verificationMatrix.forEach { verification.add(mapper.valueToTree<JsonNode>(it)) }
        }
        if (validationMatrix.isNotEmpty()) {
            val validation = pkg.putArray("validation_matrix")
            validationMatrix.forEach { validation.add(mapper.valueToTree<JsonNode>(it)) }
        }

        model.path("risks").takeIf { it.isArray && !it.isEmpty }
            ?.let { pkg.set<ObjectNode>("risk_register", it.deepCopy()) }

        // Отчёты моделирования — обоснование чисел пакета (TZ-OUT-006).
        model.path("options").takeIf { it.isArray && !it.isEmpty }
            ?.let { pkg.set<ObjectNode>("modeling_reports", it.deepCopy()) }

        // Отчёт зрелости к контрольной точке прилагается к пакету (шаг 11.4):
        // что базировано, что нет, какие разрывы — первый взгляд перед обзором.
        pkg.set<ObjectNode>("maturity_report", mapper.valueToTree(maturity))

        // Вердикт полноты — тем же правилом, что в эталоне, по полному перечню
        val verdict = transferPackage(pkg, FULL_PACKAGE_PARTS)
        pkg.set<ObjectNode>("transfer", mapper.valueToTree(verdict))
        return pkg
    }
}
