// Промпт внешнего исследования: три блока — контекст проекта · вопросы ·
// требования к ответу (образец — ПРОМПТ-ИССЛЕДОВАНИЕ-ПРИМОРЬЕ.md).
//
// Промпт уходит ЧЕЛОВЕКУ файлом, а исследование выполняет внешний контур —
// поиск, Deep Research, назначенный аналитик. Продукт токенов на это не
// тратит: здесь нет ни одного вызова модели, и `orbita.ai` этот файл не
// импортирует ни строкой. Всё, что в промпте есть, взято из среза поля
// подстановкой.
//
// Гриф проверяется ПЕРЕД выдачей (classification_rule): `dsp` и `commercial`
// во внешний контур без явного разрешения не уходят — записи, опирающиеся на
// такой источник, в промпт не попадают и считаются строкой «не включено»;
// `state_secret` в поле знаний не попадает вовсе, и его присутствие — не повод
// тихо отфильтровать, а повод отказать.
package orbita.knowledge.internal

import orbita.kernel.api.Area
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry
import java.security.MessageDigest

/**
 * Срез поля для промпта — то, что внешний контур обязан знать о проекте,
 * чтобы не искать уже найденное.
 *
 * @property скрыто сколько записей не вошло из-за грифа: человек обязан
 *   видеть, что поле показано не целиком
 * @property отпечаток по нему видно, что промпт собран по нынешнему полю
 */
internal data class ПолеПроекта(
    val замысел: String,
    val где: String,
    val рамка: List<String>,
    val стороны: List<String>,
    val цели: List<String>,
    val нормы: List<String>,
    val скрыто: Int,
    val отпечаток: String,
) {
    internal companion object {
        /** Пустое поле — только для отпечатка шаблонов вопросов, не для выдачи. */
        val пустое = ПолеПроекта("", "", emptyList(), emptyList(), emptyList(), emptyList(), 0, "")
    }
}

