// Модели системы (ШАБЛОН-МОДЕЛИ-СИСТЕМЫ).
//
// Модель — не файл, а ОТВЕТ на инженерный вопрос. Отсюда всё остальное:
//   · готовность к точке — «модель дала ответ», а не «модель заведена»;
//   · входы модели — только параметры узлов и стыков; параметра нет —
//     разрыв ведёт к анкете узла, а не к пустому графику;
//   · прокси всегда помечен на выходе, и замена прокси расчётом — версия;
//   · свёртка печатает ОБА резерва: по классу зрелости параметра и
//     системный по ступени, — потому что это разные вещи и складывать их
//     молча нельзя.
package orbita.models.api

/** Чем модель считает сегодня — честно. */
enum class Tool {
    /** Свой расчёт. */
    CALC,

    /** Прокси: правдоподобная оценка вместо расчёта — помечается на выходе. */
    PROXY,

    /** Ещё не построена. */
    BUILD,
    ;

    companion object {
        fun of(text: String?): Tool =
            entries.firstOrNull { it.name.equals(text?.trim(), ignoreCase = true) } ?: BUILD
    }
}

/** Состояние проверки модели (СХЕМЫ-ПОЛЕЙ: system_model.verification_status). */
enum class Verification { UNVERIFIED, VERIFIED, VALIDATED }

data class ModelView(
    val code: String,
    val name: String,
    val question: String,
    val requiredTo: String,
    val tool: Tool,
    val verification: Verification,
    /** Стык, к которому привязана модель потока или линка. */
    val interfaceCode: String?,
    /** Входы: параметры узлов и стыков, которых модель ждёт. */
    val inputs: List<String>,
    /** Последний прогон: когда и какой версии. */
    val lastRun: RunView?,
    /** Чего не хватает, чтобы модель дала ответ. */
    val gaps: List<String>,
)

/** Прогон модели: снимок входов и версия — иначе ответ нечем повторить. */
data class RunView(
    val code: String,
    val model: String,
    val at: String,
    val by: String,
    /** Снимок входов: параметр → версия на момент расчёта. */
    val inputsSnapshot: Map<String, Int>,
    val outputs: Map<String, String>,
    val proxy: Boolean,
    /** Входы, изменившиеся после прогона: ответ устарел. */
    val staleInputs: List<String> = emptyList(),
)

/** Строка свёртки: узел, его вклад и оба резерва. */
data class BudgetLine(
    val component: String,
    val value: Double,
    val unit: String,
    val maturity: String,
    /** Резерв по классу зрелости параметра, %. */
    val classReserve: Int,
    val origin: String,
)

/**
 * Свёртка (М6 и родня): сумма, резерв по классам и СИСТЕМНЫЙ резерв
 * ступени — печатаются оба, потому что это разные вещи.
 */
data class BudgetView(
    val kind: String,
    val gate: String,
    val lines: List<BudgetLine>,
    val sum: Double,
    val unit: String,
    val withClassReserve: Double,
    val systemMarginPercent: Int,
    val withSystemMargin: Double,
    val note: String,
    /**
     * Проверка рамок проекта: по одной строке на ограничение с числовой
     * границей, к которой относится эта величина.
     *
     * Свёртка не подгоняет сумму под рамку и не молчит о переборе —
     * она печатает «106 кг > Р2 (100 кг)» и оставляет решение человеку.
     */
    val frame: List<FrameCheck> = emptyList(),
)

/**
 * Сверка свёртки с числовой границей ограничения проекта.
 *
 * Сверяется сумма С СИСТЕМНЫМ РЕЗЕРВОМ ступени — решение владельца от
 * 07.09 (приёмка шипа B). Системный резерв и есть ожидаемый рост, и рамка
 * обязана выдерживать его СЕЙЧАС: иначе к PDR превышение станет фактом, а
 * до тех пор все отчёты показывали «в рамке». Резерв по ступени снижается
 * (MCR 20 → SRR 15 → SDR 10 → PDR 5 %), и превышение тает по мере
 * зрелости — это и есть контроль массы.
 *
 * Сверка по резервам классов печатается ТОЖЕ: по ней видно, упирается ли
 * изделие в рамку уже сегодня, без всяких запасов на будущее.
 */
data class FrameCheck(
    /** Код ограничения: Р2 — это имя рамки у людей. */
    val constraint: String,
    val statement: String,
    val op: String,
    val limit: Double,
    val unit: String,
    /** Сумма с резервами классов: столько весит изделие при нынешней зрелости. */
    val withClassReserve: Double,
    /** Держится ли рамка уже по резервам классов. */
    val withinByClass: Boolean,
    /** Сумма с системным резервом ступени — ЭТО число решает. */
    val actual: Double,
    val within: Boolean,
    /** Насколько превышена рамка; 0 — превышения нет. */
    val excess: Double,
    /** Ступень, чей системный резерв учтён. */
    val gate: String,
    val systemMarginPercent: Int,
    /** Обязательный вывод: «с системным 20 % — 122.8 кг, превышение 22.8 кг». */
    val words: String,
)

/** Метрика варианта построения: значение, порог и вердикт словами. */
data class MetricView(
    val key: String,
    val title: String,
    val value: Double,
    val unit: String,
    val threshold: Double?,
    /** Порог задаёт направление: «не больше» либо «не меньше». */
    val worseIf: String,
    val passed: Boolean,
    val group: String,
)

data class VariantView(
    val code: String,
    val name: String,
    val metrics: List<MetricView>,
    /** Отсеян порогом — с названием порога, а не «не подошёл». */
    val rejectedBy: String?,
    /** На фронте Парето: не хуже других ни по одной метрике. */
    val pareto: Boolean,
)

data class ComparisonView(
    val variants: List<VariantView>,
    val note: String,
)

interface Models {
    /** Взять набор моделей с полки: записи заводятся «не построена». */
    fun take(project: String, author: String): List<ModelView>

    fun list(project: String): List<ModelView>

    /** Прогон: снимок входов и выходы. Отсутствующий вход — отказ с адресом. */
    fun run(project: String, model: String, author: String, outputs: Map<String, String> = emptyMap()): RunView

    /**
     * Верификация модели — решение человека с основанием (условие A6: модель
     * «дала ответ и верифицирована»). Без прогона верифицировать нечего — отказ.
     */
    fun verify(project: String, model: String, status: Verification, author: String, note: String): ModelView

    /** Свёртка вида (mass · power · energy · link · dv · data · compute). */
    fun budget(project: String, kind: String, gate: String): BudgetView

    /** Разрывы к точке: модель не дала ответ либо вход не задан. */
    fun gaps(project: String, gate: String): List<String>
}

interface Variants {
    /** Сравнение вариантов: метрики, пороги, Парето. Итогового балла нет. */
    fun compare(project: String): ComparisonView
}

/** Узел графа влияния: что это и почему оно здесь. */
data class ImpactNode(
    val id: String,
    val code: String,
    val kind: String,
    val title: String,
    /** Расстояние от объекта правки: 0 — он сам. */
    val depth: Int,
)

data class ImpactEdge(val from: String, val to: String, val type: String, val why: String)

/**
 * «Что заденет правка» — до правки, а не после.
 *
 * Граф собирается по связям реестра и ссылкам документов: требование →
 * носитель → его стыки и параметры → модели, читающие эти параметры →
 * события верификации → документы, где это напечатано.
 */
data class ImpactView(
    val root: String,
    val nodes: List<ImpactNode>,
    val edges: List<ImpactEdge>,
    val summary: String,
)

interface Impact {
    fun of(project: String, code: String, depth: Int = 2): ImpactView
}
