// Ключи идентичности понятий: ступень 1 сверки — детерминированная и без
// единого вызова модели.
//
// Порядок сверки задан поставкой (СВЕРКА-РУЧНОГО-ВВОДА): сначала ключ
// идентичности понятия — дёшево, по данным; и только если ключ не сошёлся,
// вопрос уходит на семантику по срезу — дорого, токенами. Поэтому здесь нет
// ни одной ссылки на службу ИИ: всё, что решается по данным, решается по
// данным, иначе мера «дубль по ключу найден без живого вызова» недостижима.
//
// Ключ возвращается вместе с ПЕРЕЧНЕМ ПОЛЕЙ, из которых сложился. Без этого
// перечня находка звучит «похоже» без «чем именно» — брак по правам ИИ
// (ОНТОЛОГИЯ-ФОРМИРОВАНИЯ, reconciliation.rights).
//
// Какие поля образуют ключ понятия, здесь НЕ решается: перечень читается из
// онтологии (`GeneratedOntology.of(понятие).identity.key`). Онтология —
// вторая истина пакета, и второй её копии в коде быть не должно. Как
// сложить ключ из названных полей — забота этого файла; какие поля назвать
// — забота онтологии.
package orbita.knowledge.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.kernel.api.Area
import orbita.kernel.api.EntityStore
import orbita.knowledge.schema.GeneratedOntology
import orbita.library.api.Shelves

/**
 * Ключ идентичности: строка сравнения и поля кандидата, из которых она
 * сложилась.
 *
 * @property fields то самое «чем именно сравнивали» — уходит в находку
 *   сверки (compared_fields) и печатается человеку
 */
internal data class Ключ(val value: String, val fields: List<String>) {
    val сложился: Boolean get() = value.isNotBlank()
}

/**
 * Ключи идентичности понятий поля знаний.
 *
 * @param нормализация справочник единиц: показатель цели без ключа узнаётся
 *   размерностью своей единицы
 * @param словарь написание термина проекта → его канон; лемматизация идёт
 *   по словарю проекта, а не по морфологии вообще
 * @param shelves полка: правило «один акт — одна карточка» живёт в ней, и
 *   естественный ключ норматива берётся оттуда, а не переписывается здесь
 */