internal class ResearchPrompt(
    private val store: EntityStore,
    private val links: LinkRegistry?,
) {

    /**
     * Срез поля проекта с проверкой грифа.
     *
     * Читает только принятое поле постановки: замысел, рамку, стороны, цели и
     * уже названные нормы. Материалы в промпт не идут ни именем, ни текстом —
     * наружу уходит то, что проект УЖЕ знает, а не то, из чего он это узнал.
     */
    fun поле(project: String): ПолеПроекта {
        val область = Area.Project(project)
        val материалы = store.list(область, "material").associateBy { it.code }
        материалы.values.firstOrNull { гриф(it.doc.path("classification").asText("")) == СЕКРЕТ }?.let {
            error(
                "материал «${it.code}» с грифом «государственная тайна» в поле знаний быть не должен: " +
                    "промпт внешнего исследования не собирается (classification_rule)"
            )
        }
        val факты = store.list(область, "fact").filter { it.status != "cancelled" }
        val грифФакта = факты.associate { ф ->
            val свой = гриф(ф.doc.path("classification").asText(""))
            val источник = материалы[ф.doc.path("material").asText("")]
                ?.let { гриф(it.doc.path("classification").asText("")) } ?: ОТКРЫТО
            ф.id to maxOf(свой, источник)
        }
        var скрыто = 0
        // Запись, опирающаяся на закрытый источник, во внешний контур не
        // уходит — ни значением, ни формулировкой. Записи без оснований
        // (рука инженера) остаются: грифа у них нет, он живёт у источника.
        fun открыта(сущность: Entity): Boolean {
            val закрыта = links?.from(сущность.id, "derived_from_fact").orEmpty()
                .any { (грифФакта[it.to] ?: ОТКРЫТО) >= ЗАКРЫТО }
            if (закрыта) скрыто += 1
            return !закрыта
        }

        val замысел = store.list(область, "intent").firstOrNull { открыта(it) }
        val рамка = store.list(область, "constraint")
            .filter { it.status != "cancelled" && !it.doc.path("cancelled").asBoolean(false) && открыта(it) }
            .map {
                listOf(it.doc.path("code").asText("").ifBlank { it.code }, it.doc.path("statement").asText(""))
                    .filter { часть -> часть.isNotBlank() }.joinToString(" ")
            }
        val стороны = store.list(область, "stakeholder")
            .filter { it.status != "cancelled" && открыта(it) }
            .map { it.doc.path("name").asText("").ifBlank { it.code } }
        val цели = store.list(область, "goal")
            .filter { it.status != "cancelled" && открыта(it) }
            .map { строкой(it.doc.path("statement").asText("").ifBlank { it.code }, it.doc.path("year").asText("")) }
        // Нормы, уже названные полем: обязательства — это факты, а не сущности
        // проекта; карточка норматива живёт на полке и общая для всех проектов.
        val нормы = факты
            .filter { it.doc.path("kind").asText("") == "obligation" }
            .filter { (грифФакта[it.id] ?: ОТКРЫТО) < ЗАКРЫТО }
            .mapNotNull { it.doc.path("subject").asText("").trim().ifBlank { null } }
            .distinct()

        // В отпечаток идёт ВСЁ поле, а не только показанное: новый материал с
        // фактами меняет то, чего проекту не хватает, даже если ни одной новой
        // стороны из него ещё не принято.
        val срез = listOfNotNull(замысел) + факты +
            store.list(область, "constraint") + store.list(область, "stakeholder") + store.list(область, "goal")
        return ПолеПроекта(
            замысел = замыслом(замысел),
            где = замысел?.doc?.path("where")?.asText("").orEmpty(),
            рамка = рамка,
            стороны = стороны,
            цели = цели,
            нормы = нормы,
            скрыто = скрыто,
            отпечаток = отпечаток(project, срез),
        )
    }

    /**
     * Промпт файлом: контекст · вопросы · требования.
     *
     * @param отпечатокЗадачи срез, по которому вопросы собирались. Расхождение
     *   с нынешним полем не скрывается: промпт прямо говорит, что поле с тех
     *   пор изменилось, — иначе человек унесёт наружу вчерашний вопрос,
     *   считая его сегодняшним.
     */
    fun собрать(
        задача: String,
        место: String,
        цикл: Int,
        поле: ПолеПроекта,
        вопросы: List<String>,
        отпечатокЗадачи: String,
    ): String {
        val текст = StringBuilder()
        текст.append("# Промпт внешнего исследования · цикл $цикл · $место\n\n")
        текст.append(
            "*Собран системой из среза поля (задача $задача). Запускается во внешнем контуре с " +
                "поиском. Результат возвращается файлом и грузится материалом ранга «сомнительный» " +
                "до подтверждения источников человеком.*\n\n"
        )
        текст.append("## Контекст проекта (срез поля, отпечаток `${коротко(отпечатокЗадачи)}`)\n\n")
        if (отпечатокЗадачи != поле.отпечаток) {
            текст.append(
                "*Поле изменилось с момента формулирования (сейчас `${коротко(поле.отпечаток)}`): " +
                    "вопросы собраны по прежнему срезу — сформулируйте заново, если это важно.*\n\n"
            )
        }
        абзац(текст, "Замысел", поле.замысел)
        абзац(текст, "Рамка", поле.рамка.joinToString(" · "))
        абзац(текст, "Стороны уже названы (${поле.стороны.size})", поле.стороны.joinToString(", "))
        абзац(текст, "Цели проекта", поле.цели.joinToString(" · "))
        абзац(текст, "Нормы уже названы", поле.нормы.joinToString(", "))
        if (поле.скрыто > 0) {
            текст.append(
                "*Не включено записей: ${поле.скрыто} — гриф источника: во внешний контур они " +
                    "уходят только с явного разрешения.*\n\n"
            )
        }
        текст.append("## Вопросы исследования\n\n")
        вопросы.forEachIndexed { i, вопрос ->
            val класс = ResearchQuestions.класс(вопрос)?.word ?: "вопрос"
            текст.append("${i + 1}. **${класс.replaceFirstChar { it.uppercase() }}.** ")
            текст.append(вопрос.substringAfter(":").trim()).append("\n")
        }
        текст.append("\n## Требования к ответу\n\n").append(ТРЕБОВАНИЯ)
        return текст.toString()
    }

    private fun абзац(текст: StringBuilder, имя: String, значение: String) {
        if (значение.isBlank()) return
        текст.append("**$имя.** ").append(значение).append("\n\n")
    }

    private fun замыслом(сущность: Entity?): String {
        val документ = сущность?.doc ?: return ""
        return listOf("what", "for_whom", "where", "horizon")
            .map { документ.path(it).asText("").trim() }
            .filter { it.isNotBlank() }
            .joinToString("; ")
    }

    private fun строкой(голова: String, хвост: String): String =
        if (хвост.isBlank()) голова else "$голова ($хвост)"

    /**
     * Отпечаток среза: то же поле даёт тот же отпечаток. По нему видно, что
     * вопросы собраны по нынешнему полю, а не по вчерашнему.
     */
    private fun отпечаток(project: String, срез: List<Entity>): String {
        val слепок = buildString {
            append(project).append('\n')
            append(version).append('\n')
            срез.distinctBy { it.id }.sortedBy { it.code }
                .forEach { append(it.code).append(':').append(it.version).append('\n') }
        }
        return MessageDigest.getInstance("SHA-256").digest(слепок.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun коротко(отпечаток: String): String = отпечаток.take(12).ifBlank { "—" }

    /** Гриф числом: открыто < для служебного пользования и коммерческой тайны < гостайна. */
    private fun гриф(значение: String): Int = when (значение.trim()) {
        "dsp", "commercial" -> ЗАКРЫТО
        "state_secret" -> СЕКРЕТ
        else -> ОТКРЫТО
    }

    internal companion object {

        private const val ОТКРЫТО = 0
        private const val ЗАКРЫТО = 1
        private const val СЕКРЕТ = 2

        /** Требования к ответу — дословно из образца поставки; часть отпечатка версии. */
        private val ТРЕБОВАНИЯ: String = """
            - Каждое утверждение — с источником (URL или реквизит документа), датой публикации и
              датой проверки; метка **[В]** у внешнего, **[П]** у вашего предположения; уверенность 0–1.
            - Числа — с единицей; если источники расходятся — диапазон с обоими источниками.
            - «Не найдено» — законный ответ; выдумывать нельзя.
            - Формат — таблица по каждому вопросу: `класс (сторона · программа · норма · применение ·
              аналог) · утверждение · значение · источник · дата · метка · уверенность`; ТЕМА строки —
              её класс: по нему принятое раскладывается по классам.
            - В конце — список открытых вопросов для следующего цикла.
            - Объём — не более 60 строк на все вопросы; качество источников важнее количества.
        """.trimIndent() + "\n"

        /**
         * Версия сборки промпта: по ней запуск воспроизводится.
         *
         * Отпечаток берётся с ШАБЛОНА — требований к ответу и формулировок
         * вопросов. Правка любой из них меняет версию: иначе два запуска с
         * разными вопросами назвались бы одной версией, и повторить запуск по
         * его записи стало бы нечем.
         */
        val version: String by lazy {
            val слепок = ТРЕБОВАНИЯ + "\n" + ResearchQuestions.шаблоны
            val хеш = MessageDigest.getInstance("SHA-256").digest(слепок.toByteArray())
                .joinToString("") { "%02x".format(it) }.take(8)
            "research/v1+$хеш"
        }
    }
}
