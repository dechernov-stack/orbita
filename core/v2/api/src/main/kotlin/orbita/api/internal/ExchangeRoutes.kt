// Маршруты обмена (шип F): StrictDoc-канал, выгрузка знаний с отпечатком,
// пакет точки одним архивом. Домена здесь нет: раскладку, отпечаток и
// архив собирает модуль обмена; печать документов — модуль документов;
// точку — движок процесса.
package orbita.api.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.documents.api.Documents
import orbita.exchange.api.Exchange
import orbita.exchange.api.ExchangeUnavailable
import orbita.kernel.api.Area
import orbita.kernel.api.EntityStore
import orbita.process.api.ProcessEngine

class ExchangeRoutes(
    private val store: EntityStore,
    private val exchange: Exchange,
    private val engine: ProcessEngine,
    private val documents: Documents,
    private val mapper: ObjectMapper,
) {

    fun handle(method: String, path: String, query: Map<String, String>, body: String?): V2Router.Ответ? = try {
        when {
            method == "GET" && path == "/v2/export/sdoc" -> sdoc(требуется(query, "project"), query["grammar"] == "1")
            method == "GET" && path == "/v2/export/sdoc/reqif" -> reqif(требуется(query, "project"))
            method == "POST" && path == "/v2/export/sdoc/baseline" -> базирование(требуется(query, "project"), разобрать(body))
            method == "POST" && path == "/v2/import/sdoc" -> импорт(требуется(query, "project"), разобрать(body))
            method == "POST" && path == "/v2/import/reqif" -> импортЧужого(требуется(query, "project"), разобрать(body))
            method == "GET" && path == "/v2/export/knowledge" -> знания(требуется(query, "project"), части(query))
            method == "GET" && path == "/v2/export/knowledge.zip" -> знанияАрхивом(требуется(query, "project"), части(query))
            method == "POST" && path == "/v2/export/knowledge/verify" -> сверка(требуется(query, "project"), разобрать(body))
            // Ключ точки — как в шаблоне фазы (MCR, KDP-A, internal_review).
            method == "GET" && path.matches(Regex("/v2/points/[A-Za-z0-9_-]+/package\\.zip")) ->
                пакетТочки(требуется(query, "project"), path.removePrefix("/v2/points/").removeSuffix("/package.zip"))
            else -> null
        }
    } catch (e: ExchangeUnavailable) {
        V2Router.Ответ(503, mapper.createObjectNode().put("error", e.message).put("adr", "ADR-049"))
    }

    private fun sdoc(project: String, grammar: Boolean): V2Router.Ответ {
        val собрано = exchange.sdoc(project)
        val текст = if (grammar) собрано.sgra else собрано.sdoc
        return V2Router.Ответ(
            200, mapper.createObjectNode(),
            binary = текст.toByteArray(), contentType = "text/plain; charset=utf-8",
            fileName = if (grammar) "orbita.sgra" else "$project.sdoc",
        )
    }

    private fun reqif(project: String): V2Router.Ответ = V2Router.Ответ(
        200, mapper.createObjectNode(),
        binary = exchange.reqif(project).toByteArray(), contentType = "application/xml; charset=utf-8",
        fileName = "$project.strictdoc.reqif",
    )

    private fun базирование(project: String, тело: JsonNode): V2Router.Ответ {
        val снимок = exchange.baseline(project, тело.path("author").asText("инженер"))
        return V2Router.Ответ(
            200,
            mapper.createObjectNode().put("dir", снимок.dir).put("sdoc", снимок.sdoc).put("sgra", снимок.sgra)
                .put("at", снимок.at).put("author", снимок.author),
        )
    }

    private fun импорт(project: String, тело: JsonNode): V2Router.Ответ {
        val итог = exchange.importSdoc(
            project, тело.path("sdoc").asText(""), тело.path("sgra").asText("").ifBlank { null },
            тело.path("author").asText("инженер"),
        )
        val узел = mapper.createObjectNode()
        узел.put("count", итог.candidates.size)
        узел.put("task", итог.task)
        узел.put("material", итог.material)
        узел.put("note", итог.note)
        val кандидаты = узел.putArray("candidates")
        итог.candidates.forEach { к ->
            val у = кандидаты.addObject().put("uid", к.uid).put("title", к.title).put("statement", к.statement)
                .put("category", к.category).put("level", к.level).put("priority", к.priority)
                .put("rationale", к.rationale).put("acceptance_criteria", к.acceptanceCriteria)
                .put("verification_method", к.verificationMethod)
            val mop = у.putObject("mop")
            к.mop.forEach { (ключ, значение) -> mop.put(ключ, значение) }
            val чужие = у.putObject("foreign_attributes")
            к.foreign.forEach { (ключ, значение) -> чужие.put(ключ, значение) }
            val связи = у.putArray("relations")
            к.relations.forEach { (ссылка, роль) -> связи.addObject().put("ref", ссылка).put("role", роль) }
        }
        return V2Router.Ответ(200, узел)
    }

    /** Чужой файл обмена: кандидаты со всеми атрибутами, план — в поле знаний; в модель канал не пишет. */
    private fun импортЧужого(project: String, тело: JsonNode): V2Router.Ответ {
        val итог = exchange.importForeign(project, тело.path("xml").asText(""), тело.path("author").asText("инженер"))
        val узел = mapper.createObjectNode()
        узел.put("count", итог.candidates.size)
        узел.put("task", итог.task)
        узел.put("material", итог.material)
        узел.put("note", итог.note)
        val кандидаты = узел.putArray("candidates")
        итог.candidates.forEach { к ->
            val у = кандидаты.addObject().put("identifier", к.identifier).put("code", к.code).put("statement", к.statement)
                .put("title", к.title).put("type", к.type)
            val used = у.putObject("used")
            к.used.forEach { (поле, имя) -> used.put(поле, имя) }
            val чужие = у.putObject("foreign_attributes")
            к.foreign.forEach { (имя, значение) -> чужие.put(имя, значение) }
            val связи = у.putArray("relations")
            к.relations.forEach { (ссылка, роль) -> связи.addObject().put("ref", ссылка).put("role", роль) }
        }
        return V2Router.Ответ(200, узел)
    }

    private fun части(query: Map<String, String>): Set<String>? =
        query["parts"]?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.toSet()

    private fun знания(project: String, части: Set<String>?): V2Router.Ответ {
        val пакет = exchange.knowledge(project, части)
        val узел = mapper.createObjectNode().put("fingerprint", пакет.fingerprint)
        val массив = узел.putArray("parts")
        exchange.knowledgeParts().forEach { ч ->
            val размер = пакет.files[ч.file]?.toByteArray()?.size ?: 0
            массив.addObject().put("key", ч.key).put("file", ч.file).put("title", ч.title)
                .put("chosen", ч.file in пакет.files).put("size", размер)
                // величину для показа считает сервер: в клиенте расчётов нет
                .put("size_kb", if (размер == 0) 0 else maxOf(1, Math.round(размер / 1024.0).toInt()))
        }
        return V2Router.Ответ(200, узел)
    }

    private fun знанияАрхивом(project: String, части: Set<String>?): V2Router.Ответ {
        val пакет = exchange.knowledge(project, части)
        val буфер = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(буфер).use { zip ->
            пакет.files.toSortedMap().forEach { (имя, тело) ->
                zip.putNextEntry(java.util.zip.ZipEntry(имя))
                zip.write(тело.toByteArray())
                zip.closeEntry()
            }
        }
        return V2Router.Ответ(
            200, mapper.createObjectNode(),
            binary = буфер.toByteArray(), contentType = "application/zip",
            fileName = "знания-$project-${пакет.fingerprint}.zip",
        )
    }

    private fun сверка(project: String, тело: JsonNode): V2Router.Ответ {
        val итог = exchange.verify(project, тело.path("knowledge_fingerprint").asText(""))
        return V2Router.Ответ(
            200,
            mapper.createObjectNode().put("ok", итог.ok).put("current", итог.current).put("said", итог.said)
                .put("warning", итог.warning),
        )
    }

    /**
     * Пакет точки: JSON точки как её видит движок, печать каждого заведённого
     * документа фазы к этой ступени, .sdoc с грамматикой, пакет знаний.
     */
    private fun пакетТочки(project: String, ключ: String): V2Router.Ответ {
        val фаза = engine.view(project)
        val точка = фаза.gates.firstOrNull { it.key == ключ }
            ?: throw NoSuchElementException("точки «$ключ» нет в фазе проекта $project: есть ${фаза.gates.joinToString { it.key }}")
        val имя = store.byCode(Area.Project(project), project)?.doc?.path("name")?.asText(project) ?: project
        val печать = documents.list(project).associate { д -> д.code to documents.print(project, д.code, имя) }
        val пакет = exchange.pointPackage(project, ключ, точка.title, PhaseJson.точка(точка, mapper), печать)
        return V2Router.Ответ(
            200, mapper.createObjectNode(),
            binary = пакет.bytes, contentType = "application/zip", fileName = пакет.fileName,
        )
    }

    private fun требуется(query: Map<String, String>, имя: String): String =
        query[имя] ?: throw IllegalArgumentException("нужен параметр «$имя»")

    private fun разобрать(body: String?): JsonNode =
        if (body.isNullOrBlank()) mapper.createObjectNode() else mapper.readTree(body)
}
