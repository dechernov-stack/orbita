// Маршруты сцен 1–6 (волны 1–2): проект, замысел, стейкхолдеры и нужды,
// цели, ограничения, сервисы.
//
// Вынесены из V2Router отдельным файлом по правилу ТЗ-BACKEND §3: файл
// тонкий, домена внутри нет. Роутер остался диспетчером.
//
// Здесь только перевод HTTP в вызовы портов и обратно. Все решения —
// в модулях: движок считает состояние сцен, оценщик отвечает за условия,
// хранилище держит версии. Здесь нет ни одного правила предметной области.
package orbita.api.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.EntityStore
import orbita.kernel.api.KnowledgeFlag
import orbita.kernel.api.LinkRegistry
import orbita.kernel.api.Provenance
import orbita.process.api.ProcessEngine
import java.time.LocalDate

class SceneRoutes(
    private val store: EntityStore,
    private val links: LinkRegistry,
    private val engine: ProcessEngine,
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    fun handle(method: String, path: String, query: Map<String, String>, body: String?): V2Router.Ответ? = when {
        method == "GET" && path == "/v2/phase" -> фаза(требуется(query, "project"))

        method == "POST" && path == "/v2/projects" -> открытьПроект(разобрать(body))

        // З-03 (ПМИ-5, 12.09): любая принятая сущность правится на месте — новой
        // версией с провенансом «правка инженера»; факт-источник остаётся связью.
        method == "PATCH" && path.matches(Regex("/v2/entities/[A-Za-zА-Яа-я0-9._-]+")) ->
            править(требуется(query, "project"), path.removePrefix("/v2/entities/"), разобрать(body))

        // Портфель: без него продукт теряет проект при перезагрузке страницы —
        // открыть заново можно, вернуться к открытому было нельзя.
        method == "GET" && path == "/v2/projects" -> портфель()

        // План работ фазы: даты точек и окна сцен. Порог владельца — план у
        // первой доступной сцены; остальные окна до обзора — пометы.
        method == "GET" && path == "/v2/plan" -> план(требуется(query, "project"))


        method == "POST" && path == "/v2/plan" -> задатьПлан(требуется(query, "project"), разобрать(body))

        method == "POST" && path == "/v2/intent" -> замысел(требуется(query, "project"), разобрать(body))

        method == "POST" && path == "/v2/stakeholders" ->
            сторона(требуется(query, "project"), разобрать(body))

        method == "POST" && path == "/v2/needs" -> нужда(требуется(query, "project"), разобрать(body))

        // Носитель уже заведённой нужды. Нужен там, где нужда пришла
        // разбором, а названного носителя в проекте не оказалось: система
        // говорит об этом при приёме, и чинить это надо здесь, а не
        // заводить нужду заново.
        method == "POST" && path.matches(Regex("/v2/needs/[A-Za-zА-Яа-я0-9-]+/owner")) ->
            носитель(
                требуется(query, "project"),
                path.removePrefix("/v2/needs/").removeSuffix("/owner"),
                разобрать(body),
            )

        method == "POST" && path == "/v2/goals" -> цель(требуется(query, "project"), разобрать(body))

        method == "POST" && path == "/v2/constraints" ->
            ограничение(требуется(query, "project"), разобрать(body))

        method == "POST" && path == "/v2/services" -> сервис(требуется(query, "project"), разобрать(body))


        else -> null
    }

    private fun портфель(): V2Router.Ответ {
        val ответ = mapper.createObjectNode()
        val массив = ответ.putArray("items")
        store.ofKind("project").forEach { проект ->
            массив.addObject()
                .put("code", проект.code)
                .put("name", проект.doc.path("name").asText(проект.code))
                .put("standard", проект.doc.path("standard").asText(""))
                .put("lead", проект.doc.path("lead").asText(""))
                .put("phase", проект.doc.path("phase").asText("Pre-Phase A"))
                // Карточка проекта несёт признак поля знаний: без него экран
                // не знает, показывать ли постановку из поля и ранг доверия.
                // Поля нет — выключено: прежние проекты остаются на прежнем.
                .put("knowledge_v2", проект.doc.path("knowledge_v2").asBoolean(false))
        }
        return V2Router.Ответ(200, ответ)
    }

    private fun план(проект: String): V2Router.Ответ {
        val запись = store.list(Area.Project(проект), "plan").lastOrNull()
        val ответ = mapper.createObjectNode()
        if (запись == null) {
            ответ.put("planned", false)
            ответ.put("note", "план работ фазы не задан: ленте нечего показывать")
        } else {
            ответ.put("planned", true)
            ответ.put("set_by", запись.doc.path("set_by").asText(""))
            ответ.set<JsonNode>("gate_dates", запись.doc.path("gate_dates"))
            ответ.set<JsonNode>("scene_windows", запись.doc.path("scene_windows"))
        }
        return V2Router.Ответ(200, ответ)
    }

    private fun задатьПлан(проект: String, тело: JsonNode): V2Router.Ответ {
        val область = Area.Project(проект)
        val фазаПлана = тело.path("phase").asText("Pre-Phase A")
        // Фаза не может начаться раньше, чем закрылась предыдущая (замечание
        // владельца 11.09): даты точек и окна сцен Phase A — не раньше KDP-A
        // (даты решения, а если его ещё нет — плановой даты точки).
        if (фазаПлана != "Pre-Phase A") {
            val kdpA = store.byCode(область, "KDP-A")
            val решение = kdpA?.doc?.path("decision")?.asText("")?.ifBlank { null }?.let { store.byId(it) }
            val началоФазы = решение?.doc?.path("at")?.asText("")?.take(10)?.ifBlank { null }
                ?: kdpA?.doc?.path("planned_date")?.asText("")?.ifBlank { null }
            if (началоФазы != null) {
                val даты = тело.path("gate_dates").map { it.path("gate").asText() to it.path("date").asText("") } +
                    тело.path("scene_windows").map { it.path("scene").asText() to it.path("start").asText("") }
                val раньше = даты.filter { (_, д) -> д.isNotBlank() && д < началоФазы }
                require(раньше.isEmpty()) {
                    "фаза «$фазаПлана» не может начаться до окончания Pre-Phase A: KDP-A — $началоФазы, а раньше него стоят " +
                        раньше.joinToString(", ") { (к, д) -> "$к ($д)" }
                }
            }
        }
        val документ = mapper.createObjectNode()
        документ.put("phase", фазаПлана)
        документ.set<JsonNode>("gate_dates", тело.path("gate_dates"))
        документ.set<JsonNode>("scene_windows", тело.path("scene_windows"))
        документ.put("set_by", автор(тело))
        val прежний = store.list(область, "plan").lastOrNull()
        val запись = if (прежний == null) {
            store.create("PLAN-$проект", "plan", область, "1", документ, Provenance(Channel.MANUAL, автор(тело)))
        } else {
            store.update(прежний.id, документ, Provenance(Channel.MANUAL, автор(тело)))
        }
        // Даты точек живут в самих точках: план их задаёт, а не дублирует.
        тело.path("gate_dates").forEach { пара ->
            val точка = store.byCode(область, пара.path("gate").asText()) ?: return@forEach
            val док = точка.doc.deepCopy<ObjectNode>().put("planned_date", пара.path("date").asText(""))
            store.update(точка.id, док, Provenance(Channel.MANUAL, автор(тело)))
        }
        return V2Router.Ответ(201, mapper.createObjectNode().put("code", запись.code).put("version", запись.version))
    }

    private fun фаза(проект: String) = V2Router.Ответ(200, PhaseJson.вид(engine.view(проект), mapper))

    private fun открытьПроект(тело: JsonNode): V2Router.Ответ {
        val код = тело.path("code").asText("").ifBlank { "PJ-" + LocalDate.now().toString().replace("-", "") }
        val область = Area.Project(код)
        val автор = автор(тело)
        // Поле знаний v2 — признак проекта: один стенд держит проект прохода
        // ПМИ-5 (прежнее поведение) и новый проект одновременно. Явный выбор
        // в теле сильнее умолчания стенда — иначе новый порядок нельзя
        // проверить на отдельном проекте, пока умолчание выключено.
        val знанияV2 =
            if (тело.has("knowledge_v2")) тело.path("knowledge_v2").asBoolean(false)
            else KnowledgeFlag.newProjectsDefault
        store.create(
            код, "project", область, "1",
            mapper.createObjectNode()
                .put("name", тело.path("name").asText(код))
                .put("standard", тело.path("standard").asText("NASA-7120"))
                .put("mission_class", тело.path("mission_class").asText(""))
                .put("lead", тело.path("lead").asText(автор))
                .put("knowledge_v2", знанияV2),
            Provenance(Channel.MANUAL, автор),
        )
        // Точки фазы заводятся сразу с датами по умолчанию от сегодняшнего дня:
        // сцена 1 обязана оставить фазу с датами, а не с пустотой.
        val шаблон = engine.openPhase(код, тело.path("template").asText("PHT-9001"))
        шаблон.gates.forEach { точка ->
            store.create(
                точка.key, "gate", область, "1",
                mapper.createObjectNode()
                    .put("title", точка.title)
                    .put("planned_date", точка.plannedDate ?: ""),
                Provenance(Channel.MANUAL, автор),
            )
        }
        val ответ = PhaseJson.вид(engine.view(код), mapper)
        ответ.set<JsonNode>("prefilled", предзаполнитьРамки(код, тело.path("mission_class").asText(""), автор))
        // Признак поля знаний возвращается сразу: экран решает по ответу
        // сервера, какой вид сцен показывать, и не гадает по стенду.
        ответ.put("knowledge_v2", знанияV2)
        return V2Router.Ответ(201, ответ)
    }

    /**
     * Рамки класса миссии — в сцену 5 при заведении проекта (ПМИ-5, замечание
     * владельца 11.09 «в ограничениях пусто»): класс миссии с полки
     * (`mission_class`, по коду или имени) называет `default_constraints` —
     * коды записей `constraint` на полке; они копируются ограничениями проекта
     * с происхождением «полка класса миссии». Класса на полке нет — ответ
     * говорит об этом словами, а не молчит.
     */
    private fun предзаполнитьРамки(проект: String, классМиссии: String, автор: String): ObjectNode {
        val итог = mapper.createObjectNode().put("constraints", 0)
        if (классМиссии.isBlank()) return итог.put("note", "класс миссии не указан — рамки не предзаполнены")
        val класс = store.list(Area.Library, "mission_class")
            .firstOrNull { it.code == классМиссии || it.doc.path("name").asText("") == классМиссии }
            ?: return итог.put("note", "класса миссии «$классМиссии» на полке нет — рамки не предзаполнены (tools/v2/load_shelves.py)")
        val область = Area.Project(проект)
        val есть = store.list(область, "constraint").map { it.code }.toSet()
        var скопировано = 0
        класс.doc.path("default_constraints").forEach { ссылка ->
            val код = ссылка.asText()
            if (код in есть) return@forEach
            val рамка = store.byCode(Area.Library, код)?.takeIf { it.kind == "constraint" } ?: return@forEach
            val документ = рамка.doc.deepCopy<ObjectNode>()
            store.create(код, "constraint", область, "5", документ, Provenance(Channel.SHELF, "полка класса миссии ${класс.code} ($автор)"))
            скопировано += 1
        }
        return итог.put("constraints", скопировано).put("mission_class", класс.code)
    }

    private fun замысел(проект: String, тело: JsonNode): V2Router.Ответ {
        val область = Area.Project(проект)
        val автор = автор(тело)
        val документ = mapper.createObjectNode()
        listOf("for_whom", "what", "where", "horizon", "text").forEach { поле ->
            тело.path(поле).asText("").takeIf { it.isNotBlank() }?.let { документ.put(поле, it) }
        }
        val принят = тело.path("accepted").asBoolean(false)
        val прежний = store.list(область, "intent").firstOrNull()
        val сущность = if (прежний == null) {
            store.create(
                "INT-0001", "intent", область, "2", документ,
                Provenance(Channel.MANUAL, автор), status = if (принят) "accepted" else "draft",
            )
        } else {
            store.update(прежний.id, документ, Provenance(Channel.MANUAL, автор), if (принят) "accepted" else null)
        }
        val ответ = mapper.createObjectNode()
        ответ.put("id", сущность.id)
        ответ.put("status", сущность.status)
        ответ.set<ObjectNode>("phase", PhaseJson.вид(engine.view(проект), mapper))
        return V2Router.Ответ(200, ответ)
    }

    private fun завести(проект: String, вид: String, сцена: String, тело: JsonNode): V2Router.Ответ {
        val область = Area.Project(проект)
        val автор = автор(тело)
        val код = тело.path("code").asText("").ifBlank { следующийКод(область, вид) }
        val документ = тело.deepCopy<ObjectNode>().apply {
            // «reconcile» и «decision» — поля ВОРОТ, а не содержания: в
            // документе вида их нет по истине схем, и правка на месте потом
            // отбила бы их словами «поля нет». След решения остаётся там,
            // где ему место, — в провенансе записи.
            remove(listOf("code", "author", "project", "owner", "covers", "reconcile", "decision"))
        }
        val сущность = store.create(
            код, вид, область, сцена, документ,
            Provenance(Channel.MANUAL, автор, source = сверка(тело)),
        )
        val ответ = mapper.createObjectNode()
        ответ.put("id", сущность.id)
        ответ.put("code", сущность.code)
        return V2Router.Ответ(201, ответ)
    }

    /**
     * Ворота сохранения на проекте поля знаний v2: введённое проходит сверку,
     * и обязательная связь понятия обязана быть закрыта. Правило одно на все
     * сцены и на ручной факт, поэтому живёт отдельным файлом; здесь маршрут
     * называет только СВОИ поля запроса, которыми связь закрывается.
     *
     * На проекте без флага возвращает `null` — ввод сохраняется как до
     * перестройки, и тела прежних запросов не меняются ни на букву.
     */
    private fun ворота(
        проект: String,
        понятие: String,
        тело: JsonNode,
        закрывают: Map<String, List<String>> = emptyMap(),
    ): V2Router.Ответ? = ReconcileGate.ворота(store, mapper, проект, понятие, тело, закрывают)

    /** Сцена 3: сторона. Обязательных связей у стороны нет — сторона без нужды лишь помета к воротам. */
    private fun сторона(проект: String, тело: JsonNode): V2Router.Ответ {
        ворота(проект, "stakeholder", тело)?.let { return it }
        return завести(проект, "stakeholder", "3", тело)
    }

    private fun нужда(проект: String, тело: JsonNode): V2Router.Ответ {
        // Носителя нужда называет полем «owner» — им и закрывается связь
        // онтологии «owns→stakeholder»; «stakeholder» принимается как имя
        // того же поля из истины схем.
        ворота(проект, "need", тело, mapOf("owns" to listOf("owner", "stakeholder")))?.let { return it }
        val ответ = завести(проект, "need", "3", тело)
        // Носитель нужды — обязательная связь: нужда без стейкхолдера повиснет
        // и на выходе сцены 3, и в матрице покрытия.
        val носитель = тело.path("owner").asText("")
        if (носитель.isNotBlank()) {
            val область = Area.Project(проект)
            val стейкхолдер = store.byCode(область, носитель) ?: store.byId(носитель)
            requireNotNull(стейкхолдер) { "стейкхолдер «$носитель» не найден: нужде нужен носитель" }
            links.link("owns", стейкхолдер.id, ответ.body.path("id").asText(), Provenance(Channel.MANUAL, автор(тело)))
        }
        return ответ
    }

    /**
     * Назначить носителя уже заведённой нужде.
     *
     * Нужда без носителя не проходит выход сцены 3 — за неё никто не
     * отвечает. Прежняя связь снимается: носитель у нужды один, и «ещё
     * один владелец» означает не двух ответственных, а смену.
     */
    private fun носитель(проект: String, код: String, тело: JsonNode): V2Router.Ответ {
        val область = Area.Project(проект)
        val нужда = store.byCode(область, код) ?: error("нужды «$код» нет в проекте")
        require(нужда.kind == "need") { "«$код» — не нужда, а ${нужда.kind}" }
        val имя = тело.path("owner").asText("").trim()
        require(имя.isNotBlank()) { "нужде нужен носитель: назовите сторону" }
        val сторона = store.byCode(область, имя)
            ?: store.list(область, "stakeholder").firstOrNull {
                it.doc.path("name").asText("").trim().equals(имя, ignoreCase = true)
            }
        requireNotNull(сторона) { "стороны «$имя» нет в проекте: заведите её в сцене 3" }
        links.to(нужда.id, "owns").forEach { links.unlink(it.id, Provenance(Channel.MANUAL, автор(тело))) }
        links.link("owns", сторона.id, нужда.id, Provenance(Channel.MANUAL, автор(тело)),
            rationale = тело.path("rationale").asText("").ifBlank { null })
        return V2Router.Ответ(200, mapper.createObjectNode()
            .put("need", нужда.code).put("owner", сторона.code))
    }

    private fun цель(проект: String, тело: JsonNode): V2Router.Ответ {
        // Цель покрывает нужды полем «covers» — им закрывается «covers→need>=1».
        ворота(проект, "goal", тело, mapOf("covers" to listOf("covers", "needs")))?.let { return it }
        val ответ = завести(проект, "goal", "4", тело)
        val область = Area.Project(проект)
        // Цель покрывает нужды: без этой связи нужда останется невыполненной,
        // и сцена 4 честно об этом скажет.
        тело.path("covers").forEach { ссылка ->
            val нужда = store.byCode(область, ссылка.asText()) ?: store.byId(ссылка.asText())
            requireNotNull(нужда) { "нужда «${ссылка.asText()}» не найдена" }
            links.link("covers", ответ.body.path("id").asText(), нужда.id, Provenance(Channel.MANUAL, автор(тело)))
        }
        return ответ
    }

    /** Сцена 5: ограничение получает код Р-серии — он стабилен и на него ссылаются. */
    private fun ограничение(проект: String, тело: JsonNode): V2Router.Ответ {
        // У рамки связь условная — «normative_basis if obligation»: основание
        // спрашивается только у регуляторной, и условие читает сами ворота.
        ворота(проект, "constraint", тело)?.let { return it }
        val область = Area.Project(проект)
        val занято = store.list(область, "constraint").mapNotNull {
            Regex("^Р(\\d+)$").find(it.code)?.groupValues?.get(1)?.toIntOrNull()
        }
        val код = тело.path("code").asText("").ifBlank { "Р${(занято.maxOrNull() ?: 0) + 1}" }
        val документ = тело.deepCopy<ObjectNode>().apply {
            remove(listOf("code", "author", "project", "reconcile", "decision"))
        }
        val сущность = store.create(
            код, "constraint", область, "5", документ,
            Provenance(Channel.MANUAL, автор(тело), source = сверка(тело)),
        )
        return V2Router.Ответ(201, mapper.createObjectNode().put("id", сущность.id).put("code", сущность.code))
    }

    /** Сцена 6: сервис покрывает нужды — без этой связи он ничей. */
    private fun сервис(проект: String, тело: JsonNode): V2Router.Ответ {
        // У сервиса связей две: покрытые нужды и класс качества (Р9) — без
        // класса сервис не различает виды потребителей, и MOP не порождаются.
        ворота(проект, "service", тело, mapOf("covers" to listOf("covers", "needs")))?.let { return it }
        val ответ = завести(проект, "service", "6", тело)
        val область = Area.Project(проект)
        тело.path("covers").forEach { ссылка ->
            val нужда = store.byCode(область, ссылка.asText()) ?: store.byId(ссылка.asText())
            requireNotNull(нужда) { "нужда «${ссылка.asText()}» не найдена" }
            links.link("covers", ответ.body.path("id").asText(), нужда.id, Provenance(Channel.MANUAL, автор(тело)))
        }
        return ответ
    }

    private fun следующийКод(область: Area, вид: String): String {
        val префикс = when (вид) {
            "stakeholder" -> "SK"
            "need" -> "ND"
            "goal" -> "MG"
            "service" -> "SV"
            else -> вид.take(2).uppercase()
        }
        val занято = store.list(область, вид).mapNotNull {
            Regex("^$префикс-(\\d+)$").find(it.code)?.groupValues?.get(1)?.toIntOrNull()
        }
        return "%s-%04d".format(префикс, (занято.maxOrNull() ?: 0) + 1)
    }

    private fun разобрать(тело: String?): JsonNode =
        if (тело.isNullOrBlank()) mapper.createObjectNode() else mapper.readTree(тело)

    /** Виды, которые правятся на месте: содержание сцен 2–8; служебные (проект, точки, решения, план) — нет. */
    private val правимые = setOf(
        "stakeholder", "need", "goal", "service", "constraint", "requirement", "component", "interface",
        "scenario", "state_machine", "technology", "risk", "assumption", "parameter", "function",
        "exchange", "exchange_item", "budget", "logical_component", "cost_estimate", "debris_assessment",
    )

    /**
     * Правка на месте: поля из `fields` ложатся поверх текущего документа
     * (пустое значение снимает поле), запись получает новую версию и
     * провенанс «правка инженера: кто», связи и код не трогаются. Поле вне
     * схемы вида отбивает хранилище — отказ словами, версия не плодится.
     */
    private fun править(проект: String, код: String, тело: JsonNode): V2Router.Ответ {
        val область = Area.Project(проект)
        val запись = store.byCode(область, код) ?: throw NoSuchElementException("записи «$код» в проекте нет")
        require(запись.kind in правимые) { "«$код» — это ${запись.kind}: такие записи на месте не правятся" }
        val поля = тело.path("fields")
        require(поля.isObject && poleCount(поля) > 0) { "нужно fields: {поле: значение}; пустое значение снимает поле" }
        val схема = runCatching { orbita.kernel.schema.GeneratedKinds.of(запись.kind).fields }.getOrDefault(emptyList())
        val документ = запись.doc.deepCopy<ObjectNode>()
        var изменено = 0
        поля.fields().forEach { (имя, значение) ->
            require(имя !in setOf("code", "kind", "id")) { "поле «$имя» не правится: код и вид стабильны" }
            require(схема.isEmpty() || имя in схема || имя in setOf("notes", "tags")) {
                "поля «$имя» у вида ${запись.kind} нет: схема знает " + (схема + listOf("notes", "tags")).joinToString(" · ")
            }
            val было = документ.get(имя)
            if (значение.isNull || (значение.isTextual && значение.asText().isBlank())) документ.remove(имя) else документ.set<JsonNode>(имя, значение)
            if (было != документ.get(имя)) изменено += 1
        }
        if (изменено == 0) return V2Router.Ответ(200, mapper.createObjectNode().put("code", код).put("version", запись.version).put("changed", 0))
        val причина = тело.path("reason").asText("").ifBlank { null }
        val кем = "правка инженера: " + автор(тело) + (причина?.let { " — $it" } ?: "")
        val новая = store.update(запись.id, документ, Provenance(Channel.MANUAL, кем))
        return V2Router.Ответ(200, mapper.createObjectNode().put("id", новая.id).put("code", новая.code).put("version", новая.version).put("changed", изменено))
    }

    private fun poleCount(узел: JsonNode): Int = узел.fields().asSequence().count()

    private fun автор(тело: JsonNode): String =
        тело.path("author").asText("").ifBlank { "стенд" }

    /**
     * След сверки для провенанса: «SR-12#c1» — по нему видно, через какую
     * сверку прошёл этот ввод. Пусто на проекте прохода: там сверки нет, и
     * происхождение записи остаётся прежним до буквы.
     */
    private fun сверка(тело: JsonNode): String? =
        тело.path("reconcile").asText("").trim().ifBlank { null }

    private fun требуется(query: Map<String, String>, имя: String): String =
        query[имя] ?: throw IllegalArgumentException("нужен параметр «$имя»")
}
