// Документ как источник — целиком (шип 4 §3): досье, основания, откат, приём
// режимом, каталог с очередью и сводкой партии, библиотека проекта, карта
// пробелов устава. Тонкие маршруты: всё считается в модуле знаний.
package orbita.api.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.ai.api.AtomizeJobs
import orbita.kernel.api.Area
import orbita.kernel.api.EntityStore
import orbita.knowledge.api.BasisView
import orbita.knowledge.api.DocumentAccept
import orbita.knowledge.api.Dossier
import orbita.knowledge.api.GapMap
import orbita.knowledge.api.Intake
import orbita.knowledge.api.RollbackReport
import orbita.knowledge.api.TakeReport

class DossierRoutes(
    private val store: EntityStore,
    private val intake: Intake,
    private val mapper: ObjectMapper = ObjectMapper(),
    /** Очередь фонового разбора (ADR-069) — ею идёт партия каталога; null — партия только кладёт документы. */
    private val jobs: AtomizeJobs? = null,
    /** Загрузка одного материала — тем же маршрутом, что и с формы (текст · ссылка · файл base64). */
    private val upload: ((project: String, body: String) -> V2Router.Ответ)? = null,
) {

    fun handle(method: String, path: String, query: Map<String, String>, body: String?): V2Router.Ответ? {
        ДОСЬЕ.matchEntire(path)?.let { if (method == "GET") return досье(требуется(query, "project"), it.groupValues[1]) }
        ПРОБЕЛЫ.matchEntire(path)?.let { if (method == "GET") return пробелы(требуется(query, "project"), it.groupValues[1]) }
        ОТКАТ.matchEntire(path)?.let { if (method == "POST") return откат(требуется(query, "project"), it.groupValues[1], разобрать(body)) }
        ПРИЁМ.matchEntire(path)?.let { if (method == "POST") return приём(требуется(query, "project"), it.groupValues[1], разобрать(body)) }
        ПОЛЕЗНОСТЬ.matchEntire(path)?.let { if (method == "POST") return полезность(требуется(query, "project"), it.groupValues[1], разобрать(body)) }
        ОСНОВАНИЯ.matchEntire(path)?.let { if (method == "GET") return основания(требуется(query, "project"), it.groupValues[1]) }
        РОЛЬ.matchEntire(path)?.let { if (method == "POST") return роль(требуется(query, "project"), it.groupValues[1], разобрать(body)) }
        return when {
            method == "POST" && path == "/v2/materials/take" -> взять(требуется(query, "project"), разобрать(body))
            method == "POST" && path == "/v2/materials/batch" -> партия(требуется(query, "project"), body)
            method == "GET" && path == "/v2/materials/batch" -> сводка(требуется(query, "project"), query["codes"].orEmpty())
            else -> null
        }
    }

    private fun досье(project: String, material: String): V2Router.Ответ =
        V2Router.Ответ(200, видДосье(intake.dossier(project, material)))

    private fun пробелы(project: String, material: String): V2Router.Ответ =
        V2Router.Ответ(200, видПробелов(intake.charterGaps(project, material)))

    private fun откат(project: String, material: String, тело: JsonNode): V2Router.Ответ =
        V2Router.Ответ(200, видОтката(intake.rollback(project, material, автор(тело), тело.path("reason").asText(""))))

    private fun приём(project: String, material: String, тело: JsonNode): V2Router.Ответ = V2Router.Ответ(
        201,
        видПриёма(
            intake.acceptDocument(
                project, material, тело.path("mode").asText(""), автор(тело),
                sections = тело.path("sections").map { it.asText() }.filter { it.isNotBlank() },
                chosen = тело.path("chosen").map { it.asInt(-1) }.filter { it >= 0 },
                reason = тело.path("reason").asText(""),
            ),
        ),
    )

    private fun полезность(project: String, material: String, тело: JsonNode): V2Router.Ответ =
        V2Router.Ответ(200, видДосье(intake.usefulnessNote(project, material, тело.path("note").asText(""), автор(тело))))

    private fun основания(project: String, entity: String): V2Router.Ответ =
        V2Router.Ответ(200, видОснований(intake.basis(project, entity)))

    /** Роль лежащего документа — тем же правилом, что при загрузке (устав один); у устава — карта пробелов тем же ответом. */
    private fun роль(project: String, material: String, тело: JsonNode): V2Router.Ответ {
        val роль = intake.setRole(project, material, тело.path("role").asText(""), автор(тело))
        val ответ = mapper.createObjectNode().put("code", material).put("role", роль)
        if (роль == "charter") ответ.set<JsonNode>("gaps", видПробелов(intake.charterGaps(project, material)))
        return V2Router.Ответ(200, ответ)
    }

    private fun взять(project: String, тело: JsonNode): V2Router.Ответ {
        val откуда = тело.path("from_project").asText("").trim()
        val материал = тело.path("material").asText("").trim()
        require(откуда.isNotBlank() && материал.isNotBlank()) { "нужны from_project и material: откуда и какой документ берём" }
        return V2Router.Ответ(201, видВзятия(intake.takeMaterial(откуда, материал, project, автор(тело))))
    }

    /**
     * Партия каталога (РЕШЕНИЕ §6): каждому файлу — своя карточка и роль,
     * разбор — очередью фоновых заданий; ответ — коды и задания, сводку даёт
     * GET по кодам. Один непрочитавшийся файл не валит партию: он называется.
     */
    private fun партия(project: String, body: String?): V2Router.Ответ {
        val загрузка = upload ?: throw IllegalStateException("загрузка партии на этом стенде не подключена")
        val тело = разобрать(body)
        val элементы = тело.path("items")
        require(элементы.isArray && элементы.size() > 0) { "партия пуста: приложите хотя бы один документ" }
        val автор = автор(тело)
        val ответ = mapper.createObjectNode()
        val коды = ответ.putArray("codes")
        val строки = ответ.putArray("items")
        val отказы = ответ.putArray("refusals")
        элементы.forEach { э ->
            val карточка = (э.deepCopy() as ObjectNode).put("author", автор)
            val имя = э.path("name").asText("").ifBlank { э.path("filename").asText("документ") }
            val положен = try {
                загрузка(project, mapper.writeValueAsString(карточка))
            } catch (e: IllegalArgumentException) {
                отказы.add("$имя: ${e.message}")
                return@forEach
            }
            if (положен.code != 201) { отказы.add("$имя: ${положен.body.path("error").asText(положен.body.toString())}"); return@forEach }
            val код = положен.body.path("code").asText()
            коды.add(код)
            val строка = строки.addObject().put("code", код).put("name", имя)
            val намерение = э.path("intent").asText("").ifBlank { "разбери по сущностям" }
            jobs?.let { очередь ->
                val з = runCatching { очередь.start(project, код, намерение, автор) }
                    .onFailure { строка.put("job_error", it.message ?: "разбор не запустился") }
                    .getOrNull()
                з?.let { строка.put("job", it.id).put("job_status", it.status) }
            } ?: строка.put("job_status", "не запущен: очередь разбора на стенде не подключена")
        }
        ответ.put("documents", коды.size())
        ответ.put("note", "партия: документов ${коды.size()}" +
            (if (отказы.isEmpty) "" else ", не прочитано ${отказы.size()}") +
            (if (jobs == null) "; разбор запустите по каждому" else "; разбор идёт очередью — сводка по кодам партии"))
        return V2Router.Ответ(201, ответ)
    }

    /** Сводка партии по кодам: документов · фактов · противоречий · дублей источников · состояние заданий. */
    private fun сводка(project: String, codes: String): V2Router.Ответ {
        val коды = codes.split(',').map { it.trim() }.filter { it.isNotBlank() }
        require(коды.isNotEmpty()) { "нужны codes: коды документов партии через запятую" }
        val область = Area.Project(project)
        val задания = jobs?.list(project).orEmpty().groupBy { it.material }
        val отпечатки = store.list(область, "material").filter { it.status != "cancelled" }
            .associate { it.code to runCatching { intake.fingerprint(project, it.code) }.getOrDefault("") }
        val ответ = mapper.createObjectNode()
        val строки = ответ.putArray("items")
        var фактов = 0
        var противоречий = 0
        var дублей = 0
        var готово = 0
        var идёт = 0
        var неУдалось = 0
        коды.forEach { код ->
            val карточка = store.byCode(область, код) ?: run { строки.addObject().put("code", код).put("error", "документа нет"); return@forEach }
            val досье = intake.dossier(project, код)
            val задание = задания[код]?.maxByOrNull { it.startedAt }
            val двойник = отпечатки.entries.firstOrNull { (другой, отпечаток) -> другой != код && отпечаток.isNotBlank() && отпечаток == отпечатки[код] }?.key
            val живых = досье.factsByKind.values.sum()
            фактов += живых
            противоречий += досье.contradicts
            if (двойник != null) дублей += 1
            when (задание?.status) { "done" -> готово += 1; "running" -> идёт += 1; "failed" -> неУдалось += 1 }
            строки.addObject()
                .put("code", код)
                .put("name", карточка.doc.path("name").asText(код))
                .put("role", карточка.doc.path("role").asText("").ifBlank { null })
                .put("rank", карточка.doc.path("rank").asText("").ifBlank { null })
                .put("facts", живых)
                .put("contradicts", досье.contradicts)
                .put("duplicate_of", двойник)
                .put("job", задание?.id)
                .put("job_status", задание?.status ?: (if (живых > 0) "done" else "не запускался"))
                .put("job_error", задание?.error)
                .put("summary", досье.summary)
        }
        ответ.put("documents", коды.size).put("facts", фактов).put("contradicts", противоречий).put("duplicate_sources", дублей)
        ответ.put("done", готово).put("running", идёт).put("failed", неУдалось)
        ответ.put(
            "note",
            "${коды.size} документов, $фактов фактов, $противоречий противоречий, $дублей дублей источников" +
                (if (идёт > 0) "; разбор идёт: $идёт" else "") + (if (неУдалось > 0) "; не удалось: $неУдалось" else ""),
        )
        return V2Router.Ответ(200, ответ)
    }

    // --- виды ---------------------------------------------------------------

    private fun видДосье(д: Dossier): ObjectNode {
        val узел = mapper.createObjectNode()
            .put("material", д.material)
            .put("name", д.name)
            .put("role", д.role)
            .put("rank", д.rank)
            .put("summary", д.summary)
            .put("version", д.version)
            .put("chars", д.chars)
            .put("blocks", д.blocks)
            .put("supersedes", д.supersedes)
            .put("superseded_by", д.supersededBy)
            .put("created_at", д.createdAt)
            .put("accept_mode", д.acceptMode)
            .put("unique", д.unique)
            .put("confirms", д.confirms)
            .put("contradicts", д.contradicts)
            .put("usefulness", д.usefulness)
            .put("usefulness_note", д.usefulnessNote)
        val прогоны = узел.putArray("runs")
        д.runs.forEach { п ->
            прогоны.addObject().put("code", п.code).put("at", п.at).put("prompt_version", п.promptVersion)
                .put("facts", п.facts).put("status", п.status).put("rolled_back_by", п.rolledBackBy)
        }
        узел.putObject("facts_by_kind").also { в -> д.factsByKind.forEach { (к, n) -> в.put(к, n) } }
        узел.putObject("by_disposition").also { в -> д.byDisposition.forEach { (к, n) -> в.put(к, n) } }
        val сущности = узел.putArray("entities")
        д.entities.forEach { с ->
            сущности.addObject().put("code", с.code).put("kind", с.kind).put("title", с.title).put("only_basis", с.onlyBasis).put("scene", с.scene)
        }
        узел.put("entities_only_basis", д.entities.count { it.onlyBasis })
        return узел
    }

    private fun видОснований(в: BasisView): ObjectNode {
        val узел = mapper.createObjectNode().put("entity", в.entity).put("kind", в.kind).put("title", в.title)
        val факты = узел.putArray("facts")
        в.facts.forEach { ф ->
            факты.addObject()
                .put("code", ф.code)
                .put("kind", ф.kind)
                .put("subject", ф.subject)
                .put("predicate", ф.predicate)
                .put("value", ф.value)
                .put("quote", ф.quote)
                .put("anchor", ф.anchor)
                .put("material", ф.material)
                .put("material_name", ф.materialName)
                .put("role", ф.role)
                .put("rank", ф.rank)
                .put("mark", ф.mark)
                .put("disposition", ф.disposition)
                .put("withdrawn", ф.withdrawn)
        }
        узел.putArray("notes").also { а -> в.notes.forEach { а.add(it) } }
        return узел
    }

    private fun видОтката(о: RollbackReport): ObjectNode = mapper.createObjectNode()
        .put("material", о.material)
        .put("facts", о.facts)
        .put("runs", о.runs)
        .put("note", о.note)
        .also { у ->
            у.putArray("entities_cancelled").also { а -> о.entitiesCancelled.forEach { а.add(it) } }
            у.putArray("entities_kept").also { а -> о.entitiesKept.forEach { а.add(it) } }
        }

    private fun видПриёма(п: DocumentAccept): ObjectNode = mapper.createObjectNode()
        .put("material", п.material)
        .put("mode", п.mode)
        .put("facts", п.facts)
        .put("note", п.note)
        .also { у ->
            у.putArray("created").also { а -> п.created.forEach { а.add(it) } }
            у.putArray("notes").also { а -> п.notes.forEach { а.add(it) } }
        }

    private fun видВзятия(в: TakeReport): ObjectNode = mapper.createObjectNode()
        .put("material", в.material)
        .put("from", в.from)
        .put("facts", в.facts)
        .put("links", в.links)
        .put("topics", в.topics)
        .put("model_calls", в.modelCalls)
        .put("note", в.note)

    private fun видПробелов(к: GapMap): ObjectNode {
        val узел = mapper.createObjectNode().put("material", к.material).put("present", к.present).put("note", к.note)
        val пункты = узел.putArray("items")
        к.items.forEach { п ->
            пункты.addObject().put("n", п.n).put("what", п.what).put("present", п.present).put("anchor", п.anchor)
                .put("blocks", п.blocks).put("scene", п.scene).put("measure", п.measure)
        }
        узел.putArray("missing").also { а -> к.missing.forEach { а.add(it) } }
        узел.putArray("blocked_scenes").also { а -> к.blockedScenes.forEach { а.add(it) } }
        return узел
    }

    private fun разобрать(тело: String?): JsonNode =
        if (тело.isNullOrBlank()) mapper.createObjectNode() else mapper.readTree(тело)

    private fun автор(тело: JsonNode): String = тело.path("author").asText("").ifBlank { "инженер" }

    private fun требуется(query: Map<String, String>, имя: String): String =
        query[имя]?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("нужен параметр $имя")

    private companion object {
        val ДОСЬЕ: Regex = Regex("/v2/materials/(SD-[0-9]+)/dossier")
        val ПРОБЕЛЫ: Regex = Regex("/v2/materials/(SD-[0-9]+)/gaps")
        val ОТКАТ: Regex = Regex("/v2/materials/(SD-[0-9]+)/rollback")
        val ПРИЁМ: Regex = Regex("/v2/materials/(SD-[0-9]+)/accept")
        val ПОЛЕЗНОСТЬ: Regex = Regex("/v2/materials/(SD-[0-9]+)/usefulness")
        val ОСНОВАНИЯ: Regex = Regex("/v2/entities/([A-Za-z0-9._-]+)/basis")
        val РОЛЬ: Regex = Regex("/v2/materials/(SD-[0-9]+)/role")
    }
}
