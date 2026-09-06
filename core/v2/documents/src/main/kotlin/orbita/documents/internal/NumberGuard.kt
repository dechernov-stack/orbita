// Сторож чисел и квалификаторов (перенос из DocPilot, ОБЗОР-DOCPILOT §1.2).
//
// Тезис — единственный свободный текст документа, и в нём легко появиться
// числу, которого нет ни в одной опоре. Сторож сравнивает числа текста с
// числами опор по НОРМАЛИЗОВАННОМУ виду с единицей: «120 м³/ч» и «120 м3/ч»
// — одно число, «120 м³/ч» и «120 с» — разные.
//
// Исключения не в коде, а перечнем: даты ISO, номера разделов и числа
// внутри кодов сущностей числами документа не являются. Квалификаторы
// («не менее», «только», «кроме») приходят СПИСКОМ ДАННЫМИ из шаблона —
// правило языка меняется без пересборки.
package orbita.documents.internal

internal object NumberGuard {

    private val ЧИСЛО = Regex(
        """(?<![\w-])(?:[≤≥]\s*)?-?\d+(?:[.,]\d+)?(?:\s*(?:[%°]|[%°]?[A-Za-zА-Яа-яЁё³²]+(?:/[A-Za-zА-Яа-яЁё³²]+)*))?""",
    )
    private val КОД = Regex("""\[[A-ZА-Я]{1,12}-\d+]|\b[a-z_]+-[0-9a-f]{6,}\b""")
    private val ДАТА = Regex("""\b\d{4}-\d{2}-\d{2}(?:T\S+)?\b""")
    private val РАЗДЕЛ = Regex("""§\s*\d+(?:\.\d+)*""")

    /** Числа текста в нормализованном виде; служебное отсеяно исключениями. */
    fun numbers(text: String): List<String> {
        val очищенный = РАЗДЕЛ.replace(ДАТА.replace(КОД.replace(text, " "), " "), " ")
        return ЧИСЛО.findAll(очищенный).map { нормализовать(it.value) }.toList()
    }

    /**
     * Квалификаторы текста по списку из шаблона. Найденное возвращается в
     * нижнем регистре: рецензия сравнивает наборы, а не написание.
     */
    fun qualifiers(text: String, словарь: List<String>): List<String> {
        val низ = text.lowercase()
        return словарь.filter { низ.contains(it.lowercase()) }.map { it.lowercase() }.sorted()
    }

    /** Числа текста, которых нет ни в одной опоре. Пусто — тезис чист. */
    fun unsupported(text: String, опоры: List<String>): List<String> {
        val вОпорах = опоры.flatMap { numbers(it) }.toSet()
        return numbers(text).filterNot { it in вОпорах }.distinct()
    }

    private fun нормализовать(токен: String): String {
        val м = Regex("""-?\d+(?:[.,]\d+)?""").find(токен) ?: return токен
        val сырое = м.value.replace(",", ".").removePrefix("+")
        val число = сырое.toDoubleOrNull()?.let { d ->
            if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString()
        } ?: сырое
        val оператор = when {
            "≤" in токен.take(м.range.first) -> "≤"
            "≥" in токен.take(м.range.first) -> "≥"
            else -> ""
        }
        val единица = токен.substring(м.range.last + 1).trim().lowercase()
            .replace("³", "3").replace("²", "2")
        return оператор + if (единица.isBlank()) число else "$число $единица"
    }
}
