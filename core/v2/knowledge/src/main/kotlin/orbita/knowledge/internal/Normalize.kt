// Нормализация величин и горизонтов: приведение к канону перед сравнением.
//
// Ступень 1 сверки обязана быть бесплатной: расхождение по числам находится
// здесь, без единого вызова модели. Но сравнивать можно только приведённое к
// одному канону — «180 мин» и «3 часа» об одном и том же лишь после
// приведения по справочнику единиц.
//
// Канон живёт в ДАННЫХ: справочник единиц (вид `unit` с `conversion_type`) и
// план дат (`plan.gate_dates`). Второй таблицы коэффициентов и дат внутри
// кода нет намеренно — она разошлась бы со справочником на первой же
// поставке, и расхождение всплыло бы вердиктом «противоречие» на пустом
// месте.
//
// Чего здесь нет: разбора чисел из текста. Пороговое число живёт ПОЛЕМ —
// measure · bound · year (thresholds_rule); строка «не менее 180 минут»
// остаётся строкой, и сравнение говорит об этом словами, а не угадывает 180.
package orbita.knowledge.internal

import com.fasterxml.jackson.databind.JsonNode
import orbita.kernel.api.Area
import orbita.kernel.api.EntityStore
import kotlin.math.abs
import kotlin.math.max

/** Единица справочника — ровно то, чем величина приводится к канону. */
internal data class ЕдиницаКанона(
    val symbol: String,
    val dimension: String,
    val canonical: Boolean,
    val factor: Double,
    /** linear · offset · log · rate — правило приведения, а не украшение карточки. */
    val conversionType: String,
    val offset: Double = 0.0,
    /** Дата курса: валюты сравнимы только в пределах одной даты. */
    val rateDate: String? = null,
)

/** Величина, приведённая к канону своей размерности. */
internal data class Канон(val value: Double, val unit: String, val dimension: String)

/**
 * Итог приведения: канон либо ПРИЧИНА, по которой его нет.
 *
 * Причина обязательна: «не сравнилось» без «почему» человек читать не может,
 * а сверка обязана называть отличие словами.
 */
internal data class Приведение(val канон: Канон?, val reason: String = "")

/**
 * Отличие двух значений словами: что сравнивали, что с чем и почему
 * несравнимо. «Похоже» без «чем именно» — брак (СВЕРКА-РУЧНОГО-ВВОДА).
 *
 * @property kind equal — совпало · differs — расходится · incomparable — сравнить нельзя
 * @property field поле, по которому шло сравнение
 */
internal data class Difference(
    val kind: String,
    val field: String,
    val mine: String,
    val theirs: String,
    val reason: String = "",
) {
    companion object {
        const val EQUAL = "equal"
        const val DIFFERS = "differs"
        const val INCOMPARABLE = "incomparable"
    }
}

/**
 * Приведение к канону: единицы по справочнику, горизонты по плану.
 *
 * @param единицы написание единицы → её место в справочнике
 * @param горизонты ворота и фазы плана → год; горизонт «к MCR» без плана —
 *   не год, а слово, и таким он и остаётся
 */
