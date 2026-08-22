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
        } catch (e: SchemaValidationException) {
            respond(ex, 422, errorsJson(e.errors))
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

        when {
            // Список видов выводится из состава типов, а не перечисляется руками:
            // после ADR-021 их стало пятнадцать, и забытый в регулярном выражении
            // вид означал бы объект, который модель хранит, но принять не может.
            method == "POST" && objectTypePath(path) != null -> {
                val stored = boundary.ingest(objectTypePath(path)!!, body(ex))
                respond(ex, 201, summary(stored))
            }

            // Перевод статуса (TZ-REQ-006): в Baseline — только зрелое требование
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
                val stored = boundary.objects.change(id, doc, changeRef = req.path("change_ref").textValue())
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

            method == "POST" && path == "/links" -> {
                val req = mapper.readTree(body(ex))
                boundary.links.add(
                    req["from"].asText(), req["to"].asText(),
                    req.path("kind").asText("trace"),
                )
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

            method == "GET" && path == "/reports/trace-breaks" ->
                respond(ex, 200, mapper.valueToTree(boundary.links.traceBreaks()))

            method == "GET" && path == "/reports/unaccepted-ai" -> {
                val arr = mapper.createArrayNode()
                boundary.params.unacceptedAiProposals().forEach {
                    arr.addObject().put("object_id", it.objectId).put("name", it.name)
                        .put("prompt_package_id", it.promptPackageId)
                }
                respond(ex, 200, arr)
            }

            method == "GET" && path == "/reports/stale-results" -> {
                val arr = mapper.createArrayNode()
                boundary.results.staleReport().forEach {
                    arr.addObject().put("pk", it.pk).put("scenario_id", it.scenarioId).put("kind", it.kind)
                }
                respond(ex, 200, arr)
            }

            // Отчёты шага 2 (TZ-OUT-003/004, TZ-REQ-001/005/006)
            method == "GET" && path == "/reports/maturity" -> {
                val q = query(ex)
                val gate = q["gate"] ?: throw IllegalArgumentException("query parameter 'gate' is required")
                val report = boundary.maturity.build(gate, q["at"]?.let(OffsetDateTime::parse))
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
                val m = boundary.matrices.traceMatrix()
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
            method == "GET" && path == "/reports/verification-matrix" -> {
                val arr = mapper.createArrayNode()
                boundary.matrices.verificationMatrix(query(ex)["configuration"]).forEach { r ->
                    val n = arr.addObject()
                    n.put("requirement", r.requirementId).put("state", r.state)
                    n.set<ArrayNode>("plan_issues", mapper.valueToTree(r.planIssues))
                    val events = n.putArray("events")
                    r.events.forEach { e ->
                        events.addObject()
                            .put("event", e.eventId).put("method", e.method).put("kind", e.kind)
                            .put("phase", e.phase).put("level", e.level).put("closes", e.closes)
                            .put("status", e.status).put("approach", e.approach).put("means", e.means)
                            .put("success_criterion", e.successCriterion)
                            .put("evidence_ref", e.evidenceRef).put("evidence_state", e.evidenceState)
                    }
                }
                respond(ex, 200, arr)
            }

            // CR-003: валидация — отдельная матрица, отдельный вопрос «то ли построили»
            method == "GET" && path == "/reports/validation-matrix" -> {
                val arr = mapper.createArrayNode()
                boundary.matrices.validationMatrix().forEach { v ->
                    arr.addObject()
                        .put("validation", v.validationId).put("target", v.target)
                        .put("conops_ref", v.conopsRef).put("product_kind", v.productKind)
                        .put("method", v.method).put("phase", v.phase).put("status", v.status)
                        .put("evidence_ref", v.evidenceRef)
                }
                respond(ex, 200, arr)
            }

            method == "GET" && path == "/reports/inconsistent-allocations" -> {
                val arr = mapper.createArrayNode()
                boundary.req.inconsistentAllocations().forEach { (parent, child, why) ->
                    arr.addObject().put("parent", parent).put("child", child).put("reason", why)
                }
                respond(ex, 200, arr)
            }

            // Экраны клиента: строки приходят готовыми, клиент ничего не считает
            method == "GET" && path == "/views/requirement-tree" -> {
                val view = boundary.screens.requirementTree()
                val n = mapper.createObjectNode()
                n.set<ArrayNode>("roots", mapper.valueToTree(view.roots))
                n.set<ObjectNode>("children", mapper.valueToTree(view.children))
                n.set<ArrayNode>("rows", mapper.valueToTree(view.rows))
                respond(ex, 200, n)
            }

            method == "GET" && Regex("^/views/requirements/(RQ-[0-9]{4})$").matches(path) -> {
                val id = path.removePrefix("/views/requirements/")
                respond(ex, 200, mapper.valueToTree(boundary.screens.card(id)))
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
                    by = request.path("by").asText(""),
                )
                // Акцептующий инженер и есть автор изменения (TZ-AI-004):
                // поле называется `by`, но роль у него та же, что у `author`.
                val author = request.path("by").asText("").trim().takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("TZ-AI-004: field 'by' is required to accept a proposal")
                val type = CoreType.byDbType(stored?.type ?: typeByIdPrefix(targetId).dbType)
                val saved = if (stored == null) {
                    marked.put("id", targetId)
                    boundary.editing.create(type, marked, author)
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
                respond(ex, 200, mapper.valueToTree(boundary.wizard.needs()))

            method == "GET" && path == "/views/services" ->
                respond(ex, 200, mapper.valueToTree(boundary.wizard.services()))

            method == "GET" && path == "/views/readiness" ->
                respond(ex, 200, mapper.valueToTree(boundary.wizard.readiness(query(ex)["gate"] ?: "SRR")))

            method == "GET" && path == "/views/wizard" ->
                respond(ex, 200, mapper.valueToTree(boundary.wizard.wizard(boundary.screens)))

            // Реестр рисков: список и матрица одним ответом
            method == "GET" && path == "/views/risks" -> {
                val risks = boundary.req.risks()
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

            // Экран 6: глобус — CZML-поток с траекториями. Собственной модели
            // движения в клиенте нет: трассы считает пропагатор на сервере.
            method == "GET" && path == "/views/globe" -> {
                val q = query(ex)
                val config = orbita.bal.ConstellationConfig(
                    incDeg = q["inc_deg"]?.toDoubleOrNull() ?: 53.0,
                    total = q["total"]?.toIntOrNull() ?: 8,
                    planes = q["planes"]?.toIntOrNull() ?: 2,
                    phasing = q["phasing"]?.toIntOrNull() ?: 1,
                    altKm = q["alt_km"]?.toDoubleOrNull() ?: 550.0,
                )
                val epoch = q["epoch"] ?: "2026-03-20T00:00:00.000Z"
                val durationS = q["duration_s"]?.toDoubleOrNull() ?: 5400.0
                val tracks = boundary.visibility.groundTracks(config, epoch, durationS)
                respond(ex, 200, orbita.bal.VizData.czml(config, epoch, durationS, tracks, mapper))
            }

            // Экран 12: система в целом — сводки, бюджеты, матрица рисков
            method == "GET" && path == "/views/system" ->
                respond(ex, 200, mapper.valueToTree(boundary.screens.systemOverview()))

            // Экспорт ReqIF (TZ-OUT-005, ADR-023): отображение здесь, XML — в службе
            // обмена. Дата выгрузки фиксируется в файле; параметр exported_at
            // позволяет получить воспроизводимый файл.
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
                    val model = orbita.out.ModelSnapshot.of(boundary.objects, mapper)
                    val links = (boundary.links.list("trace") + boundary.links.list("derive"))
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
                val current = boundary.objects.listCurrent()
                val demandMap = current.firstOrNull { it.type == "demand_map" }
                    ?: throw NoSuchElementException("карта спроса не загружена: маску не из чего строить")
                val stations = current.firstOrNull { it.type == "ground_stations" }
                    ?: throw NoSuchElementException("станции не заданы: маску сброса не из чего строить")
                val constellation = current.firstOrNull { it.type == "constellation" }
                    ?: throw NoSuchElementException("группировка не задана: трассу не по чему считать")
                val spacecraft = current.firstOrNull { it.type == "spacecraft" }

                val w = constellation.doc.path("walker")
                val config = orbita.bal.ConstellationConfig(
                    incDeg = w.path("inclination_deg").asDouble(),
                    total = w.path("total").asInt(),
                    planes = w.path("planes").asInt(),
                    phasing = w.path("phasing").asInt(),
                    altKm = w.path("altitude_km").asDouble(),
                )
                val masks = orbita.ka.buildMasks(demandMap.doc, stations.doc, config.altKm)
                val epoch = query(ex)["epoch"] ?: "2026-03-20T00:00:00.000Z"
                val durationS = query(ex)["duration_s"]?.toDoubleOrNull() ?: 86400.0
                // трасса одного аппарата: в симметричном Уокере статистика
                // долей витка одна на всех
                val track = boundary.visibility.groundTracks(config, epoch, durationS)
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
                val stored = boundary.objects.listCurrent()
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
                    val existing = boundary.objects.listCurrent()
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
                val options = boundary.results.activeForScenario(
                    query(ex)["scenario"] ?: "SC-0001", "kpi",
                ).map { r ->
                    (r.payload.deepCopy() as ObjectNode)
                        .put("stale", r.stale)
                        .put("rng_seed", r.rngSeed)
                        .also { n ->
                            val iv = n.putObject("input_versions")
                            r.inputVersions.forEach { (k, v) -> iv.put(k, v) }
                        }
                }
                val spacecraft = boundary.objects.listCurrent().firstOrNull { it.type == "spacecraft" }
                val budgets = spacecraft?.let {
                    orbita.out.ModelSnapshot.budgetsOf(
                        boundary.spacecraft.build(it.doc, orbita.out.SpacecraftConditions()),
                        mapper,
                    )
                } ?: emptyList()
                val model = orbita.out.ModelSnapshot.of(
                    boundary.objects, mapper, options = options, budgets = budgets,
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

            // Экран 7: сравнение вариантов — нормировка и Парето считаются здесь
            method == "GET" && path == "/views/comparison" -> {
                val options = boundary.results.activeForScenario(
                    query(ex)["scenario"] ?: "SC-0001", "kpi",
                ).map { r ->
                    orbita.bal.RadarOption(
                        r.payload.path("name").asText(),
                        buildMap {
                            listOf("quality", "cost", "reliability", "energy",
                                "deployment_days", "launch_campaigns").forEach { axis ->
                                r.payload.path(axis).takeIf { it.isNumber }?.let { put(axis, it.asDouble()) }
                            }
                        },
                    )
                }
                if (options.size < 2) {
                    respond(
                        ex, 409,
                        mapper.createObjectNode()
                            .put("error", "сравнение требует не менее двух вариантов: нормировать не по чему"),
                    )
                } else {
                    respond(ex, 200, mapper.valueToTree(orbita.out.comparisonView(options)))
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

            method == "GET" && path == "/reports/needs-without-services" ->
                respond(ex, 200, mapper.valueToTree(boundary.req.needsWithoutServices()))

            method == "GET" && path == "/reports/elements-without-requirements" ->
                respond(ex, 200, mapper.valueToTree(boundary.req.elementsWithoutRequirements()))

            method == "GET" && path == "/reports/review-candidates" ->
                respond(ex, 200, mapper.valueToTree(boundary.req.reviewCandidates()))

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
                val stored = boundary.editing.create(
                    editTypePath(path)!!, req.path("doc"), author(req),
                )
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
                        .put("author", v.createdBy).put("valid_from", v.validFrom.toString())
                        .put("valid_to", v.validTo?.toString())
                        .put("current", v.validTo == null)
                }
                respond(ex, 200, arr)
            }

            // Что мешает базированию — до попытки перевода, чтобы форма могла
            // показать это инженеру, а не отказом после нажатия.
            method == "GET" && editMatch?.groupValues?.get(2) == "/issues" -> {
                val issues = boundary.editing.promotionIssues(editMatch.groupValues[1])
                val n = mapper.createObjectNode().put("can_baseline", issues.isEmpty())
                val arr = n.putArray("issues")
                issues.forEach(arr::add)
                respond(ex, 200, n)
            }

            // Объекты вида для списка на экране (шаг 15). Подпись объекта
            // выбирает СЕРВЕР: какое поле содержательно, знает модель, а не
            // клиент, которому иначе пришлось бы гадать по именам полей.
            method == "GET" && path == "/objects" -> {
                val type = query(ex)["type"]
                    ?: throw IllegalArgumentException("query parameter 'type' is required")
                val arr = mapper.createArrayNode()
                boundary.objects.listCurrent().filter { it.type == type }.forEach { o ->
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
    private fun author(request: JsonNode): String =
        request.path("author").asText("").trim().takeIf { it.isNotEmpty() }
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

    private fun query(ex: HttpExchange): Map<String, String> =
        ex.requestURI.query?.split('&')?.mapNotNull { p ->
            p.substringBefore('=').takeIf { it.isNotBlank() }?.let { it to p.substringAfter('=', "") }
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
