// Мостик ведущего и рабочий лист специалиста (шип 2, экран 6).
//
// РЕЖИМЫ-РАБОТЫ-ДВУХ-РОЛЕЙ: у продукта два входа, и оба — не разделы, а
// ОЧЕРЕДИ. Ведущий не открывает реестр ради обзора: маршрут фазы, очередь
// решений (≤ 7), блокирующие ближайшей точки, загрузка команды и три-четыре
// сигнала приходят одним ответом. Специалист видит только свои поручения по
// сроку и одно открытое мероприятие.
//
// Домена здесь нет — и это главное правило файла (ТЗ-BACKEND §3). Маршрут ни
// одного числа не выводит сам: состояние сцен и точек считает движок, уровень
// риска и разрывы TRL — программатика, свёртку с рамкой — модели, покрытие —
// постановка. Чего в этих данных НЕТ, того нет и в ответе: пустой проект не
// рисует нулей, потому что выдуманный ноль читается как измеренный.
//
// Единственное, что маршрут считает сам, — КАЛЕНДАРЬ: дней до точки и
// просрочка. Это тоже решение сервера, а не экрана: на клиенте вердиктов из
// сравнения величин не бывает (сторож tools/validate_web_no_math.py).
package orbita.api.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.api.api.Actor
import orbita.formulation.api.Formulation
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.kernel.api.Provenance
import orbita.kernel.schema.GeneratedKinds
import orbita.models.api.Models
import orbita.process.api.GateView
import orbita.process.api.PhaseView
import orbita.process.api.ProcessEngine
import orbita.process.api.SceneState
import orbita.process.api.SceneView
import orbita.programmatics.api.Programmatics
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class BridgeRoutes(
    private val store: EntityStore,
    private val engine: ProcessEngine,
    private val mapper: ObjectMapper,
    /** Матрица покрытия — сигнал «нужды с покрытием»; null: сигнала нет. */
    private val formulation: Formulation? = null,
    /** Программатика — сигналы «риски ≥ 12» и «разрывы TRL без пакета созревания». */
    private val programmatics: Programmatics? = null,
    /** Свёртки с рамками проекта — сигнал массы; null: числа массы не будет. */
    private val models: Models? = null,
    /**
     * Сегодня. Параметром, потому что срок и просрочка — решение сервера, а
     * тест не имеет права зависеть от календаря машины.
     */
    private val today: () -> LocalDate = { LocalDate.now() },
) {

    private val закрытие = Regex("/v2/assignments/([A-Za-z0-9_-]+)/done")

    /** Виды цели поручения — из истины схем, а не второй копией перечня в коде. */
    private val видыЦели: List<String> =
        GeneratedKinds.byCode["assignment"]?.enums?.get("target.kind") ?: listOf("gap", "activity")

    fun handle(method: String, path: String, query: Map<String, String>, body: String?, actor: Actor?): V2Router.Ответ? =
        when {
            method == "GET" && path == "/v2/bridge" -> мостик(требуется(query))
            method == "GET" && path == "/v2/assignments" -> перечень(требуется(query), query["login"])
            method == "POST" && path == "/v2/assignments" -> поручить(требуется(query), разобрать(body), actor)
            method == "POST" && закрытие.matches(path) ->
                завершить(требуется(query), закрытие.matchEntire(path)!!.groupValues[1], actor)
            method == "GET" && path == "/v2/mywork" -> мояРабота(требуется(query), query["login"])
            else -> null
        }

    // ——— мостик ————————————————————————————————————————————————————————

    private fun мостик(проект: String): V2Router.Ответ {
        val фаза = engine.view(проект)
        val точка = ближайшая(фаза)
        val поручения = поручения(проект)
        val ответ = mapper.createObjectNode()
        ответ.put("project", проект)
        ответ.set<JsonNode>("route", маршрут(фаза, точка))
        val очередь = очередь(проект, фаза, точка)
        val видимые = ответ.putArray("decide")
        очередь.take(ПОТОЛОК_ОЧЕРЕДИ).forEach { видимые.add(it) }
        // Что не влезло — числом, а не молчанием: семь строк это правило
        // экрана, а не вся работа ведущего.
        ответ.put("decide_more", maxOf(0, очередь.size - ПОТОЛОК_ОЧЕРЕДИ))
        ответ.set<JsonNode>("blockers", блокеры(фаза, поручения))
        ответ.set<JsonNode>("team", команда(проект, фаза, поручения))
        ответ.set<JsonNode>("signals", сигналы(проект, точка))
        // Точки фазы — пикеру срока у кнопки «Поручить»: срок поручения это
        // точка, и её выбирают из точек ЭТОЙ фазы, а не из списка в коде.
        val точки = ответ.putArray("points")
        фаза.gates.filter { !it.passed }.forEach { т ->
            точки.addObject().put("key", т.key).put("title", т.title)
                .put("planned_date", т.plannedDate ?: "")
        }
        return V2Router.Ответ(200, ответ)
    }

    /**
     * Ближайшая точка — непройденная с самой ранней плановой датой; без дат —
     * первая по порядку шаблона. Пройденные точки не «ближайшие» никогда: у
     * них решение уже записано.
     */
    private fun ближайшая(фаза: PhaseView): GateView? =
        фаза.gates.filter { !it.passed }
            .minWithOrNull(compareBy({ it.plannedDate?.ifBlank { null } ?: "9999-12-31" }, { it.order }))

    private fun маршрут(фаза: PhaseView, точка: GateView?): ObjectNode {
        val узел = mapper.createObjectNode()
        узел.put("phase", фаза.phase)
        val сцена = фаза.scenes.firstOrNull { it.key == фаза.currentScene }
            ?: фаза.scenes.firstOrNull { it.state == SceneState.OPEN }
            ?: фаза.scenes.firstOrNull()
        узел.putObject("scene").put("key", сцена?.key ?: "").put("title", сцена?.title ?: "")
        val т = узел.putObject("gate")
        т.put("key", точка?.key ?: "").put("title", точка?.title ?: "")
        т.put("planned_date", точка?.plannedDate ?: "")
        т.put("blocking", точка?.blocking?.size ?: 0)
        val дней = дней(точка?.plannedDate)
        if (дней == null) узел.putNull("days_to_gate") else узел.put("days_to_gate", дней)
        return узел
    }

    /**
     * Очередь решений — из того, ЧТО ЕСТЬ В ДАННЫХ проекта: непринятые
     * предложения синтеза, допущения к точке, замечания обзора, темы поля без
     * адреса, держащаяся точка. Порядок — цена ошибки: противоречие постановки
     * дороже незакрытой темы.
     */
    private fun очередь(проект: String, фаза: PhaseView, точка: GateView?): List<ObjectNode> {
        val область = Area.Project(проект)
        val строки = mutableListOf<ObjectNode>()

        // 1. Постановка из поля: предложения, по которым человек не решил.
        val ждут = store.list(область, "proposal").filter { it.status == "pending" }
        if (ждут.isNotEmpty()) {
            val запуск = store.list(область, "synthesis_run")
                .filter { з -> з.doc.path("tags").any { it.asText() == "synthesis" } }
                .maxByOrNull { it.code }
            строки += строка(
                "постановка",
                "Из поля: " + (запуск?.let { слова(it) } ?: "предложений без решения ${ждут.size}"),
                "Разобрать", "knowledge", код = запуск?.code,
            )
        }

        // 2. Допущения: диспозиция `assumed` требует подтверждения К ТОЧКЕ —
        // владелец и точка лежат в самом факте (истина: fact.assumption).
        store.list(область, "fact")
            .filter { it.doc.path("disposition").asText("") == "assumed" }
            .filter { точка == null || it.doc.path("assumption").path("confirm_by").asText("") == точка.key }
            .forEach { факт ->
                val владелец = факт.doc.path("assumption").path("owner").asText("")
                строки += строка(
                    "допущение",
                    "«${предмет(факт)}» — к «${точка?.title ?: "ближайшей точке"}»" +
                        (if (владелец.isBlank()) "" else ", владелец $владелец"),
                    "Подтвердить", "knowledge", код = факт.code,
                )
            }

        // 3. Замечания обзора: каждое возвращает сцену в работу — решение там.
        store.list(область, "finding").filter { it.status == "open" }.forEach { замечание ->
            val сцена = замечание.doc.path("returns_to_scene").asText("")
            строки += строка(
                "замечание",
                "Обзор: «${замечание.doc.path("text").asText("")}» — сцена $сцена возвращена",
                "Открыть", "work", сцена = сцена, код = замечание.code,
            )
        }

        // 4. Темы поля без адреса: факты приняты, а сущности, о которой они,
        // ещё нет — это вопрос человеку, а не работа службы.
        store.list(область, "topic")
            .filter { it.status != "resolved" && it.doc.path("resolved_to").asText("").isBlank() }
            .filter { it.doc.path("merged_into").asText("").isBlank() }
            .forEach { тема ->
                строки += строка(
                    "тема",
                    "Тема «${тема.doc.path("label").asText(тема.code)}» без адреса: чем она станет?",
                    "Решить", "knowledge", код = тема.code,
                )
            }

        // 5. Сама точка: фиксация недоступна, пока её держат критерии.
        if (точка != null && точка.blocking.isNotEmpty()) {
            строки += строка(
                "точка",
                "${точка.title}: блокирующих ${точка.blocking.size} — фиксация недоступна",
                "Что блокирует", "points", код = точка.key,
            )
        }
        return строки
    }

    /** Диф запуска синтеза словами: «12 новых, 3 дополнения, 2 противоречия». */
    private fun слова(запуск: Entity): String {
        val диф = запуск.doc.path("diff")
        val части = listOf("new" to "новых", "augment" to "дополнений", "contradict" to "противоречий")
            .mapNotNull { (ключ, слово) ->
                диф.path(ключ).size().takeIf { it > 0 }?.let { "$it $слово" }
            }
        return части.joinToString(", ").ifBlank { "диф пуст, а предложения ещё открыты" }
    }

    /** Предмет факта для строки очереди: предикат со значением, а не код. */
    private fun предмет(факт: Entity): String {
        val предикат = факт.doc.path("predicate").asText("")
        val значение = факт.doc.path("value").let { if (it.isValueNode) it.asText("") else it.path("value").asText("") }
        return listOf(предикат, значение).filter { it.isNotBlank() }.joinToString(": ")
            .ifBlank { факт.doc.path("quote").asText(факт.code) }
    }

    private fun строка(
        вид: String,
        текст: String,
        действие: String,
        раздел: String,
        сцена: String? = null,
        код: String? = null,
    ): ObjectNode {
        val узел = mapper.createObjectNode()
        узел.put("kind", вид).put("text", текст).put("action", действие)
        val куда = узел.putObject("where").put("section", раздел)
        сцена?.takeIf { it.isNotBlank() }?.let { куда.put("scene", it) }
        код?.takeIf { it.isNotBlank() }?.let { куда.put("code", it) }
        return узел
    }

    /**
     * Что блокирует ближайшую точку — СЛОВАМИ движка: разрывы сцен, которые
     * ещё не прожиты. Своего списка причин у маршрута нет: он бы разошёлся с
     * тем, что держит точку на самом деле.
     */
    private fun блокеры(фаза: PhaseView, поручения: List<Entity>): ArrayNode {
        val массив = mapper.createArrayNode()
        фаза.scenes.filter { it.state != SceneState.DONE }.forEach { сцена ->
            сцена.blockers.forEach { причина ->
                val узел = массив.addObject()
                узел.put("scene", сцена.key)
                узел.put("scene_title", сцена.title)
                узел.put("why", причина)
                // Закрытая сцена ждёт входа: срок по ней не тикает, и это видно.
                узел.put("waiting", сцена.state == SceneState.LOCKED)
                своё(поручения, сцена.key, причина)?.let { узел.set<JsonNode>("assignment", коротко(it)) }
            }
        }
        return массив
    }

    /** Поручение по ЭТОМУ разрыву: цель — сцена, слова — те же. */
    private fun своё(поручения: List<Entity>, сцена: String, причина: String): Entity? =
        поручения.firstOrNull { п ->
            п.status != "done" && п.doc.path("target").path("ref").asText("") == сцена &&
                п.doc.path("notes").asText("").let { it.isBlank() || it == причина }
        }

    private fun коротко(поручение: Entity): ObjectNode = mapper.createObjectNode()
        .put("code", поручение.code)
        .put("assignee", поручение.doc.path("assignee").asText(""))
        .put("due_point", поручение.doc.path("due_point").asText(""))

    /** Команда: кто · что · срок · просрочено. Перепоручения нет — есть «Закрыть». */
    private fun команда(проект: String, фаза: PhaseView, поручения: List<Entity>): ArrayNode {
        val массив = mapper.createArrayNode()
        поручения.sortedWith(порядок(проект)).forEach { массив.add(строкаПоручения(проект, фаза, it)) }
        return массив
    }

    /**
     * Сигналы — из СУЩЕСТВУЮЩИХ свёрток: масса с рамкой (модели), риски ≥ 12 и
     * разрывы TRL без пакета созревания (программатика), доля нужд с покрытием
     * (постановка). Свёртки нет — сигнала нет: ноль, которого никто не мерил,
     * на мостике опаснее пустого места.
     */
    private fun сигналы(проект: String, точка: GateView?): ArrayNode {
        val массив = mapper.createArrayNode()

        // Масса с системным резервом против рамки проекта. Слова вывода —
        // модели: «с системным 20 % — 122.8 кг, превышение 22.8 кг».
        val ступень = точка?.key ?: "MCR"
        val рамка = models?.let { м -> runCatching { м.budget(проект, "mass", ступень) }.getOrNull() }
            ?.frame?.firstOrNull()
        if (рамка != null) {
            массив.add(
                сигнал(
                    "масса с резервом · ${рамка.constraint} ${число(рамка.limit)} ${рамка.unit}",
                    "${число(рамка.actual)} ${рамка.unit}",
                    предел = "${число(рамка.limit)} ${рамка.unit}",
                    заРамкой = !рамка.within,
                    раздел = "models",
                    помета = рамка.words,
                ),
            )
        }

        formulation?.coverage(проект)?.takeIf { it.total > 0 }?.let { матрица ->
            val доля = матрица.covered * 100 / матрица.total
            массив.add(
                сигнал(
                    "нужды с покрытием", "$доля %",
                    предел = null, заРамкой = матрица.covered < матрица.total,
                    раздел = "formulation",
                    помета = "покрыто ${матрица.covered} из ${матрица.total}",
                ),
            )
        }

        programmatics?.let { прог ->
            val созревание = runCatching { прог.maturationState(проект) }.getOrElse { emptyList() }
                .filter { it.trlCurrent < it.trlRequired && it.packageCode == null }
            if (созревание.isNotEmpty()) {
                массив.add(
                    сигнал(
                        "разрывы TRL без пакета созревания", "${созревание.size}",
                        предел = "0", заРамкой = true, раздел = "work", сцена = "10",
                        помета = созревание.joinToString(" · ") { it.technology },
                    ),
                )
            }
            val риски = runCatching { прог.risks(проект) }.getOrElse { emptyList() }
                .filter { it.status == "open" && it.level >= ПОРОГ_РИСКА }
            if (риски.isNotEmpty()) {
                массив.add(
                    сигнал(
                        "риски ≥ $ПОРОГ_РИСКА без решения", "${риски.size}",
                        предел = "0", заРамкой = true, раздел = "risks",
                        помета = риски.joinToString(" · ") { it.code },
                    ),
                )
            }
        }
        // Три-четыре числа — правило экрана: пятое уже не смотрят каждый день.
        while (массив.size() > ПОТОЛОК_СИГНАЛОВ) массив.remove(массив.size() - 1)
        return массив
    }

    private fun сигнал(
        имя: String,
        значение: String,
        предел: String?,
        заРамкой: Boolean,
        раздел: String,
        сцена: String? = null,
        помета: String = "",
    ): ObjectNode {
        val узел = mapper.createObjectNode()
        узел.put("name", имя).put("value", значение)
        if (предел == null) узел.putNull("limit") else узел.put("limit", предел)
        узел.put("over", заРамкой)
        узел.put("note", помета)
        val куда = узел.putObject("where").put("section", раздел)
        сцена?.let { куда.put("scene", it) }
        return узел
    }

    /** Число свёртки для экрана: «122,8», а не «122.80000000000001». */
    private fun число(значение: Double): String =
        if (значение == значение.toLong().toDouble()) значение.toLong().toString()
        else String.format("%.1f", значение).replace('.', ',')

    // ——— поручения ————————————————————————————————————————————————————

    private fun поручения(проект: String): List<Entity> = store.list(Area.Project(проект), "assignment")

    private fun поручить(проект: String, тело: JsonNode, actor: Actor?): V2Router.Ответ {
        val область = Area.Project(проект)
        val цель = тело.path("target")
        val вид = цель.path("kind").asText("").trim()
        require(вид in видыЦели) {
            "цель поручения бывает «${видыЦели.joinToString("» либо «")}»; получено «$вид»"
        }
        val ссылка = цель.path("ref").asText("").trim()
        require(ссылка.isNotBlank()) { "поручение без цели не ставится: назовите сцену либо шаг" }
        val исполнитель = тело.path("assignee").asText("").trim()
        require(исполнитель.isNotBlank()) { "поручение без исполнителя не ставится: выберите учётку" }
        val срок = тело.path("due_point").asText("").trim()
        require(срок.isNotBlank()) { "поручение без срока не ставится: срок — точка фазы" }
        val фаза = engine.view(проект)
        // Срок — точка ФАЗЫ или веха проекта: выдуманная точка сроком не станет.
        val точки = (фаза.gates.map { it.key } + store.list(область, "gate").map { it.code }).toSet()
        require(срок in точки) {
            "точки «$срок» в проекте нет: срок поручения — одна из ${точки.sorted().joinToString(" · ")}"
        }
        val поручил = (actor?.login ?: тело.path("author").asText("")).trim()
        require(поручил.isNotBlank()) { "поручение без автора не ставится: кто поручил — часть поручения" }
        // Поручение указывает на мероприятие (истина 24.09, ОНТОЛОГИЯ-АУДИТ часть 5):
        // «шаг» ушёл, целью бывает разрыв сцены либо мероприятие фазы кодом.
        if (вид == "activity") {
            val мероприятия = фаза.scenes.flatMap { с -> с.activities.map { it.code } }
            require(ссылка in мероприятия) {
                "мероприятия «$ссылка» в фазе нет: поручение указывает на мероприятие (${мероприятия.joinToString(" · ")})"
            }
        }
        if (вид == "gap") {
            require(фаза.scenes.any { it.key == ссылка }) {
                "сцены «$ссылка» в фазе нет: разрыв живёт в сцене (${фаза.scenes.joinToString(" · ") { it.key }})"
            }
        }

        val номер = store.list(область, "assignment").count() + 1
        val документ = mapper.createObjectNode()
        документ.putObject("target").put("kind", вид).put("ref", ссылка)
        документ.put("assignee", исполнитель)
        документ.put("due_point", срок)
        документ.put("assigned_by", поручил)
        // Слова поручения — в `notes`, поле ЯДРА: истина схем полей у вида
        // «задание» ровно четыре, и пятое сторож записи отбил бы (и правильно).
        тело.path("what").asText("").trim().takeIf { it.isNotBlank() }?.let { документ.put("notes", it) }
        val запись = store.create(
            "AS-" + номер.toString().padStart(4, '0'), "assignment", область,
            if (вид == "gap") ссылка else null, документ,
            Provenance(Channel.MANUAL, actor?.name ?: поручил), status = "open",
        )
        return V2Router.Ответ(201, строкаПоручения(проект, фаза, запись))
    }

    private fun перечень(проект: String, логин: String?): V2Router.Ответ {
        val фаза = engine.view(проект)
        val ответ = mapper.createObjectNode()
        val массив = ответ.putArray("items")
        поручения(проект)
            .filter { логин == null || it.doc.path("assignee").asText("") == логин }
            .sortedWith(порядок(проект))
            .forEach { массив.add(строкаПоручения(проект, фаза, it)) }
        ответ.put(
            "note",
            if (массив.isEmpty()) "поручений нет: их ставит ведущий с мостика — от разрыва, который держит точку"
            else "срок поручения — точка фазы; просрочка считается по её дате",
        )
        return V2Router.Ответ(200, ответ)
    }

    private fun завершить(проект: String, код: String, actor: Actor?): V2Router.Ответ {
        val область = Area.Project(проект)
        val запись = store.byCode(область, код)?.takeIf { it.kind == "assignment" }
            ?: throw NoSuchElementException("поручения «$код» в проекте нет")
        val фаза = engine.view(проект)
        if (запись.status == "done") return V2Router.Ответ(200, строкаПоручения(проект, фаза, запись))
        // Отчёта специалист не пишет: закрытие — одно движение, а кем оно
        // сделано, помнит провенанс версии, не выдуманное поле документа.
        val закрытое = store.update(
            запись.id, запись.doc,
            Provenance(Channel.MANUAL, actor?.name ?: "стенд"), status = "done",
        )
        return V2Router.Ответ(200, строкаПоручения(проект, фаза, закрытое))
    }

    /** Порядок поручений: просроченные первыми, дальше по дате срока и коду. */
    private fun порядок(проект: String): Comparator<Entity> =
        compareBy({ дата(проект, it.doc.path("due_point").asText("")) ?: "9999-12-31" }, { it.code })

    private fun строкаПоручения(проект: String, фаза: PhaseView, поручение: Entity): ObjectNode {
        val узел = mapper.createObjectNode()
        val цель = поручение.doc.path("target")
        val ссылка = цель.path("ref").asText("")
        val сцена = фаза.scenes.firstOrNull { it.key == ссылка }
        узел.put("code", поручение.code)
        узел.put("status", поручение.status)
        узел.put("done", поручение.status == "done")
        узел.putObject("target").put("kind", цель.path("kind").asText("")).put("ref", ссылка)
        узел.put("scene", ссылка)
        узел.put("scene_title", сцена?.title ?: "")
        узел.put("assignee", поручение.doc.path("assignee").asText(""))
        узел.put("assigned_by", поручение.doc.path("assigned_by").asText(""))
        val срок = поручение.doc.path("due_point").asText("")
        узел.put("due_point", срок)
        узел.put("due_point_title", точкаИмя(проект, фаза, срок))
        val дата = дата(проект, срок)
        узел.put("due_date", дата ?: "")
        // Просрочка — решение сервера: закрытое поручение не просрочено никогда.
        узел.put("overdue", поручение.status != "done" && дата != null && дата < today().toString())
        узел.put(
            "what",
            поручение.doc.path("notes").asText("").ifBlank {
                сцена?.let { "сцена ${it.key} · ${it.title}" } ?: ссылка
            },
        )
        // «Что мешает» — ЖИВЫЕ разрывы сцены: слова поручения годовой давности
        // могли закрыться, а сцена — нет.
        val мешает = узел.putArray("what_blocks")
        сцена?.blockers?.forEach { мешает.add(it) }
        return узел
    }

    private fun точкаИмя(проект: String, фаза: PhaseView, ключ: String): String =
        фаза.gates.firstOrNull { it.key == ключ }?.title
            ?: store.byCode(Area.Project(проект), ключ)?.doc?.path("title")?.asText("").orEmpty()

    /** Дата точки: план фазы кладёт её в саму точку, второго источника нет. */
    private fun дата(проект: String, ключ: String): String? {
        if (ключ.isBlank()) return null
        return store.byCode(Area.Project(проект), ключ)?.doc
            ?.let { it.path("decided_at").asText("").ifBlank { it.path("planned_date").asText("") } }
            ?.ifBlank { null }
    }

    private fun дней(дата: String?): Int? {
        val срок = дата?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { ChronoUnit.DAYS.between(today(), LocalDate.parse(срок)).toInt() }.getOrNull()
    }

    // ——— моя работа ————————————————————————————————————————————————————

    private fun мояРабота(проект: String, логин: String?): V2Router.Ответ {
        require(!логин.isNullOrBlank()) { "укажите ?login=<учётка>: рабочий лист всегда чей-то" }
        val фаза = engine.view(проект)
        val мои = поручения(проект)
            .filter { it.doc.path("assignee").asText("") == логин && it.status != "done" }
            .sortedWith(порядок(проект))
        val ответ = mapper.createObjectNode()
        ответ.put("project", проект)
        val массив = ответ.putArray("assignments")
        мои.forEach { массив.add(строкаПоручения(проект, фаза, it)) }
        val открыто = открытое(фаза, мои)
        if (открыто == null) ответ.putNull("open_activity") else ответ.set<JsonNode>("open_activity", открыто)
        ответ.put(
            "note",
            if (мои.isEmpty()) "поручений нет: ведущий поставит их с мостика — от разрыва, который держит точку"
            else "закрыли — ведущий увидит сам: отчёта писать не нужно",
        )
        return V2Router.Ответ(200, ответ)
    }

    /**
     * Одно открытое мероприятие — то, где стоит работа первого по сроку
     * поручения. Поверхность мероприятия живёт в разделе «Работа»: здесь
     * только адрес, по которому специалист туда уходит.
     */
    private fun открытое(фаза: PhaseView, мои: List<Entity>): ObjectNode? {
        мои.forEach { поручение ->
            val ссылка = поручение.doc.path("target").path("ref").asText("")
            val сцена: SceneView = фаза.scenes.firstOrNull { it.key == ссылка }
                ?: фаза.scenes.firstOrNull { с -> с.activities.any { it.code == ссылка } }
                ?: return@forEach
            val дело = сцена.activities.firstOrNull { it.code == ссылка }
                ?: сцена.activities.firstOrNull { it.state.name == "IN_PROGRESS" }
                ?: сцена.activities.firstOrNull { it.state.name == "AVAILABLE" }
                ?: сцена.activities.firstOrNull()
                ?: return@forEach
            val узел = mapper.createObjectNode()
            узел.put("scene", сцена.key)
            узел.put("scene_title", сцена.title)
            узел.putObject("activity").put("code", дело.code).put("name", дело.name)
                .put("goal", дело.goal).put("state", дело.state.name.lowercase())
            return узел
        }
        return null
    }

    // ——— общее —————————————————————————————————————————————————————————

    private fun требуется(query: Map<String, String>): String =
        query["project"]?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("укажите ?project=<код проекта>")

    private fun разобрать(тело: String?): JsonNode =
        if (тело.isNullOrBlank()) mapper.createObjectNode() else mapper.readTree(тело)

    private companion object {
        /** Семь строк — правило экрана: восьмую ведущий уже не решает сегодня. */
        const val ПОТОЛОК_ОЧЕРЕДИ = 7

        /** Три-четыре числа: пятое перестают смотреть. */
        const val ПОТОЛОК_СИГНАЛОВ = 4

        /** Критичность риска, с которой он идёт на мостик (В × П). */
        const val ПОРОГ_РИСКА = 12
    }
}
