// Проверка документа против поля знаний (ЗНАНИЯ-V2, мера ПМИ-6 п. 2.10).
//
// Полнота отвечает на вопрос «всё ли заполнено», базирование — «что мы
// зафиксировали». Ни то, ни другое не отвечает на третий вопрос, который на
// обзоре задают первым: СХОДИТСЯ ЛИ написанное с тем, что известно. Документ
// собран однажды, а поле живёт: пришла новая версия источника, эксперт завёл
// факт, разбор нашёл спор между двумя документами.
//
// Четыре рода расхождения — истина схем (`verification_report.items.issue`):
//   · тезис без основания — свободный текст, за которым нет ни элемента
//     раздела, ни факта;
//   · число против факта — величина, которой нет ни в одном факте поля;
//   · противоречие — два основания с разными значениями: показываются ОБА с
//     рангами, победитель не выбирается;
//   · устарело — опора изменилась после того, как вошла в документ.
//
// Чего здесь нет и не будет:
//   · правок. Ни одной. Проверка заводит отчёт — и всё: версии документа,
//     версии сущностей и диспозиции фактов остаются прежними до единицы.
//     Молча исправленный документ хуже расходящегося: в нём не видно, что
//     он однажды разошёлся;
//   · второго разборщика чисел. Числа документа читает тот же NumberGuard,
//     что стережёт тезисы и живой рендеринг: два разборщика разошлись бы, и
//     сторож начал бы спорить сам с собой;
//   · собственных правил дубля и противоречия. Что считается спором, знает
//     ОНТОЛОГИЯ-ФОРМИРОВАНИЯ (`conflict_on`), а спор конкретных фактов уже
//     посчитало поле знаний (связь `contradicts` и её представление
//     `conflicts`). Документы читают это знание, а не считают заново.
package orbita.documents.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.documents.api.DocumentBaseline
import orbita.documents.api.Documents
import orbita.documents.api.ElementKind
import orbita.documents.api.ElementView
import orbita.documents.api.Issue
import orbita.documents.api.SectionView
import orbita.documents.api.Verification
import orbita.documents.api.VerificationItem
import orbita.documents.api.VerificationReport
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry
import orbita.kernel.api.Provenance
import orbita.knowledge.api.Authority
import orbita.knowledge.schema.GeneratedOntology
import java.time.LocalDate
import java.time.OffsetDateTime

