// Задачи исследования полноты: цикл «промпт → запуск → результат → ещё раз».
//
// Контур целиком БЕСПЛАТЕН: ни один метод здесь не зовёт модель. Вопросы
// собираются из среза поля подстановкой, промпт уходит человеку файлом,
// исследование выполняет внешний контур. Мера прямая — журнал ИИ после
// формулирования и запуска не прирастает.
//
// Результат в модель мимо общего пути не попадает (research_rule): он
// возвращается МАТЕРИАЛОМ ранга «сомнительный» со связью `output_of` на
// задачу и дальше идёт как любой другой материал — атомизация, синтез,
// сверка, решение человека. До подтверждения источников ЧЕЛОВЕКОМ ранг не
// поднимается: непроверенное не имеет права зачесть само себя.
package orbita.knowledge.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry
import orbita.kernel.api.Provenance
import orbita.knowledge.api.AcceptedSummary
import orbita.knowledge.api.Authority
import orbita.knowledge.api.Intake
import orbita.knowledge.api.Research
import orbita.knowledge.api.ResearchClass
import orbita.knowledge.api.ResearchTask
import orbita.knowledge.api.ResearchTrigger
import orbita.library.api.Shelves
import java.time.LocalDate

/**
 * @param intake приём знаний: материал результата заводится ИМ. Второй записи
 *   материалов в проекте быть не должно — ранг, канон и пометы живут в одном
 *   месте, иначе результат исследования окажется материалом не по правилам.
 */
