// Программатика: WBS с парами к узлам, оценка диапазоном, созревание
// технологий и риски со сроком-точкой.
//
// Три правила, из которых всё следует:
//   · пакет работ ПАРНЫЙ узлу состава: работа без предмета — строка сметы,
//     а не работа, и на обзоре её нечем защитить;
//   · оценка — ДИАПАЗОН с допущениями и методом; одно число на Pre-A врёт;
//   · технология с разрывом TRL РОЖДАЕТ пакет созревания и веху: план
//     созревания не живёт в голове ведущего.
package orbita.programmatics.api

/** Метод оценки: чем считали — тем и отвечаем. */
enum class EstimateMethod { ROM, PARAMETRIC, BOTTOM_UP }

data class PackageView(
    val code: String,
    val name: String,
    val parent: String?,
    val crossCutting: Boolean,
    /** Узлы состава, которым пакет парен. */
    val pbsRefs: List<String>,
    val estimate: EstimateView?,
    /** Плановые начало и конец работ; пусто — план не задан. */
    val planStart: String? = null,
    val planEnd: String? = null,
    /** Чего не хватает пакету, чтобы считаться готовым к KDP. */
    val gaps: List<String>,
)

/** Строка окна взятия WBS: пакет полки и что с ним. */
data class WbsOffer(
    val code: String,
    val name: String,
    val crossCutting: Boolean,
    /** Узлы состава, которые нашлись в проекте по ссылкам полки. */
    val nodes: List<String>,
    /** Узлы, названные полкой, но отсутствующие в проекте. */
    val missingNodes: List<String>,
    /** Входит ли в рекомендованный набор. */
    val recommended: Boolean,
    /** Уже взят в проект. */
    val taken: Boolean,
    /** Почему рекомендован или нет — словами. */
    val why: String,
)

data class EstimateView(
    val min: Double,
    val max: Double,
    val unit: String,
    val method: EstimateMethod,
    val assumptions: String,
    val date: String,
)

/** Пакет созревания технологии: разрыв TRL, веха и резервное решение. */
data class MaturationView(
    val technology: String,
    val component: String?,
    val trlCurrent: Int,
    val trlRequired: Int,
    val requiredBy: String,
    val packageCode: String?,
    val milestoneGate: String?,
    val fallback: String?,
    val words: String,
)

data class RiskView(
    val code: String,
    val statement: String,
    val category: String,
    val probability: Int,
    val impact: Int,
    val strategy: String,
    val owner: String,
    /** Срок — ТОЧКА, а не дата: даты плывут, точки нет. */
    val duePoint: String,
    val level: Int,
)

interface Programmatics {
    /** Взять типовой WBS с полки и сопоставить пакеты узлам состава. */
    /**
     * Окно взятия WBS: что полка предлагает и что взято.
     *
     * Полка знает 54 пакета, а состав проекта — шесть узлов: брать всё
     * значило заводить 41 пакет, которому не с чем быть парным, и держать
     * ими выход сцены 12. Тем же механизмом, что полки Arcadia (ADR-054):
     * рекомендованное — пакеты, чьи узлы ЕСТЬ в составе, плюс сквозные;
     * остальное видно в окне и берётся руками.
     */
    fun wbsOffer(project: String): List<WbsOffer>

    /**
     * Взять пакеты работ с полки.
     *
     * @param codes что брать; пусто — рекомендованный набор
     */
    fun takeWbs(project: String, author: String, codes: List<String> = emptyList()): List<PackageView>

    fun packages(project: String): List<PackageView>

    /** Оценка пакета диапазоном: одно число на Pre-A — неправда. */
    fun estimate(
        project: String,
        packageCode: String,
        min: Double,
        max: Double,
        unit: String,
        method: EstimateMethod,
        assumptions: String,
        author: String,
    ): EstimateView

    /**
     * Созревание технологий: по каждому разрыву TRL заводится пакет работ
     * и веха «TRL n достигнут» (`gate.kind = technology`).
     */
    fun maturation(project: String, author: String): List<MaturationView>

    /**
     * То же СОСТОЯНИЕ, но без заведения чего бы то ни было.
     *
     * Нужно воротам: критерий обязан НАБЛЮДАТЬ, а не действовать. Пока
     * условие `maturation_planned` звало генератор, оно само закрывало
     * разрыв, который проверяло, и не могло отказать никогда — ровно тот
     * класс «проверки, которая молча проходит», ради которого заведено
     * правило отрицательного теста (ответ владельца 08.09).
     */
    fun maturationState(project: String): List<MaturationView>

    /**
     * План пакета работ. Для пакета созревания это ИСТОЧНИК ДАТЫ ВЕХИ:
     * веха «TRL n достигнут» рождается вместе с пакетом и дату получает от
     * его плана, а не от чьей-то руки (решение владельца 08.09).
     *
     * @return код вехи, чья дата поехала следом; null — веха не тронута
     */
    fun planPackage(project: String, packageCode: String, start: String, end: String, author: String): String?

    fun risks(project: String): List<RiskView>

    /** Разрывы к точке: оценка без пакетов созревания при открытых TRL и т.п. */
    fun gaps(project: String, gate: String): List<String>
}
