// HTTP-слой шага 1 (STEP-1 §1.1: api = HTTP + валидация). Без внешнего
// фреймворка — jdk.httpserver достаточно для каркаса; каждый вход проходит
// границу Boundary. Метода импорта содержания документов в модель нет и не
// будет (TZ-COM-001: обратная загрузка из документов не допускается).
package orbita.com.api

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import orbita.mod.model.CoreType
import orbita.mod.model.Lifecycle
import orbita.mod.schema.SchemaValidationException
import orbita.mod.schema.ValidationError
import orbita.mod.store.BaselineChangeException
import orbita.req.BaselineBlockedException
import orbita.mod.store.CycleException
import orbita.mod.store.IdReuseException
import orbita.mod.store.ModelViolationException
import orbita.mod.store.StoredObject
import orbita.mod.store.VersionConflictException
import java.net.InetSocketAddress
import java.time.OffsetDateTime

/**
 * Идентификатор адаптера протокола, который отдаётся справочным запросом
 * `/protocol-adapter`. Это форма обмена, а не выборка из хранилища: адаптер
 * встроен в ядро, и объект модели с этим идентификатором может ещё не
 * существовать (ADR-021).
 */
private const val PROTOCOL_ADAPTER_ID = "PA-0001"

private val DEFAULT_PROFILE_KINDS = listOf(
    // цепочка постановки О2–О4 плюс операции, которые мастер предлагает сам
    "mission_to_goals",
    "mission_to_needs",
    "mission_intent_from_docs",
    "normative_to_candidates",
    "document_semantic_parse",
)

class HttpApi(private val boundary: Boundary) {

    private val mapper = ObjectMapper()

    /**
     * Запуск на 127.0.0.1; port=0 — эфемерный порт (для тестов).
     *
     * Адрес привязки переопределяется ORBITA_HTTP_BIND. В контейнере петля
     * недоступна соседям по сети, и nginx получал бы 502: единственный вход —
     * nginx, порт API наружу не публикуется, поэтому там привязка 0.0.0.0
     * (docker-compose.yml). По умолчанию — петля: в разработке API не должен
     * оказаться открытым в сеть без явного решения.
     */
    fun start(port: Int): HttpServer {
        val bind = System.getenv("ORBITA_HTTP_BIND") ?: "127.0.0.1"
        val server = HttpServer.create(InetSocketAddress(bind, port), 0)
        server.createContext("/api/") { ex -> handle(ex) }
        server.start()
        return server
    }

    private fun handle(ex: HttpExchange) {
        try {
            route(ex)
        } catch (e: ProcessTasks.AssignForbiddenException) {
            respond(ex, 403, errJson(e))
        } catch (e: SchemaValidationException) {
            respond(ex, 422, errorsJson(e.errors))
        } catch (e: GateNotReadyException) {
            // не готово — перечень незакрытого с операциями, где чинится (ADR-029)
            val body = mapper.createObjectNode()
            body.put("gate", e.gate)
            body.put("ready", false)
            body.putArray("issues").also { a -> e.issues.forEach(a::add) }
            body.putArray("operations").also { a -> e.operations.forEach(a::add) }
            respond(ex, 409, body)
        } catch (e: BatchRejectedException) {
            // пачка откатана целиком; отчёт — причины поимённо
            respond(ex, 422, batchJson(e.report))
        } catch (e: ModelViolationException) {
            respond(ex, 422, errJson(e))
        } catch (e: BaselineBlockedException) {
            val body = errJson(e)
            val reasons = body.putArray("reasons")
            e.reasons.forEach(reasons::add)
            respond(ex, 409, body)
        } catch (e: BaselineChangeException) {
            respond(ex, 409, errJson(e))
        } catch (e: BaselineEditBlockedException) {
            // Причина адресована инженеру: она попадает прямо на экран формы
            respond(ex, 409, errJson(e).put("blocked", true).put("reason", e.reason))
        } catch (e: VersionConflictException) {
            // Отказ несёт то, чем разрешают конфликт: чужая версия, её автор
            // и чужие значения тех полей, которые правились (шаг 15 §1.2)
            val body = errJson(e).put("conflict", true)
                .put("your_base", e.yourBase)
                .put("current_version", e.currentVersion)
                .put("changed_by", e.changedBy)
            val theirs = body.putObject("their_values")
            e.theirValues.forEach { (field, value) -> theirs.set<ObjectNode>(field, value) }
            val yours = body.putObject("your_values")
            e.yourValues.forEach { (field, value) -> yours.set<ObjectNode>(field, value) }
            respond(ex, 409, body)
        } catch (e: IdReuseException) {
            respond(ex, 409, errJson(e))
        } catch (e: CycleException) {
            respond(ex, 409, errJson(e))
        } catch (e: NoSuchElementException) {
            respond(ex, 404, errJson(e))
        } catch (e: JsonProcessingException) {
            respond(ex, 400, errJson(e))
        } catch (e: IllegalArgumentException) {
            respond(ex, 400, errJson(e))
        } catch (e: Exception) {
            respond(ex, 500, errJson(e))
        } finally {
            ex.close()
        }
    }

