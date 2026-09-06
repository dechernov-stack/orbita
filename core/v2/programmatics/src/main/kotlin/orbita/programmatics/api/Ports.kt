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
    /** Чего не хватает пакету, чтобы считаться готовым к KDP. */
    val gaps: List<String>,
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
    fun takeWbs(project: String, author: String): List<PackageView>

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

    fun risks(project: String): List<RiskView>

    /** Разрывы к точке: оценка без пакетов созревания при открытых TRL и т.п. */
    fun gaps(project: String, gate: String): List<String>
}
