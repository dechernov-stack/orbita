// М2а — бюджет абонентской радиолинии. Настоящая физика, не заглушка.
//
// Считается по уравнению Фрииса: принятая мощность = ЭИИМ − потери в
// свободном пространстве − прочие потери + усиление приёмной антенны.
// Отношение Eb/N0 — из принятой мощности, шумовой температуры и скорости.
// Формулы опубликованные (Friis 1946; ITU-R P.525 для FSPL), поэтому
// здесь ссылка, а не «наша модель распространения».
//
// Правило проекта: физику не изобретаем. Всё, что сложнее бюджета линии
// (дожди, многолучёвость, помехи), сюда не тащим — это уже не Pre-A.
package orbita.models.internal

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

internal object LinkBudget {

    /** Постоянная Больцмана, дБВт/(К·Гц). */
    private const val БОЛЬЦМАН_ДБ = -228.6

    /** Скорость света, м/с. */
    private const val C = 299_792_458.0

    data class Вход(
        /** Мощность передатчика, Вт. */
        val txPowerW: Double,
        /** Усиление передающей антенны, дБи. */
        val txGainDbi: Double,
        /** Усиление приёмной антенны, дБи. */
        val rxGainDbi: Double,
        /** Частота, Гц. */
        val frequencyHz: Double,
        /** Наклонная дальность, м. */
        val rangeM: Double,
        /** Шумовая температура системы, К. */
        val noiseTempK: Double,
        /** Скорость передачи, бит/с. */
        val bitrateBps: Double,
        /** Прочие потери (фидер, поляризация, атмосфера), дБ. */
        val otherLossesDb: Double = 2.0,
        /** Требуемое Eb/N0 для выбранной модуляции и кодирования, дБ. */
        val requiredEbN0Db: Double = 6.0,
    )

    data class Ответ(
        val eirpDbw: Double,
        val fsplDb: Double,
        val receivedDbw: Double,
        val ebN0Db: Double,
        val marginDb: Double,
        /** Запас положителен — линия замыкается. */
        val closes: Boolean,
        val words: String,
    )

    /** Потери в свободном пространстве, дБ (ITU-R P.525). */
    fun fspl(rangeM: Double, frequencyHz: Double): Double =
        20 * log10(4 * PI * rangeM * frequencyHz / C)

    /**
     * Наклонная дальность до КА на круговой орбите при заданном угле места
     * (плоская тригонометрия сферической геометрии; R⊕ = 6371 км).
     */
    fun slantRangeM(altitudeM: Double, elevationDeg: Double): Double {
        val re = 6_371_000.0
        val e = Math.toRadians(elevationDeg)
        val sinE = kotlin.math.sin(e)
        return sqrt((re * sinE).pow(2) + 2 * re * altitudeM + altitudeM * altitudeM) - re * sinE
    }

    fun рассчитать(вход: Вход): Ответ {
        val eirp = 10 * log10(вход.txPowerW) + вход.txGainDbi
        val потери = fspl(вход.rangeM, вход.frequencyHz)
        val принято = eirp - потери - вход.otherLossesDb + вход.rxGainDbi
        // Eb/N0 = Pпр − k − 10lg(T) − 10lg(R)
        val ebN0 = принято - БОЛЬЦМАН_ДБ - 10 * log10(вход.noiseTempK) - 10 * log10(вход.bitrateBps)
        val запас = ebN0 - вход.requiredEbN0Db
        return Ответ(
            eirpDbw = округлить(eirp),
            fsplDb = округлить(потери),
            receivedDbw = округлить(принято),
            ebN0Db = округлить(ebN0),
            marginDb = округлить(запас),
            closes = запас >= 0,
            words = if (запас >= 0) {
                "линия замыкается с запасом ${округлить(запас)} дБ " +
                    "(Eb/N0 ${округлить(ebN0)} дБ против требуемых ${вход.requiredEbN0Db})"
            } else {
                "линия НЕ замыкается: не хватает ${округлить(-запас)} дБ — " +
                    "поднимать мощность, усиление антенны или снижать скорость"
            },
        )
    }

    private fun округлить(x: Double) = Math.round(x * 100.0) / 100.0
}
