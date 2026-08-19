// Валидация как самостоятельный объект VA-NNNN (CR-003/ADR-019).
// Эталон spec/traceability_semantics.py, один в один.
//
// Верификация ≠ валидация (ловушка 1): первая — соответствие требованиям,
// вторая — пригодность для задачи пользователя в предполагаемой среде.
// Валидация привязывается к ожиданию стейкхолдера (нужде или сервису),
// НЕ к требованию, и даёт отдельную матрицу.
package orbita.req

import com.fasterxml.jackson.databind.JsonNode

/** Замечания к активности валидации; пустой список — активность описана полно. */
fun validationIssues(activity: JsonNode): List<String> {
    val issues = mutableListOf<String>()
    val target = activity.path("target").asText("")
    if (target.startsWith("RQ-")) {
        issues += "валидация привязана к требованию, а не к ожиданию стейкхолдера"
    }
    if (!target.startsWith("ND-") && !target.startsWith("SV-")) {
        issues += "цель валидации не указана"
    }
    if (activity.path("conops_ref").asText("").isBlank()) {
        issues += "нет ссылки на сценарий ConOps"
    }
    if (activity.path("product_kind").asText("").isBlank()) {
        issues += "не указано, на чём выполняется валидация (модель, макет, изделие)"
    }
    return issues
}
