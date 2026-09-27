// Окна сцен по умолчанию (карта фазы, 27.09): план фазы не утверждён — карта
// показывает окна, посчитанные из шаблона фазы и дат точек, штриховкой, и
// предлагает их утвердить. Считает сервер: у клиента своей арифметики дат нет.
//
// Правило — лесенка по связям шаблона (владелец 27.09: «а не все от сегодня
// до своей точки»). Связи — `depends` сцены и её условия входа: «сцена K
// прожита» — FS, «сцена K начата» — SS, вход, который у другой сцены выход
// («замысел принят» у сцены 3 Pre-A), — FS на неё. У Pre-A связей `depends`
// нет: её цепочка задана входами. Экземпляры сцены наследуют её окно — так их
// читает и движок.
//
// Срок сцены — её точка (`gate` шаблона) либо самая ранняя точка, чьи критерии
// её называют (`scene_done:К`); иначе — последняя точка фазы. Начало — с
// начала фазы, связи двигают его: FS — с конца предшественника, SS — через
// неделю после его начала, INPUT — через две (вход должен появиться).
// Цепочка FS делит время до сроков своих звеньев поровну, звено тянется до
// начала следующего — ступени без зазоров; сцена без последователя по FS
// кончается к своему сроку, FF — не раньше конца пары.
package orbita.api.internal

import orbita.process.api.PhaseView
import orbita.process.api.SceneView
import java.time.LocalDate
import java.time.temporal.ChronoUnit

internal object PlanDefaults {

    data class Окно(val scene: String, val start: LocalDate, val end: LocalDate)

    /** Самое короткое окно, которое карта ещё покажет полосой, — неделя. */
    private const val НЕДЕЛЯ = 7L

    /** Окно, прижатое к сроку, начинается за две недели до него. */
    private const val ПРИЖАТО = 14L

    /** Сдвиг начала после начала предшественника: SS — неделя, INPUT — две (вход должен появиться). */
    private val СДВИГ = mapOf("SS" to НЕДЕЛЯ, "INPUT" to 2 * НЕДЕЛЯ)

    private data class Связь(val on: String, val type: String)

