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
        return p to composer.compose(kind, p, context(projectId, statement))
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

    /** Разбор и фильтр — общие для обоих транспортов. */
    private fun screen(raw: String, kind: String, p: AiProfile): ObjectNode {
        val pkg = boundary.packages.build(kind, mapper.createObjectNode(), "служба")
        val parsed = boundary.parser.parse(raw, pkg)
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
