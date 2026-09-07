// Документ проекта — снимок сцен (ЗАДАНИЕ-ДОКУМЕНТЫ-СЕЙЧАС).
//
// Документ НЕ хранит содержание: он хранит СТРУКТУРУ (шаблон полки) и
// тезисы человека. Всё остальное — запросы к выходам сцен, и они считаются
// при каждом чтении. Отсюда главное свойство: выполненная сцена сама
// заполняет свой раздел, а удаление её выходов сразу видно пустой строкой,
// а не устаревшим текстом, который кто-то забыл переписать.
package orbita.documents.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.documents.api.ColumnView
import orbita.documents.api.DocumentHint
import orbita.documents.api.DocumentView
import orbita.documents.api.Documents
import orbita.documents.api.ElementKind
import orbita.documents.api.ElementView
import orbita.documents.api.PrintSection
import orbita.documents.api.PrintView
import orbita.documents.api.SectionView
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry
import orbita.kernel.api.Provenance

class EntityDocuments(
    private val store: EntityStore,
    private val links: LinkRegistry,
    /** Шаблон документа читается С ПОЛКИ: содержания в коде нет. */
    private val template: (String) -> JsonNode?,
    private val mapper: ObjectMapper = ObjectMapper(),
) : Documents {

    private val запросы = Queries(store, links)

    override fun ensure(project: String, templateCode: String, author: String): DocumentView {
        val шаблон = шаблонИли(templateCode)
        val существующий = store.list(Area.Project(project), "document")
            .firstOrNull { it.doc.path("template").asText() == templateCode }
        if (существующий == null) {
            val doc = mapper.createObjectNode()
            doc.put("template", templateCode)
            doc.put("title", шаблон.path("title").asText(templateCode))
            doc.put("standard", шаблон.path("standard").asText(""))
            doc.put("owner", author)
            store.create(
                code = templateCode,
                kind = "document",
                area = Area.Project(project),
                bornIn = null,
                doc = doc,
                provenance = Provenance(Channel.SHELF, author, source = templateCode),
            )
        }
        return document(project, templateCode)
    }

    override fun list(project: String): List<DocumentView> =
        store.list(Area.Project(project), "document")
            .map { document(project, it.doc.path("template").asText(it.code)) }

    /**
     * Лестница ступеней: раздел, которого ступень ещё не ждёт, документ
     * не держит. Порядок — из шаблона фазы Pre-Phase A / Phase A.
     */
    private val лестница = listOf("MCR", "SRR", "SDR", "PDR", "CDR")

    /** Ждёт ли ступень `gate` раздел, обязанный быть полным к `expects`. */
    private fun ждётСейчас(expects: String?, gate: String): Boolean {
        if (expects.isNullOrBlank()) return true
        val когда = лестница.indexOf(expects.uppercase())
        val сейчас = лестница.indexOf(gate.uppercase())
        // Ступень вне лестницы — спрашиваем: молчать о разделе опаснее,
        // чем спросить лишний раз.
        if (когда < 0 || сейчас < 0) return true
        return когда <= сейчас
    }

    override fun document(project: String, code: String, gate: String): DocumentView {
        val шаблон = шаблонИли(code)
        val тезисы = тезисыПроекта(project, code)
        val подписи = шаблон.path("labels").properties()
            .filterNot { it.key.startsWith("_") }
            .associate { (к, в) -> к to в.asText() }
        val разделы = шаблон.path("sections").map { раздел ->
            val ждёт = раздел.path("expects").asText("").ifBlank { null }
            собратьРаздел(project, раздел, тезисы[раздел.path("no").asText()].orEmpty(), подписи)
                .copy(expectedBy = ждёт, dueNow = ждётСейчас(ждёт, gate))
        }
        val ожидаемые = разделы.filter { it.dueNow }
        return DocumentView(
            code = code,
            title = шаблон.path("title").asText(code),
            template = code,
            standard = шаблон.path("standard").asText(""),
            sections = разделы,
            // Полнота считается по ОЖИДАЕМЫМ разделам: делить полные на все
            // значило бы вечно показывать недоделанным документ, который к
            // своей ступени полон.
            complete = ожидаемые.count { it.complete },
            total = ожидаемые.size,
            gate = gate.uppercase(),
            notDueYet = разделы.size - ожидаемые.size,
        )
    }

    /**
     * Раздел: запросы шаблона считаются, тезисы человека добавляются следом.
     * Незаполненный запрос называет сцены, которых ждёт, — иначе пустой
     * раздел неотличим от забытого.
     */
    private fun собратьРаздел(
        project: String,
        раздел: JsonNode,
        тезисы: List<Entity>,
        подписи: Map<String, String> = emptyMap(),
    ): SectionView {
        val сцены = раздел.path("scenes").map { it.asText() }
        val элементы = раздел.path("elements").map { элемент ->
            val строки = запросы.rows(project, элемент, подписи)
            val минимум = элемент.path("min_rows").asInt(1)
            val хватает = строки.size >= минимум
            ElementView(
                code = элемент.path("code").asText(""),
                kind = ElementKind.QUERY,
                title = элемент.path("title").asText(""),
                columns = элемент.path("columns").map {
                    ColumnView(it.path("title").asText(""), it.path("field").asText(""))
                },
                rows = строки,
                text = null,
                supports = emptyList(),
                minRows = минимум,
                satisfied = хватает,
                waitingScenes = if (хватает) emptyList() else сцены,
                notes = emptyList(),
            )
        }
        val опоры = элементы.flatMap { э -> э.rows.flatten() }
        val тезисыВида = тезисы.map { т ->
            val текст = т.doc.path("text").asText("")
            val свои = т.doc.path("supports").map { it.asText() }
            val безОпоры = NumberGuard.unsupported(текст, если(опоры, свои, элементы))
            ElementView(
                code = т.code,
                kind = ElementKind.STATEMENT,
                title = "Тезис",
                columns = emptyList(),
                rows = emptyList(),
                text = текст,
                supports = свои,
                minRows = 0,
                satisfied = true,
                waitingScenes = emptyList(),
                notes = if (безОпоры.isEmpty()) emptyList() else listOf(
                    "число без опоры: " + безОпоры.joinToString(", ") +
                        " — назовите элемент раздела, откуда величина",
                ),
            )
        }
        val ждёт = элементы.filterNot { it.satisfied }.map { э ->
            "«${э.title}»: ${э.rows.size} из ${э.minRows}" + чегоЖдёт(э.waitingScenes)
        }
        return SectionView(
            no = раздел.path("no").asText(""),
            title = раздел.path("title").asText(""),
            scenes = сцены,
            elements = элементы + тезисыВида,
            complete = ждёт.isEmpty(),
            waiting = ждёт,
        )
    }

    /**
     * Чего ждёт элемент. Сквозной раздел (допущения, открытые вопросы)
     * питается всей фазой — перечислять десять сцен значит не сказать
     * ничего: он ждёт по ходу фазы, и так и говорится.
     */
    private fun чегоЖдёт(сцены: List<String>): String = when {
        сцены.isEmpty() -> ""
        сцены.size > 3 -> " — наполняется по ходу фазы"
        else -> " — ждёт сцен " + сцены.joinToString(", ")
    }

    /** Опоры тезиса: названные элементы, а без названных — весь раздел. */
    private fun если(всеСтроки: List<String>, свои: List<String>, элементы: List<ElementView>): List<String> =
        if (свои.isEmpty()) всеСтроки
        else элементы.filter { it.code in свои }.flatMap { it.rows.flatten() }

    override fun addStatement(
        project: String,
        code: String,
        section: String,
        text: String,
        supports: List<String>,
        author: String,
    ): SectionView {
        require(text.isNotBlank()) { "тезис пуст: свободный текст документа — это тезис, и он обязан что-то утверждать" }
        val шаблон = шаблонИли(code)
        val раздел = шаблон.path("sections").firstOrNull { it.path("no").asText() == section }
            ?: throw NoSuchElementException("раздела «$section» нет в шаблоне «$code»")
        val doc = mapper.createObjectNode()
        doc.put("document", code)
        doc.put("section", section)
        doc.put("kind", "statement")
        doc.put("text", text)
        doc.set<ObjectNode>("supports", mapper.valueToTree(supports))
        doc.put("author", author)
        store.create(
            code = свободныйТезис(project, code, section),
            kind = "element",
            area = Area.Project(project),
            bornIn = раздел.path("scenes").firstOrNull()?.asText(),
            doc = doc,
            provenance = Provenance(Channel.MANUAL, author),
        )
        return собратьРаздел(project, раздел, тезисыПроекта(project, code)[section].orEmpty())
    }

    override fun hintsForScene(project: String, scene: String): List<DocumentHint> =
        store.list(Area.Project(project), "document").flatMap { документ ->
            val код = документ.doc.path("template").asText(документ.code)
            val шаблон = template(код) ?: return@flatMap emptyList()
            val вид = document(project, код)
            шаблон.path("sections")
                .filter { раздел -> раздел.path("scenes").any { it.asText() == scene } }
                .map { раздел ->
                    val номер = раздел.path("no").asText()
                    val собранный = вид.sections.first { it.no == номер }
                    DocumentHint(
                        document = вид.title,
                        section = номер,
                        sectionTitle = собранный.title,
                        elements = собранный.elements.size,
                        filled = собранный.elements.count { it.satisfied && (it.rows.isNotEmpty() || it.text != null) },
                    )
                }
        }

    override fun render(project: String, code: String): PrintView {
        val вид = document(project, code)
        return PrintView(
            title = вид.title,
            subtitle = "${вид.standard} · полнота ${вид.complete} из ${вид.total} разделов",
            sections = вид.sections.map { раздел ->
                PrintSection(раздел.no, раздел.title, StubRender.lines(раздел))
            },
        )
    }

    override fun print(project: String, code: String, projectName: String): ByteArray =
        PdfPrint.bytes(render(project, code), projectName)

    /**
     * Свободный код тезиса: MAX + 1 по разделу. Размер списка врёт после
     * снятия тезиса, и следующий получил бы занятый код.
     */
    private fun свободныйТезис(project: String, code: String, section: String): String {
        val занято = тезисыПроекта(project, code)[section].orEmpty().mapNotNull {
            Regex("-т(\\d+)$").find(it.code)?.groupValues?.get(1)?.toIntOrNull()
        }
        return "$code-$section-т${(занято.maxOrNull() ?: 0) + 1}"
    }

    private fun тезисыПроекта(project: String, code: String): Map<String, List<Entity>> =
        store.list(Area.Project(project), "element")
            .filter { it.status != "cancelled" && it.doc.path("document").asText() == code }
            .groupBy { it.doc.path("section").asText() }

    private fun шаблонИли(code: String): JsonNode = template(code)
        ?: throw NoSuchElementException(
            "шаблона документа «$code» нет на полке: положите его в полку шаблонов",
        )
}
