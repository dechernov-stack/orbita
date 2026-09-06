// Сравнение вариантов построения: пороги, Парето — и НИ ОДНОГО итогового балла.
//
// Свёртка вариантов в один балл прячет решение внутрь коэффициентов: тот,
// кто выбирает веса, и выбирает вариант — молча. Поэтому здесь: каждая
// метрика своей строкой, порог называет себя, отсеянный вариант говорит,
// каким порогом он отсеян, а из оставшихся показан фронт Парето. Выбор
// делает человек и записывает обоснованием (сцена 7).
package orbita.models.internal

import orbita.kernel.api.Area
import orbita.kernel.api.EntityStore
import orbita.models.api.ComparisonView
import orbita.models.api.MetricView
import orbita.models.api.VariantView
import orbita.models.api.Variants

class EntityVariants(private val store: EntityStore) : Variants {

    override fun compare(project: String): ComparisonView {
        val область = Area.Project(project)
        val пороги = store.list(область, "criterion").associateBy { it.doc.path("key").asText(it.code) }

        val варианты = store.list(область, "constellation_variant").map { вариант ->
            val метрики = вариант.doc.path("metrics").map { метрика ->
                val ключ = метрика.path("key").asText()
                val порог = пороги[ключ]
                val хужеЕсли = порог?.doc?.path("worse_if")?.asText("greater") ?: "greater"
                val значениеПорога = порог?.doc?.path("threshold")?.takeIf { it.isNumber }?.asDouble()
                val значение = метрика.path("value").asDouble()
                MetricView(
                    key = ключ,
                    title = порог?.doc?.path("title")?.asText(ключ) ?: метрика.path("title").asText(ключ),
                    value = значение,
                    unit = метрика.path("unit").asText(""),
                    threshold = значениеПорога,
                    worseIf = хужеЕсли,
                    passed = значениеПорога == null ||
                        if (хужеЕсли == "greater") значение <= значениеПорога else значение >= значениеПорога,
                    group = метрика.path("group").asText(порог?.doc?.path("group")?.asText("") ?: ""),
                )
            }
            val отсеян = метрики.firstOrNull { !it.passed }
            VariantView(
                code = вариант.code,
                name = вариант.doc.path("name").asText(вариант.code),
                metrics = метрики,
                rejectedBy = отсеян?.let {
                    "${it.title}: ${it.value} ${it.unit} против порога ${it.threshold} " +
                        (if (it.worseIf == "greater") "(не больше)" else "(не меньше)")
                },
                pareto = false,
            )
        }

        val прошедшие = варианты.filter { it.rejectedBy == null }
        val сПарето = варианты.map { в ->
            if (в.rejectedBy != null) в
            else в.copy(pareto = прошедшие.none { другой -> доминирует(другой, в) })
        }

        return ComparisonView(
            variants = сПарето,
            note = when {
                варианты.isEmpty() -> "вариантов построения нет: сцена 7 сравнивать нечего"
                прошедшие.isEmpty() -> "все варианты отсеяны порогами — пороги или варианты придётся пересмотреть"
                else -> "итогового балла нет: выбор делает человек и записывает обоснованием"
            },
        )
    }

    /** Доминирование по Парето: не хуже нигде и лучше хотя бы в одном. */
    private fun доминирует(a: VariantView, b: VariantView): Boolean {
        if (a.code == b.code) return false
        val общие = a.metrics.mapNotNull { м -> b.metrics.firstOrNull { it.key == м.key }?.let { м to it } }
        if (общие.isEmpty()) return false
        var лучшеГдеТо = false
        общие.forEach { (х, у) ->
            val лучше = if (х.worseIf == "greater") х.value < у.value else х.value > у.value
            val хуже = if (х.worseIf == "greater") х.value > у.value else х.value < у.value
            if (хуже) return false
            if (лучше) лучшеГдеТо = true
        }
        return лучшеГдеТо
    }
}