    private fun route(ex: HttpExchange) {
        val path = ex.requestURI.path.removePrefix("/api").trimEnd('/')
        val method = ex.requestMethod
        val objectMatch = Regex("^/objects/([A-Z]{2,3}-[0-9]{4})(/.*)?$").find(path)
        val editMatch = Regex("^/edit/([A-Z]{2,3}-[0-9]{4})(/.*)?$").find(path)

        // ── В3: учётки и права. Пока учёток нет — прежний однопользовательский
        // режим (мягкое включение: владелец сам решает, когда завести первую).
        // Проверка прав — ЗДЕСЬ, на сервере: спрятанная кнопка правом не
        // является (ловушка 5).
        val authOn = boundary.auth.enabled()
        val sessionUser = sessionToken(ex)?.let { boundary.auth.sessionUser(it) }
        if (path.startsWith("/auth/")) {
            authRoutes(ex, method, path, sessionUser)
            return
        }
        if (authOn && sessionUser == null) {
            respond(ex, 401, mapper.createObjectNode().put("error", "войдите: сессия не найдена или истекла"))
            return
        }
        // ADR-022: проектный контекст запроса. ?project=PJ-NNNN обязателен,
        // как только в портфеле больше одного проекта; при единственном
        // проекте вызов без параметра работает в нём (переходный режим до
        // перевёрстки клиента). null — портфель пуст.
        // проект вычисляется ЛЕНИВО: маршруты, которым он не нужен, не
        // должны получать отказ «укажите ?project» (вынос маршрутов в
        // отдельную функцию однажды это и сломал)
        val projectRef = lazy { resolveProject(ex) }
        val project: String? by projectRef

        if (authOn && sessionUser != null && method != "GET" && method != "HEAD") {
            val role = boundary.auth.roleIn(
                if (path.startsWith("/objects/project") || path == "/edit/project") null else project,
                sessionUser.login,
            )
            denyReason(method, path, role, sessionUser, project, objectMatch, editMatch)?.let { why ->
                respond(ex, 403, mapper.createObjectNode().put("error", why))
                return
            }
        }
        // автор — из учётки везде: тело может нести что угодно, провенанс
        // получает имя вошедшего
        currentAuthor.set(sessionUser?.displayName)
        currentAuthorLogin.set(sessionUser?.login)

        // поток документов вынесен отдельной функцией (предел метода JVM)
        if (routeDocuments(ex, method, path, projectRef)) return

        when {
            // Список видов выводится из состава типов, а не перечисляется руками:
            // после ADR-021 их стало пятнадцать, и забытый в регулярном выражении
            // вид означал бы объект, который модель хранит, но принять не может.
            method == "POST" && objectTypePath(path) != null -> {
                val type = objectTypePath(path)!!
                // создание проекта не требует контекста: оно контейнер и заводит
                val stored =
                    if (type == CoreType.Project) boundary.ingest(type, body(ex))
                    else boundary.ingest(type, body(ex), projectId = requireProject(project))
                // В3: создатель проекта — его руководитель
                if (type == CoreType.Project) {
                    currentAuthorLogin.get()?.let { boundary.auth.setRole(stored.id, it, "lead") }
                }
                respond(ex, 201, summary(stored))
            }

            // Перевод статуса (TZ-REQ-006): в Baseline — только зрелое требование
            // Блок E/D: массовое действие реестра — перевод статуса пачкой.
            // Каждый объект проходит ту же проверку, что и одиночный promote;
            // отчёт называет непереведённые поимённо, переведённые остаются.
            method == "POST" && path == "/objects/promote-batch" -> {
                val request = mapper.readTree(body(ex))
                val target = Lifecycle.valueOf(request.path("status").asText())
                val by = (currentAuthor.get() ?: request.path("author").asText("")).trim().takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("TZ-COM-005: field 'author' is required")
                val n = mapper.createObjectNode()
                val done = n.putArray("promoted")
                val failed = n.putArray("failed")
                request.path("ids").forEach { idNode ->
                    val id = idNode.asText()
                    try {
                        boundary.req.promote(id, target, by)
                        done.add(id)
                    } catch (e: Exception) {
                        val f = failed.addObject().put("id", id)
                        f.put("reason", (e as? BaselineBlockedException)?.reasons?.joinToString("; ")
                            ?: (e.message ?: e.javaClass.simpleName))
                    }
                }
                respond(ex, 200, n)
            }

            method == "POST" && objectMatch?.groupValues?.get(2) == "/promote" -> {
                val target = Lifecycle.valueOf(mapper.readTree(body(ex)).path("status").asText())
                respond(ex, 200, summary(boundary.req.promote(objectMatch.groupValues[1], target)))
            }

            // Процедура изменения базированного объекта (TZ-COM-003): рабочий слой
            // правки (/edit) базированный объект не трогает и отсылает сюда —
            // изменение принимается только с основанием (change_ref).
            method == "POST" && objectMatch?.groupValues?.get(2) == "/change" -> {
                val id = objectMatch.groupValues[1]
                val req = mapper.readTree(body(ex))
                val doc = req["doc"] ?: throw IllegalArgumentException("body must contain 'doc'")
                // процедура с основанием проходит прикладные правила приёма
                // (порядок дат вех — круг 2, одно правило на сервере). Схему
                // канал исторически не требует: он принимает и частичный
                // документ. Отказ «нет основания» старше отказа содержания.
                boundary.objects.current(id)?.let { cur ->
                    val changeRef = req.path("change_ref").asText("")
                    if (!(cur.status == Lifecycle.Baseline && changeRef.isBlank())) {
                        boundary.req.requireApplicationRules(cur.type, doc)
                    }
                }
                // автор изменения — из учётки/тела, когда назван (дефект:
                // канал писал все правки как «system»); без автора — прежний
                // контракт канала (внутренние вызовы), не отказ
                val changeAuthor = currentAuthor.get()
                    ?: req.path("author").asText("").trim().ifEmpty { "system" }
                val stored = boundary.objects.change(
                    id, doc, changeRef = req.path("change_ref").textValue(), createdBy = changeAuthor,
                )
                // и у процедуры с основанием связи выводятся из документа (ADR-027)
                boundary.req.syncLinks(stored.type, stored.id, stored.doc)
                respond(ex, 200, summary(stored))
            }

            method == "GET" && objectMatch?.groupValues?.get(2) == "/ancestors" ->
                respond(ex, 200, hops(boundary.links.ancestors(objectMatch.groupValues[1])))

            method == "GET" && objectMatch?.groupValues?.get(2) == "/descendants" ->
                respond(ex, 200, hops(boundary.links.descendants(objectMatch.groupValues[1])))

            method == "GET" && objectMatch != null && objectMatch.groupValues[2].isEmpty() -> {
                val stored = boundary.objects.current(objectMatch.groupValues[1])
                    ?: throw NoSuchElementException("object '${objectMatch.groupValues[1]}' not found")
                respond(ex, 200, summary(stored).apply { set<ObjectNode>("doc", stored.doc) })
            }

            // Связи trace/allocation/derive выводятся из документа (ADR-027):
            // ручное создание запрещено — два источника связей разошлись бы.
            // Verification остаётся: событие привязывается к требованию отсюда.
            method == "POST" && path == "/links" -> {
                val req = mapper.readTree(body(ex))
                val kind = req.path("kind").asText("trace")
                if (kind in setOf("trace", "allocation", "derive")) {
                    return respond(
                        ex, 409,
                        mapper.createObjectNode()
                            .put(
                                "error",
                                "связь вида '$kind' выводится из документа и вручную не создаётся: " +
                                    "укажите ссылку в самом объекте (traces_up / allocated_to / derives_from) — " +
                                    "иначе два источника связей разойдутся (ADR-027)",
                            )
                            .put("adr", "ADR-027"),
                    )
                }
                boundary.links.add(req["from"].asText(), req["to"].asText(), kind)
                respond(ex, 201, mapper.createObjectNode().put("status", "created"))
            }

            method == "POST" && path == "/param-deps" -> {
                val req = mapper.readTree(body(ex))
                boundary.params.addDependency(
                    req["object_id"].asText(), req["name"].asText(),
                    req["dep_object_id"].asText(), req["dep_name"].asText(),
                )
                respond(ex, 201, mapper.createObjectNode().put("status", "created"))
            }

            // Параметры объекта на карточку (TZ-MOD-005): предложенное ИИ и не
            // акцептованное в действующие не входит — фильтрует хранилище.
            method == "GET" && objectMatch?.groupValues?.get(2) == "/params" -> {
                val arr = mapper.createArrayNode()
                boundary.params.effectiveParams(objectMatch.groupValues[1]).forEach { param ->
                    val n = arr.addObject()
                        .put("name", param.name)
                        .put("unit", param.unit)
                        .put("is_tpm", param.isTpm)
                    param.value?.let { n.put("value", it) }
                    param.formula?.let { n.put("formula", it) }
                    n.put("source", param.provenance.path("source").asText(""))
                }
                respond(ex, 200, arr)
            }

            method == "POST" && objectMatch != null && objectMatch.groupValues[2].startsWith("/params/") -> {
                val req = mapper.readTree(body(ex))
                boundary.params.putRaw(
                    objectId = objectMatch.groupValues[1],
                    name = objectMatch.groupValues[2].removePrefix("/params/"),
                    value = req.path("value").let { if (it.isNumber) it.asDouble() else null },
                    unit = req.path("unit").asText(""),
                    provenance = req.path("provenance"),
                    formula = req.path("formula").textValue(),
                )
                respond(ex, 204, null)
            }

            method == "GET" && path == "/reports/unaccepted-ai" -> {
                val arr = mapper.createArrayNode()
                boundary.params.unacceptedAiProposals().forEach {
                    arr.addObject().put("object_id", it.objectId).put("name", it.name)
                        .put("prompt_package_id", it.promptPackageId)
                }
                respond(ex, 200, arr)
            }

            method == "GET" && path == "/reports/stale-results" -> {
                // Устаревшие бывают двух родов: вытесненные СВЕЖИМ прогоном
                // (история, тревоги не стоят) и помеченные каскадом при правке
                // входов. Наружу идут только вторые — те, чей сценарий остался
                // без активного результата того же вида: плашка «пересчитайте»
                // при уже выполненном пересчёте — ложная тревога (находка
                // живого прогона: после двух свежих прогонов сравнение
                // требовало пересчитать вытесненную историю).
                val arr = mapper.createArrayNode()
                boundary.results.staleReport().forEach {
                    val recomputed = boundary.results
                        .activeForScenario(it.scenarioId, it.kind).isNotEmpty()
                    if (!recomputed) {
                        arr.addObject().put("pk", it.pk).put("scenario_id", it.scenarioId).put("kind", it.kind)
                    }
                }
                respond(ex, 200, arr)
            }

            // Отчёты шага 2 (TZ-OUT-003/004, TZ-REQ-001/005/006)
            method == "GET" && path == "/reports/maturity" -> {
                val q = query(ex)
                val gate = q["gate"] ?: throw IllegalArgumentException("query parameter 'gate' is required")
                val report = boundary.maturity.build(gate, q["at"]?.let(OffsetDateTime::parse), project)
                val n = mapper.createObjectNode()
                n.put("gate", report.gate)
                report.at?.let { n.put("at", it.toString()) }
                n.put("ready", report.ready())
                val blocking = n.putArray("blocking")
                report.blockingReasons().forEach(blocking::add)
                val byType = n.putObject("gaps_by_type")
                report.gapsByType.forEach { (type, gaps) ->
                    val arr = byType.putArray(type)
                    gaps.forEach { g ->
                        arr.addObject().put("id", g.id).put("actual", g.actual)
                            .put("required", g.required).put("owner", g.owner)
                    }
                }
                val tbd = n.putArray("open_tbd")
                report.openTbd.forEach { tbd.addObject().put("id", it.id).put("owner", it.owner) }
                n.set<ObjectNode>("trace_breaks", mapper.valueToTree(report.traceBreaks))
                n.set<ObjectNode>("unverified", mapper.valueToTree(report.unverified))
                respond(ex, 200, n)
            }

            method == "GET" && path == "/reports/trace-matrix" -> {
                val m = boundary.matrices.traceMatrix(project)
                val n = mapper.createObjectNode()
                val rows = n.putArray("rows")
                m.rows.forEach { r ->
                    val row = rows.addObject().put("requirement", r.requirementId)
                    r.needs.let { needs -> row.putArray("needs").also { a -> needs.forEach(a::add) } }
                    val svc = row.putArray("services")
                    r.services.forEach { svc.addObject().put("id", it.id).put("consumer_class", it.consumerClass) }
                    row.putArray("elements").also { a -> r.elements.forEach(a::add) }
                    row.put("method", r.method)
                }
                val gaps = n.putArray("gaps")
                m.gaps.forEach { gaps.addObject().put("requirement", it.requirementId).put("missing", it.missing) }
                respond(ex, 200, n)
            }

            // CR-003: строка на каждое событие плюс состояние требования
            // Вкладка «Верификация» экрана требований (шаг 16 §2.4): строка на
            // пару «требование × событие» (verificationMatrixView), разрывы —
            // ОТДЕЛЬНЫМ списком: пустая ячейка читается как «данных нет», а не
            // как «проверять нечего». Рядом — непокрытые требования (TZ-REQ-008).
            // Богатая сборка matrices.verificationMatrix остаётся входом пакета
            // передачи (TZ-OUT-006) — у неё другой потребитель, не экран.
            method == "GET" && path == "/reports/verification-matrix" -> {
                val docs = boundary.objects.listCurrent(project)
                    .filter { it.type == "requirement" && it.status != Lifecycle.Cancelled }
                    .map { it.doc }
                val view = orbita.out.verificationMatrixView(docs)
                val n = mapper.createObjectNode()
                val rows = n.putArray("rows")
                view.rows.forEach { r ->
                    rows.addObject()
                        .put("requirement", r.requirementId).put("event", r.eventId)
                        .put("method", r.method).put("level", r.level).put("closes", r.closes)
                        .put("approach", r.approach).put("status", r.status)
                        .put("evidence_ref", r.evidenceRef).put("evidence_stale", r.evidenceStale)
                }
                val gaps = n.putArray("gaps")
                view.gaps.forEach { g ->
                    gaps.addObject().put("requirement", g.requirementId)
                        .put("event", g.eventId).put("reason", g.reason)
                }
                val unverified = n.putArray("unverified")
                boundary.matrices.unverifiedRequirements(project).forEach(unverified::add)
                respond(ex, 200, n)
            }

            // CR-003: валидация — отдельная матрица, отдельный вопрос «то ли построили»
            method == "GET" && path == "/reports/validation-matrix" -> {
                val arr = mapper.createArrayNode()
                boundary.matrices.validationMatrix(project).forEach { v ->
                    arr.addObject()
                        .put("validation", v.validationId).put("target", v.target)
                        .put("conops_ref", v.conopsRef).put("product_kind", v.productKind)
                        .put("method", v.method).put("phase", v.phase).put("status", v.status)
                        .put("evidence_ref", v.evidenceRef)
                }
                respond(ex, 200, arr)
            }

            method == "GET" && path == "/views/requirement-tree" -> {
                val view = boundary.screens.requirementTree(project)
                val n = mapper.createObjectNode()
                n.set<ArrayNode>("roots", mapper.valueToTree(view.roots))
                n.set<ObjectNode>("children", mapper.valueToTree(view.children))
                n.set<ArrayNode>("rows", mapper.valueToTree(view.rows))
                n.set<ArrayNode>("needsUncovered", mapper.valueToTree(view.needsUncovered))
                n.set<ObjectNode>("systemRoot", mapper.valueToTree(view.systemRoot))
                n.put("compositionRoots", view.compositionRoots)
                respond(ex, 200, n)
            }

            method == "GET" && Regex("^/views/requirements/(RQ-[0-9]{4})$").matches(path) -> {
                val id = path.removePrefix("/views/requirements/")
                respond(ex, 200, mapper.valueToTree(boundary.screens.card(id)))
            }

            // ---- Т-1: сохранённые виды реестра (VW) ----
            // Личные виды фильтрует СЕРВЕР по учётке — generic /objects отдал бы
            // чужие личные; при выключенных учётках (login == null) фильтра нет.
            // О-9: портфель одним запросом — входная дверь: строка проекта
            // несёт всё для решения «куда идти» (бриф §2), клиент не собирает
            method == "GET" && path == "/views/portfolio" -> {
                val projects = boundary.objects.listCurrent()
                    .filter { it.type == "project" && it.status != Lifecycle.Cancelled }
                // активность — последняя правка НЕслужебной учётки (§1.1:
                // один список служебности с якорем помет); проект без единой
                // содержательной правки — тихая служебная строка (§1.2)
                val human = boundary.objects.lastActivityByProject(
                    orbita.req.ServiceAuthors.all, orbita.req.ServiceAuthors.MIGRATION_PREFIX,
                )
                val activity = boundary.objects.lastActivityByProject()
                val arr = mapper.createArrayNode()
                projects.forEach { p ->
                    val row = arr.addObject()
                    row.put("id", p.id)
                    row.put("name", p.doc.path("name").asText(p.id))
                    row.put("phase", p.doc.path("phase").asText(""))
                    // руководитель: роль lead при учётках, иначе автор создания
                    val lead = boundary.auth.listRoles(p.id).entries.firstOrNull { it.value == "lead" }?.key
                    row.put("owner", lead ?: boundary.objects.history(p.id).firstOrNull()?.createdBy ?: "")
                    val next = p.doc.path("milestones").firstOrNull { !it.path("held").asBoolean(false) }
                        ?.path("gate")?.asText("")?.ifBlank { null }
                    if (next != null) {
                        val g = row.putObject("gate")
                        g.put("name", next)
                        g.put("label", boundary.req.gateLabel(next))
                        // у точки без конфигурации проверок счётчика нет —
                        // честное отсутствие, не ноль и не отказ всего портфеля
                        runCatching { boundary.gatePassing.issues(next, p.id).size }
                            .onSuccess { g.put("open_count", it) }
                    } else {
                        row.putNull("gate")
                    }
                    val ret = p.doc.path("return")
                    if (ret.isObject) row.putObject("return").put("reason", ret.path("reason").asText(""))
                    else row.putNull("return")
                    val sp = p.doc.path("start_path")
                    if (sp.isObject) {
                        row.putObject("start_path")
                            .put("status", sp.path("status").asText(""))
                            .put("step", sp.path("step").asInt(0))
                    } else {
                        row.putNull("start_path")
                    }
                    val engineering = human[p.id]
                    if (engineering != null) {
                        row.putObject("last_activity")
                            .put("at", engineering.validFrom.toString())
                            .put("author", humanAuthor(engineering.createdBy))
                            .put(
                                "what",
                                engineering.changeRef?.ifBlank { null }
                                    ?: (if (engineering.version == "1" && engineering.supersedes == null)
                                        "создан ${engineering.id}" else "правка ${engineering.id}"),
                            )
                    } else {
                        activity[p.id]?.let { last ->
                            row.putObject("last_activity")
                                .put("at", last.validFrom.toString())
                                .put("service", true)
                        } ?: row.putNull("last_activity")
                    }
                }
                // «что нового» читается сверху: сортировка — последняя активность
                val sorted = arr.sortedByDescending {
                    it.path("last_activity").path("at").asText("")
                }
                val out = mapper.createObjectNode()
                out.putArray("projects").also { a -> sorted.forEach(a::add) }
                respond(ex, 200, out)
            }

            method == "GET" && path == "/views/req-views" -> {
                val login = currentAuthorLogin.get()
                val arr = mapper.createArrayNode()
                boundary.objects.listCurrent(project)
                    .filter { it.type == "saved_view" }
                    .filter {
                        it.doc.path("scope").asText() == "project" ||
                            login == null || it.doc.path("owner_login").asText("") == login
                    }
                    .sortedBy { it.id }
                    .forEach { st ->
                        arr.add((st.doc.deepCopy<ObjectNode>()).put("version", st.version))
                    }
                respond(ex, 200, mapper.createObjectNode().apply { set<ArrayNode>("views", arr) })
            }

            // owner_login проставляется здесь из сессии: документ клиента его
            // не задаёт — иначе личный вид можно было бы подписать чужим именем.
            method == "POST" && path == "/views/req-views" -> {
                val request = mapper.readTree(body(ex))
                val doc = request.path("doc") as? ObjectNode
                    ?: throw IllegalArgumentException("doc object is required")
                doc.remove("owner_login")
                currentAuthorLogin.get()?.let { doc.put("owner_login", it) }
                val stored = boundary.editing.create(
                    CoreType.SavedView, doc, author(request), requireProject(project),
                )
                respond(ex, 201, summary(stored).apply { set<ObjectNode>("doc", stored.doc) })
            }

            // ---- ИИ-контур (TZ-AI) ----
            // Генерация происходит ВНЕ системы: пакет копируют во внешний
            // интерфейс LLM, ответ вставляют обратно. Никакого вызова модели
            // здесь нет и не предполагается (канал 1, TZ-AI-001).
            method == "POST" && path == "/ai/packages" -> {
                val request = mapper.readTree(body(ex))
                val pkg = boundary.packages.build(
                    kind = request.path("kind").asText(),
                    context = request.path("context"),
                    task = request.path("task").asText(),
                )
                respond(ex, 201, pkg.toJson(mapper))
            }

            // Разбор ответа модели и структурный фильтр: до инженера доходит
            // только состоятельное, остальное — в очередь переделки.
            method == "POST" && path == "/ai/answers" -> {
                val request = mapper.readTree(body(ex))
                val pkg = boundary.packages.build(
                    kind = request.path("kind").asText(),
                    context = request.path("context"),
                    task = request.path("task").asText(),
                )
                val parsed = boundary.parser.parse(request.path("raw").asText(""), pkg)
                val report = boundary.screening.screen(parsed.accepted)

                val n = mapper.createObjectNode()
                n.put("package_id", pkg.id)
                n.put("proposed", parsed.accepted.size + parsed.rejected.size)
                val malformed = n.putArray("malformed")
                parsed.rejected.forEach { r ->
                    val item = malformed.addObject()
                    r.item?.let { item.set<ObjectNode>("item", it) }
                    val errs = item.putArray("errors")
                    r.errors.forEach(errs::add)
                }
                val shown = n.putArray("shown")
                report.shown.forEach { proposal ->
                    val entry = shown.addObject()
                    entry.set<ObjectNode>("item", proposal)
                    // diff к текущему состоянию: применять будет инженер по полям
                    val targetId = proposal.path("id").asText("")
                    val stored = boundary.objects.current(targetId)
                    entry.set<ObjectNode>(
                        "diff",
                        orbita.ai.diffToJson(
                            orbita.ai.makeDiff(stored?.doc ?: mapper.createObjectNode(), proposal), mapper,
                        ),
                    )
                    // Версия, против которой посчитан diff: акцепт — такая же
                    // правка, как ручная, и подчиняется той же блокировке.
                    // null означает, что объекта ещё нет и акцепт его создаст.
                    entry.put("base_version", stored?.version)
                }
                n.set<ObjectNode>("rework", report.reworkContext(mapper))
                val byRule = n.putObject("by_rule")
                report.byRule.forEach { (rule, count) -> byRule.put(rule, count) }
                respond(ex, 200, n)
            }

            // Акцепт: применяются ТОЛЬКО выбранные поля; массового акцепта
            // без просмотра интерфейсом не предусмотрено (TZ-AI-004, ловушка 2).
            //
            // Акцепт СОХРАНЯЕТ объект в модель. До шага 15 этот маршрут возвращал
            // размеченный объект и на этом заканчивался: экран рапортовал
            // «принято полей: N», а в модели не менялось ничего — контур ИИ
            // обрывался на последнем шаге. Запись идёт тем же путём, что ручная
            // правка: те же правила, тот же автор, та же блокировка по версии.
            // Блок E: акцепт ПАЧКОЙ — сотни предложений (нужды, сервисы,
            // требования) принимаются одним действием; порядок разрешает
            // сервер проходами, как в импорте (ADR-024); всё или ничего.
            // Служба ИИ (П5): промпт собирает СЛУЖБА из профиля и состояния
            // модели; клиент его показывает, но не сочиняет
            method == "POST" && path == "/ai/compose" -> {
                val req = mapper.readTree(body(ex))
                val (profile, prompt) = boundary.ai.compose(
                    req.path("kind").asText(), req.path("profile").asText(),
                    requireProject(project), req.path("statement").asText(""),
                )
                val n = mapper.createObjectNode()
                n.put("profile", profile.id)
                n.put("profile_version", profile.version)
                n.put("transport", profile.transport)
                n.put("require_source", profile.requireSource)
                n.put("prompt", prompt)
                // Атрибуция источников (О-4): каждая часть промпта помнит,
                // пришла она из профиля, модели проекта или входа операции
                val (_, promptBlocks) = boundary.ai.composeBlocks(
                    req.path("kind").asText(), req.path("profile").asText(),
                    requireProject(project), req.path("statement").asText(""),
                )
                val blocksArr = n.putArray("blocks")
                promptBlocks.forEach { b ->
                    blocksArr.addObject()
                        .put("source", b.source)
                        .put("title", b.title)
                        .put("text", b.text)
                }
                // Ф-05: состав промпта ПО ИСТОЧНИКАМ со счётчиками — пустой
                // источник виден строкой, а не исчезает: инженер сразу знает,
                // чего в промпте нет и откуда это взять
                val sourcesArr = n.putArray("sources")
                StatementSources(boundary).of(req.path("kind").asText(), requireProject(project))
                    .forEach { src ->
                        val o = sourcesArr.addObject()
                            .put("key", src.key)
                            .put("title", src.title)
                            .put("count", src.count)
                            .put("empty", src.empty)
                        src.note?.let { o.put("note", it) }
                    }
                respond(ex, 200, n)
            }

            // Прямой вызов провайдера — основной транспорт службы
            method == "POST" && path == "/ai/ask" -> {
                val req = mapper.readTree(body(ex))
                val run = boundary.ai.ask(
                    req.path("kind").asText(), req.path("profile").asText(),
                    requireProject(project), req.path("statement").asText(""), author(req),
                )
                respond(ex, if (run.report.path("failed").asBoolean()) 503 else 200, run.report)
            }

            // Закрытый контур: ответ владельца, полученный файлом, — тем же
            // разбором, фильтром и журналом
            method == "POST" && path == "/ai/submit" -> {
                val req = mapper.readTree(body(ex))
                val run = boundary.ai.submit(
                    req.path("kind").asText(), req.path("profile").asText(),
                    requireProject(project), req.path("statement").asText(""),
                    req.path("raw").asText(""), author(req),
                )
                respond(ex, 200, run.report)
            }

            // Б-01: заготовленный пакет предложений — без вызова модели.
            // Вид — из самого пакета; разбор, фильтр и журнал общие
            // (transport `package`, модель «пакет»).
            method == "POST" && path == "/ai/packet" -> {
                val req = mapper.readTree(body(ex))
                val run = boundary.ai.packet(
                    req.path("raw").asText(""), requireProject(project), author(req),
                )
                respond(ex, 200, run.report)
            }

            // Журнал вызовов: «сколько и почём»
            method == "GET" && path == "/ai/journal" ->
                respond(ex, 200, boundary.ai.journal(requireProject(project)))

            // Дозаполнение: применить частичные правки к СУЩЕСТВУЮЩИМ
            // требованиям (находка прогона: 140 без обоснования/показателя).
            // Два прохода: сухая проверка ВСЕХ с отчётом поимённо, затем
            // транзакционная запись — всё или ничего, как у пачек (ADR-024).
            // Базированные правятся с основанием (TZ-COM-003): основание —
            // акцепт инженером предложений службы; статус слетает в черновик,
            // и это называется в ответе, а не выясняется на «Готовности».
            method == "POST" && path == "/ai/enrich-apply" -> {
                val request = mapper.readTree(body(ex))
                val by = (currentAuthor.get() ?: request.path("by").asText("")).trim().takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("TZ-AI-004: field 'by' is required to accept proposals")
                val call = request.path("call").takeIf { it.isNumber }?.asLong()
                val items = request.path("items")
                require(items.isArray && items.size() > 0) { "items — принятые правки дозаполнения" }
                val ctx = requireProject(project)
                val changeRef = "акцепт предложений службы ИИ" +
                    (call?.let { " (вызов $it)" } ?: "") + ": дозаполнение rationale/mop"

                // проход 1: сухая проверка каждого слияния по нормативной схеме
                data class Prep(val id: String, val base: String, val changes: com.fasterxml.jackson.databind.node.ObjectNode)
                val problems = mutableListOf<BatchProblem>()
                val prepared = mutableListOf<Prep>()
                items.forEachIndexed { i, item ->
                    val id = item.path("id").asText("")
                    val cur = boundary.objects.current(id)
                    if (cur == null || cur.type != "requirement") {
                        problems += BatchProblem(i, id.ifBlank { null }, null, "unknown", "требование '$id' не найдено")
                        return@forEachIndexed
                    }
                    val changes = mapper.createObjectNode()
                    item.path("rationale").takeIf { it.isTextual && it.asText().isNotBlank() }
                        ?.let { changes.put("rationale", it.asText()) }
                    item.path("mop").takeIf { it.isObject && !it.isEmpty }
                        ?.let { changes.set<com.fasterxml.jackson.databind.node.ObjectNode>("mop", it.deepCopy()) }
                    if (changes.isEmpty) {
                        problems += BatchProblem(i, id, null, "empty", "правка пуста: нет ни rationale, ни mop")
                        return@forEachIndexed
                    }
                    val merged = cur.doc.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
                    changes.properties().forEach { (k, v) -> merged.set<com.fasterxml.jackson.databind.node.ObjectNode>(k, v) }
                    boundary.schemaProblems(orbita.mod.model.CoreType.Requirement, merged).forEach { e ->
                        problems += BatchProblem(i, id, e.path, e.rule, e.message)
                    }
                    prepared += Prep(id, cur.version, changes)
                }
                if (problems.isNotEmpty()) {
                    respond(ex, 422, batchJson(BatchReport(0, problems.sortedBy { it.index })))
                } else {
                    // проход 2: запись в одной транзакции. ADR-031: правка
                    // наследует статус — дозаполнение (закрытие TBD) ничего
                    // не понижает; прежний «честный сброс в черновик» умер
                    boundary.transaction {
                        prepared.forEach { pr ->
                            boundary.editing.update(
                                orbita.mod.model.CoreType.Requirement, pr.id, pr.changes,
                                pr.base, by, changeRef = changeRef,
                            )
                        }
                    }
                    call?.let { boundary.ai.markAccepted(it, prepared.size, by) }
                    val out = mapper.createObjectNode()
                    out.put("written", prepared.size)
                    out.putArray("problems")
                    respond(ex, 201, out)
                }
            }

            method == "POST" && path == "/ai/accept-batch" -> {
                val request = mapper.readTree(body(ex))
                val by = (currentAuthor.get() ?: request.path("by").asText("")).trim().takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("TZ-AI-004: field 'by' is required to accept proposals")
                val packageId = request.path("package_id").asText("")
                val llm = request.path("llm").asText("unknown")
                val items = request.path("items")
                require(items.isArray && items.size() > 0) { "items — пачка принятых предложений" }
                val ctx = requireProject(project)
                // В3 §2.5: акцепт ИИ — роль из профиля. Профиль вызова несёт
                // accept_role; несовпадение — отказ (руководителю можно всегда).
                currentAuthorLogin.get()?.let { login ->
                    val callPk = request.path("call").takeIf { it.isNumber }?.asLong()
                    val profileId = callPk?.let { pk ->
                        boundary.ai.journal(ctx).path("calls")
                            .firstOrNull { it.path("pk").asLong() == pk }
                            ?.path("profile")?.asText("")
                    }
                    val need = profileId?.let {
                        boundary.objects.current(it)?.doc?.path("accept_role")?.asText("")
                    } ?: ""
                    if (need.isNotBlank()) {
                        val actual = boundary.auth.roleIn(ctx, login)
                        if (actual != need && actual != "lead") {
                            respond(
                                ex, 403,
                                mapper.createObjectNode().put(
                                    "error",
                                    "акцепт профиля $profileId — роль «$need» (ваша — ${actual ?: "нет"})",
                                ),
                            )
                            return
                        }
                    }
                }
                val payload = mapper.createObjectNode()
                payload.put("author", by)
                val arr = payload.putArray("objects")
                items.forEach { item ->
                    // происхождение ИИ с акцептом — на каждом объекте (TZ-AI-004)
                    val marked = orbita.ai.accept(
                        orbita.ai.asProposal(item.deepCopy(), packageId, llm, mapper),
                        by = by,
                    )
                    arr.add(marked)
                }
                // Г-01: подтверждённое инженером сопоставление чужих ссылок —
                // применяется ДО перебивки собственных id пачки
                val карта = mutableMapOf<String, String>()
                request.path("link_mapping").properties().forEach { (старый, новый) ->
                    новый.asText("").takeIf { it.isNotBlank() }?.let { карта[старый] = it }
                }
                if (карта.isNotEmpty()) {
                    // применяем к КОПИИ: очистка исходного массива до
                    // переноса выносила бы пакет целиком (поймано прогоном)
                    val сопоставленные = LinkMapping.применить(arr.deepCopy(), карта)
                    arr.removeAll()
                    сопоставленные.forEach { arr.add(it) }
                }
                val importer = BatchImport(boundary, mapper)
                // id предложений — черновые (TZ-MOD-007: id глобальны и не
                // переиспользуются): межпакетные ссылки — по карте проекта,
                // занятые id — свежие, след в provenance.ai.source_id
                val (remapped, idMap) = importer.remapForAccept(arr, ctx)
                payload.set<ObjectNode>("objects", remapped)
                // Отказ обязан называть строку ТАК ЖЕ, как её видит инженер:
                // перебитый id ему ни о чём не говорит и снять отметку не даёт
                // (находка живого прохода ПМИ-3). Отказ записи приходит
                // ИСКЛЮЧЕНИЕМ — обогащать надо и его, иначе обратный адрес
                // теряется по дороге к общему обработчику.
                val обратно = idMap.entries.associate { (old, new) -> new to old }
                fun сИсходными(r: BatchReport): BatchReport =
                    if (обратно.isEmpty()) r
                    else BatchReport(r.written, r.problems.map { p -> p.copy(sourceId = обратно[p.id]) })
                val report = try {
                    сИсходными(importer.import(payload, by, ctx))
                } catch (e: BatchRejectedException) {
                    throw BatchRejectedException(сИсходными(e.report))
                }
                // акцепт дописывается к своему вызову: «сколько дошло до модели»
                request.path("call").takeIf { it.isNumber }?.let {
                    if (report.ok) boundary.ai.markAccepted(it.asLong(), report.written, by)
                }
                val out = batchJson(report)
                if (idMap.isNotEmpty()) {
                    val rm = out.putArray("remapped")
                    idMap.forEach { (old, new) -> rm.addObject().put("from", old).put("to", new) }
                }
                respond(ex, if (report.ok) 201 else 422, out)
            }

            // Правило основания требует ЯВНОГО РЕШЕНИЯ ЧЕЛОВЕКА: величина,
            // придуманная службой (source=manual от модели), не проходит молча.
            // Но решение это негде было принять — снятые предложения висели
            // счётчиком «снято 8» без единого действия (находка живого прохода
            // ПМИ-3: сервисы внешнего контура встали намертво).
            //
            // Здесь инженер берёт значения ПОД СВОЮ ОТВЕТСТВЕННОСТЬ: провенанс
            // получает его имя и время — след того, кто отвечает за число.
            // Это исполнение правила, а не обход: «я так решил» становится
            // подписанным «я так решил», и видно, кем.
            method == "POST" && path == "/ai/accept-rework" -> {
                val request = mapper.readTree(body(ex))
                val by = author(request)
                require(by.isNotBlank()) { "TZ-COM-005: field 'author' is required for editing" }
                val ctx = requireProject(project)
                val items = request.path("items").takeIf { it.isArray } as? ArrayNode
                    ?: throw IllegalArgumentException("нет предложений: поле 'items'")
                require(!items.isEmpty()) { "не выбрано ни одного предложения" }
                val принято = mapper.createArrayNode()
                val подписано = java.time.OffsetDateTime.now().toString()
                items.forEach { item ->
                    val копия = item.deepCopy<JsonNode>() as ObjectNode
                    подписатьВеличины(копия, by, подписано)
                    принято.add(копия)
                }
                val payload = mapper.createObjectNode()
                val работник = BatchImport(boundary, mapper)
                val (remapped, idMap) = работник.remapForAccept(принято, ctx)
                payload.set<ArrayNode>("objects", remapped)
                val сырой = работник.import(payload, by, ctx)
                val обратно = idMap.entries.associate { (old, new) -> new to old }
                val report = if (обратно.isEmpty()) сырой else BatchReport(
                    сырой.written,
                    сырой.problems.map { p -> p.copy(sourceId = обратно[p.id]) },
                )
                val out = batchJson(report)
                out.put("signed_by", by)
                out.put(
                    "note",
                    "значения приняты под ответственность инженера: в происхождении каждой " +
                        "величины стоит его имя и время решения",
                )
                if (idMap.isNotEmpty()) {
                    val rm = out.putArray("remapped")
                    idMap.forEach { (old, new) -> rm.addObject().put("from", old).put("to", new) }
                }
                respond(ex, if (report.ok) 201 else 422, out)
            }

            method == "POST" && path == "/ai/accept" -> {
                val request = mapper.readTree(body(ex))
                val targetId = request.path("target_id").asText()
                val stored = boundary.objects.current(targetId)
                val current = stored?.doc ?: mapper.createObjectNode()
                val proposal = request.path("proposal")
                val selected = request.path("selected").map { it.asText() }.toSet()
                val diff = orbita.ai.makeDiff(current, proposal)
                val applied = orbita.ai.applyDiff(current, diff, selected, mapper)
                val marked = orbita.ai.accept(
                    orbita.ai.asProposal(
                        applied, request.path("package_id").asText(""),
                        request.path("llm").asText("unknown"), mapper,
                    ),
                    by = currentAuthor.get() ?: request.path("by").asText(""),
                )
                // Акцептующий инженер и есть автор изменения (TZ-AI-004):
                // поле называется `by`, но роль у него та же, что у `author`.
                val author = (currentAuthor.get() ?: request.path("by").asText("")).trim().takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("TZ-AI-004: field 'by' is required to accept a proposal")
                val type = CoreType.byDbType(stored?.type ?: typeByIdPrefix(targetId).dbType)
                val saved = if (stored == null) {
                    marked.put("id", targetId)
                    boundary.editing.create(type, marked, author, requireProject(project))
                } else {
                    val changes = mapper.createObjectNode()
                    selected.forEach { field -> changes.set<ObjectNode>(field, marked.path(field)) }
                    changes.set<ObjectNode>("provenance", marked.path("provenance"))
                    boundary.editing.update(
                        type = type, id = targetId, changes = changes,
                        baseVersion = request.path("base_version").asText(stored.version),
                        author = author,
                    )
                }
                respond(ex, 200, summary(saved).apply { set<ObjectNode>("doc", saved.doc) })
            }

            // Экраны мастера: нужды, сервисы, готовность, состояние шагов
            method == "GET" && path == "/views/needs" ->
                respond(ex, 200, mapper.valueToTree(boundary.wizard.needs(project)))

            method == "GET" && path == "/views/services" ->
                respond(ex, 200, mapper.valueToTree(boundary.wizard.services(projectId = project)))

            method == "GET" && path == "/views/readiness" ->
                respond(ex, 200, mapper.valueToTree(boundary.wizard.readiness(query(ex)["gate"] ?: "SRR", project)))

            method == "GET" && path == "/views/wizard" ->
                respond(ex, 200, mapper.valueToTree(boundary.wizard.wizard(boundary.screens, project)))

            // Реестр рисков: список и матрица одним ответом
            method == "GET" && path == "/views/risks" -> {
                val risks = boundary.req.risks(project)
                val n = mapper.createObjectNode()
                n.set<ObjectNode>("summary", mapper.valueToTree(orbita.req.registerSummary(risks)))
                n.set<ArrayNode>("risks", mapper.valueToTree(risks))
                n.set<ArrayNode>(
                    "matrix",
                    mapper.valueToTree(
                        orbita.out.riskMatrix(
                            risks.map {
                                it.path("id").asText() to
                                    (it.path("probability").asInt() to it.path("impact").asInt())
                            },
                        ),
                    ),
                )
                respond(ex, 200, n)
            }

            // Хранимая карта спроса (ADR-021): ячейки и веса берутся из
            // сохранённого документа, а не пересчитываются. Сценарий ссылается
            // именно на сохранённую карту.
            method == "GET" && Regex("^/views/demand/(DM-[0-9]{4})$").matches(path) -> {
                val id = path.removePrefix("/views/demand/")
                val stored = boundary.objects.current(id)
                    ?: throw NoSuchElementException("карта спроса $id в модели отсутствует")
                respond(ex, 200, mapper.valueToTree(boundary.demand.fromDocument(stored.doc)))
            }

            // Экран 4: карта спроса. Библиотека референсных сценариев — слой 3
            // (TZ-USR-006); её состав задаётся ресурсом, а не экраном.
            method == "GET" && path == "/views/demand/library" ->
                respond(ex, 200, mapper.valueToTree(boundary.demand.referenceScenarios()))

            // Сборка карты по слоям. Ячейки, веса и пик считает сервер: вторая
            // нормировка в клиенте разошлась бы с первой (STEP-7-9, ловушка 2).
            method == "POST" && path == "/views/demand" -> {
                val request = mapper.readTree(body(ex))
                respond(ex, 200, mapper.valueToTree(boundary.demand.build(demandLayers(request))))
            }

            // Экран 5: пресеты платформ (TZ-KA-001) — редактируемая конфигурация
            method == "GET" && path == "/views/spacecraft/presets" ->
                respond(ex, 200, mapper.valueToTree(boundary.spacecraft.presetRows()))

            // Бюджеты аппарата по ХРАНИМОЙ модели КА (ADR-021). Модель берётся
            // из хранилища по ссылке, а не приходит телом запроса: до CR-005
            // экран жил в пределах сеанса и сохранить построенное было некуда.
            method == "GET" && Regex("^/views/spacecraft/(SP-[0-9]{4})$").matches(path) -> {
                val id = path.removePrefix("/views/spacecraft/")
                val stored = boundary.objects.current(id)
                    ?: throw NoSuchElementException("модель аппарата $id в модели отсутствует")
                respond(ex, 200, mapper.valueToTree(boundary.spacecraft.build(stored.doc, conditions(query(ex)))))
            }

            // Расчёт по ещё не сохранённой модели: экран считает до записи.
            // Документ проходит схему контракта — считать по не прошедшему нельзя.
            method == "POST" && path == "/views/spacecraft" -> {
                val request = mapper.readTree(body(ex))
                val doc = request.path("spacecraft")
                boundary.validateContract("contracts/spacecraft", mapper.writeValueAsString(doc))
                    .takeIf { it.isNotEmpty() }
                    ?.let { return respond(ex, 422, errorsJson(it)) }
                respond(ex, 200, mapper.valueToTree(boundary.spacecraft.build(doc, conditions(request))))
            }

            // §3 МВП-М1: сводка построения для ФОРМЫ — сервер считает, клиент
            // показывает (ловушка 2): итог КА суммой с формулой, наклонение
            // ССО из высоты, предупреждения (T не делится на P) — до записи.
            method == "POST" && path == "/calc/constellation-summary" -> {
                val req = mapper.readTree(body(ex))
                val out = mapper.createObjectNode()
                val rows = out.putArray("subgroups")
                var total = 0
                val parts = mutableListOf<String>()
                val warnings = out.putArray("warnings")
                req.path("subgroups").forEachIndexed { i, g ->
                    val planes = g.path("planes").asInt(0)
                    val perPlane = g.path("per_plane").asInt(0)
                    val altKm = g.path("altitude_km").asDouble(0.0)
                    val kind = g.path("kind").asText("walker_delta")
                    val sats = planes * perPlane
                    total += sats
                    if (sats > 0) parts += "$planes×$perPlane"
                    val row = rows.addObject().put("index", i).put("sats", sats)
                    if (kind == "sso" && altKm > 0) {
                        row.put("computed_inclination_deg", orbita.bal.ssoInclinationDeg(altKm))
                    }
                    val f = g.path("phasing").asInt(0)
                    if (sats > 0 && f >= sats) {
                        warnings.add("подгруппа ${i + 1}: F=$f не меньше T=$sats — фазовый параметр берётся по модулю T")
                    }
                }
                out.put("total_sats", total)
                out.put("formula", if (parts.isEmpty()) "" else "$total = ${parts.joinToString(" + ")}")
                respond(ex, 200, out)
            }

            // МВП-М2: сравнение построений — 2–5 вариантов, метрики группами
            // из того же интеграла §5; пороги — фильтром с перечнем отсеянных;
            // Парето — сервером; результат хранится (kind constellation_compare)
            // и уходит вставкой в раздел AoA при рендере документа.
            method == "POST" && path == "/views/constellation-compare" -> {
                val req = mapper.readTree(body(ex))
                val ctx = requireProject(project)
                val scenarioId = req.path("scenario").asText("")
                    .ifBlank { throw IllegalArgumentException("field 'scenario' is required") }
                val scenario = boundary.objects.current(scenarioId)
                    ?: throw NoSuchElementException("сценарий $scenarioId не найден")
                val variantIds = req.path("variants").map { it.asText() }.distinct()
                require(variantIds.size in 2..5) {
                    "сравнение — по выбранным 2–5 вариантам (передано ${variantIds.size})"
                }
                val demandMap = scenario.doc.path("demand_map_ref").asText("")
                    .takeIf { it.isNotBlank() }?.let { boundary.objects.current(it) }
                    ?: throw NoSuchElementException("карта спроса по ссылке сценария не найдена")
                val epoch = scenario.doc.path("epoch").asText("")
                    .ifBlank { throw IllegalArgumentException("scenario has no epoch") }
                val durationS = scenario.doc.path("duration_s").asDouble(0.0)
                    .takeIf { it > 0 } ?: throw IllegalArgumentException("scenario has no duration_s")
                val cells = demandMap.doc.path("cells").map { c ->
                    orbita.bal.CompareMetrics.DemandCell(
                        orbita.bal.GridPoint(
                            c.path("cell_id").asText(), c.path("lat_deg").asDouble(), c.path("lon_deg").asDouble(),
                        ),
                        c.path("demand").groupBy { it.path("terminal_profile_ref").asText("A_prime") }
                            .mapValues { (_, ds) ->
                                ds.sumOf { it.path("count").asDouble(0.0) } to
                                    ds.sumOf { it.path("uplink_msgs_per_day").asDouble(0.0) }
                            },
                    )
                }
                val stations = boundary.objects.listCurrent(ctx)
                    .firstOrNull { it.type == "ground_stations" }?.doc?.path("stations")
                    ?.map {
                        orbita.bal.GridPoint(
                            it.path("id").asText(), it.path("lat_deg").asDouble(), it.path("lon_deg").asDouble(),
                        )
                    } ?: emptyList()

                val evaluated = variantIds.map { vid ->
                    val v = boundary.objects.current(vid)
                        ?: throw NoSuchElementException("вариант $vid не найден")
                    require(v.type == "constellation") { "'$vid' — не построение" }
                    boundary.compareMetrics.evaluate(
                        vid, v.doc.path("name").asText(vid), v.doc, cells, stations, epoch, durationS,
                    )
                }

                // значение метрики по имени фильтра/оси — одна карта направлений
                fun metricOf(v: JsonNode, key: String): Double? = when {
                    key.startsWith("coverage_") ->
                        v.path("service").path(key.removePrefix("coverage_")).path("coverage_share")
                            .takeIf { it.isNumber }?.asDouble()
                    key.startsWith("max_gap_") ->
                        v.path("service").path(key.removePrefix("max_gap_")).path("max_gap_s")
                            .takeIf { it.isNumber }?.asDouble()
                    key.startsWith("latency_") ->
                        v.path("service").path(key.removePrefix("latency_")).path("latency_s")
                            .takeIf { it.isNumber }?.asDouble()
                    key.startsWith("capacity_") ->
                        v.path("service").path(key.removePrefix("capacity_"))
                            .path("capacity_margin_min_per_msg").takeIf { it.isNumber }?.asDouble()
                    key == "max_gap" -> v.path("service").properties().asSequence()
                        .mapNotNull { it.value.path("max_gap_s").takeIf { n -> n.isNumber }?.asDouble() }
                        .maxOrNull()
                    key == "capacity" -> v.path("service").properties().asSequence()
                        .mapNotNull {
                            it.value.path("capacity_margin_min_per_msg")
                                .takeIf { n -> n.isNumber }?.asDouble()
                        }
                        .minOrNull()
                    key == "cost" -> v.path("logistics").path("cost_proxy").asDouble()
                    key == "deployment_days" -> v.path("logistics").path("deployment_days").asDouble()
                    key == "degradation" -> v.path("resilience").path("degradation_dmax_gap_s").asDouble()
                    else -> null
                }
                // направление: true — больше лучше
                fun higherBetter(key: String) = key.startsWith("coverage_") ||
                    key.startsWith("capacity") || key == "capacity"

                // пороги требований — фильтр с перечнем «кто и по какому выбыл»
                val excluded = mapper.createArrayNode()
                val passing = evaluated.filter { v ->
                    var ok = true
                    req.path("thresholds").forEach { t ->
                        val key = t.path("metric").asText()
                        val value = metricOf(v, key) ?: return@forEach
                        val limit = t.path("value").asDouble()
                        val violated = if (higherBetter(key)) value < limit else value > limit
                        if (violated) {
                            ok = false
                            excluded.addObject()
                                .put("variant", v.path("variant").asText())
                                .put("name", v.path("name").asText())
                                .put("threshold", t.path("label").asText(key))
                                .put("metric", key)
                                .put("value", value)
                                .put("limit", limit)
                        }
                    }
                    ok
                }

                // Парето по осям (2–3): недоминируемые среди прошедших пороги
                val axes = req.path("axes").map { it.asText() }
                    .ifEmpty { listOf("capacity", "max_gap", "cost") }
                fun dominates(a: JsonNode, b: JsonNode): Boolean {
                    var strictly = false
                    axes.forEach { ax ->
                        val av = metricOf(a, ax) ?: return false
                        val bv = metricOf(b, ax) ?: return false
                        val better = if (higherBetter(ax)) av >= bv else av <= bv
                        if (!better) return false
                        val strictlyBetter = if (higherBetter(ax)) av > bv else av < bv
                        if (strictlyBetter) strictly = true
                    }
                    return strictly
                }
                val nondominated = passing.filter { v ->
                    passing.none { other -> other !== v && dominates(other, v) }
                }.map { it.path("variant").asText() }

                val out = mapper.createObjectNode()
                out.put("scenario_ref", scenarioId)
                out.put("computed_at", java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).toString())
                out.put("working_variant", scenario.doc.path("constellation_ref").asText(""))
                val varr = out.putArray("variants")
                evaluated.forEach { varr.add(it) }
                out.set<ObjectNode>("excluded", excluded)
                out.putArray("axes").also { a -> axes.forEach(a::add) }
                out.putArray("pareto").also { a -> nondominated.forEach(a::add) }

                // хранимый результат: сценарий + версии входов (TZ-COM-006);
                // рендер AoA возьмёт последний, выпуск зафиксирует снимок
                val versions = buildMap {
                    put(scenarioId, scenario.version)
                    put(demandMap.id, demandMap.version)
                    variantIds.forEach { vid -> put(vid, boundary.objects.current(vid)!!.version) }
                }
                boundary.results.insert(
                    scenarioId, "constellation_compare", out, versions,
                    orbita.bal.BAL_MODULE_VERSION, rngSeed = 0L,
                )
                respond(ex, 200, out)
            }

            // МВП-М2 §1: «рабочий» вариант — ссылка сценария; смена — явным
            // действием с основанием (изменение через процедуру)
            method == "POST" && path == "/scenarios/working-constellation" -> {
                val req = mapper.readTree(body(ex))
                val scenarioId = req.path("scenario").asText("")
                val variant = req.path("constellation").asText("")
                val by = author(req)
                val scenario = boundary.objects.current(scenarioId)
                    ?: throw NoSuchElementException("сценарий $scenarioId не найден")
                val v = boundary.objects.current(variant)
                    ?: throw NoSuchElementException("построение $variant не найдено")
                require(v.type == "constellation") { "'$variant' — не построение" }
                val changes = mapper.createObjectNode()
                changes.put("constellation_ref", variant)
                boundary.editing.update(
                    CoreType.Scenario, scenarioId, changes, scenario.version, by,
                    changeRef = "смена рабочего варианта построения на $variant",
                )
                respond(ex, 200, mapper.createObjectNode().put("working", variant))
            }

            // §6 МВП-М1: географические маски зон — слой карты (точки зон
            // приёма и сброса с радиусами; геометрия — сервером, клиент рисует)
            method == "GET" && path == "/views/geo-masks" -> {
                val q = query(ex)
                val scenarioId = q["scenario"] ?: throw IllegalArgumentException(
                    "query parameter 'scenario' is required",
                )
                val scenario = boundary.objects.current(scenarioId)
                    ?: throw NoSuchElementException("сценарий $scenarioId не найден")
                val constellation = scenario.doc.path("constellation_ref").asText("")
                    .takeIf { it.isNotBlank() }?.let { boundary.objects.current(it) }
                    ?: throw NoSuchElementException("группировка по ссылке сценария не найдена")
                val demandMap = scenario.doc.path("demand_map_ref").asText("")
                    .takeIf { it.isNotBlank() }?.let { boundary.objects.current(it) }
                    ?: throw NoSuchElementException("карта спроса по ссылке сценария не найдена")
                val stationsObj = boundary.objects.listCurrent(requireProject(project))
                    .firstOrNull { it.type == "ground_stations" }
                val parsed = orbita.bal.parseConstellationDoc(constellation.doc)
                val masks = orbita.ka.buildMasks(
                    demandMap.doc,
                    stationsObj?.doc ?: mapper.createObjectNode().set("stations", mapper.createArrayNode()),
                    parsed.minAltKm,
                )
                val out = mapper.createObjectNode()
                out.put("rx_radius_km", masks.rxRadiusKm)
                out.put("downlink_radius_km", masks.downlinkRadiusKm)
                out.putArray("rx").also { a ->
                    masks.rxCells.forEach { a.addArray().add(it.lat).add(it.lon) }
                }
                out.putArray("downlink").also { a ->
                    masks.downlinkCells.forEach { a.addArray().add(it.lat).add(it.lon) }
                }
                respond(ex, 200, out)
            }

            // §6 МВП-М1: наземные трассы подгрупп — каждая своим цветом
            // (индекс подгруппы; палитра у клиента — раскраска, не расчёт).
            // Один виток самой низкой подгруппы: сверка рисунка глазами.
            method == "GET" && path == "/views/ground-tracks" -> {
                val q = query(ex)
                val scenarioId = q["scenario"] ?: throw IllegalArgumentException(
                    "query parameter 'scenario' is required",
                )
                val scenario = boundary.objects.current(scenarioId)
                    ?: throw NoSuchElementException("сценарий $scenarioId не найден")
                val constellation = scenario.doc.path("constellation_ref").asText("")
                    .takeIf { it.isNotBlank() }?.let { boundary.objects.current(it) }
                    ?: throw NoSuchElementException("группировка по ссылке сценария не найдена")
                val epoch = scenario.doc.path("epoch").asText("")
                    .ifBlank { throw IllegalArgumentException("scenario '$scenarioId' has no epoch") }
                val parsed = orbita.bal.parseConstellationDoc(constellation.doc)
                val orbitS = orbita.bal.orbitalPeriodS(parsed.minAltKm)
                val out = mapper.createObjectNode()
                out.put("scenario_ref", scenarioId)
                out.put("duration_s", orbitS)
                val groups = out.putArray("subgroups")
                val bySub = parsed.slotsBySubgroup().ifEmpty {
                    listOf(null to parsed.slots) // explicit: одна группа без имени
                }
                bySub.forEachIndexed { gi, (g, slots) ->
                    val node = groups.addObject()
                    node.put("name", g?.name ?: "перечень орбит")
                    node.put("kind", g?.kind ?: "explicit")
                    node.put("color_index", gi)
                    val tracks = boundary.visibility.groundTracksSlots(slots, epoch, orbitS, stepS = 60.0)
                    val arr = node.putArray("tracks")
                    tracks.forEach { (sat, pts) ->
                        val t = arr.addObject().put("sat", sat)
                        val pa = t.putArray("points")
                        pts.forEach { (_, lat, lon) ->
                            pa.addArray().add(lat).add(lon)
                        }
                    }
                }
                respond(ex, 200, out)
            }

            // Карта покрытия (шаг 16 §2.2): всё — от ХРАНИМЫХ объектов по ссылкам
            // сценария, по образцу mask-schedule. Показывается ЗОНА ОБСЛУЖИВАНИЯ,
            // не footprint (TZ-MOD-006): углы — те же, что в GeoMasks (25°/10°).
            // Значение И класс каждой ячейки считает сервер, клиент красит
            // (ловушка 2). Пустые состояния — рабочие: 409 с шагом мастера,
            // где заводится недостающее, а не 404 и не 500.
            method == "GET" && path == "/views/coverage" -> {
                val q = query(ex)
                val scenarioId = q["scenario"] ?: throw IllegalArgumentException(
                    "query parameter 'scenario' is required: выберите сценарий из /objects?type=scenario",
                )
                val horizon = q["horizon"] ?: "day"
                if (horizon !in setOf("orbit", "day", "run")) {
                    throw IllegalArgumentException("query parameter 'horizon' must be one of: orbit, day, run")
                }
                val scenario = boundary.objects.current(scenarioId)
                    ?: return respondMissing(ex, "сценарий $scenarioId в модели отсутствует: заведите его на Ш5 «Входы моделирования»", 5)
                val constellation = scenario.doc.path("constellation_ref").asText("")
                    .takeIf { it.isNotBlank() }?.let { boundary.objects.current(it) }
                    ?: return respondMissing(ex, "группировка по ссылке сценария не найдена: заведите её на Ш5 «Входы моделирования»", 5)
                val demandMap = scenario.doc.path("demand_map_ref").asText("")
                    .takeIf { it.isNotBlank() }?.let { boundary.objects.current(it) }
                    ?: return respondMissing(ex, "карта спроса по ссылке сценария не найдена: постройте её на Ш2 «Карта спроса»", 2)
                val cellNodes = demandMap.doc.path("cells")
                if (!cellNodes.isArray || cellNodes.isEmpty) {
                    return respondMissing(ex, "карта спроса пуста: постройте её на Ш2 «Карта спроса»", 2)
                }
                val durationS = scenario.doc.path("duration_s").asDouble(0.0)
                if (durationS <= 0.0) throw IllegalArgumentException("scenario '$scenarioId' has no positive duration_s")
                val epoch = scenario.doc.path("epoch").asText("")
                if (epoch.isBlank()) throw IllegalArgumentException("scenario '$scenarioId' has no epoch")

                // составное построение (МВП-М1): подгруппы, объединение
                // пролётов — само собой (пролёты всех КА в одном расписании)
                val parsed = orbita.bal.parseConstellationDoc(constellation.doc)
                val targets = cellNodes.map {
                    orbita.bal.GridPoint(it.path("cell_id").asText(), it.path("lat_deg").asDouble(), it.path("lon_deg").asDouble())
                }
                val vis = boundary.visibility.scheduleSlots(
                    parsed.slots, epoch, durationS,
                    minElevDeg = 10.0, targets = targets, scenarioRef = scenarioId,
                    serviceElevDeg = 25.0,
                )
                // Дальше — только пролёты в зоне обслуживания: там, где линия
                // не замыкается, сервиса нет, и красить ячейку зелёным нельзя.
                val serviceVis = mapper.createObjectNode()
                val servicePasses = serviceVis.putArray("passes")
                vis["passes"].forEach { p ->
                    if (p.path("in_service_zone").asBoolean(false)) servicePasses.add(p)
                }
                val heatByCell = orbita.bal.VizData.availabilityHeatmap(serviceVis, durationS, parsed.minAltKm)
                    .path("cells").associateBy { it.path("cell_id").asText() }
                val byTarget = orbita.bal.coverageByTarget(serviceVis, durationS, targets = targets.map { it.id })
                val windowsByCell = linkedMapOf<String, MutableList<Pair<Double, Double>>>()
                servicePasses.forEach { p ->
                    windowsByCell.getOrPut(p["target_ref"].asText()) { mutableListOf() } +=
                        p["start_s"].asDouble() to p["end_s"].asDouble()
                }

                val out = mapper.createObjectNode()
                out.put("scenario_ref", scenarioId)
                out.put("horizon", horizon)
                out.putObject("horizons")
                    .put("orbit_s", orbita.bal.orbitalPeriodS(parsed.minAltKm))
                    .put("day_s", 86400.0)
                    .put("run_s", durationS)
                // §5 МВП-М1: ёмкостная мера ячейки — проходо-минуты (сумма
                // длительностей ВСЕХ сервисных пролётов, без слияния окон:
                // два КА над ячейкой — двойная ёмкость). Ёмкости канала в
                // модели КА нет — при её появлении мера умножается на канал.
                val passMinutes = linkedMapOf<String, Double>()
                servicePasses.forEach { p ->
                    val id = p["target_ref"].asText()
                    passMinutes[id] = (passMinutes[id] ?: 0.0) +
                        (p["end_s"].asDouble() - p["start_s"].asDouble()) / 60.0
                }
                // геометрия ячеек для карты: шаг сетки по широтным кольцам,
                // долготный — равноплощадный (как строится сама сетка спроса)
                val uniqueLats = cellNodes.map { it.path("lat_deg").asDouble() }
                    .distinct().sorted()
                val latStep = uniqueLats.zipWithNext { a, b -> b - a }
                    .filter { it > 1e-6 }.minOrNull() ?: 0.9
                val cellsOut = out.putArray("cells")
                cellNodes.forEach { cellNode ->
                    val id = cellNode.path("cell_id").asText()
                    val metrics = byTarget.getValue(id)
                    val heat = heatByCell[id]
                    // непокрытая ячейка в тепловой карте отсутствует — её нули
                    // подставляются здесь, а не теряются вместе с ячейкой
                    val mean = when (horizon) {
                        "run" -> heat?.path("availability_run")?.asDouble() ?: 0.0
                        else -> heat?.path("availability_$horizon")?.asDouble() ?: 0.0
                    }
                    val worst = when (horizon) {
                        "run" -> mean
                        else -> heat?.path("availability_${horizon}_worst")?.asDouble() ?: 0.0
                    }
                    val node = cellsOut.addObject()
                        .put("cell_id", id)
                        .put("lat_deg", cellNode.path("lat_deg").asDouble())
                        .put("lon_deg", cellNode.path("lon_deg").asDouble())
                        .put("availability_mean", mean)
                        .put("availability_worst", worst)
                        .put("class", orbita.bal.coverageClass(mean, worst).code)
                        .put("access_windows", metrics.accessWindows)
                    node.put("pass_minutes", passMinutes[id] ?: 0.0)
                    // §6 слои «спрос» и «запас»: спрос по классам и
                    // обслуживаемо/спрос (метрика 6) — тем же вьюером
                    val demandByClass = node.putObject("demand_by_class")
                    var msgsDay = 0.0
                    cellNode.path("demand").forEach { d ->
                        val cls = d.path("terminal_profile_ref").asText("A_prime")
                        demandByClass.put(
                            cls,
                            demandByClass.path(cls).asDouble(0.0) + d.path("count").asDouble(0.0),
                        )
                        msgsDay += d.path("uplink_msgs_per_day").asDouble(0.0)
                    }
                    if (msgsDay > 0) {
                        node.put(
                            "margin_min_per_msg",
                            (passMinutes[id] ?: 0.0) / (msgsDay * durationS / 86400.0),
                        )
                    }
                    node.put("half_lat_deg", latStep / 2.0)
                    node.put(
                        "half_lon_deg",
                        latStep / 2.0 / Math.cos(Math.toRadians(cellNode.path("lat_deg").asDouble()))
                            .coerceAtLeast(0.1),
                    )
                    metrics.meanGapS?.let { node.put("mean_gap_s", it) }
                    metrics.maxGapS?.let { node.put("max_gap_s", it) }
                    metrics.revisitS?.let { node.put("revisit_s", it) }
                    // Суточное среднее взвешивается профилем активности ячейки
                    // (ловушка 3): пик спроса в провале покрытия простое среднее
                    // по часам скрывает.
                    val diurnal = cellNode.path("diurnal_profile")
                    if (horizon == "day" && diurnal.isArray && diurnal.size() == 24) {
                        val series = orbita.bal.hourlySeries(windowsByCell[id] ?: emptyList(), durationS)
                        val weighted = orbita.bal.VizData.availability(
                            listOf(orbita.bal.HeatCell(id, series)),
                            orbita.bal.Horizon.Daily,
                            diurnal.map { it.asDouble() },
                        ).getValue(id)
                        node.put("availability_weighted", weighted)
                    }
                }
                // §5: числовая шкала и баланс — статистика карты считается
                // сервером; клиент только красит по ней
                val values = cellNodes.map { passMinutes[it.path("cell_id").asText()] ?: 0.0 }
                val margins = cellsOut.mapNotNull {
                    it.path("margin_min_per_msg").takeIf { m -> m.isNumber }?.asDouble()
                }
                val demandTotals = cellsOut.flatMap { c ->
                    c.path("demand_by_class").properties().map { it.key to it.value.asDouble() }
                }.groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
                out.putObject("map_stats")
                    .put("pass_minutes_min", values.minOrNull() ?: 0.0)
                    .put("pass_minutes_max", values.maxOrNull() ?: 0.0)
                    .put("pass_minutes_total", values.sum())
                    .put("cells_out_of_view", values.count { it <= 0.0 })
                    .put("margin_min", margins.minOrNull() ?: 0.0)
                    .put("margin_max", margins.maxOrNull() ?: 0.0)
                    .also { ms ->
                        val dm = (ms as ObjectNode).putObject("demand_max_by_class")
                        cellsOut.flatMap { c ->
                            c.path("demand_by_class").properties().map { it.key to it.value.asDouble() }
                        }.groupBy({ it.first }, { it.second })
                            .forEach { (cls, vals) -> dm.put(cls, vals.max()) }
                        val dt = ms.putObject("demand_total_by_class")
                        demandTotals.forEach { (cls, v) -> dt.put(cls, v) }
                    }
                // сводка построения — подписи «итого КА: сумма по подгруппам»
                val cst = out.putObject("constellation")
                cst.put("total_sats", parsed.totalSats)
                val sgArr = cst.putArray("subgroups")
                parsed.subgroups.forEach { g ->
                    sgArr.addObject()
                        .put("name", g.name)
                        .put("kind", g.kind)
                        .put("planes", g.planes)
                        .put("per_plane", g.perPlane)
                        .put("altitude_km", g.altKm)
                        .put("inclination_deg", g.effectiveIncDeg())
                        .put("sats", g.total)
                }
                respond(ex, 200, out)
            }

            // Экран 6: глобус — модель проекта, а не демонстрация пропагатора
            // (шаг 16 §2.3): группировка, станции и ячейки — ХРАНИМЫЕ объекты
            // по ссылкам сценария, умолчаний нет. Собственной модели движения
            // в клиенте нет: трассы считает пропагатор на сервере. Расписание
            // пролётов — из того же расписания видимости, что карта покрытия
            // (общий кэш), времена окон переведены в UTC здесь: клиент
            // подсвечивает и мотает шкалу, но не считает.
            method == "GET" && path == "/views/globe" -> {
                val q = query(ex)
                val scenarioId = q["scenario"] ?: throw IllegalArgumentException(
                    "query parameter 'scenario' is required: выберите сценарий из /objects?type=scenario",
                )
                val scenario = boundary.objects.current(scenarioId)
                    ?: return respondMissing(ex, "сценарий $scenarioId в модели отсутствует: заведите его на Ш5 «Входы моделирования»", 5)
                val constellation = scenario.doc.path("constellation_ref").asText("")
                    .takeIf { it.isNotBlank() }?.let { boundary.objects.current(it) }
                    ?: return respondMissing(ex, "группировка по ссылке сценария не найдена: заведите её на Ш5 «Входы моделирования»", 5)
                val demandMap = scenario.doc.path("demand_map_ref").asText("")
                    .takeIf { it.isNotBlank() }?.let { boundary.objects.current(it) }
                    ?: return respondMissing(ex, "карта спроса по ссылке сценария не найдена: постройте её на Ш2 «Карта спроса»", 2)
                val stations = scenario.doc.path("ground_stations_ref").asText("")
                    .takeIf { it.isNotBlank() }?.let { boundary.objects.current(it) }
                    ?: return respondMissing(ex, "набор станций по ссылке сценария не найден: заведите его на Ш5 «Входы моделирования»", 5)
                val cellNodes = demandMap.doc.path("cells")
                if (!cellNodes.isArray || cellNodes.isEmpty) {
                    return respondMissing(ex, "карта спроса пуста: постройте её на Ш2 «Карта спроса»", 2)
                }
                val epoch = q["epoch"] ?: scenario.doc.path("epoch").asText("")
                if (epoch.isBlank()) throw IllegalArgumentException("scenario '$scenarioId' has no epoch")
                val durationS = q["duration_s"]?.toDoubleOrNull() ?: scenario.doc.path("duration_s").asDouble(0.0)
                if (durationS <= 0.0) throw IllegalArgumentException("scenario '$scenarioId' has no positive duration_s")

                val parsed = orbita.bal.parseConstellationDoc(constellation.doc)
                val targets = cellNodes.map {
                    orbita.bal.GridPoint(it.path("cell_id").asText(), it.path("lat_deg").asDouble(), it.path("lon_deg").asDouble())
                }
                val tracks = boundary.visibility.groundTracksSlots(parsed.slots, epoch, durationS)
                val vis = boundary.visibility.scheduleSlots(
                    parsed.slots, epoch, durationS,
                    minElevDeg = 10.0, targets = targets, scenarioRef = scenarioId,
                    serviceElevDeg = 25.0,
                )
                val globeStations = stations.doc.path("stations").map { st ->
                    orbita.bal.VizData.GlobeStation(
                        id = st.path("id").asText(),
                        name = st.path("name").asText(""),
                        latDeg = st.path("lat_deg").asDouble(),
                        lonDeg = st.path("lon_deg").asDouble(),
                    )
                }
                val globeCells = cellNodes.map { c ->
                    orbita.bal.VizData.GlobeCell(
                        id = c.path("cell_id").asText(),
                        latDeg = c.path("lat_deg").asDouble(),
                        lonDeg = c.path("lon_deg").asDouble(),
                        weight = c.path("demand").sumOf { d -> d.path("weight").asDouble(0.0) },
                    )
                }
                val czml = orbita.bal.VizData.czml(
                    parsed.altBySat, epoch, durationS, tracks,
                    stations = globeStations,
                    demandCells = globeCells,
                    serviceRadiusKm = orbita.bal.footprintRadiusKm(parsed.minAltKm, 25.0),
                    mapper = mapper,
                )
                val out = mapper.createObjectNode()
                out.put("scenario_ref", scenarioId)
                out.put("epoch", epoch)
                out.put("duration_s", durationS)
                out.set<ObjectNode>("czml", czml)
                val epochInstant = java.time.Instant.parse(epoch)
                // Расписание ограничено первыми окнами по времени: на полном
                // проекте окон сотни тысяч, и таблица такого размера — не
                // инструмент. Ограничение НЕ тихое: полное число объявлено
                // в passes_total, обрезка — в passes_truncated (ловушка 3 §1.1
                // про счётчики: молчаливая обрезка читалась бы как «всё»).
                val sorted = vis["passes"].sortedBy { it.path("start_s").asDouble() }
                val limit = 500
                out.put("passes_total", sorted.size)
                out.put("passes_truncated", sorted.size > limit)
                val passes = out.putArray("passes")
                sorted.take(limit).forEach { p ->
                    val startS = p.path("start_s").asDouble()
                    val endS = p.path("end_s").asDouble()
                    passes.addObject()
                        .put("spacecraft_ref", p.path("spacecraft_ref").asText())
                        .put("target_ref", p.path("target_ref").asText())
                        .put("start_utc", epochInstant.plusMillis((startS * 1000).toLong()).toString())
                        .put("end_utc", epochInstant.plusMillis((endS * 1000).toLong()).toString())
                        .put("duration_s", endS - startS)
                        .put("in_service_zone", p.path("in_service_zone").asBoolean(false))
                }
                respond(ex, 200, out)
            }

            // Экран 12: система в целом — сводки, бюджеты, матрица рисков
            method == "GET" && path == "/views/system" ->
                respond(ex, 200, mapper.valueToTree(boundary.screens.systemOverview(project)))

            // Экспорт ReqIF (TZ-OUT-005, ADR-023): отображение здесь, XML — в службе
            // обмена. Дата выгрузки фиксируется в файле; параметр exported_at
            // позволяет получить воспроизводимый файл.
            // Контрольные точки (Шаг 17 C4): перечень и даты — из ХРАНИМОГО
            // проекта; без проекта — имена из реестра ворот с пометкой источника.
            // Реестр ворот в любом случае остаётся источником планок статусов.
            method == "GET" && path == "/views/gates" -> {
                // проект контекста (ADR-022): чужие точки не показываются
                val projectObj = project?.let { boundary.objects.current(it) }
                    ?.takeIf { it.status != Lifecycle.Cancelled }
                val out = mapper.createObjectNode()
                val gates = out.putArray("gates")
                if (projectObj != null) {
                    out.put("source", "project")
                    out.put("project_ref", projectObj.id)
                    out.put("project_name", projectObj.doc.path("name").asText(""))
                    out.put("phase", projectObj.doc.path("phase").asText(""))
                    // Длительности промежутков и просрочку считает СЕРВЕР
                    // (STEP-6 §3.2: расчётов в клиенте нет — обход кода клиента
                    // это стережёт и поймал первую же шкалу с Math.round)
                    val today = java.time.LocalDate.now()
                    val known = boundary.req.gates.gateNames
                    // Планирование длительностями (находка прогона: «двигать
                    // сроки адекватно»): дата вехи = её явная due (якорь),
                    // иначе дата предыдущей + duration_days — цепочкой от
                    // последнего якоря. Считает СЕРВЕР (STEP-6 §3.2).
                    // Ответ по О-10 §2: источник сроков — ПЛАНОВЫЕ ДАТЫ вех;
                    // длительность — производная (интервал), отдельно не
                    // хранится, не редактируется и дат больше не выводит
                    var prevDate: java.time.LocalDate? = null
                    // Ф-01, второй пункт: опору календаря считает СЕРВЕР —
                    // веха открывается от предыдущей ЗАДАННОЙ точки, а первая
                    // точка фазы — от последней точки предыдущей фазы, и это
                    // называется вслух (граница фаз перестаёт быть невидимой).
                    val ops = orbita.req.Operations()
                    // предыдущая точка ленты (её имя и её дата) — она и есть
                    // опора: на границе фаз это последняя точка предыдущей фазы
                    var prevGateName: String? = null
                    var prevGateDue: java.time.LocalDate? = null
                    var prevPhase: String? = null
                    projectObj.doc.path("milestones").forEach { m ->
                        val gateName = m.path("gate").asText()
                        val anchor = m.path("due").asText("").takeIf { it.isNotBlank() }
                            ?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
                        val held = m.path("held").asBoolean(false)
                        val phaseOfGate = m.path("phase").asText("").takeIf { it.isNotBlank() }
                            ?: ops.phaseOfGate(gateName)
                        val boundaryOfPhase = prevPhase != null && phaseOfGate != null && phaseOfGate != prevPhase
                        val g = gates.addObject()
                            .put("gate", gateName)
                            .put("due", anchor?.toString())
                            .put("held", held)
                            // дальняя веха (Phase B–F) — план в едином ряду
                            // точек: показывается, но воротами не ведётся
                            .put("in_scope", gateName in known)
                        // опора: точка, от которой открывается календарь этой
                        val opensFromGate = prevGateName
                        if (opensFromGate != null) {
                            val from = g.putObject("opens_from")
                            from.put("gate", opensFromGate)
                            from.put("label", boundary.req.gateLabel(opensFromGate))
                            // дата опоры — дата ИМЕННО той точки; её нет —
                            // календарь опирается на ближайшую заданную ранее
                            (prevGateDue ?: prevDate)?.let { from.put("due", it.toString()) }
                            if (boundaryOfPhase) {
                                from.put("phase_boundary", true)
                                from.put(
                                    "note",
                                    "граница фаз: ${phaseOfGate ?: "фаза"} открывается от точки " +
                                        boundary.req.gateLabel(opensFromGate),
                                )
                            }
                        }
                        m.path("held_at").asText("").takeIf { it.isNotBlank() }?.let { g.put("held_at", it) }
                        phaseOfGate?.let { g.put("phase", it) }
                        if (anchor != null && prevDate != null) {
                            g.put(
                                "days_from_prev",
                                java.time.temporal.ChronoUnit.DAYS.between(prevDate, anchor),
                            )
                        }
                        if (anchor != null && !held && anchor.isBefore(today)) g.put("overdue", true)
                        prevGateName = gateName
                        prevGateDue = anchor
                        prevDate = anchor ?: prevDate
                        phaseOfGate?.let { prevPhase = it }
                    }
                    // О-10: пройденной вехе — решение прохождения; ближайшей —
                    // счётчик незакрытого (без конфигурации — честно нет)
                    val decisions = boundary.objects.listCurrent(projectObj.id)
                        .filter { it.type == "decision" }
                    gates.forEach { gn ->
                        val g = gn as ObjectNode
                        val gname = g.path("gate").asText()
                        g.put("label", boundary.req.gateLabel(gname))
                        if (g.path("held").asBoolean(false)) {
                            decisions.lastOrNull {
                                it.doc.path("question").asText() == "Прохождение точки $gname"
                            }?.let { g.put("decision_rationale", it.doc.path("rationale").asText("")) }
                        }
                    }
                    gates.firstOrNull { !it.path("held").asBoolean(false) }?.let { gn ->
                        val g = gn as ObjectNode
                        runCatching { boundary.gatePassing.issues(g.path("gate").asText(), projectObj.id).size }
                            .onSuccess { g.put("open_count", it) }
                    }
                    // возврат — полосой между лентой и паспортом (О-10);
                    // счётчик открытых замечаний точки — ссылке «замечания →»
                    val ret = projectObj.doc.path("return")
                    if (ret.isObject) {
                        val r = out.putObject("return")
                        r.put("gate", ret.path("gate").asText(""))
                        r.put("reason", ret.path("reason").asText(""))
                        r.put("at", ret.path("at").asText(""))
                        r.put(
                            "open_reviews",
                            boundary.objects.listCurrent(projectObj.id).count {
                                it.type == "review_item" && it.status != Lifecycle.Cancelled &&
                                    it.doc.path("status").asText("") != "closed"
                            },
                        )
                    }
                    // паспорт — второй блок экрана (О-10 §5)
                    val pass = out.putObject("passport")
                    val leadLogin = boundary.auth.listRoles(projectObj.id).entries
                        .firstOrNull { it.value == "lead" }?.key
                    pass.put(
                        "owner",
                        leadLogin?.let { boundary.auth.displayNameOf(it) ?: it }
                            ?: humanAuthor(boundary.objects.history(projectObj.id).firstOrNull()?.createdBy ?: ""),
                    )
                    val mc = projectObj.doc.path("mission_class").asText("")
                    if (mc.isNotBlank()) {
                        pass.putObject("mission_class")
                            .put("id", mc)
                            .put("name", boundary.objects.current(mc)?.doc?.path("name")?.asText("") ?: mc)
                    }
                    pass.set<ArrayNode>("constraints", projectObj.doc.path("constraints").deepCopy())
                    projectObj.doc.path("start_path").takeIf { it.isObject }?.let {
                        pass.set<ObjectNode>("start_path", it.deepCopy())
                    }
                    // стандартные дальние вехи, которых в паспорте ещё нет, —
                    // кнопке «+ вехи Phase B–F» (NPR 7120.5): инженер не обязан
                    // печатать PDR/CDR руками (находка прогона)
                    val present = projectObj.doc.path("milestones").map { it.path("gate").asText() }.toSet()
                    val suggest = out.putArray("suggested_outlook")
                    orbita.req.LifecycleOutlook.default().forEach { (gname, phase) ->
                        if (gname !in present) {
                            suggest.addObject().put("gate", gname).put("phase", phase)
                        }
                    }
                } else {
                    out.put("source", "registry")
                    boundary.maturity.gateNames().sorted().forEach { g ->
                        gates.addObject().put("gate", g)
                    }
                }
                respond(ex, 200, out)
            }

            // Круг 2 стартового потока: приём файла исходного документа.
            // Файл + карточка (тип и наименование обязательны); текст
            // извлекается сервером (docx/PDF/txt) — от него работают
            // аннотация и типовые разборы. Файл хранится при стенде
            // (ORBITA_FILES_DIR, том orbita-files) и входит в копию О-18.
            method == "POST" && path == "/sd-files" -> {
                val q = query(ex)
                val fileName = q["filename"]
                    ?: throw IllegalArgumentException("query 'filename' is required")
                val cardName = q["name"] ?: throw IllegalArgumentException("query 'name' is required: наименование карточки")
                val kind = q["kind"] ?: throw IllegalArgumentException(
                    "query 'kind' is required: mission_note · normative · datasheet · reference · other")
                val area = q["area"] ?: "project"   // project | library (полки А3/А4/В2 — той же формой)
                val fileProject =
                    if (area == "library") orbita.mod.store.ObjectStore.LIBRARY_PROJECT
                    else requireProject(project)
                val bytes = ex.requestBody.readAllBytes()
                require(bytes.isNotEmpty()) { "пустой файл" }
                require(bytes.size <= 50 * 1024 * 1024) { "файл больше 50 МБ" }
                val fileAuthor = author(q["author"] ?: "")
                val doc = mapper.createObjectNode()
                doc.put("name", cardName)
                doc.put("kind", kind)
                q["org"]?.let { doc.put("org", it) }
                q["doc_date"]?.let { doc.put("doc_date", it) }
                q["shelf"]?.let { doc.put("shelf", it) }
                doc.put("rights", q["rights"] ?: "внутренний документ проекта")
                orbita.out.TextExtractor.extract(fileName, bytes)?.let { doc.put("text", it) }
                doc.putObject("file").put("name", fileName).put("size", bytes.size)
                val stored = boundary.editing.create(
                    CoreType.SourceDocument, doc, fileAuthor, fileProject,
                )
                val dir = java.nio.file.Path.of(filesDir(), stored.id)
                java.nio.file.Files.createDirectories(dir)
                java.nio.file.Files.write(dir.resolve(java.nio.file.Path.of(fileName).fileName.toString()), bytes)
                // Д1: разбор — при загрузке, один раз. Дальше документ живёт
                // каноном и картой: службе сырой файл больше не отдаётся.
                val parseId = DocumentParseStore.parseAndStore(
                    filesDir(), stored.id, fileName, bytes, DocumentParseStore.lexiconOf(boundary),
                )
                // Ф-08.1: умолчание «в промпт» — свойство ТИПА документа
                val promptDefault = promptDefaultOf(kind)
                val includedBlocks = when {
                    promptDefault != "on" -> 0
                    // Ф-09: на полке паспорта нет — умолчание ложится на карточку
                    area == "library" -> includeShelfDocumentInPrompt(stored.id, stored.version, fileAuthor)
                    else -> includeDocumentInPrompt(stored.id, fileProject, fileAuthor)
                }
                respond(
                    ex, 201,
                    mapper.createObjectNode()
                        .put("id", stored.id)
                        .put("file", fileName)
                        .put("text_extracted", doc.has("text"))
                        .put("parsed", parseId)
                        .put("prompt_default", promptDefault)
                        .put("blocks_in_prompt", includedBlocks),
                )
            }

            // файл карточки — обратно (пакет уходит людям, файл — инженеру)
            method == "GET" && Regex("^/sd-files/SD-[0-9]{4}$").matches(path) -> {
                val sdId = path.removePrefix("/sd-files/")
                val sd = boundary.objects.current(sdId)
                    ?: throw NoSuchElementException("document '$sdId' not found")
                val fileName = sd.doc.path("file").path("name").asText("")
                require(fileName.isNotBlank()) { "у карточки $sdId нет файла" }
                val f = java.nio.file.Path.of(filesDir(), sdId, java.nio.file.Path.of(fileName).fileName.toString())
                require(java.nio.file.Files.exists(f)) { "файл карточки $sdId не найден в хранилище" }
                respondBinary(ex, java.nio.file.Files.readAllBytes(f), "application/octet-stream", fileName)
            }

            // В1.2: сохранение авторского текста раздела. Отпечаток данных
            // вставок ставит СЕРВЕР на момент сохранения: по нему выпуск
            // узнаёт, что модель уехала из-под текста («текст устарел» —
            // помета, не блокировка). Существующий текст правится процедурой
            // с основанием, если базирован; генерация сюда не пишет никогда.
            method == "PUT" && Regex("^/export/documents/[a-z_]+/sections/[0-9]+/text$").matches(path) -> {
                val parts = path.removePrefix("/export/documents/").split("/")
                val code = parts[0]
                val sectionNo = parts[2].toInt()
                val template = templateOf(code)
                require(template.sections.any { it.number == sectionNo }) {
                    "раздела $sectionNo нет в шаблоне '$code'"
                }
                val req = mapper.readTree(body(ex))
                val textAuthor = author(req.path("author").asText(""))
                require(textAuthor.isNotBlank()) { "field 'author' is required" }
                val text = req.path("text").asText("")
                require(text.isNotBlank()) { "field 'text' is required" }
                val textProject = requireProject(project)
                val model = orbita.out.ModelSnapshot.of(boundary.objects, mapper, projectId = project)
                    .also { m ->
                        // МВП-М2 §3.5: последняя матрица сравнения построений —
                        // вставкой в раздел AoA; выпуск зафиксирует снимок
                        boundary.objects.listCurrent(project)
                            .filter { it.type == "scenario" }
                            .flatMap { sc ->
                                boundary.results.activeForScenario(sc.id, "constellation_compare")
                            }
                            .maxByOrNull { it.pk }
                            ?.let { (m as ObjectNode).set<ObjectNode>("constellation_compare", it.payload.deepCopy()) }
                    }
                val rendered = orbita.out.DocumentGenerator(mapper).render(model, template)
                val fingerprint = rendered.body.path("sections")
                    .first { it.path("number").asInt() == sectionNo }
                    .path("inserts_fingerprint").asText("")
                val doc = mapper.createObjectNode()
                doc.put("template_code", code)
                doc.put("section", sectionNo)
                doc.put("text", text)
                doc.put("inserts_fingerprint", fingerprint)
                val existing = boundary.objects.listCurrent(textProject).firstOrNull {
                    it.type == "section_text" && it.status != Lifecycle.Cancelled &&
                        it.doc.path("template_code").asText() == code &&
                        it.doc.path("section").asInt() == sectionNo
                }
                val stored = if (existing == null) {
                    boundary.editing.create(CoreType.SectionText, doc, textAuthor, textProject)
                } else {
                    boundary.editing.update(
                        CoreType.SectionText, existing.id, doc, existing.version, textAuthor,
                        changeRef = "правка авторского текста раздела $sectionNo документа '$code'",
                    )
                }
                respond(
                    ex, if (existing == null) 201 else 200,
                    mapper.createObjectNode().put("id", stored.id).put("version", stored.version)
                        .put("inserts_fingerprint", fingerprint),
                )
            }

            // В1.4/О-8: печать — выпуск рендерится документом (docx и PDF
            // с сервера); ?issue=DI-NNNN печатает СНИМОК выпуска, без него —
            // текущую генерацию (черновой просмотр). Оформление подтянется
            // вёрсткой по эталону печатной формы.
            method == "GET" && Regex("^/export/documents/[a-z_]+/print\\.(docx|pdf)$").matches(path) -> {
                val fmt = path.substringAfterLast('.')
                val code = path.removePrefix("/export/documents/").substringBefore("/print.")
                val template = templateOf(code)
                val q = query(ex)
                val issueId = q["issue"]
                val printProject = requireProject(project)
                val passportName = boundary.objects.current(printProject)
                    ?.doc?.path("name")?.asText(printProject) ?: printProject
                val (printBody, meta) = if (issueId != null) {
                    val di = boundary.objects.current(issueId)
                        ?: throw NoSuchElementException("issue '$issueId' not found")
                    require(di.type == "document_issue" && di.doc.path("template").asText() == code) {
                        "'$issueId' is not an issue of '$code'"
                    }
                    val snapshot = di.doc.path("snapshot")
                    require(snapshot.isObject && snapshot.size() > 0) {
                        "выпуск '$issueId' сделан до модели снимков (В1.2) — перевыпустите документ"
                    }
                    snapshot to orbita.out.PrintMeta(
                        project = passportName,
                        designation = "$code · $issueId",
                        version = di.version,
                        status = di.doc.path("status").asText("issued"),
                        issuedAt = di.doc.path("issued_at").asText(""),
                        author = di.createdBy,
                    )
                } else {
                    val model = orbita.out.ModelSnapshot.of(boundary.objects, mapper, projectId = project)
                    .also { m ->
                        // МВП-М2 §3.5: последняя матрица сравнения построений —
                        // вставкой в раздел AoA; выпуск зафиксирует снимок
                        boundary.objects.listCurrent(project)
                            .filter { it.type == "scenario" }
                            .flatMap { sc ->
                                boundary.results.activeForScenario(sc.id, "constellation_compare")
                            }
                            .maxByOrNull { it.pk }
                            ?.let { (m as ObjectNode).set<ObjectNode>("constellation_compare", it.payload.deepCopy()) }
                    }
                    val generated = orbita.out.DocumentGenerator(mapper)
                        .render(model, template, sectionTexts(code, project))
                    generated.body to orbita.out.PrintMeta(
                        project = passportName,
                        designation = "$code · текущая генерация",
                        version = "—",
                        status = "черновик просмотра",
                        issuedAt = "не выпускался",
                        author = "—",
                    )
                }
                val renderer = orbita.out.PrintRenderer()
                if (fmt == "docx") {
                    respondBinary(
                        ex, renderer.docx(printBody, meta),
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "$code${issueId?.let { "-$it" } ?: ""}.docx",
                    )
                } else {
                    respondBinary(
                        ex, renderer.pdf(printBody, meta),
                        "application/pdf",
                        "$code${issueId?.let { "-$it" } ?: ""}.pdf",
                    )
                }
            }

            // Выпуск документа (Шаг 17 C5): слепок текущей генерации становится
            // объектом document_issue. Дата выпуска — из запроса, не из часов:
            // воспроизводимость выпусков та же, что у экспорта ReqIF.
            method == "POST" && Regex("^/export/documents/[a-z_]+/issue$").matches(path) -> {
                val code = path.removePrefix("/export/documents/").removeSuffix("/issue")
                val template = templateOf(code)
                val req = mapper.readTree(body(ex))
                val issuedAt = req.path("issued_at").asText("")
                if (issuedAt.isBlank()) throw IllegalArgumentException("'issued_at' is required: дата выпуска — аргумент, не чтение часов")
                val author = author(req.path("author").asText(""))
                if (author.isBlank()) throw IllegalArgumentException("'author' is required (TZ-COM-005)")
                val model = orbita.out.ModelSnapshot.of(boundary.objects, mapper, projectId = project)
                    .also { m ->
                        // МВП-М2 §3.5: последняя матрица сравнения построений —
                        // вставкой в раздел AoA; выпуск зафиксирует снимок
                        boundary.objects.listCurrent(project)
                            .filter { it.type == "scenario" }
                            .flatMap { sc ->
                                boundary.results.activeForScenario(sc.id, "constellation_compare")
                            }
                            .maxByOrNull { it.pk }
                            ?.let { (m as ObjectNode).set<ObjectNode>("constellation_compare", it.payload.deepCopy()) }
                    }
                val generated = orbita.out.DocumentGenerator(mapper)
                    .render(model, template, sectionTexts(code, project))
                val issue = mapper.createObjectNode()
                issue.put("template", template.code)
                issue.put("digest", generated.digest)
                issue.put("issued_at", issuedAt)
                issue.put("status", "issued")
                issue.put("gaps", generated.gaps.size)
                // В1.2: выпуск фиксирует снимок — авторские тексты и данные
                // вставок на момент выпуска, целиком
                issue.set<ObjectNode>("snapshot", generated.body)
                val stored = boundary.editing.create(CoreType.DocumentIssue, issue, author, requireProject(project))
                respond(ex, 201, summary(stored))
            }

            // Выпуски документа со сверкой слепков: расхождение текущей
            // генерации с выпущенной — факт, а не ощущение (Шаг 17 C5)
            method == "GET" && Regex("^/export/documents/[a-z_]+/issues$").matches(path) -> {
                val code = path.removePrefix("/export/documents/").removeSuffix("/issues")
                val template = templateOf(code)
                val model = orbita.out.ModelSnapshot.of(boundary.objects, mapper, projectId = project)
                    .also { m ->
                        // МВП-М2 §3.5: последняя матрица сравнения построений —
                        // вставкой в раздел AoA; выпуск зафиксирует снимок
                        boundary.objects.listCurrent(project)
                            .filter { it.type == "scenario" }
                            .flatMap { sc ->
                                boundary.results.activeForScenario(sc.id, "constellation_compare")
                            }
                            .maxByOrNull { it.pk }
                            ?.let { (m as ObjectNode).set<ObjectNode>("constellation_compare", it.payload.deepCopy()) }
                    }
                val currentDigest = orbita.out.DocumentGenerator(mapper)
                    .render(model, template, sectionTexts(code, project)).digest
                val out = mapper.createObjectNode()
                out.put("template", template.code)
                out.put("current_digest", currentDigest)
                val issues = out.putArray("issues")
                boundary.objects.listCurrent(project)
                    .filter { it.type == "document_issue" && it.doc.path("template").asText() == template.code }
                    .sortedBy { it.id }
                    .forEach { di ->
                        issues.addObject()
                            .put("id", di.id)
                            .put("digest", di.doc.path("digest").asText())
                            .put("issued_at", di.doc.path("issued_at").asText())
                            .put("status", di.doc.path("status").asText())
                            .put("gaps", di.doc.path("gaps").asInt(0))
                            .put("stale", di.doc.path("digest").asText() != currentDigest)
                    }
                respond(ex, 200, out)
            }

            // Документы БП-PA из модели (TZ-OUT-001, шаг 16 §2.4): чистая функция
            // модели, ручное дополнение текста не сохраняется — правка вносится
            // в модель. Пустой раздел остаётся на месте вместе с разрывом.
            method == "GET" && path == "/export/documents" -> {
                val arr = mapper.createArrayNode()
                libraryTemplates().forEach { t ->
                    arr.addObject().put("code", t.code).put("title", t.title).put("source", t.source)
                }
                respond(ex, 200, arr)
            }

            method == "GET" && path.startsWith("/export/documents/") -> {
                val code = path.removePrefix("/export/documents/")
                val template = templateOf(code)
                val model = orbita.out.ModelSnapshot.of(boundary.objects, mapper, projectId = project)
                    .also { m ->
                        // МВП-М2 §3.5: последняя матрица сравнения построений —
                        // вставкой в раздел AoA; выпуск зафиксирует снимок
                        boundary.objects.listCurrent(project)
                            .filter { it.type == "scenario" }
                            .flatMap { sc ->
                                boundary.results.activeForScenario(sc.id, "constellation_compare")
                            }
                            .maxByOrNull { it.pk }
                            ?.let { (m as ObjectNode).set<ObjectNode>("constellation_compare", it.payload.deepCopy()) }
                    }
                val doc = orbita.out.DocumentGenerator(mapper)
                    .render(model, template, sectionTexts(code, project))
                val out = mapper.createObjectNode()
                out.set<ObjectNode>("body", doc.body)
                out.put("digest", doc.digest)
                val gaps = out.putArray("gaps")
                doc.gaps.forEach { g ->
                    gaps.addObject().put("section", g.section).put("what", g.what).put("expected", g.expected)
                }
                respond(ex, 200, out)
            }

            // Проверка отображения ПЕРЕД выгрузкой ReqIF (шаг 16 §2.4, ADR-023):
            // замечания к словарю атрибутов, описания типов и объекты, у которых
            // составное значение свёрнуто в строку. Файл при таких замечаниях
            // валиден — терпит принимающий инструмент, поэтому предупреждение
            // показывается инженеру рядом с кнопкой выгрузки, а не прячется в лог.
            method == "GET" && path == "/export/reqif/check" -> {
                val model = orbita.out.ModelSnapshot.of(boundary.objects, mapper, projectId = project)
                    .also { m ->
                        // МВП-М2 §3.5: последняя матрица сравнения построений —
                        // вставкой в раздел AoA; выпуск зафиксирует снимок
                        boundary.objects.listCurrent(project)
                            .filter { it.type == "scenario" }
                            .flatMap { sc ->
                                boundary.results.activeForScenario(sc.id, "constellation_compare")
                            }
                            .maxByOrNull { it.pk }
                            ?.let { (m as ObjectNode).set<ObjectNode>("constellation_compare", it.payload.deepCopy()) }
                    }
                val flattened = model.path("requirements")
                    .map { orbita.out.toSpecObject(it) }
                    .filter { orbita.out.flattenedAsString(it).isNotEmpty() }
                    .map { it.identifier }
                val out = mapper.createObjectNode()
                val issues = out.putArray("mapping_issues")
                orbita.out.mappingIssues().forEach(issues::add)
                val flat = out.putArray("flattened")
                flattened.forEach(flat::add)
                val dts = out.putObject("datatypes")
                orbita.out.datatypeDefinitions().forEach { (name, d) ->
                    val n = dts.putObject(name)
                    n.put("type", d.type)
                    d.values?.let { vs -> n.putArray("values").also { a -> vs.forEach(a::add) } }
                }
                respond(ex, 200, out)
            }

            // Выгрузка в форматы обмена помимо ReqIF (TZ-OUT-005: «в ReqIF и CSV»).
            // reqif-lite JSON — направление только наружу (Шаг 16 §2.1): ввод идёт
            // настоящим ReqIF через службу обмена.
            method == "GET" && path == "/export/exchange" -> {
                val format = query(ex)["format"] ?: "csv"
                if (format !in setOf("csv", "json")) {
                    throw IllegalArgumentException("query parameter 'format' must be one of: csv, json")
                }
                val model = orbita.out.ModelSnapshot.of(boundary.objects, mapper, projectId = project)
                    .also { m ->
                        // МВП-М2 §3.5: последняя матрица сравнения построений —
                        // вставкой в раздел AoA; выпуск зафиксирует снимок
                        boundary.objects.listCurrent(project)
                            .filter { it.type == "scenario" }
                            .flatMap { sc ->
                                boundary.results.activeForScenario(sc.id, "constellation_compare")
                            }
                            .maxByOrNull { it.pk }
                            ?.let { (m as ObjectNode).set<ObjectNode>("constellation_compare", it.payload.deepCopy()) }
                    }
                val requirements = model.path("requirements").map { r ->
                    orbita.out.ExchangeRequirement(
                        id = r.path("id").asText(),
                        // атрибуты — поля документа как есть: незнакомое поле
                        // получает колонку, а не выбрасывается
                        attributes = r.properties().asSequence()
                            .filter { (k, _) -> k != "id" }
                            .associate { (k, v) -> k to v },
                    )
                }
                val links = (boundary.links.list("trace", project) + boundary.links.list("derive", project))
                val doc = orbita.out.toExchange(requirements, links, exportedAt = query(ex)["exported_at"])
                if (format == "json") {
                    ex.responseHeaders.add("Content-Disposition", "attachment; filename=\"orbita-requirements.json\"")
                    respond(ex, 200, orbita.out.exchangeToJson(doc, mapper))
                } else {
                    val csv = orbita.out.toCsv(doc.requirements)
                    ex.responseHeaders.add("Content-Type", "text/csv; charset=utf-8")
                    ex.responseHeaders.add("Content-Disposition", "attachment; filename=\"orbita-requirements.csv\"")
                    val bytes = csv.toByteArray()
                    ex.sendResponseHeaders(200, bytes.size.toLong())
                    ex.responseBody.use { it.write(bytes) }
                }
            }

            method == "GET" && path == "/export/reqif" -> {
                val exchangeUrl = System.getenv("ORBITA_EXCHANGE_URL")
                if (exchangeUrl.isNullOrBlank()) {
                    // Отказ, а не заглушка: файл без службы не собрать, и молчаливый
                    // пустой ответ выглядел бы работающим экспортом
                    respond(
                        ex, 503,
                        mapper.createObjectNode()
                            .put("error", "служба обмена не настроена: задайте ORBITA_EXCHANGE_URL")
                            .put("adr", "ADR-023"),
                    )
                } else {
                    val model = orbita.out.ModelSnapshot.of(boundary.objects, mapper, projectId = project)
                    .also { m ->
                        // МВП-М2 §3.5: последняя матрица сравнения построений —
                        // вставкой в раздел AoA; выпуск зафиксирует снимок
                        boundary.objects.listCurrent(project)
                            .filter { it.type == "scenario" }
                            .flatMap { sc ->
                                boundary.results.activeForScenario(sc.id, "constellation_compare")
                            }
                            .maxByOrNull { it.pk }
                            ?.let { (m as ObjectNode).set<ObjectNode>("constellation_compare", it.payload.deepCopy()) }
                    }
                    val links = (boundary.links.list("trace", project) + boundary.links.list("derive", project))
                        .map { orbita.out.ExchangeLink(it.fromId, it.toId, it.kind) }
                    val exportedAt = query(ex)["exported_at"]
                        ?: java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
                            .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    val payload = orbita.out.ReqifExport.payload(model, links, exportedAt, mapper)
                    val xml = postToExchange("$exchangeUrl/reqif/export", payload.toString())
                    ex.responseHeaders.add("Content-Type", "application/xml; charset=utf-8")
                    ex.responseHeaders.add(
                        "Content-Disposition",
                        "attachment; filename=\"orbita-requirements.reqif\"",
                    )
                    val bytes = xml.toByteArray()
                    ex.sendResponseHeaders(200, bytes.size.toLong())
                    ex.responseBody.use { it.write(bytes) }
                }
            }

            // Циклограмма из географических масок (TZ-KA-009, Р4/ADR-004):
            // маски генерируются из ХРАНИМЫХ карты спроса и станций, доли витка —
            // по трассе аппарата. Ответ сравнивает их с ручными из модели;
            // подстановка в модель — решение инженера, не автоматика.
            method == "GET" && path == "/views/spacecraft/mask-schedule" -> {
                val current = boundary.objects.listCurrent(project)
                val demandMap = current.firstOrNull { it.type == "demand_map" }
                    ?: throw NoSuchElementException("карта спроса не загружена: маску не из чего строить")
                val stations = current.firstOrNull { it.type == "ground_stations" }
                    ?: throw NoSuchElementException("станции не заданы: маску сброса не из чего строить")
                val constellation = current.firstOrNull { it.type == "constellation" }
                    ?: throw NoSuchElementException("группировка не задана: трассу не по чему считать")
                val spacecraft = current.firstOrNull { it.type == "spacecraft" }

                val parsed = orbita.bal.parseConstellationDoc(constellation.doc)
                val masks = orbita.ka.buildMasks(demandMap.doc, stations.doc, parsed.minAltKm)
                val epoch = query(ex)["epoch"] ?: "2026-03-20T00:00:00.000Z"
                val durationS = query(ex)["duration_s"]?.toDoubleOrNull() ?: 86400.0
                // трасса одного аппарата: в симметричном Уокере статистика
                // долей витка одна на всех; для составного — первый КА первой
                // подгруппы (сводка режимов, не точный расчёт по каждому)
                val track = boundary.visibility
                    .groundTracksSlots(parsed.slots.take(1), epoch, durationS)
                    .values.first().map { (_, lat, lon) -> orbita.ka.MaskPoint(lat, lon) }
                val fractions = orbita.ka.modeFractions(track, masks)

                val out = mapper.createObjectNode()
                out.put("mask_version", masks.version)
                out.put("rx_cells", masks.rxCells.size)
                out.put("downlink_cells", masks.downlinkCells.size)
                val generated = out.putObject("generated_orbit_fractions")
                fractions.forEach { (mode, f) -> generated.put(mode, f) }
                spacecraft?.doc?.path("modes")?.takeIf { it.isArray }?.let { modes ->
                    val manual = out.putObject("model_orbit_fractions")
                    modes.forEach { m ->
                        manual.put(m.path("name").asText(), m.path("orbit_fraction").asDouble())
                    }
                }
                respond(ex, 200, out)
            }

            // Рекомендательное размещение станций (шаг 12.1, Концепция 5.4).
            // Ручные станции берутся из ХРАНИМОГО набора и не переписываются:
            // подбор строится поверх них, предложенное помечено происхождением.
            method == "POST" && path == "/ground/suggest" -> {
                val req = mapper.readTree(body(ex))
                val stored = boundary.objects.listCurrent(project)
                    .firstOrNull { it.type == "ground_stations" }
                val manual = stored?.doc?.path("stations")?.map { s ->
                    orbita.bal.StationSite(
                        id = s.path("id").asText(),
                        name = s.path("name").asText(""),
                        lat = s.path("lat_deg").asDouble(),
                        lon = s.path("lon_deg").asDouble(),
                        placement = s.path("placement").asText("manual"),
                    )
                } ?: emptyList()
                val candidates = req.path("candidates").map { c ->
                    orbita.bal.StationSite(
                        id = c.path("id").asText(""),
                        name = c.path("name").asText(""),
                        lat = c.path("lat_deg").asDouble(),
                        lon = c.path("lon_deg").asDouble(),
                    )
                }
                val inclination = req.path("inclination_deg").asDouble(53.0)
                val altKm = req.path("alt_km").asDouble(550.0)
                val result = orbita.bal.suggestStations(
                    candidates = candidates,
                    inclinationDeg = inclination,
                    altKm = altKm,
                    k = req.path("k").asInt(1),
                    existing = manual,
                )
                val out = mapper.createObjectNode()
                val suggested = out.putArray("suggested")
                result.suggested.forEach { s ->
                    suggested.addObject()
                        .put("id", s.id).put("name", s.name)
                        .put("lat_deg", s.lat).put("lon_deg", s.lon)
                        .put("placement", s.placement)
                        .put(
                            "gain",
                            orbita.bal.stationGain(
                                result.placed.takeWhile { it !== s }, s, inclination, altKm,
                            ),
                        )
                }
                out.put("coverage_before", orbita.bal.coverage(manual, inclination, altKm))
                out.put("coverage_after", orbita.bal.coverage(result.placed, inclination, altKm))
                out.put("manual_kept", result.placed.count { it.placement == "manual" })
                respond(ex, 200, out)
            }

            // Импорт записи каталога устройств (шаг 14, ADR-024). ПО ОДНОЙ ЗАПИСИ,
            // по действию инженера: массовой синхронизации каталога нет как пути —
            // правовой режим источника (sui generis) запрещает выгрузку целиком.
            // Возвращается ЧЕРНОВИК с замечаниями фильтра, не хранимый объект:
            // источник не знает наших параметров генерации, и выдумывать их
            // импорту нельзя. Хранение — обычный канал после дополнения,
            // теми же правилами, что рукописный ввод.
            // Спина процесса (блок B, ADR-029): состояние операций фазы,
            // прохождение точки проверкой, возвраты §5.1
            // МВП-П1: назначение заданий — с готовности и с разрыва; пачка
            // идемпотентна, право — руководитель и ведущий СИ (при учётках)
            method == "POST" && path == "/tasks/assign" -> {
                val req = mapper.readTree(body(ex))
                val gaps = req.path("gaps").map {
                    ProcessTasks.GapRef(
                        it.path("id").asText(),
                        it.path("title").asText(""),
                        it.path("place").asText("").ifBlank { null },
                    )
                }
                val (created, skipped) = boundary.processTasks.assign(
                    gate = req.path("gate").asText(),
                    gaps = gaps,
                    assignee = req.path("assignee").asText(),
                    due = req.path("due").asText("").ifBlank { null },
                    note = req.path("note").asText("").ifBlank { null },
                    author = author(req),
                    projectId = requireProject(project),
                    authorLogin = currentAuthorLogin.get(),
                )
                val out = mapper.createObjectNode()
                out.putArray("created").also { a -> created.forEach(a::add) }
                out.putArray("existing").also { a -> skipped.forEach(a::add) }
                respond(ex, 201, out)
            }

            // МВП-П1: «Мои задания» — личный разрез готовности; без assignee
            // (руководителю) — все задания проекта
            method == "GET" && path == "/views/my-tasks" -> {
                val who = query(ex)["assignee"]?.ifBlank { null }
                respond(ex, 200, boundary.processTasks.myTasks(requireProject(project), who))
            }

            method == "GET" && path == "/views/operations" ->
                respond(ex, 200, boundary.gatePassing.operationStates(requireProject(project)))

            method == "POST" && path == "/gates/return/resolve" -> {
                val req = mapper.readTree(body(ex))
                respond(
                    ex, 200,
                    boundary.gatePassing.resolveReturn(
                        req.path("note").asText(""), author(req), requireProject(project),
                    ),
                )
            }

            // О-11: структурная готовность — группы агрегатов с местом починки
            method == "GET" && path == "/views/gate-readiness" -> {
                val p = requireProject(project)
                val gateOrNull = query(ex)["gate"] ?: boundary.gatePassing.nextGate(p)
                if (gateOrNull == null) {
                    // горизонт исчерпан — честное состояние, не ошибка
                    respond(ex, 200, mapper.createObjectNode().put("horizon_done", true))
                    return
                }
                val gate = gateOrNull
                val checks = boundary.gatePassing.readiness(gate, p)
                val out = mapper.createObjectNode()
                out.put("gate", gate)
                out.put("label", boundary.req.gateLabel(gate))
                boundary.objects.current(p)!!.doc.path("milestones")
                    .firstOrNull { it.path("gate").asText() == gate }
                    ?.path("due")?.asText("")?.ifBlank { null }
                    ?.let { out.put("due", it) }
                out.put("open_total", checks.count { it.state == "open" })
                out.put("blocking_open", checks.count { it.state == "open" && it.blocking })
                out.put("total", checks.count { it.state != "na" })
                out.put("na_total", checks.count { it.state == "na" })
                val titles = mapOf(
                    "blocking" to "Блокирует фиксацию",
                    "statement" to "Постановка и требования",
                    "ai" to "Служба ИИ",
                    "risks" to "Риски",
                )
                val groups = out.putArray("groups")
                listOf("blocking", "statement", "ai", "risks").forEach { key ->
                    val inGroup = checks.filter { it.group == key }
                    if (inGroup.isEmpty()) return@forEach
                    val g = groups.addObject()
                    g.put("key", key)
                    g.put("title", titles[key])
                    g.put("open", inGroup.count { it.state == "open" })
                    val arr = g.putArray("checks")
                    inGroup.forEach { c ->
                        val n = arr.addObject()
                            .put("id", c.id).put("title", c.title).put("state", c.state)
                            .put("blocking", c.blocking).put("note", c.note)
                        c.place?.let { n.put("place", it) }
                        c.naRationale?.let {
                            n.put("na_rationale", it)
                            n.put("na_author", humanAuthor(c.naAuthor ?: ""))
                            n.put("na_at", c.naAt ?: "")
                        }
                    }
                }
                respond(ex, 200, out)
            }

            // О-11: tailoring — проверка неприменима к точке (след с автором);
            // remove: true снимает запись — отмена возможна
            method == "POST" && path == "/views/gate-readiness/na" -> {
                val req = mapper.readTree(body(ex))
                val p = requireProject(project)
                val gate = req.path("gate").asText("").ifBlank {
                    boundary.gatePassing.nextGate(p) ?: throw IllegalArgumentException("точка не названа")
                }
                val check = req.path("check").asText("")
                require(check.isNotBlank()) { "field 'check' is required" }
                val remove = req.path("remove").asBoolean(false)
                val na = author(req)
                val cur = boundary.objects.current(p)!!
                val doc = cur.doc.deepCopy<ObjectNode>()
                val next = mapper.createArrayNode()
                doc.path("gate_tailoring").forEach { t ->
                    if (!(t.path("gate").asText() == gate && t.path("check").asText() == check)) {
                        next.add(t.deepCopy<JsonNode>())
                    }
                }
                if (!remove) {
                    val rationale = req.path("rationale").asText("")
                    require(rationale.length >= 10) {
                        "неприменимость — это tailoring: обоснование обязательно (не короче 10 знаков)"
                    }
                    next.addObject()
                        .put("gate", gate).put("check", check)
                        .put("rationale", rationale).put("author", na)
                        .put("at", java.time.LocalDate.now().toString())
                }
                if (next.isEmpty) doc.remove("gate_tailoring")
                else doc.set<ObjectNode>("gate_tailoring", next)
                boundary.objects.change(
                    p, doc,
                    changeRef = if (remove) "tailoring снят: проверка «$check» точки $gate снова применима"
                    else "tailoring: проверка «$check» точки $gate неприменима",
                    createdBy = na,
                )
                respond(ex, 200, mapper.createObjectNode().put("ok", true))
            }

            // Предпросмотр незакрытого точки — тем же расчётом, что и прохождение
            method == "GET" && gateMatch(path, "issues") != null -> {
                val gate = gateMatch(path, "issues")!!
                val p = requireProject(project)
                val issues = boundary.gatePassing.issues(gate, p)
                val n = mapper.createObjectNode()
                n.put("gate", gate)
                n.put("ready", issues.isEmpty())
                n.put("next_gate", boundary.gatePassing.nextGate(p))
                n.putArray("issues").also { a -> issues.forEach(a::add) }
                respond(ex, 200, n)
            }

            method == "POST" && gateMatch(path, "pass") != null -> {
                val req = mapper.readTree(body(ex))
                respond(
                    ex, 200,
                    boundary.gatePassing.pass(
                        gateMatch(path, "pass")!!, req.path("rationale").asText(""),
                        author(req), requireProject(project),
                    ),
                )
            }

            method == "POST" && gateMatch(path, "return") != null -> {
                val req = mapper.readTree(body(ex))
                respond(
                    ex, 200,
                    boundary.gatePassing.requestReturn(
                        gateMatch(path, "return")!!,
                        req.path("to").map { it.asText() },
                        req.path("reason").asText(""), author(req), requireProject(project),
                    ),
                )
            }

            // Загрузка пачкой (блок A, ADR-024): проверка по схемам до записи,
            // всё или ничего, порядок разрешает сервер, отчёт с путём до поля
            method == "POST" && path == "/import/objects" -> {
                val req = mapper.readTree(body(ex))
                val report = BatchImport(boundary, mapper).import(req, author(req), project)
                respond(ex, if (report.ok) 201 else 422, batchJson(report))
            }

            // Выгрузка проекта тем же форматом — выгруженное грузится обратно
            method == "GET" && path == "/export/objects" ->
                respond(ex, 200, BatchImport(boundary, mapper).export(requireProject(project)))

            method == "POST" && path == "/import/terminal-profile" -> {
                val req = mapper.readTree(body(ex))
                val source = req.path("source").asText("lorawan-devices")
                val verdict = boundary.importPolicy.importAllowed(source, "item")
                if (!verdict.allowed) {
                    respond(ex, 422, mapper.createObjectNode().put("error", verdict.reason))
                } else {
                    val provenance = boundary.importPolicy.provenanceFor(
                        source = source,
                        version = req.path("dataset_version").asText(""),
                        retrievedAt = req.path("retrieved_at").asText(""),
                        itemRef = req.path("item_ref").asText("").ifBlank { null },
                        mapper = mapper,
                    )
                    val draft = orbita.usr.TerminalImport.mapTerminal(
                        req.path("device"), req.path("profile"), provenance, mapper,
                    )
                    // повторный импорт той же записи источника — обновление
                    // существующего профиля; ручная правка не затирается
                    val itemRef = provenance.path("import").path("item_ref").asText("")
                    val existing = boundary.objects.listCurrent(project)
                        .filter { it.type == "terminal_profile" }
                        .map { it.doc.deepCopy<ObjectNode>() }
                        .filter {
                            it.path("provenance").path("import").path("item_ref").asText("") == itemRef
                        }
                    val (merged, action) = orbita.mod.model.mergeImported(existing, draft)
                    val out = mapper.createObjectNode()
                    out.put("action", action.name.lowercase())
                    out.set<ObjectNode>("draft", merged.last())
                    val issues = out.putArray("issues")
                    orbita.usr.TerminalImport.screen(merged.last()).forEach(issues::add)
                    respond(ex, 200, out)
                }
            }

            // Импорт требований из ReqIF (шаг 14, канал «требования»). Файл
            // разбирает служба обмена; сюда возвращаются черновики — хранение
            // идёт обычным каналом, тем же фильтром, что рукописный ввод.
            method == "POST" && path == "/import/reqif" -> {
                val exchangeUrl = System.getenv("ORBITA_EXCHANGE_URL")
                if (exchangeUrl.isNullOrBlank()) {
                    respond(
                        ex, 503,
                        mapper.createObjectNode()
                            .put("error", "служба обмена не настроена: задайте ORBITA_EXCHANGE_URL")
                            .put("adr", "ADR-023"),
                    )
                } else {
                    val parsed = mapper.readTree(postToExchange("$exchangeUrl/reqif/parse", body(ex)))
                    val out = mapper.createObjectNode()
                    val drafts = out.putArray("drafts")
                    parsed.path("objects")
                        .filter { it.path("type").asText() == "ST-REQUIREMENT" }
                        .forEach { so ->
                            val spec = orbita.out.SpecObject(
                                identifier = so.path("identifier").asText(),
                                type = so.path("type").asText(),
                                values = so.path("values").properties()
                                    .associate { (k, v) -> k to v },
                            )
                            drafts.add(orbita.out.fromSpecObject(spec, mapper = mapper))
                        }
                    out.put("source_title", parsed.path("title").asText(""))
                    out.put("relations", parsed.path("relations").size())
                    respond(ex, 200, out)
                }
            }

            // Пакет передачи одной операцией (TZ-OUT-006, шаг 11.3). Вердикт
            // полноты и предупреждения о небазированном — внутри пакета.
            method == "GET" && path == "/export/package" -> {
                // Сценарий обязателен (шаг 16 §3.2): умолчание показывало
                // бы чужой проект молча — на пустой базе пакет выглядел бы пустым,
                // а на демо-базе рабочий проект получил бы демо-результаты.
                val packageScenario = query(ex)["scenario"] ?: throw IllegalArgumentException(
                    "query parameter 'scenario' is required: выберите сценарий из /objects?type=scenario",
                )
                val options = boundary.results.activeForScenario(
                    packageScenario, "kpi",
                ).map { r ->
                    (r.payload.deepCopy() as ObjectNode)
                        .put("stale", r.stale)
                        .put("rng_seed", r.rngSeed)
                        .also { n ->
                            val iv = n.putObject("input_versions")
                            r.inputVersions.forEach { (k, v) -> iv.put(k, v) }
                        }
                }
                val spacecraft = boundary.objects.listCurrent(project).firstOrNull { it.type == "spacecraft" }
                val budgets = spacecraft?.let {
                    orbita.out.ModelSnapshot.budgetsOf(
                        boundary.spacecraft.build(it.doc, orbita.out.SpacecraftConditions()),
                        mapper,
                    )
                } ?: emptyList()
                val model = orbita.out.ModelSnapshot.of(
                    boundary.objects, mapper, options = options, budgets = budgets, projectId = project,
                )
                val pkg = orbita.out.TransferPackages.assemble(
                    model = model,
                    verificationMatrix = boundary.matrices.verificationMatrix(),
                    validationMatrix = boundary.matrices.validationMatrix(),
                    maturity = boundary.maturity.build(query(ex)["gate"] ?: "SRR"),
                    mapper = mapper,
                )
                respond(ex, 200, pkg)
            }

            // Узкие места из СОХРАНЁННОГО прогона потоков (шаг 16 §2.4, TZ-OUT-002):
            // отчёт ничего не пересчитывает. Пустой отчёт отличается от
            // неисполненного: executed=false — «не считали», пустые entries при
            // executed=true — «считали, узких мест нет».
            // Прогон потоков (Монте-Карло) от хранимых объектов сценария —
            // результат ложится в results (kind=flow), его читают «Сравнение»
            // (узкие места) и свидетельства верификации. Прежде ядро было
            // не подключено: запустить прогон из интерфейса было нельзя.
            // Наполнение полок (§5): объект в область LIB — тип и документ.
            // Обычные edit-маршруты требуют проект; библиотека — область.
            method == "POST" && path == "/library/objects" -> {
                val req = mapper.readTree(body(ex))
                val libAuthor = author(req.path("author").asText(""))
                require(libAuthor.isNotBlank()) { "field 'author' is required" }
                val typeName = req.path("type").asText("")
                val libType = orbita.mod.model.CoreType.entries.firstOrNull { it.dbType == typeName }
                    ?: throw IllegalArgumentException("unknown type '$typeName'")
                val stored = boundary.editing.create(
                    libType, req.path("doc"), libAuthor, orbita.mod.store.ObjectStore.LIBRARY_PROJECT,
                )
                respond(
                    ex, 201,
                    mapper.createObjectNode().put("id", stored.id).put("type", stored.type),
                )
            }

            // Канал «Сохранить как шаблон» (§2): предпросмотр ДО записи —
            // что войдёт, какие связи будут отрезаны поимённо, какие величины
            // можно обезличить. Без предпросмотра фрагмент не пишется.
            method == "POST" && path == "/library/fragments/preview" -> {
                val req = mapper.readTree(body(ex))
                val prevProject = requireProject(project)
                val c = LibraryChannel(boundary).closure(
                    prevProject,
                    req.path("kind").asText(null),
                    req.path("ids").map { it.asText() },
                    req.path("root").asText(null),
                )
                val out = mapper.createObjectNode()
                val objs = out.putArray("objects")
                c.objects.forEach { o ->
                    objs.addObject().put("id", o.id).put("type", o.type)
                        .put("name", o.doc.path("name").asText(o.doc.path("statement").asText("")))
                }
                val counters = out.putObject("counters")
                c.objects.groupBy { it.type }.forEach { (t, l) -> counters.put(t, l.size) }
                val cuts = out.putArray("cuts")
                c.cuts.forEach { cuts.add("${it.from} → ${it.to} (${it.what})") }
                val vals = out.putArray("value_candidates")
                c.valueCandidates.forEach { (id, pth, v) ->
                    vals.addObject().put("object", id).put("path", pth).put("value", v)
                }
                respond(ex, 200, out)
            }

            // Запись фрагмента: резы обязаны быть подтверждены предпросмотром
            method == "POST" && path == "/library/fragments" -> {
                val req = mapper.readTree(body(ex))
                val saveAuthor = author(req.path("author").asText(""))
                require(saveAuthor.isNotBlank()) { "field 'author' is required" }
                val saveProject = requireProject(project)
                val stored = LibraryChannel(boundary).save(
                    projectId = saveProject,
                    kind = req.path("kind").asText(null),
                    ids = req.path("ids").map { it.asText() },
                    root = req.path("root").asText(null),
                    name = req.path("name").asText(""),
                    shelf = req.path("shelf").asText(""),
                    missionClassRef = req.path("mission_class_ref").asText(null),
                    acknowledgedCuts = req.path("acknowledged_cuts").map { it.asText() }.toSet(),
                    replacements = buildMap {
                        req.path("replacements").properties().forEach { (k, v) ->
                            put(k, v.map { it.asText() })
                        }
                    },
                    author = saveAuthor,
                )
                respond(
                    ex, 201,
                    mapper.createObjectNode().put("id", stored.id).put("version", stored.version),
                )
            }

            // Применение фрагмента (§3): экземпляры со связью «применяет»
            method == "POST" && path.matches(Regex("/library/fragments/LF-[0-9]{4}/apply")) -> {
                val req = mapper.readTree(body(ex))
                val applyAuthor = author(req.path("author").asText(""))
                require(applyAuthor.isNotBlank()) { "field 'author' is required" }
                val applyProject = requireProject(project)
                val fragId = path.removePrefix("/library/fragments/").removeSuffix("/apply")
                val outcome = LibraryChannel(boundary).apply(fragId, applyProject, applyAuthor)
                val out = mapper.createObjectNode()
                val arr = out.putArray("created")
                outcome.created.forEach { (from, id) -> arr.addObject().put("from", from).put("id", id) }
                out.set<ArrayNode>("existing", mapper.valueToTree(outcome.existing))
                // 200 на повтор: набор уже взят, второй не создан (идемпотентность)
                respond(ex, if (outcome.created.isEmpty() && outcome.existing.isNotEmpty()) 200 else 201, out)
            }

            // Круг 3 §1: отмена взятия — до конца пути; тронутое руками — отказ
            method == "POST" && path.matches(Regex("/library/fragments/LF-[0-9]{4}/revert")) -> {
                val req = mapper.readTree(body(ex))
                val revertAuthor = author(req.path("author").asText(""))
                require(revertAuthor.isNotBlank()) { "field 'author' is required" }
                val fragId = path.removePrefix("/library/fragments/").removeSuffix("/revert")
                try {
                    val removed = LibraryChannel(boundary).revert(fragId, requireProject(project), revertAuthor)
                    respond(ex, 200, mapper.createObjectNode().apply {
                        set<ArrayNode>("removed", mapper.valueToTree(removed))
                    })
                } catch (e: LibraryChannel.RevertBlockedException) {
                    respond(ex, 409, mapper.createObjectNode().apply {
                        put("error", "созданное этим взятием уже тронуто руками — отмена не выполняется")
                        set<ArrayNode>("touched", mapper.valueToTree(e.touched))
                    })
                }
            }

            // Полки библиотеки по классу (§4 Ш2): фрагменты с живыми счётчиками
            method == "GET" && path == "/library/shelves" -> {
                val arr = mapper.createArrayNode()
                boundary.objects.listCurrent(orbita.mod.store.ObjectStore.LIBRARY_PROJECT)
                    .filter { it.type == "library_fragment" && it.status != Lifecycle.Cancelled }
                    .sortedBy { it.id }
                    .forEach { f ->
                        val row = arr.addObject()
                            .put("id", f.id)
                            .put("name", f.doc.path("name").asText(""))
                            .put("shelf", f.doc.path("shelf").asText(""))
                            .put("mission_class_ref", f.doc.path("mission_class_ref").asText(""))
                            .put("summary", f.doc.path("summary").asText(""))
                        row.set<ObjectNode>("counters", f.doc.path("counters").deepCopy())
                        val manifest = row.putObject("origin")
                        f.doc.path("origin").properties().forEach { (k, v) ->
                            if (k != "object_versions") manifest.set<com.fasterxml.jackson.databind.JsonNode>(k, v.deepCopy())
                        }
                        // круг 3 §1: взятие видно после перезахода — по связям
                        // «применяет», отдельного состояния у взятия нет
                        project?.let { pj ->
                            val alive = LibraryChannel(boundary).appliedInstances(f.id, pj)
                            if (alive.isNotEmpty()) {
                                val taken = row.putObject("applied")
                                taken.put("count", alive.size)
                                val byType = taken.putObject("by_type")
                                alive.groupingBy { it.type }.eachCount()
                                    .forEach { (t, n) -> byType.put(t, n) }
                            }
                        }
                    }
                respond(ex, 200, arr)
            }

            // Классы миссии (§4 Ш1) — из полки Б4
            method == "GET" && path == "/library/mission-classes" -> {
                val arr = mapper.createArrayNode()
                boundary.objects.listCurrent(orbita.mod.store.ObjectStore.LIBRARY_PROJECT)
                    .filter { it.type == "mission_class" && it.status != Lifecycle.Cancelled }
                    .sortedBy { it.id }
                    .forEach { c ->
                        arr.addObject()
                            .put("id", c.id)
                            .put("name", c.doc.path("name").asText(""))
                            .set<com.fasterxml.jackson.databind.JsonNode>(
                                "typical_constraints", c.doc.path("typical_constraints").deepCopy(),
                            )
                    }
                respond(ex, 200, arr)
            }

            // Ф-03: глоссарий — смысловые подсказки типов и терминов ДАННЫМИ
            // полки LIB, не хардкодом клиента (один источник, как словарь
            // единиц); тот же список кормит экран «Справочники».
            method == "GET" && path == "/library/glossary" -> {
                val arr = mapper.createArrayNode()
                boundary.objects.listCurrent(orbita.mod.store.ObjectStore.LIBRARY_PROJECT)
                    .filter { it.type == "glossary" && it.status != Lifecycle.Cancelled }
                    .sortedBy { it.id }
                    .forEach { g -> g.doc.path("entries").forEach { e -> arr.add(e.deepCopy<com.fasterxml.jackson.databind.JsonNode>()) } }
                respond(ex, 200, arr)
            }

            // Ф-03: справочник единиц просмотром — размерности с канонами
            // и входными единицами, как лежат в UR (границы читают его же).
            method == "GET" && path == "/library/unit-registry" -> {
                val arr = mapper.createArrayNode()
                boundary.objects.listCurrent(orbita.mod.store.ObjectStore.LIBRARY_PROJECT)
                    .filter { it.type == "unit_registry" && it.status != Lifecycle.Cancelled }
                    .sortedBy { it.id }
                    .forEach { u -> u.doc.path("dimensions").forEach { d -> arr.add(d.deepCopy<com.fasterxml.jackson.databind.JsonNode>()) } }
                respond(ex, 200, arr)
            }

            // В2.1: свёртка бюджетов — по вхождениям с кратностью. Величина
            // узла = параметр определения × произведение quantity по пути от
            // корня. Считает сервер; расчётов в клиенте нет.
            method == "GET" && path == "/views/composition/budgets" -> {
                val cur = boundary.objects.listCurrent(requireProject(project))
                val usages = cur.filter { it.type == "component_usage" && it.status != Lifecycle.Cancelled }
                val defs = cur.filter { it.type == "component" }.associateBy { it.id }
                val byId = usages.associateBy { it.id }
                fun multiplier(u: orbita.mod.store.StoredObject): Long {
                    var m = 1L
                    var c: orbita.mod.store.StoredObject? = u
                    while (c != null) {
                        m *= c.doc.path("quantity").asLong(1)
                        c = byId[c.doc.path("parent_usage").asText("")]
                    }
                    return m
                }
                val totals = linkedMapOf<String, Pair<Double, String>>()
                val rows = mapper.createArrayNode()
                usages.sortedBy { it.id }.forEach { u ->
                    val def = defs[u.doc.path("definition_ref").asText("")] ?: return@forEach
                    val m = multiplier(u)
                    val row = rows.addObject()
                        .put("usage", u.id)
                        .put("definition", def.id)
                        .put("name", def.doc.path("name").asText(""))
                        .put("multiplier", m)
                    val params = row.putObject("parameters")
                    def.doc.path("parameters").forEach { pr ->
                        val nm = pr.path("name").asText("")
                        val q = pr.path("quantity")
                        if (nm.isNotBlank() && q.path("value").isNumber) {
                            val total = q.path("value").asDouble() * m
                            val unit = q.path("unit").asText("")
                            params.putObject(nm).put("value", total).put("unit", unit)
                            val prev = totals[nm]
                            totals[nm] = ((prev?.first ?: 0.0) + total) to unit
                        }
                    }
                }
                val out = mapper.createObjectNode()
                out.set<com.fasterxml.jackson.databind.JsonNode>("rows", rows)
                val t = out.putObject("totals")
                totals.forEach { (nm, v) -> t.putObject(nm).put("value", v.first).put("unit", v.second) }
                respond(ex, 200, out)
            }

            // В2.2: стоимость и сроки сворачиваются по дереву работ. Стоимость —
            // сумма привязанных оценок по поддереву; срок — максимум по детям
            // (работы ветвей параллельны; последовательность — предмет графика,
            // не свёртки).
            method == "GET" && path == "/views/wbs/rollup" -> {
                val cur = boundary.objects.listCurrent(requireProject(project))
                val wbs = cur.filter { it.type == "wbs_element" && it.status != Lifecycle.Cancelled }
                val estimates = cur.filter { it.type == "cost_estimate" && it.status != Lifecycle.Cancelled }
                    .groupBy { it.doc.path("wbs_ref").asText("") }
                val children = wbs.groupBy { it.doc.path("parent").asText("") }
                data class Roll(val low: Double, val high: Double, val monthsLow: Double, val monthsHigh: Double)
                val memo = mutableMapOf<String, Roll>()
                fun roll(id: String): Roll = memo.getOrPut(id) {
                    val own = estimates[id].orEmpty()
                    fun num(n: com.fasterxml.jackson.databind.JsonNode): Double =
                        if (n.isNumber) n.asDouble() else n.path("value").asDouble(0.0)
                    var low = own.sumOf { num(it.doc.path("total_low")) }
                    var high = own.sumOf { num(it.doc.path("total_high")) }
                    var ml = own.maxOfOrNull { num(it.doc.path("schedule_months_low")) } ?: 0.0
                    var mh = own.maxOfOrNull { num(it.doc.path("schedule_months_high")) } ?: 0.0
                    children[id].orEmpty().forEach { ch ->
                        val r = roll(ch.id)
                        low += r.low; high += r.high
                        ml = maxOf(ml, r.monthsLow); mh = maxOf(mh, r.monthsHigh)
                    }
                    Roll(low, high, ml, mh)
                }
                val arr = mapper.createArrayNode()
                wbs.sortedBy { it.id }.forEach { w ->
                    val r = roll(w.id)
                    arr.addObject()
                        .put("id", w.id)
                        .put("name", w.doc.path("name").asText(""))
                        .put("parent", w.doc.path("parent").asText(""))
                        .put("estimates", estimates[w.id].orEmpty().size)
                        .put("total_low", r.low)
                        .put("total_high", r.high)
                        .put("schedule_months_low", r.monthsLow)
                        .put("schedule_months_high", r.monthsHigh)
                }
                respond(ex, 200, mapper.createObjectNode().set("elements", arr))
            }

            // Мастер-путь Ш2, «Взять из библиотеки»: библиотека исходных
            // документов (ADR-030) — общая, а объекты попроектные. Здесь
            // документы ДРУГИХ проектов видны как библиотека текущего:
            // НПА и глоссарий, загруженные однажды, не заводятся заново.
            method == "GET" && path == "/views/library/source-documents" -> {
                val libProject = requireProject(project)
                val arr = mapper.createArrayNode()
                boundary.objects.listCurrent()
                    .filter {
                        it.type == "source_document" && it.status != Lifecycle.Cancelled &&
                            it.projectId != libProject
                    }
                    .sortedBy { it.id }
                    .forEach { o ->
                        arr.addObject()
                            .put("id", o.id)
                            .put("project", o.projectId)
                            .put("name", o.doc.path("name").asText(o.id))
                            .put("kind", o.doc.path("kind").asText(""))
                            .put("summary", o.doc.path("summary").asText(""))
                            .put("has_text", o.doc.path("text").asText("").isNotBlank())
                    }
                respond(ex, 200, arr)
            }

            // Взятие из библиотеки — КОПИЯ в текущий проект с провенансом
            // imported: откуда, какой версии, на каких условиях (TZ-COM-005).
            // Исходный документ не тронут; копия начинает свой цикл черновиком.
            method == "POST" && path == "/views/library/take" -> {
                val req = mapper.readTree(body(ex))
                val takeAuthor = author(req.path("author").asText(""))
                require(takeAuthor.isNotBlank()) { "field 'author' is required" }
                val takeProject = requireProject(project)
                val taken = mapper.createArrayNode()
                req.path("ids").forEach { idNode ->
                    val fromId = idNode.asText()
                    val src = boundary.objects.current(fromId)
                        ?: throw NoSuchElementException("source document '$fromId' not found")
                    require(src.type == "source_document") {
                        "'$fromId' is not a source document"
                    }
                    val copy = src.doc.deepCopy<ObjectNode>()
                    copy.remove("id")
                    copy.remove("lifecycle")
                    copy.remove("provenance")
                    copy.putObject("provenance")
                        .put("source", "imported")
                        .put("author", takeAuthor)
                        .putObject("import")
                        .put("dataset", "библиотека исходных документов: $fromId (${src.projectId})")
                        .put("dataset_version", src.version)
                        .put("retrieved_at", java.time.LocalDate.now().toString())
                        .put("terms", src.doc.path("rights").asText(""))
                    val stored = boundary.editing.create(
                        orbita.mod.model.CoreType.SourceDocument, copy, takeAuthor, takeProject,
                    )
                    taken.addObject().put("from", fromId).put("id", stored.id)
                }
                respond(ex, 201, mapper.createObjectNode().set("taken", taken))
            }

            // Мастер-путь «Начало проекта», Ш3: профиль службы — СЛЕДСТВИЕ
            // шагов, а не находка в меню. Запреты профиля собираются из
            // ограничений паспорта (Ш1) здесь, на сервере: правило «запреты
            // службы = ограничения проекта» живёт в одном месте. Повторный
            // вызов обновляет собранный профиль, а не плодит дубли.
            method == "POST" && path == "/views/start-path/profile" -> {
                val req = mapper.readTree(body(ex))
                val startAuthor = author(req.path("author").asText(""))
                require(startAuthor.isNotBlank()) { "field 'author' is required" }
                val assembled = assembleStartProfile(requireProject(project), startAuthor)
                respond(
                    ex, if (assembled.created) 201 else 200,
                    mapper.createObjectNode()
                        .put("id", assembled.id)
                        .put("version", assembled.version)
                        .put("name", assembled.name)
                        .put("prohibitions", assembled.prohibitions),
                )
            }

            method == "POST" && path == "/views/flows/run" -> {
                val req = mapper.readTree(body(ex))
                val scenarioId = req.path("scenario").asText("")
                require(scenarioId.isNotBlank()) { "field 'scenario' is required: сценарий прогона" }
                requireProject(project)
                respond(ex, 201, FlowRun(boundary).run(scenarioId, project!!))
            }

            method == "GET" && path == "/views/bottlenecks" -> {
                val scenarioId = query(ex)["scenario"] ?: throw IllegalArgumentException(
                    "query parameter 'scenario' is required: выберите сценарий из /objects?type=scenario",
                )
                val flowResults = boundary.results.activeForScenario(scenarioId, "flow").map { it.payload }
                val report = if (flowResults.isEmpty()) {
                    orbita.out.AnalyticReport.notExecuted<orbita.out.BottleneckEntry>("bottlenecks")
                } else {
                    orbita.out.bottlenecks(flowResults)
                }
                val out = mapper.createObjectNode()
                out.put("name", report.name)
                out.put("executed", report.executed)
                val entries = out.putArray("entries")
                report.entries.forEach { e ->
                    entries.addObject().put("scenario_ref", e.scenarioRef)
                        .put("location", e.location).put("utilization", e.utilization)
                }
                respond(ex, 200, out)
            }

            // Экран 7: сравнение вариантов — нормировка и Парето считаются здесь.
            // Сценарий обязателен (шаг 16 §3.2): умолчаний нет.
            method == "GET" && path == "/views/comparison" -> {
                // Вариант сравнения = СЦЕНАРИЙ с выполненным прогоном (второй
                // заход: маршрут ждал нескольких kpi-расчётов одного сценария,
                // а процесс порождает вариантность клонами сценариев — базовый
                // против варианта, — и сравнение не работало никогда). Значения
                // осей берутся из активных результатов сценария: kpi-вектора,
                // если он есть, и прогона потоков.
                val comparisonScenarios = boundary.objects.listCurrent(project)
                    .filter { it.type == "scenario" && it.status != Lifecycle.Cancelled }
                val options = comparisonScenarios.mapNotNull { sc ->
                    val values = buildMap {
                        boundary.results.activeForScenario(sc.id, "kpi").lastOrNull()?.let { r ->
                            listOf("quality", "cost", "reliability", "energy",
                                "deployment_days", "launch_campaigns").forEach { axis ->
                                r.payload.path(axis).takeIf { it.isNumber }?.let { put(axis, it.asDouble()) }
                            }
                        }
                        boundary.results.activeForScenario(sc.id, "flow").lastOrNull()?.let { r ->
                            putAll(orbita.out.flowComparisonAxes(r.payload))
                        }
                    }
                    if (values.isEmpty()) null else orbita.bal.RadarOption(sc.id, values)
                }
                if (options.size < 2) {
                    val have = options.joinToString { it.name }.ifEmpty { "ни одного" }
                    respond(
                        ex, 409,
                        mapper.createObjectNode().put(
                            "error",
                            "в сравнении участвуют сценарии с выполненным прогоном: " +
                                "сейчас с результатами $have из ${comparisonScenarios.size} — " +
                                "выполните прогон потоков по второму сценарию",
                        ),
                    )
                } else {
                    // Оси — из фактически имеющихся в результатах (шаг 16 §3.5):
                    // отсутствие оси — сообщение инженеру с перечнем доступных,
                    // а не отказ сервера. Порядок общий для всех вариантов.
                    // доступна ось, которая есть во ВСЕХ вариантах и у которой
                    // задано направление показателя: без направления нормировать
                    // нельзя, и предлагать такую ось значит предлагать отказ
                    val available = options.first().values.keys
                        .filter { axis -> options.all { it.values.containsKey(axis) } }
                        .filter { it in orbita.bal.KpiAxes.default.axes }
                    val requested = query(ex)["axes"]?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }
                    val missing = requested.orEmpty().filterNot { it in available }
                    if (missing.isNotEmpty()) {
                        return respond(
                            ex, 409,
                            mapper.createObjectNode().put(
                                "error",
                                "в результатах нет ос${if (missing.size == 1) "и" else "ей"} " +
                                    "${missing.joinToString()}: доступны ${available.sorted().joinToString()}",
                            ),
                        )
                    }
                    // Набор по умолчанию — тоже из фактических, не константа
                    val axes = requested
                        ?: listOf("quality", "cost", "reliability").filter { it in available }
                            .ifEmpty { available.sorted().take(3) }
                    if (axes.isEmpty()) {
                        return respond(
                            ex, 409,
                            mapper.createObjectNode()
                                .put("error", "в результатах нет ни одной общей оси: сравнивать не по чему"),
                        )
                    }
                    val view = orbita.out.comparisonView(options, axes = axes)
                    val out = mapper.valueToTree<ObjectNode>(view)
                    val avail = out.putArray("availableAxes")
                    available.sorted().forEach(avail::add)
                    // Подписи показателей — из того же реестра, что направления:
                    // ключи вида delivery_a_prime инженеру не адресованы
                    val labels = out.putObject("axisLabels")
                    available.forEach { labels.put(it, orbita.bal.KpiAxes.default.label(it)) }
                    respond(ex, 200, out)
                }
            }

