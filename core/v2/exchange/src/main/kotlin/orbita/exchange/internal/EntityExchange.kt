// Обмен над реестром (шип F): StrictDoc-канал, выгрузка знаний, пакет точки.
//
// Импорт .sdoc идёт тем же путём, что любой входной документ: файл ложится
// материалом, каждое требование — фактом с якорем блока канона, план —
// действиями «завести требование» с кодом UID. В модель канал сам не
// пишет: требование появится, когда человек примет действие в поле знаний.
package orbita.exchange.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.exchange.api.Exchange
import orbita.exchange.api.ExchangeUnavailable
import orbita.exchange.api.FingerprintCheck
import orbita.exchange.api.ForeignCandidates
import orbita.exchange.api.ForeignImport
import orbita.exchange.api.ImportedRequirement
import orbita.exchange.api.KnowledgeBundle
import orbita.exchange.api.KnowledgePart
import orbita.exchange.api.PointPackage
import orbita.exchange.api.ReqifParser
import orbita.exchange.api.SdocBaseline
import orbita.exchange.api.SdocBundle
import orbita.exchange.api.SdocImport
import orbita.exchange.api.StrictDocService
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry
import orbita.knowledge.api.Authority
import orbita.knowledge.api.Intake
import java.io.ByteArrayOutputStream
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal class EntityExchange(
    private val store: EntityStore,
    links: LinkRegistry,
    private val intake: Intake,
    private val mapper: ObjectMapper,
    private val strictDoc: StrictDocService?,
    private val baselineRoot: java.io.File,
    private val reqif: ReqifParser? = null,
) : Exchange {

    private val раскладка = SdocPayload(store, links, mapper)
    private val знания = KnowledgeExportV2(store, intake)

    private fun служба(): StrictDocService = strictDoc
        ?: throw ExchangeUnavailable("служба StrictDoc не настроена: задайте ORBITA_STRICTDOC_URL (ADR-049)")

    override fun sdocPayload(project: String): ObjectNode = раскладка.of(project)

    override fun sdoc(project: String): SdocBundle = служба().build(sdocPayload(project))

    override fun reqif(project: String): String = служба().reqif(sdocPayload(project))

    override fun baseline(project: String, author: String): SdocBaseline {
        val собрано = sdoc(project)
        val отметка = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val каталог = baselineRoot.resolve(project).resolve(отметка)
        каталог.mkdirs()
        каталог.resolve("orbita.sgra").writeText(собрано.sgra)
        каталог.resolve("$project.sdoc").writeText(собрано.sdoc)
        return SdocBaseline(каталог.path, "$project.sdoc", "orbita.sgra", отметка, author)
    }

    override fun importSdoc(project: String, sdoc: String, sgra: String?, author: String): SdocImport {
        require(sdoc.isNotBlank()) { "пустой .sdoc: импортировать нечего" }
        val разобрано = служба().parse(sdoc, sgra)
        val кандидаты = разобрано.path("requirements").map { кандидат(it) }
        if (кандидаты.isEmpty()) return SdocImport(emptyList(), null, null, "в документе нет ни одного [REQUIREMENT]")
        // Файл — входной документ проекта: у требования будет источник и якорь.
        val отметка = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        // Ранг доверия — справочный: импорт ЧУЖОГО файла свидетельство, а не
        // обязательный документ заказчика. Назван здесь явно, потому что
        // спрашивать некого: у канала обмена формы нет, а на проекте поля
        // знаний v2 ядро без ранга материал не примет.
        val материал = intake.putMaterial(
            project, "Импорт .sdoc $отметка", "sdoc", sdoc, author,
            authority = Authority.REFERENCE,
        )
        val якоря = intake.canon(project, материал)
        val ответ = mapper.createObjectNode()
        ответ.putArray("topics")
        val факты = ответ.putArray("facts")
        val действия = ответ.putArray("actions")
        кандидаты.forEachIndexed { i, к ->
            val якорь = якоря.firstOrNull { it.text.trim() == "UID: ${к.uid}" }?.anchor
                ?: якоря.firstOrNull { it.text.contains(к.uid) }?.anchor ?: якоря.firstOrNull()?.anchor ?: "s0#1"
            факты.addObject()
                .put("kind", "obligation").put("subject", "${к.uid}").put("predicate", к.statement ?: к.title ?: к.uid)
                .put("value", "требование").put("source_mark", "И").put("confidence", 1.0)
                .also { it.putObject("source").put("anchor", якорь) }
            val д = действия.addObject()
            д.put("kind", "create_entity").put("target_kind", "requirement").put("scene", "8")
            д.put("title", "завести требование ${к.uid}")
            д.put("preview", "появится требование ${к.uid}: «${(к.statement ?: "").take(80)}»")
            val содержимое = д.putObject("payload")
            содержимое.put("code", к.uid)
            к.title?.let { содержимое.put("title", it) }
            содержимое.put("statement", к.statement ?: "")
            содержимое.put("level", к.level ?: "project")
            к.category?.let { содержимое.put("category", it) }
            к.priority?.let { содержимое.put("priority", it) }
            к.rationale?.let { содержимое.put("rationale", it) }
            к.acceptanceCriteria?.let { содержимое.put("acceptance_criteria", it) }
            к.verificationMethod?.let { содержимое.put("verification_method", it) }
            if (к.mop.isNotEmpty()) {
                val м = содержимое.putObject("measure")
                к.mop["name"]?.let { м.put("key", it) }
                к.mop["operator"]?.let { м.put("op", it) }
                к.mop["value"]?.let { в -> в.toDoubleOrNull()?.let { м.put("value", it) } ?: м.put("value", в) }
                к.mop["unit"]?.let { м.put("unit", it) }
            }
            if (к.foreign.isNotEmpty()) {
                val чужие = содержимое.putObject("foreign_attributes")
                к.foreign.forEach { (ключ, значение) -> чужие.put(ключ, значение) }
            }
            д.putArray("facts").add(i)
        }
        val итог = intake.putFacts(project, материал, ответ.toString(), author, intent = "импорт .sdoc: требования кандидатами")
        return SdocImport(кандидаты, итог.task, материал, итог.note)
    }

    private fun кандидат(у: JsonNode): ImportedRequirement {
        fun т(поле: String): String? = у.path(поле).takeIf { it.isTextual }?.asText()?.ifBlank { null }
        val mop = у.path("mop").takeIf { it.isObject }?.properties()
            ?.associate { (к, в) -> к to в.asText("") }?.filterValues { it.isNotBlank() }.orEmpty()
        val чужие = у.path("foreign_attributes").takeIf { it.isObject }?.properties()
            ?.associate { (к, в) -> к to в.asText("") }.orEmpty()
        return ImportedRequirement(
            uid = т("id") ?: "RQ-?",
            title = т("title"), statement = т("statement"), category = т("category"), level = т("level"),
            priority = т("priority"), rationale = т("rationale"), acceptanceCriteria = т("acceptance_criteria"),
            verificationMethod = т("verification_method") ?: чужие["VERIFICATION_METHOD"],
            mop = mop, foreign = чужие.filterKeys { it != "VERIFICATION_METHOD" },
            relations = у.path("relations").map { it.path("ref").asText("") to it.path("role").asText("") },
        )
    }

    /**
     * Чужой файл обмена: служба разбирает библиотекой, кандидаты идут тем же
     * путём, что и .sdoc, — материал (читаемая развёртка объектов, по абзацу
     * на объект), факты с якорями, план «завести требование». Атрибуты чужого
     * профиля ложатся в foreign_attributes целиком.
     */
    override fun importForeign(project: String, xml: String, author: String): ForeignImport {
        require(xml.isNotBlank()) { "пустой файл: импортировать нечего" }
        val служба = reqif ?: throw ExchangeUnavailable("служба обмена не настроена: задайте ORBITA_EXCHANGE_URL (ADR-023)")
        val разобрано = служба.parse(xml)
        val кандидаты = ForeignCandidates.of(разобрано)
        if (кандидаты.isEmpty()) return ForeignImport(emptyList(), null, null, "в файле нет ни одного объекта")
        val заголовок = разобрано.path("title").asText("").ifBlank { "файл обмена" }
        val отметка = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        // Материал — развёртка объектов текстом: у каждого объекта свой абзац,
        // и факт кандидата помнит именно его.
        val текст = buildString {
            appendLine("Импорт файла обмена «$заголовок» $отметка")
            appendLine()
            кандидаты.forEach { к ->
                appendLine("${к.code} · ${к.type}")
                appendLine(к.statement.ifBlank { "(формулировки в файле нет)" })
                к.foreign.forEach { (имя, значение) -> appendLine("$имя: ${значение.replace('\n', ' ')}") }
                appendLine()
            }
        }
        // Тот же ранг и по той же причине: чужой файл обмена — справочный
        // источник, доверие к нему поднимает человек, а не канал.
        val материал = intake.putMaterial(
            project, "Импорт обмена «$заголовок» $отметка", "reqif", текст, author,
            authority = Authority.REFERENCE,
        )
        val якоря = intake.canon(project, материал)
        val ответ = mapper.createObjectNode()
        ответ.putArray("topics")
        val факты = ответ.putArray("facts")
        val действия = ответ.putArray("actions")
        кандидаты.forEachIndexed { i, к ->
            val якорь = якоря.firstOrNull { it.text.trim() == "${к.code} · ${к.type}" }?.anchor ?: якоря.firstOrNull()?.anchor ?: "s0#1"
            факты.addObject()
                .put("kind", "obligation").put("subject", к.code).put("predicate", к.statement.ifBlank { к.title ?: к.code })
                .put("value", "требование").put("source_mark", "В").put("confidence", 1.0)
                .also { it.putObject("source").put("anchor", якорь) }
            val д = действия.addObject()
            д.put("kind", "create_entity").put("target_kind", "requirement").put("scene", "8")
            д.put("title", "завести требование ${к.code} (${к.type})")
            д.put("preview", "появится требование ${к.code}: «${к.statement.take(80)}»; " +
                "код из «${к.used["code"] ?: "identifier"}», формулировка из «${к.used["statement"] ?: "—"}», чужих полей ${к.foreign.size}")
            val содержимое = д.putObject("payload")
            содержимое.put("code", к.code)
            к.title?.let { содержимое.put("title", it) }
            содержимое.put("statement", к.statement)
            содержимое.put("level", "project")
            содержимое.put("source_type", к.type)
            if (к.foreign.isNotEmpty()) {
                val чужие = содержимое.putObject("foreign_attributes")
                к.foreign.forEach { (имя, значение) -> чужие.put(имя, значение) }
            }
            д.putArray("facts").add(i)
        }
        val итог = intake.putFacts(project, материал, ответ.toString(), author, intent = "импорт файла обмена: требования кандидатами")
        return ForeignImport(кандидаты, итог.task, материал, итог.note)
    }

    override fun knowledgeParts(): List<KnowledgePart> = KnowledgeExportV2.PARTS

    override fun knowledge(project: String, parts: Set<String>?): KnowledgeBundle = знания.bundle(project, parts)

    override fun verify(project: String, said: String): FingerprintCheck {
        val нынешний = knowledge(project).fingerprint
        val названный = said.trim()
        return when {
            названный.isBlank() -> FingerprintCheck(
                нынешний, названный, false,
                "пакет не назвал отпечаток знаний — проверить, на какой выгрузке он собран, невозможно",
            )
            названный == нынешний -> FingerprintCheck(нынешний, названный, true, null)
            else -> FingerprintCheck(
                нынешний, названный, false,
                "знания устарели: пакет собран по выгрузке $названный, на стенде сейчас $нынешний — " +
                    "перепроверьте ответ против нынешних знаний",
            )
        }
    }

    override fun pointPackage(
        project: String,
        gate: String,
        gateTitle: String,
        point: JsonNode,
        documents: Map<String, ByteArray>,
    ): PointPackage {
        val пакетЗнаний = knowledge(project)
        val sdoc = runCatching { sdoc(project) }
        val записи = mutableListOf<String>()
        val буфер = ByteArrayOutputStream()
        ZipOutputStream(буфер).use { zip ->
            fun положить(имя: String, байты: ByteArray) {
                zip.putNextEntry(ZipEntry(имя))
                zip.write(байты)
                zip.closeEntry()
                записи += имя
            }
            положить("точка-$gate.json", mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(point))
            documents.toSortedMap().forEach { (код, pdf) -> положить("документы/$код.pdf", pdf) }
            sdoc.getOrNull()?.let {
                положить("требования/orbita.sgra", it.sgra.toByteArray())
                положить("требования/$project.sdoc", it.sdoc.toByteArray())
            }
            пакетЗнаний.files.toSortedMap().forEach { (имя, тело) -> положить("знания/$имя", тело.toByteArray()) }
            val манифест = buildString {
                appendLine("# Пакет точки «$gateTitle» — проект $project")
                appendLine()
                appendLine("- отпечаток знаний: `${пакетЗнаний.fingerprint}`")
                appendLine("- документов к точке: ${documents.size}" + (if (documents.isEmpty()) " — печатать нечего: ни один документ фазы не заведён" else ""))
                appendLine("- требования StrictDoc: " + (sdoc.exceptionOrNull()?.let { "не приложены — ${it.message}" } ?: "orbita.sgra + $project.sdoc"))
                appendLine()
                appendLine("## Состав")
                appendLine()
                записи.forEach { appendLine("- $it") }
                appendLine("- 00-МАНИФЕСТ.md")
            }
            положить("00-МАНИФЕСТ.md", манифест.toByteArray())
        }
        return PointPackage("пакет-$gate-$project.zip", буфер.toByteArray(), записи, пакетЗнаний.fingerprint)
    }
}
