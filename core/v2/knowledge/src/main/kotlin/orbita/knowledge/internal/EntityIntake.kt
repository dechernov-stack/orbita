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
import orbita.kernel.api.LinkRegistry
import orbita.kernel.api.Provenance
import orbita.knowledge.api.Fact
import orbita.knowledge.api.Intake
import orbita.knowledge.api.IntakeTask
import orbita.knowledge.api.KnowledgeCoverage
import orbita.knowledge.api.PlannedAction
import orbita.knowledge.api.SceneSuggestions
import orbita.knowledge.api.CanonBlock
import orbita.knowledge.api.Disposition
import orbita.knowledge.api.FactIntake
import orbita.knowledge.api.SourceMark
import orbita.knowledge.api.Topic

class EntityIntake(
    private val store: EntityStore,
    /** Реестр связей: `derived_from_fact` — нить от сущности к её факту. */
    private val links: LinkRegistry? = null,
    private val mapper: ObjectMapper = ObjectMapper(),
    /**
     * Полка: по ней принятый норматив узнаёт СВОЮ карточку.
     *
     * Правило «один акт — одна карточка» живёт в полке: она знает, что
     * «№2216» и «№ 2216» — один и тот же ПП РФ. Здесь оно только
     * применяется — записью со своим провенансом (канал службы, автор,
     * якорь факта), которого у поставки нет.
     */
    private val shelves: orbita.library.api.Shelves? = null,
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

        // Задание загрузки — ОДНО на пару «материал + намерение»: повторная
        // загрузка того же материала с тем же заданием пересчитывает план по
        // свежим фактам, а не падает дублем кода. Другое намерение по тому же
        // материалу — новое задание со свободным номером (код выводился из
        // кода материала, и второй заход по SD-0002 упирался в IT-0002).
        val документЗадания = mapper.createObjectNode()
            .put("material", material)
            .put("intent", intent)
            .put("facts", факты.size)
            .put("note", примечание)
        val прежнее = store.list(область, "intake_task").firstOrNull {
            it.doc.path("material").asText() == material && it.doc.path("intent").asText() == intent
        }
        val задание = if (прежнее != null) {
            store.update(прежнее.id, документЗадания, Provenance(Channel.MANUAL, author, source = material))
        } else {
            store.create(
                свободныйКод(область), "intake_task", область, "2",
                документЗадания, Provenance(Channel.MANUAL, author, source = material),
            )
        }

        return IntakeTask(задание.code, material, intent, факты, действия, примечание)
    }

    /**
     * Свободный номер вида: MAX + 1, а не «размер списка + 1».
     *
     * Размер врёт после любой отмены или чистки — номер повторяется, и
     * запись падает на уникальности кода. Одна ошибка этого рода уже
     * поймана владельцем на повторной загрузке материала.
     */
    private fun следующий(область: Area, вид: String, префикс: String): String {
        val занято = store.list(область, вид).mapNotNull {
            Regex("^$префикс-(\\d+)$").find(it.code)?.groupValues?.get(1)?.toIntOrNull()
        }
        return "$префикс-%04d".format((занято.maxOrNull() ?: 0) + 1)
    }

    /** Свободный номер задания: коды не переиспользуются и не сталкиваются. */
    private fun свободныйКод(область: Area): String = следующий(область, "intake_task", "IT")

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

    override fun accept(project: String, task: String, chosen: List<Int>, author: String): Intake.Accepted {
        val область = Area.Project(project)
        val задание = store.byCode(область, task) ?: error("задания «$task» нет")
        val действия = задание.doc.path("actions")
        require(!действия.isEmpty()) {
            "у задания «$task» нет плана: принимать нечего. Сначала разбор материала"
        }
        val созданные = mutableListOf<String>()
        val заметки = mutableListOf<String>()
        val принятыеФакты = mutableSetOf<String>()

        действия.forEachIndexed { i, действие ->
            if (i !in chosen) return@forEachIndexed
            val вид = действие.path("target_kind").asText("")
            if (вид.isBlank()) return@forEachIndexed
            val содержимое = действие.path("payload").deepCopy<JsonNode>() as
                com.fasterxml.jackson.databind.node.ObjectNode
            // Имя соседа снимается с документа до записи: его место — связь.
            val ссылка = ссылкиПлана[вид]
            val названныйСосед = ссылка?.let { с ->
                содержимое.path(с.поле).asText("").trim().ifBlank { null }
                    .also { содержимое.remove(с.поле) }
            }
            val якорь = действие.path("facts").firstOrNull()?.asText()
                ?.let { код -> store.byCode(область, код)?.doc?.path("anchor")?.asText() }
            // Замысел проекта один: повторное принятие обновляет его, а не
            // плодит вторую версию замысла рядом с первой.
            // Норматив — общий для всех проектов: он живёт на ПОЛКЕ, а не в
            // проекте. Один и тот же ПП РФ не заводится в каждом проекте
            // заново; проект ссылается на него основанием ограничения.
            val куда = if (вид == "normative_document") Area.Library else область
            val прежний = when {
                вид == "intent" -> store.list(область, вид).firstOrNull()
                вид == "normative_document" ->
                    shelves?.matching(вид, содержимое)?.let { store.byCode(Area.Library, it.code) }
                        ?: store.list(Area.Library, вид).firstOrNull { н ->
                            н.doc.path("designation").asText() == содержимое.path("designation").asText()
                        }
                else -> null
            }
            val сущность = if (прежний != null) {
                store.update(прежний.id, содержимое, Provenance(
                    Channel.SERVICE, author, source = задание.doc.path("material").asText(), anchor = якорь,
                ), status = "accepted")
            } else {
                store.create(
                    следующий(куда, вид, префикс(вид)), вид, куда,
                    // Сцена рождения — только у проектных сущностей: у полки
                    // сцены нет, и «polka» сценой не является.
                    действие.path("scene").asText("").takeIf { it.matches(Regex("[0-9]+")) },
                    содержимое,
                    Provenance(
                        Channel.SERVICE, author,
                        source = задание.doc.path("material").asText(), anchor = якорь,
                    ),
                    status = if (вид == "intent") "accepted" else "draft",
                )
            }
            // Названный сосед — связью. Не нашёлся — система говорит об
            // этом при приёме, а не оставляет нужду висеть до ворот.
            if (ссылка != null && названныйСосед != null) {
                val сосед = поИмени(область, ссылка.вид, названныйСосед)
                if (сосед == null) {
                    заметки += "${сущность.code}: носитель «$названныйСосед» не найден " +
                        "среди сторон проекта — назначьте его в сцене 3"
                } else {
                    links?.link(
                        ссылка.связь, сосед.id, сущность.id,
                        Provenance(Channel.SERVICE, author),
                        rationale = "носитель назван разбором: «$названныйСосед»",
                    )
                }
            }
            // Нить от сущности к её факту: по ней считается доля знаний, и
            // по ней же видно, откуда в проекте взялось это утверждение.
            действие.path("facts").forEach { к ->
                val факт = store.byCode(область, к.asText()) ?: return@forEach
                links?.link(
                    "derived_from_fact", сущность.id, факт.id,
                    Provenance(Channel.SERVICE, author),
                    rationale = "принято планом загрузки: " + действие.path("title").asText(""),
                )
                принятыеФакты += к.asText()
            }
            созданные += сущность.code
        }

        // Диспозиции: принятое — adopted, рассмотренное и не взятое — noted.
        // Факт не исчезает от того, что его не взяли, — это тоже решение.
        val всеФакты = действия.flatMap { it.path("facts").map { к -> к.asText() } }.toSet()
        всеФакты.forEach { код ->
            val решение = if (код in принятыеФакты) Disposition.ADOPTED else Disposition.NOTED
            val причина = if (код in принятыеФакты) "принят планом загрузки «$task»"
            else "рассмотрен планом «$task» и не взят"
            runCatching { dispose(project, код, решение, причина, author) }
        }

        store.update(
            задание.id,
            (задание.doc.deepCopy<JsonNode>() as com.fasterxml.jackson.databind.node.ObjectNode)
                .put("accepted", chosen.joinToString(","))
                .put("created", созданные.size),
            Provenance(Channel.MANUAL, author),
            status = "accepted",
        )
        return Intake.Accepted(созданные, заметки)
    }

    /**
     * Поле плана, которое называет ДРУГУЮ сущность.
     *
     * @param поле имя поля в `payload` разбора
     * @param вид вид сущности, среди которых имя ищется
     * @param связь тип связи; она идёт ОТ найденной сущности К заведённой
     *   («сторона владеет нуждой»), как её ставит ручной ввод сцены 3
     */
    private data class СсылкаПлана(val поле: String, val вид: String, val связь: String)

    /**
     * Ссылки плана: имя соседа — это связь, а не строка в документе.
     *
     * Разбор называет носителя нужды по-человечески («Минтранс России»),
     * а система держит его связью `owns`: нужда без связи повисает и на
     * выходе сцены 3, и в матрице покрытия, сколько бы имён ни лежало у
     * неё в документе. Ручной ввод сцены 3 поступает так же — поле
     * `owner` там тоже уходит в связь, а не в документ.
     */
    private val ссылкиПлана = mapOf("need" to СсылкаПлана("owner", "stakeholder", "owns"))

    /** Сущность вида, названная именем или кодом; null — такой нет. */
    private fun поИмени(область: Area, вид: String, имя: String) =
        store.list(область, вид).firstOrNull {
            it.doc.path("name").asText("").trim().equals(имя, ignoreCase = true)
        } ?: store.byCode(область, имя)

    /** Префикс кода по виду: человеку он говорит, что перед ним. */
    private fun префикс(вид: String): String = when (вид) {
        "stakeholder" -> "SK"
        "need" -> "ND"
        "goal" -> "MG"
        "constraint" -> "Р"
        "service" -> "SV"
        "normative_document" -> "NR"
        "intent" -> "IN"
        else -> вид.take(2).uppercase()
    }

    override fun task(project: String, task: String): IntakeTask {
        val область = Area.Project(project)
        val задание = store.byCode(область, task) ?: error("задания «$task» нет")
        val материал = задание.doc.path("material").asText("")
        val действия = задание.doc.path("actions").map { д ->
            PlannedAction(
                kind = д.path("kind").asText("create_entity"),
                title = д.path("title").asText(""),
                effect = д.path("preview").asText(""),
                factIds = д.path("facts").map { it.asText() },
                targetKind = д.path("target_kind").asText(""),
                scene = д.path("scene").asText(""),
                payload = д.path("payload").properties().associate { (к, в) -> к to в.asText() },
            )
        }
        return IntakeTask(
            задание.code, материал, задание.doc.path("intent").asText(""),
            facts(project).filter { it.material == материал },
            действия, задание.doc.path("note").asText(""),
        )
    }

    override fun suggestions(project: String, scene: String): SceneSuggestions {
        val область = Area.Project(project)
        val задание = store.list(область, "intake_task")
            .filter { it.doc.path("actions").size() > 0 }
            .maxByOrNull { it.updatedAt }
            ?: return SceneSuggestions(scene, null, emptyList(), emptyList(), "")
        val принятые = задание.doc.path("accepted").asText("")
            .split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
        val действия = mutableListOf<PlannedAction>()
        val номера = mutableListOf<Int>()
        задание.doc.path("actions").forEachIndexed { i, д ->
            if (i in принятые || д.path("scene").asText() != scene) return@forEachIndexed
            действия += PlannedAction(
                kind = д.path("kind").asText("create_entity"),
                title = д.path("title").asText(""),
                effect = д.path("preview").asText(""),
                factIds = д.path("facts").map { it.asText() },
                targetKind = д.path("target_kind").asText(""),
                scene = scene,
                payload = д.path("payload").properties().associate { (к, в) -> к to в.asText() },
            )
            номера += i
        }
        val материал = store.byCode(область, задание.doc.path("material").asText())
        val откуда = материал?.doc?.path("name")?.asText("") ?: "материала"
        val поВидам = действия.groupingBy { it.targetKind }.eachCount()
        val словами = поВидам.entries.joinToString(" и ") { (вид, сколько) ->
            "$сколько ${названиеВида(вид, сколько)}"
        }
        return SceneSuggestions(
            scene, задание.code, действия, номера,
            if (действия.isEmpty()) "" else "из «$откуда»: ещё $словами с якорями",
        )
    }

    /** Вид по-русски и в числе: «5 сторон миссии», «3 потребности». */
    private fun названиеВида(вид: String, сколько: Int): String = when (вид) {
        "stakeholder" -> if (сколько == 1) "сторона миссии" else "сторон миссии"
        "need" -> if (сколько == 1) "потребность" else "потребности"
        "goal" -> if (сколько == 1) "цель" else "цели"
        "constraint" -> if (сколько == 1) "ограничение" else "ограничения"
        "service" -> if (сколько == 1) "сервис" else "сервиса"
        "intent" -> "замысел"
        "normative_document" -> if (сколько == 1) "норматив" else "норматива"
        else -> вид
    }

    override fun coverage(project: String): KnowledgeCoverage {
        val область = Area.Project(project)
        // Считаются сущности ПОСТАНОВКИ: служебное (проект, точки, задания,
        // сами факты и темы) знанием не выводится и долю не портит.
        val виды = listOf("intent", "stakeholder", "need", "goal", "constraint", "service")
        var всего = 0
        var изИсточников = 0
        var изРучных = 0
        val поВидам = mutableMapOf<String, Pair<Int, Int>>()
        // Ручной факт — полноправный, но доля знаний ИЗ ИСТОЧНИКОВ
        // считается без него: иначе «100 % из знаний» можно набрать,
        // заведя факты руками под каждую сущность.
        val ручные = store.list(область, "fact")
            .filter { it.doc.path("manual").asBoolean(false) }.map { it.id }.toSet()
        виды.forEach { вид ->
            val сущности = store.list(область, вид).filter { it.status != "cancelled" }
            var изИст = 0
            var изРук = 0
            сущности.forEach { с ->
                val нити = links?.from(с.id, "derived_from_fact").orEmpty()
                when {
                    нити.isEmpty() -> Unit
                    нити.all { it.to in ручные } -> изРук += 1
                    else -> изИст += 1
                }
            }
            всего += сущности.size
            изИсточников += изИст
            изРучных += изРук
            if (сущности.isNotEmpty()) поВидам[вид] = сущности.size to изИст
        }
        return KnowledgeCoverage(
            total = всего,
            fromFacts = изИсточников,
            fromManualFacts = изРучных,
            manual = всего - изИсточников - изРучных,
            share = if (всего == 0) 0.0 else изИсточников.toDouble() / всего,
            byKind = поВидам,
        )
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
            .associateBy { it.doc.path("anchor").asText() + "|" + it.doc.path("predicate").asText() }
        var повторов = 0
        // Номер факта в ответе → его код в проекте. План ссылается на факты
        // НОМЕРАМИ, и при повторном приёме (факты уже есть) карта обязана
        // указывать на существующие: иначе план схлопывается в одно
        // действие — поймано на повторе разбора записки.
        val поНомеру = mutableMapOf<Int, String>()

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
                якорь + "|" + предикат in уже -> {
                    повторов += 1
                    уже[якорь + "|" + предикат]?.let { поНомеру[i] = it.code }
                }
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
                    val код = следующий(область, "fact", "F")
                    val сущность = store.create(
                        код, "fact", область, ф.path("scene").asText("").ifBlank { null },
                        документ, Provenance(Channel.SERVICE, author, source = material, anchor = якорь),
                    )
                    поНомеру[i] = сущность.code
                    принятые += факт(сущность.code, документ)
                }
            }
        }
        // Д2в: план приходит тем же ответом, что и факты, — один живой
        // вызов на версию документа. Действие несёт СОДЕРЖИМОЕ будущей
        // сущности: предпросмотр показывает, а не обещает.
        val действия = mapper.createArrayNode()
        корень.path("actions").forEach { д ->
            val коды = д.path("facts").mapNotNull { н -> поНомеру[н.asInt(-1)] }
            if (коды.isEmpty() && д.path("target_kind").asText() != "intent") return@forEach
            val узел = действия.addObject()
            узел.put("kind", д.path("kind").asText("create_entity"))
            узел.put("target_kind", д.path("target_kind").asText(""))
            узел.put("scene", д.path("scene").asText(""))
            узел.put("title", д.path("title").asText(""))
            узел.put("preview", д.path("preview").asText(""))
            узел.set<com.fasterxml.jackson.databind.node.ObjectNode>("payload", д.path("payload").deepCopy())
            узел.putArray("facts").also { а -> коды.forEach { к -> а.add(к) } }
        }
        if (!действия.isEmpty) {
            val планЗадание = mapper.createObjectNode()
            планЗадание.put("material", material)
            планЗадание.put("intent", "разбери по сущностям")
            планЗадание.put("facts", принятые.size)
            планЗадание.set<com.fasterxml.jackson.databind.node.ObjectNode>("actions", действия)
            планЗадание.put("note", "план собран разбором: действий ${действия.size()}")
            val прежний = store.list(область, "intake_task").firstOrNull {
                it.doc.path("material").asText() == material && it.doc.path("actions").size() > 0
            }
            if (прежний == null) {
                store.create(
                    свободныйКод(область), "intake_task", область, "2",
                    планЗадание, Provenance(Channel.SERVICE, author, source = material),
                )
            } else {
                store.update(прежний.id, планЗадание, Provenance(Channel.SERVICE, author))
            }
        }

        val примечание = "разбор материала «${карточка.doc.path("name").asText(material)}»: " +
            "принято фактов ${принятые.size}, тем ${темы.size}" +
            (if (отказы.isEmpty()) "" else ", отклонено ${отказы.size} (правила честности §6.1)") +
            (if (повторов == 0) "" else ", уже было $повторов") +
            (if (действия.isEmpty) "" else ", действий плана ${действия.size()}")
        val задание = if (действия.isEmpty) null else store.list(область, "intake_task")
            .firstOrNull { it.doc.path("material").asText() == material && it.doc.path("actions").size() > 0 }
            ?.code
        return FactIntake(принятые, отказы, topics(project), примечание, задание)
    }

    private fun темаКод(область: Area, метка: String): String {
        val уже = store.list(область, "topic").firstOrNull { it.doc.path("label").asText() == метка }
        if (уже != null) return уже.code
        val код = следующий(область, "topic", "TP")
        store.create(
            код, "topic", область, null,
            mapper.createObjectNode().put("label", метка),
            Provenance(Channel.SERVICE, "разбор"),
        )
        return код
    }

    /**
     * Нужен ли повод к решению.
     *
     * Замечание прохода 08.09: обоснование требовалось на КАЖДУЮ
     * диспозицию, включая согласие. Поле на десяток символов, фразу
     * придумать нечего — и человек пишет «ок», чтобы кнопка нажалась.
     * Обоснование не должно быть налогом на согласие.
     *
     * Повод обязателен там, где он несёт смысл:
     *   · `rejected` — почему не берём;
     *   · разрешение `contested` — какой факт победил и почему;
     *   · смена УЖЕ ПРИНЯТОГО решения — что изменилось.
     * `adopted` и `noted` с чистого листа идут одним кликом.
     */
    private fun нуженПовод(было: Disposition, стало: Disposition): Boolean = when {
        стало == Disposition.REJECTED -> true
        было == Disposition.CONTESTED -> true
        было != Disposition.FREE && было != стало -> true
        else -> false
    }

    private fun поводЗачем(было: Disposition, стало: Disposition): String = when {
        стало == Disposition.REJECTED ->
            "отклонение без причины не ставится: через год «нет» без объяснения читается как забывчивость"
        было == Disposition.CONTESTED ->
            "разрешение спора без причины не ставится: назовите, какой факт победил и почему"
        else ->
            "смена принятого решения без причины не ставится: что изменилось с прошлого раза"
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
        val прежняя = Disposition.of(сущность.doc.path("disposition").asText("free"))
        require(!нуженПовод(прежняя, disposition) || reason.isNotBlank()) {
            поводЗачем(прежняя, disposition)
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
        manual = документ.path("manual").asBoolean(false),
    )

    // Один построитель на оба пути: две копии однажды разошлись бы на поле.
    override fun facts(project: String): List<Fact> =
        store.list(Area.Project(project), "fact").map { факт(it.code, it.doc) }

    override fun addTopic(project: String, label: String, author: String): Topic {
        val область = Area.Project(project)
        val метка = label.trim()
        require(метка.isNotBlank()) { "у темы нет названия" }
        val уже = store.list(область, "topic").firstOrNull { it.doc.path("label").asText() == метка }
        val сущность = уже ?: store.create(
            следующий(область, "topic", "TP"), "topic", область, null,
            mapper.createObjectNode().put("label", метка).put("manual", true),
            Provenance(Channel.MANUAL, author, source = "инженер"),
        )
        return topics(project).first { it.id == сущность.code }
    }

    override fun addFact(
        project: String,
        subject: String,
        predicate: String,
        value: String,
        unit: String?,
        kind: String,
        topic: String?,
        material: String?,
        author: String,
    ): Fact {
        val область = Area.Project(project)
        require(predicate.isNotBlank()) { "факт без утверждения — не факт" }
        require(value.isNotBlank()) { "факт без значения — не факт" }
        // Правило честности то же, что у разбора: величина без единицы —
        // не факт, кто бы её ни заводил.
        require(kind != "quantity" || !unit.isNullOrBlank()) {
            "величина без единицы — не факт: назовите единицу"
        }
        material?.takeIf { it.isNotBlank() }?.let {
            require(store.byCode(область, it) != null) { "материала «$it» нет в проекте" }
        }
        val документ = mapper.createObjectNode()
        документ.put("kind", kind.ifBlank { "framing" })
        документ.put("subject", subject.trim())
        документ.put("predicate", predicate.trim())
        документ.put("value", value.trim())
        unit?.takeIf { it.isNotBlank() }?.let { документ.put("unit", it.trim()) }
        // Источник — материал, если назван; иначе «инженер, дата»: у руки
        // документа нет, а происхождение обязано быть у каждого факта.
        val источник = material?.takeIf { it.isNotBlank() }
            ?: "инженер, ${java.time.LocalDate.now()}"
        документ.put("material", источник)
        документ.put("mark", "И")
        документ.put("confidence", 1.0)
        документ.put("disposition", "free")
        документ.put("manual", true)
        topic?.takeIf { it.isNotBlank() }?.let { документ.put("topic", темаКод(область, it.trim())) }
        val сущность = store.create(
            следующий(область, "fact", "F"), "fact", область, null, документ,
            Provenance(Channel.MANUAL, author, source = источник),
        )
        return факт(сущность.code, документ)
    }
}
