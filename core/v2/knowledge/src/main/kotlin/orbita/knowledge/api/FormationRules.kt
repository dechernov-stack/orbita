// Правило образования понятия — из онтологии, на НАШЕЙ стороне.
//
// Онтология говорит, ИЗ КАКИХ фактов понятие образуется (`from_facts`): вид
// факта, предикаты, субъект, метка, признак. До 14.09 это правило жило только
// в истине YAML — промпт его не нёс, а приём плана и акцепт предложения не
// проверяли. Постановка набралась из чего попало: строка РОЛИ стороны стала
// нуждой, автор указа — стороной (остановка ПМИ-6).
//
// Здесь правило становится воротами. Ворота — наши: служба предлагает, правило
// проверяет. Второй копии правила нет, признаки берутся у сгенерированной
// онтологии, а признак, которого разбор не знает, назван отказом, а не
// пропущен молча.
package orbita.knowledge.api

import com.fasterxml.jackson.databind.JsonNode
import orbita.knowledge.schema.Concept
import orbita.knowledge.schema.FactRule

object FormationRules {

    /**
     * Признаки факта, по которым правило образования его узнаёт. Отдельный тип
     * нужен, чтобы одно правило служило и приёму плана (там факт — JSON), и
     * синтезу (там факт уже разобран).
     */
    data class Признаки(
        val kind: String,
        val subject: String,
        val predicate: String,
        val value: String,
        val mark: String,
        val entityClass: String,
        val asOf: String,
    )

    /**
     * Годится ли факт в основание понятия: подходит ли он хотя бы под одно
     * правило образования. Правило-подсказка (только `section_hint`) воротами
     * не служит: оно адресовано разбору, а не проверке, и считать его
     * совпадением значило бы пускать что угодно.
     */
    /**
     * @param знаетСубъекта опознаватель субъекта: «это сторона проекта?».
     *   Нужен потому, что класс сущности разбор проставляет не всегда (вне
     *   режима ТЗ его нет вовсе), а факт-оценка об уже заведённой стороне —
     *   законное основание нужды. Без опознавателя правило судит только по
     *   классу, и оценка об известной стороне отбивалась бы напрасно.
     */
    fun подходит(
        понятие: Concept,
        факт: Признаки,
        знаетСубъекта: ((String, String) -> Boolean)? = null,
    ): Boolean = понятие.fromFacts.any { правило ->
        проверяемое(правило) && совпало(правило, факт, знаетСубъекта)
    }

    fun подходит(
        понятие: Concept,
        факт: JsonNode,
        знаетСубъекта: ((String, String) -> Boolean)? = null,
    ): Boolean = подходит(понятие, признаки(факт), знаетСубъекта)

    fun подходит(
        понятие: Concept,
        факт: Fact,
        знаетСубъекта: ((String, String) -> Boolean)? = null,
    ): Boolean = подходит(понятие, признаки(факт), знаетСубъекта)

    /** Есть ли у понятия хоть одно проверяемое правило: иначе ворот нет. */
    fun проверяемо(понятие: Concept): Boolean = понятие.fromFacts.any { проверяемое(it) }

    /**
     * Отказ словами: чего факт не даёт. Человек читает не «не подходит», а ЧТО
     * именно требуется — иначе он не знает, что чинить.
     */
    fun почемуНе(понятие: Concept, факт: Признаки): String {
        val вид = факт.kind.ifBlank { "не назван" }
        val требуется = понятие.fromFacts.filter { проверяемое(it) }.joinToString("; ") { словами(it) }
        return "факт [$вид] «${факт.predicate.take(60)}» не подходит под правило образования " +
            "понятия «${понятие.code}»: требуется $требуется"
    }

    fun почемуНе(понятие: Concept, факт: JsonNode): String = почемуНе(понятие, признаки(факт))

    fun почемуНе(понятие: Concept, факт: Fact): String = почемуНе(понятие, признаки(факт))

