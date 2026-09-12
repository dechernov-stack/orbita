// Загрузка материала и сборка задания.
//
// Граница честности: факты не выдумываются здесь. Детерминированная часть —
// снимок материала, канон и хранение фактов; атомизация и план приходят
// разбором (пакетом или службой) через `ai`. Пока разбора нет, задание
// говорит об этом прямо, а не показывает пустой список как результат.
package orbita.knowledge.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.EntityStore
import orbita.kernel.api.KnowledgeFlag
import orbita.kernel.api.LinkRegistry
import orbita.kernel.api.Provenance
import orbita.knowledge.api.Assumption
import orbita.knowledge.api.Authority
import orbita.knowledge.api.ContentProfile
import orbita.knowledge.api.Fact
import orbita.knowledge.api.FactLink
import orbita.knowledge.api.FactSource
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

    private val режимы = IntakeModes(store, links, mapper)

    // Корпусная часть поля знаний вынесена файлами рядом: этот и так на
    // тысячу строк, а темы и связи фактов — своя забота (ТЗ-BACKEND §2.1).
    private val темыПоля = Topics(store, links)
    private val связиФактов = FactLinks(store, links)

    override fun putMaterial(
        project: String,
        name: String,
        kind: String,
        text: String,
        author: String,
        supersedes: String?,
        authority: String?,
        profile: ContentProfile?,
    ): String {
        val область = Area.Project(project)
        val прежний = supersedes?.takeIf { it.isNotBlank() }?.let { код ->
            store.byCode(область, код)?.takeIf { it.kind == "material" }
                ?: throw IllegalArgumentException("прежней версии «$код» среди материалов проекта нет")
        }
        val занято = store.list(область, "material").mapNotNull {
            Regex("^SD-(\\d+)$").find(it.code)?.groupValues?.get(1)?.toIntOrNull()
        }
        val код = "SD-%04d".format((занято.maxOrNull() ?: 0) + 1)
        val документ = mapper.createObjectNode()
        документ.put("name", name)
        документ.put("kind", kind)
        // Истина схем зовёт тип материала `type`; плоское `kind` остаётся до DTO из YAML.
        документ.put("type", kind)
        документ.put("text", text)
        документ.put("chars", text.length)
        // Ранг доверия — ОТДЕЛЬНОЕ поле: тип входного остаётся режимом разбора
        // и полем ввода доверия не бывает. Записывается он всегда, в том числе
        // на проекте прохода: без ранга у материала фактам нечего наследовать,
        // а прежние поля (kind/type) при этом не трогаются.
        val ранг = рангВхода(project, kind, authority)
        документ.put("authority", ранг.value)
        ранг.note?.let { документ.put("notes", it) }
        // Профиль ставит разбор; при загрузке он известен редко — например,
        // когда материал приходит вместе с готовым разбором.
        profile?.let { документ.set<JsonNode>("profile", профильУзел(it)) }
        прежний?.let { документ.put("supersedes", it.code) }
        val материал = store.create(
            код, "material", область, "2", документ,
            Provenance(Channel.MANUAL, author, source = name),
        )
        // Новая версия входного: факты прежней в изменённых блоках получают
        // «источник обновлён», диспозиции не трогаются (правило поля знаний).
        прежний?.let { режимы.supersede(область, it, материал, author) }
        return материал.code
    }

    /** Ранг входного и помета, если ранг пришлось выводить. */
    private data class Ранг(val value: String, val note: String? = null)

    /**
     * Ранг доверия материала.
     *
     * На проекте поля знаний v2 ранг обязателен и приходит с формы: доверие —
     * решение человека, а не вывод из расширения файла. На проекте прохода
     * спрашивать некого, поэтому ранг выводится по прежнему типу входного тем
     * же правилом, что и миграция стенда. Тип вне перечня истины схем даёт
     * `doubtful` С ПОМЕТОЙ: правдоподобный ранг здесь не выдумывается —
     * выдуманное доверие хуже отсутствующего.
     */
    private fun рангВхода(project: String, kind: String, authority: String?): Ранг {
        authority?.takeIf { it.isNotBlank() }?.let { return Ранг(Authority.of(it)) }
        require(!KnowledgeFlag.on(store, project)) {
            "ранг доверия материала обязателен: ${Authority.words()} — " +
                "доверие не выводится из типа файла, его называет человек"
        }
        Authority.ofMaterialKind(kind)?.let { return Ранг(it) }
        return Ранг(
            Authority.DOUBTFUL,
            "ранг доверия выведен по типу входного «$kind»: такого типа в истине схем нет, " +
                "поставлен ${Authority.word(Authority.DOUBTFUL)} — назовите ранг",
        )
    }

    private fun профильУзел(профиль: ContentProfile): ObjectNode = mapper.createObjectNode()
        .put("statement", профиль.statement)
        .put("params", профиль.params)
        .put("norms", профиль.norms)
        .put("assessments", профиль.assessments)

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
                // Риск и замечание узнаются по формулировке: повторный приём того
                // же плана обновляет запись, а не плодит RI-0003 рядом с RI-0001
                // (поймано повторным прогоном E1).
                вид == "risk" -> store.list(область, вид).firstOrNull { р ->
                    р.doc.path("statement").asText() == содержимое.path("statement").asText()
                }
                вид == "finding" -> store.list(область, вид).firstOrNull { з ->
                    з.doc.path("text").asText() == содержимое.path("text").asText()
                }
                else -> null
            }
            // Адресные виды (шип E): параметр узла и запрос данных ссылаются на
            // узел кодом — здесь код становится идентификатором; замечание
            // (RFA заказчику) рождается в сцене обзора.
            var кодЗаписи: String? = null
            var статусЗаписи = if (вид == "intent") "accepted" else "draft"
            when (вид) {
                "parameter", "data_request" -> {
                    val узел = store.byCode(область, содержимое.path("target").asText(""))
                        ?: run {
                            заметки += "${действие.path("title").asText()}: узла «${содержимое.path("target").asText()}» в проекте нет — примите сначала узел"
                            return@forEachIndexed
                        }
                    содержимое.put("target", узел.id)
                    if (вид == "parameter") {
                        val ключ = содержимое.path("key").asText("")
                        кодЗаписи = "${узел.code}.$ключ"
                        val величина = содержимое.putObject("measure")
                        содержимое.path("value").asText("").replace(",", ".").toDoubleOrNull()?.let { величина.put("value", it) }
                            ?: величина.put("value", содержимое.path("value").asText(""))
                        величина.put("unit", содержимое.path("unit").asText(""))
                        содержимое.putObject("source")
                            .put("material", содержимое.path("material").asText(""))
                            .put("anchor", содержимое.path("anchor").asText(""))
                        содержимое.remove(listOf("value", "unit", "anchor"))
                    } else {
                        кодЗаписи = "DR-" + узел.code
                    }
                }
                "finding" -> статусЗаписи = "open"
                // Узел и требование приходят со своим кодом: узел — из полки,
                // требование — UID из импорта .sdoc (шип F): экспорт → импорт →
                // экспорт совпадает только при сохранённом коде.
                "component", "requirement" -> {
                    содержимое.path("code").asText("").takeIf { it.isNotBlank() }?.let { кодЗаписи = it }
                    содержимое.remove("code")
                }
            }
            // Карточка полки ДОПОЛНЯЕТСЯ, а не переписывается: разбор текста акта
            // не знает даты и редакции, и «ПП РФ от 22.12.2020 № 2216» терял дату,
            // становясь «ПП РФ № 2216» (поймано прогоном E1). Пустое новое поле
            // не стирает заполненное старое; обозначение — то, что полнее.
            if (прежний != null && вид == "normative_document") {
                val старое = прежний.doc
                старое.properties().forEach { (поле, значение) ->
                    val новое = содержимое.path(поле)
                    val пусто = новое.isMissingNode || новое.isNull || (новое.isTextual && новое.asText().isBlank()) ||
                        (новое.isArray && новое.isEmpty)
                    if (пусто) содержимое.set<JsonNode>(поле, значение.deepCopy())
                }
                val прежнееОбозначение = старое.path("designation").asText("")
                if (прежнееОбозначение.length > содержимое.path("designation").asText("").length) {
                    содержимое.put("designation", прежнееОбозначение)
                }
            }
            val сущность = if (прежний != null) {
                store.update(прежний.id, содержимое, Provenance(
                    Channel.SERVICE, author, source = задание.doc.path("material").asText(), anchor = якорь,
                ), status = if (вид in setOf("risk", "finding")) null else "accepted")
            } else if (кодЗаписи != null && store.byCode(куда, кодЗаписи!!) != null) {
                val существующая = store.byCode(куда, кодЗаписи!!)!!
                store.update(существующая.id, содержимое, Provenance(
                    Channel.SERVICE, author, source = задание.doc.path("material").asText(), anchor = якорь,
                ))
            } else {
                store.create(
                    кодЗаписи ?: следующий(куда, вид, префикс(вид)), вид, куда,
                    // Сцена рождения — только у проектных сущностей: у полки
                    // сцены нет, и «polka» сценой не является.
                    действие.path("scene").asText("").takeIf { it.matches(Regex("[0-9]+")) },
                    содержимое,
                    Provenance(
                        Channel.SERVICE, author,
                        source = задание.doc.path("material").asText(), anchor = якорь,
                    ),
                    status = статусЗаписи,
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
                payload = д.path("payload").properties().associate { (к, в) -> к to (if (в.isValueNode) в.asText() else в.toString()) },
            )
        }
        return IntakeTask(
            задание.code, материал, задание.doc.path("intent").asText(""),
            facts(project).filter { it.material == материал },
            действия, задание.doc.path("note").asText(""),
            assessment = режимы.assessmentView(задание.doc.path("assessment")),
        )
    }

    override fun suggestions(project: String, scene: String): SceneSuggestions {
        val область = Area.Project(project)
        // Непроверенное в сцену не предлагается. Результат внешнего
        // исследования приходит материалом ранга «сомнительный», и до того,
        // как человек подтвердил источники, его разбор живёт отдельной
        // группой поля знаний, а не строкой «принять одним нажатием» (мера §6
        // задания). Разбор материалов выше рангом при этом не пропадает:
        // берётся последнее задание, которое предлагать ПОЗВОЛЕНО.
        //
        // Только на проекте поля знаний v2: на проекте прохода ранга у части
        // материалов нет вовсе, и фильтровать по нему значило бы убрать с
        // экрана предложения, которые владелец уже видел.
        val флаг = KnowledgeFlag.on(store, project)
        val задание = store.list(область, "intake_task")
            .filter { it.doc.path("actions").size() > 0 }
            .filter { !флаг || !сомнительный(область, it.doc.path("material").asText("")) }
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

    /** Материал ранга «сомнительный»: свидетельством он ещё не стал. */
    private fun сомнительный(область: Area, материал: String): Boolean =
        материал.isNotBlank() &&
            store.byCode(область, материал)?.doc?.path("authority")?.asText("") == Authority.DOUBTFUL

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
        val факты = store.list(область, "fact")
        // Ручной факт — полноправный, но доля знаний ИЗ ИСТОЧНИКОВ
        // считается без него: иначе «100 % из знаний» можно набрать,
        // заведя факты руками под каждую сущность.
        val ручные = факты.filter { it.doc.path("manual").asBoolean(false) }.map { it.id }.toSet()
        // Ранг основания: сомнительное свидетельством ещё не стало. Результат
        // исследования приходит материалом `doubtful` и до подтверждения
        // источников человеком в долю знаний не идёт — иначе непроверенное
        // само себя и зачло бы (мера §6 задания).
        //
        // Только на проекте поля знаний v2: на проекте прохода ранга у части
        // фактов нет вовсе, и считать по нему значило бы задним числом
        // пересчитать уже показанную владельцу долю. Пустой ранг — факт,
        // заведённый до перестройки; выдуманный ранг хуже отсутствующего.
        val сомнительные = if (!KnowledgeFlag.on(store, project)) emptySet() else факты
            .filter { it.doc.path("authority").asText("") == Authority.DOUBTFUL }
            .map { it.id }.toSet()
        // Ранг основания наружу: доля знаний без рангов не отличает
        // «80 % из обязательных документов» от «80 % из справок» (мера ПМИ-6
        // п. 1.8). Считается ОДНА нить — одно основание; факт без ранга
        // (заведён до перестройки) не приписывается ни одному рангу.
        val рангФакта = факты.associate { it.id to it.doc.path("authority").asText("") }
        val поРангам = mutableMapOf<String, Int>()
        виды.forEach { вид ->
            val сущности = store.list(область, вид).filter { it.status != "cancelled" }
            var изИст = 0
            var изРук = 0
            сущности.forEach { с ->
                val нити = links?.from(с.id, "derived_from_fact").orEmpty()
                нити.forEach { нить ->
                    val ранг = рангФакта[нить.to].orEmpty()
                    if (Authority.known(ранг)) поРангам[ранг] = (поРангам[ранг] ?: 0) + 1
                }
                when {
                    нити.isEmpty() -> Unit
                    // Долю знаний держит хотя бы одно основание из источника,
                    // ранг которого выше сомнительного; всё прочее видно
                    // отдельной строкой, а не в общей доле.
                    нити.all { it.to in ручные || it.to in сомнительные } -> изРук += 1
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
            // Порядок — весом ранга, а не алфавитом: человек читает
            // «обязательный · экспертный · справочный · сомнительный».
            byAuthority = поРангам.toList().sortedBy { (ранг, _) -> Authority.weight(ранг) }.toMap(),
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
    override fun questionnaireKeys(project: String, intent: String): List<Pair<String, String>> =
        режимы.анкетаДляПромпта(Area.Project(project), intent)

    override fun putFacts(project: String, material: String, raw: String, author: String, intent: String): FactIntake {
        val область = Area.Project(project)
        // Карточка перечитывается после записи профиля: режим разбора этого же
        // ответа выбирается ПО ПРОФИЛЮ БЛОКА, и со старым снимком он падал бы
        // обратно на тип входного — профиль вступал бы в силу со следующего раза.
        var карточка = store.byCode(область, material)
            ?: error("материала «$material» нет в проекте")
        val якоря = Canon.of(карточка.doc.path("text").asText("")).blocks.map { it.anchor }.toSet()
        val корень = mapper.readTree(
            raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim(),
        )
        val принятые = mutableListOf<Fact>()
        val отказы = mutableListOf<String>()
        val темы = mutableMapOf<String, String>()
        // Ранг факта НАСЛЕДУЕТСЯ от материала (knowledge_field_rules) — не
        // приходит из ответа модели: доверие назначает человек загрузкой, а не
        // разбор своим мнением. У материала, загруженного до перестройки, поля
        // ещё нет — ранг выводится по типу входного тем же правилом миграции.
        val рангМатериала = карточка.doc.path("authority").asText("").ifBlank {
            Authority.ofMaterialKind(карточка.doc.path("kind").asText("")) ?: Authority.DOUBTFUL
        }
        // Профиль содержимого ставит РАЗБОР: доли блоков приходят тем же
        // ответом, что и факты, и по ним выбирается режим атомизации блока.
        // Брак профиля не отменяет фактов — он называется отказом поимённо.
        if (корень.path("profile").isObject) {
            runCatching { профильИзРазбора(корень.path("profile")) }
                .onSuccess { профиль ->
                    карточка = store.update(
                        карточка.id,
                        (карточка.doc.deepCopy() as ObjectNode).set<JsonNode>("profile", профильУзел(профиль)),
                        Provenance(Channel.SERVICE, author, source = material),
                    )
                }
                .onFailure { отказы += "профиль содержимого не принят: ${it.message}" }
        }
        // Повторный приём того же разбора фактов НЕ удваивает: факт узнаётся
        // по своему месту в источнике — якорь блока (а у экспертного факта
        // учётка автора) и утверждение (поймано живым прогоном: кэш ответа не
        // спасал от дублей).
        val уже = store.list(область, "fact")
            .filter { it.doc.path("material").asText() == material }
            .associateBy { ключФакта(it.doc) }
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
            val источник = ф.path("source")
            val якорь = источник.path("anchor").asText("").ifBlank { ф.path("anchor").asText("") }
            // Вторая ветка союза `fact.source` — эксперт без якоря: учётка ·
            // роль · дата. Схема союз не стережёт (генератор сворачивает его в
            // бестиповый object), поэтому «ровно одно из двух» проверяется
            // здесь. Ослаблять сторож якоря целиком нельзя — только ветвить:
            // документальный факт без якоря по-прежнему не существует.
            val учётка = источник.path("account").asText("")
            val роль = источник.path("role").asText("")
            val когда = источник.path("at").asText("")
            val экспертный = учётка.isNotBlank() || роль.isNotBlank() || когда.isNotBlank()
            val предикат = ф.path("predicate").asText("")
            val величина = ф.path("value")
            val единица = ф.path("unit").asText("").ifBlank { величина.path("unit").asText("") }
            val текстЗначения = if (величина.isObject) величина.path("value").asText("") else величина.asText("")
            val ключ = (if (якорь.isNotBlank()) якорь else "эксперт:$учётка") + "|" + предикат
            when {
                экспертный && якорь.isNotBlank() ->
                    отказы += "факт $i «$предикат»: источник разом документом и экспертом не бывает — " +
                        "либо якорь, либо учётка с ролью и датой"
                экспертный && (учётка.isBlank() || роль.isBlank() || когда.isBlank()) ->
                    отказы += "факт $i «$предикат»: экспертный источник без учётки, роли или даты — происхождения нет"
                !экспертный && якорь.isBlank() -> отказы += "факт $i «$предикат»: без якоря — факта не существует"
                !экспертный && якорь !in якоря -> отказы += "факт $i «$предикат»: якорь «$якорь» не найден в каноне"
                предикат.isBlank() -> отказы += "факт $i: без утверждения"
                текстЗначения.isBlank() -> отказы += "факт $i «$предикат»: без значения"
                ф.path("kind").asText("") == "quantity" && единица.isBlank() ->
                    отказы += "факт $i «$предикат»: величина без единицы — не факт"
                ключ in уже -> {
                    повторов += 1
                    уже[ключ]?.let { поНомеру[i] = it.code }
                }
                else -> {
                    val метка = ф.path("topic").asText("")
                    val документ = mapper.createObjectNode()
                    документ.put("kind", ф.path("kind").asText("framing"))
                    документ.put("subject", ф.path("subject").asText(метка))
                    документ.put("predicate", предикат)
                    документ.put("value", текстЗначения)
                    if (единица.isNotBlank()) документ.put("unit", единица)
                    if (якорь.isNotBlank()) документ.put("anchor", якорь)
                    документ.put("material", material)
                    документ.put("mark", ф.path("source_mark").asText("И"))
                    // Ранг: документальный факт наследует ранг материала,
                    // экспертный — expert (рука эксперта, а не документ).
                    if (экспертный) {
                        поСхеме(документ, FactSource.FromExpert(учётка, роль, когда))
                        документ.put("authority", Authority.EXPERT)
                    } else {
                        поСхеме(документ)
                        документ.put("authority", рангМатериала)
                    }
                    // Даташит: ключ анкеты узла; норматив: порог нормы полем; конфликт с рамкой — кодом.
                    ф.path("param_key").asText("").takeIf { it.isNotBlank() }?.let { документ.put("param_key", it) }
                    // ТЗ: класс сущности факта — сверка разбора с пакетом по классам (ШИП-G-ПРИНЯТ).
                    ф.path("entity_class").asText("").takeIf { it.isNotBlank() }?.let { документ.put("entity_class", it) }
                    if (ф.path("limit").isObject) документ.set<JsonNode>("limit", ф.path("limit").deepCopy())
                    ф.path("conflict").asText("").takeIf { it.isNotBlank() }?.let { документ.put("conflict_constraint", it) }
                    документ.put("confidence", ф.path("confidence").asDouble(0.5))
                    документ.put("disposition", "free")
                    if (метка.isNotBlank()) документ.put("topic", темы[метка] ?: темаКод(область, метка))
                    val код = следующий(область, "fact", "F")
                    val сущность = store.create(
                        код, "fact", область, ф.path("scene").asText("").ifBlank { null },
                        документ,
                        Provenance(
                            Channel.SERVICE, author, source = material,
                            anchor = якорь.ifBlank { null },
                        ),
                    )
                    поНомеру[i] = сущность.code
                    принятые += факт(область, сущность.code, документ)
                }
            }
        }
        // Связи между фактами приходят ТЕМ ЖЕ ответом: разбор говорит не
        // только, какие атомы нашёл, но и чем они друг другу приходятся.
        // Концы названы номерами фактов — теми же, которыми ссылается план, —
        // поэтому карта `поНомеру` берётся готовой: при повторном приёме она
        // указывает на уже заведённые факты, и связь не ставится второй раз.
        val связи = связиФактов.relate(область, корень.path("links"), поНомеру, author)
        отказы += связи.refused

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
        // Конфликт фактов — показать оба, не выбирать: ссылки ставятся по данным.
        режимы.markConflicts(область, author)
        // Режим по типу входного: оценка ТЗ против нужд, параметры даташита в
        // анкету, сверка с рамками, запрос поставщику — считаются по данным.
        val оценка = режимы.assessment(корень, поНомеру, область)
        val фактыСНомерами = поНомеру.mapNotNull { (н, код) -> store.byCode(область, код)?.let { н to it } }
        режимы.typeActions(область, карточка, intent, фактыСНомерами, оценка, действия)
        if (!действия.isEmpty || оценка != null) {
            val планЗадание = mapper.createObjectNode()
            планЗадание.put("material", material)
            планЗадание.put("intent", "разбери по сущностям")
            планЗадание.put("facts", принятые.size)
            планЗадание.set<com.fasterxml.jackson.databind.node.ObjectNode>("actions", действия)
            оценка?.let { планЗадание.set<JsonNode>("assessment", it) }
            планЗадание.put("note", "план собран разбором: действий ${действия.size()}" +
                (оценка?.let { "; оценка ТЗ: непокрытых нужд ${it.path("uncovered_needs").size()}, требований без нужды ${it.path("orphan_requirements").size()}" } ?: ""))
            val прежний = store.list(область, "intake_task").firstOrNull {
                it.doc.path("material").asText() == material && (it.doc.path("actions").size() > 0 || it.doc.path("assessment").isObject)
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
            (if (связи.accepted == 0) "" else ", связей между фактами ${связи.accepted}") +
            (if (действия.isEmpty) "" else ", действий плана ${действия.size()}") +
            (оценка?.let { "; ТЗ оценено против нужд" } ?: "")
        val задание = if (действия.isEmpty && оценка == null) null else store.list(область, "intake_task")
            .firstOrNull { it.doc.path("material").asText() == material && (it.doc.path("actions").size() > 0 || it.doc.path("assessment").isObject) }
            ?.code
        return FactIntake(принятые, отказы, topics(project), примечание, задание)
    }

    /**
     * Место факта в источнике: якорь блока — а у экспертного факта учётка
     * автора, потому что якоря у руки нет. По нему повторный приём того же
     * разбора узнаёт факт и не заводит его вторым.
     */
    private fun ключФакта(документ: JsonNode): String {
        val якорь = документ.path("anchor").asText("")
        val учётка = документ.path("source").path("account").asText("")
        return (if (якорь.isNotBlank()) якорь else "эксперт:$учётка") + "|" +
            документ.path("predicate").asText()
    }

    /** Профиль из ответа разбора; доли проверяет сам вид (брак — отказ, а не тихий ноль). */
    private fun профильИзРазбора(узел: JsonNode): ContentProfile = ContentProfile(
        statement = узел.path("statement").asDouble(0.0),
        params = узел.path("params").asDouble(0.0),
        norms = узел.path("norms").asDouble(0.0),
        assessments = узел.path("assessments").asDouble(0.0),
    )

    private fun темаКод(область: Area, метка: String): String {
        val темы = темыПоля.all(область)
        // Метка слитой темы — второе имя ТОГО ЖЕ предмета: новые факты идут на
        // голову цепочки, а не воскрешают тему, которую человек уже слил.
        val уже = темы.firstOrNull { it.doc.path("label").asText() == метка }
        if (уже != null) return темыПоля.head(темы, уже).code
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
        assumption: Assumption?,
    ): Fact {
        val область = Area.Project(project)
        val сущность = store.byCode(область, fact) ?: error("факта «$fact» нет в проекте")
        val прежняя = Disposition.of(сущность.doc.path("disposition").asText("free"))
        require(!нуженПовод(прежняя, disposition) || reason.isNotBlank()) {
            поводЗачем(прежняя, disposition)
        }
        val документ = сущность.doc.deepCopy<JsonNode>() as com.fasterxml.jackson.databind.node.ObjectNode
        // Допущение без владельца, точки и способа проверки — не допущение,
        // а надежда (истина схем: assumption обязателен при assumed).
        if (disposition == Disposition.ASSUMED) {
            require(assumption != null && assumption.owner.isNotBlank() && assumption.confirmBy.isNotBlank() &&
                assumption.validation.isNotBlank()) {
                "допущение ставится с владельцем, точкой подтверждения и способом проверки — иначе к точке его никто не подтвердит"
            }
            документ.putObject("assumption")
                .put("owner", assumption.owner).put("confirm_by", assumption.confirmBy)
                .put("validation", assumption.validation).put("impact_if_wrong", assumption.impactIfWrong)
            документ.put("mark", "П")
            документ.put("source_mark", "П")
        }
        if (прежняя == Disposition.ASSUMED && disposition == Disposition.ADOPTED) {
            документ.put("confirmed_by", author)
        }
        документ.put("disposition", disposition.name.lowercase())
        документ.putObject("disposition_decision")
            .put("by", author).put("at", java.time.OffsetDateTime.now().toString())
            .put("reason", reason)
        val обновлена = store.update(сущность.id, документ, Provenance(Channel.MANUAL, author))
        return факт(область, обновлена.code, документ)
    }

    override fun resolveTopic(project: String, topic: String, entity: String, author: String): Topic {
        val область = Area.Project(project)
        val тема = store.byCode(область, topic)?.takeIf { it.kind == "topic" } ?: error("темы «$topic» нет в проекте")
        // Адрес — запись проекта либо карточка полки (норматив живёт в библиотеке).
        val адрес = store.byCode(область, entity) ?: store.byCode(Area.Library, entity)
            ?: throw IllegalArgumentException("сущности «$entity» нет ни в проекте, ни на полке: тема разрешается в существующую запись")
        // Слитая тема своего адреса не имеет: разрешается ГОЛОВА цепочки.
        // Иначе факты обеих тем читались бы по голове, а сущность нашлась бы
        // только у одной из них — и разрыв точки требовал бы разрешить тему,
        // которой на экране уже нет.
        val голова = темыПоля.head(темыПоля.all(область), тема)
        store.update(
            голова.id,
            (голова.doc.deepCopy() as ObjectNode).put("resolved_to", адрес.code).put("resolved_kind", адрес.kind),
            Provenance(Channel.MANUAL, author), status = "resolved",
        )
        return topics(project).first { it.id == голова.code }
    }

    /**
     * Слить тему в другую: один предмет — одна тема.
     *
     * Сливает ЧЕЛОВЕК — служба только предлагает (ИИ не сливает, правило прав
     * онтологии). Факты при этом не переписываются: они остаются при своих
     * темах, а читаются по цепочке `merged_into` до головы.
     */
    override fun mergeTopic(project: String, topic: String, into: String, author: String, reason: String): Topic {
        val голова = темыПоля.merge(Area.Project(project), topic, into, author, reason)
        return topics(project).first { it.id == голова.code }
    }

    override fun topics(project: String): List<Topic> {
        val область = Area.Project(project)
        val факты = store.list(область, "fact")
        val темы = темыПоля.all(область)
        val цепочки = темыПоля.chains(темы)
        // Слитая тема с экрана уходит: адрес среза один — голова цепочки. Счёт
        // фактов при этом складывается по ВСЕЙ цепочке: факты остались при
        // своих темах, и пересчитывать их по головам значило бы переписывать
        // принятое ради вида на экране.
        return темыПоля.heads(темы).map { т ->
            val цепочка = цепочки[т.code] ?: setOf(т.code)
            Topic(
                id = т.code,
                label = т.doc.path("label").asText(""),
                scene = т.doc.path("scene").asText("").ifBlank { null },
                resolvedTo = т.doc.path("resolved_to").asText("").ifBlank { null },
                facts = факты.count { it.doc.path("topic").asText() in цепочка },
            )
        }
    }

    /**
     * Факт наружу.
     *
     * Связи читаются ЗДЕСЬ, а не в маршруте: сериализатор вида один
     * (`tools/validate_one_serializer.py`), и связь, добытая мимо порта, была
     * бы вторым источником правды о том же знании. Область нужна именно для
     * них — без реестра связей перечень пуст, и факт от этого не портится.
     */
    private fun факт(область: Area, код: String, документ: JsonNode): Fact = Fact(
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
        entityClass = документ.path("entity_class").asText("").ifBlank { null },
        manual = документ.path("manual").asBoolean(false),
        paramKey = документ.path("param_key").asText("").ifBlank { null },
        conflicts = документ.path("conflicts").map { it.asText() },
        assumption = документ.path("assumption").takeIf { it.isObject }?.let {
            Assumption(
                it.path("owner").asText(""), it.path("confirm_by").asText(""),
                it.path("validation").asText(""), it.path("impact_if_wrong").asText(""),
            )
        },
        sourceUpdated = документ.path("source_note").asText("").ifBlank { null },
        // Пусто — факт заведён до перестройки: ранг проставит миграция стенда,
        // а здесь он не выдумывается.
        authority = документ.path("authority").asText("").ifBlank { null },
        evidence = документ.path("evidence").asText("").ifBlank { null },
        source = источникФакта(документ),
        links = связиФактов.factLinks(область, код)
            .map { FactLink(it.type, it.from, it.to, it.rationale) },
    )

    // Один построитель на оба пути: две копии однажды разошлись бы на поле.
    override fun facts(project: String): List<Fact> =
        Area.Project(project).let { область -> store.list(область, "fact").map { факт(область, it.code, it.doc) } }

    override fun addTopic(project: String, label: String, author: String): Topic {
        val область = Area.Project(project)
        val метка = label.trim()
        require(метка.isNotBlank()) { "у темы нет названия" }
        val темы = темыПоля.all(область)
        val уже = темы.firstOrNull { it.doc.path("label").asText() == метка }
        // Повтор метки — та же тема, не вторая; а если ту тему уже слили, то
        // и голова её цепочки: тем с экрана не бывает двух на один предмет.
        val код = уже?.let { темыПоля.head(темы, it).code } ?: store.create(
            следующий(область, "topic", "TP"), "topic", область, null,
            mapper.createObjectNode().put("label", метка).put("manual", true),
            Provenance(Channel.MANUAL, author, source = "инженер"),
        ).code
        return topics(project).first { it.id == код }
    }

    /**
     * Поля факта по ИСТИНЕ СХЕМ рядом с плоскими: `source_mark` и союзный
     * `source`. Шаблон отчёта отбирает допущения по `source_mark` (как в
     * YAML), а запись хранила только `mark` — §10 не наполнялся никогда (шип
     * D). Плоские поля остаются до DTO из YAML.
     *
     * Союз пишется РОВНО одной веткой: документ с якорем либо эксперт с
     * учёткой, ролью и датой. Обе ветки разом — не источник, а два источника
     * у одного факта, и сверка тогда не знает, что с чем сличать.
     */
    private fun поСхеме(документ: ObjectNode, эксперт: FactSource.FromExpert? = null) {
        документ.put("source_mark", документ.path("mark").asText("И"))
        val источник = документ.putObject("source")
        if (эксперт != null) {
            источник.put("account", эксперт.account).put("role", эксперт.role).put("at", эксперт.at)
            // Якорь — место в документе; у руки документа нет, и пустой якорь
            // ради прохода под прежнюю схему не подставляется.
            документ.remove("anchor")
            return
        }
        источник.put("material", документ.path("material").asText(""))
        документ.path("anchor").asText("").takeIf { it.isNotBlank() }?.let { источник.put("anchor", it) }
    }

    /** Источник факта из документа: ровно одна ветка союза — по тому, чем он записан. */
    private fun источникФакта(документ: JsonNode): FactSource {
        val источник = документ.path("source")
        val учётка = источник.path("account").asText("")
        if (учётка.isNotBlank()) {
            return FactSource.FromExpert(
                учётка,
                источник.path("role").asText(""),
                источник.path("at").asText(""),
            )
        }
        return FactSource.FromMaterial(
            источник.path("material").asText("").ifBlank { документ.path("material").asText("") },
            документ.path("anchor").asText("").ifBlank { null },
        )
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
        mark: String,
        role: String?,
        authority: String?,
    ): Fact {
        val область = Area.Project(project)
        require(predicate.isNotBlank()) { "факт без утверждения — не факт" }
        // Ручной ввод — кандидат-факт ЭКСПЕРТА (manual_input_rule): источник у
        // него не документ, а человек, и назвать его обязаны трое — учётка,
        // роль и дата. На проекте поля знаний роль обязательна; на проекте
        // прохода её не спрашивают, и источником остаётся прежнее «инженер, дата».
        val ролью = role?.trim().orEmpty()
        require(ролью.isNotBlank() || !KnowledgeFlag.on(store, project)) {
            "роль автора обязательна: у ручного факта источник — учётка · роль · дата, якоря у руки нет"
        }
        // Помета — из перечня владельца: допущение (П) идёт в §10 отчёта
        // как допущение, а не как наш проверенный материал.
        require(mark in setOf("И", "В", "П")) { "помета достоверности: И · В · П, получено «$mark»" }
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
        документ.put("mark", mark)
        // Материал, если он назван, остаётся ПОМЕТОЙ «на что ссылался»: без
        // якоря ссылка на документ не проверяется (правило честности §6.1), и
        // источником у названной роли остаётся эксперт.
        поСхеме(
            документ,
            ролью.takeIf { it.isNotBlank() }
                ?.let { FactSource.FromExpert(author, it, java.time.LocalDate.now().toString()) },
        )
        // Ранг ручного факта — экспертный: рука эксперта выше справки и ниже
        // документа заказчика (ОНТОЛОГИЯ-ФОРМИРОВАНИЯ, authority_notes).
        документ.put(
            "authority",
            authority?.takeIf { it.isNotBlank() }?.let { Authority.of(it) } ?: Authority.EXPERT,
        )
        документ.put("confidence", if (mark == "П") 0.5 else 1.0)
        документ.put("disposition", "free")
        документ.put("manual", true)
        topic?.takeIf { it.isNotBlank() }?.let { документ.put("topic", темаКод(область, it.trim())) }
        val сущность = store.create(
            следующий(область, "fact", "F"), "fact", область, null, документ,
            Provenance(Channel.MANUAL, author, source = источник),
        )
        return факт(область, сущность.code, документ)
    }
}
