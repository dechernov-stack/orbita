// Знания: материал → факты → план действий (ИНТЕЛЛЕКТУАЛЬНАЯ-ЗАГРУЗКА).
//
// Ключевая мысль поставки: «тип — вывод, факт — атом, задание — намерение».
// Документ не раскладывается по типу на входе; из него добываются ФАКТЫ с
// якорями, а из задания пользователя строится план действий, который
// принимается целиком, а не по одному факту.
package orbita.knowledge.api

/** Достоверность утверждения — семантика владельца, дословно. */
enum class SourceMark {
    /** Внутренний документ: наш материал. */
    И,

    /** Внешний источник, проверенный на указанную дату. */
    В,

    /** Предлагаемая цель или инженерно-финансовое допущение, требующее подтверждения. */
    П,
}

/**
 * Факт — атом знания. Живёт после загрузки: на него ссылаются параметры,
 * требования и риски, а повторная загрузка источника пересчитывает факты
 * и помечает зависимое «источник обновлён».
 */
data class Fact(
    val id: String,
    val subject: String,
    val predicate: String,
    /** Значение как есть: величина парой либо текст. */
    val value: String,
    val unit: String?,
    /** Якорь блока канона: по нему факт проверяется, не выходя из системы. */
    val anchor: String?,
    val mark: SourceMark,
    val confidence: Double?,
    val material: String,
)

/** Действие плана: что именно система создаст или изменит, если план принять. */
data class PlannedAction(
    val kind: String,
    val title: String,
    /** Что появится в модели — словами, до нажатия. */
    val effect: String,
    val factIds: List<String>,
    /** Не выполняется само: план принимает человек. */
    val requiresDecision: Boolean = true,
)

/**
 * Задание загрузки: источник плюс намерение пользователя.
 *
 * Задание вне каталога не додумывается: система предлагает ближайшее и
 * спрашивает (правило поставки §3).
 */
data class IntakeTask(
    val id: String,
    val material: String,
    val intent: String,
    val facts: List<Fact>,
    val plan: List<PlannedAction>,
    /** Почему план такой — и что осталось непонятным. */
    val note: String,
)

interface Intake {
    /** Положить материал: снимок и карточка. Разбор идёт следом. */
    fun putMaterial(project: String, name: String, kind: String, text: String, author: String): String

    /** Собрать задание: факты из материала и план действий из намерения. */
    fun plan(project: String, material: String, intent: String, author: String): IntakeTask

    /** Принять план целиком: действия идут штатными каналами. */
    fun accept(project: String, task: String, chosen: List<Int>, author: String): List<String>

    fun facts(project: String): List<Fact>
}
