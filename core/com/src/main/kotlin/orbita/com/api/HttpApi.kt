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
import java.net.InetSocketAddress
import java.time.OffsetDateTime

class HttpApi(private val boundary: Boundary) {

    private val mapper = ObjectMapper()

    /** Запуск на 127.0.0.1; port=0 — эфемерный порт (для тестов). */
    fun start(port: Int): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
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

        when {
            method == "POST" && Regex("^/objects/(need|service|requirement|component|scenario|risk)$").matches(path) -> {
                val type = CoreType.byDbType(path.substringAfterLast('/'))
                val stored = boundary.ingest(type, body(ex))
                respond(ex, 201, summary(stored))
            }

            // Перевод статуса (TZ-REQ-006): в Baseline — только зрелое требование
            method == "POST" && objectMatch?.groupValues?.get(2) == "/promote" -> {
                val target = Lifecycle.valueOf(mapper.readTree(body(ex)).path("status").asText())
                respond(ex, 200, summary(boundary.req.promote(objectMatch.groupValues[1], target)))
            }

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

            method == "GET" && path == "/slice" -> {
                val at = ex.requestURI.query?.split('&')
                    ?.firstOrNull { it.startsWith("at=") }?.substringAfter('=')
                    ?: throw IllegalArgumentException("query parameter 'at' is required")
                val arr = mapper.createArrayNode()
                boundary.objects.sliceAt(OffsetDateTime.parse(at)).forEach { arr.add(summary(it)) }
                respond(ex, 200, arr)
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

            // Реестр рисков (шаг 7): сводка считается сервером, включая критичность
            method == "GET" && path == "/reports/risk-register" -> {
                val summary = orbita.req.registerSummary(boundary.req.risks())
                respond(ex, 200, mapper.valueToTree(summary))
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

            method == "GET" && Regex("^/views/components/(CM-[0-9]{4})$").matches(path) -> {
                val id = path.removePrefix("/views/components/")
                respond(ex, 200, mapper.valueToTree(boundary.screens.componentSpecification(id)))
            }

            // Подписи единиц: подстановка на стороне представления, коды СИ
            // в модели не меняются (STEP-6 §3.2, ловушка 6)
            method == "GET" && path == "/unit-labels" ->
                respond(ex, 200, mapper.valueToTree(orbita.req.UnitLabels().all()))

            method == "GET" && Regex("^/components/(CM-[0-9]{4})/specification$").matches(path) -> {
                val cm = path.removePrefix("/components/").removeSuffix("/specification")
                respond(ex, 200, mapper.valueToTree(boundary.req.specificationOf(cm)))
            }

            method == "GET" && path == "/reports/needs-without-services" ->
                respond(ex, 200, mapper.valueToTree(boundary.req.needsWithoutServices()))

            method == "GET" && path == "/reports/elements-without-requirements" ->
                respond(ex, 200, mapper.valueToTree(boundary.req.elementsWithoutRequirements()))

            method == "GET" && path == "/reports/review-candidates" ->
                respond(ex, 200, mapper.valueToTree(boundary.req.reviewCandidates()))

            // Параметры канала отдаются только адаптером (TZ-NET-001, TZ-NET-006)
            method == "GET" && path == "/protocol-adapter" ->
                respond(ex, 200, boundary.protocolAdapter.toContractJson(mapper))

            method == "POST" && path.startsWith("/validate/") -> {
                val schema = path.removePrefix("/validate/")
                val errors = boundary.validateContract(schema, body(ex))
                val res = mapper.createObjectNode().put("valid", errors.isEmpty())
                res.set<ArrayNode>("errors", (errorsJson(errors)["errors"] as ArrayNode))
                respond(ex, 200, res)
            }

            else -> respond(
                ex, 404,
                mapper.createObjectNode().put("error", "no route: $method /api$path"),
            )
        }
    }

    private fun body(ex: HttpExchange): String = ex.requestBody.readAllBytes().decodeToString()

    private fun query(ex: HttpExchange): Map<String, String> =
        ex.requestURI.query?.split('&')?.mapNotNull { p ->
            p.substringBefore('=').takeIf { it.isNotBlank() }?.let { it to p.substringAfter('=', "") }
        }?.toMap() ?: emptyMap()

    private fun summary(o: StoredObject): ObjectNode = mapper.createObjectNode()
        .put("id", o.id).put("type", o.type).put("version", o.version).put("status", o.status.name)

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
