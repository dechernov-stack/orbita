// Округление величин ДЛЯ ПОКАЗА (STEP-7-9 §9.2, замечание сквозного прохода).
//
// Экран, показывающий «60.42621244822911 Вт·ч» и «0.333333333…», нечитаем:
// в таблице такие числа обрезаются многоточием, и сравнить их глазом нельзя.
// Округление выполняется здесь, в слое представления, а не в клиенте: там оно
// стало бы второй реализацией правила, и одна и та же величина показалась бы
// по-разному на экране и в отчёте.
//
// Округляется ЗНАЧАЩИМИ цифрами, а не знаками после запятой: величины экранов
// расходятся на порядки — 0.0029 Вт·ч энергии маяка и 120000 сообщений в сутки
// на одной странице. Фиксированные три знака превратили бы первую в ноль.
//
// В модели значения не меняются: округляется только то, что уходит на экран.
package orbita.out

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong

/** Округление до [digits] значащих цифр. Ноль, NaN и бесконечность — как есть. */
fun sig(x: Double, digits: Int = 4): Double {
    if (x == 0.0 || !x.isFinite()) return x
    val exponent = floor(log10(abs(x))).toInt()
    val scale = 10.0.pow(digits - 1 - exponent)
    // при очень больших числах масштаб вырождается — возвращаем исходное
    if (!scale.isFinite() || scale == 0.0) return x
    val scaled = x * scale
    if (abs(scaled) > Long.MAX_VALUE.toDouble()) return x
    return scaled.roundToLong() / scale
}

/** То же для необязательной величины: отсутствие значения остаётся отсутствием. */
fun sig(x: Double?, digits: Int = 4): Double? = x?.let { sig(it, digits) }

/** Округление значений карты по ключам (спрос по классам и т. п.). */
fun <K> sig(values: Map<K, Double>, digits: Int = 4): Map<K, Double> =
    values.mapValues { (_, v) -> sig(v, digits) }