    fun окна(фаза: PhaseView, начало: LocalDate): List<Окно> {
        val даты = фаза.gates.mapNotNull { т ->
            т.plannedDate?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { т.key to it }
        }.toMap()
        if (даты.isEmpty()) return emptyList()
        val последняя = даты.values.max()
        // Экземпляры считаются сценой шаблона: у проекта бывают только «A4:EL-…»
        // без самой «A4», а окно у них одно — окно сцены шаблона.
        val сцены = фаза.scenes.distinctBy { it.instanceOf ?: it.key }
            .map { с -> с.instanceOf?.let { шаблон -> с.copy(key = шаблон, instanceOf = null) } ?: с }
        val срок = сцены.associate { с ->
            val кТочке = с.gate?.let { даты[it] }
            val поКритериям = фаза.gates
                .filter { т -> т.criteria.any { у -> у.check == "scene_done:${с.key}" } }
                .mapNotNull { даты[it.key] }.minOrNull()
            с.key to (listOfNotNull(кТочке, поКритериям).minOrNull() ?: последняя)
        }
        val связи = связи(сцены)
        // Вход «точка пройдена» внутри фазы — не раньше даты точки.
        val порог = сцены.associate { с ->
            с.key to (с.entry.mapNotNull { у -> у.check.takeIf { it.startsWith("gate_passed:") }?.let { даты[it.substringAfter(':')] } }
                .maxOrNull()?.let { maxOf(it, начало) } ?: начало)
        }
        val послеFS = связи.flatMap { (к, сс) -> сс.filter { it.type == "FS" }.map { it.on to к } }
            .groupBy({ it.first }, { it.second })

        // Звенья цепочки FS впереди сцены: до каждого — длиннейший путь (цикл
        // FS — ошибка шаблона, расчёт на нём не виснет).
        fun впереди(к: String, путь: Set<String>): Map<String, Int> {
            val итог = mutableMapOf(к to 0)
            послеFS[к].orEmpty().filter { it !in путь }.forEach { следующая ->
                впереди(следующая, путь + к).forEach { (д, n) -> if ((итог[д] ?: -1) < n + 1) итог[д] = n + 1 }
            }
            return итог
        }
        val цепь = сцены.associate { it.key to впереди(it.key, setOf(it.key)) }

        // Конец звена FS: время до срока каждого звена впереди делится поровну.
        fun конец(к: String, н: LocalDate): LocalDate {
            val впередиЗвенья = цепь.getValue(к)
            if (впередиЗвенья.size == 1) return срок.getValue(к)
            val шаг = впередиЗвенья.minOf { (д, n) -> ChronoUnit.DAYS.between(н, срок.getValue(д)) / (n + 1) }
            return н.plusDays(maxOf(НЕДЕЛЯ, шаг))
        }

        // Начала только растут от начала фазы, пока связи их двигают; на
        // встречных связях проходов не больше числа сцен.
        val начала = сцены.associate { it.key to начало }.toMutableMap()
        val концы = сцены.associate { it.key to конец(it.key, начало) }.toMutableMap()
        run {
            repeat(сцены.size + 1) {
                var сдвинуто = false
                сцены.forEach { с ->
                    val н = связи.getValue(с.key).fold(порог.getValue(с.key)) { н, л ->
                        val от = when (л.type) {
                            "FS" -> концы.getValue(л.on)
                            "SS", "INPUT" -> начала.getValue(л.on).plusDays(СДВИГ.getValue(л.type))
                            else -> н
                        }
                        maxOf(н, от)
                    }
                    if (н != начала[с.key]) сдвинуто = true
                    начала[с.key] = н
                    концы[с.key] = конец(с.key, н)
                }
                if (!сдвинуто) return@run
            }
        }

        // Звено тянется до начала последователя (ступени без зазоров), но не за свой срок.
        val итог = сцены.associate { с ->
            с.key to (послеFS[с.key]?.minOf { начала.getValue(it) }?.let { minOf(срок.getValue(с.key), it) } ?: срок.getValue(с.key))
        }
        return сцены.map { с ->
            var н = начала.getValue(с.key)
            var к = связи.getValue(с.key).filter { it.type == "FF" }
                .fold(итог.getValue(с.key)) { к, л -> minOf(срок.getValue(с.key), maxOf(к, итог.getValue(л.on))) }
            if (к.isBefore(н.plusDays(НЕДЕЛЯ))) н = maxOf(начало, к.minusDays(ПРИЖАТО))
            if (!н.isBefore(к)) к = н.plusDays(НЕДЕЛЯ)
            Окно(с.key, н, к)
        }
    }

    /** Связи сцены: `depends` шаблона и условия входа, только между сценами этой фазы. */
    private fun связи(сцены: List<SceneView>): Map<String, List<Связь>> {
        val ключи = сцены.map { it.key }.toSet()
        val чейВыход = сцены.flatMap { с -> с.exit.map { it.check to с.key } }
            .groupBy({ it.first }, { it.second }).mapValues { (_, чьи) -> чьи.distinct() }
        return сцены.associate { с ->
            val изВходов = с.entry.mapNotNull { у ->
                when {
                    у.check.startsWith("scene_done:") -> Связь(у.check.substringAfter(':'), "FS")
                    у.check.startsWith("scene_started:") -> Связь(у.check.substringAfter(':'), "SS")
                    else -> чейВыход[у.check]?.singleOrNull()?.let { Связь(it, "FS") }
                }
            }
            с.key to (с.links.map { Связь(it.on, it.type) } + изВходов)
                .filter { it.on in ключи && it.on != с.key }.distinct()
        }
    }
}
