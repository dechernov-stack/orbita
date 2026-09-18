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
    /**
     * Чем нужда закрывается по истине (`need.coverage_expected`): `service` ·
     * `constraint` · `programme` · `requirement`. Пусто — истина о роли
     * носителя молчит, и сервиса с такой нужды никто не спрашивает.
     */
    val expected: String? = SERVICE,
) {
    /** Ждёт ли нужда сервиса: только с таких он и спрашивается. */
    val expectsService: Boolean get() = expected == SERVICE

    /**
     * Закрыта ли нужда: цель нужна всякой, сервис — только той, что его ждёт.
     * Нужда регулятора закрывается ограничением (сцены 5/8), нужда поставщика
     * и партнёра — пакетом работ и оценкой (сцена 12), и «нет сервиса» о них
     * говорить нельзя (истина 17.09, `need.coverage_rule`).
     */
    val covered: Boolean get() = goals.isNotEmpty() && (!expectsService || services.isNotEmpty())

    /** Чего не хватает — словами, а не молчанием пустой клетки. */
    val gap: String?
        get() = when {
            ownerCode == null -> "нет носителя: за нужду никто не отвечает"
            goals.isEmpty() && expectsService && services.isEmpty() -> "нет ни цели, ни сервиса"
            goals.isEmpty() -> "нет цели: непонятно, какого результата ждём"
            expectsService && services.isEmpty() -> "нет сервиса: нечем закрыть"
            else -> null
        }

    /** Чем закрывается, если не сервисом, — рядом с «покрыта», а не вместо неё. */
    val note: String?
        get() = if (expectsService) null else "сервис не нужен: закрывается — ${expected ?: "не названо истиной"}"

    companion object {
        /** Вид покрытия, который спрашивает сервис (истина онтологии). */
        const val SERVICE: String = "service"
    }
}

data class CoverageMatrix(
    val needs: List<NeedCoverage>,
    /** Стейкхолдеры без нужд — край матрицы, который иначе теряется. */
    val stakeholdersWithoutNeeds: List<String>,
) {
    val total: Int get() = needs.size
    val covered: Int get() = needs.count { it.covered }

    /** Сколько нужд закрывается не сервисом — и чем именно (истина 17.09). */
    val otherCoverage: Map<String, Int>
        get() = needs.filter { !it.expectsService }
            .groupingBy { it.expected ?: "не названо" }
            .eachCount()

    val summary: String
        get() = when {
            needs.isEmpty() -> "нужд ещё нет: матрица заполнится на сцене 3"
            covered == total -> "покрыты все нужды: $total из $total" + прочее()
            else -> "покрыто $covered из $total; остальные показывают, чего не хватает" + прочее()
        }

    private fun прочее(): String {
        if (otherCoverage.isEmpty()) return ""
        return "; сервиса не ждут: " +
            otherCoverage.entries.sortedBy { it.key }.joinToString(", ") { (вид, n) -> "$вид $n" }
    }
}

interface Formulation {
    fun coverage(project: String): CoverageMatrix
}
