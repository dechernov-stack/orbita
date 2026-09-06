// Загрузка материала и сборка задания.
//
// Граница честности: факты не выдумываются здесь. Детерминированная часть —
// снимок материала, канон и хранение фактов; атомизация и план приходят
// разбором (пакетом или службой) через `ai`. Пока разбора нет, задание
// говорит об этом прямо, а не показывает пустой список как результат.
package orbita.knowledge.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.EntityStore
import orbita.kernel.api.Provenance
import orbita.knowledge.api.Fact
import orbita.knowledge.api.Intake
import orbita.knowledge.api.IntakeTask
import orbita.knowledge.api.PlannedAction
import orbita.knowledge.api.CanonBlock
import orbita.knowledge.api.Disposition
import orbita.knowledge.api.FactIntake
import orbita.knowledge.api.SourceMark
import orbita.knowledge.api.Topic

class EntityIntake(
    private val store: EntityStore,
    private val mapper: ObjectMapper = ObjectMapper(),
) : Intake {

    /** Каталог заданий (поставка §3): семантика, не свобода. */
    private val каталог = mapOf(
        "разбери по сущностям" to "полный урожай по классам",
        "положи на полку как типовой" to "обобщение в библиотеку",
        "рассмотрим как базовую" to "кандидат базового решения в проекте",
        "сравни с нашим" to "диф фактов против текущих значений",
        "обнови параметры" to "параметрический импорт",
        "это норматив — заведи" to "норматив с редакцией и пунктами",
        "просто в контекст" to "без действий: блоки в промпт, фактов не создаём",
    )

    override fun putMaterial(
        project: String,
        name: String,
        kind: String,
        text: String,
        author: String,
    ): String {
        val область = Area.Project(project)
        val занято = store.list(область, "material").mapNotNull {
            Regex("^SD-(\\d+)$").find(it.code)?.groupValues?.get(1)?.toIntOrNull()
        }
        val код = "SD-%04d".format((занято.maxOrNull() ?: 0) + 1)
        val документ = mapper.createObjectNode()
        документ.put("name", name)
        документ.put("kind", kind)
        документ.put("text", text)
        документ.put("chars", text.length)
        val материал = store.create(
            код, "material", область, "2", документ,
            Provenance(Channel.MANUAL, author, source = name),
        )
        return материал.code
    }

    override fun plan(project: String, material: String, intent: String, author: String): IntakeTask {
        val область = Area.Project(project)
        val карточка = store.byCode(область, material)
            ?: error("материала «$material» нет в проекте")

        val факты = facts(project).filter { it.material == material }
        val намерение = каталог.entries.firstOrNull { intent.contains(it.key.substringBefore(" ‹")) }

        val действия = when {
            факты.isEmpty() -> emptyList()
            намерение == null -> emptyList()
            else -> действияПоНамерению(намерение.key, факты)
        }

        val примечание = when {
            факты.isEmpty() ->
                "материал «${карточка.doc.path("name").asText(material)}» ещё не разобран: " +
                    "фактов нет, а выдумывать их нельзя. Разбор идёт службой либо вставкой пакета."
            намерение == null ->
                "задание «$intent» вне каталога. Ближайшее по смыслу: " +
                    каталог.keys.joinToString("; ") + ". Уточните — гадать система не станет."
            else -> "план собран из ${факты.size} фактов по заданию «${намерение.key}»: ${намерение.value}"
        }

        val задание = store.create(
            "IT-" + карточка.code.removePrefix("SD-"),
            "intake_task", область, "2",
            mapper.createObjectNode()
                .put("material", material)
                .put("intent", intent)
                .put("facts", факты.size)
                .put("note", примечание),
            Provenance(Channel.MANUAL, author, source = material),
        )

        return IntakeTask(задание.code, material, intent, факты, действия, примечание)
    }

    private fun действияПоНамерению(намерение: String, факты: List<Fact>): List<PlannedAction> =
        when (намерение) {
            "положи на полку как типовой" -> listOf(
                PlannedAction(
                    "shelf_put",
                    "положить на полку типовой компонент",
                    "появится запись полки с ${факты.size} параметрами и ссылкой на источник",
                    факты.map { it.id },
                ),
            )
            "рассмотрим как базовую" -> listOf(
                PlannedAction(
                    "candidate_node",
                    "завести узел состава кандидатом",
                    "появится узел с параметрами из фактов и решением «кандидат»",
                    факты.map { it.id },
                ),
                PlannedAction(
                    "check_constraints",
                    "сверить с ограничениями проекта",
                    "покажет расхождения с Р-кодами до того, как кандидат станет базовым",
                    факты.map { it.id },
                ),
            )
            "это норматив — заведи" -> listOf(
                PlannedAction(
                    "normative",
                    "завести норматив с редакцией",
                    "появится норматив; пункты станут основаниями требований",
                    факты.map { it.id },
                ),
            )
            "просто в контекст" -> emptyList()
            else -> listOf(
                PlannedAction(
                    "harvest",
                    "разложить факты по разделам постановки",
                    "кандидаты появятся в своих разделах и будут приняты по одному",
                    факты.map { it.id },
                ),
            )
        }

    override fun accept(project: String, task: String, chosen: List<Int>, author: String): List<String> {
        val область = Area.Project(project)
        val задание = store.byCode(область, task) ?: error("задания «$task» нет")
        // Выполнение действий штатными каналами — волна 2 продолжается; пока
        // фиксируем решение человека, чтобы оно не потерялось.
        store.update(
            задание.id,
            (задание.doc.deepCopy<JsonNode>() as com.fasterxml.jackson.databind.node.ObjectNode)
                .put("accepted", chosen.joinToString(",")),
            Provenance(Channel.MANUAL, author),
            status = "accepted",
        )
        return chosen.map { "действие $it принято" }
    }

    override fun canon(project: String, material: String): List<CanonBlock> {
        val карточка = store.byCode(Area.Project(project), material)
            ?: error("материала «$material» нет в проекте")
        return Canon.of(карточка.doc.path("text").asText("")).blocks
            .map { CanonBlock(it.anchor, it.kind, it.text) }
    }

    override fun fingerprint(project: String, material: String): String {
        val карточка = store.byCode(Area.Project(project), material)
            ?: error("материала «$material» нет в проекте")
        return Canon.of(карточка.doc.path("text").asText("")).fingerprint
    }

    /**
     * Приём фактов разбора. Ворота — правила честности §6.1: факт без якоря
     * не существует, величина без единицы — не факт. Отклонённое называется
     * поимённо: разбор чинится, а не подчищается молча.
     */
    override fun putFacts(project: String, material: String, raw: String, author: String): FactIntake {
        val область = Area.Project(project)
        val карточка = store.byCode(область, material)
            ?: error("материала «$material» нет в проекте")
        val якоря = Canon.of(карточка.doc.path("text").asText("")).blocks.map { it.anchor }.toSet()
        val корень = mapper.readTree(
            raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim(),
        )
        val принятые = mutableListOf<Fact>()
        val отказы = mutableListOf<String>()
        val темы = mutableMapOf<String, String>()
        // Повторный приём того же разбора фактов НЕ удваивает: факт узнаётся
        // по своему месту в документе — материал, якорь и утверждение
        // (поймано живым прогоном: кэш ответа не спасал от дублей).
        val уже = store.list(область, "fact")
            .filter { it.doc.path("material").asText() == material }
            .map { it.doc.path("anchor").asText() + "|" + it.doc.path("predicate").asText() }
            .toSet()
        var повторов = 0

        корень.path("topics").forEach { т ->
            val метка = т.path("label").asText("").ifBlank { return@forEach }
            val код = темаКод(область, метка)
            темы[метка] = код
        }

        корень.path("facts").forEachIndexed { i, ф ->
            val якорь = ф.path("source").path("anchor").asText("").ifBlank { ф.path("anchor").asText("") }
            val предикат = ф.path("predicate").asText("")
            val величина = ф.path("value")
            val единица = ф.path("unit").asText("").ifBlank { величина.path("unit").asText("") }
            val текстЗначения = if (величина.isObject) величина.path("value").asText("") else величина.asText("")
            when {
                якорь.isBlank() -> отказы += "факт $i «$предикат»: без якоря — факта не существует"
                якорь !in якоря -> отказы += "факт $i «$предикат»: якорь «$якорь» не найден в каноне"
                предикат.isBlank() -> отказы += "факт $i: без утверждения"
                текстЗначения.isBlank() -> отказы += "факт $i «$предикат»: без значения"
                ф.path("kind").asText("") == "quantity" && единица.isBlank() ->
                    отказы += "факт $i «$предикат»: величина без единицы — не факт"
                якорь + "|" + предикат in уже -> повторов += 1
                else -> {
                    val метка = ф.path("topic").asText("")
                    val документ = mapper.createObjectNode()
                    документ.put("kind", ф.path("kind").asText("framing"))
                    документ.put("subject", ф.path("subject").asText(метка))
                    документ.put("predicate", предикат)
                    документ.put("value", текстЗначения)
                    if (единица.isNotBlank()) документ.put("unit", единица)
                    документ.put("anchor", якорь)
                    документ.put("material", material)
                    документ.put("mark", ф.path("source_mark").asText("И"))
                    документ.put("confidence", ф.path("confidence").asDouble(0.5))
                    документ.put("disposition", "free")
                    if (метка.isNotBlank()) документ.put("topic", темы[метка] ?: темаКод(область, метка))
                    val код = "F-%04d".format(store.list(область, "fact").size + 1)
                    val сущность = store.create(
                        код, "fact", область, ф.path("scene").asText("").ifBlank { null },
                        документ, Provenance(Channel.SERVICE, author, source = material, anchor = якорь),
                    )
                    принятые += факт(сущность.code, документ)
                }
            }
        }
        val примечание = "разбор материала «${карточка.doc.path("name").asText(material)}»: " +
            "принято фактов ${принятые.size}, тем ${темы.size}" +
            (if (отказы.isEmpty()) "" else ", отклонено ${отказы.size} (правила честности §6.1)") +
            (if (повторов == 0) "" else ", уже было $повторов")
        return FactIntake(принятые, отказы, topics(project), примечание)
    }

    private fun темаКод(область: Area, метка: String): String {
        val уже = store.list(область, "topic").firstOrNull { it.doc.path("label").asText() == метка }
        if (уже != null) return уже.code
        val код = "TP-%04d".format(store.list(область, "topic").size + 1)
        store.create(
            код, "topic", область, null,
            mapper.createObjectNode().put("label", метка),
            Provenance(Channel.SERVICE, "разбор"),
        )
        return код
    }

    override fun dispose(
        project: String,
        fact: String,
        disposition: Disposition,
        reason: String,
        author: String,
    ): Fact {
        val область = Area.Project(project)
        val сущность = store.byCode(область, fact) ?: error("факта «$fact» нет в проекте")
        require(reason.isNotBlank()) {
            "диспозиция без причины не ставится: решение человека обязано быть объяснено"
        }
        val документ = сущность.doc.deepCopy<JsonNode>() as com.fasterxml.jackson.databind.node.ObjectNode
        документ.put("disposition", disposition.name.lowercase())
        документ.putObject("disposition_decision")
            .put("by", author).put("at", java.time.OffsetDateTime.now().toString())
            .put("reason", reason)
        val обновлена = store.update(сущность.id, документ, Provenance(Channel.MANUAL, author))
        return факт(обновлена.code, документ)
    }

    override fun topics(project: String): List<Topic> {
        val факты = store.list(Area.Project(project), "fact")
        return store.list(Area.Project(project), "topic").map { т ->
            Topic(
                id = т.code,
                label = т.doc.path("label").asText(""),
                scene = т.doc.path("scene").asText("").ifBlank { null },
                resolvedTo = т.doc.path("resolved_to").asText("").ifBlank { null },
                facts = факты.count { it.doc.path("topic").asText() == т.code },
            )
        }
    }

    private fun факт(код: String, документ: JsonNode): Fact = Fact(
        id = код,
        subject = документ.path("subject").asText(""),
        predicate = документ.path("predicate").asText(""),
        value = документ.path("value").asText(""),
        unit = документ.path("unit").asText("").ifBlank { null },
        anchor = документ.path("anchor").asText("").ifBlank { null },
        mark = runCatching { SourceMark.valueOf(документ.path("mark").asText("И")) }
            .getOrDefault(SourceMark.И),
        confidence = документ.path("confidence").takeIf { it.isNumber }?.asDouble(),
        material = документ.path("material").asText(""),
        kind = документ.path("kind").asText("framing"),
        disposition = runCatching {
            Disposition.valueOf(документ.path("disposition").asText("free").uppercase())
        }.getOrDefault(Disposition.FREE),
        topic = документ.path("topic").asText("").ifBlank { null },
    )

    override fun facts(project: String): List<Fact> =
        store.list(Area.Project(project), "fact").map { сущность ->
            Fact(
                id = сущность.code,
                subject = сущность.doc.path("subject").asText(""),
                predicate = сущность.doc.path("predicate").asText(""),
                value = сущность.doc.path("value").asText(""),
                unit = сущность.doc.path("unit").asText("").ifBlank { null },
                anchor = сущность.doc.path("anchor").asText("").ifBlank { null },
                mark = runCatching {
                    SourceMark.valueOf(сущность.doc.path("mark").asText("И"))
                }.getOrDefault(SourceMark.И),
                confidence = сущность.doc.path("confidence").takeIf { it.isNumber }?.asDouble(),
                material = сущность.doc.path("material").asText(""),
                kind = сущность.doc.path("kind").asText("framing"),
                disposition = runCatching {
                    Disposition.valueOf(сущность.doc.path("disposition").asText("free").uppercase())
                }.getOrDefault(Disposition.FREE),
                topic = сущность.doc.path("topic").asText("").ifBlank { null },
            )
        }
}