internal class ResearchTasks(
    private val store: EntityStore,
    private val links: LinkRegistry? = null,
    private val mapper: ObjectMapper = ObjectMapper(),
    private val shelves: Shelves? = null,
    private val intake: Intake = EntityIntake(store, links, mapper, shelves),
) : Research {

    private val промпт = ResearchPrompt(store, links)

    // --- вопросы -----------------------------------------------------------

    override fun formulate(
        project: String,
        trigger: ResearchTrigger,
        author: String,
        questions: List<String>,
    ): ResearchTask {
        require(author.isNotBlank()) { "исследование без автора не заводится: спрашивает человек" }
        место(trigger)
        val область = Area.Project(project)
        val поле = промпт.поле(project)
        val вопросы = if (questions.isEmpty()) {
            ResearchQuestions.вопросы(trigger, поле, остатки(область))
        } else {
            ResearchQuestions.проверить(questions)
        }
        return завести(область, trigger, вопросы, поле, author, "вопросы собраны из среза поля")
    }

    override fun next(
        project: String,
        task: String,
        author: String,
        questions: List<String>,
    ): ResearchTask {
        require(author.isNotBlank()) { "следующий цикл без автора не заводится: продолжает человек" }
        val область = Area.Project(project)
        val прежняя = запись(область, task)
        val статус = прежняя.status
        require(статус == ПОЛУЧЕНО || статус == РАЗОБРАНО) {
            "цикл «$task» ещё не завершён (${слово(статус)}): следующий цикл идёт ПО ОСТАТКАМ, " +
                "а остатки видны только по принятому из результата"
        }
        val открытые = итог(прежняя.doc).open()
        val дописанные = if (questions.isEmpty()) emptyList() else ResearchQuestions.проверить(questions)
        // Остановка цикла — решение человека, и выглядит она именно так:
        // спрашивать нечего, и новой задачи не заводится (research_rule —
        // «цикл останавливает эксперт, когда прирост фактов незначим»).
        require(открытые.isNotEmpty() || дописанные.isNotEmpty()) {
            "по «$task» принято по всем пяти классам и дописанных вопросов нет: " +
                "следующего цикла не нужно — исследование окончено"
        }
        val поле = промпт.поле(project)
        val вопросы = ResearchQuestions.поКлассам(открытые, поле) + дописанные
        return завести(
            область, место(прежняя.doc), вопросы, поле, author,
            "цикл по остаткам задачи $task",
        )
    }

    /** Одна дверь заведения задачи: и первый цикл, и следующий проходят те же ворота. */
    private fun завести(
        область: Area,
        trigger: ResearchTrigger,
        вопросы: List<String>,
        поле: ПолеПроекта,
        author: String,
        повод: String,
    ): ResearchTask {
        val задачи = задачиМеста(область, trigger)
        // Две открытые задачи по одному месту — два промпта об одном и том же:
        // внешний контур сходит дважды, а ответ вернётся в одну строку экрана.
        задачи.firstOrNull { it.status == ФОРМУЛИРОВАНО || it.status == ЗАПУЩЕНО }?.let {
            throw IllegalStateException(
                "по ${trigger.word} исследование уже заведено (${it.code}, цикл " +
                    "${it.doc.path("iteration").asInt(1)}, ${слово(it.status)}): " +
                    "доведите его до результата либо снимите, второго промпта об одном и том же не нужно"
            )
        }
        val цикл = (задачи.maxOfOrNull { it.doc.path("iteration").asInt(0) } ?: 0) + 1
        val документ = mapper.createObjectNode()
        val узелМеста = документ.putObject("trigger_scene")
        when (trigger) {
            is ResearchTrigger.Scene -> узелМеста.put("scene", trigger.scene)
            is ResearchTrigger.Gate -> узелМеста.put("gate", trigger.gate)
        }
        документ.putArray("questions").also { узел -> вопросы.forEach { узел.add(it) } }
        документ.put("slice_fingerprint", поле.отпечаток)
        документ.put("prompt_version", ResearchPrompt.version)
        документ.put("iteration", цикл)
        документ.put(
            "notes",
            "$повод: вопросов ${вопросы.size}" +
                (if (поле.скрыто == 0) "" else ", не включено записей ${поле.скрыто} (гриф)") +
                "; вызова модели в контуре исследования нет",
        )
        val запись = store.create(
            следующий(область, "research_task", "RT"), "research_task", область,
            // Сцена рождения — та, из которой спрашивают; у точки сцены нет.
            (trigger as? ResearchTrigger.Scene)?.scene, документ,
            // Канал службы: вопросы собрала система из среза поля. Автор —
            // человек: служба происхождение, а не автор.
            Provenance(Channel.SERVICE, author, source = кодМеста(trigger)),
            status = ФОРМУЛИРОВАНО,
        )
        return вид(область, запись, факты(область))
    }

    // --- цикл --------------------------------------------------------------

    override fun task(project: String, task: String): ResearchTask {
        val область = Area.Project(project)
        return вид(область, запись(область, task), факты(область))
    }

    override fun list(project: String): List<ResearchTask> {
        val область = Area.Project(project)
        val всеФакты = факты(область)
        return store.list(область, "research_task").sortedBy { it.code }.map { вид(область, it, всеФакты) }
    }

    override fun prompt(project: String, task: String): String {
        val область = Area.Project(project)
        val запись = запись(область, task)
        // Гриф проверяется ЗДЕСЬ, при сборке среза: промпт уходит человеку
        // файлом и дальше системе не подконтролен (classification_rule).
        val поле = промпт.поле(project)
        return промпт.собрать(
            задача = запись.code,
            место = место(запись.doc).word,
            цикл = запись.doc.path("iteration").asInt(1),
            поле = поле,
            вопросы = запись.doc.path("questions").map { it.asText() },
            отпечатокЗадачи = запись.doc.path("slice_fingerprint").asText(""),
        )
    }

    override fun launch(project: String, task: String, author: String, note: String): ResearchTask {
        require(author.isNotBlank()) { "запуск без автора не ставится: наружу ходит человек" }
        val область = Area.Project(project)
        val запись = запись(область, task)
        require(запись.status == ФОРМУЛИРОВАНО) {
            "«запущено» ставится сформулированной задаче: «$task» сейчас ${слово(запись.status)}"
        }
        val обновлённая = store.update(
            запись.id,
            приписка(запись.doc, "запущено во внешнем контуре: $author, ${LocalDate.now()}", note),
            Provenance(Channel.MANUAL, author),
            status = ЗАПУЩЕНО,
        )
        return вид(область, обновлённая, факты(область))
    }

    override fun result(
        project: String,
        task: String,
        name: String,
        text: String,
        author: String,
    ): ResearchTask {
        require(author.isNotBlank()) { "результат без автора не принимается: принёс его человек" }
        require(name.isNotBlank()) { "у результата нет имени: материал без имени в поле знаний не ляжет" }
        require(text.isNotBlank()) { "результат пуст: принимать нечего" }
        val область = Area.Project(project)
        val запись = запись(область, task)
        require(запись.status == ЗАПУЩЕНО) {
            "результат принимается у запущенной задачи: «$task» сейчас ${слово(запись.status)} — " +
                "исследование сперва уходит во внешний контур"
        }
        require(запись.doc.path("result_material").asText("").isBlank()) {
            "у «$task» результат уже принят (${запись.doc.path("result_material").asText()}): " +
                "второй результат того же цикла — это следующий цикл"
        }
        // Ранг назван ЯВНО и не выводится из типа входного: результат внешнего
        // контура сомнителен по построению, пока источники не подтверждены.
        val материал = intake.putMaterial(
            project = project,
            name = name,
            // Тип — режим разбора (аналитика: полный разбор с выводами), а не
            // доверие: доверие живёт отдельным полем.
            kind = "analysis",
            text = text,
            author = author,
            authority = Authority.DOUBTFUL,
        )
        val заметка = связать(область, материал, запись, author)
        val документ = приписка(
            запись.doc,
            "результат принят материалом $материал (${Authority.word(Authority.DOUBTFUL)}): $name",
            заметка,
        )
        документ.put("result_material", материал)
        val обновлённая = store.update(запись.id, документ, Provenance(Channel.MANUAL, author, source = материал), status = ПОЛУЧЕНО)
        return вид(область, обновлённая, факты(область))
    }

    override fun confirmSources(
        project: String,
        task: String,
        facts: List<String>,
        author: String,
    ): ResearchTask {
        require(author.isNotBlank()) { "подтверждение без автора не ставится: источники смотрел человек" }
        require(facts.isNotEmpty()) { "подтверждать нечего: не назван ни один факт результата" }
        val область = Area.Project(project)
        val запись = запись(область, task)
        val материал = запись.doc.path("result_material").asText("").ifBlank {
            throw IllegalStateException("у «$task» результата ещё нет: подтверждать источники не у чего")
        }
        var сведено = итог(запись.doc)
        // Повтор того же кода — то же подтверждение, а не второе: иначе итог
        // по классам вырос бы от нажатия, а не от найденного.
        val подтверждённые = facts.distinct().map { код ->
            val факт = store.byCode(область, код)?.takeIf { it.kind == "fact" }
                ?: throw IllegalArgumentException("факта «$код» нет в проекте")
            // Чужой материал этой задачей не подтверждается: подтверждение
            // поднимает ранг ЕЁ результата, и зачесть им факт соседнего
            // документа значило бы поднять доверие не тому источнику.
            require(факт.doc.path("material").asText("") == материал) {
                "факт «$код» не из материала результата «$материал»: " +
                    "этой задачей подтверждаются источники её собственного результата"
            }
            факт
        }.filter { it.doc.path("evidence").asText("") != ПОДТВЕРЖДЕНО }
        подтверждённые.forEach { факт ->
            // Свидетельство подтверждено вторым источником — это и есть
            // corroborated (истина схем `fact.evidence`). Ни диспозиция, ни
            // помета [В] при этом не трогаются: их ставит человек отдельно.
            store.update(
                факт.id,
                (факт.doc.deepCopy() as ObjectNode).put("evidence", ПОДТВЕРЖДЕНО),
                Provenance(Channel.MANUAL, author, source = материал),
            )
            классФакта(область, факт)?.let { сведено = сведено.plus(it, 1) }
        }
        val поднят = поднять(область, материал, author)
        val документ = приписка(
            запись.doc,
            "источники подтверждены: фактов ${подтверждённые.size}, $author, ${LocalDate.now()}" +
                (if (поднят) "; материал $материал поднят до «${Authority.word(Authority.REFERENCE)}»" else ""),
            "",
        )
        сводка(документ, сведено)
        val обновлённая = store.update(запись.id, документ, Provenance(Channel.MANUAL, author), status = РАЗОБРАНО)
        return вид(область, обновлённая, факты(область))
    }

    // --- записи и чтение ---------------------------------------------------

    /**
     * Ранг результата поднимается ТОЛЬКО подтверждением человека и только с
     * «сомнительного»: справка, ставшая обязательным документом сама по себе,
     * — это не исследование, а подлог.
     */
    private fun поднять(область: Area, материал: String, author: String): Boolean {
        val карточка = store.byCode(область, материал)?.takeIf { it.kind == "material" } ?: return false
        if (карточка.doc.path("authority").asText("") != Authority.DOUBTFUL) return false
        store.update(
            карточка.id,
            (карточка.doc.deepCopy() as ObjectNode).put("authority", Authority.REFERENCE),
            Provenance(Channel.MANUAL, author, source = "подтверждение источников"),
        )
        return true
    }

    /** Материал — ВЫХОД задачи: связь `output_of` читается с обоих концов. */
    private fun связать(область: Area, материал: String, задача: Entity, author: String): String {
        val реестр = links ?: return "связь с задачей не поставлена: реестра связей нет"
        val карточка = store.byCode(область, материал) ?: return ""
        реестр.link(
            "output_of", карточка.id, задача.id, Provenance(Channel.MANUAL, author),
            rationale = "результат исследования ${задача.code} (цикл ${задача.doc.path("iteration").asInt(1)})",
        )
        return ""
    }

    /**
     * Класс факта результата — по ТЕМЕ строки ответа: промпт требует, чтобы
     * темой каждой строки был её класс. Не назвали тему — класс берётся по
     * классу сущности ТЗ, а если и его нет, факт не относится ни к какому
     * классу: выдуманная принадлежность испортила бы остатки следующего цикла.
     */
    private fun классФакта(область: Area, факт: Entity): ResearchClass? {
        val тема = факт.doc.path("topic").asText("")
        val метка = тема.takeIf { it.isNotBlank() }
            ?.let { store.byCode(область, it)?.doc?.path("label")?.asText("") }
            .orEmpty().lowercase()
        ResearchClass.entries.firstOrNull { метка.contains(it.word) }?.let { return it }
        return when (факт.doc.path("entity_class").asText("")) {
            "stakeholder" -> ResearchClass.STAKEHOLDER
            "normative_ref" -> ResearchClass.NORM
            else -> null
        }
    }

    /**
     * Остатки проекта: классы, по которым не принято ничего ни одной задачей.
     * Ими спрашивает точка — к воротам важно не то, с чего начинали, а то, что
     * осталось незакрытым.
     */
    private fun остатки(область: Area): List<ResearchClass> {
        val всего = store.list(область, "research_task")
            .fold(AcceptedSummary()) { сумма, запись ->
                val принято = итог(запись.doc)
                ResearchClass.entries.fold(сумма) { с, класс -> с.plus(класс, принято.of(класс)) }
            }
        return всего.open()
    }

    private fun вид(область: Area, запись: Entity, факты: List<Entity>): ResearchTask {
        val материал = запись.doc.path("result_material").asText("").ifBlank { null }
        // «Разобрано» ВЫЧИСЛЯЕТСЯ: разбор результата — путь загрузки, а не
        // исследования, и отдельного нажатия «я разобрал» у задачи нет. Есть
        // факты её материала — значит разобрано.
        val разобран = материал != null && факты.any { it.doc.path("material").asText("") == материал }
        return ResearchTask(
            id = запись.code,
            trigger = место(запись.doc),
            questions = запись.doc.path("questions").map { it.asText() },
            sliceFingerprint = запись.doc.path("slice_fingerprint").asText(""),
            promptVersion = запись.doc.path("prompt_version").asText(""),
            iteration = запись.doc.path("iteration").asInt(1),
            status = if (разобран) РАЗОБРАНО else запись.status,
            resultMaterial = материал,
            accepted = итог(запись.doc),
            note = запись.doc.path("notes").asText(""),
        )
    }

    private fun место(документ: JsonNode): ResearchTrigger {
        val узел = документ.path("trigger_scene")
        val сцена = узел.path("scene").asText("").trim()
        val точка = узел.path("gate").asText("").trim()
        return if (сцена.isNotBlank()) ResearchTrigger.Scene(сцена) else ResearchTrigger.Gate(точка)
    }

    /**
     * Место входа: сцена из истины схем либо точка. Сцена вне перечня — отказ:
     * «исследовать полноту» осмысленно там, где полнота и проверяется.
     */
    private fun место(trigger: ResearchTrigger): ResearchTrigger {
        when (trigger) {
            is ResearchTrigger.Scene -> require(trigger.scene.trim() in ResearchQuestions.сцены) {
                "из сцены «${trigger.scene}» исследование полноты не запускается: " +
                    "сцены ${ResearchQuestions.сцены.joinToString(" · ")} и точки"
            }
            is ResearchTrigger.Gate -> require(trigger.gate.isNotBlank()) {
                "точка не названа: исследование заводится от места входа"
            }
        }
        return trigger
    }

    private fun кодМеста(trigger: ResearchTrigger): String = when (trigger) {
        is ResearchTrigger.Scene -> trigger.scene
        is ResearchTrigger.Gate -> trigger.gate
    }

    private fun задачиМеста(область: Area, trigger: ResearchTrigger): List<Entity> =
        store.list(область, "research_task").filter { место(it.doc) == trigger }

    private fun запись(область: Area, код: String): Entity =
        store.byCode(область, код)?.takeIf { it.kind == "research_task" }
            ?: throw IllegalArgumentException("исследования «$код» в проекте нет")

    private fun факты(область: Area): List<Entity> = store.list(область, "fact")

    private fun итог(документ: JsonNode): AcceptedSummary {
        val узел = документ.path("accepted_summary")
        return AcceptedSummary(
            stakeholders = узел.path("stakeholders").asInt(0),
            programs = узел.path("programs").asInt(0),
            norms = узел.path("norms").asInt(0),
            applications = узел.path("applications").asInt(0),
            analogs = узел.path("analogs").asInt(0),
        )
    }

    private fun сводка(документ: ObjectNode, итог: AcceptedSummary) {
        документ.putObject("accepted_summary")
            .put("stakeholders", итог.stakeholders)
            .put("programs", итог.programs)
            .put("norms", итог.norms)
            .put("applications", итог.applications)
            .put("analogs", итог.analogs)
    }

    /** Журнал задачи строкой: что с ней сделали и когда. Поля под это истина схем не заводит. */
    private fun приписка(документ: JsonNode, строка: String, хвост: String): ObjectNode {
        val копия = документ.deepCopy<JsonNode>() as ObjectNode
        val было = копия.path("notes").asText("")
        val полная = строка + (if (хвост.isBlank()) "" else "; ${хвост.trim()}")
        копия.put("notes", if (было.isBlank()) полная else "$было\n$полная")
        return копия
    }

    private fun следующий(область: Area, вид: String, префикс: String): String {
        val занято = store.list(область, вид).mapNotNull {
            Regex("^$префикс-(\\d+)$").find(it.code)?.groupValues?.get(1)?.toIntOrNull()
        }
        return "%s-%04d".format(префикс, (занято.maxOrNull() ?: 0) + 1)
    }

    /** Статус словами: отказ звучит теми же словами, что и строка состояния на экране. */
    private fun слово(статус: String): String = СЛОВА[статус] ?: статус

    private companion object {

        const val ФОРМУЛИРОВАНО = "formulated"
        const val ЗАПУЩЕНО = "launched"
        const val ПОЛУЧЕНО = "received"
        const val РАЗОБРАНО = "parsed"

        /** Свидетельство подтверждено источником (истина схем `fact.evidence`). */
        const val ПОДТВЕРЖДЕНО = "corroborated"

        val СЛОВА = mapOf(
            ФОРМУЛИРОВАНО to "сформулировано",
            ЗАПУЩЕНО to "запущено во внешнем контуре",
            ПОЛУЧЕНО to "результат получен",
            РАЗОБРАНО to "разобрано",
        )
    }
}
