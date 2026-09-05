// Матрица покрытия (МОДЕЛЬ-ДАННЫХ §5: «всё вычисляется»).
//
// Покрытие не хранится и не отмечается руками: оно СЧИТАЕТСЯ по связям
// «цель → нужда» и «сервис → нужда». Поэтому расхождения между матрицей и
// действительностью не бывает — матрица и есть действительность.
package orbita.formulation.api

/** Состояние нужды в матрице: чем она закрыта и чем ещё нет. */
data class NeedCoverage(
    val id: String,
    val code: String,
    val statement: String,
    /** Носитель: стейкхолдер, чья это нужда. Пусто — нужда ничья. */
    val ownerCode: String?,
    val ownerName: String?,
    val goals: List<String>,
    val services: List<String>,
) {
    val covered: Boolean get() = goals.isNotEmpty() && services.isNotEmpty()

    /** Чего не хватает — словами, а не молчанием пустой клетки. */
    val gap: String?
        get() = when {
            ownerCode == null -> "нет носителя: за нужду никто не отвечает"
            goals.isEmpty() && services.isEmpty() -> "нет ни цели, ни сервиса"
            goals.isEmpty() -> "нет цели: непонятно, какого результата ждём"
            services.isEmpty() -> "нет сервиса: нечем закрыть"
            else -> null
        }
}

data class CoverageMatrix(
    val needs: List<NeedCoverage>,
    /** Стейкхолдеры без нужд — край матрицы, который иначе теряется. */
    val stakeholdersWithoutNeeds: List<String>,
) {
    val total: Int get() = needs.size
    val covered: Int get() = needs.count { it.covered }

    val summary: String
        get() = when {
            needs.isEmpty() -> "нужд ещё нет: матрица заполнится на сцене 3"
            covered == total -> "покрыты все нужды: $total из $total"
            else -> "покрыто $covered из $total; остальные показывают, чего не хватает"
        }
}

interface Formulation {
    fun coverage(project: String): CoverageMatrix
}
