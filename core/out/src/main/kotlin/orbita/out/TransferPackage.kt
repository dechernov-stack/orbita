// Пакет передачи в детальное проектирование (TZ-OUT-006, STEP-6 §2.5).
// Эталон spec/presentation_semantics.py, один в один.
//
// Собирается ОДНОЙ операцией. Отсутствующая часть выявляется и называется;
// объекты не в статусе Baseline перечисляются ПРЕДУПРЕЖДЕНИЕМ, а не блокируют
// сборку: на ранних фазах небазированное — норма, а вот незамеченное
// небазированное — нет.
package orbita.out

import com.fasterxml.jackson.databind.JsonNode

/** Обязательные части пакета передачи. */
val PACKAGE_PARTS = listOf(
    "requirements",         // базированные требования
    "architecture",         // архитектурная модель с распределением
    "parameters",           // параметры с резервами
    "verification_matrix",  // матрица верификации
    "modeling_reports",     // отчёты моделирования
)

data class TransferPackageResult(
    val complete: Boolean,
    val missing: List<String>,
    /** Идентификаторы объектов не в статусе Baseline. */
    val warnings: List<String>,
)

fun transferPackage(model: JsonNode): TransferPackageResult {
    val missing = PACKAGE_PARTS.filterNot { model.has(it) }
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