internal class IdentityKeys(
    private val нормализация: Normalize = Normalize(),
    private val словарь: Map<String, String> = emptyMap(),
    private val shelves: Shelves? = null,
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    /** Многословные термины словаря — заменяются до разбиения на слова. */
    private val фразы: List<Pair<String, String>> = словарь.entries
        .filter { it.key.contains(' ') }
        .sortedByDescending { it.key.length }
        .map { it.key to it.value.replace(' ', '_') }

    /**
     * Ядро формулировки: регистр снят, служебные слова убраны, слова сведены
     * к основам и отсортированы.
     *
     * Сортировка — не эстетика: «нужна связь в Арктике» и «в Арктике нужна
     * связь» об одном и том же, и порядок слов не должен заводить второй
     * сущности. Отрицания («не», «без») в служебные НЕ входят: снять их
     * значит склеить требование с его противоположностью.
     */
    fun statementCore(текст: String, field: String = "statement"): Ключ {
        val основы = основы(текст)
        return Ключ(основы.joinToString(" "), if (основы.isEmpty()) emptyList() else listOf(field))
    }

    /**
     * Имя организации к сравнимому виду: кавычки, регистр и организационная
     * форма сняты, синоним сведён к канону словаря.
     *
     * «ФГУП „Космическая связь“» и «Космическая связь» — одна сторона:
     * форма собственности живёт полем карточки, а не различает организации.
     */
    fun nameNormalized(имя: String, field: String = "name"): Ключ {
        val слова = плоско(имя).split(" ").filter { it.isNotBlank() && it !in ОРГФОРМЫ }
        val строка = слова.joinToString(" ")
        val канон = словарь[строка] ?: строка
        return Ключ(канон, if (канон.isBlank()) emptyList() else listOf(field))
    }

    /** Ядро имени: то же, что ядро формулировки, но поверх нормализованного имени. */
    fun nameCore(имя: String, field: String = "name"): Ключ =
        statementCore(nameNormalized(имя, field).value, field)

    /**
     * Ключ величины: что меряем, каким оператором и сколько — в каноне
     * справочника. «P95 180 мин» и «P95 3 ч» дают один ключ; величина,
     * которую справочник не знает, ключа не даёт вовсе.
     */
    fun measureKey(величина: JsonNode, field: String = "measure"): Ключ {
        val канон = нормализация.величина(величина).канон ?: return Ключ("", emptyList())
        val показатель = показательВеличины(величина, field) ?: return Ключ("", emptyList())
        val оператор = величина.path("op").asText("=").trim().ifBlank { "=" }
        val значение = канон.value
        val текст = "${показатель.value} $оператор $значение ${канон.unit}"
        return Ключ(текст, показатель.fields + listOf("$field.op", "$field.value", "$field.unit"))
    }

    /**
     * Естественный ключ норматива: карточка полки, если акт ей известен.
     *
     * Род и номер акта разбирает ПОЛКА — там правило «один акт — одна
     * карточка» и живёт. Вторая такая разборка здесь разошлась бы с первой,
     * и один и тот же ПП РФ снова оказался бы двумя актами.
     */
    fun designationNatural(обозначение: String, field: String = "designation"): Ключ {
        val очищенное = обозначение.replace(Regex("\\([^)]*\\)"), " ").trim()
        if (очищенное.isBlank()) return Ключ("", emptyList())
        val карточка = shelves?.matching(
            "normative_document",
            mapper.createObjectNode().put("designation", очищенное),
        )
        val значение = карточка?.code ?: плоско(очищенное).replace(" ", "")
        return Ключ(значение, listOf(field))
    }

    /**
     * Ключ понятия по перечню полей из онтологии (`identity.key`).
     *
     * `null` — ключ не сложился: у кандидата нет того, на чём держится
     * идентичность понятия (нужда без стороны, цель без показателя). Это не
     * сбой: такой кандидат уходит на семантическую ступень и к воротам
     * нехватки, а ключ на неполном вводе не выдумывается.
     */
    fun of(keyFields: List<String>, payload: JsonNode): Ключ? {
        val части = mutableListOf<String>()
        val поля = mutableListOf<String>()
        keyFields.forEach { токен ->
            val ключ = поТокену(токен, payload)
            if (ключ == null || !ключ.сложился) return null
            части += ключ.value
            поля += ключ.fields
        }
        if (части.isEmpty()) return null
        return Ключ(части.joinToString("|"), поля.distinct())
    }

    /**
     * Ключ понятия по онтологии: перечень полей — `identity.key` понятия из
     * ОНТОЛОГИЯ-ФОРМИРОВАНИЯ. Второй копии этого перечня в коде нет; понятие
     * вне онтологии ключа не получает вовсе — генератор отвечает отказом.
     */
    fun ofConcept(concept: String, payload: JsonNode): Ключ? =
        of(GeneratedOntology.of(concept).identity.key, payload)

    /** «a|b» в онтологии — альтернативы по порядку: берётся первая сложившаяся. */
    private fun поТокену(токен: String, payload: JsonNode): Ключ? {
        токен.split("|").map { it.trim() }.forEach { часть ->
            val ключ = одинТокен(часть, payload)
            if (ключ != null && ключ.сложился) return ключ
        }
        return null
    }

    private fun одинТокен(токен: String, payload: JsonNode): Ключ? = when (токен) {
        "statement_core" -> {
            val поле = listOf("statement", "text", "label").firstOrNull {
                payload.path(it).asText("").isNotBlank()
            }
            поле?.let { statementCore(payload.path(it).asText(""), it) }
        }
        // Синонимы сведены к канону в самом nameNormalized — отдельной ветки нет.
        "name_normalized", "synonyms" -> nameNormalized(payload.path("name").asText(""))
        "name_core" -> nameCore(payload.path("name").asText(""))
        "measure.key" -> показательВеличины(payload.path("measure"), "measure")
        "bound.key" -> показательВеличины(payload.path("bound"), "bound")
        "designation_natural" -> designationNatural(payload.path("designation").asText(""))
        // Поле-ссылка (нужда: сторона) — код как есть: коды не нормализуются.
        else -> payload.path(токен).asText("").trim()
            .takeIf { it.isNotBlank() }?.let { Ключ(it, listOf(токен)) }
    }

    /**
     * Что меряет величина. У рамки это поле `bound.key`; у цели ключа полем
     * нет, и роль показателя играет размерность единицы — «180 МКА» и «200
     * МКА» об одном и том же результате, а «180 МКА» и «3 года» — о разном.
     */
    private fun показательВеличины(величина: JsonNode, field: String): Ключ? {
        val ключ = величина.path("key").asText("").trim().lowercase()
        if (ключ.isNotBlank()) return Ключ(ключ, listOf("$field.key"))
        val размерность = нормализация.размерность(величина.path("unit").asText("")) ?: return null
        return Ключ("размерность:$размерность", listOf("$field.unit"))
    }

    /** Основы формулировки: словарь → служебные слова → основа → без повторов → по алфавиту. */
    private fun основы(текст: String): List<String> =
        поСловарю(текст).split(" ")
            .filter { it.isNotBlank() }
            .map { словарь[it] ?: it }
            .filter { it !in СЛУЖЕБНЫЕ }
            .map { основа(it) }
            .distinct()
            .sorted()

    private fun поСловарю(текст: String): String {
        var строка = " " + плоско(текст) + " "
        фразы.forEach { (написание, канон) -> строка = строка.replace(" $написание ", " $канон ") }
        return строка.trim()
    }

    companion object {

        /**
         * Ключи по данным проекта: словарь — из терминов глоссария (полка и
         * проект), справочник единиц и план — через `Normalize`.
         */
        fun of(store: EntityStore, area: Area, shelves: Shelves? = null): IdentityKeys {
            val словарь = mutableMapOf<String, String>()
            (store.list(Area.Library, "glossary_term") + store.list(area, "glossary_term")).forEach { термин ->
                val канон = плоско(термин.doc.path("term_ru").asText(""))
                if (канон.isBlank()) return@forEach
                listOf(термин.doc.path("term_ru").asText(""), термин.doc.path("term_en").asText(""))
                    .forEach { написание ->
                        val ключ = плоско(написание)
                        if (ключ.isNotBlank()) словарь[ключ] = канон
                    }
            }
            return IdentityKeys(Normalize.of(store, area), словарь, shelves)
        }

        /**
         * Служебные слова постановки: «необходимо обеспечить связь» и «связь
         * требуется» — одна нужда. Отрицаний и предлога «без» здесь нет
         * намеренно: они меняют смысл, а не оформляют его.
         */
        private val СЛУЖЕБНЫЕ = setOf(
            "необходимо", "необходима", "необходим", "необходимы",
            "требуется", "требуются", "требуемый",
            "должен", "должна", "должно", "должны", "быть",
            "обеспечить", "обеспечен", "обеспечена", "обеспечено", "обеспечены", "обеспечивается",
            "нужен", "нужна", "нужно", "нужны", "следует", "предусмотреть", "иметь",
            "в", "во", "на", "для", "с", "со", "по", "от", "из", "за", "к", "ко", "о", "об", "при",
            "и", "а", "или", "что", "как", "у", "же", "это", "том", "чтобы",
        )

        /** Организационная форма — поле карточки, а не имя организации. */
        private val ОРГФОРМЫ = setOf(
            "ао", "оао", "зао", "пао", "нао", "ооо", "фгуп", "гуп", "муп",
            "фгбу", "фгау", "фку", "фгбну", "ано", "нко",
        )

        /**
         * Окончания русских слов: лемматизации в изделии нет и не будет —
         * словарь морфологии тянет зависимость ради ключа сравнения. Основа
         * режется консервативно и только для ключа: в тексте, который видит
         * человек, слово остаётся целым.
         */
        private val ОКОНЧАНИЯ = listOf(
            "ами", "ями", "ого", "его", "ому", "ему", "ыми", "ими", "ия", "ии", "ию",
            "ая", "яя", "ое", "ее", "ые", "ие", "ый", "ий", "ой", "ей", "ем", "ом",
            "ах", "ях", "ов", "ев", "ью",
            "у", "ю", "а", "я", "ы", "и", "о", "е", "ь",
        ).sortedByDescending { it.length }

        /** Основа слова: короткое слово, число и канон словаря не режутся. */
        private fun основа(слово: String): String {
            if (слово.length < 4 || слово.contains('_') || слово.any { it.isDigit() }) return слово
            val окончание = ОКОНЧАНИЯ.firstOrNull { слово.endsWith(it) && слово.length - it.length >= 3 }
            return if (окончание == null) слово else слово.dropLast(окончание.length)
        }

        /** Текст к плоскому виду: регистр, «ё» и пунктуация ключа не меняют. */
        private fun плоско(текст: String): String =
            текст.lowercase().replace('ё', 'е')
                .map { if (it.isLetterOrDigit() || it == '_') it else ' ' }
                .joinToString("")
                .replace(Regex("\\s+"), " ")
                .trim()
    }
}