internal class Normalize(
    private val единицы: Map<String, ЕдиницаКанона> = emptyMap(),
    private val горизонты: Map<String, Int> = emptyMap(),
) {

    /** Канон размерности: по нему величина получает имя единицы после приведения. */
    private val каноныРазмерностей: Map<String, String> =
        единицы.values.filter { it.canonical }.associate { it.dimension to it.symbol }

    /** Размерность написания: `null` — единицы в справочнике нет. */
    fun размерность(единица: String): String? = единицы[ключЕдиницы(единица)]?.dimension

    /**
     * Величина поля (measure · bound) в канон размерности.
     *
     * Диапазон (min/max) ступень 1 не сводит: свести диапазон к одному числу
     * значит выбрать за человека, какой его край считать значением.
     */
    fun величина(узел: JsonNode): Приведение {
        if (!узел.isObject) {
            return Приведение(null, ПОЛЯМИ)
        }
        val написание = узел.path("unit").asText("").trim()
        if (написание.isBlank()) {
            return Приведение(null, "величина без единицы — не величина (правило проекта)")
        }
        val единица = единицы[ключЕдиницы(написание)]
            ?: return Приведение(
                null,
                "единица «$написание» вне справочника единиц — запись добавляется в справочник (вид unit), а не в код",
            )
        if (!узел.path("value").isNumber) {
            val диапазон = узел.path("min").isNumber || узел.path("max").isNumber
            return Приведение(
                null,
                if (диапазон) "диапазон min/max к одному числу не сводится — какой край считать значением, решает человек"
                else "значение величины не число — сравнивать нечего",
            )
        }
        val значение = узел.path("value").asDouble()
        val приведённое = when (единица.conversionType) {
            "linear" -> значение * единица.factor
            "offset" -> значение * единица.factor + единица.offset
            "log" -> return Приведение(
                null,
                "логарифмическая шкала («$написание») — сравнение только в той же единице",
            )
            "rate" -> return Приведение(
                null,
                "курсовая единица («$написание», курс на ${единица.rateDate ?: "неназванную дату"}) — " +
                    "сравнение только по одной дате курса",
            )
            else -> return Приведение(
                null,
                "у единицы «$написание» нет правила приведения (conversion_type)",
            )
        }
        val имяКанона = каноныРазмерностей[единица.dimension] ?: единица.symbol
        return Приведение(Канон(приведённое, имяКанона, единица.dimension))
    }

    /**
     * Сравнение двух величин после приведения.
     *
     * Порядок отказов важен: разные ПОКАЗАТЕЛИ (P95 против среднего) — это
     * «не сравнимо», а не «противоречие»: два разных показателя не спорят
     * друг с другом, и объявить их конфликтом значит завести спор на пустом
     * месте.
     */
    fun сравнить(field: String, моё: JsonNode, чужое: JsonNode): Difference {
        val мТекст = текст(моё)
        val чТекст = текст(чужое)
        if (!моё.isObject || !чужое.isObject) {
            return Difference(Difference.INCOMPARABLE, field, мТекст, чТекст, ПОЛЯМИ)
        }
        val мПоказатель = показатель(моё)
        val чПоказатель = показатель(чужое)
        if (мПоказатель.isNotBlank() && чПоказатель.isNotBlank() && мПоказатель != чПоказатель) {
            return Difference(
                Difference.INCOMPARABLE, field, мТекст, чТекст,
                "разные показатели: «$мПоказатель» и «$чПоказатель» — одно к другому нормализацией не сводится",
            )
        }
        val мПриведение = величина(моё)
        val чПриведение = величина(чужое)
        val мК = мПриведение.канон
        val чК = чПриведение.канон
        // Та же единица — приводить нечего: так сравнимы и логарифмические
        // шкалы, и валюты, которые к канону не приводятся вовсе.
        val таЖеЕдиница = ключЕдиницы(моё.path("unit").asText("")) == ключЕдиницы(чужое.path("unit").asText("")) &&
            моё.path("value").isNumber && чужое.path("value").isNumber
        val пара: Pair<Double, Double>? = when {
            мК != null && чК != null && мК.dimension == чК.dimension -> мК.value to чК.value
            таЖеЕдиница -> моё.path("value").asDouble() to чужое.path("value").asDouble()
            else -> null
        }
        if (пара == null) {
            val причина = when {
                мК == null -> мПриведение.reason
                чК == null -> чПриведение.reason
                else -> "разные размерности: «${мК.dimension}» и «${чК.dimension}» — сравнивать нечего"
            }
            return Difference(Difference.INCOMPARABLE, field, мТекст, чТекст, причина)
        }
        val мOp = моё.path("op").asText("").trim()
        val чOp = чужое.path("op").asText("").trim()
        if (!равны(пара.first, пара.second)) {
            return Difference(
                Difference.DIFFERS, field, мТекст, чТекст,
                "после приведения к канону значения расходятся: ${число(пара.first)} против ${число(пара.second)}" +
                    (мК?.let { " (${it.unit})" } ?: ""),
            )
        }
        if (мOp != чOp) {
            return Difference(
                Difference.DIFFERS, "$field.op", мТекст, чТекст,
                "значение одно, а оператор разный: «$мOp» против «$чOp»",
            )
        }
        return Difference(Difference.EQUAL, field, мТекст, чТекст)
    }

    /**
     * Горизонт к году: год числом, год строкой из четырёх цифр либо ворота
     * или фаза плана. Всё остальное годом не становится — «этап 1, 1,5–3
     * года от старта» разбирать числами запрещено (thresholds_rule).
     */
    fun горизонт(узел: JsonNode): Int? {
        if (узел.isNumber) return узел.asInt().takeIf { it in 1900..2200 }
        val строка = узел.asText("").trim()
        if (строка.isBlank()) return null
        if (Regex("^\\d{4}$").matches(строка)) return строка.toInt().takeIf { it in 1900..2200 }
        return горизонты[ключГоризонта(строка)]
    }

    /** Сравнение горизонтов: оба значения остаются видны, победитель не выбирается. */
    fun сравнитьГоризонт(field: String, моё: JsonNode, чужое: JsonNode): Difference {
        val мТекст = текст(моё)
        val чТекст = текст(чужое)
        val м = горизонт(моё)
        val ч = горизонт(чужое)
        if (м == null || ч == null) {
            val чей = if (м == null) мТекст else чТекст
            return Difference(
                Difference.INCOMPARABLE, field, мТекст, чТекст,
                "горизонт «$чей» к году не сводится — назовите год или ворота плана (plan.gate_dates); " +
                    "числа из текста не разбираются",
            )
        }
        // Оба значения остаются словами кандидата («к MCR», «2032»), а
        // разобранные годы — в причине: победителя выбирает человек.
        return if (м == ч) {
            Difference(Difference.EQUAL, field, мТекст, чТекст)
        } else {
            Difference(Difference.DIFFERS, field, мТекст, чТекст, "годы расходятся: $м против $ч")
        }
    }

    private fun показатель(узел: JsonNode): String = узел.path("key").asText("").trim().lowercase()

    /** Значение словами — то, что человек увидит в находке. */
    private fun текст(узел: JsonNode): String = when {
        узел.isObject -> listOf(
            узел.path("key").asText("").trim(),
            узел.path("op").asText("").trim(),
            if (узел.path("value").isNumber) число(узел.path("value").asDouble()) else узел.path("value").asText("").trim(),
            узел.path("unit").asText("").trim(),
        ).filter { it.isNotBlank() }.joinToString(" ")
        else -> узел.asText("").trim()
    }

    private fun число(значение: Double): String =
        if (значение == значение.toLong().toDouble()) значение.toLong().toString() else значение.toString()

    /** Равенство величин с допуском: иначе двойная точность объявит спор на ровном месте. */
    private fun равны(а: Double, б: Double): Boolean =
        abs(а - б) <= 1e-9 * max(1.0, max(abs(а), abs(б)))

    companion object {
        /** Почему сравнение не состоялось: пороги — полями, а не строками. */
        private const val ПОЛЯМИ =
            "числовые пороги сравниваются только полями (measure · bound · year) — " +
                "число из свободного текста не разбирается (thresholds_rule)"

        /**
         * Справочник и план — из данных: единицы всех областей (справочник
         * общий), горизонты — из плана ЭТОГО проекта.
         */
        fun of(store: EntityStore, area: Area): Normalize {
            val единицы = mutableMapOf<String, ЕдиницаКанона>()
            store.ofKind("unit").forEach { запись ->
                val д = запись.doc
                val единица = ЕдиницаКанона(
                    symbol = д.path("symbol").asText(""),
                    dimension = д.path("dimension").asText(""),
                    canonical = д.path("canonical").asBoolean(false),
                    factor = if (д.path("factor").isNumber) д.path("factor").asDouble() else 1.0,
                    conversionType = д.path("conversion_type").asText("linear"),
                    offset = д.path("offset").asDouble(0.0),
                    rateDate = д.path("rate_date").asText("").ifBlank { null },
                )
                // Единица узнаётся любым своим написанием: символом, именем и
                // кодом карточки — ввод приходит и «мин», и «минута».
                listOf(единица.symbol, д.path("name").asText(""), запись.code).forEach { написание ->
                    val ключ = ключЕдиницы(написание)
                    if (ключ.isNotBlank() && !единицы.containsKey(ключ)) единицы[ключ] = единица
                }
            }
            val горизонты = mutableMapOf<String, Int>()
            store.list(area, "plan").forEach { план ->
                val годаПлана = mutableListOf<Int>()
                план.doc.path("gate_dates").forEach { пара ->
                    val ворота = пара.path("gate").asText("").trim()
                    val год = годДаты(пара.path("date").asText(""))
                    if (ворота.isNotBlank() && год != null) {
                        горизонты[ключГоризонта(ворота)] = год
                        годаПлана += год
                    }
                }
                // Фаза кончается последними своими воротами: «к концу Phase A»
                // — это год последней точки плана, а не отдельная дата.
                val фаза = план.doc.path("phase").asText("").trim()
                if (фаза.isNotBlank() && годаПлана.isNotEmpty()) {
                    горизонты[ключГоризонта(фаза)] = годаПлана.max()
                }
            }
            return Normalize(единицы, горизонты)
        }

        /** Написание единицы к сравнимому виду: регистр и «ё» ключа не меняют. */
        internal fun ключЕдиницы(написание: String): String =
            написание.trim().lowercase().replace('ё', 'е')

        /** Горизонт к ключу словаря: «к MCR» и «MCR» — одни ворота. */
        internal fun ключГоризонта(текст: String): String {
            val очищенный = текст.trim().lowercase().replace('ё', 'е')
                .trim('«', '»', '"', '\'', '.', ',', ' ')
            return listOf("к концу ", "к ", "до ", "на ").fold(очищенный) { строка, предлог ->
                строка.removePrefix(предлог)
            }.trim()
        }

        /** Год из даты плана: дата — поле ISO, а не текст, разбирать её можно. */
        private fun годДаты(дата: String): Int? =
            дата.trim().take(4).takeIf { it.length == 4 && it.all { символ -> символ.isDigit() } }?.toInt()
    }
}
