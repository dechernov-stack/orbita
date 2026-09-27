// Окна сцен по умолчанию (карта фазы, 27.09): план фазы не утверждён — карта
// показывает окна, посчитанные из шаблона фазы и дат точек, штриховкой, и
// предлагает их утвердить. Считает сервер: у клиента своей арифметики дат нет.
//
// Правило. Сцена кончается к своей точке (`gate` шаблона) либо к самой ранней
// точке, чьи критерии её называют (`scene_done:К`); иначе — к последней точке
// фазы. Начинается с начала фазы и сдвигается связями шаблона: FS — не раньше
// конца предшественника (при общем сроке предшественник уступает последователю
// две недели), SS и INPUT — не раньше его начала, FF — кончается вместе с ним
// (но не позже своего срока). Связи бывают встречными (A5 ⇄ A4),
// поэтому проходов несколько. Экземпляры сцены наследуют её окно — так их
// читает и движок.
package orbita.api.internal

import orbita.process.api.PhaseView
import java.time.LocalDate

internal object PlanDefaults {

    data class Окно(val scene: String, val start: LocalDate, val end: LocalDate)

    /** Самое короткое окно, которое карта ещё покажет полосой, — неделя. */
    private const val НЕДЕЛЯ = 7L

    /** Окно, прижатое к сроку, начинается за две недели до него. */
    private const val ПРИЖАТО = 14L

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
        val начала = сцены.associate { it.key to начало }.toMutableMap()
        val концы = срок.toMutableMap()
        repeat(4) {
            сцены.forEach { с ->
                var н = начала.getValue(с.key)
                var к = концы.getValue(с.key)
                с.links.forEach { л ->
                    when (л.type) {
                        "FS" -> {
                            // Общий срок у предшественника и последователя: предшественник
                            // уступает конец, иначе последователю не остаётся окна (A10 → A11 к KDP-B).
                            концы[л.on]?.let { предКонец ->
                                if (!предКонец.isBefore(к)) {
                                    концы[л.on] = maxOf(начала.getValue(л.on).plusDays(НЕДЕЛЯ), к.minusDays(ПРИЖАТО))
                                }
                            }
                            (концы[л.on] ?: даты[л.on])?.let { if (it > н) н = it }
                        }
                        "SS", "INPUT" -> (начала[л.on] ?: даты[л.on])?.let { if (it > н) н = it }
                        "FF" -> концы[л.on]?.let { к = minOf(срок.getValue(с.key), maxOf(к, it)) }
                    }
                }
                if (!н.isBefore(к)) н = maxOf(начало, к.minusDays(ПРИЖАТО))
                if (!н.isBefore(к)) к = н.plusDays(НЕДЕЛЯ)
                начала[с.key] = н
                концы[с.key] = к
            }
        }
        return сцены.map { Окно(it.key, начала.getValue(it.key), концы.getValue(it.key)) }
    }
}