            method == "GET" && Regex("^/views/components/(CM-[0-9]{4})$").matches(path) -> {
                val id = path.removePrefix("/views/components/")
                respond(ex, 200, mapper.valueToTree(boundary.screens.componentSpecification(id)))
            }

            // Подписи единиц: подстановка на стороне представления, коды СИ
            // в модели не меняются (STEP-6 §3.2, ловушка 6)
            method == "GET" && path == "/unit-labels" ->
                respond(ex, 200, mapper.valueToTree(orbita.req.UnitLabels().all()))

            // Подписи кодов перечислений — тем же способом, что и единицы
            // (шаг 15 §2): до этого шага коды `operator`, `customer`,
            // `regulator` выходили на экран как есть.
            method == "GET" && path == "/enum-labels" ->
                respond(ex, 200, mapper.valueToTree(orbita.req.EnumLabels().all()))

            // Подписи имён полей форм (блок D, §3.6) — той же таблицей
            method == "GET" && path == "/field-labels" ->
                respond(ex, 200, mapper.valueToTree(orbita.req.FieldLabels().all()))

            method == "GET" && path == "/reports/review-candidates" ->
                respond(ex, 200, mapper.valueToTree(boundary.req.reviewCandidates(project)))

            // Параметры канала отдаются только адаптером (TZ-NET-001, TZ-NET-006)
            method == "GET" && path == "/protocol-adapter" ->
                respond(ex, 200, boundary.protocolAdapter.toContractJson(PROTOCOL_ADAPTER_ID, mapper))

