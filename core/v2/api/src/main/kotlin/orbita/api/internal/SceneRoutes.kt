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
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.kernel.api.KnowledgeFlag
import orbita.kernel.api.LinkRegistry
import orbita.kernel.api.Provenance
import orbita.kernel.api.QosClass
import orbita.process.api.GateView
import orbita.process.api.ProcessEngine
import java.time.LocalDate

class SceneRoutes(
    private val store: EntityStore,
    private val links: LinkRegistry,
    private val engine: ProcessEngine,
    private val mapper: ObjectMapper = ObjectMapper(),
    /** Единицы справочника для экрана: код записи → показ человеку. */
    private val units: (() -> Map<String, String>)? = null,
) {

    fun handle(method: String, path: String, query: Map<String, String>, body: String?): V2Router.Ответ? = when {
        method == "GET" && path == "/v2/phase" -> фаза(требуется(query, "project"))

        method == "POST" && path == "/v2/projects" -> открытьПроект(разобрать(body))

        // З-03 (ПМИ-5, 12.09): любая принятая сущность правится на месте — новой
        // версией с провенансом «правка инженера»; факт-источник остаётся связью.
        method == "PATCH" && path.matches(Regex("/v2/entities/[A-Za-zА-Яа-я0-9._-]+")) ->
            править(требуется(query, "project"), path.removePrefix("/v2/entities/"), разобрать(body))

        // Истина СХЕМ о виде — для экрана: поля, русские имена полей, стадии
        // обязательности, перечни и РУССКИЕ ЗНАЧЕНИЯ перечислений (истина
        // 19.09, `enum_labels`). Ни имён полей, ни значений у экрана своих нет:
        // «клиент показывает только label», «значение без метки — сторож».
        method == "GET" && path.matches(Regex("/v2/kinds/[a-z_]+")) ->
            видДляЭкрана(path.removePrefix("/v2/kinds/"))

        // Единицы справочника: показатель без единицы не существует, а выбрать
        // её было негде (владелец 19.09: «единиц измерения нет — блок»).
        method == "GET" && path == "/v2/units" -> единицы()

        // Портфель: без него продукт теряет проект при перезагрузке страницы —
        // открыть заново можно, вернуться к открытому было нельзя.
        method == "GET" && path == "/v2/projects" -> портфель()

        // План работ фазы: даты точек и окна сцен. Порог владельца — план у
        // первой доступной сцены; остальные окна до обзора — пометы.
        method == "GET" && path == "/v2/plan" -> план(требуется(query, "project"))


        method == "POST" && path == "/v2/plan" -> задатьПлан(требуется(query, "project"), разобрать(body))
        // Паспорт проекта (журнал ПМИ-7, З-25): название · класс · руководитель ·
        // стандарт · даты точек — правка на месте с версией; печать читает отсюда.
        method == "GET" && path == "/v2/passport" -> паспорт(требуется(query, "project"))
        method == "PATCH" && path == "/v2/passport" -> правитьПаспорт(требуется(query, "project"), разобрать(body))

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
            val руководитель = проект.doc.path("manager").asText("")
                .ifBlank { проект.doc.path("lead").asText("") }
            val узел = массив.addObject()
                .put("code", проект.code)
                .put("name", проект.doc.path("name").asText(проект.code))
                .put("standard", проект.doc.path("standard").asText(""))
                // Карточка портфеля читает имена истины, но заведённые прежде
                // проекты несут старые (`lead`, `phase`) — читаем оба, пока они
                // живы на стенде.
                .put("lead", руководитель)
                // Руководитель именем истины схем: карточка экрана «Проекты»
                // ставит его инициалы чипом, а полное имя — подсказкой.
                .put("manager", руководитель)
                .put(
                    "phase",
                    проект.doc.path("phase_current").asText("")
                        .ifBlank { проект.doc.path("phase").asText("") }
                        .ifBlank { "Pre-Phase A" },
                )
                // Карточка проекта несёт признак поля знаний: без него экран
                // не знает, показывать ли постановку из поля и ранг доверия.
                // Поля нет — выключено: прежние проекты остаются на прежнем.
                .put("knowledge_v2", проект.doc.path("knowledge_v2").asBoolean(false))
                // Группа портфеля (экран 2, шип 2): рабочие и примеры — две
                // равные колонки. Колонку решает поле, а не догадка клиента.
                .put("group", группаПроекта(проект))
                .put("last_activity", последняяАктивность(проект))
            // Ближайшая непройденная точка со СЧЁТОМ блокирующих: на клиенте
            // вердиктов из сравнения величин нет — «блокирует 2» приходит
            // числом, готовым к показу. Точки нет (фаза пройдена целиком или
            // шаблона на стенде не оказалось) — поля нет: молчание честнее
            // выдуманной точки.
            ближайшаяТочка(проект.code)?.let { (точка, дата) ->
                узел.putObject("gate")
                    .put("key", точка.key)
                    .put("title", точка.title)
                    .put("planned_date", дата)
                    .put("blocking", точка.blocking.size)
            }
        }
        return V2Router.Ответ(200, ответ)
    }

    /**
     * Группа портфеля словами истины схем: `group` вида «проект» — work либо
     * example. Проекты, заведённые до этого поля, его не несут: тогда решает
     * `example` (так проект-пример помечен у первой версии, ADR-053) и канал
     * происхождения — пример засеян поставкой, а не руками человека.
     */
    private fun группаПроекта(проект: Entity): String {
        val названа = проект.doc.path("group").asText("")
        if (названа == "work" || названа == "example") return названа
        val пример = проект.doc.path("example").asBoolean(false) || проект.provenance.channel == Channel.EXAMPLE
        return if (пример) "example" else "work"
    }

    /**
     * День последней активности проекта: самая поздняя правка ЛЮБОЙ его
     * записи. Считается по самим записям — второго счётчика активности,
     * который разойдётся с данными, в проекте нет.
     */
    private fun последняяАктивность(проект: Entity): String {
        val правки = store.list(Area.Project(проект.code)).map { it.updatedAt } + проект.updatedAt
        return правки.max().toString().take(10)
    }

    /**
     * Ближайшая непройденная точка фазы и её дата. Порядок точек и блокирующие
     * условия считает движок; дату берём из самой точки — план и форма правят
     * записи, и двум датам разойтись негде. Шаблона фазы на стенде нет —
     * строка остаётся без точки, а не роняет весь портфель.
     */
    private fun ближайшаяТочка(проект: String): Pair<GateView, String>? {
        val фаза = runCatching { engine.view(проект) }.getOrNull() ?: return null
        val точка = фаза.gates.firstOrNull { !it.passed } ?: return null
        val запись = store.byCode(Area.Project(проект), точка.key)?.takeIf { it.kind == "gate" }
        val дата = запись?.doc?.path("planned_date")?.asText("")?.ifBlank { null } ?: точка.plannedDate ?: ""
        return точка to дата
    }

    /** Поля паспорта: их правит руководитель на месте; фазу и шаблон меняет решение точки. */
    private val ПАСПОРТ = listOf("name", "mission_class", "manager", "standard")

    private fun записьПроекта(проект: String) =
        store.byCode(Area.Project(проект), проект)?.takeIf { it.kind == "project" }
            ?: throw NoSuchElementException("проекта «$проект» нет")

    private fun меткаПоля(имя: String): String =
        runCatching { orbita.kernel.schema.GeneratedKinds.of("project").labels[имя] }.getOrNull() ?: имя

    /**
     * Паспорт проекта: то, что задаётся при создании и прежде не правилось
     * ничем — в §2 FAD печатался «инженер», в §1 FA пустой класс миссии
     * (журнал ПМИ-7, З-25). Печать читает те же поля документа проекта, так
     * что правка здесь и есть правка документов.
     */
    private fun паспорт(проект: String): V2Router.Ответ {
        val запись = записьПроекта(проект)
        val узел = mapper.createObjectNode()
        узел.put("code", запись.code).put("version", запись.version)
        узел.put("updated_at", запись.updatedAt.toString().take(10))
        узел.put("updated_by", запись.provenance.author)
        ПАСПОРТ.forEach { поле -> узел.put(поле, запись.doc.path(поле).asText("")) }
        // Проекты, заведённые до имён истины, несут `lead` и `phase`: читаем оба.
        if (узел.path("manager").asText("").isBlank()) узел.put("manager", запись.doc.path("lead").asText(""))
        узел.put(
            "phase_current",
            запись.doc.path("phase_current").asText("").ifBlank { запись.doc.path("phase").asText("") },
        )
        узел.put("phase_template", запись.doc.path("phase_template").asText(""))
        val метки = узел.putObject("labels")
        (ПАСПОРТ + listOf("phase_current", "phase_template")).forEach { метки.put(it, меткаПоля(it)) }
        val стандарты = узел.putArray("standards")
        orbita.kernel.schema.Enums.значения("project", "standard").forEach { (код, метка) ->
            стандарты.addObject().put("code", код).put("label", метка)
        }
        // Классы миссии — с полки: значение поля есть код или имя класса.
        val классы = узел.putArray("mission_classes")
        store.list(Area.Library, "mission_class").forEach { к ->
            классы.addObject().put("code", к.code).put("name", к.doc.path("name").asText(к.code))
        }
        // Даты точек текущей фазы — из САМИХ точек: план их задаёт, паспорт
        // правит те же; вид фазы берёт их оттуда же и не может разойтись.
        val точки = узел.putArray("gates")
        val область = Area.Project(проект)
        engine.view(проект).gates.forEach { т ->
            val запись = store.byCode(область, т.key)?.takeIf { it.kind == "gate" }
            val дата = запись?.doc?.path("planned_date")?.asText("")?.ifBlank { null } ?: т.plannedDate ?: ""
            точки.addObject().put("key", т.key).put("title", т.title)
                .put("planned_date", дата).put("passed", т.passed)
        }
        return V2Router.Ответ(200, узел)
    }

    private fun правитьПаспорт(проект: String, тело: JsonNode): V2Router.Ответ {
        val область = Area.Project(проект)
        val запись = записьПроекта(проект)
        val поля = тело.path("fields")
        val даты = тело.path("gate_dates")
        require((поля.isObject && poleCount(поля) > 0) || (даты.isArray && даты.size() > 0)) {
            "нужно fields: {поле: значение} и/или gate_dates: [{gate, date}]"
        }
        val документ = запись.doc.deepCopy<ObjectNode>()
        var изменено = 0
        поля.fields().forEach { (имя, значение) ->
            require(имя in ПАСПОРТ) {
                "поле «$имя» в паспорте не правится: паспорт — это " +
                    ПАСПОРТ.joinToString(" · ") { меткаПоля(it) } + "; фазу и шаблон меняет решение точки"
            }
            val текст = значение.asText("").trim()
            // Поля паспорта обязательны по истине: их меняют, а не снимают.
            require(текст.isNotBlank()) { "поле «${меткаПоля(имя)}» обязательно — его меняют, а не снимают" }
            if (документ.path(имя).asText("") != текст) {
                документ.put(имя, текст)
                изменено += 1
            }
        }
        // Руководитель назван именем истины — старое `lead` снимается, чтобы
        // печать и портфель читали одно поле, а не гадали между двумя.
        if (поля.has("manager") && документ.has("lead")) {
            документ.remove("lead")
            изменено += 1
        }
        val правимые = поля.fieldNames().asSequence().toSet()
        orbita.kernel.schema.Enums.проверить("project", документ, правимые).let { отказы ->
            require(отказы.isEmpty()) { отказы.joinToString("; ") }
        }
        изменено += orbita.kernel.schema.Enums.нормализовать("project", документ).size
        val автор = автор(тело)
        val причина = тело.path("reason").asText("").ifBlank { null }
        val кем = "паспорт проекта: $автор" + (причина?.let { " — $it" } ?: "")
        val новая = if (изменено > 0) store.update(запись.id, документ, Provenance(Channel.MANUAL, кем)) else запись

        // Даты точек: правятся в самих точках, как делает план; пройденную
        // точку не передвинуть — её дата стала фактом.
        val пройдены = engine.view(проект).gates.filter { it.passed }.map { it.key }.toSet()
        var датИзменено = 0
        даты.forEach { пара ->
            val ключ = пара.path("gate").asText("")
            val дата = пара.path("date").asText("")
            require(Regex("""\d{4}-\d{2}-\d{2}""").matches(дата)) { "дата точки «$ключ» — ГГГГ-ММ-ДД; пришло «$дата»" }
            val точка = store.byCode(область, ключ)?.takeIf { it.kind == "gate" }
                ?: throw IllegalArgumentException("точки «$ключ» в проекте нет")
            require(ключ !in пройдены) { "точка «$ключ» пройдена: её дата — факт, а не план" }
            if (точка.doc.path("planned_date").asText("") == дата) return@forEach
            store.update(точка.id, точка.doc.deepCopy<ObjectNode>().put("planned_date", дата), Provenance(Channel.MANUAL, кем))
            датИзменено += 1
        }
        // План фазы держит те же даты — поправить, чтобы две даты не разъехались.
        if (датИзменено > 0) {
            store.list(область, "plan").lastOrNull()?.let { план ->
                val док = план.doc.deepCopy<ObjectNode>()
                val перечень = док.path("gate_dates")
                if (перечень.isArray) {
                    перечень.forEach { п ->
                        val новое = даты.firstOrNull { it.path("gate").asText() == п.path("gate").asText() } ?: return@forEach
                        (п as ObjectNode).put("date", новое.path("date").asText())
                    }
                    store.update(план.id, док, Provenance(Channel.MANUAL, кем))
                }
            }
        }
        return V2Router.Ответ(
            200,
            mapper.createObjectNode().put("code", новая.code).put("version", новая.version)
                .put("changed", изменено).put("gate_dates_changed", датИзменено),
        )
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
        // Группа портфеля называется при заведении: рабочий проект или пример.
        // Не названа — рабочий: проект заводят, чтобы работать.
        val группа = тело.path("group").asText("").ifBlank { "work" }
        require(группа == "work" || группа == "example") {
            "группа проекта — рабочая («work») или пример («example»); пришло «$группа»"
        }
        // Даты точек приходят формой «Новый проект» (экран 3): три точки фазы
        // с умолчаниями от даты старта. Формат проверяется ДО заведения —
        // отказ не должен оставлять за собой проект без точек.
        val заданныеДаты = linkedMapOf<String, String>()
        тело.path("gate_dates").forEach { пара ->
            val ключ = пара.path("gate").asText("")
            val дата = пара.path("date").asText("")
            require(Regex("""\d{4}-\d{2}-\d{2}""").matches(дата)) { "дата точки «$ключ» — ГГГГ-ММ-ДД; пришло «$дата»" }
            заданныеДаты[ключ] = дата
        }
        // Шаблон фазы читается ПЕРЕД записью проекта: по нему видно, какие
        // точки бывают, и дата чужой точки отбивается словами, пока заводить
        // ещё нечего.
        val шаблон = engine.openPhase(код, тело.path("template").asText("PHT-9001"))
        val ключиШаблона = шаблон.gates.map { it.key }
        val чужие = заданныеДаты.keys.filterNot { it in ключиШаблона }
        require(чужие.isEmpty()) {
            "точки «" + чужие.joinToString("», «") + "» в шаблоне фазы нет: точки фазы — " +
                шаблон.gates.joinToString(" · ") { it.title }
        }
        store.create(
            код, "project", область, "1",
            mapper.createObjectNode()
                .put("name", тело.path("name").asText(код))
                .put("standard", тело.path("standard").asText("NASA-7120"))
                .put("mission_class", тело.path("mission_class").asText(""))
                // Руководитель зовётся `manager` — так он назван у вида «проект»
                // в истине схем. Печать ищет поле по имени: документ с `lead`
                // печатал в §2 FAD прочерк вместо фамилии (владелец, 21.09).
                // Тело со старым именем принимается: экран сцены 1 не переучен.
                .put(
                    "manager",
                    тело.path("manager").asText("").ifBlank { тело.path("lead").asText(автор) },
                )
                .put("group", группа)
                // `example` — тот же факт, что группа, но им помечен проект-пример
                // у первой версии (портфель `/views/portfolio` читает его). Пишутся
                // оба и всегда вместе: разойтись двум именам одного факта нельзя.
                .put("example", группа == "example")
                .put("knowledge_v2", знанияV2),
            Provenance(Channel.MANUAL, автор),
        )
        // Точки фазы заводятся сразу с датами: названные формой — её датами,
        // остальные — умолчанием шаблона от сегодняшнего дня. Сцена 1 обязана
        // оставить фазу с датами, а не с пустотой.
        шаблон.gates.forEach { точка ->
            store.create(
                точка.key, "gate", область, "1",
                mapper.createObjectNode()
                    .put("title", точка.title)
                    .put("planned_date", заданныеДаты[точка.key] ?: точка.plannedDate ?: ""),
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
        // Класс обслуживания нужды: обязателен, но до сцены сервисов допустимо
        // TBR с ответственным (правило — QosClass, решение владельца 12.09).
        // Инженер заводит нужду словами заказчика; класса в этих словах может
        // не быть, и требовать его здесь значило бы просить выдумать.
        if (вид == "need") QosClass.fillIfMissing(mapper, документ, автор)
        // Интересы стороны — список её слов (истина 24.09): строка экрана
        // и перечень эталона ложатся одним правилом.
        if (вид == "stakeholder") orbita.kernel.schema.Interests.нормализовать(документ)
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

    private fun нужда(проект: String, телоСырое: JsonNode): V2Router.Ответ {
        // Носителей нужда называет полем «owner» (одна сторона) либо «owners»
        // (несколько) — ими закрывается связь онтологии «owns→stakeholder».
        // Нужда — много носителей (истина 24.09): запись держит их списком
        // ссылок `stakeholders`, зеркалящим связи owns.
        val область = Area.Project(проект)
        val названные = (телоСырое.path("owners").takeIf { it.isArray }?.map { it.asText("") }.orEmpty() +
            listOf(телоСырое.path("owner").asText(""), телоСырое.path("stakeholder").asText("")))
            .map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val носители = названные.map { имя ->
            store.byCode(область, имя) ?: store.byId(имя)
                ?: throw IllegalArgumentException("стейкхолдер «$имя» не найден: нужде нужен носитель")
        }.distinctBy { it.id }
        val тело = телоСырое.deepCopy<ObjectNode>().apply {
            remove(listOf("owners", "stakeholder"))
            if (носители.isNotEmpty()) {
                put("owner", носители.first().code)
                putArray("stakeholders").also { м -> носители.forEach { м.add(it.id) } }
            }
        }
        ворота(проект, "need", тело, mapOf("owns" to listOf("owner", "stakeholders")))?.let { return it }
        val ответ = завести(проект, "need", "3", тело)
        // Носитель нужды — обязательная связь: нужда без стейкхолдера повиснет
        // и на выходе сцены 3, и в матрице покрытия.
        носители.forEach { сторона ->
            links.link("owns", сторона.id, ответ.body.path("id").asText(), Provenance(Channel.MANUAL, автор(тело)))
        }
        return ответ
    }

    /**
     * Носитель уже заведённой нужде. Нужда — много носителей (истина 24.09):
     * названная сторона ДОБАВЛЯЕТСЯ к носителям; `remove: true` снимает её;
     * `replace: true` делает единственной. Поле `stakeholders` зеркалит связи.
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
        val провенанс = Provenance(Channel.MANUAL, автор(тело))
        val снять = тело.path("remove").asBoolean(false)
        val заменить = тело.path("replace").asBoolean(false)
        val было = links.to(нужда.id, "owns")
        if (снять) {
            require(было.size > 1 || было.none { it.from == сторона.id }) { "у нужды обязан остаться хотя бы один носитель" }
            было.filter { it.from == сторона.id }.forEach { links.unlink(it.id, провенанс) }
        } else {
            if (заменить) было.filter { it.from != сторона.id }.forEach { links.unlink(it.id, провенанс) }
            if (было.none { it.from == сторона.id }) {
                links.link("owns", сторона.id, нужда.id, провенанс, rationale = тело.path("rationale").asText("").ifBlank { null })
            }
        }
        val носители = links.to(нужда.id, "owns").map { it.from }.distinct()
        val документ = (store.byId(нужда.id) ?: нужда).doc.deepCopy<ObjectNode>()
        документ.remove("stakeholder")
        документ.putArray("stakeholders").also { м -> носители.forEach { м.add(it) } }
        store.update(нужда.id, документ, провенанс)
        val коды = носители.mapNotNull { store.byId(it)?.code }
        return V2Router.Ответ(200, mapper.createObjectNode()
            .put("need", нужда.code).put("owner", сторона.code)
            .also { у -> у.putArray("owners").also { м -> коды.forEach { м.add(it) } } })
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
    private fun ограничение(проект: String, телоСырое: JsonNode): V2Router.Ответ {
        // Экран сцены 5 и прежние пробы шлют «text» и «category»; у вида
        // «ограничение» по истине поля зовутся `statement` и `type`. Сторож
        // записи чужое имя отбивает, поэтому прежнее имя переводится ЗДЕСЬ,
        // а значение перечня приводится к коду истины («техническое» → technical).
        val тело = телоСырое.deepCopy<ObjectNode>().apply {
            if (has("text") && !has("statement")) set<JsonNode>("statement", remove("text"))
            if (has("category") && !has("type")) set<JsonNode>("type", remove("category"))
        }
        orbita.kernel.schema.Enums.нормализовать("constraint", тело)
        // У рамки связь условная — «normative_basis if obligation»: основание
        // спрашивается только у регуляторной, и условие читает сами ворота.
        ворота(проект, "constraint", тело)?.let { return it }
        val область = Area.Project(проект)
        val занято = store.list(область, "constraint").mapNotNull {
            Regex("^Р(\\d+)$").find(it.code)?.groupValues?.get(1)?.toIntOrNull()
        }
        val код = тело.path("code").asText("").ifBlank { "Р${(занято.maxOrNull() ?: 0) + 1}" }
        val документ = тело.deepCopy().apply {
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
        // Материал правится РАДИ РОЛИ: документ, загруженный до появления
        // ролей, иначе нельзя прочитать как устав — пришлось бы грузить его
        // заново вторым экземпляром. Текст и канон правке не подлежат: их
        // стережёт та же проверка полей по истине схем.
        "material",
        // Базовый вариант правится РАДИ СПИСКОВ: отклонённые варианты и
        // отказы от объёма (descopes) копятся по ходу сцены 7, а называть их
        // разом в миг выбора нельзя — §9 отчёта наполнить было нечем.
        "baseline_concept",
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
        val спец = runCatching { orbita.kernel.schema.GeneratedKinds.of(запись.kind) }.getOrNull()
        val схема = спец?.fields.orEmpty()
        val обязательные = спец?.requiredFields.orEmpty()
        val документ = запись.doc.deepCopy<ObjectNode>()
        var изменено = 0
        поля.fields().forEach { (имя, значение) ->
            require(имя !in setOf("code", "kind", "id")) { "поле «$имя» не правится: код и вид стабильны" }
            require(схема.isEmpty() || имя in схема || имя in setOf("notes", "tags")) {
                "поля «$имя» у вида ${запись.kind} нет: схема знает " + (схема + listOf("notes", "tags")).joinToString(" · ")
            }
            val снимают = значение.isNull || (значение.isTextual && значение.asText().isBlank())
            // Обязательное поле правкой НЕ снимается: его меняют. Пустое
            // значение здесь означало бы «у нужды больше нет класса
            // обслуживания» — а по истине схем нужды без класса не бывает,
            // и снять его молча значило бы потерять и самого ответственного
            // за TBR. Необязательное поле пустым по-прежнему снимается.
            require(!(снимают && имя in обязательные)) {
                "поле «$имя» у вида ${запись.kind} обязательно — его меняют, а не снимают: " +
                    "пришлите значение вместо пустого"
            }
            val было = документ.get(имя)
            if (снимают) документ.remove(имя) else документ.set<JsonNode>(имя, значение)
            if (было != документ.get(имя)) изменено += 1
        }
        // Сторож перечислений (журнал ПМИ-7, З-11): значение вне перечня истины
        // не записывается. Экран показывает русские значения (`enum_labels`,
        // 19.09) и ими же возвращает — русское имя узнаётся и ложится кодом;
        // неузнанное отбивается словами, а не чистится молча: правка пришла от
        // человека, и молчание он прочтёт как «сохранилось».
        // Сначала ОТКАЗ, потом приведение: чистка вперёд проверки съедала бы
        // неузнанное значение молча, и человек прочёл бы это как «сохранилось».
        val правимыеПоля = поля.properties().map { it.key }.toSet()
        orbita.kernel.schema.Enums.проверить(запись.kind, документ, правимыеПоля).let { отказы ->
            require(отказы.isEmpty()) { отказы.joinToString("; ") }
        }
        val перечни = orbita.kernel.schema.Enums.нормализовать(запись.kind, документ)
        // Приведение — тоже правка: без этого счёта «категория: характеристика»
        // равна прежнему значению, `изменено` остаётся нулём, и запись не
        // сохраняется — лечение молча не доезжало до хранилища.
        изменено += перечни.size
        // Интересы стороны правятся одной строкой («;» между интересами):
        // цитаты и основания нетронутых интересов остаются от прежней версии.
        if (запись.kind == "stakeholder" && orbita.kernel.schema.Interests.ПОЛЕ in правимыеПоля) {
            orbita.kernel.schema.Interests.нормализовать(документ, прежние = запись.doc)
        }
        val словаПеречней = перечни.joinToString("; ") { it.словами }

        // Вычисляемое поле пересчитывается при правке его источника: влияние
        // стороны считает СИСТЕМА из роли, и после смены роли оно обязано
        // сойтись само (журнал ПМИ-7, З-01). Названное человеком не трогаем:
        // истина говорит «инженер правит на месте».
        if (запись.kind == orbita.knowledge.api.Influence.CONCEPT && поля.has(orbita.knowledge.api.Influence.FROM)) {
            val прежнее = запись.doc.path(orbita.knowledge.api.Influence.FIELD).asText("")
            val былоПоРоли = orbita.knowledge.api.Influence.of(запись.doc.path(orbita.knowledge.api.Influence.FROM).asText(""))
            if (прежнее.isBlank() || прежнее == былоПоРоли) {
                документ.remove(orbita.knowledge.api.Influence.FIELD)
                if (orbita.knowledge.api.Influence.fillIfMissing(документ)) изменено += 1
            }
        }
        if (изменено == 0) return V2Router.Ответ(200, mapper.createObjectNode().put("code", код).put("version", запись.version).put("changed", 0))
        val причина = тело.path("reason").asText("").ifBlank { null }
        val кем = "правка инженера: " + автор(тело) + (причина?.let { " — $it" } ?: "")
        val новая = store.update(запись.id, документ, Provenance(Channel.MANUAL, кем))
        return V2Router.Ответ(
            200,
            mapper.createObjectNode().put("id", новая.id).put("code", новая.code)
                .put("version", новая.version).put("changed", изменено)
                .put("note", словаПеречней),
        )
    }

    /**
     * Вид для экрана: поля с русскими именами, стадии, перечни со значениями.
     *
     * Один ответ на весь экран: реестр показывает уровень, категорию, статус и
     * шаблон EARS человеческими словами, а форма правки собирается по стадиям
     * (`required_at`) — не списком в коде клиента.
     */
    private fun видДляЭкрана(код: String): V2Router.Ответ {
        val вид = orbita.kernel.schema.GeneratedKinds.byCode[код]
            ?: throw NoSuchElementException("вида «$код» в истине схем нет")
        val узел = mapper.createObjectNode().put("code", вид.code).put("title", вид.title)
            .put("status_model", вид.statusModel ?: "")
        fun массив(имя: String, значения: List<String>) = узел.putArray(имя).also { м -> значения.forEach(м::add) }
        массив("fields", вид.fields)
        массив("required", вид.requiredFields)
        массив("measures", вид.measures)
        val подписи = узел.putObject("labels")
        вид.labels.forEach { (поле, имя) -> подписи.put(поле, имя) }
        val стадии = узел.putObject("required_at")
        вид.requiredAt.forEach { (поле, правило) -> стадии.put(поле, правило) }
        // Примечание поля словами истины: «обязателен для performance» у
        // показателя. Без него необязательное поле выглядит долгом (владелец
        // 19.09: «и какой показатель тут можно поставить?»).
        val примечания = узел.putObject("notes")
        вид.notes.forEach { (поле, слова) -> примечания.put(поле, слова) }
        val операторы = узел.putObject("measure_ops")
        orbita.kernel.schema.GeneratedKinds.measureOps.forEach { (код, знак) -> операторы.put(код, знак) }
        val перечни = узел.putObject("enums")
        вид.enums.forEach { (поле, значения) -> перечни.putArray(поле).also { м -> значения.forEach(м::add) } }
        val значения = узел.putObject("enum_labels")
        // Статус записи среди перечислений истины назван наравне с полями —
        // реестр показывает «черновик», а не Draft.
        // Пути ВЛОЖЕННЫХ перечислений («states.kind») идут наравне с полями:
        // истина 20.09 адресует их тем же путём, и метки у них свои.
        (вид.fields + вид.enums.keys + "status").distinct().forEach { поле ->
            val метки = orbita.kernel.schema.Enums.значения(вид.code, поле)
            if (метки.isEmpty()) return@forEach
            val узелПоля = значения.putObject(поле)
            метки.forEach { (значение, имя) -> узелПоля.put(значение, имя) }
        }
        return V2Router.Ответ(200, узел)
    }

    /**
     * Единицы справочника для выбора: код записи и как его читать человеку.
     *
     * Пусто — значит справочник на стенд не засеян; экран говорит это словами,
     * а не молчит пустым списком. Своего перечня единиц у кода нет: единицы
     * ведёт справочник (полка LIB).
     */
    private fun единицы(): V2Router.Ответ {
        val все = units?.invoke().orEmpty()
        val узел = mapper.createObjectNode()
        val массив = узел.putArray("items")
        все.toSortedMap().forEach { (код, показ) -> массив.addObject().put("code", код).put("label", показ) }
        узел.put("count", все.size)
        if (все.isEmpty()) {
            узел.put("why", "справочник единиц пуст: внесите его на полку LIB — величина без единицы не бывает")
        }
        return V2Router.Ответ(200, узел)
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