    /** Правило словами — теми же признаками, какими оно записано в истине. */
    fun словами(правило: FactRule): String {
        // Вид и предикат читаются одной фразой: «вид [framing] с предикатом
        // из …». Запятая между ними делала бы из одного требования два.
        val голова = buildString {
            правило.kind?.let { append("вид [$it]") }
            if (правило.predicateIn.isNotEmpty()) {
                if (isNotEmpty()) append(" с ") else append("с ")
                append("предикатом из «${правило.predicateIn.joinToString(" · ")}»")
            }
        }
        val хвост = buildList {
            правило.subject?.let { add("субъект «$it»") }
            правило.subjectIs?.let { add("субъект — $it") }
            правило.mark?.let { add("метка [$it]") }
            правило.with?.let { add("несёт «$it»") }
        }
        return (listOfNotNull(голова.ifEmpty { null }) + хвост)
            .joinToString(", ").ifEmpty { "правило без признаков" }
    }

    fun признаки(факт: JsonNode): Признаки = Признаки(
        kind = факт.path("kind").asText(""),
        subject = факт.path("subject").asText(""),
        predicate = факт.path("predicate").asText(""),
        value = факт.path("value").asText(""),
        mark = факт.path("source_mark").asText("").ifBlank { факт.path("mark").asText("") },
        entityClass = факт.path("entity_class").asText(""),
        asOf = факт.path("as_of").asText(""),
    )

    fun признаки(факт: Fact): Признаки = Признаки(
        kind = факт.kind,
        subject = факт.subject,
        predicate = факт.predicate,
        value = факт.value,
        mark = факт.mark.name,
        entityClass = факт.entityClass.orEmpty(),
        asOf = факт.sourceUpdated.orEmpty(),
    )

    /** Правило, которое можно проверить данными факта. */
    private fun проверяемое(правило: FactRule): Boolean =
        правило.kind != null || правило.predicateIn.isNotEmpty() ||
            правило.subject != null || правило.subjectIs != null ||
            правило.mark != null || правило.with != null

    private fun совпало(
        правило: FactRule,
        факт: Признаки,
        знаетСубъекта: ((String, String) -> Boolean)? = null,
    ): Boolean {
        правило.kind?.let { если -> if (факт.kind != если) return false }
        if (правило.predicateIn.isNotEmpty()) {
            val предикат = факт.predicate.lowercase()
            if (правило.predicateIn.none { предикат.contains(it.lowercase()) }) return false
        }
        правило.subject?.let { если ->
            if (!факт.subject.lowercase().contains(если.lowercase())) return false
        }
        // «Субъект — сторона» узнаётся двумя путями: классом, который назвал
        // разбор, либо справочником проекта. Одного класса мало: вне режима ТЗ
        // разбор его не ставит, и оценка об уже заведённой стороне отбивалась
        // бы напрасно (уточнение владельца 14.09: интерес стороны — законное
        // основание нужды, роль — никогда).
        правило.subjectIs?.let { если ->
            val поКлассу = факт.entityClass == если
            val поРеестру = знаетСубъекта?.invoke(если, факт.subject) == true
            if (!поКлассу && !поРеестру) return false
        }
        правило.mark?.let { если -> if (факт.mark != если) return false }
        правило.with?.let { если -> if (!несёт(факт, если)) return false }
        return true
    }

    /**
     * Признак, который факт обязан нести: «horizon/year», «date|year»,
     * «designation». Альтернативы разделяются «|» или «/»; довольно одной.
     */
    private fun несёт(факт: Признаки, признак: String): Boolean =
        признак.split('|', '/').map { it.trim() }.filter { it.isNotEmpty() }.any { токен ->
            val где = listOf(факт.value, факт.asOf, факт.subject, факт.predicate).joinToString(" ")
            when (токен) {
                "year", "horizon" -> ГОД.containsMatchIn(где)
                "date" -> ДАТА.containsMatchIn(где)
                "designation" -> ОБОЗНАЧЕНИЕ.containsMatchIn(где)
                // Признак, которого правило не знает, — отказ, а не «подошло»:
                // тихое согласие здесь и есть та дыра, через которую понятия
                // образовывались из чего попало.
                else -> false
            }
        }

    private val ГОД = Regex("""\b(19|20)\d{2}\b""")
    private val ДАТА = Regex("""\b\d{1,2}[.\-/]\d{1,2}[.\-/](19|20)?\d{2}\b|\b(19|20)\d{2}-\d{2}-\d{2}\b""")
    private val ОБОЗНАЧЕНИЕ = Regex("""[№N]\s?\d+|ГОСТ|ПНСТ|ПП\s?РФ|Указ|СП\s?\d|ТР\s?ТС""", RegexOption.IGNORE_CASE)
}
