// Подключение Orekit (TZ-BAL-001, ADR-010): пропагация и точечные события
// видимости — только Orekit; собственные интеграторы не создаются.
//
// Воспроизводимость данных (ловушка 6): внешние хосты IERS/Orekit-data из среды
// сборки недоступны, поэтому UTC−TAI задан ПРОГРАММНОЙ таблицей секунд
// координации (IERS Bulletin C; последняя поправка 2017-01-01, TAI−UTC = 37 с) —
// версия зафиксирована кодом, сеть при прогонах не используется. Земная СК —
// GTOD без EOP-поправок (полярное движение и UT1−UTC пренебрежимы для геометрии
// покрытия; допуски сходимости это учитывают). Версии Orekit и набора данных
// входят в input_versions результатов (TZ-COM-006).
package orbita.bal

import org.orekit.propagation.Propagator
import org.orekit.time.DateComponents
import org.orekit.time.OffsetModel
import org.orekit.time.TimeScalesFactory

object OrekitSetup {

    const val DATA_VERSION =
        "utc-tai=bulletin-c-2017-01-01(37s,programmatic); eop=zero(GTOD-no-EOP)"

    /** Модель гравитационного поля фиксируется наравне с версией Orekit (TZ-BAL-001). */
    const val GRAVITY_MODEL = "EIGEN5C zonal J2-J6 (Orekit Constants)"

    val orekitVersion: String by lazy {
        Propagator::class.java.`package`?.implementationVersion ?: "12.2.1"
    }

    /** Версии расчётного ядра для input_versions (TZ-BAL-001). */
    fun inputVersions(): Map<String, String> = mapOf(
        "orekit" to orekitVersion,
        "orekit-data" to DATA_VERSION,
        "gravity-model" to GRAVITY_MODEL,
        "ballistics" to BAL_MODULE_VERSION,
    )

    // Секунды координации TAI−UTC с 1972 года (IERS Bulletin C, общедоступная таблица)
    private val LEAP_SECONDS: List<Pair<Triple<Int, Int, Int>, Int>> = listOf(
        Triple(1972, 1, 1) to 10, Triple(1972, 7, 1) to 11, Triple(1973, 1, 1) to 12,
        Triple(1974, 1, 1) to 13, Triple(1975, 1, 1) to 14, Triple(1976, 1, 1) to 15,
        Triple(1977, 1, 1) to 16, Triple(1978, 1, 1) to 17, Triple(1979, 1, 1) to 18,
        Triple(1980, 1, 1) to 19, Triple(1981, 7, 1) to 20, Triple(1982, 7, 1) to 21,
        Triple(1983, 7, 1) to 22, Triple(1985, 7, 1) to 23, Triple(1988, 1, 1) to 24,
        Triple(1990, 1, 1) to 25, Triple(1991, 1, 1) to 26, Triple(1992, 7, 1) to 27,
        Triple(1993, 7, 1) to 28, Triple(1994, 7, 1) to 29, Triple(1996, 1, 1) to 30,
        Triple(1997, 7, 1) to 31, Triple(1999, 1, 1) to 32, Triple(2006, 1, 1) to 33,
        Triple(2009, 1, 1) to 34, Triple(2012, 7, 1) to 35, Triple(2015, 7, 1) to 36,
        Triple(2017, 1, 1) to 37,
    )

    @Volatile
    private var initialized = false

    /** Идемпотентная инициализация; вызывается каждой точкой входа в Orekit. */
    @Synchronized
    fun ensureInitialized() {
        if (initialized) return
        TimeScalesFactory.addUTCTAIOffsetsLoader {
            LEAP_SECONDS.map { (d, offset) ->
                OffsetModel(DateComponents(d.first, d.second, d.third), offset)
            }
        }
        initialized = true
    }
}
