// Библиотека → «Результаты» (ШАБЛОН-SEMP, механика п. 8; «три пакета», шип
// 2.3): третий отдел рядом с Материалами и Полками — вход → работа →
// результат. Карточка выпуска: документ · версия · снимок печати · авторство.
//
// Авторы текста — из ИСТОРИИ правок разделов (все версии section_text этого
// документа до момента выпуска), плюс выпустивший и дата. Служба автором не
// бывает никогда (закон ServiceAuthors): её след — происхождение черновика, а
// в авторах стоит человек, который текст принял. Существующие выпуски
// попадают сюда без миграции: карточка — проекция объекта document_issue.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.mod.model.Lifecycle
import orbita.mod.store.ObjectStore

object Results {

    private val mapper = ObjectMapper()

    fun toJson(boundary: Boundary, projectId: String): ObjectNode {
        val out = mapper.createObjectNode()
        val cards = out.putArray("cards")
        val templates = boundary.objects.listCurrent(ObjectStore.LIBRARY_PROJECT)
            .filter { it.type == "document_template" && it.status != Lifecycle.Cancelled }
            .associate { it.doc.path("code").asText() to orbita.out.TemplateData.of(it.doc) }
        val issues = boundary.objects.listCurrent(projectId)
            .filter { it.type == "document_issue" && it.status != Lifecycle.Cancelled }
            .sortedByDescending { it.doc.path("issued_at").asText("") }
        if (issues.isEmpty()) {
            out.put("empty_why", "выпусков документов в проекте ещё нет")
            return out
        }
        // текущие слепки — по одному рендеру на шаблон, не на выпуск
        val currentDigest = HashMap<String, String>()
        val texts = boundary.objects.historyByType("section_text", projectId)
        issues.forEach { di ->
            val code = di.doc.path("template").asText("")
            val template = templates[code]
            val digest = template?.let { t ->
                currentDigest.getOrPut(code) {
                    runCatching {
                        orbita.out.DocumentGenerator(mapper)
                            .render(DocumentModel.model(boundary, projectId), t, DocumentModel.sectionTexts(boundary, code, projectId))
                            .digest
                    }.getOrDefault("")
                }
            } ?: ""
            val issuedAt = di.doc.path("issued_at").asText("")
            // авторы текста: версии разделов этого документа, принятые до выпуска,
            // без служебных учёток — служба автором не бывает
            val byAuthor = LinkedHashMap<String, MutableSet<Int>>()
            texts.filter { it.doc.path("template_code").asText() == code }
                .filter { issuedAt.isBlank() || it.validFrom.toString() <= issuedAt || it.validFrom.toLocalDate().toString() <= issuedAt.take(10) }
                .filterNot { orbita.req.ServiceAuthors.isService(it.createdBy) }
                .sortedBy { it.validFrom }
                .forEach { st ->
                    byAuthor.getOrPut(boundary.humanAuthor(st.createdBy)) { sortedSetOf() }.add(st.doc.path("section").asInt())
                }
            val card = cards.addObject()
            card.put("issue", di.id)
            card.put("template", code)
            card.put("title", template?.title ?: code)
            card.put("version", di.version)
            card.put("issued_at", issuedAt)
            card.put("issued_by", boundary.humanAuthor(di.createdBy))
            card.put("digest", di.doc.path("digest").asText(""))
            card.put("stale", digest.isNotBlank() && di.doc.path("digest").asText("") != digest)
            card.put("gaps", di.doc.path("gaps").asInt(0))
            card.put(
                "sections_with_text",
                di.doc.path("snapshot").path("sections").count { it.path("text").asText("").isNotBlank() },
            )
            val authors = card.putArray("authors")
            byAuthor.forEach { (name, sections) ->
                val a = authors.addObject().put("name", name)
                val arr = a.putArray("sections")
                sections.forEach { arr.add(it) }
            }
        }
        return out
    }
}
