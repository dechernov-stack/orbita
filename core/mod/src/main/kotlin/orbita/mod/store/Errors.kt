// Ошибки хранилища. Ограничения обеспечивает DDL (db/migrations/V001__init.sql);
// здесь нарушения переводятся в понятные отказы со ссылкой на требование.
package orbita.mod.store

import org.postgresql.util.PSQLException
import java.sql.SQLException

open class StoreException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Нарушение модели данных (CHECK-ограничения DDL: единицы, происхождение, шаблоны ID). */
class ModelViolationException(message: String, cause: Throwable? = null) : StoreException(message, cause)

/** Изменение Baseline-объекта вне процедуры изменения (TZ-COM-003). */
class BaselineChangeException(message: String, cause: Throwable? = null) : StoreException(message, cause)

/** Повторное использование ID (TZ-MOD-007). */
class IdReuseException(message: String, cause: Throwable? = null) : StoreException(message, cause)

/** Циклическая зависимость параметров (TZ-MOD-005). */
class CycleException(message: String) : StoreException(message)

/** Перевод ошибок PostgreSQL в отказы модели по имени нарушенного ограничения. */
internal fun <T> mappingConstraints(block: () -> T): T {
    try {
        return block()
    } catch (e: SQLException) {
        val constraint = (e as? PSQLException)?.serverErrorMessage?.constraint
        val mapped: StoreException? = when (constraint) {
            "objects_current" -> IdReuseException(
                "TZ-MOD-007: object id is already in use; ids are stable and must not be reused", e
            )
            "id_pattern" -> ModelViolationException(
                "TZ-MOD-007 (id_pattern): object id must match " +
                    "(${orbita.mod.model.CoreType.entries.joinToString("|") { it.idPrefix }})-NNNN",
                e,
            )
            // CR-005/ADR-021: ограничения миграции V008 на входы моделирования
            "scenario_refs_typed" -> ModelViolationException(
                "CR-005 (scenario_refs_typed): ссылки сценария обязаны вести на хранимые объекты " +
                    "нужного вида — CN, SP, DM, GS, PA; требуемый вид задаёт поле, а не префикс",
                e,
            )
            "scenario_input_versions" -> ModelViolationException(
                "TZ-COM-006 (scenario_input_versions): версии входов не зафиксированы — " +
                    "результат такого сценария невоспроизводим",
                e,
            )
            "constellation_sso_ltan" -> ModelViolationException(
                "TZ-BAL-003 (constellation_sso_ltan): для ССО обязателен LTAN — " +
                    "без него прецессия не определена",
                e,
            )
            "scenario_pattern" -> ModelViolationException(
                "TZ-MOD-007: scenario id must match SC-NNNN", e
            )
            "unit_not_blank" -> ModelViolationException(
                "TZ-MOD-004: unit must not be blank", e
            )
            "prov_has_source" -> ModelViolationException(
                "TZ-COM-005: provenance.source is required", e
            )
            "value_or_formula" -> ModelViolationException(
                "TZ-MOD-004: either value or formula must be set", e
            )
            "ai_needs_accept" -> ModelViolationException(
                "TZ-AI-004: ai_proposed value requires an explicit 'accepted' flag", e
            )
            "no_self_link" -> ModelViolationException(
                "TZ-COM-002: link endpoints must differ", e
            )
            "interval_sane" -> ModelViolationException(
                "TZ-COM-003: version interval must satisfy valid_to > valid_from", e
            )
            else -> null
        }
        if (mapped != null) throw mapped
        // Триггер objects_baseline_guard поднимает ошибку с текстом «TZ-COM-003: …»
        if (e.message?.contains("TZ-COM-003") == true) throw BaselineChangeException(e.message!!, e)
        throw e
    }
}