internal class FieldVerification(
    private val store: EntityStore,
    private val links: LinkRegistry,
    /**
     * Документ читается ТЕМ ЖЕ портом, которым его видит экран. Собери
     * проверка документ по-своему — и отчёт однажды сослался бы на элемент,
     * которого на экране нет.
     */
    private val documents: Documents,
    private val mapper: ObjectMapper = ObjectMapper(),
) : Verification {

    override fun verify(
        project: String,
        document: String,
        author: String,
        baseline: String?,
    ): VerificationReport {
        require(author.isNotBlank()) { "проверку заводит человек: без автора отчёт некому предъявить" }
        val область = Area.Project(project)
        val вид = documents.document(project, document)
        val линия = baseline?.takeIf { it.isNotBlank() }?.let { имя ->
            documents.baselines(project, document).firstOrNull { it.name == имя }
                ?: throw NoSuchElementException(
                    "базовой линии «$имя» у документа «$document» нет: проверять не с чем",
                )
        }
        val поле = Поле(область)

        val находки = mutableListOf<VerificationItem>()
        var проверено = 0
        // Разделы обходятся ВСЕ, включая те, которых ступень ещё не ждёт:
        // незрелость — про сроки, а тезис без основания остаётся тезисом без
        // основания в любой ступени.
        вид.sections.forEach { раздел ->
            раздел.elements.forEach { элемент ->
                when (элемент.kind) {
                    ElementKind.STATEMENT -> {
                        проверено += 1
                        находки += тезис(раздел, элемент, поле)
                    }
                    ElementKind.QUERY -> элемент.rows.forEachIndexed { номер, строка ->
                        // Строка, которую не удалось назвать сущностью поля,
                        // молчит: проверка говорит только о том, что может
                        // назвать, и не выдумывает опору по догадке.
                        val сущность = поле.сущностьСтроки(элемент, строка) ?: return@forEachIndexed
                        проверено += 1
                        находки += опора("${элемент.code}#${номер + 1}", сущность, поле, линия)
                    }
                    // Ссылка на сущность, ссылка на факт, таблица и рисунок
                    // документом пока не заводятся: EntityDocuments собирает
                    // только запрос и тезис, а сущности приходят СТРОКАМИ
                    // запроса — там их основание и проверяется. Писать разбор
                    // под элемент, которого не существует, значит писать его
                    // вслепую: такая проверка молчала бы или врала.
                    else -> Unit
                }
            }
        }

        val отобранные = находки.distinctBy { Triple(it.element, it.issue, it.detail) }
        return записать(область, document, линия, отобранные, сводка(проверено, отобранные), author)
    }

    override fun reports(project: String, document: String?): List<VerificationReport> {
        val область = Area.Project(project)
        return store.list(область, ВИД)
            .filter { document.isNullOrBlank() || it.doc.path("document").asText() == document }
            .map { отчёт(область, it) }
            .sortedWith(compareBy<VerificationReport>({ it.at }, { it.code }))
    }

    override fun report(project: String, code: String): VerificationReport {
        val область = Area.Project(project)
        return отчёт(область, найти(область, code))
    }

    override fun close(
        project: String,
        code: String,
        author: String,
        reason: String,
    ): VerificationReport {
        require(author.isNotBlank()) { "отчёт закрывает человек: без автора закрытие никому не принадлежит" }
        require(reason.isNotBlank()) {
            "отчёт закрывается с причиной: «закрыт» без причины — тишина, а не решение"
        }
        val область = Area.Project(project)
        val запись = найти(область, code)
        require(запись.status != VerificationReport.CLOSED) {
            "отчёт «$code» уже закрыт: закрывать дважды нечего"
        }
        // Находки НЕ трогаются: закрытие — про разбор, а не про правку
        // отчёта. Стереть находку значит стереть след расхождения.
        val документ = (запись.doc.deepCopy() as ObjectNode)
        документ.put(
            "notes",
            listOf(запись.doc.path("notes").asText(""), "закрыт: $reason ($author)")
                .filter { it.isNotBlank() }.joinToString(" · "),
        )
        store.update(запись.id, документ, Provenance(Channel.MANUAL, author), status = VerificationReport.CLOSED)
        return report(project, code)
    }

    // --- тезис -------------------------------------------------------------

    /**
     * Тезис: единственный свободный текст документа — и единственное место,
     * где число может появиться из ниоткуда.
     */
    private fun тезис(раздел: SectionView, элемент: ElementView, поле: Поле): List<VerificationItem> {
        val текст = элемент.text.orEmpty()
        val находки = mutableListOf<VerificationItem>()
        if (элемент.supports.isEmpty()) {
            находки += VerificationItem(
                element = элемент.code,
                issue = Issue.NO_BASIS,
                detail = "тезис раздела ${раздел.no} «${коротко(текст)}» утверждает, но не назвал ни одной " +
                    "опоры: ни элемента раздела, ни факта поля — проверить его нечем",
                proposal = "назовите опору тезиса: элемент раздела либо факт поля; " +
                    "у руки эксперта основанием служит его же ручной факт",
            )
        }
        // Опоры числа — сведения ПОЛЯ плюс строки самого раздела: строки
        // раздела тоже пришли из поля, и требовать для них второго факта
        // значило бы спорить с собственной таблицей.
        val опоры = поле.значенияФактов + раздел.elements
            .filter { it.kind == ElementKind.QUERY }.flatMap { it.rows.flatten() }
        NumberGuard.unsupported(текст, опоры).forEach { число ->
            находки += VerificationItem(
                element = элемент.code,
                issue = Issue.NUMBER_VS_FACT,
                detail = детальЧисла(число, поле),
                proposal = "назовите факт, откуда величина, либо исправьте число в тезисе; " +
                    "документ — не место, где числа появляются впервые",
            )
        }
        return находки
    }

    private fun детальЧисла(число: String, поле: Поле): String {
        val спорный = поле.фактТойЖеМеры(число)
        return if (спорный == null) {
            "числа «$число» нет ни в одном факте поля и ни в одной строке раздела — " +
                "утверждение держится на слове"
        } else {
            "в документе «$число», а поле говорит ${поле.значение(спорный)} " +
                "(${поле.откуда(спорный)}) — показаны оба значения; " +
                "ранг подсказывает, победителя выбирает человек"
        }
    }

    // --- строка из поля ----------------------------------------------------

    /** Строка документа, за которой стоит сущность поля: её основания и их возраст. */
    private fun опора(
        mid: String,
        сущность: Entity,
        поле: Поле,
        линия: DocumentBaseline?,
    ): List<VerificationItem> {
        val находки = mutableListOf<VerificationItem>()
        val основания = поле.основания(сущность)
        if (основания.isEmpty()) {
            находки += VerificationItem(
                element = mid,
                issue = Issue.NO_BASIS,
                detail = "«${поле.имя(сущность)}» (${сущность.code}) в документе есть, а основания в поле " +
                    "нет: ни одного факта по связи — строка держится на слове",
                proposal = "привяжите факт-основание либо заведите ручной факт эксперта " +
                    "(учётка · роль · дата)",
            )
        }
        находки += противоречия(mid, сущность, основания, поле)
        устарело(mid, сущность, основания, поле, линия)?.let { находки += it }
        return находки
    }

    /**
     * Противоречие двумя путями: спор самих оснований (его посчитало поле
     * знаний) и расхождение показанного поля сущности с её же основанием по
     * `conflict_on` онтологии. Оба значения называются вслух; поля
     * «победитель» здесь нет и быть не может.
     */
    private fun противоречия(
        mid: String,
        сущность: Entity,
        основания: List<Entity>,
        поле: Поле,
    ): List<VerificationItem> {
        val находки = mutableListOf<VerificationItem>()
        основания.forEach { факт ->
            поле.соперники(факт).forEach { другой ->
                // Пара пишется один раз: у противоречия нет направления, и
                // вторая находка была бы тем же спором наизнанку.
                if (факт.code > другой.code) return@forEach
                находки += VerificationItem(
                    element = mid,
                    issue = Issue.CONTRADICTION,
                    detail = "${предикат(факт)}: ${поле.значение(факт)} (${поле.откуда(факт)}) против " +
                        "${поле.значение(другой)} (${поле.откуда(другой)}) — оба основания строки " +
                        "«${поле.имя(сущность)}» показаны; ранг подсказывает, победителя выбирает человек",
                    proposal = "решите спор в поле знаний диспозицией фактов: документ спор не разрешает",
                )
            }
        }
        return находки + поКонфликтнымПолям(mid, сущность, основания, поле)
    }

    /**
     * Поле, различие в котором онтология считает противоречием, показано в
     * документе иначе, чем говорит его собственное основание.
     *
     * Сравниваются только СОИЗМЕРИМЫЕ числа: величина спорит с величиной той
     * же единицы, год — с годом. «Пять сторон» и «2030 год» единицы не имеют
     * оба, а спорить им не о чем — объявить их конфликтом значит завести спор
     * на пустом месте.
     */
    private fun поКонфликтнымПолям(
        mid: String,
        сущность: Entity,
        основания: List<Entity>,
        поле: Поле,
    ): List<VerificationItem> {
        val понятие = GeneratedOntology.byCode[сущность.kind] ?: return emptyList()
        val находки = mutableListOf<VerificationItem>()
        понятие.conflictOn.map { имяПоля(it) }.filter { it.isNotBlank() }.forEach { имя ->
            val числаДокумента = NumberGuard.numbers(словами(сущность.doc.path(имя)), поле.единицы)
            if (числаДокумента.isEmpty()) return@forEach
            основания.forEach { факт ->
                val соизмеримые = NumberGuard.numbers(поле.величина(факт), поле.единицы)
                    .filter { чф -> числаДокумента.any { соизмеримы(it, чф) } }
                if (соизмеримые.isEmpty() || соизмеримые.any { it in числаДокумента }) return@forEach
                находки += VerificationItem(
                    element = mid,
                    issue = Issue.CONTRADICTION,
                    detail = "${словоПоля(имя)}: документ показывает «${числаДокумента.joinToString(" · ")}», " +
                        "а основание ${поле.откуда(факт)} говорит «${соизмеримые.joinToString(" · ")}» — " +
                        "показаны оба значения; ранг подсказывает, победителя выбирает человек",
                    proposal = "подтвердите значение фактом либо оспорьте основание в поле знаний",
                )
            }
        }
        return находки
    }

    /**
     * Устарело: опора изменилась после того, как вошла в документ.
     *
     * Одна находка на строку, сколько бы причин ни нашлось: человеку нужно
     * знать, что здесь смотреть, а не получить четыре карточки об одном.
     */
    private fun устарело(
        mid: String,
        сущность: Entity,
        основания: List<Entity>,
        поле: Поле,
        линия: DocumentBaseline?,
    ): VerificationItem? {
        val причины = mutableListOf<String>()
        основания.forEach { факт ->
            val обновлён = факт.doc.path("source_updated").asText("")
            if (обновлён.isNotBlank() || факт.doc.path("superseded").asBoolean(false)) {
                причины += факт.doc.path("source_note").asText("").ifBlank {
                    "основание ${факт.code}: источник обновлён" +
                        if (обновлён.isBlank()) "" else " ($обновлён)"
                }
            }
        }
        сущность.doc.path("source_updated").asText("").ifBlank { null }?.let {
            причины += "сущность помечена «источник обновлён»: $it"
        }
        основания.mapNotNull { поле.материал(it) }.distinctBy { it.code }.forEach { материал ->
            val до = материал.doc.path("valid_until").asText("").take(10)
            if (до.isNotBlank() && до < сегодня()) {
                причины += "материал ${материал.code} действителен до $до — срок истёк"
            }
        }
        линия?.let { базовая ->
            моложе(базовая, основания + сущность).forEach { свежая ->
                причины += "опора ${свежая.code} изменилась после базирования «${базовая.name}» " +
                    "(версия ${свежая.version})"
            }
        }
        if (причины.isEmpty()) return null
        return VerificationItem(
            element = mid,
            issue = Issue.STALE,
            detail = "строка «${поле.имя(сущность)}» опирается на устаревшее: " +
                причины.joinToString("; ") + " — документ остался на прежнем значении",
            proposal = "перечитайте новый источник и решите: подтвердить прежнее значение или " +
                "завести новое; молча документ не правится",
        )
    }

    /** Опоры, изменённые позже базирования. Разобрать время линии не удалось — молчим. */
    private fun моложе(линия: DocumentBaseline, опоры: List<Entity>): List<Entity> {
        val когда = runCatching { OffsetDateTime.parse(линия.at) }.getOrNull() ?: return emptyList()
        return опоры.filter { it.updatedAt.isAfter(когда) }.distinctBy { it.code }
    }

    // --- запись отчёта -----------------------------------------------------

    private fun записать(
        область: Area,
        document: String,
        линия: DocumentBaseline?,
        находки: List<VerificationItem>,
        сводка: String,
        author: String,
    ): VerificationReport {
        val когда = OffsetDateTime.now().toString()
        val снимок = линия?.let { снимокЛинии(область, document, it.name) }
        val документ = mapper.createObjectNode()
        документ.put("document", document)
        снимок?.let { документ.put("baseline", it.code) }
        val массив = документ.putArray("items")
        находки.forEach { находка ->
            val узел = массив.addObject()
            узел.put("element", находка.element)
            узел.put("issue", находка.issue.code)
            узел.put("detail", находка.detail)
            находка.proposal?.let { узел.put("proposal", it) }
        }
        документ.put("at", когда)
        документ.put("notes", сводка)
        val код = следующийКод(область)
        // ЕДИНСТВЕННАЯ запись проверки. Всё остальное — чтение: мера
        // «правок молча — 0» держится именно здесь.
        store.create(
            code = код,
            kind = ВИД,
            area = область,
            bornIn = null,
            doc = документ,
            provenance = Provenance(Channel.SERVICE, author, source = document),
            status = VerificationReport.OPEN,
        )
        return VerificationReport(
            code = код,
            document = document,
            baseline = снимок?.code,
            baselineName = линия?.name,
            items = находки,
            at = когда,
            status = VerificationReport.OPEN,
            summary = сводка,
        )
    }

    private fun отчёт(область: Area, запись: Entity): VerificationReport {
        val снимок = запись.doc.path("baseline").asText("").ifBlank { null }
        return VerificationReport(
            code = запись.code,
            document = запись.doc.path("document").asText(""),
            baseline = снимок,
            baselineName = снимок
                ?.let { store.byCode(область, it)?.doc?.path("name")?.asText("") }
                ?.ifBlank { null },
            items = запись.doc.path("items").map { узел ->
                VerificationItem(
                    element = узел.path("element").asText(""),
                    issue = Issue.of(узел.path("issue").asText("")),
                    detail = узел.path("detail").asText(""),
                    proposal = узел.path("proposal").asText("").ifBlank { null },
                )
            },
            at = запись.doc.path("at").asText(""),
            status = запись.status,
            summary = запись.doc.path("notes").asText(""),
        )
    }

    private fun найти(область: Area, code: String): Entity =
        store.byCode(область, code)?.takeIf { it.kind == ВИД }
            ?: throw NoSuchElementException("отчёта проверки «$code» в проекте нет")

    private fun снимокЛинии(область: Area, document: String, name: String): Entity? =
        store.list(область, "baseline_snapshot").firstOrNull {
            it.doc.path("document").asText() == document && it.doc.path("name").asText() == name
        }

    private fun следующийКод(область: Area): String {
        val занято = store.list(область, ВИД).mapNotNull {
            Regex("^VR-(\\d+)$").find(it.code)?.groupValues?.get(1)?.toIntOrNull()
        }
        return "VR-%04d".format((занято.maxOrNull() ?: 0) + 1)
    }

    /**
     * Сводка словами. «Проверять нечего» — не то же самое, что «расхождений
     * нет»: первое говорит, что документ пуст, второе — что он сошёлся
     * (readiness_negative_test_rule).
     */
    private fun сводка(проверено: Int, находки: List<VerificationItem>): String = when {
        проверено == 0 ->
            "проверять нечего: в документе нет ни тезисов, ни строк, опознанных в поле знаний"
        находки.isEmpty() ->
            "расхождений нет: проверено ${мест(проверено)}, каждое сошлось с полем"
        else -> "проверено ${мест(проверено)}; " + Issue.entries
            .map { род -> род to находки.count { it.issue == род } }
            .filter { it.second > 0 }
            .joinToString(" · ") { (род, сколько) -> "${род.word} — $сколько" }
    }

    // --- поле знаний -------------------------------------------------------

    /**
     * Поле знаний проекта, прочитанное ОДИН раз за проверку.
     *
     * Читать факты и материалы на каждую строку значило бы ходить в
     * хранилище сотню раз за одним и тем же ответом; а «прочитано один раз»
     * даёт ещё и цельность снимка: отчёт описывает поле на один момент.
     */
    private inner class Поле(область: Area) {

        val факты: List<Entity> = store.list(область, "fact").filter { it.status != "cancelled" }

        private val фактыПоКоду: Map<String, Entity> = поКодам(факты)

        private val материалы: Map<String, Entity> = поКодам(store.list(область, "material"))

        /** Величины фактов своими словами — они же словарь чисел и единиц поля. */
        val значенияФактов: List<String> = факты.map { величина(it) }

        val единицы: Set<String> = NumberGuard.unitsOf(значенияФактов)

        /** Нормализованное число поля → факты, которые его утверждают. */
        private val числа: Map<String, List<Entity>> =
            buildMap<String, MutableList<Entity>> {
                факты.forEach { факт ->
                    NumberGuard.numbers(величина(факт), единицы).forEach { токен ->
                        getOrPut(токен) { mutableListOf() } += факт
                    }
                }
            }

        /**
         * Сущности, которыми документ может назвать строку: проект без
         * служебных видов плюс нормативы полки (документ проекта читает общие
         * знания, не копируя их).
         */
        private val сущности: List<Entity> =
            store.list(область).filter { it.status != "cancelled" && it.kind !in СЛУЖЕБНЫЕ } +
                store.list(Area.Library, "normative_document").filter { it.status != "cancelled" }

        private val поКоду: Map<String, Entity> = поКодам(сущности)

        /**
         * Имя → сущность, только для ОДНОЗНАЧНЫХ имён. Две сущности с одним
         * именем строку не опознают: угадать, о какой из них документ, нельзя,
         * а угаданная опора хуже отсутствующей.
         */
        private val поИмени: Map<String, Entity> = сущности
            .mapNotNull { сущность -> ключ(сырое(сущность))?.let { it to сущность } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size == 1 }
            .mapValues { it.value.first() }

        /** Сущность строки: сначала колонкой кода, потом любой ячейкой, потом именем. */
        fun сущностьСтроки(элемент: ElementView, строка: List<String>): Entity? {
            val колонка = элемент.columns.indexOfFirst { it.field == "code" }
            if (колонка >= 0) {
                поКоду[строка.getOrNull(колонка)?.trim().orEmpty()]?.let { return it }
            }
            строка.forEach { ячейка -> поКоду[ячейка.trim()]?.let { return it } }
            строка.forEach { ячейка -> ключ(ячейка)?.let { к -> поИмени[к]?.let { return it } } }
            return null
        }

        fun основания(сущность: Entity): List<Entity> =
            (links.from(сущность.id, СВЯЗЬ_ОСНОВАНИЯ).map { it.to } +
                links.to(сущность.id, СВЯЗЬ_ОСНОВАНИЯ).map { it.from })
                .mapNotNull { store.byId(it) }
                .filter { it.kind == "fact" && it.status != "cancelled" }
                .distinctBy { it.code }
                .sortedBy { it.code }

        /**
         * Факты, спорящие с этим: связь `contradicts` и её вычисляемое
         * представление `conflicts`. Спор считает ПОЛЕ ЗНАНИЙ — второй счётной
         * машины противоречия в документах нет.
         */
        fun соперники(факт: Entity): List<Entity> =
            (факт.doc.path("conflicts").mapNotNull { фактыПоКоду[it.asText("")] } +
                (links.from(факт.id, "contradicts").map { it.to } +
                    links.to(факт.id, "contradicts").map { it.from })
                    .mapNotNull { store.byId(it) }
                    .filter { it.kind == "fact" })
                .filter { it.code != факт.code && it.status != "cancelled" }
                .distinctBy { it.code }
                .sortedBy { it.code }

        fun материал(факт: Entity): Entity? = материалы[факт.doc.path("material").asText("")]

        /** Величина факта своими словами: «180 МКА», «2030». */
        fun величина(факт: Entity): String =
            (факт.doc.path("value").asText("") + " " + факт.doc.path("unit").asText("")).trim()

        fun значение(факт: Entity): String = "«${величина(факт)}»"

        /**
         * Ранг факта: свой, а если разбор его не проставил — ранг материала
         * (факт наследует ранг документа, knowledge_field_rules).
         */
        fun ранг(факт: Entity): String {
            val свой = факт.doc.path("authority").asText("")
            if (Authority.known(свой)) return свой
            val карточка = материал(факт) ?: return ""
            return карточка.doc.path("authority").asText("").takeIf { Authority.known(it) }.orEmpty()
        }

        /** «F-0001, обязательный, из MM-0001» — код, ранг словами и источник. */
        fun откуда(факт: Entity): String = listOfNotNull(
            факт.code,
            Authority.word(ранг(факт)).ifBlank { null },
            факт.doc.path("material").asText("").ifBlank { null }?.let { "из $it" },
        ).joinToString(", ")

        /**
         * Факт, спорящий с числом документа: та же мера, другое значение.
         * Весомейший по рангу — чтобы подсказка называла лучшее, что есть в
         * поле, а не первое попавшееся.
         */
        fun фактТойЖеМеры(число: String): Entity? = числа.entries
            .filter { (токен, _) -> токен != число && соизмеримы(число, токен) }
            .flatMap { it.value }
            .minByOrNull { Authority.weight(ранг(it)) }

        /** Имя сущности для человека — укороченное: находка читается строкой. */
        fun имя(сущность: Entity): String = сырое(сущность)?.let { коротко(it) } ?: сущность.code

        /**
         * Имя как оно записано. Именно оно ложится в указатель имён: строка
         * документа несёт имя ЦЕЛИКОМ, и сверять её с укороченным значило бы
         * не опознать ни одну длинную формулировку.
         */
        private fun сырое(сущность: Entity): String? = listOf("name", "title", "statement", "label")
            .firstNotNullOfOrNull { сущность.doc.path(it).asText("").ifBlank { null } }

        /** Код → сущность; при одинаковых кодах побеждает ПЕРВАЯ (проект, не полка). */
        private fun поКодам(записи: List<Entity>): Map<String, Entity> = buildMap<String, Entity> {
            записи.forEach { запись -> if (запись.code !in this) put(запись.code, запись) }
        }

        private fun ключ(текст: String?): String? =
            текст?.trim()?.lowercase()?.replace('ё', 'е')?.ifBlank { null }
    }

    // --- мелочи ------------------------------------------------------------

    /**
     * Соизмеримы ли два нормализованных числа. Разные единицы не спорят;
     * безъединичные спорят, только если оба — годы (thresholds_rule: число из
     * свободного текста мерой не становится).
     */
    private fun соизмеримы(моё: String, чужое: String): Boolean {
        val моя = единица(моё)
        if (моя != единица(чужое)) return false
        return моя.isNotBlank() || (год(моё) != null && год(чужое) != null)
    }

    private fun единица(токен: String): String = токен.substringAfter(' ', "").trim()

    private fun год(токен: String): Int? = токен.trim().trimStart('≤', '≥', ' ')
        .substringBefore(' ').toIntOrNull()?.takeIf { it in 1900..2200 }

    /** Утверждение факта — им называется спор: «масса», «год развёртывания». */
    private fun предикат(факт: Entity): String =
        факт.doc.path("predicate").asText("").trim().ifBlank { факт.doc.path("subject").asText("") }
            .let { коротко(it) }

    /**
     * Имя поля из `conflict_on`: онтология пишет «statement contradicts» —
     * полем сущности является первое слово, остальное объясняет, ЧЕМ спор.
     */
    private fun имяПоля(правило: String): String = правило.trim().substringBefore(' ').substringBefore('|')

    /**
     * Поле словами. Имя поля — адрес в модели, а человеку нужен русский:
     * «год», а не `year`. Неизвестное имя остаётся как есть — выдумывать
     * перевод хуже, чем показать адрес.
     */
    private fun словоПоля(имя: String): String = ПОЛЯ_СЛОВАМИ[имя] ?: имя

    /** Значение поля словами: величина парой, список — через точку. */
    private fun словами(узел: JsonNode): String = when {
        узел.isMissingNode || узел.isNull -> ""
        узел.isBoolean -> if (узел.asBoolean()) "да" else "нет"
        узел.isArray -> узел.joinToString(" · ") { словами(it) }
        узел.isObject -> listOf("key", "op", "value", "unit")
            .map { словами(узел.path(it)) }.filter { it.isNotBlank() }.joinToString(" ")
            .ifBlank { узел.properties().joinToString(" · ") { (_, значение) -> словами(значение) } }
        else -> узел.asText("")
    }

    private fun коротко(текст: String): String {
        val чистый = текст.trim().replace(Regex("\\s+"), " ")
        return if (чистый.length <= 70) чистый else чистый.take(67) + "…"
    }

    private fun сегодня(): String = LocalDate.now().toString()

    private fun мест(сколько: Int): String {
        val последняя = сколько % 10
        val десяток = сколько % 100
        val слово = when {
            десяток in 11..14 -> "мест"
            последняя == 1 -> "место"
            последняя in 2..4 -> "места"
            else -> "мест"
        }
        return "$сколько $слово"
    }

    private companion object {

        const val ВИД: String = "verification_report"

        /** Нить от сущности к её факту: без неё основания не видно. */
        const val СВЯЗЬ_ОСНОВАНИЯ: String = "derived_from_fact"

        /**
         * Виды, которые строкой документа не бывают: они сами и есть поле,
         * его журнал или тело документа. Опознай проверка строку как факт —
         * и спросила бы у факта его же основание.
         */
        val СЛУЖЕБНЫЕ: Set<String> = setOf(
            "fact", "material", "topic", "element", "document", "baseline_snapshot",
            "rendering", "verification_report", "synthesis_run", "research_task",
            "intake_task", "project", "plan", "scene", "finding",
        )

        /** Поля спора онтологии по-русски: человеку нужен русский, модели — адрес. */
        val ПОЛЯ_СЛОВАМИ: Map<String, String> = mapOf(
            "role" to "роль",
            "statement" to "формулировка",
            "measure" to "величина",
            "year" to "год",
            "qos_class" to "класс обслуживания",
            "target_measure" to "целевая величина",
            "bound" to "граница",
            "date" to "дата",
            "edition" to "редакция",
        )
    }
}