            // ---------- рабочий слой: ввод и правка через интерфейс (шаг 15) ----------
            // Автор идёт ТЕЛОМ запроса, а не заголовком: имена инженеров русские,
            // а значение заголовка HTTP обязано быть ASCII — на первом же
            // «инженер А» клиент отказывается собрать запрос. Автор и по смыслу
            // часть изменения, а не сведения о транспорте.

            method == "POST" && editTypePath(path) != null -> {
                val req = mapper.readTree(body(ex))
                val type = editTypePath(path)!!
                // создание проекта из интерфейса заводит контейнер (ADR-022)
                val stored =
                    if (type == CoreType.Project) boundary.editing.create(type, req.path("doc"), author(req))
                    else boundary.editing.create(type, req.path("doc"), author(req), requireProject(project))
                // В3: создатель проекта — его руководитель
                if (type == CoreType.Project) {
                    currentAuthorLogin.get()?.let { boundary.auth.setRole(stored.id, it, "lead") }
                }
                respond(ex, 201, summary(stored).apply { set<ObjectNode>("doc", stored.doc) })
            }

            method == "PATCH" && editMatch != null && editMatch.groupValues[2].isEmpty() -> {
                val req = mapper.readTree(body(ex))
                val id = editMatch.groupValues[1]
                val type = CoreType.byDbType(
                    boundary.objects.current(id)?.type
                        ?: throw NoSuchElementException("object '$id' not found")
                )
                val stored = boundary.editing.update(
                    type = type,
                    id = id,
                    changes = req.path("changes"),
                    baseVersion = req.path("base_version").asText(""),
                    author = author(req),
                )
                respond(ex, 200, summary(stored).apply { set<ObjectNode>("doc", stored.doc) })
            }

