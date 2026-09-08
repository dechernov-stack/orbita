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
import orbita.documents.api.BaselineElement
import orbita.documents.api.ColumnView
import orbita.documents.api.DocumentBaseline
import orbita.documents.api.DocumentHint
import orbita.documents.api.DocumentView
import orbita.documents.api.Documents
import orbita.documents.api.ElementKind
import orbita.documents.api.ElementView
import orbita.documents.api.FieldChange
import orbita.documents.api.PrintSection
import orbita.documents.api.RenderedSection
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
    /**
     * Каталог репозиториев базирования. Отдельно от репозитория изделия:
     * документы проектов — содержание владельца, и в публичный репозиторий
     * продукта они не попадают.
     */
    private val baselineRoot: java.io.File =
        java.io.File(System.getenv("ORBITA_BASELINES_DIR") ?: "/files/basirovaniya"),
    /**
     * Живая модель для «Написать связно»: промпт → текст и имя модели.
     *
     * Приходит ФУНКЦИЕЙ, а не модулем: документам не нужно знать ни про
     * транспорт, ни про журнал вызовов — им нужен текст. Пусто — связного
     * текста в проекте не будет, и маршрут скажет об этом прямо, а не
     * подсунет stub-склейку под видом прозы.
     */
    private val writer: ((project: String, prompt: String) -> Pair<String, String>)? = null,
) : Documents {

    private val книга = BaselineBook(baselineRoot)

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
    private val лестница = listOf("MCR", "KDP-A", "SRR", "SDR", "PDR", "CDR")

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
        } + минимумТезисов(раздел, тезисы.size)
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
     * Раздел, который ждёт ТЕЗИСА человека (FAD/FA по Прил. 1–2: «основание
     * инициирования», «период действия; стороны»): запросом его не
     * наполнить, и без тезиса раздел неполон — с подсказкой, о чём тезис.
     */
    private fun минимумТезисов(раздел: JsonNode, есть: Int): List<String> {
        val нужно = раздел.path("min_statements").asInt(0)
        if (есть >= нужно) return emptyList()
        val подсказка = раздел.path("statement_hint").asText("").ifBlank { null }
        return listOf("тезис: $есть из $нужно" + (подсказка?.let { " — $it" } ?: ""))
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
        // Принятый связный текст печатается ВМЕСТО структуры раздела:
        // читателю нужен документ, а список строк — рабочая поверхность.
        val связные = renderings(project, code).associate { it.section to it.text }
        val линия = baselines(project, code).lastOrNull()
        return PrintView(
            title = вид.title,
            subtitle = "${вид.standard} · полнота ${вид.complete} из ${вид.total} разделов " +
                "к ступени ${вид.gate}",
            sections = вид.sections.map { раздел ->
                val текст = связные[раздел.no]
                PrintSection(
                    раздел.no, раздел.title,
                    if (текст.isNullOrBlank()) StubRender.lines(раздел) else listOf(текст),
                )
            },
            authors = авторы(project, code),
            baseline = линия?.let { л ->
                "базовая линия «${л.name}»" + (if (л.tag.isNotBlank()) " · тег ${л.tag}" else "") +
                    (if (л.commit.isNotBlank()) " · коммит ${л.commit.take(7)}" else "")
            } ?: "базовой линии нет: документ в работе",
        )
    }

    /**
     * Авторы документа — те, кто завёл его содержание.
     *
     * Тезисы несут автора полем; выходы сцен — провенансом сущностей, из
     * которых собраны строки. Порядок — по первому появлению: так титул
     * читается как список работавших, а не как алфавитный указатель.
     */
    private fun авторы(project: String, code: String): List<String> {
        val область = Area.Project(project)
        val имена = linkedSetOf<String>()
        тезисыПроекта(project, code).values.flatten().sortedBy { it.code }
            .forEach { т -> т.doc.path("author").asText("").ifBlank { null }?.let { имена += it } }
        store.list(область, "rendering")
            .filter { it.doc.path("document").asText() == code }
            .forEach { имена += "связный текст: " + it.doc.path("model").asText("модель") }
        return имена.toList()
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

    // --- базирование -------------------------------------------------------

    /**
     * Снимок документа поэлементно.
     *
     * MID строки запроса — код элемента с НОМЕРОМ СТРОКИ, а не с её
     * содержимым: иначе правка ячейки читалась бы как «строку удалили и
     * завели другую», и диф раздувался бы вдвое на каждую опечатку.
     */
    private fun снимок(project: String, code: String): List<BaselineElement> =
        document(project, code).sections.flatMap { раздел ->
            раздел.elements.flatMap { элемент ->
                when (элемент.kind) {
                    ElementKind.STATEMENT -> listOf(
                        BaselineElement(
                            mid = элемент.code,
                            kind = "тезис",
                            section = раздел.no,
                            fields = mapOf(
                                "текст" to (элемент.text ?: ""),
                                "опоры" to элемент.supports.joinToString(", "),
                            ),
                        ),
                    )
                    ElementKind.QUERY -> элемент.rows.mapIndexed { н, строка ->
                        BaselineElement(
                            mid = "${элемент.code}#${н + 1}",
                            kind = "строка",
                            section = раздел.no,
                            fields = элемент.columns.mapIndexed { к, колонка ->
                                колонка.title to (строка.getOrNull(к) ?: "")
                            }.toMap(),
                        )
                    }
                    // Виды элементов, которых документ пока не заводит, в
                    // снимок не идут: снимать нечего, а домысливать нельзя.
                    else -> emptyList()
                }
            }
        }

    override fun baseline(
        project: String,
        code: String,
        name: String,
        author: String,
    ): DocumentBaseline {
        val область = Area.Project(project)
        require(name.isNotBlank()) { "у базовой линии нет имени: ею зовут состояние на точке" }
        val прежние = store.list(область, "baseline_snapshot")
            .filter { it.doc.path("document").asText() == code }
        require(прежние.none { it.doc.path("name").asText() == name }) {
            "базовая линия «$name» документа «$code» уже есть: линия неизменяема, " +
                "перебазирование заводит новое имя"
        }
        val элементы = снимок(project, code)
        require(элементы.isNotEmpty()) {
            "документ «$code» пуст: базировать нечего — сначала прожить сцены"
        }

        val когда = java.time.OffsetDateTime.now().toString()
        val отметка = книга.отметить(project, code, name, текстСнимка(project, code, элементы), author)

        val документ = mapper.createObjectNode()
        документ.put("project", project)
        документ.put("document", code)
        документ.put("name", name)
        документ.put("by", author)
        документ.put("at", когда)
        документ.put("tag", отметка.tag)
        документ.put("commit", отметка.commit)
        val список = документ.putArray("elements")
        элементы.forEach { э ->
            val узел = список.addObject()
            узел.put("mid", э.mid).put("kind", э.kind).put("section", э.section)
            val поля = узел.putObject("fields")
            э.fields.forEach { (к, в) -> поля.put(к, в) }
        }
        val код = следующийКод(область, "baseline_snapshot", "BL")
        store.create(код, "baseline_snapshot", область, null, документ,
            Provenance(Channel.MANUAL, author), status = "immutable")

        return DocumentBaseline(
            name = name, document = code, elements = элементы,
            by = author, at = когда,
            tag = отметка.tag, commit = отметка.commit, note = отметка.note,
        )
    }

    override fun baselines(project: String, code: String): List<DocumentBaseline> =
        store.list(Area.Project(project), "baseline_snapshot")
            .filter { it.doc.path("document").asText() == code }
            .map { запись ->
                DocumentBaseline(
                    name = запись.doc.path("name").asText(""),
                    document = code,
                    elements = элементыСнимка(запись.doc),
                    by = запись.doc.path("by").asText(""),
                    at = запись.doc.path("at").asText(""),
                    tag = запись.doc.path("tag").asText(""),
                    commit = запись.doc.path("commit").asText(""),
                )
            }
            .sortedBy { it.at }

    private fun элементыСнимка(документ: JsonNode): List<BaselineElement> =
        документ.path("elements").map { э ->
            BaselineElement(
                mid = э.path("mid").asText(""),
                kind = э.path("kind").asText(""),
                section = э.path("section").asText(""),
                fields = э.path("fields").properties().associate { (к, в) -> к to в.asText("") },
            )
        }

    override fun diff(project: String, code: String, from: String, to: String?): List<FieldChange> {
        val линии = baselines(project, code).associateBy { it.name }
        val было = линии[from]
            ?: throw NoSuchElementException(
                "базовой линии «$from» у документа «$code» нет: есть ${линии.keys.joinToString(", ")}",
            )
        val стало = if (to.isNullOrBlank()) снимок(project, code) else (
            линии[to] ?: throw NoSuchElementException("базовой линии «$to» нет")
            ).elements

        val слева = было.elements.associateBy { it.mid }
        val справа = стало.associateBy { it.mid }
        val расхождения = mutableListOf<FieldChange>()

        // Порядок обхода — по mid: отчёт о расхождении обязан читаться
        // одинаково при каждом запуске, иначе его нельзя сравнить с прошлым.
        (слева.keys + справа.keys).sorted().forEach { mid ->
            val б = слева[mid]
            val с = справа[mid]
            when {
                б == null && с != null -> с.fields.forEach { (поле, значение) ->
                    расхождения += FieldChange(mid, с.section, "added", поле, "", значение)
                }
                б != null && с == null -> б.fields.forEach { (поле, значение) ->
                    расхождения += FieldChange(mid, б.section, "removed", поле, значение, "")
                }
                б != null && с != null -> (б.fields.keys + с.fields.keys).sorted().forEach { поле ->
                    val было1 = б.fields[поле] ?: ""
                    val стало1 = с.fields[поле] ?: ""
                    if (было1 != стало1) {
                        расхождения += FieldChange(mid, с.section, "changed", поле, было1, стало1)
                    }
                }
            }
        }
        return расхождения
    }

    /** Читаемый текст снимка: он и ложится в репозиторий базирований. */
    private fun текстСнимка(project: String, code: String, элементы: List<BaselineElement>): String {
        val вид = document(project, code)
        val строки = StringBuilder()
        строки.append("# ${вид.title}\n\n")
        строки.append("Проект: $project · стандарт: ${вид.standard}\n\n")
        вид.sections.forEach { раздел ->
            строки.append("## ${раздел.no} ${раздел.title}\n\n")
            элементы.filter { it.section == раздел.no }.forEach { э ->
                строки.append("- [${э.mid}] ")
                строки.append(э.fields.entries.joinToString(" · ") { (к, в) -> "$к: $в" })
                строки.append("\n")
            }
            строки.append("\n")
        }
        return строки.toString()
    }

    private fun следующийКод(область: Area, вид: String, префикс: String): String {
        val занято = store.list(область, вид).mapNotNull {
            Regex("^$префикс-(\\d+)$").find(it.code)?.groupValues?.get(1)?.toIntOrNull()
        }
        return "%s-%04d".format(префикс, (занято.maxOrNull() ?: 0) + 1)
    }

    // --- связный текст -----------------------------------------------------

    override fun write(
        project: String,
        code: String,
        section: String,
        author: String,
    ): RenderedSection {
        val писать = writer ?: throw IllegalStateException(
            "живая модель не подключена: связный текст писать нечем — " +
                "проверьте ORBITA_AI_KEY у службы",
        )
        val шаблон = шаблонИли(code)
        val вид = document(project, code)
        val раздел = вид.sections.firstOrNull { it.no == section }
            ?: throw NoSuchElementException("раздела «$section» в документе «$code» нет")
        val квалификаторы = шаблон.path("review").path("qualifiers").map { it.asText() }

        val сведения = LiveRender.facts(раздел)
        val обороты = NumberGuard.qualifiers(сведения, квалификаторы)
        val промпт = LiveRender.prompt(вид.title, раздел, сведения, обороты)
        val (текст, модель) = писать(project, промпт)
        val чистый = текст.trim()
        val отказы = LiveRender.refusals(чистый, раздел, квалификаторы)
        val пометы = LiveRender.notes(чистый, раздел, квалификаторы)

        // Принятый текст хранится, отклонённый — нет. Хранить отклонённое
        // значит однажды его напечатать.
        if (отказы.isEmpty()) {
            val область = Area.Project(project)
            val код = "RND-$code-${section.replace(Regex("[^0-9]"), "")}"
            val документ = mapper.createObjectNode()
            документ.put("document", code)
            документ.put("section", section)
            документ.put("text", чистый)
            документ.put("model", модель)
            документ.put("at", java.time.OffsetDateTime.now().toString())
            val прежний = store.byCode(область, код)
            if (прежний == null) {
                store.create(код, "rendering", область, null, документ,
                    Provenance(Channel.SERVICE, author), status = "draft")
            } else {
                store.update(прежний.id, документ, Provenance(Channel.SERVICE, author))
            }
        }
        return RenderedSection(section, раздел.title, чистый, модель, отказы, пометы)
    }

    override fun renderings(project: String, code: String): List<RenderedSection> =
        store.list(Area.Project(project), "rendering")
            .filter { it.doc.path("document").asText() == code }
            .map { з ->
                RenderedSection(
                    section = з.doc.path("section").asText(""),
                    title = document(project, code).sections
                        .firstOrNull { it.no == з.doc.path("section").asText() }?.title ?: "",
                    text = з.doc.path("text").asText(""),
                    model = з.doc.path("model").asText(""),
                    refusals = emptyList(),
                )
            }
            .sortedBy { it.section }
}
