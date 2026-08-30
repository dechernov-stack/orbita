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
    private val statementSources by lazy { StatementSources(boundary) }

    /**
     * Ф-05: генерация постановки без замысла миссии заблокирована — промпт
     * без него даёт общие места. Проверка живёт здесь, у сборки промпта, и
     * потому одинаково срабатывает и на предпросмотре, и на вызове модели.
     * Пакетный канал (вставка готового пакета) не затронут.
     */
    fun requireStatementReady(kind: String, projectId: String) {
        statementSources.refusalFor(kind, projectId)?.let {
            throw IllegalArgumentException(it)
        }
    }

    fun context(projectId: String, statement: String, kind: String? = null): ModelContext {
        val project = boundary.objects.current(projectId)
        // §6 СТРУКТУРЫ-БИБЛИОТЕКИ: compose тянет и полки — профили
        // стейкхолдеров, типовые риски, нормативы живут в области LIB,
        // а выборка операции должна их видеть
        val shelfTypes = setOf("stakeholder_profile", "typical_risk", "normative_document", "mission_class")
        val objects = (boundary.objects.listCurrent(projectId) +
            boundary.objects.listCurrent(orbita.mod.store.ObjectStore.LIBRARY_PROJECT)
                .filter { it.type in shelfTypes })
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
            sources = kind?.let { k ->
                statementSources.of(k, projectId).map {
                    orbita.ai.ContextSource(it.key, it.title, it.lines, it.note)
                }
            } ?: emptyList(),
        )
    }

    /** Промпт, собранный службой. Клиент его не сочиняет — только читает. */
    /** Блоки промпта с атрибуцией источников — предпросмотру (О-4). */
    fun composeBlocks(
        kind: String, profileId: String, projectId: String, statement: String,
    ): Pair<AiProfile, List<orbita.ai.PromptComposer.PromptBlock>> {
        val p = profile(profileId, projectId)
        val schema = if (kind == ENRICHMENT_KIND) enrichmentResponseSchema()
        else boundary.packages.build(kind, mapper.createObjectNode(), "служба").responseSchema
        val effective = if (kind == ENRICHMENT_KIND) enrichmentStatement(projectId, statement)
        else statement
        requireStatementReady(kind, projectId)
        return p to composer.composeBlocks(kind, p, context(projectId, effective, kind), schema)
    }

    /**
     * @param enforceReady Ф-05: требовать замысел миссии. Пакетный канал
     * (готовый пакет вносится без вызова модели) проверкой не затронут —
     * там промпт собирается лишь для журнала.
     */
    fun compose(
        kind: String,
        profileId: String,
        projectId: String,
        statement: String,
        enforceReady: Boolean = true,
    ): Pair<AiProfile, String> {
        val p = profile(profileId, projectId)
        // схема ответа — из пакета: прямой канал шлёт модели только текст,
        // и без схемы она отвечает своей формой
        val schema = if (kind == ENRICHMENT_KIND) enrichmentResponseSchema()
        else boundary.packages.build(kind, mapper.createObjectNode(), "служба").responseSchema
        val effective = if (kind == ENRICHMENT_KIND) enrichmentStatement(projectId, statement)
        else statement
        if (enforceReady) requireStatementReady(kind, projectId)
        return p to composer.compose(kind, p, context(projectId, effective, kind), schema)
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
        val screened = screen(completeImportProvenance(answer.text, projectId), kind, p)
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

    /** Сырой ответ службы: текст как пришёл, плюс запись в журнал. */
    data class RawAnswer(
        val call: Long,
        val text: String?,
        val model: String?,
        val failure: String?,
    )

    /**
     * Живой вызов БЕЗ фильтра предложений: операции, чей ответ — не пачка
     * объектов на акцепт, а один документ по своей схеме (замысел миссии,
     * кандидаты из нормативов), разбираются собственными воротами. В журнал
     * вызов ложится так же: промпт, модель, токены, стоимость — иначе живой
     * канал стал бы дырой в учёте.
     */
    fun askRaw(
        kind: String,
        profileId: String,
        projectId: String,
        statement: String,
        author: String,
    ): RawAnswer {
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
            return RawAnswer(pk, null, null, e.message)
        }
        val pk = calls.record(
            projectId = projectId, kind = kind, transport = "direct", prompt = prompt,
            createdBy = author, profileId = p.id, profileVersion = p.version,
            model = answer.model, response = answer.text,
            tokensIn = answer.tokensIn, tokensOut = answer.tokensOut,
            costUsd = cost(answer.tokensIn, answer.tokensOut),
        )
        return RawAnswer(pk, answer.text, answer.model, null)
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
        val screened = screen(completeImportProvenance(raw, projectId), kind, p)
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
     * Б-01 реестра блокеров MVP-прохода: заготовленный ПАКЕТ предложений
     * вносится без вызова модели. Вид — из самого пакета: обёртка
     * {"kind": "...", "profile": "AP-NNNN"?, "items": [...]} несёт вид явно;
     * голый массив объектов вида выводит вид по префиксу id (только
     * порождающие виды — правящему виду обёртка обязательна). Разбор,
     * фильтр и журнал — общие с прочими транспортами (transport `package`,
     * модель «пакет»); профиль — из обёртки либо первый профиль проекта,
     * разрешающий вид.
     */
    fun packet(rawPacket: String, projectId: String, author: String): AiServiceRun {
        require(rawPacket.isNotBlank()) { "пакет пуст — вставьте JSON пакета" }
        val cleaned = rawPacket.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val root = try {
            mapper.readTree(cleaned)
        } catch (e: Exception) {
            throw IllegalArgumentException("пакет не разобран как JSON: ${e.message}")
        }
        val (kind, profileRef, items) = when {
            root.isArray -> Triple(kindOfBareArray(root), null, root)
            root.isObject && root.path("kind").isTextual -> {
                val arr = root.path("items").takeIf { it.isArray }
                    ?: root.path("objects").takeIf { it.isArray }
                    ?: throw IllegalArgumentException("в обёртке пакета нет массива items")
                Triple(
                    root.path("kind").asText(),
                    root.path("profile").asText("").ifBlank { null },
                    arr,
                )
            }
            else -> throw IllegalArgumentException(
                "формат пакета: массив объектов вида либо обёртка {\"kind\": \"...\", \"items\": [...]}",
            )
        }
        orbita.ai.PackageKinds.default().of(kind) // неизвестный вид — отказ с перечнем
        val profileId = profileRef ?: boundary.objects.listCurrent(projectId)
            .filter { it.type == "ai_profile" && it.status.name != "Cancelled" }
            .sortedBy { it.id }
            .firstOrNull { p -> p.doc.path("kinds").any { it.asText() == kind } }?.id
        ?: throw IllegalArgumentException(
            "нет профиля службы, разрешающего вид «$kind» — добавьте вид в профиль",
        )
        val p = profile(profileId, projectId)
        val prompt = compose(kind, profileId, projectId, "", enforceReady = false).second
        val itemsJson = mapper.writeValueAsString(items)
        val screened = screen(completeImportProvenance(itemsJson, projectId), kind, p)
        val pk = calls.record(
            projectId = projectId, kind = kind, transport = "package", prompt = prompt,
            createdBy = author, profileId = p.id, profileVersion = p.version,
            model = PACKET_MODEL, response = itemsJson,
            proposed = screened.path("proposed").asInt(),
            filtered = screened.path("rework").path("rejected").asInt(),
            noSource = screened.path("no_source").asInt(),
        )
        screened.put("call", pk)
        screened.put("kind", kind)
        screened.put("profile", profileId)
        screened.put("model", PACKET_MODEL)
        return AiServiceRun(pk, prompt, "package", PACKET_MODEL, screened)
    }

    /** Вид голого массива — по префиксу id элементов; двусмысленное — отказ. */
    private fun kindOfBareArray(arr: JsonNode): String {
        require(arr.size() > 0) { "пакет пуст — предложений в массиве нет" }
        val prefixes = arr.map { el ->
            val id = el.path("id").asText("")
            require(id.contains('-')) {
                "у элемента пакета нет id вида «ПРЕФИКС-NNNN» — вид не выводится; " +
                    "оберните пакет {\"kind\": \"...\", \"items\": [...]}"
            }
            id.substringBefore('-')
        }.toSet()
        require(prefixes.size == 1) {
            "в пакете смешаны виды (${prefixes.sorted()}) — вносите пакет одним видом " +
                "либо оберните {\"kind\": \"...\", \"items\": [...]}"
        }
        return BARE_PREFIX_KINDS[prefixes.single()]
            ?: throw IllegalArgumentException(
                "вид по префиксу «${prefixes.single()}» не выводится — оберните пакет " +
                    "{\"kind\": \"...\", \"items\": [...]} (правящим видам обёртка обязательна)",
            )
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
    /**
     * Происхождение импорта — ДОПОЛНЯЕТСЯ ФАКТАМИ, а не отбраковывается.
     *
     * Служба ссылается на документ проекта («Записка… (SD-0006)») и ставит
     * source=imported, но версию документа и дату получения знает не она, а
     * система: это её карточка. Схема требует оба поля — и весь ответ уходил
     * в брак целиком (находка живого прогона: предложено 7, показано 0).
     *
     * Здесь недостающие поля берутся из карточки названного документа. Это
     * не выдумывание значения: версия и дата — факты хранилища. Документ, на
     * который сослаться не удалось, не трогаем — такой ответ честно
     * отбраковывается.
     */
    private fun completeImportProvenance(raw: String, projectId: String): String {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val root = try {
            mapper.readTree(cleaned)
        } catch (e: Exception) {
            return raw
        }
        val docs = boundary.objects.listCurrent(projectId)
            .filter { it.type == "source_document" && it.status != orbita.mod.model.Lifecycle.Cancelled }
        if (docs.isEmpty()) return raw
        var touched = false

        fun complete(node: com.fasterxml.jackson.databind.JsonNode) {
            when {
                node.isArray -> node.forEach { complete(it) }
                node.isObject -> {
                    val obj = node as ObjectNode
                    val imp = obj.path("import")
                    if (obj.path("source").asText("") == "imported" && imp is ObjectNode) {
                        val dataset = imp.path("dataset").asText("")
                        val sd = docs.firstOrNull { dataset.contains(it.id) }
                            ?: docs.firstOrNull { d ->
                                val name = d.doc.path("name").asText("")
                                name.isNotBlank() && dataset.contains(name, ignoreCase = true)
                            }
                        if (sd != null) {
                            if (imp.path("dataset_version").asText("").isBlank()) {
                                imp.put("dataset_version", sd.version)
                                touched = true
                            }
                            if (imp.path("retrieved_at").asText("").isBlank()) {
                                imp.put("retrieved_at", sd.validFrom.toLocalDate().toString())
                                touched = true
                            }
                            if (imp.path("terms").asText("").isBlank()) {
                                imp.put(
                                    "terms",
                                    sd.doc.path("rights").asText("").ifBlank { "внутренний документ проекта" },
                                )
                                touched = true
                            }
                        }
                    }
                    obj.properties().forEach { (_, v) -> complete(v) }
                }
            }
        }

        complete(root)
        return if (touched) mapper.writeValueAsString(root) else raw
    }

    private fun screen(raw: String, kind: String, p: AiProfile): ObjectNode {
        val parsed = if (kind == ENRICHMENT_KIND) {
            // частичные правки: пакета вида у дозаполнения НЕТ (сборка пакета
            // упала бы «схему ответа выводить не из чего» — находка прогона);
            // разбор самодостаточен, проверка — против схемы дозаполнения
            boundary.parser.parseAgainstInline(raw, enrichmentResponseSchema())
        } else {
            val pkg = boundary.packages.build(kind, mapper.createObjectNode(), "служба")
            val schemaName = orbita.ai.PackageKinds.default().of(kind).targetSchema
            if (schemaName != null) {
                boundary.parser.parseAgainstSchema(raw, pkg, boundary.schemas, schemaName)
            } else {
                boundary.parser.parse(raw, pkg)
            }
        }
        val report = if (kind == ENRICHMENT_KIND) {
            // частичная правка — не объект вида: правила качества формулировок
            // к ней неприменимы (срезали бы «нет модального „должна"» у правки
            // без formulировки — находка прогона); действует только правило
            // основания по величинам
            val shown = mutableListOf<com.fasterxml.jackson.databind.JsonNode>()
            val rework = mutableListOf<orbita.ai.Screened>()
            parsed.accepted.forEach { item ->
                val issues = if (p.requireSource) orbita.req.sourceIssues(item) else emptyList()
                if (issues.isEmpty()) shown.add(item) else rework += orbita.ai.Screened(item, issues)
            }
            orbita.ai.ScreenReport(shown, rework)
        } else {
            boundary.screening.screen(
                parsed.accepted,
                ScreeningContext(requireSource = p.requireSource),
            )
        }
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

        /** Подпись источника в журнале для внесённого пакета (Б-01). */
        const val PACKET_MODEL = "пакет"

        /** Голый массив: префикс id → порождающий вид. Правящие виды
         * (quality, decomposition, verification, enrichment, section_editor,
         * checklist/annotation) сюда не входят намеренно — им обёртка. */
        val BARE_PREFIX_KINDS: Map<String, String> = mapOf(
            "MG" to "mission_to_goals",
            "ND" to "mission_to_needs",
            "SV" to "needs_to_services",
            "RQ" to "services_to_requirements",
            "RSK" to "risk_register",
            "SH" to "mission_to_stakeholders",
            "TR" to "mission_to_typical_risks",
            "DT" to "template_extraction",
        )
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
        // Сводка по профилям (эталон профилей: доля акцепта — главный
        // показатель здоровья профиля; аудит вызовов — в подвале карточки)
        val listed = calls.list(projectId)
        val byProfile = out.putObject("by_profile")
        listed.groupBy { it.profileId }.forEach { (pid, rows) ->
            val n = byProfile.putObject(pid)
            n.put("calls", rows.size)
            n.put("proposed", rows.sumOf { it.proposed })
            n.put("accepted", rows.sumOf { it.accepted })
            val prop = rows.sumOf { it.proposed }
            if (prop > 0) n.put("acceptance_pct", rows.sumOf { it.accepted } * 100 / prop)
            n.put("cost_usd", rows.sumOf { it.costUsd?.toDouble() ?: 0.0 })
            rows.maxByOrNull { it.at }?.let { last ->
                n.put("last_at", last.at.toString())
                n.put("last_model", last.model ?: "")
            }
        }
        val arr = out.putArray("calls")
        listed.forEach { c ->
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