            method == "POST" && editMatch?.groupValues?.get(2) == "/cancel" -> {
                val req = mapper.readTree(body(ex).ifBlank { "{}" })
                val stored = boundary.editing.cancel(
                    editMatch.groupValues[1], author(req),
                    baseVersion = req.path("base_version").textValue(),
                )
                respond(ex, 200, summary(stored))
            }

            // Отмена действия (§1.4): содержание предыдущей версии становится
            // новой текущей. Отменять нечего — это 409, а не молчаливое «ок».
            method == "POST" && editMatch?.groupValues?.get(2) == "/undo" -> {
                val req = mapper.readTree(body(ex).ifBlank { "{}" })
                val stored = boundary.editing.undo(editMatch.groupValues[1], author(req))
                if (stored == null) {
                    respond(
                        ex, 409,
                        mapper.createObjectNode()
                            .put("error", "object '${editMatch.groupValues[1]}' has a single version: nothing to undo"),
                    )
                } else {
                    respond(ex, 200, summary(stored).apply { set<ObjectNode>("doc", stored.doc) })
                }
            }

            method == "GET" && editMatch?.groupValues?.get(2) == "/history" -> {
                val arr = mapper.createArrayNode()
                boundary.editing.history(editMatch.groupValues[1]).forEach { v ->
                    arr.addObject()
                        .put("version", v.version).put("status", v.status.name)
                        .put("author", humanAuthor(v.createdBy)).put("valid_from", v.validFrom.toString())
                        .put("valid_to", v.validTo?.toString())
                        .put("current", v.validTo == null)
                }
                respond(ex, 200, arr)
            }

