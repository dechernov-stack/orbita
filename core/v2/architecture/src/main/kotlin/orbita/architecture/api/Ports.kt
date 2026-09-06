// Архитектура: компонент как УЗЕЛ MBSE (КОМПОНЕНТ-ГРАНИ-ПРИМЕР-ПО §2).
//
// Компонент — не строка состава с массой, а узел, к которому цепляется всё:
// функции, стыки, обмены, режимы, параметры, бюджеты, требования,
// верификация, конфигурация изделия, поставщик, документы. Поэтому карточка
// компонента здесь — не набор полей, а НАБОР ГРАНЕЙ.
//
// Второе, ради чего модуль существует: пустая грань не молчит. Она говорит,
// что здесь ожидается и к какой точке — лестницей зрелости с полки
// (ГЛУБИНА-КОМПОНЕНТА-ПО-ШАГАМ §1), а не памятью инженера.
package orbita.architecture.api

/** Род компонента (как в Capella PA). */
enum class Nature {
    /** Физический носитель: масса, габарит, тепло, крепление. */
    NODE,

    /** Поведенческий: функции, режимы, ресурсы вычисления. Обязан быть развёрнут на node. */
    BEHAVIOUR,
    ;

    companion object {
        fun of(text: String?): Nature =
            if (text?.trim()?.lowercase() == "behaviour") BEHAVIOUR else NODE
    }
}

/** Класс зрелости значения параметра и резерв по умолчанию (ГЛУБИНА §2). */
enum class Maturity(val reservePercent: Int) {
    ESTIMATED(20),
    MODIFIED(10),
    OFF_THE_SHELF(5),
    MEASURED(2),
    ;

    companion object {
        fun of(text: String?): Maturity =
            entries.firstOrNull { it.name.equals(text?.trim()?.replace("-", "_"), ignoreCase = true) } ?: ESTIMATED
    }
}

data class ComponentView(
    val id: String,
    val code: String,
    val name: String,
    val level: Int,
    val nature: Nature,
    val kind: String,
    val parent: String?,
    val external: Boolean,
    val templateRef: String?,
    val applicability: String?,
    val version: Int,
)

data class ParameterView(
    val id: String,
    val key: String,
    val name: String,
    val target: String,
    val measure: String,
    val origin: String,
    val maturity: Maturity,
    /** Неопределённость ±%; вместе с классом зрелости даёт резерв свёрток. */
    val uncertainty: Double,
    val requiredTo: String,
    val basis: String?,
    val version: Int,
)

/** Строка грани: что там есть, и на какую сущность это ссылается. */
data class FacetLine(val what: String, val ref: String? = null)

/**
 * Грань карточки компонента.
 *
 * @property requiredTo точка, к которой грань обязана быть закрыта (из лестницы полки)
 * @property expected чего здесь ждут, когда пусто — вместо пустоты
 */
data class Facet(
    val key: String,
    val title: String,
    val lines: List<FacetLine>,
    val requiredTo: String? = null,
    val expected: String? = null,
) {
    val empty: Boolean get() = lines.isEmpty()
}

/** Разрыв ступени: чего нет у узла к точке. */
data class Gap(val component: String, val facet: String, val gate: String, val what: String)

data class ComponentCard(
    val component: ComponentView,
    val facets: List<Facet>,
    /** Ступень текущей точки: что мешает считать её закрытой. */
    val gaps: List<Gap>,
)

/** Слой архитектуры для экрана: OA · SA · Цепочки · LA · PA. */
data class Layer(val key: String, val title: String, val lines: List<FacetLine>)

interface Architecture {
    fun components(project: String): List<ComponentView>
    fun parameters(project: String, componentCode: String? = null): List<ParameterView>

    /** Карточка гранями; лестница считается к указанной точке. */
    fun card(project: String, code: String, gate: String = "SDR"): ComponentCard

    /** Разрывы ступени по всем узлам — этим ворота считают готовность. */
    fun gaps(project: String, gate: String): List<Gap>

    /** Слои архитектуры для экрана сцены 8. */
    fun layers(project: String): List<Layer>

    /**
     * Развернуть поведенческий компонент на носителе (`deployed_on`).
     * Behaviour без развёртывания — брак модели: Capella такого не соберёт.
     */
    fun deploy(project: String, behaviour: String, node: String, author: String, rationale: String)
}
