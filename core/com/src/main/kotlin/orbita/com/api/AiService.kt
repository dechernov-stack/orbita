// Служба ИИ (П5 задания «прогон до KDP B»): профиль → промпт → вызов →
// разбор → фильтр (включая правило основания) → журнал.
//
// Транспорта два, формат один. Прямой вызов провайдера — основной; закрытый
// контур (пакет отдаётся владельцу, ответ возвращается файлом) — режим того же
// формата, а не отдельная тропа: разбор, фильтр и журнал у них общие, иначе
// один канал молча расходится с другим.
package orbita.com.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.ai.AiProfile
import orbita.ai.ModelContext
import orbita.ai.NO_SOURCE_RULE
import orbita.ai.PromptComposer
import orbita.ai.ProviderTransport
import orbita.ai.ProviderUnavailableException
import orbita.ai.ScreeningContext
import orbita.mod.store.AiCallStore
import java.math.BigDecimal

/** Итог обращения к службе: что предложено, что снято, чем и почём. */
data class AiServiceRun(
    val callPk: Long,
    val prompt: String,
    val transport: String,
    val model: String?,
    val report: ObjectNode,
)

class AiService(
    private val boundary: Boundary,
    private val provider: ProviderTransport,
    private val composer: PromptComposer = PromptComposer(),
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    private val calls: AiCallStore get() = AiCallStore(boundary.connection)

    /** Профиль службы по идентификатору; без профиля служба не работает. */
    fun profile(profileId: String, projectId: String): AiProfile {
        val stored = boundary.objects.current(profileId)
            ?: throw NoSuchElementException("профиль службы '$profileId' не найден")
        require(stored.type == "ai_profile") { "'$profileId' — не профиль службы" }
        require(stored.projectId == projectId) {
            "ADR-022: профиль $profileId принадлежит проекту ${stored.projectId}"
        }
        return AiProfile.of(stored.doc)
    }

    /**
     * Состояние модели для промпта: что уже есть в проекте. Список ограничен
     * видами, которые вид пакета берёт на вход, — пакет с полным проектом
     * внутри и дороже, и хуже.
     */
    fun context(projectId: String, statement: String): ModelContext {
        val project = boundary.objects.current(projectId)
        val objects = boundary.objects.listCurrent(projectId)
            .filter { it.status.name != "Cancelled" && it.type != "ai_profile" }
        return ModelContext(
            projectName = project?.doc?.path("name")?.asText(projectId) ?: projectId,
            phase = project?.doc?.path("phase")?.asText("") ?: "",
            existing = objects.groupBy { it.type }.mapValues { (_, list) ->
                list.sortedBy { it.id }.map { o ->
                    val title = listOf("name", "statement", "question", "code")
                        .firstNotNullOfOrNull { f -> o.doc.path(f).asText("").ifBlank { null } }
                        ?: ""
                    "${o.id} — ${title.take(120)}"
                }
            },
            statement = statement,
        )
    }

    /** Промпт, собранный службой. Клиент его не сочиняет — только читает. */
    fun compose(kind: String, profileId: String, projectId: String, statement: String): Pair<AiProfile, String> {
        val p = profile(profileId, projectId)
        // схема ответа — из пакета: прямой канал шлёт модели только текст,
        // и без схемы она отвечает своей формой
        val schema = if (kind == ENRICHMENT_KIND) enrichmentResponseSchema()
        else boundary.packages.build(kind, mapper.createObjectNode(), "служба").responseSchema
        val effective = if (kind == ENRICHMENT_KIND) enrichmentStatement(projectId, statement)
        else statement
        return p to composer.compose(kind, p, context(projectId, effective), schema)
    }

    /**
     * Дозаполнение (находка живого прогона: 140 требований без обоснования
     * и показателя): ВХОД собирает служба — дырявые требования проекта с
     * перечнем недостающего, пачкой не больше [ENRICHMENT_BATCH] за вызов
     * (длинный ответ дорог и рвётся; вызов повторяется, пока дыры есть).
     */
    fun enrichmentStatement(projectId: String, note: String): String {
        val holes = enrichmentCandidates(projectId)
        val batch = holes.take(ENRICHMENT_BATCH)
        val sb = StringBuilder()
        if (note.isNotBlank()) sb.append(note).append("\n\n")
        sb.append("Дозаполни атрибуты СУЩЕСТВУЮЩИХ требований. Верни массив объектов ")
        sb.append("строго по схеме ответа: только id из перечня ниже и ТОЛЬКО недостающие поля ")
        sb.append("(rationale — обоснование одним-двумя предложениями со ссылкой на источник ")
        sb.append("Р-ограничение/сервис/нужду; mop — показатель с оператором и величиной, ")
        sb.append("значение обязано нести основание по правилу основания). Новых требований не создавать.\n")
        sb.append("Всего дырявых: ${holes.size}; в этой пачке: ${batch.size}.\n\n")
        batch.forEach { (o, missing) ->
            sb.append("- ").append(o.id).append(" [не хватает: ").append(missing.joinToString(", ")).append("] ")
            sb.append(o.doc.path("category").asText("")).append(": ")
            sb.append(o.doc.path("statement").asText("").take(220)).append("\n")
        }
        return sb.toString()
    }

    /** Требования с дырами: без обоснования либо без показателя. */
    fun enrichmentCandidates(projectId: String): List<Pair<orbita.mod.store.StoredObject, List<String>>> =
        boundary.objects.listCurrent(projectId)
            .filter { it.type == "requirement" && it.status.name != "Cancelled" }
            .sortedBy { it.id }
            .mapNotNull { o ->
                val missing = buildList {
                    if (o.doc.path("rationale").asText("").isBlank()) add("rationale")
                    if (!o.doc.path("mop").isObject || o.doc.path("mop").isEmpty) add("mop")
                }
                if (missing.isEmpty()) null else o to missing
            }

    /** Схема ответа дозаполнения: частичные правки, не полные объекты вида. */
    private fun enrichmentResponseSchema(): com.fasterxml.jackson.databind.JsonNode {
        val quantity = mapper.createObjectNode().apply {
            put("type", "object")
            putArray("required").add("value").add("unit").add("provenance")
            put("additionalProperties", false)
            with(putObject("properties")) {
                putObject("value").put("type", "number")
                putObject("unit").put("type", "string")
                with(putObject("provenance")) {
                    put("type", "object")
                    putArray("required").add("source")
                    with(putObject("properties")) {
                        putObject("source").putArray("enum")
                            .add("computed").add("imported")
                        with(putObject("module")) { put("type", "string") }
                        with(putObject("import")) {
                            put("type", "object")
                            // полное происхождение (ADR-024): без версии и даты
                            // получения нормативная схема правку отклонит
                            putArray("required").add("dataset").add("dataset_version")
                                .add("retrieved_at").add("terms")
                            with(putObject("properties")) {
                                putObject("dataset").put("type", "string")
                                putObject("dataset_version").put("type", "string")
                                putObject("retrieved_at").put("type", "string")
                                putObject("terms").put("type", "string")
                            }
                        }
                    }
                }
            }
        }
        val item = mapper.createObjectNode().apply {
            put("type", "object")
            putArray("required").add("id")
            put("additionalProperties", false)
            with(putObject("properties")) {
                putObject("id").put("type", "string").put("pattern", "^RQ-[0-9]{4}$")
                putObject("rationale").put("type", "string").put("minLength", 10)
                with(putObject("mop")) {
                    put("type", "object")
                    putArray("required").add("name").add("operator").add("value")
                    put("additionalProperties", false)
                    with(putObject("properties")) {
                        putObject("name").put("type", "string").put("minLength", 3)
                        putObject("operator").putArray("enum")
                            .add("ge").add("le").add("gt").add("lt").add("eq")
                        set<com.fasterxml.jackson.databind.node.ObjectNode>("value", quantity)
                    }
                }
            }
        }
        return mapper.createObjectNode().apply {
            put("type", "array")
            set<com.fasterxml.jackson.databind.node.ObjectNode>("items", item)
        }
    }

    /**
     * Прямой вызов (основной транспорт). Отказ провайдера — не ошибка модели:
     * он записывается в журнал и возвращается инженеру как состояние.
     */
    fun ask(
        kind: String,
        profileId: String,
        projectId: String,
        statement: String,
        author: String,
    ): AiServiceRun {
        val (p, prompt) = compose(kind, profileId, projectId, statement)
        require(p.transport != "package") {
            "профиль ${p.id} работает режимом закрытого контура: соберите пакет и внесите ответ"
        }
        val answer = try {
            provider.ask(prompt, p.modelHint)
        } catch (e: ProviderUnavailableException) {
            val pk = calls.record(
                projectId = projectId, kind = kind, transport = "direct", prompt = prompt,
                createdBy = author, profileId = p.id, profileVersion = p.version,
                failure = e.message,
            )
            val out = mapper.createObjectNode()
            out.put("failed", true)
            out.put("reason", e.message)
            return AiServiceRun(pk, prompt, "direct", null, out)
        }
        val screened = screen(answer.text, kind, p)
        val pk = calls.record(
            projectId = projectId, kind = kind, transport = "direct", prompt = prompt,
            createdBy = author, profileId = p.id, profileVersion = p.version,
            model = answer.model, response = answer.text,
            tokensIn = answer.tokensIn, tokensOut = answer.tokensOut,
            costUsd = cost(answer.tokensIn, answer.tokensOut),
            proposed = screened.path("proposed").asInt(),
            filtered = screened.path("rework").path("rejected").asInt(),
            noSource = screened.path("no_source").asInt(),
        )
        screened.put("call", pk)
        screened.put("model", answer.model)
        return AiServiceRun(pk, prompt, "direct", answer.model, screened)
    }

    /**
     * Закрытый контур: ответ владельца, полученный файлом, — тем же разбором
     * и фильтром, с той же записью в журнал (транспорт `package`).
     */
    fun submit(
        kind: String,
        profileId: String,
        projectId: String,
        statement: String,
        raw: String,
        author: String,
    ): AiServiceRun {
        val (p, prompt) = compose(kind, profileId, projectId, statement)
        val screened = screen(raw, kind, p)
        val pk = calls.record(
            projectId = projectId, kind = kind, transport = "package", prompt = prompt,
            createdBy = author, profileId = p.id, profileVersion = p.version,
            model = p.modelHint, response = raw,
            proposed = screened.path("proposed").asInt(),
            filtered = screened.path("rework").path("rejected").asInt(),
            noSource = screened.path("no_source").asInt(),
        )
        screened.put("call", pk)
        return AiServiceRun(pk, prompt, "package", p.modelHint, screened)
    }

    /**
     * Разбор и фильтр — общие для обоих транспортов.
     *
     * Проверка идёт по НОРМАТИВНОЙ СХЕМЕ целевого вида, а не по списку
     * обязательных полей: список required — условие необходимое, но не
     * достаточное, и предложение, прошедшее только его, до модели всё равно
     * не доходит (пачка отклоняется целиком на записи). Инженер обязан
     * видеть лишь то, что ляжет: иначе «до инженера доходит состоятельное»
     * перестаёт быть правдой.
     */
    private fun screen(raw: String, kind: String, p: AiProfile): ObjectNode {
        val pkg = boundary.packages.build(kind, mapper.createObjectNode(), "служба")
        val schemaName = orbita.ai.PackageKinds.default().of(kind).targetSchema
        val parsed = if (kind == ENRICHMENT_KIND) {
            // частичные правки: полная схема вида к ним неприменима,
            // проверка — против схемы ответа дозаполнения
            boundary.parser.parseAgainstInline(raw, pkg, enrichmentResponseSchema())
        } else if (schemaName != null) {
            boundary.parser.parseAgainstSchema(raw, pkg, boundary.schemas, schemaName)
        } else {
            boundary.parser.parse(raw, pkg)
        }
        val report = boundary.screening.screen(
            parsed.accepted,
            ScreeningContext(requireSource = p.requireSource),
        )
        val out = mapper.createObjectNode()
        out.put("proposed", parsed.accepted.size + parsed.rejected.size)
        out.put(
            "no_source",
            report.rework.count { s -> s.issues.any { it.startsWith(NO_SOURCE_RULE) } },
        )
        val malformed = out.putArray("malformed")
        parsed.rejected.forEach { r ->
            val item = malformed.addObject()
            r.item?.let { item.set<ObjectNode>("item", it) }
            r.errors.forEach { item.withArray("errors").add(it) }
        }
        val shown = out.putArray("shown")
        report.shown.forEach { shown.addObject().set<ObjectNode>("item", it) }
        out.set<ObjectNode>("rework", report.reworkContext(mapper))
        val byRule = out.putObject("by_rule")
        report.byRule.forEach { (rule, count) -> byRule.put(rule, count) }
        return out
    }

    companion object {
        const val ENRICHMENT_KIND = "requirement_enrichment"
        /** Дырявых в один вызов: длинный ответ дорог и рвётся. */
        const val ENRICHMENT_BATCH = 30
    }

    /** Акцепт дописывается к своему вызову — «сколько дошло до модели». */
    fun markAccepted(callPk: Long, accepted: Int, by: String) =
        calls.markAccepted(callPk, accepted, by)

    fun journal(projectId: String): ObjectNode {
        val out = mapper.createObjectNode()
        val totals = out.putObject("totals")
        calls.totals(projectId).forEach { (k, v) ->
            when (v) {
                is BigDecimal -> totals.put(k, v)
                else -> totals.put(k, v.toLong())
            }
        }
        val arr = out.putArray("calls")
        calls.list(projectId).forEach { c ->
            val n = arr.addObject()
            n.put("pk", c.pk)
            n.put("at", c.at.toString())
            n.put("kind", c.kind)
            n.put("profile", c.profileId)
            n.put("profile_version", c.profileVersion)
            n.put("transport", c.transport)
            n.put("model", c.model)
            n.put("tokens_in", c.tokensIn)
            n.put("tokens_out", c.tokensOut)
            n.put("cost_usd", c.costUsd)
            n.put("proposed", c.proposed)
            n.put("filtered", c.filtered)
            n.put("no_source", c.noSource)
            n.put("accepted", c.accepted)
            n.put("accepted_by", c.acceptedBy)
            n.put("failure", c.failure)
            n.put("prompt", c.prompt)
            n.put("author", c.createdBy)
        }
        return out
    }

    /**
     * Стоимость вызова. Цены — окружением (ORBITA_AI_PRICE_IN/OUT, долларов
     * за миллион токенов): зашитый в код прайс устаревает молча, а «почём»
     * без цены не отвечается вовсе.
     */
    private fun cost(tokensIn: Int?, tokensOut: Int?): BigDecimal? {
        val priceIn = System.getenv("ORBITA_AI_PRICE_IN")?.toBigDecimalOrNull() ?: return null
        val priceOut = System.getenv("ORBITA_AI_PRICE_OUT")?.toBigDecimalOrNull() ?: return null
        val million = BigDecimal(1_000_000)
        val a = BigDecimal(tokensIn ?: 0).multiply(priceIn).divide(million)
        val b = BigDecimal(tokensOut ?: 0).multiply(priceOut).divide(million)
        return a.add(b)
    }
}