            // Что мешает базированию — до попытки перевода, чтобы форма могла
            // показать это инженеру, а не отказом после нажатия.
            method == "GET" && editMatch?.groupValues?.get(2) == "/issues" -> {
                val v = boundary.editing.promotionVerdict(editMatch.groupValues[1])
                val n = mapper.createObjectNode().put("can_baseline", v.ok)
                val arr = n.putArray("issues")
                v.blocking.forEach(arr::add)
                // отводимость: правила качества — эвристики, инженер вправе
                // отвести с обоснованием (находка прогона: «в пределах» по
                // смыслу ровно); TBD и план верификации не отводимы
                val wv = n.putArray("waivable")
                v.blocking.filter { it in v.waivable }.forEach(wv::add)
                val wd = n.putArray("waived")
                v.waived.forEach { (rule, why) ->
                    wd.addObject().put("rule", rule).put("rationale", why)
                }
                respond(ex, 200, n)
            }

            // Объекты вида для списка на экране (шаг 15). Подпись объекта
            // выбирает СЕРВЕР: какое поле содержательно, знает модель, а не
            // клиент, которому иначе пришлось бы гадать по именам полей.
            method == "GET" && path == "/objects" -> {
                val type = query(ex)["type"]
                    ?: throw IllegalArgumentException("query parameter 'type' is required")
                val arr = mapper.createArrayNode()
                boundary.objects.listCurrent(if (type == "project") null else project)
                    .filter { it.type == type }.forEach { o ->
                        arr.add(summary(o).put("title", titleOf(o)))
                }
                respond(ex, 200, arr)
            }

            // Схемы видов — источник структуры форм ввода (шаг 15 §2).
            // Виды перечисляются из состава CoreType, а не списком в клиенте.
            method == "GET" && path == "/kinds" -> {
                val arr = mapper.createArrayNode()
                CoreType.entries.forEach { t ->
                    arr.addObject().put("type", t.dbType).put("prefix", t.idPrefix)
                        .put("schema", t.schemaName)
                        // Вид без статусной модели живёт собственным циклом:
                        // лестница зрелости и массовый перевод к нему неприменимы.
                        // Риск — исключение по планкам ворот: Д6 зреет к MCR
                        .put(
                            "lifecycle",
                            boundary.schemaAllows(t, "lifecycle") ||
                                t.dbType in boundary.req.gates.typesWithStatusBar,
                        )
                }
                respond(ex, 200, arr)
            }

            method == "GET" && path.startsWith("/schemas/") ->
                respond(ex, 200, boundary.bundledSchema(path.removePrefix("/schemas/")))

            else -> respond(
                ex, 404,
                mapper.createObjectNode().put("error", "no route: $method /api$path"),
            )
        }
    }

    /** Вид объекта по префиксу идентификатора — для акцепта предложения нового объекта. */
    private fun typeByIdPrefix(id: String): CoreType =
        CoreType.entries.firstOrNull { id.startsWith("${it.idPrefix}-") }
            ?: throw IllegalArgumentException("unknown object id prefix: '$id'")

    /** Вид объекта из пути `/edit/<db_type>`; null — путь не про создание. */
    private fun editTypePath(path: String): CoreType? {
        val name = path.removePrefix("/edit/")
        if (path == name || '/' in name) return null
        return CoreType.entries.firstOrNull { it.dbType == name }
    }

    /**
     * Автор изменения (шаг 15 §1.2). Обязателен: правка без автора на сессии
     * параллельного проектирования — это правка, о которой некого спросить.
     */
    private fun batchJson(report: BatchReport): ObjectNode {
        val n = mapper.createObjectNode()
        n.put("written", report.written)
        val arr = n.putArray("problems")
        report.problems.forEach { p ->
            val n2 = arr.addObject()
                .put("index", p.index).put("id", p.id)
                .put("path", p.path).put("rule", p.rule).put("message", p.message)
            // как строка называлась в пакете инженера — если id перебивался
            p.sourceId?.let { n2.put("source_id", it) }
        }
        return n
    }

    /**
     * Подпись величин без основания именем инженера: правило основания
     * принимает «manual» от ЧЕЛОВЕКА, но не от службы. Значение, за которое
     * никто не отвечал, получает того, кто отвечает.
     *
     * Величины с настоящим основанием (imported с набором данных, computed с
     * модулем) не трогаются: подписывать чужой источник своим именем нельзя.
     */
    private fun подписатьВеличины(node: JsonNode, by: String, at: String) {
        when {
            node.isArray -> node.forEach { подписатьВеличины(it, by, at) }
            node.isObject -> {
                val obj = node as ObjectNode
                if (obj.path("value").isNumber && obj.path("unit").isTextual) {
                    val prov = obj.path("provenance")
                    val обосновано = when (prov.path("source").asText("")) {
                        "computed" -> prov.path("module").asText("").isNotBlank()
                        "imported" -> prov.path("import").path("dataset").asText("").isNotBlank()
                        else -> false
                    }
                    if (!обосновано) {
                        obj.putObject("provenance")
                            .put("source", "manual")
                            .put("author", by)
                            .put("timestamp", at)
                    }
                }
                obj.properties().forEach { (name, child) ->
                    if (name != "provenance") подписатьВеличины(child, by, at)
                }
            }
        }
    }

    /** Точка из пути /gates/<точка>/<действие>; имена точек несут дефисы (KDP-A). */
    private fun gateMatch(path: String, action: String): String? =
        Regex("^/gates/([^/]+)/$action$").find(path)?.groupValues?.get(1)
            ?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8) }

    /** Показ автора (круг 2 портфеля §1.3): карта авторов → учётка → имя;
     * «system» на экраны не выходит — безымянный служебный след. */
    private fun humanAuthor(name: String): String {
        val login = boundary.auth.authorMap()[name]
            ?: name.takeIf { boundary.auth.displayNameOf(it) != null }
        val display = login?.let { boundary.auth.displayNameOf(it) }
        return display
            ?: if (orbita.req.ServiceAuthors.isService(name)) "служебная запись" else name
    }

    private fun author(request: JsonNode): String =
        currentAuthor.get()
            ?: request.path("author").asText("").trim().takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("TZ-COM-005: field 'author' is required for editing")

    /** Вид объекта из пути `/objects/<db_type>`; null — путь не про приём объекта. */
    private fun objectTypePath(path: String): CoreType? {
        val name = path.removePrefix("/objects/")
        if (path == name || '/' in name) return null
        // интерфейс принимается своим маршрутом контура требований, а не здесь
        return CoreType.entries.firstOrNull { it.dbType == name }
    }

    private fun body(ex: HttpExchange): String = ex.requestBody.readAllBytes().decodeToString()

    /** Вызов службы обмена (ADR-023). Отказ службы — отказ запроса, не заглушка. */
    private fun postToExchange(url: String, json: String): String {
        val connection = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 5_000
        connection.readTimeout = 60_000
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.outputStream.use { it.write(json.toByteArray()) }
        val status = connection.responseCode
        val text = (if (status < 400) connection.inputStream else connection.errorStream)
            ?.readAllBytes()?.decodeToString() ?: ""
        if (status >= 400) {
            throw IllegalStateException("служба обмена ответила $status: ${text.take(300)}")
        }
        return text
    }

    /** ADR-022: запись требует проекта; пустой портфель — сначала создайте проект. */
    private fun requireProject(project: String?): String =
        project ?: throw IllegalArgumentException(
            "в портфеле нет ни одного проекта — сначала создайте проект (POST /objects/project)"
        )

    /** ADR-022: проект запроса — из ?project= либо единственный в портфеле. */
    /**
     * Шаблон документа — из библиотечной области (нитка Б.1): enum удалён.
     * Отсутствие шаблона — внятный отказ с перечнем заведённых кодов.
     */
    private fun templateOf(code: String): orbita.out.TemplateData {
        val rows = boundary.objects
            .listCurrent(orbita.mod.store.ObjectStore.LIBRARY_PROJECT)
            .filter { it.type == "document_template" && it.status != Lifecycle.Cancelled }
        return rows.firstOrNull { it.doc.path("code").asText() == code }
            ?.let { orbita.out.TemplateData.of(it.doc) }
            ?: throw IllegalArgumentException(
                "шаблон документа '$code' не заведён в библиотеке; заведены: " +
                    rows.map { it.doc.path("code").asText() }.sorted().joinToString()
                        .ifBlank { "ни одного — залейте сид data/library/document-templates.json" },
            )
    }


    /**
     * Маршруты потока документов (Д1–Д3, Ф-06, Ф-07) — отдельной функцией:
     * общий when маршрутизации перерос предел метода JVM (64 КБ байт-кода),
     * и компилятор отказывался собирать класс. Возвращает true, если запрос
     * обработан здесь.
     */
    private fun routeDocuments(
        ex: HttpExchange,
        method: String,
        path: String,
        projectRef: Lazy<String?>,
    ): Boolean {
        val project: String? by projectRef
        when {
            // Ф-07: есть ли из чего собирать замысел — и чем именно
            method == "GET" && path == "/views/mission-intent/readiness" ->
                respond(ex, 200, MissionIntentDraft.readiness(boundary, filesDir(), requireProject(project)))

            // Ф-07: промпт сборки замысла из документов — собирает система
            method == "GET" && path == "/views/mission-intent/prompt" -> {
                val ctx = requireProject(project)
                // Ф-11: профиль не спрашивается у инженера — система его
                // обеспечивает сама (дописывает вид либо собирает профиль
                // из ограничений паспорта). Кнопка не ведёт в тупик.
                val profileId = query(ex)["profile"]
                    ?: profileFor(MissionIntentDraft.KIND, ctx, author(query(ex)["author"] ?: ""))
                val statement = MissionIntentDraft.statementOf(boundary, filesDir(), ctx)
                val (profile, blocks) = boundary.ai.composeBlocks(MissionIntentDraft.KIND, profileId, ctx, statement)
                val out = mapper.createObjectNode()
                out.put("profile", profile.id)
                out.put("kind", MissionIntentDraft.KIND)
                out.put("text", blocks.joinToString("\n\n") { it.text })
                respond(ex, 200, out)
            }

            // Ф-07: предложение замысла пакетом — проверяется схемой и
            // возвращается инженеру НА ПРАВКУ, в паспорт само не ложится
            // Ф-07 + живой канал: «собрать из документов» ДЕЛАЕТ сборку, а не
            // отдаёт текст промпта. Владелец нажал кнопку — система спросила
            // службу сама, показала четыре поля с якорями и ждёт правки.
            // Канал не настроен — честный отказ с причиной: тогда работает
            // прежний путь, промпт наружу и ответ пакетом.
            method == "POST" && path == "/views/mission-intent/compose" -> {
                val req = mapper.readTree(body(ex))
                val ctx = requireProject(project)
                // вызов службы — не правка модели: без представления он
                // законен, а подпись правки профиля подставит profileFor
                val by = author(req.path("author").asText(""))
                val profileId = req.path("profile").asText("").ifBlank {
                    profileFor(MissionIntentDraft.KIND, ctx, by)
                }
                val statement = MissionIntentDraft.statementOf(boundary, filesDir(), ctx)
                val answer = boundary.ai.askRaw(MissionIntentDraft.KIND, profileId, ctx, statement, by)
                if (answer.failure != null || answer.text == null) {
                    respond(
                        ex, 503,
                        mapper.createObjectNode()
                            .put("error", answer.failure ?: "служба не ответила")
                            .put("call", answer.call)
                            .put("profile", profileId),
                    )
                    return true
                }
                val cleaned = answer.text.trim()
                    .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                val draft = try {
                    val parsed = mapper.readTree(cleaned)
                    // терпимость к обёртке: общая форма ответа — массив, и
                    // модель иногда заворачивает документ в него. Рабочий
                    // ответ из-за обёртки терять нельзя — разворачиваем.
                    if (parsed.isArray && parsed.size() == 1) parsed[0] else parsed
                } catch (e: Exception) {
                    respond(
                        ex, 422,
                        mapper.createObjectNode()
                            .put("error", "ответ службы не разобрался как JSON: ${e.message}")
                            .put("raw", answer.text.take(2000))
                            .put("call", answer.call),
                    )
                    return true
                }
                val problems = MissionIntentDraft.problems(boundary, draft)
                if (problems.isNotEmpty()) {
                    respond(
                        ex, 422,
                        mapper.createObjectNode()
                            .put("error", "ответ службы не по схеме замысла: ${problems.take(3)}")
                            .put("raw", answer.text.take(2000))
                            .put("call", answer.call),
                    )
                    return true
                }
                val out = mapper.createObjectNode()
                out.put("call", answer.call)
                answer.model?.let { out.put("model", it) }
                out.put("profile", profileId)
                out.set<JsonNode>("draft", draft)
                respond(ex, 200, out)
            }

            method == "POST" && path == "/views/mission-intent/draft" -> {
                val req = mapper.readTree(body(ex))
                val raw = req.path("raw").takeIf { it.isTextual }?.asText()
                val draft = (
                    if (raw != null) mapper.readTree(
                        raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim(),
                    ) else req.path("draft")
                    ) as? ObjectNode ?: throw IllegalArgumentException(
                    "тело: {\"raw\": \"<JSON пакета>\"} либо {\"draft\": {…}}",
                )
                val problems = MissionIntentDraft.problems(boundary, draft)
                if (problems.isNotEmpty()) {
                    val out = mapper.createObjectNode().put("error", "предложение не по схеме замысла")
                    val arr = out.putArray("problems")
                    problems.take(10).forEach { arr.add(it) }
                    respond(ex, 422, out)
                    return true
                }
                respond(ex, 200, draft)
            }

            // Ф-07: акцепт замысла — правкой паспорта, с якорями происхождения
            method == "POST" && path == "/views/mission-intent/accept" -> {
                val req = mapper.readTree(body(ex))
                val by = author(req)
                val ctx = requireProject(project)
                val draft = req.path("draft").takeIf { it.isObject }
                    ?: throw IllegalArgumentException("нет предложения замысла: поле 'draft'")
                val problems = MissionIntentDraft.problems(boundary, draft)
                require(problems.isEmpty()) { "предложение не по схеме замысла: ${problems.take(3)}" }
                val passport = boundary.objects.current(ctx)
                    ?: throw NoSuchElementException("project '$ctx' not found")
                val intent = MissionIntentDraft.applyTo(passport.doc, draft)
                val changes = mapper.createObjectNode()
                changes.set<ObjectNode>("mission_intent", intent)
                val stored = boundary.editing.update(
                    CoreType.Project, ctx, changes, passport.version, by,
                    changeRef = "Ф-07: замысел миссии собран из документов и принят инженером",
                )
                respond(
                    ex, 200,
                    mapper.createObjectNode()
                        .put("project", stored.id)
                        .put("version", stored.version)
                        .set<ObjectNode>("mission_intent", MissionIntentDraft.toJson(intent)),
                )
            }

            // Ф-14 п.3: нитка «взято/брали» в обе стороны. Полка обязана
            // знать, кто её брал: без этого библиотека — витрина, а не
            // общий фонд, и последствия правки шаблона невидимы.
            method == "GET" && path == "/views/library/usage" -> {
                val libId = query(ex)["id"]
                    ?: throw IllegalArgumentException("query 'id' is required: объект полки")
                val takers = boundary.objects.listCurrent()
                    .filter { it.projectId != orbita.mod.store.ObjectStore.LIBRARY_PROJECT }
                    .filter { it.status != Lifecycle.Cancelled }
                    .filter { o ->
                        val dataset = o.doc.path("provenance").path("import").path("dataset").asText("")
                        val profile = o.doc.path("profile_ref").asText("")
                        dataset.contains(libId) || profile == libId
                    }
                val out = mapper.createObjectNode()
                out.put("id", libId)
                out.put("takers", takers.size)
                val byProject = takers.groupBy { it.projectId ?: "—" }
                val arr = out.putArray("projects")
                byProject.toSortedMap().forEach { (prj, rows) ->
                    val n = arr.addObject()
                    n.put("project", prj)
                    n.put("name", boundary.objects.current(prj)?.doc?.path("name")?.asText(prj) ?: prj)
                    n.put("objects", rows.size)
                    val ids = n.putArray("ids")
                    rows.sortedBy { it.id }.take(20).forEach { ids.add(it.id) }
                }
                out.put(
                    "note",
                    if (takers.isEmpty()) "с этой полки ещё не брали — правка никого не затронет"
                    else "брали: ${byProject.keys.sorted().joinToString(", ")} — правка отразится на них",
                )
                respond(ex, 200, out)
            }

            // Ф-14: второй конец контура библиотеки. Ш2 берёт типовое с полки
            // в проект; здесь проектный факт ОБОБЩАЕТСЯ в шаблон полки —
            // отдельным осознанным действием, со следом «обобщено из PJ-…».
            method == "POST" && Regex("^/views/stakeholders/SK-[0-9]{4}/generalize$").matches(path) -> {
                val skId = path.removePrefix("/views/stakeholders/").removeSuffix("/generalize")
                val req = mapper.readTree(body(ex))
                val by = author(req)
                require(by.isNotBlank()) { "TZ-COM-005: field 'author' is required for editing" }
                val ctx = requireProject(project)
                val sk = boundary.objects.current(skId)
                    ?: throw NoSuchElementException("стейкхолдер '$skId' не найден")
                require(sk.type == "stakeholder") { "$skId — не стейкхолдер проекта" }
                // роль профиля полки — свой перечень; отображаем честно
                val role = when (val r = sk.doc.path("role").asText("")) {
                    "consumer" -> "end_user"
                    "established" -> "operator"
                    "supplier" -> "supplier"
                    else -> r
                }
                val doc = mapper.createObjectNode()
                doc.put("name", sk.doc.path("name").asText(skId))
                doc.put("role", role)
                sk.doc.path("interest").asText("").takeIf { it.isNotBlank() }?.let { doc.put("interests", it) }
                boundary.objects.current(ctx)?.doc?.path("mission_class")?.asText("")
                    ?.takeIf { it.isNotBlank() }?.let { doc.put("mission_class_ref", it) }
                // происхождение: обобщение — не ручной ввод и не импорт извне
                val provenance = doc.putObject("provenance")
                provenance.put("source", "manual")
                provenance.put("author", "обобщено из $ctx · $skId (автор: $by)")
                val stored = boundary.editing.create(
                    orbita.mod.model.CoreType.StakeholderProfile, doc, by,
                    orbita.mod.store.ObjectStore.LIBRARY_PROJECT,
                )
                // нитка в обратную сторону: факт знает свой шаблон
                val back = mapper.createObjectNode()
                back.put("profile_ref", stored.id)
                boundary.editing.update(
                    orbita.mod.model.CoreType.Stakeholder, skId, back, sk.version, by,
                    changeRef = "Ф-14: обобщён в профиль полки ${stored.id}",
                )
                respond(
                    ex, 201,
                    mapper.createObjectNode()
                        .put("profile", stored.id)
                        .put("from", skId)
                        .put("project", ctx)
                        .put("note", "профиль А2 создан обобщением: полка знает исток, факт знает шаблон"),
                )
            }

            // Инспекция обзора: чек-листы полки с состоянием пунктов.
            method == "GET" && path == "/views/review-checklist" ->
                respond(
                    ex, 200,
                    ReviewChecklist.view(boundary, requireProject(project), query(ex)["gate"]),
                )

            // Отметка пункта — единственное место, где состояние ставится
            // РУКОЙ: инспекция формулировок машине не поручается. Поэтому
            // отметка несёт автора, время и замечание словами.
            method == "POST" && path == "/views/review-checklist/check" -> {
                val req = mapper.readTree(body(ex))
                val by = author(req)
                require(by.isNotBlank()) { "TZ-COM-005: field 'author' is required for editing" }
                val ctx = requireProject(project)
                val чек = req.path("checklist").asText("")
                val пункт = req.path("item").asText("")
                require(чек.isNotBlank() && пункт.isNotBlank()) { "нужны 'checklist' и 'item'" }
                val снять = req.path("uncheck").asBoolean(false)
                val паспорт = boundary.objects.current(ctx)
                    ?: throw NoSuchElementException("project '$ctx' not found")
                val отметки = (паспорт.doc.path("review_checks").deepCopy<JsonNode>() as? ArrayNode)
                    ?: mapper.createArrayNode()
                val остальные = mapper.createArrayNode()
                отметки.forEach { o ->
                    val тот = o.path("checklist").asText() == чек && o.path("item").asText() == пункт
                    if (!тот) остальные.add(o)
                }
                if (!снять) {
                    val n = остальные.addObject()
                    n.put("checklist", чек)
                    n.put("item", пункт)
                    n.put("author", by)
                    n.put("at", java.time.LocalDate.now().toString())
                    req.path("note").asText("").takeIf { it.isNotBlank() }?.let { n.put("note", it) }
                }
                val changes = mapper.createObjectNode()
                changes.set<ArrayNode>("review_checks", остальные)
                boundary.editing.update(
                    CoreType.Project, ctx, changes, паспорт.version, by,
                    changeRef = if (снять) "инспекция: отметка снята с «$пункт»"
                    else "инспекция: пункт «$пункт» проверен",
                )
                respond(ex, 200, ReviewChecklist.view(boundary, ctx, query(ex)["gate"]))
            }

            // Г-01: чужие ссылки пакета — не отказ, а сопоставление. Здесь
            // только разбор и предложение по смыслу: решение за инженером,
            // изоляция проектов не ослабляется ни на шаг.
            method == "POST" && path == "/views/link-mapping" -> {
                val req = mapper.readTree(body(ex))
                val ctx = requireProject(project)
                val raw = req.path("raw").asText("")
                val items = if (raw.isNotBlank()) {
                    val cleaned = raw.trim()
                        .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                    mapper.readTree(cleaned)
                } else {
                    req.path("items")
                }
                require(items.isArray) { "нет пакета: поле 'raw' либо 'items' массивом" }
                respond(ex, 200, LinkMapping.toJson(LinkMapping.разобрать(boundary, items, ctx)))
            }

            // Ф-15: правка справочника не бесплатна — она меняет смысл
            // величин во всех разборах. До сохранения инженер обязан знать
            // объём последствий: сколько документов придётся переразобрать.
            method == "GET" && path == "/views/registry-impact" -> {
                val kind = query(ex)["type"] ?: "unit_registry"
                val ctx = resolveProject(ex)
                val docs = boundary.objects.listCurrent(ctx)
                    .filter { it.type == "source_document" && it.status != Lifecycle.Cancelled }
                val parsed = docs.count { DocumentParseStore.mapOf(filesDir(), it.id) != null }
                val harvested = docs.count { DocumentHarvest.of(filesDir(), it.id) != null }
                val out = mapper.createObjectNode()
                out.put("type", kind)
                out.put("documents", docs.size)
                out.put("parsed", parsed)
                out.put("harvested", harvested)
                out.put(
                    "warning",
                    when {
                        parsed == 0 -> "разобранных документов нет — правка ни на что не повлияет"
                        kind == "unit_registry" ->
                            "правка справочника единиц меняет отпечаток разбора: " +
                                "$parsed документов будут переразобраны при следующем открытии" +
                                (if (harvested > 0) ", у $harvested придётся сверить урожай" else "")
                        else ->
                            "правка глоссария меняет отпечаток разбора: $parsed документов " +
                                "получат новые термы при следующем открытии"
                    },
                )
                respond(ex, 200, out)
            }

            // Ф-13: матрица «стейкхолдер × нужды» — тройное состояние и
            // видимые края (стейкхолдер без нужд, нужда без носителя).
            method == "GET" && path == "/views/stakeholder-coverage" ->
                respond(ex, 200, StakeholderCoverage.toJson(boundary, requireProject(project)))

            // «Работа фазы»: задачи регламента со статусами, шагами, разрывами
            // разрезом и окнами ленты. Всё вычисляется — ручного нет ничего.
            method == "GET" && path == "/views/phase-work" ->
                respond(ex, 200, PhaseWork.toJson(boundary, requireProject(project)))

            // Ф-12: проводник постановки — сквозная цепочка со счётчиками и
            // первым несделанным звеном. Куда идти дальше, знает система.
            method == "GET" && path == "/views/statement-path" ->
                respond(ex, 200, StatementPath.toJson(boundary, requireProject(project)))

            // Профиль под вид операции: инженер выбирает ЧТО делать, а не
            // какой профиль это разрешает. Есть подходящий — вернём его;
            // нет — обеспечим (тот же закон, что у мастер-пути).
            method == "GET" && path == "/views/ai/profile-for" -> {
                val kind = query(ex)["kind"]
                    ?: throw IllegalArgumentException("query 'kind' is required: вид пакета")
                val ctx = requireProject(project)
                val had = boundary.objects.listCurrent(ctx)
                    .filter { it.type == "ai_profile" && it.status != Lifecycle.Cancelled }
                    .any { p -> p.doc.path("kinds").any { it.asText() == kind } }
                val id = profileFor(kind, ctx, author(query(ex)["author"] ?: ""))
                val out = mapper.createObjectNode()
                out.put("profile", id)
                out.put("kind", kind)
                out.put("ensured", !had)
                respond(ex, 200, out)
            }

            // Ф-10: состав выгрузки знаний и её отпечаток — до скачивания
            // видно, что уйдёт во внешний контур и сколько это весит.
            method == "GET" && path == "/views/knowledge-export" -> {
                val ctx = requireProject(project)
                val asked = query(ex)["parts"]?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.toSet()
                    ?: KnowledgeExport.PARTS.map { it.key }.toSet()
                val bundle = KnowledgeExport.bundle(boundary, filesDir(), ctx, asked)
                val out = mapper.createObjectNode()
                out.put("fingerprint", bundle.fingerprint)
                val parts = out.putArray("parts")
                KnowledgeExport.PARTS.forEach { p ->
                    val body = bundle.files[p.file]
                    val size = body?.toByteArray()?.size ?: 0
                    parts.addObject()
                        .put("key", p.key)
                        .put("file", p.file)
                        .put("title", p.title)
                        .put("chosen", p.key in asked)
                        .put("size", size)
                        // величину для показа считает сервер: в клиенте расчётов нет
                        .put("size_kb", if (size == 0) 0 else maxOf(1, Math.round(size / 1024.0).toInt()))
                }
                respond(ex, 200, out)
            }

            // Ф-10: сам пакет — архивом MD-файлов. Каноны уже в MD, поэтому
            // выгрузка их переносит, а не пересобирает.
            method == "GET" && path == "/views/knowledge-export/bundle.zip" -> {
                val ctx = requireProject(project)
                val asked = query(ex)["parts"]?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.toSet()
                    ?: KnowledgeExport.PARTS.map { it.key }.toSet()
                val bundle = KnowledgeExport.bundle(boundary, filesDir(), ctx, asked)
                val buffer = java.io.ByteArrayOutputStream()
                java.util.zip.ZipOutputStream(buffer).use { zip ->
                    bundle.files.forEach { (name, body) ->
                        zip.putNextEntry(java.util.zip.ZipEntry(name))
                        zip.write(body.toByteArray())
                        zip.closeEntry()
                    }
                }
                respondBinary(
                    ex, buffer.toByteArray(), "application/zip",
                    "знания-$ctx-${bundle.fingerprint}.zip",
                )
            }

            // Ф-09: есть ли на полке нормативы, из которых можно порождать
            // кандидатов, — и какие из них знают только своё имя.
            method == "GET" && path == "/views/normative-candidates/readiness" ->
                respond(ex, 200, NormativeCandidates.readiness(boundary, filesDir(), requireProject(project)))

            // Ф-09: промпт «норматив → кандидаты» собирает служба — пунктами
            // НПА и блоками канонов, не перечнем кодов.
            method == "GET" && path == "/views/normative-candidates/prompt" -> {
                val ctx = requireProject(project)
                // Ф-11: тот же закон — профиль обеспечивается системой
                val profileId = query(ex)["profile"]
                    ?: profileFor(NormativeCandidates.KIND, ctx, author(query(ex)["author"] ?: ""))
                val statement = NormativeCandidates.statementOf(boundary, filesDir(), ctx)
                val (profile, blocks) = boundary.ai.composeBlocks(NormativeCandidates.KIND, profileId, ctx, statement)
                val out = mapper.createObjectNode()
                out.put("profile", profile.id)
                out.put("kind", NormativeCandidates.KIND)
                out.put("text", blocks.joinToString("\n\n") { it.text })
                respond(ex, 200, out)
            }

            // Ф-09: предложение пакетом — ворота нормативной схемой, показ
            // кандидатов инженеру. В модель здесь ещё ничего не ложится.
            method == "POST" && path == "/views/normative-candidates/draft" -> {
                val req = mapper.readTree(body(ex))
                val raw = req.path("raw").asText("")
                val packet = if (raw.isNotBlank()) mapper.readTree(raw) else req.path("packet")
                require(packet.isObject) { "нет пакета кандидатов: поле 'raw' либо 'packet'" }
                val problems = NormativeCandidates.problems(boundary, packet)
                require(problems.isEmpty()) { "пакет не по схеме кандидатов: ${problems.take(3)}" }
                val out = mapper.createObjectNode()
                out.put("kind", NormativeCandidates.KIND)
                out.put("items", packet.path("items").size())
                // Ф-10: пакет из внешнего контура сверяется с отпечатком знаний —
                // предупреждением, не отказом: знания стенда меняются чаще, чем
                // идёт диалог, и рабочий ответ терять нельзя
                val said = packet.path("knowledge_fingerprint").asText("")
                val current = if (said.isBlank()) "" else KnowledgeExport
                    .bundle(boundary, filesDir(), requireProject(project), KnowledgeExport.PARTS.map { it.key }.toSet())
                    .fingerprint
                KnowledgeExport.staleWarning(packet, current)?.let { out.put("knowledge_warning", it) }
                out.set<JsonNode>("packet", packet)
                respond(ex, 200, out)
            }

            // Ф-09: акцепт кандидатов — требования объектами с трассой на
            // норматив, ограничения Р-кодами в паспорт. Выбор — инженера.
            method == "POST" && path == "/views/normative-candidates/accept" -> {
                val req = mapper.readTree(body(ex))
                val by = author(req)
                val ctx = requireProject(project)
                val packet = req.path("packet").takeIf { it.isObject }
                    ?: throw IllegalArgumentException("нет пакета кандидатов: поле 'packet'")
                val problems = NormativeCandidates.problems(boundary, packet)
                require(problems.isEmpty()) { "пакет не по схеме кандидатов: ${problems.take(3)}" }
                val selected = req.path("selected").map { it.asInt() }.toSet()
                val items = packet.path("items").toList()
                require(selected.isNotEmpty()) { "не выбрано ни одного кандидата" }
                val created = mapper.createArrayNode()
                val addedConstraints = mapper.createArrayNode()
                // ограничения ложатся одной правкой паспорта: коды Р-серии
                // выдаются подряд, а не гонкой отдельных запросов
                var passport = boundary.objects.current(ctx)
                    ?: throw NoSuchElementException("project '$ctx' not found")
                val constraints = (passport.doc.path("constraints").deepCopy<JsonNode>() as? ArrayNode)
                    ?: mapper.createArrayNode()
                items.forEachIndexed { i, item ->
                    if (i !in selected) return@forEachIndexed
                    when (item.path("class").asText()) {
                        "requirement" -> {
                            val doc = NormativeCandidates.requirementOf(item, owner = by)
                            val stored = boundary.editing.create(CoreType.Requirement, doc, by, ctx)
                            created.addObject()
                                .put("id", stored.id)
                                .put("statement", doc.path("statement").asText(""))
                                .put("basis", item.path("basis").path("normative_ref").asText(""))
                        }
                        "constraint" -> {
                            val c = NormativeCandidates.constraintOf(item, constraints)
                            constraints.add(c)
                            addedConstraints.add(c)
                        }
                    }
                }
                if (!addedConstraints.isEmpty) {
                    val changes = mapper.createObjectNode()
                    changes.set<ArrayNode>("constraints", constraints)
                    passport = boundary.editing.update(
                        CoreType.Project, ctx, changes, passport.version, by,
                        changeRef = "Ф-09: ограничения-кандидаты из нормативов полки приняты инженером",
                    )
                }
                val out = mapper.createObjectNode()
                out.put("accepted", selected.size)
                out.set<ArrayNode>("requirements", created)
                out.set<ArrayNode>("constraints", addedConstraints)
                respond(ex, 200, out)
            }

            // Д3: поиск по материалам проекта — по канонам разбора, с
            // координатой блока: найденное можно взять в промпт куском.
            method == "GET" && path == "/views/document-search" -> {
                val q = query(ex)["q"] ?: ""
                val search = DocumentSearch(boundary)
                val hits = search.search(requireProject(project), q)
                val out = mapper.createObjectNode()
                out.put("query", q)
                out.put("hits", hits.size)
                out.set<ArrayNode>("results", search.toJson(hits))
                respond(ex, 200, out)
            }

            // Ф-06: запросы данных — анкеты характеристик, наложенные на
            // модель: что заполнено, чего не хватает и откуда это взять.
            method == "GET" && path == "/views/data-requests" -> {
                val requests = DataRequests(boundary).of(requireProject(project))
                val out = mapper.createObjectNode()
                out.put("missing_total", requests.sumOf { it.missing.size })
                out.set<ArrayNode>("requests", DataRequests(boundary).toJson(requests))
                respond(ex, 200, out)
            }

            // Ф-06: анкеты полки — просмотром (правятся пачкой, как справочники)
            method == "GET" && path == "/library/property-forms" -> {
                val arr = mapper.createArrayNode()
                boundary.objects.listCurrent(orbita.mod.store.ObjectStore.LIBRARY_PROJECT)
                    .filter { it.type == "property_form" && it.status != Lifecycle.Cancelled }
                    .sortedBy { it.id }
                    .forEach { f -> arr.add(f.doc.deepCopy<com.fasterxml.jackson.databind.JsonNode>()) }
                respond(ex, 200, arr)
            }

            // Д1: карта разбора — структура, числа каноном, термы,
            // нормативы-кандидаты. Текста не несёт: он в каноне (ниже).
            method == "GET" && Regex("^/sd-parse/SD-[0-9]{4}$").matches(path) -> {
                val sdId = path.removePrefix("/sd-parse/")
                boundary.objects.current(sdId) ?: throw NoSuchElementException("document '$sdId' not found")
                val map = DocumentParseStore.mapOf(filesDir(), sdId)
                    ?: throw NoSuchElementException(
                        "разбора у $sdId нет — переразберите документ (POST /sd-parse/$sdId)",
                    )
                respond(ex, 200, map)
            }

            // Д1: MD-канон — 100% текста документа с якорями блоков. Люди
            // читают его как документ, промпт берёт разделы по якорям.
            method == "GET" && Regex("^/sd-parse/SD-[0-9]{4}/canon$").matches(path) -> {
                val sdId = path.removePrefix("/sd-parse/").removeSuffix("/canon")
                boundary.objects.current(sdId) ?: throw NoSuchElementException("document '$sdId' not found")
                val canon = DocumentParseStore.canonOf(filesDir(), sdId)
                    ?: throw NoSuchElementException("разбора у $sdId нет — переразберите документ")
                respondBinary(ex, canon.toByteArray(), "text/markdown; charset=utf-8", "$sdId.md")
            }

            // Д1: переразбор — документам, загруженным до появления разбора,
            // и после смены версии разборщика (кэш по хешу файла).
            method == "POST" && Regex("^/sd-parse/SD-[0-9]{4}$").matches(path) -> {
                val sdId = path.removePrefix("/sd-parse/")
                val sd = boundary.objects.current(sdId)
                    ?: throw NoSuchElementException("document '$sdId' not found")
                val fileName = sd.doc.path("file").path("name").asText("")
                require(fileName.isNotBlank()) { "у карточки $sdId нет файла — разбирать нечего" }
                val f = java.nio.file.Path.of(filesDir(), sdId, java.nio.file.Path.of(fileName).fileName.toString())
                require(java.nio.file.Files.exists(f)) { "файл карточки $sdId не найден в хранилище" }
                val parseId = DocumentParseStore.parseAndStore(
                    filesDir(), sdId, fileName, java.nio.file.Files.readAllBytes(f),
                    DocumentParseStore.lexiconOf(boundary),
                ) ?: throw IllegalArgumentException("формат '$fileName' разборщику не поддаётся")
                respond(ex, 200, mapper.createObjectNode().put("id", sdId).put("parsed", parseId))
            }

            // Д2: промпт смыслового разбора — собирает СИСТЕМА: правила вида
            // (реестр пакетов) + карточка + выжимка блоками из канона Д1.
            // Службе уходит выжимка, сырой файл не уходит никогда.
            method == "GET" && Regex("^/sd-parse/SD-[0-9]{4}/harvest/prompt$").matches(path) -> {
                val sdId = path.removePrefix("/sd-parse/").removeSuffix("/harvest/prompt")
                val sd = boundary.objects.current(sdId)
                    ?: throw NoSuchElementException("document '$sdId' not found")
                val canon = DocumentParseStore.canonOf(filesDir(), sdId)
                    ?: throw NoSuchElementException("у $sdId нет разбора — переразберите документ")
                // Ф-06 путь 3: анкеты полки идут во вход — служба метит
                // характеристики даташита ключом поля, и они предзаполняют форму
                val forms = boundary.objects.listCurrent(orbita.mod.store.ObjectStore.LIBRARY_PROJECT)
                    .filter { it.type == "property_form" && it.status != Lifecycle.Cancelled }
                val statement = DocumentHarvest.statementOf(
                    sd.doc, sdId, canon, DocumentParseStore.mapOf(filesDir(), sdId), forms,
                )
                val ctx = requireProject(project)
                // Тот же закон, что у замысла и кандидатов: профиль под вид
                // обеспечивает система, а не спрашивает у инженера
                val profileId = query(ex)["profile"]
                    ?: profileFor(DocumentHarvest.KIND, ctx, author(query(ex)["author"] ?: ""))
                val (profile, blocks) = boundary.ai.composeBlocks(DocumentHarvest.KIND, profileId, ctx, statement)
                val out = mapper.createObjectNode()
                out.put("document", sdId)
                out.put("profile", profile.id)
                out.put("kind", DocumentHarvest.KIND)
                val arr = out.putArray("blocks")
                blocks.forEach { b ->
                    arr.addObject().put("source", b.source).put("title", b.title).put("text", b.text)
                }
                out.put("text", blocks.joinToString("\n\n") { it.text })
                respond(ex, 200, out)
            }

            // Д2: приём урожая — пакетом (закрытый контур и ПМИ Б2). Ворота —
            // нормативная схема ответа: чужая форма внутрь не проходит.
            method == "POST" && Regex("^/sd-parse/SD-[0-9]{4}/harvest$").matches(path) -> {
                val sdId = path.removePrefix("/sd-parse/").removeSuffix("/harvest")
                boundary.objects.current(sdId) ?: throw NoSuchElementException("document '$sdId' not found")
                val map = DocumentParseStore.mapOf(filesDir(), sdId)
                    ?: throw NoSuchElementException("у $sdId нет разбора — сначала разбор Д1")
                val req = mapper.readTree(body(ex))
                val raw = req.path("raw").takeIf { it.isTextual }?.asText()
                val harvest = (
                    if (raw != null) mapper.readTree(
                        raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim(),
                    ) else req.path("harvest")
                    ) as? ObjectNode ?: throw IllegalArgumentException(
                    "тело: {\"raw\": \"<JSON пакета>\"} либо {\"harvest\": {…}}",
                )
                val problems = boundary.schemaProblems("core/document-harvest", harvest)
                if (problems.isNotEmpty()) {
                    val out = mapper.createObjectNode().put("error", "пакет не по схеме разбора")
                    val arr = out.putArray("problems")
                    problems.take(20).forEach { e ->
                        arr.addObject().put("path", e.path).put("rule", e.rule).put("message", e.message)
                    }
                    respond(ex, 422, out)
                    return true
                }
                harvest.put("accepted_at_document", sdId)
                // редакция правил, по которой урожай собран: семантика меток
                // источников уточнялась — по редакции видно, чей это свод
                if (!harvest.has("rules_version")) {
                    harvest.put(
                        "rules_version",
                        orbita.ai.PackageKinds.default().of(DocumentHarvest.KIND).rulesVersion,
                    )
                }
                harvest.set<ObjectNode>("summary", DocumentHarvest.summaryOf(harvest))
                DocumentHarvest.store(filesDir(), sdId, map.path("fingerprint").asText(), harvest)
                respond(
                    ex, 201,
                    mapper.createObjectNode().put("document", sdId)
                        .put("items", harvest.path("items").size())
                        .set<ObjectNode>("summary", DocumentHarvest.summaryOf(harvest)),
                )
            }

            // Д2: урожай документа — вкладка «Найдено в документе»
            method == "GET" && Regex("^/sd-parse/SD-[0-9]{4}/harvest$").matches(path) -> {
                val sdId = path.removePrefix("/sd-parse/").removeSuffix("/harvest")
                val harvest = DocumentHarvest.of(filesDir(), sdId)
                    ?: throw NoSuchElementException(
                        "смыслового разбора у $sdId нет — соберите промпт и внесите урожай пакетом",
                    )
                val out = (harvest as ObjectNode).deepCopy()
                // показ величин и координат готовит сервер: клиент печатает
                out.path("items").forEach { i ->
                    (i as ObjectNode).put("display", DocumentHarvest.displayOf(i))
                    i.put("blocks_label", DocumentHarvest.blocksOf(i).joinToString(", "))
                }
                // адреса раскладки — рядом с кандидатами: инженеру видно,
                // куда ляжет каждый класс и чего системе не хватает
                val targets = out.putObject("targets")
                DocumentHarvest.TARGETS.forEach { (cls, t) ->
                    val node = targets.putObject(cls)
                    node.put("where", t.where)
                    node.put("type", t.type?.dbType ?: "")
                    t.note?.let { node.put("note", it) }
                    val gaps = node.putArray("gaps")
                    t.gaps.forEach { g ->
                        val gn = gaps.addObject().put("field", g.field).put("prompt", g.prompt)
                        val opts = gn.putArray("options")
                        g.options.forEach { opts.add(it) }
                    }
                }
                respond(ex, 200, out)
            }

            // Д2: акцепт урожая ПО АДРЕСАМ — кандидаты становятся объектами
            // системы; недостающее обязательное поле приходит от инженера,
            // не выдумывается. Запись — транзакцией: всё или ничего.
            method == "POST" && Regex("^/sd-parse/SD-[0-9]{4}/harvest/accept$").matches(path) -> {
                val sdId = path.removePrefix("/sd-parse/").removeSuffix("/harvest/accept")
                val sd = boundary.objects.current(sdId)
                    ?: throw NoSuchElementException("document '$sdId' not found")
                val req = mapper.readTree(body(ex))
                val by = author(req)
                val ctx = requireProject(project)
                val harvest = DocumentHarvest.of(filesDir(), sdId)
                    ?: throw NoSuchElementException("урожая у $sdId нет")
                val items = DocumentHarvest.itemsOf(harvest)
                val sdName = sd.doc.path("name").asText(sdId)

                data class Ready(val index: Int, val cls: String, val item: JsonNode, val filled: JsonNode)
                val ready = mutableListOf<Ready>()
                val refused = mapper.createArrayNode()
                req.path("selected").forEach { sel ->
                    val index = sel.path("index").asInt(-1)
                    val item = items.get(index) ?: run {
                        refused.addObject().put("index", index).put("why", "кандидата с таким номером нет")
                        return@forEach
                    }
                    val cls = item.path("class").asText("")
                    val target = DocumentHarvest.TARGETS[cls]
                    val filled = sel.path("filled").takeIf { it.isObject } ?: mapper.createObjectNode()
                    if (target == null) {
                        refused.addObject().put("index", index).put("class", cls)
                            .put("why", "класс вне раскладки — адрес назначает инженер")
                        return@forEach
                    }
                    val gaps = DocumentHarvest.gapsOf(cls, item, filled)
                    if (gaps.isNotEmpty()) {
                        refused.addObject().put("index", index).put("class", cls)
                            .put("why", "не заполнено: " + gaps.joinToString(", ") { it.prompt })
                        return@forEach
                    }
                    ready += Ready(index, cls, item, filled)
                }

                val created = mapper.createArrayNode()
                try {
                    boundary.transaction {
                        val constraints = ready.filter { it.cls == "constraint" }
                        val milestones = ready.filter { it.cls == "milestone" }
                        ready.filter { it.cls != "constraint" && it.cls != "milestone" }.forEach { r ->
                            val doc = DocumentHarvest.objectOf(
                                r.item, r.filled, sdId, sd.version, sdName, java.time.LocalDate.now().toString(),
                            )
                            if (doc == null) {
                                refused.addObject().put("index", r.index).put("class", r.cls)
                                    .put("why", "класс кладётся не объектом — см. адрес класса")
                                return@forEach
                            }
                            val type = DocumentHarvest.TARGETS[r.cls]!!.type!!
                            // стейкхолдеры и нормативы — общая полка LIB (А1/А2),
                            // постановка и оценки — проект
                            val where = if (type == CoreType.StakeholderProfile ||
                                type == CoreType.NormativeDocument
                            ) orbita.mod.store.ObjectStore.LIBRARY_PROJECT else ctx
                            val stored = boundary.editing.create(type, doc, by, where)
                            created.addObject().put("index", r.index).put("class", r.cls)
                                .put("id", stored.id).put("where", DocumentHarvest.TARGETS[r.cls]!!.where)
                        }
                        // паспорт правится ОДНОЙ версией: ограничения Р-кодами
                        // и вехи-заготовки без дат — обе ленты сразу
                        if (constraints.isNotEmpty() || milestones.isNotEmpty()) {
                            val passport = boundary.objects.current(ctx)
                                ?: throw NoSuchElementException("project '$ctx' not found")
                            val changes = mapper.createObjectNode()
                            if (constraints.isNotEmpty()) {
                                val list = (passport.doc.path("constraints").deepCopy() as? ArrayNode)
                                    ?: mapper.createArrayNode()
                                constraints.forEach { r ->
                                    val c = DocumentHarvest.constraintOf(r.item, list, sdId)
                                    list.add(c)
                                    created.addObject().put("index", r.index).put("class", r.cls)
                                        .put("id", c.path("code").asText())
                                        .put("where", DocumentHarvest.TARGETS["constraint"]!!.where)
                                }
                                changes.set<ArrayNode>("constraints", list)
                            }
                            if (milestones.isNotEmpty()) {
                                val list = (passport.doc.path("milestones").deepCopy() as? ArrayNode)
                                    ?: mapper.createArrayNode()
                                milestones.forEach { r ->
                                    val m = DocumentHarvest.milestoneOf(r.item, sdId)
                                    val gate = m.path("gate").asText()
                                    if (list.none { it.path("gate").asText() == gate }) list.add(m)
                                    created.addObject().put("index", r.index).put("class", r.cls)
                                        .put("id", gate)
                                        .put("where", DocumentHarvest.TARGETS["milestone"]!!.where)
                                }
                                changes.set<ArrayNode>("milestones", list)
                            }
                            boundary.editing.update(
                                CoreType.Project, ctx, changes, passport.version, by,
                                changeRef = "акцепт урожая смыслового разбора $sdId",
                            )
                        }
                    }
                } catch (e: Exception) {
                    respond(
                        ex, 422,
                        mapper.createObjectNode()
                            .put("error", "акцепт отклонён целиком: ${e.message}")
                            .set<ObjectNode>("refused", refused),
                    )
                    return true
                }
                val out = mapper.createObjectNode().put("document", sdId)
                out.set<ArrayNode>("created", created)
                out.set<ArrayNode>("refused", refused)
                respond(ex, if (created.isEmpty) 422 else 201, out)
            }
            else -> return false
        }
        return true
    }

    /**
     * Ф-08.1: умолчание включения документа в промпт — по его ТИПУ, из
     * глоссария (поле prompt_default). Постановочный документ входит в
     * промпт разделами сразу: без этого «загрузил и смотри» оставалось
     * тупиком — блоки не выбраны, и в промпт уходило одно оглавление.
     */
    private fun promptDefaultOf(kind: String): String =
        boundary.objects.listCurrent(orbita.mod.store.ObjectStore.LIBRARY_PROJECT)
            .filter { it.type == "glossary" && it.status != Lifecycle.Cancelled }
            .flatMap { g -> g.doc.path("entries").toList() }
            .firstOrNull { it.path("sd_kind").asText() == kind }
            ?.path("prompt_default")?.asText("off") ?: "off"

    /** Включает все блоки разбора документа в промпт проекта; сколько вошло. */
    private fun includeDocumentInPrompt(sdId: String, projectId: String, author: String): Int {
        val map = DocumentParseStore.mapOf(filesDir(), sdId) ?: return 0
        val anchors = map.path("structure")
            .map { it.path("anchor").asText() }
            .filter { it.isNotBlank() }
        if (anchors.isEmpty()) return 0
        val passport = boundary.objects.current(projectId)?.takeIf { it.type == "project" } ?: return 0
        val path = (passport.doc.path("start_path").deepCopy<JsonNode>() as? ObjectNode)
            ?: mapper.createObjectNode().put("status", "in_progress").put("step", 2)
        val refs = (path.path("source_refs").takeIf { it.isArray } as? ArrayNode) ?: path.putArray("source_refs")
        if (refs.none { it.asText() == sdId }) refs.add(sdId)
        val blocks = (path.path("source_blocks").takeIf { it.isObject } as? ObjectNode)
            ?: path.putObject("source_blocks")
        val arr = blocks.putArray(sdId)
        anchors.forEach { arr.add(it) }
        val changes = mapper.createObjectNode()
        changes.set<ObjectNode>("start_path", path)
        boundary.editing.update(
            CoreType.Project, passport.id, changes, passport.version, author,
            changeRef = "Ф-08.1: постановочный документ $sdId включён в промпт разделами",
        )
        return anchors.size
    }

    /** Собранный профиль службы: что вышло из ограничений паспорта. */
    private data class AssembledProfile(
        val id: String,
        val version: String,
        val name: String,
        val prohibitions: Int,
        val created: Boolean,
    )

    /**
     * Профиль службы из ограничений паспорта (мастер-путь, шаг запуска).
     * Виды по умолчанию — вся цепочка постановки и обе операции, которые
     * мастер предлагает сам: замысел из документов и кандидаты из нормативов.
     * Иначе кнопка, предложенная системой, упиралась бы в её же настройку.
     */
    private fun assembleStartProfile(
        projectId: String,
        by: String,
        extraKinds: List<String> = emptyList(),
    ): AssembledProfile {
        val passport = boundary.objects.current(projectId)
            ?: throw NoSuchElementException("project '$projectId' not found")
        val prohibitions = mapper.createArrayNode()
        passport.doc.path("constraints")
            .filterNot { it.path("removed").asBoolean(false) } // Ф-02: отменённое — след, не запрет
            .forEach { c ->
                val text = c.path("text").asText("")
                val code = c.path("code").asText("")
                if (text.isNotBlank()) prohibitions.add(if (code.isBlank()) text else "$text ($code)")
            }
        val profileName = "Генерация О2 — цели и нужды"
        // Заготовка Г1 (§4 Ш3): базовые правила и глоссарий — из
        // библиотечного фрагмента полки G1; поверх — запреты проекта.
        val template = boundary.objects
            .listCurrent(orbita.mod.store.ObjectStore.LIBRARY_PROJECT)
            .filter { it.type == "library_fragment" && it.doc.path("shelf").asText() == "G1" }
            .maxByOrNull { it.id }
            ?.doc?.path("payload")?.path("objects")
            ?.firstOrNull { it.path("id").asText("").startsWith("AP-") }
        val doc = (template?.deepCopy() as? ObjectNode) ?: mapper.createObjectNode()
        doc.remove("id")
        doc.remove("lifecycle")
        doc.remove("provenance")
        doc.put("name", profileName)
        doc.put(
            "purpose",
            if (template == null)
                "Собран мастер-путём «Начало проекта»: запреты — из ограничений паспорта"
            else
                "Собран мастер-путём из заготовки Г1: запреты — из ограничений паспорта",
        )
        val kinds = (doc.path("kinds").takeIf { it.isArray } as? ArrayNode) ?: doc.putArray("kinds")
        DEFAULT_PROFILE_KINDS.forEach { k -> if (kinds.none { it.asText() == k }) kinds.add(k) }
        extraKinds.forEach { k -> if (kinds.none { it.asText() == k }) kinds.add(k) }
        doc.set<ArrayNode>("kinds", kinds)
        if (!doc.has("transport")) doc.put("transport", "any")
        doc.set<ObjectNode>("prohibitions", prohibitions)
        doc.put("require_source", true)
        val existing = boundary.objects.listCurrent(projectId)
            .firstOrNull {
                it.type == "ai_profile" && it.status != Lifecycle.Cancelled &&
                    it.doc.path("name").asText("") == profileName
            }
        val stored = if (existing == null) {
            boundary.editing.create(orbita.mod.model.CoreType.AiProfile, doc, by, projectId)
        } else {
            boundary.editing.update(
                orbita.mod.model.CoreType.AiProfile, existing.id, doc, existing.version, by,
            )
        }
        return AssembledProfile(
            stored.id, stored.version, profileName, prohibitions.size(), created = existing == null,
        )
    }

    /**
     * Ф-11 (тот же закон, что у кнопок): операция, которую система предлагает,
     * не имеет права упереться в служебную настройку. Раньше «собрать замысел
     * из документов» отвечало 400 «нет профиля службы с видом …»: профиль
     * собирается на последнем шаге мастера, а замысел спрашивается раньше —
     * тупик по построению.
     *
     * Теперь профиль ОБЕСПЕЧИВАЕТСЯ: у проекта уже есть профиль — вид
     * дописывается в него правкой; профилей нет вовсе — собирается тот же
     * профиль из ограничений паспорта, что и на шаге запуска. Инженеру
     * ничего настраивать не нужно, и запреты Р-кодов при этом не теряются.
     */
    private fun profileFor(kind: String, projectId: String, author: String): String {
        // учётки могут быть выключены — правку всё равно кто-то подписывает
        val by = author.ifBlank { "мастер-путь «Начало проекта»" }
        val profiles = boundary.objects.listCurrent(projectId)
            .filter { it.type == "ai_profile" && it.status != Lifecycle.Cancelled }
            .sortedBy { it.id }
        profiles.firstOrNull { p -> p.doc.path("kinds").any { it.asText() == kind } }?.let { return it.id }
        val host = profiles.firstOrNull()
        if (host != null) {
            val kinds = (host.doc.path("kinds").deepCopy<JsonNode>() as? ArrayNode) ?: mapper.createArrayNode()
            kinds.add(kind)
            val changes = mapper.createObjectNode()
            changes.set<ArrayNode>("kinds", kinds)
            val stored = boundary.editing.update(
                orbita.mod.model.CoreType.AiProfile, host.id, changes, host.version, by,
                changeRef = "вид «$kind» добавлен в профиль: операция предложена системой, настройка не спрашивается",
            )
            return stored.id
        }
        return assembleStartProfile(projectId, by, extraKinds = listOf(kind)).id
    }

    /**
     * Ф-09: тот же закон умолчания — но для документа ПОЛКИ. Паспорта у
     * библиотечной области нет, поэтому состав промпта хранится на самой
     * карточке: полка общая, и знание из неё идёт всем проектам класса.
     */
    private fun includeShelfDocumentInPrompt(sdId: String, version: String, author: String): Int {
        val map = DocumentParseStore.mapOf(filesDir(), sdId) ?: return 0
        val anchors = map.path("structure")
            .map { it.path("anchor").asText() }
            .filter { it.isNotBlank() }
        if (anchors.isEmpty()) return 0
        val prompt = mapper.createObjectNode().put("included", true)
        val arr = prompt.putArray("blocks")
        anchors.forEach { arr.add(it) }
        val changes = mapper.createObjectNode()
        changes.set<ObjectNode>("prompt", prompt)
        boundary.editing.update(
            CoreType.SourceDocument, sdId, changes, version, author,
            changeRef = "Ф-09: документ полки $sdId включён в промпт блоками — знание, а не имя",
        )
        return anchors.size
    }

    /** Авторские тексты разделов документа (В1.2) — текущие объекты проекта. */
    private fun sectionTexts(code: String, projectId: String?): Map<Int, orbita.out.SectionAuthorText> =
        boundary.objects.listCurrent(projectId)
            .filter {
                it.type == "section_text" && it.status != Lifecycle.Cancelled &&
                    it.doc.path("template_code").asText() == code
            }
            .associate {
                it.doc.path("section").asInt() to orbita.out.SectionAuthorText(
                    text = it.doc.path("text").asText(""),
                    insertsFingerprint = it.doc.path("inserts_fingerprint").asText(""),
                )
            }

    private fun libraryTemplates(): List<orbita.out.TemplateData> =
        boundary.objects
            .listCurrent(orbita.mod.store.ObjectStore.LIBRARY_PROJECT)
            .filter { it.type == "document_template" && it.status != Lifecycle.Cancelled }
            .map { orbita.out.TemplateData.of(it.doc) }
            .sortedBy { it.code }

    /** Автор текущего запроса из учётки (В3); null — однопользовательский режим. */
    private val currentAuthor = ThreadLocal<String?>()
    private val currentAuthorLogin = ThreadLocal<String?>()

    /** Автор для провенанса: учётка главнее поля тела (В3: автор — из учётки везде). */
    private fun author(fromBody: String): String = currentAuthor.get() ?: fromBody

    private fun sessionToken(ex: HttpExchange): String? =
        ex.requestHeaders.getFirst("Cookie")
            ?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("orbita_session=") }
            ?.substringAfter('=')
            ?: ex.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")?.takeIf { it.isNotBlank() }

    /**
     * Права по РЕЕСТРУ (В3, СТАРТ-В3 §2.3): «маршрут → роль» — данные
     * permissions.json, не константы в коде. Первый совпавший решает;
     * write-маршрут без правила закрыт. Спец-проверка владельца узла —
     * owner_guard правила.
     */
    private fun denyReason(
        method: String,
        path: String,
        role: String?,
        user: orbita.mod.store.AuthUser,
        project: String?,
        objectMatch: MatchResult?,
        editMatch: MatchResult?,
    ): String? {
        // создание проекта не должно запирать систему: без роли можно
        // только завести проект (создатель становится его руководителем)
        if (path == "/objects/project" || path == "/edit/project") return null
        if (role == null) return "у ${user.login} нет роли в проекте ${project ?: "—"}: назначает руководитель"
        val rule = orbita.req.Permissions.default.ruleFor(method, path)
            ?: return "маршрут $method $path не покрыт реестром прав — запись закрыта (fail-closed)"
        if (role !in rule.allow) return rule.why + "; ваша роль — " + role
        if (rule.ownerGuard && role == "specialist") {
            val id = editMatch?.groupValues?.get(1) ?: objectMatch?.groupValues?.get(1)
            if (id != null && (id.startsWith("CM-") || id.startsWith("CU-"))) {
                val owner = boundary.objects.current(id)?.doc?.path("owner")?.asText("") ?: ""
                if (owner.isNotBlank() && owner != user.login) {
                    return "узел $id принадлежит $owner: специалист работает в своих узлах"
                }
            }
        }
        return null
    }

    /** Маршруты учёток: регистрация, вход, выход, кто я, роли. */
    private fun authRoutes(ex: HttpExchange, method: String, path: String, user: orbita.mod.store.AuthUser?) {
        when {
            method == "POST" && path == "/auth/register" -> {
                val req = mapper.readTree(body(ex))
                val login = req.path("login").asText("")
                val password = req.path("password").asText("")
                val display = req.path("display_name").asText("").ifBlank { login }
                require(login.isNotBlank() && password.isNotBlank()) { "login и password обязательны" }
                // первая учётка заводится свободно (включение режима);
                // дальше регистрирует руководитель любого проекта
                val bootstrap = !boundary.auth.enabled()
                if (!bootstrap) {
                    val isLead = user != null && boundary.auth.rolesOf(user.login).containsValue("lead")
                    require(isLead) { "новые учётки заводит руководитель проекта" }
                }
                boundary.auth.createUser(login, password, display)
                if (bootstrap) {
                    // режим первичной настройки (СТАРТ-В3 §2.1): первая учётка —
                    // руководитель существующих проектов, ей назначать роли
                    boundary.objects.listCurrent()
                        .filter { it.type == "project" && it.status != Lifecycle.Cancelled }
                        .forEach { boundary.auth.setRole(it.id, login, "lead") }
                }
                respond(ex, 201, mapper.createObjectNode().put("login", login))
            }

            method == "POST" && path == "/auth/login" -> {
                val req = mapper.readTree(body(ex))
                val verified = boundary.auth.verify(
                    req.path("login").asText(""), req.path("password").asText(""),
                )
                if (verified == null) {
                    respond(ex, 401, mapper.createObjectNode().put("error", "неверный логин или пароль"))
                    return
                }
                val token = boundary.auth.createSession(verified.login)
                ex.responseHeaders.add(
                    "Set-Cookie",
                    "orbita_session=$token; Path=/; HttpOnly; SameSite=Lax; Max-Age=2592000",
                )
                respond(
                    ex, 200,
                    mapper.createObjectNode()
                        .put("login", verified.login)
                        .put("display_name", verified.displayName),
                )
            }

            method == "POST" && path == "/auth/logout" -> {
                sessionToken(ex)?.let { boundary.auth.dropSession(it) }
                ex.responseHeaders.add(
                    "Set-Cookie", "orbita_session=; Path=/; HttpOnly; Max-Age=0",
                )
                respond(ex, 200, mapper.createObjectNode().put("ok", true))
            }

            method == "GET" && path == "/auth/whoami" -> {
                val out = mapper.createObjectNode()
                out.put("enabled", boundary.auth.enabled())
                if (user != null) {
                    val u = out.putObject("user")
                    u.put("login", user.login)
                    u.put("display_name", user.displayName)
                    val roles = u.putObject("roles")
                    boundary.auth.rolesOf(user.login).forEach { (pj, r) -> roles.put(pj, r) }
                }
                respond(ex, 200, out)
            }

            // учётки поимённо — пикеру исполнителя (МВП-П1); паролей тут нет
            method == "GET" && path == "/auth/users" -> {
                val out = mapper.createObjectNode()
                val arr = out.putArray("users")
                boundary.auth.listUsers().forEach { (login, name) ->
                    arr.addObject().put("login", login).put("display_name", name)
                }
                respond(ex, 200, out)
            }

            // роли назначает руководитель проекта (создатель проекта — lead)
            method == "POST" && path == "/auth/roles" -> {
                requireNotNull(user) { "войдите" }
                val req = mapper.readTree(body(ex))
                val projectId = req.path("project").asText("")
                val login = req.path("login").asText("")
                val role = req.path("role").asText("")
                require(boundary.auth.roleIn(projectId, user.login) == "lead") {
                    "роли назначает руководитель проекта"
                }
                boundary.auth.setRole(projectId, login, role)
                respond(ex, 200, mapper.createObjectNode().put("ok", true))
            }

            // В3 §2.2: история неприкосновенна — карта «строка → учётка»
            method == "POST" && path == "/auth/author-map" -> {
                requireNotNull(user) { "войдите" }
                require(boundary.auth.rolesOf(user.login).containsValue("lead")) {
                    "карту авторов ведёт руководитель"
                }
                val req = mapper.readTree(body(ex))
                boundary.auth.mapAuthor(req.path("author").asText(), req.path("login").asText())
                respond(ex, 200, mapper.createObjectNode().put("ok", true))
            }

            method == "GET" && path == "/auth/author-map" -> {
                val out = mapper.createObjectNode()
                boundary.auth.authorMap().forEach { (a, l) -> out.put(a, l) }
                respond(ex, 200, out)
            }

            method == "GET" && Regex("^/auth/roles/PJ-[0-9]{4}$").matches(path) -> {
                requireNotNull(user) { "войдите" }
                val projectId = path.removePrefix("/auth/roles/")
                val out = mapper.createObjectNode()
                boundary.auth.listRoles(projectId).forEach { (l, r) -> out.put(l, r) }
                respond(ex, 200, out)
            }

            else -> respond(ex, 404, mapper.createObjectNode().put("error", "unknown auth route"))
        }
    }

    /** Каталог файлов исходных документов (том orbita-files; копия О-18). */
    private fun filesDir(): String = System.getenv("ORBITA_FILES_DIR") ?: "/files"

    /** Бинарный ответ печати: файл уходит людям без Орбиты (В1.4/О-8). */
    private fun respondBinary(ex: HttpExchange, bytes: ByteArray, contentType: String, filename: String) {
        ex.responseHeaders.set("Content-Type", contentType)
        ex.responseHeaders.set(
            "Content-Disposition",
            "attachment; filename*=UTF-8''" + java.net.URLEncoder.encode(filename, Charsets.UTF_8).replace("+", "%20"),
        )
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun resolveProject(ex: HttpExchange): String? {
        val asked = query(ex)["project"]
        // Область библиотеки — законный контекст запроса (СТРУКТУРА-БИБЛИОТЕКИ
        // §4): акцепт предложений службы для полок А2/Б3/В3 идёт в LIB.
        if (asked == orbita.mod.store.ObjectStore.LIBRARY_PROJECT) return asked
        if (asked != null) {
            val p = boundary.objects.current(asked)
            require(p != null && p.type == "project") {
                "проект '$asked' не найден"
            }
            return asked
        }
        val ids = boundary.objects.projectIds()
        return when {
            ids.isEmpty() -> null
            ids.size == 1 -> ids[0]
            else -> throw IllegalArgumentException(
                "в портфеле ${ids.size} проектов — укажите ?project=PJ-NNNN (${ids.joinToString()})"
            )
        }
    }

    // Разбор по СЫРОЙ строке: URI.getQuery() снимает проценты, но оставляет
    // «+» плюсом — имя карточки с пробелом приезжало как «Имя+карточки»
    // (замечание круга 3). URLDecoder декодирует и %XX, и «+» → пробел.
    private fun query(ex: HttpExchange): Map<String, String> =
        ex.requestURI.rawQuery?.split('&')?.mapNotNull { p ->
            p.substringBefore('=').takeIf { it.isNotBlank() }?.let {
                java.net.URLDecoder.decode(it, Charsets.UTF_8) to
                    java.net.URLDecoder.decode(p.substringAfter('=', ""), Charsets.UTF_8)
            }
        }?.toMap() ?: emptyMap()

    private fun summary(o: StoredObject): ObjectNode = mapper.createObjectNode()
        .put("id", o.id).put("type", o.type).put("version", o.version).put("status", o.status.name)

    /**
     * Содержательная подпись объекта для списка. Виды называют главное поле
     * по-разному: у нужды и требования это формулировка, у элемента и сервиса —
     * наименование. Пустая подпись — честный признак того, что содержания
     * в объекте ещё нет.
     */
    private fun titleOf(o: StoredObject): String =
        listOf("statement", "name", "title").firstNotNullOfOrNull { field ->
            o.doc.path(field).asText("").takeIf { it.isNotBlank() }
        } ?: ""

    /** Слои карты спроса из запроса экрана 4 (TZ-USR-004). */
    private fun demandLayers(request: JsonNode) = orbita.out.DemandLayers(
        population = request.path("population").map {
            orbita.usr.PopulationCell(
                id = it.path("id").asText(),
                lat = it.path("lat").asDouble(),
                lon = it.path("lon").asDouble(0.0),
                popDensityPerKm2 = it.path("pop_density_per_km2").asDouble(),
                terminalsPerCapita = it.path("terminals_per_capita").asDouble(),
                msgsPerTerminalDay = it.path("msgs_per_terminal_day").asDouble(),
                klass = it.path("consumer_class").asText(),
            )
        },
        pointObjects = request.path("point_objects").map {
            orbita.usr.SeedObject(
                cellId = it.path("cell_id").asText(),
                lat = it.path("lat").asDouble(),
                lon = it.path("lon").asDouble(0.0),
                terminals = it.path("terminals").asDouble(),
                msgsPerTerminalDay = it.path("msgs_per_terminal_day").asDouble(),
                klass = it.path("consumer_class").asText(),
            )
        },
        scenarioIds = request.path("scenario_ids").map { it.asText() },
        diurnal = request.path("diurnal").takeIf { it.isArray }?.map { it.asDouble() },
        seasonal = request.path("seasonal").takeIf { it.isArray }?.map { it.asDouble() },
    )

    // Ведомость масс телом запроса больше не приходит: после CR-006 она часть
    // модели аппарата (platform.mel), а не состояние экрана.

    private fun conditions(request: JsonNode) = request.path("conditions").let { c ->
        orbita.out.SpacecraftConditions(
            altKm = c.path("alt_km").asDouble(550.0),
            worstBetaDeg = c.path("worst_beta_deg").asDouble(0.0),
            minElevDeg = c.path("min_elev_deg").asDouble(5.0),
            yearsInOrbit = c.path("years_in_orbit").asDouble(0.0),
            plannedPayloadDuty = c.path("planned_payload_duty").asDouble(0.5),
        )
    }

    /** Условия оценки из строки запроса — для хранимой модели аппарата. */
    private fun conditions(q: Map<String, String>) = orbita.out.SpacecraftConditions(
        altKm = q["alt_km"]?.toDoubleOrNull() ?: 550.0,
        worstBetaDeg = q["worst_beta_deg"]?.toDoubleOrNull() ?: 0.0,
        minElevDeg = q["min_elev_deg"]?.toDoubleOrNull() ?: 5.0,
        yearsInOrbit = q["years_in_orbit"]?.toDoubleOrNull() ?: 0.0,
        plannedPayloadDuty = q["planned_payload_duty"]?.toDoubleOrNull() ?: 0.5,
    )

    private fun hops(hops: List<orbita.mod.store.TraceHop>): ArrayNode =
        mapper.createArrayNode().apply { hops.forEach { addObject().put("id", it.id).put("depth", it.depth) } }

    private fun errorsJson(errors: List<ValidationError>): ObjectNode {
        val res = mapper.createObjectNode()
        val arr = res.putArray("errors")
        errors.forEach { e ->
            arr.addObject().put("path", e.path).put("rule", e.rule).put("message", e.message)
                .put("adr", e.adr)
        }
        return res
    }

    private fun errJson(e: Exception): ObjectNode =
        mapper.createObjectNode().put("error", e.message ?: e.javaClass.simpleName)

    /**
     * Пустое состояние модели — рабочее, а не отказ (шаг 16 §2.2): 409 с текстом,
     * адресованным инженеру, и шагом мастера, где заводится недостающее.
     */
    private fun respondMissing(ex: HttpExchange, text: String, step: Int) =
        respond(ex, 409, mapper.createObjectNode().put("error", text).put("wizard_step", step))

    private fun respond(ex: HttpExchange, code: Int, node: JsonNode?) {
        if (node == null) {
            ex.sendResponseHeaders(code, -1)
            return
        }
        val bytes = mapper.writeValueAsBytes(node)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}
