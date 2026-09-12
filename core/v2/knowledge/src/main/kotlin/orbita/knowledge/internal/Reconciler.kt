// Сверка: четыре вопроса к кандидату — дубль · противоречие · соединение ·
// нехватка (СВЕРКА-РУЧНОГО-ВВОДА).
//
// Ступень 1 и только она: здесь нет ни одного вызова службы. Всё, что
// решается по данным — ключ идентичности понятия, сравнение по `conflict_on`
// после нормализации, основания по формулировке, незакрытые `must_link`, —
// решается по данным. Семантику надстраивает служба ИИ отдельным модулем, и
// это разделение и есть гарантия меры «дубль по ключу найден БЕЗ живого
// вызова, журнал ИИ не прирастает».
//
// Правила образования не придумываются здесь ни одним оператором: чем
// понятие узнаётся (identity), что считается противоречием (conflict_on) и
// без какой связи его нельзя принять (must_link) — читается из
// ОНТОЛОГИЯ-ФОРМИРОВАНИЯ. Понятия вне онтологии не существует.
//
// Права службы (reconciliation.rights): предлагает — слить · уточнить ·
// связать · пометить противоречие · принять новым. НЕ вправе: сливать сама,
// менять принятое, выбирать победителя. Поэтому `preview` не трогает ни одной
// принятой сущности, а единственный путь изменения модели — `apply`, и у
// каждого его решения есть автор и причина.
package orbita.knowledge.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry
import orbita.kernel.api.Provenance
import orbita.kernel.schema.GeneratedKinds
import orbita.knowledge.api.Action
import orbita.knowledge.api.Applied
import orbita.knowledge.api.Authority
import orbita.knowledge.api.Candidate
import orbita.knowledge.api.CandidateOrigin
import orbita.knowledge.api.Comparison
import orbita.knowledge.api.Disposition
import orbita.knowledge.api.FactSource
import orbita.knowledge.api.FieldDifference
import orbita.knowledge.api.Finding
import orbita.knowledge.api.Intake
import orbita.knowledge.api.Match
import orbita.knowledge.api.MustLinkMissing
import orbita.knowledge.api.Question
import orbita.knowledge.api.Reconcile
import orbita.knowledge.api.ReconcileItem
import orbita.knowledge.api.ReconcileRun
import orbita.knowledge.api.Verdict
import orbita.knowledge.schema.Concept
import orbita.knowledge.schema.GeneratedOntology
import orbita.library.api.Shelves
import java.security.MessageDigest
import java.time.OffsetDateTime

/**
 * Детерминированная сверка ручного ввода и предложений синтеза.
 *
 * @param intake приём знаний: кандидат-факт заводится ИМ, и диспозицию факта
 *   ставит тоже он. Второй записи фактов в проекте быть не должно — ворота
 *   честности (величина без единицы, роль автора) живут в одном месте
 */
internal class Reconciler(
    private val store: EntityStore,
    private val links: LinkRegistry? = null,
    private val mapper: ObjectMapper = ObjectMapper(),
    private val shelves: Shelves? = null,
    private val intake: Intake = EntityIntake(store, links, mapper, shelves),
) : Reconcile {

    // --- ступень 1: показать, ничего не меняя ------------------------------

    override fun preview(
        project: String,
        candidates: List<Candidate>,
        author: String,
        role: String,
        semantic: Boolean,
    ): ReconcileRun {
        val область = Area.Project(project)
        require(candidates.isNotEmpty()) { "сверять нечего: ни одного кандидата" }
        require(author.isNotBlank()) { "у ввода нет автора: происхождение обязано быть у каждого факта" }
        require(role.isNotBlank()) {
            "роль автора обязательна: источник ручного ввода — учётка · роль · дата, якоря у руки нет"
        }
        val номера = candidates.map { it.localId }
        require(номера.distinct().size == номера.size) {
            "местные номера кандидатов повторяются: находка вернулась бы не в ту строку ввода"
        }

        val ключи = IdentityKeys.of(store, область, shelves)
        val нормализация = Normalize.of(store, область)
        // Ключ ищется по ВСЕМУ полю: он бесплатен, и урезать его срезом значит
        // терять дубли молча. Потолок в 50 записей — у среза второй ступени.
        val факты = store.list(область, "fact").filter { it.status != "cancelled" }

        val записи = candidates.map { кандидат ->
            сверить(project, область, кандидат, author, role, semantic, ключи, нормализация, факты)
        }
        val срез = срезПоля(область, candidates, факты)

        val документ = mapper.createObjectNode()
        документ.put(
            "trigger",
            if (candidates.all { it.origin == CandidateOrigin.SYNTHESIS }) "new_facts" else "manual",
        )
        документ.put("slice_fingerprint", отпечаток(project, candidates, срез))
        документ.put("slice_size", срез.size)
        // Карточек-предложений сверка не заводит: она создаёт только
        // кандидатов-фактов и запись себя самой. Снимок принятых сущностей до
        // и после `preview` обязан совпадать (мера «ни одного слияния без клика»).
        документ.putArray("proposals")
        val диф = документ.putObject("diff")
        Verdict.entries.forEach { диф.putArray(группа(it)) }
        записи.forEach { запись ->
            val вердикт = Verdict.valueOf(запись.path("verdict").asText().uppercase())
            (диф.get(группа(вердикт)) as ArrayNode).add(запись)
        }
        документ.put("ontology_version", GeneratedOntology.ontologyVersion)
        документ.put("cached", false)
        // Вид один на синтез и сверку (истина схем второго не заводит), а
        // различает их метка: без неё список открытых сверок собирал бы заодно
        // и запуски синтеза.
        документ.putArray("tags").add(МЕТКА)
        документ.put("notes", примечание(записи, срез.size))

        val запуск = store.create(
            следующий(область, "synthesis_run", "SR"), "synthesis_run", область,
            // Сцены у запуска нет: сверка идёт по всему полю, а не в сцене.
            null, документ, Provenance(Channel.MANUAL, author), status = "done",
        )
        return вид(запуск)
    }

    override fun run(project: String, run: String): ReconcileRun = вид(запуск(Area.Project(project), run))

    override fun open(project: String): List<ReconcileRun> =
        store.list(Area.Project(project), "synthesis_run")
            .filter { запись -> запись.doc.path("tags").any { it.asText() == МЕТКА } }
            .map { вид(it) }
            .filter { it.open }
            .sortedBy { it.id }

    // --- единственный путь изменения модели --------------------------------

    override fun apply(
        project: String,
        run: String,
        localId: String,
        finding: Int,
        action: Action,
        target: String?,
        reason: String,
        author: String,
    ): Applied {
        val область = Area.Project(project)
        require(author.isNotBlank()) { "решение без автора не ставится: кто решил — часть решения" }
        require(reason.isNotBlank()) {
            "решение без причины не ставится: через год «${action.word}» без объяснения читается как случайность"
        }
        val сохранённый = запуск(область, run)
        val документ = сохранённый.doc.deepCopy<JsonNode>() as ObjectNode
        val запись = записьКандидата(документ, localId)
            ?: throw IllegalArgumentException("кандидата «$localId» в сверке «$run» нет")
        val находки = запись.path("findings")
        require(finding in 0 until находки.size()) {
            "у кандидата «$localId» находки №$finding нет: их ${находки.size()}"
        }
        val находка = находки.get(finding) as ObjectNode
        require(находка.path("offers").any { it.asText() == action.name }) {
            "«${action.word}» этой находке не предлагалось: ${предложенное(находка)}"
        }
        require(!находка.path("decision").isObject) {
            "по этой находке решение уже принято: ${словоДействия(находка.path("decision").path("action").asText())}"
        }

        val итог = выполнить(project, область, запись, находка, action, target, reason, author)

        находка.putObject("decision")
            .put("action", action.name)
            .put("by", author)
            .put("at", OffsetDateTime.now().toString())
            .put("reason", reason)
        // Судьба кандидата решена — строка ввода закрывается; «привязать
        // основание» и «снять находку» судьбы не решают, кандидат ждёт дальше.
        if (action in ТЕРМИНАЛЬНЫЕ) запись.put("decided", action.name)
        store.update(сохранённый.id, документ, Provenance(Channel.MANUAL, author))
        return итог
    }

    private fun выполнить(
        project: String,
        область: Area,
        запись: ObjectNode,
        находка: JsonNode,
        action: Action,
        target: String?,
        reason: String,
        author: String,
    ): Applied {
        val понятие = GeneratedOntology.of(запись.path("concept").asText())
        val цель = (target?.trim()?.ifBlank { null }) ?: находка.path("target").asText("").ifBlank { null }
        return when (action) {
            Action.ACCEPT_NEW -> завести(project, область, запись, понятие, target, reason, author)
            Action.REFINE -> завести(project, область, запись, понятие, target, reason, author, уточняет = цель)
            Action.GENERALIZE -> завести(project, область, запись, понятие, target, reason, author, обобщает = цель)
            Action.MERGE_INTO -> слить(project, область, запись, цель, reason, author)
            Action.LINK_BASIS -> привязать(project, область, запись, находка, reason, author)
            Action.MARK_CONTESTED -> оспорить(project, область, запись, находка, цель, reason, author)
            Action.FIX_INPUT -> исправить(project, запись, reason, author)
            Action.DISMISS -> Applied(
                emptyList(), emptyList(), emptyList(), emptyList(),
                "находка снята: $reason — в модели не изменилось ничего",
            )
        }
    }

    /**
     * Завести понятие сущностью. Отказывает, пока обязательная связь не
     * закрыта: предложение с незакрытым `must_link` — предложение с пометой,
     * а не сущность (инвариант онтологии).
     *
     * @param уточняет код понятия, которое заведённое уточняет (`refines`)
     * @param обобщает код понятия, которое становится уточнением заведённого
     */
    private fun завести(
        project: String,
        область: Area,
        запись: ObjectNode,
        понятие: Concept,
        target: String?,
        reason: String,
        author: String,
        уточняет: String? = null,
        обобщает: String? = null,
    ): Applied {
        val вид = видПонятия(понятие.code) ?: error(
            "понятие «${словоПонятия(понятие.code)}» вида в истине схем не имеет: " +
                "заводить вид в коде запрещено — решение за владельцем файла схем",
        )
        val снимок = запись.path("payload").deepCopy<JsonNode>() as ObjectNode
        // Цель действия закрывает нехватку: «выбрать сторону» — это и есть
        // выбор человека, которого ждала блокирующая находка.
        закрытьНехватку(область, понятие, снимок, target)
        нехватка(область, понятие, снимок).firstOrNull()?.let { пробел ->
            throw MustLinkMissing(
                пробел.missing.orEmpty(),
                пробел.difference?.reason.orEmpty().ifBlank {
                    "обязательная связь понятия не закрыта — сущности не будет"
                },
            )
        }

        val областьЗаписи = куда(область, вид)
        val документ = снимок.deepCopy() as ObjectNode
        // Код выдаёт система: код записи — её адрес, а не поле ввода. Свой код
        // остаётся только там, где он часть содержания (рамка «Р-N»).
        val код = документ.path("code").asText("").trim()
            .ifBlank { следующий(областьЗаписи, вид, префикс(вид)) }
        документ.remove(listOf("code", "author", "project", "owner"))
        val канал = if (запись.path("origin").asText() == CandidateOrigin.SYNTHESIS.name) {
            Channel.SERVICE
        } else {
            Channel.MANUAL
        }
        val кандидат = факт(область, запись) ?: error("кандидат-факт «${запись.path("candidate_fact").asText()}» потерян")
        val сущность = store.create(
            код, вид, областьЗаписи, сцена(вид), документ,
            Provenance(канал, author, source = кандидат.code),
        )

        val связи = mutableListOf<String>()
        // Нить к основанию: по ней считается доля знаний и видно, откуда в
        // проекте взялось это утверждение.
        links?.link(
            "derived_from_fact", сущность.id, кандидат.id,
            Provenance(канал, author), rationale = "принято сверкой: $reason",
        )?.let { связи += it.type }
        связи += обязательныеСвязи(область, понятие, снимок, сущность, канал, author)
        уточняет?.let { кодОбщего ->
            сущностьПоИмени(область, null, кодОбщего)?.let { цель ->
                links?.link(
                    "refines", сущность.id, цель.id, Provenance(канал, author),
                    rationale = "уточнение принято сверкой: $reason",
                )?.let { связи += it.type }
            }
        }
        обобщает?.let { кодЧастного ->
            сущностьПоИмени(область, null, кодЧастного)?.let { частное ->
                // Обобщение не переписывает частное: оно остаётся жить и
                // становится уточнением нового — это и просил владелец
                // («обобщить N2 и N5, оставить их уточнениями»).
                links?.link(
                    "refines", частное.id, сущность.id, Provenance(канал, author),
                    rationale = "обобщено сверкой: $reason",
                )?.let { связи += it.type }
            }
        }
        intake.dispose(project, кандидат.code, Disposition.ADOPTED, reason, author)
        return Applied(
            created = listOf(сущность.code),
            updated = emptyList(),
            links = связи,
            facts = listOf(кандидат.code),
            note = "заведено: ${сущность.code} (${словоПонятия(понятие.code)}), основание — ${кандидат.code}",
        )
    }

    /**
     * Слить кандидата с принятым. Цель НЕ переписывается: слияние привязывает
     * кандидат-факт основанием, а поля принятого остаются как есть — принятое
     * не меняется молча, даже по клику «слить».
     */
    private fun слить(
        project: String,
        область: Area,
        запись: ObjectNode,
        цель: String?,
        reason: String,
        author: String,
    ): Applied {
        val код = цель?.trim().orEmpty()
        require(код.isNotBlank()) { "слить не с чем: назовите принятую сущность" }
        val принятая = сущностьПоИмени(область, null, код)
            ?: throw IllegalArgumentException("«$код» в проекте не найдено: слить не с чем")
        val кандидат = факт(область, запись) ?: error("кандидат-факт потерян")
        val связи = mutableListOf<String>()
        links?.link(
            "derived_from_fact", принятая.id, кандидат.id, Provenance(Channel.MANUAL, author),
            rationale = "слито сверкой: $reason",
        )?.let { связи += it.type }
        intake.dispose(project, кандидат.code, Disposition.ADOPTED, reason, author)
        return Applied(
            created = emptyList(),
            updated = emptyList(),
            links = связи,
            facts = listOf(кандидат.code),
            note = "кандидат привязан основанием к ${принятая.code}; поля принятого не изменены",
        )
    }

    /**
     * Привязать основания: факты независимого источника, сказавшие то же
     * самое. Обоим поднимается `evidence` до `corroborated` — подтверждение
     * идёт в обе стороны, а не только кандидату.
     */
    private fun привязать(
        project: String,
        область: Area,
        запись: ObjectNode,
        находка: JsonNode,
        reason: String,
        author: String,
    ): Applied {
        val кандидат = факт(область, запись) ?: error("кандидат-факт потерян")
        val основания = находка.path("basis").map { it.asText() }.filter { it.isNotBlank() }
        require(основания.isNotEmpty()) { "привязывать нечего: находка не назвала ни одного факта-основания" }
        val связи = mutableListOf<String>()
        val изменённые = mutableListOf<String>()
        основания.forEach { код ->
            val основание = store.byCode(область, код)?.takeIf { it.kind == "fact" } ?: return@forEach
            links?.link(
                "supports", основание.id, кандидат.id, Provenance(Channel.MANUAL, author),
                rationale = "основание подтверждено сверкой: $reason",
            )?.let { связи += it.type }
            изменённые += подтвердить(основание, author)
        }
        изменённые += подтвердить(кандидат, author)
        return Applied(
            created = emptyList(), updated = emptyList(), links = связи, facts = изменённые,
            note = "основания привязаны: ${основания.joinToString(" · ")}; обоим концам поднято подтверждение",
        )
    }

    /** Подтверждение факта независимым источником: `claimed` → `corroborated`. */
    private fun подтвердить(факт: Entity, author: String): String {
        val документ = факт.doc.deepCopy<JsonNode>() as ObjectNode
        документ.put("evidence", "corroborated")
        store.update(факт.id, документ, Provenance(Channel.MANUAL, author))
        return факт.code
    }

    /**
     * Оспорить принятое: сущность остаётся, кандидат становится `contested`,
     * у сущности появляется помета «оспорено экспертом». Победителя не
     * выбирает никто — ни ранг, ни служба.
     */
    private fun оспорить(
        project: String,
        область: Area,
        запись: ObjectNode,
        находка: JsonNode,
        цель: String?,
        reason: String,
        author: String,
    ): Applied {
        val кандидат = факт(область, запись) ?: error("кандидат-факт потерян")
        val принятая = цель?.let { сущностьПоИмени(область, null, it) }
        intake.dispose(project, кандидат.code, Disposition.CONTESTED, reason, author)
        val связи = mutableListOf<String>()
        val изменённые = mutableListOf<String>()
        принятая?.let { сущность ->
            val документ = сущность.doc.deepCopy<JsonNode>() as ObjectNode
            документ.put(
                "notes",
                "оспорено экспертом: $author, ${OffsetDateTime.now().toLocalDate()} — $reason; " +
                    отличиеСловами(находка),
            )
            store.update(сущность.id, документ, Provenance(Channel.MANUAL, author))
            изменённые += сущность.code
            links?.link(
                "contradicts", кандидат.id, сущность.id, Provenance(Channel.MANUAL, author),
                rationale = отличиеСловами(находка) + " — показаны оба, победителя выбирает человек",
            )?.let { связи += it.type }
        }
        return Applied(
            created = emptyList(), updated = изменённые, links = связи, facts = listOf(кандидат.code),
            note = "кандидат помечен спорным; значение принятого не изменено",
        )
    }

    /** Ввод исправляется человеком: кандидат снят, в модели не изменилось ничего. */
    private fun исправить(project: String, запись: ObjectNode, reason: String, author: String): Applied {
        val код = запись.path("candidate_fact").asText()
        intake.dispose(project, код, Disposition.REJECTED, reason, author)
        return Applied(
            created = emptyList(), updated = emptyList(), links = emptyList(), facts = listOf(код),
            note = "ввод отправлен на исправление: $reason",
        )
    }

    // --- четыре вопроса ----------------------------------------------------

    private fun сверить(
        project: String,
        область: Area,
        кандидат: Candidate,
        author: String,
        role: String,
        semantic: Boolean,
        ключи: IdentityKeys,
        нормализация: Normalize,
        факты: List<Entity>,
    ): ObjectNode {
        val понятие = GeneratedOntology.of(кандидат.concept)
        val снимок = снимокКандидата(область, понятие, кандидат.payload)
        val кандФакт = кандидатФакт(project, понятие, снимок, author, role)
        val пометы = mutableListOf<String>()

        val ключ = ключи.ofConcept(кандидат.concept, снимок)
        val близнец = ключ?.let { найти(область, понятие, ключи, it.value) }
        val находки = mutableListOf<Finding>()
        находки += if (близнец == null) {
            // Вопрос задан и отвечен: дубля по ключу нет. Находка нужна и в
            // этом случае — иначе человеку не на что нажать, чтобы принять.
            Finding(
                question = Question.DUPLICATE,
                verdict = Verdict.NEW,
                match = Match.KEY,
                comparedFields = ключ?.fields ?: emptyList(),
                offers = listOf(Action.ACCEPT_NEW, Action.DISMISS),
            )
        } else {
            Finding(
                question = Question.DUPLICATE,
                verdict = Verdict.AUGMENT,
                target = близнец.code,
                match = Match.KEY,
                comparedFields = ключ!!.fields,
                difference = отличиеПолей(понятие, снимок, снимокСущности(область, понятие, близнец), ключ.fields),
                basis = основанияСущности(близнец),
                offers = listOf(
                    Action.MERGE_INTO, Action.REFINE, Action.GENERALIZE, Action.ACCEPT_NEW, Action.DISMISS,
                ),
            )
        }
        if (ключ == null && semantic) {
            // Ключ не сложился — вопрос о дубле уходит на вторую ступень;
            // здесь она не зовётся, и журнал ИИ не прирастает.
            пометы += "ключ идентичности не сложился: вопрос о дубле — второй ступени"
        }

        близнец?.let { принятая ->
            противоречия(область, понятие, снимок, принятая, нормализация).forEach { находка ->
                // Несравнимое — не противоречие: два разных показателя не
                // спорят друг с другом. Причина остаётся словами в помете.
                if (находка.difference?.comparison == Comparison.INCOMPARABLE) {
                    пометы += "не сравнимо по полю «${находка.difference!!.field}»: ${находка.difference!!.reason}"
                } else {
                    находки += находка
                }
            }
            текстовыеПоля(понятие).forEach { поле ->
                пометы += "противоречие по полю «$поле» проверяется второй ступенью: текст числом не сравнивается"
            }
        }
        находки += соединения(область, понятие, снимок, кандФакт.id, ключи, факты)
        val пробелы = нехватка(область, понятие, снимок)
        находки += пробелы

        val запись = mapper.createObjectNode()
        запись.put("local_id", кандидат.localId)
        запись.put("concept", кандидат.concept)
        запись.put("origin", кандидат.origin.name)
        запись.put("candidate_fact", кандФакт.id)
        запись.put("authority", кандФакт.authority ?: Authority.EXPERT)
        (кандФакт.source as? FactSource.FromExpert)?.let { источник ->
            запись.putObject("source")
                .put("account", источник.account).put("role", источник.role).put("at", источник.at)
        }
        запись.set<JsonNode>("payload", снимок)
        запись.put("verdict", вердикт(находки).name.lowercase())
        val массив = запись.putArray("findings")
        находки.forEach { массив.add(вЗапись(it)) }
        val блокирующие = запись.putArray("blocking")
        пробелы.mapNotNull { it.missing }.forEach { блокирующие.add(it) }
        запись.put("note", пометы.joinToString("; "))
        return запись
    }

    /**
     * Кандидат-факт: ручной ввод — источник, равный документу по механике и
     * отличный рангом. Заводится приёмом знаний, чтобы ворота честности
     * (величина без единицы, роль автора) остались в одном месте.
     */
    private fun кандидатФакт(
        project: String,
        понятие: Concept,
        снимок: ObjectNode,
        author: String,
        role: String,
    ): orbita.knowledge.api.Fact {
        val текст = текстКандидата(снимок)
        require(текст.isNotBlank()) {
            "кандидат «${словоПонятия(понятие.code)}» без формулировки: сверять нечего"
        }
        val величина = величинаПонятия(снимок)
        val единица = величина?.path("unit")?.asText("")?.ifBlank { null }
        // Субъект — то, чему утверждение принадлежит: у нужды это сторона
        // (ссылочное поле онтологии). У понятия без ссылки субъект и значение
        // совпадают: кандидат-факт говорит «вот это внесено», и придумывать
        // ему другой субъект значит сочинять за эксперта.
        val субъект = ссылочноеЗначение(понятие, снимок) ?: текст
        return intake.addFact(
            project = project,
            subject = субъект,
            predicate = предикатПонятия(понятие),
            value = if (единица != null) значениеВеличины(величина!!) else текст,
            unit = единица,
            kind = if (единица != null) "quantity" else видФакта(понятие),
            topic = null,
            material = null,
            author = author,
            mark = "И",
            role = role,
            authority = Authority.EXPERT,
        )
    }

    /** Дубль по ключу: то же понятие с тем же ключом идентичности среди принятых. */
    private fun найти(область: Area, понятие: Concept, ключи: IdentityKeys, ключ: String): Entity? {
        val вид = видПонятия(понятие.code) ?: return null
        return store.list(куда(область, вид), вид)
            .filter { it.status != "cancelled" }
            .firstOrNull { принятая ->
                ключи.ofConcept(понятие.code, снимокСущности(область, понятие, принятая))?.value == ключ
            }
    }

    /**
     * Противоречие: различие в полях `conflict_on` после приведения единиц и
     * горизонтов к канону. Показываются ОБА значения с рангами; поля
     * «победитель» здесь нет и быть не может.
     */
    private fun противоречия(
        область: Area,
        понятие: Concept,
        снимок: ObjectNode,
        принятая: Entity,
        нормализация: Normalize,
    ): List<Finding> {
        val принятый = снимокСущности(область, понятие, принятая)
        val ихРанг = Authority.word(рангСущности(область, принятая)).ifBlank { "без ранга" }
        val мойРанг = Authority.word(Authority.EXPERT)
        return понятие.conflictOn.mapNotNull { токен ->
            val поле = токен.trim()
            // «statement contradicts» — текстовое противоречие: числом оно не
            // сравнивается, его смотрит вторая ступень.
            if (поле.isBlank() || поле.contains(" ")) return@mapNotNull null
            val моё = снимок.path(поле)
            val чужое = принятый.path(поле)
            if (пусто(моё) || пусто(чужое)) return@mapNotNull null
            val отличие = when {
                поле == "year" -> нормализация.сравнитьГоризонт(поле, моё, чужое)
                моё.isObject || чужое.isObject -> нормализация.сравнить(поле, моё, чужое)
                else -> строкой(поле, моё.asText(""), чужое.asText(""))
            }
            if (отличие.kind == Difference.EQUAL) return@mapNotNull null
            val сравнение =
                if (отличие.kind == Difference.INCOMPARABLE) Comparison.INCOMPARABLE else Comparison.DIFFERS
            Finding(
                question = Question.CONTRADICTION,
                verdict = Verdict.CONTRADICT,
                target = принятая.code,
                match = Match.KEY,
                comparedFields = listOf(поле),
                difference = FieldDifference(
                    comparison = сравнение,
                    field = поле,
                    mine = отличие.mine,
                    theirs = отличие.theirs,
                    // Оба значения с рангами — и ни слова о победителе: ранг
                    // подсказывает, решает человек.
                    reason = отличие.reason.ifBlank { "значения расходятся" } +
                        " — «${отличие.mine}» ($мойРанг) против «${отличие.theirs}» ($ихРанг); " +
                        "показаны оба, победителя выбирает человек",
                ),
                basis = основанияСущности(принятая),
                offers = listOf(Action.MARK_CONTESTED, Action.FIX_INPUT, Action.DISMISS),
            )
        }
    }

    /**
     * Соединение: факты поля, сказавшие то же самое. Совпадение формулировки
     * с фактом НЕЗАВИСИМОГО источника — это подтверждение, а не дубль
     * понятия: обоим фактам поднимается `evidence`.
     */
    private fun соединения(
        область: Area,
        понятие: Concept,
        снимок: ObjectNode,
        кандидат: String,
        ключи: IdentityKeys,
        факты: List<Entity>,
    ): List<Finding> {
        val ядро = ключи.statementCore(текстКандидата(снимок)).value
        if (ядро.isBlank()) return emptyList()
        val основания = факты
            .filter { it.code != кандидат }
            // Основание — факт ДОКУМЕНТА: подтверждает независимый источник, а
            // не вторая рука того же эксперта. Документальный факт узнаётся
            // якорем: якорь есть место в документе, и у руки его нет.
            .filter { it.doc.path("anchor").asText("").isNotBlank() }
            .filter { ключи.statementCore(it.doc.path("value").asText("")).value == ядро }
        if (основания.isEmpty()) return emptyList()
        val первое = основания.first()
        return listOf(
            Finding(
                question = Question.CONNECTION,
                verdict = Verdict.CONFIRM,
                target = первое.code,
                match = Match.KEY,
                // Совпали ОСНОВЫ формулировки, а не буква: уверенность — тот
                // самый порог близости смысла, который назвала онтология
                // понятию; своего числа здесь не заводится.
                confidence = понятие.identity.threshold,
                comparedFields = listOf("statement"),
                difference = FieldDifference(
                    Comparison.EQUAL, "statement",
                    текстКандидата(снимок), первое.doc.path("value").asText(""),
                    "то же утверждение в ${первое.doc.path("material").asText("")} " +
                        "(${Authority.word(рангФакта(область, первое))}) — основание к привязке",
                ),
                basis = основания.map { it.code },
                offers = listOf(Action.LINK_BASIS, Action.DISMISS),
            ),
        )
    }

    /**
     * Нехватка: обязательная связь понятия (`must_link`) не закрыта. Пока она
     * открыта, сущности не будет — предложение живёт пометой.
     */
    private fun нехватка(область: Area, понятие: Concept, снимок: JsonNode): List<Finding> =
        обязательства(понятие).mapNotNull { обязательство ->
            if (!подходит(снимок, обязательство)) return@mapNotNull null
            val названные = имена(снимок, обязательство.поле)
            val закрыто = if (обязательство.вид == null) {
                названные.size
            } else {
                названные.count { сущностьПоИмени(область, обязательство.вид, it) != null }
            }
            if (закрыто >= обязательство.минимум) return@mapNotNull null
            val чего = обязательство.вид?.let { словоПонятия(it) } ?: обязательство.поле
            Finding(
                question = Question.GAP,
                verdict = Verdict.NEW,
                match = Match.KEY,
                comparedFields = listOf(обязательство.поле),
                // Сравнивать было не с чем — отличие здесь называет не спор, а
                // чего именно не хватает и что человеку выбрать.
                difference = FieldDifference(
                    Comparison.INCOMPARABLE, обязательство.поле, названные.joinToString(" · "), "",
                    "${словоПонятия(понятие.code)} без «$чего»: обязательная связь не закрыта — выберите $чего",
                ),
                offers = listOf(Action.FIX_INPUT, Action.DISMISS),
                blocking = true,
                missing = обязательство.token,
            )
        }

    // --- разбор правил онтологии -------------------------------------------

    /**
     * Обязательная связь понятия из онтологии: «owns→stakeholder»,
     * «covers→need>=1», «qos_class», «normative_basis if obligation».
     *
     * @property поле поле кандидата, которым связь называется; берётся из
     *   `fields` того же понятия — второго перечня в коде нет
     * @property условие связь обязательна только при таком источнике факта
     */
    private data class Обязательство(
        val token: String,
        val связь: String?,
        val вид: String?,
        val поле: String,
        val минимум: Int,
        val многие: Boolean,
        val условие: String?,
    )

    private fun обязательства(понятие: Concept): List<Обязательство> = понятие.mustLink.mapNotNull { токен ->
        val текст = токен.trim()
        if (текст.isBlank()) return@mapNotNull null
        val условие = текст.substringAfter(" if ", "").trim().ifBlank { null }
        val голова = текст.substringBefore(" if ").trim()
        if (!голова.contains("→")) {
            return@mapNotNull Обязательство(текст, null, null, голова, 1, false, условие)
        }
        val связь = голова.substringBefore("→").trim()
        val хвост = голова.substringAfter("→").trim()
        val вид = хвост.substringBefore(">=").trim()
        val многие = хвост.contains(">=")
        val минимум = хвост.substringAfter(">=", "1").trim().toIntOrNull() ?: 1
        Обязательство(текст, связь, вид, полеСвязи(понятие, связь, вид), минимум, многие, условие)
    }

    /**
     * Каким полем кандидата называется связь. Истина — `fields` понятия:
     * онтология сама говорит, что нужда несёт сторону полем `stakeholder`
     * («subject (owns) — обязательно»), а цель — нужды полем `needs`
     * («covers ≥1»).
     */
    private fun полеСвязи(понятие: Concept, связь: String, вид: String): String =
        понятие.fields.entries.firstOrNull { it.value.contains(связь) }?.key
            ?: понятие.fields.keys.firstOrNull { it == вид || it == "${вид}s" }
            ?: вид

    /**
     * Условие обязательности («normative_basis if obligation»): онтология
     * сама сопоставляет источник и тип — «obligation→regulatory».
     */
    private fun подходит(снимок: JsonNode, обязательство: Обязательство): Boolean {
        val условие = обязательство.условие ?: return true
        val тип = снимок.path("type").asText("").trim()
        val вид = снимок.path("kind").asText("").trim()
        return вид == условие || (тип == "regulatory" && условие == "obligation")
    }

    /** Поля `conflict_on`, которые числом не сравниваются: их смотрит вторая ступень. */
    private fun текстовыеПоля(понятие: Concept): List<String> =
        понятие.conflictOn.map { it.trim() }.filter { it.contains(" ") }

    // --- снимки и значения -------------------------------------------------

    /**
     * Снимок кандидата: ссылочные поля сводятся к КОДАМ сущностей.
     *
     * Эксперт называет сторону по-человечески («Минтранс России»), а ключ
     * идентичности нужды держится на её коде: без приведения тот же ввод
     * второй раз не узнал бы сам себя.
     */
    private fun снимокКандидата(область: Area, понятие: Concept, payload: JsonNode): ObjectNode {
        val снимок = payload.deepCopy<JsonNode>() as ObjectNode
        обязательства(понятие).forEach { обязательство ->
            val вид = обязательство.вид ?: return@forEach
            val названные = имена(снимок, обязательство.поле)
            val коды = названные.mapNotNull { сущностьПоИмени(область, вид, it)?.code }
            if (коды.isEmpty()) return@forEach
            if (обязательство.многие) {
                val массив = снимок.putArray(обязательство.поле)
                коды.forEach { массив.add(it) }
            } else {
                снимок.put(обязательство.поле, коды.first())
            }
        }
        return снимок
    }

    /**
     * Снимок принятой сущности для сравнения: документ плюс поля, которые в
     * модели живут СВЯЗЬЮ. Нужда носит сторону связью `owns`, и без этого
     * дополнения её ключ не сложился бы вовсе.
     */
    private fun снимокСущности(область: Area, понятие: Concept, сущность: Entity): ObjectNode {
        val снимок = сущность.doc.deepCopy<JsonNode>() as ObjectNode
        обязательства(понятие).forEach { обязательство ->
            val связь = обязательство.связь ?: return@forEach
            if (имена(снимок, обязательство.поле).isNotEmpty()) return@forEach
            val реестр = links ?: return@forEach
            val соседи = (реестр.to(сущность.id, связь).map { it.from } +
                реестр.from(сущность.id, связь).map { it.to })
                .mapNotNull { store.byId(it) }
                .filter { обязательство.вид == null || it.kind == обязательство.вид }
            if (соседи.isEmpty()) return@forEach
            if (обязательство.многие) {
                val массив = снимок.putArray(обязательство.поле)
                соседи.forEach { массив.add(it.code) }
            } else {
                снимок.put(обязательство.поле, соседи.first().code)
            }
        }
        return снимок
    }

    /** Формулировка кандидата — то, что написал человек, дословно. */
    private fun текстКандидата(снимок: JsonNode): String =
        listOf("statement", "name", "designation", "label")
            .map { снимок.path(it).asText("").trim() }
            .firstOrNull { it.isNotBlank() }.orEmpty()

    /** Величина кандидата: показатель цели либо граница рамки. */
    private fun величинаПонятия(снимок: JsonNode): JsonNode? =
        listOf("measure", "bound", "target_measure")
            .map { снимок.path(it) }
            .firstOrNull { it.isObject && it.path("unit").asText("").isNotBlank() }

    private fun значениеВеличины(величина: JsonNode): String =
        if (величина.path("value").isNumber) величина.path("value").asText()
        else величина.path("value").asText("").trim()

    /** Значение ссылочного поля понятия (нужда → сторона); null — ссылки нет. */
    private fun ссылочноеЗначение(понятие: Concept, снимок: JsonNode): String? =
        обязательства(понятие).firstOrNull { it.связь != null && !it.многие }
            ?.let { имена(снимок, it.поле).firstOrNull() }

    /**
     * Утверждение кандидата-факта. Предикат берётся из онтологии («нуждается
     * в», «к году»); у понятия, которому онтология предикатов не назвала,
     * предикатом становится его же имя словами — выдумывать свой нельзя.
     */
    private fun предикатПонятия(понятие: Concept): String =
        понятие.fromFacts.firstNotNullOfOrNull { it.predicateIn.firstOrNull() } ?: словоПонятия(понятие.code)

    /** Вид факта — тот, из которого понятие образуется (from_facts онтологии). */
    private fun видФакта(понятие: Concept): String =
        понятие.fromFacts.firstNotNullOfOrNull { it.kind } ?: "framing"

    private fun имена(снимок: JsonNode, поле: String): List<String> {
        val узел = снимок.path(поле)
        return when {
            узел.isArray -> узел.map { it.asText("").trim() }.filter { it.isNotBlank() }
            узел.isTextual -> listOf(узел.asText().trim()).filter { it.isNotBlank() }
            else -> emptyList()
        }
    }

    private fun пусто(узел: JsonNode): Boolean =
        узел.isMissingNode || узел.isNull || (узел.isTextual && узел.asText().isBlank())

    /** Сравнение текстовых значений: регистр и пробелы спора не заводят. */
    private fun строкой(поле: String, моё: String, чужое: String): Difference =
        if (моё.trim().lowercase() == чужое.trim().lowercase()) {
            Difference(Difference.EQUAL, поле, моё, чужое)
        } else {
            Difference(Difference.DIFFERS, поле, моё, чужое, "значения расходятся")
        }

    /** Что у кандидата отличается от принятого — по полям ключа и соседним. */
    private fun отличиеПолей(
        понятие: Concept,
        снимок: JsonNode,
        принятый: JsonNode,
        поля: List<String>,
    ): FieldDifference {
        val новые = понятие.fields.keys.filter { поле ->
            !пусто(снимок.path(поле)) && пусто(принятый.path(поле))
        }
        val поле = поля.firstOrNull().orEmpty()
        return FieldDifference(
            comparison = if (новые.isEmpty()) Comparison.EQUAL else Comparison.DIFFERS,
            field = поле,
            mine = текстКандидата(снимок),
            theirs = текстКандидата(принятый),
            reason = if (новые.isEmpty()) {
                "совпало по ключу (${поля.joinToString(" · ")}); нового у кандидата нет"
            } else {
                "совпало по ключу (${поля.joinToString(" · ")}); у кандидата есть ${новые.joinToString(" · ")}"
            },
        )
    }

    private fun отличиеСловами(находка: JsonNode): String {
        val отличие = находка.path("difference")
        val поле = отличие.path("field").asText("")
        return "$поле: «${отличие.path("mine").asText("")}» против «${отличие.path("theirs").asText("")}»"
    }

    // --- ранги, коды, срез -------------------------------------------------

    /** Ранг сущности — весомейший ранг среди её фактов-оснований. */
    private fun рангСущности(область: Area, сущность: Entity): String {
        val реестр = links ?: return ""
        return реестр.from(сущность.id, "derived_from_fact")
            .mapNotNull { store.byId(it.to) }
            .filter { it.kind == "fact" }
            .map { рангФакта(область, it) }
            .filter { Authority.known(it) }
            .minByOrNull { Authority.weight(it) }
            .orEmpty()
    }

    /** Ранг факта: свой, а если разбор его не проставил — ранг материала. */
    private fun рангФакта(область: Area, факт: Entity): String {
        val свой = факт.doc.path("authority").asText("")
        if (Authority.known(свой)) return свой
        val материал = факт.doc.path("material").asText("").ifBlank { return "" }
        val карточка = store.byCode(область, материал) ?: return ""
        return карточка.doc.path("authority").asText("").takeIf { Authority.known(it) }.orEmpty()
    }

    private fun основанияСущности(сущность: Entity): List<String> =
        links?.from(сущность.id, "derived_from_fact")
            ?.mapNotNull { store.byId(it.to)?.takeIf { факт -> факт.kind == "fact" }?.code }
            .orEmpty()

    /**
     * Срез поля для отпечатка и второй ступени. Потолок назван истиной
     * онтологии (reconciliation.batching) — второй копии числа в коде нет;
     * урезание идёт по рангу, весомое остаётся.
     */
    private fun срезПоля(область: Area, candidates: List<Candidate>, факты: List<Entity>): List<Entity> {
        val виды = candidates.mapNotNull { видПонятия(it.concept) }.distinct()
        val сущности = виды.flatMap { store.list(куда(область, it), it) }
        return (сущности + факты)
            .sortedWith(compareBy<Entity>({ Authority.weight(рангФакта(область, it)) }, { it.code }))
            .take(ПОТОЛОК)
    }

    /**
     * Отпечаток среза: тот же срез при том же вводе даёт тот же отпечаток —
     * по нему вторая ступень берёт готовый ответ вместо нового вызова.
     */
    private fun отпечаток(project: String, candidates: List<Candidate>, срез: List<Entity>): String {
        val слепок = buildString {
            append(project).append('\n')
            append(GeneratedOntology.ontologyVersion).append('\n')
            candidates.sortedBy { it.localId }.forEach {
                append(it.localId).append(':').append(it.concept).append(':').append(it.payload.toString()).append('\n')
            }
            срез.sortedBy { it.code }.forEach { append(it.code).append(':').append(it.version).append('\n') }
        }
        return MessageDigest.getInstance("SHA-256").digest(слепок.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun следующий(область: Area, вид: String, префикс: String): String {
        val занято = store.list(область, вид).mapNotNull {
            Regex("^$префикс-(\\d+)$").find(it.code)?.groupValues?.get(1)?.toIntOrNull()
        }
        return "%s-%04d".format(префикс, (занято.maxOrNull() ?: 0) + 1)
    }

    /** Префикс кода по виду — тот же, что у приёма знаний: человеку код говорит, что перед ним. */
    private fun префикс(вид: String): String = when (вид) {
        "stakeholder" -> "SK"
        "need" -> "ND"
        "goal" -> "MG"
        "constraint" -> "Р"
        "service" -> "SV"
        "normative_document" -> "NR"
        else -> вид.take(2).uppercase()
    }

    /** Норматив общий для всех проектов: он живёт на полке, а не в проекте. */
    private fun куда(область: Area, вид: String): Area =
        if (вид == "normative_document") Area.Library else область

    /** Сцена рождения — по истине схем; у видов полки сцены нет. */
    private fun сцена(вид: String): String? =
        GeneratedKinds.byCode[вид]?.bornIn?.split(",", "/")?.firstOrNull()?.trim()
            ?.takeIf { it.matches(Regex("[0-9]+")) }

    /** Вид сущности понятия; null — вида в истине схем нет (допущение, этап). */
    private fun видПонятия(понятие: String): String? = GeneratedKinds.byCode[понятие]?.code

    private fun сущностьПоИмени(область: Area, вид: String?, имя: String): Entity? {
        val искомое = имя.trim()
        if (искомое.isBlank()) return null
        val где = вид?.let { куда(область, it) } ?: область
        val поКоду = store.byCode(где, искомое) ?: store.byCode(область, искомое)
            ?: store.byCode(Area.Library, искомое)
        if (поКоду != null && (вид == null || поКоду.kind == вид)) return поКоду
        val список = if (вид == null) store.list(область) else store.list(где, вид)
        return список.firstOrNull { it.doc.path("name").asText("").trim().equals(искомое, ignoreCase = true) }
    }

    private fun факт(область: Area, запись: JsonNode): Entity? =
        store.byCode(область, запись.path("candidate_fact").asText())?.takeIf { it.kind == "fact" }

    private fun запуск(область: Area, код: String): Entity =
        store.byCode(область, код)?.takeIf { it.kind == "synthesis_run" }
            ?: throw IllegalArgumentException("сверки «$код» в проекте нет")

    private fun записьКандидата(документ: JsonNode, localId: String): ObjectNode? =
        Verdict.entries.asSequence()
            .flatMap { документ.path("diff").path(группа(it)).asSequence() }
            .firstOrNull { it.path("local_id").asText() == localId } as? ObjectNode

    private fun группа(вердикт: Verdict): String = вердикт.name.lowercase()

    private fun вердикт(находки: List<Finding>): Verdict = when {
        // Противоречие важнее дополнения: оно останавливает человека, а
        // дополнение только предлагает.
        находки.any { it.verdict == Verdict.CONTRADICT } -> Verdict.CONTRADICT
        находки.any { it.verdict == Verdict.AUGMENT } -> Verdict.AUGMENT
        находки.any { it.verdict == Verdict.CONFIRM } -> Verdict.CONFIRM
        else -> Verdict.NEW
    }

    private fun примечание(записи: List<ObjectNode>, срез: Int): String {
        val находки = записи.flatMap { it.path("findings").toList() }
        fun сколько(вопрос: Question) = находки.count { it.path("question").asText() == вопрос.name }
        val дубли = находки.count {
            it.path("question").asText() == Question.DUPLICATE.name && it.path("target").asText("").isNotBlank()
        }
        return "сверка ступени 1 без вызова службы: кандидатов ${записи.size}, " +
            "дублей по ключу $дубли, " +
            "противоречий ${сколько(Question.CONTRADICTION)}, " +
            "оснований ${сколько(Question.CONNECTION)}, " +
            "нехваток ${сколько(Question.GAP)}; срез поля $срез"
    }

    // --- запись и чтение находок -------------------------------------------

    private fun вЗапись(находка: Finding): ObjectNode {
        val узел = mapper.createObjectNode()
        узел.put("question", находка.question.name)
        узел.put("verdict", находка.verdict.name)
        находка.target?.let { узел.put("target", it) }
        узел.put("match", находка.match.name)
        узел.put("confidence", находка.confidence)
        val поля = узел.putArray("compared_fields")
        находка.comparedFields.forEach { поля.add(it) }
        находка.difference?.let { отличие ->
            узел.putObject("difference")
                .put("comparison", отличие.comparison.name)
                .put("field", отличие.field)
                .put("mine", отличие.mine)
                .put("theirs", отличие.theirs)
                .put("reason", отличие.reason)
        }
        val основания = узел.putArray("basis")
        находка.basis.forEach { основания.add(it) }
        val действия = узел.putArray("offers")
        находка.offers.forEach { действия.add(it.name) }
        узел.put("blocking", находка.blocking)
        находка.missing?.let { узел.put("missing", it) }
        return узел
    }

    private fun изЗаписи(узел: JsonNode): Finding = Finding(
        question = Question.valueOf(узел.path("question").asText()),
        verdict = Verdict.valueOf(узел.path("verdict").asText()),
        target = узел.path("target").asText("").ifBlank { null },
        match = Match.valueOf(узел.path("match").asText(Match.KEY.name)),
        confidence = узел.path("confidence").asDouble(1.0),
        comparedFields = узел.path("compared_fields").map { it.asText() },
        difference = узел.path("difference").takeIf { it.isObject }?.let {
            FieldDifference(
                Comparison.valueOf(it.path("comparison").asText()),
                it.path("field").asText(""),
                it.path("mine").asText(""),
                it.path("theirs").asText(""),
                it.path("reason").asText(""),
            )
        },
        basis = узел.path("basis").map { it.asText() },
        offers = узел.path("offers").map { Action.valueOf(it.asText()) },
        blocking = узел.path("blocking").asBoolean(false),
        missing = узел.path("missing").asText("").ifBlank { null },
    )

    private fun вид(запуск: Entity): ReconcileRun {
        val предметы = Verdict.entries.flatMap { вердикт ->
            запуск.doc.path("diff").path(группа(вердикт)).map { предмет(it) }
        }
        return ReconcileRun(
            id = запуск.code,
            status = запуск.status,
            sliceFingerprint = запуск.doc.path("slice_fingerprint").asText(""),
            ontologyVersion = запуск.doc.path("ontology_version").asText(""),
            // Живой вызов виден по находкам «по смыслу»: отдельного поля под
            // него истина схем не держит, а выдуманное поле разошлось бы с
            // журналом ИИ.
            aiCalled = предметы.any { элемент -> элемент.findings.any { it.match == Match.SEMANTIC } },
            open = предметы.any { it.decided == null },
            items = предметы,
            note = запуск.doc.path("notes").asText(""),
        )
    }

    private fun предмет(запись: JsonNode): ReconcileItem = ReconcileItem(
        localId = запись.path("local_id").asText(""),
        concept = запись.path("concept").asText(""),
        candidateFact = запись.path("candidate_fact").asText(""),
        authority = запись.path("authority").asText(Authority.EXPERT),
        source = FactSource.FromExpert(
            запись.path("source").path("account").asText(""),
            запись.path("source").path("role").asText(""),
            запись.path("source").path("at").asText(""),
        ),
        verdict = Verdict.valueOf(запись.path("verdict").asText("new").uppercase()),
        findings = запись.path("findings").map { изЗаписи(it) },
        blocking = запись.path("blocking").map { it.asText() },
        decided = запись.path("decided").asText("").ifBlank { null }?.let { Action.valueOf(it) },
        note = запись.path("note").asText(""),
    )

    /** Закрыть нехватку выбором человека: названная цель становится ссылкой кандидата. */
    private fun закрытьНехватку(область: Area, понятие: Concept, снимок: ObjectNode, цель: String?) {
        val код = цель?.trim().orEmpty()
        if (код.isBlank()) return
        обязательства(понятие).forEach { обязательство ->
            val вид = обязательство.вид ?: return@forEach
            if (имена(снимок, обязательство.поле).isNotEmpty()) return@forEach
            val сущность = сущностьПоИмени(область, вид, код) ?: return@forEach
            if (обязательство.многие) снимок.putArray(обязательство.поле).add(сущность.code)
            else снимок.put(обязательство.поле, сущность.code)
        }
    }

    /** Обязательные связи заведённой сущности: направление называет онтология. */
    private fun обязательныеСвязи(
        область: Area,
        понятие: Concept,
        снимок: JsonNode,
        сущность: Entity,
        канал: Channel,
        author: String,
    ): List<String> {
        val реестр = links ?: return emptyList()
        val поставленные = mutableListOf<String>()
        обязательства(понятие).forEach { обязательство ->
            val связь = обязательство.связь ?: return@forEach
            // «subject (owns)» в онтологии значит, что найденная сторона —
            // субъект связи: она владеет нуждой, а не наоборот.
            val отНайденной = понятие.fields[обязательство.поле]?.contains("subject") == true
            имена(снимок, обязательство.поле).forEach { имя ->
                val сосед = сущностьПоИмени(область, обязательство.вид, имя) ?: return@forEach
                val (откуда, куда) = if (отНайденной) сосед.id to сущность.id else сущность.id to сосед.id
                реестр.link(
                    связь, откуда, куда, Provenance(канал, author),
                    rationale = "обязательная связь понятия «${словоПонятия(понятие.code)}»: ${обязательство.token}",
                )
                поставленные += связь
            }
        }
        return поставленные
    }

    private fun предложенное(находка: JsonNode): String =
        находка.path("offers").joinToString(" · ") { словоДействия(it.asText()) }

    private fun словоДействия(имя: String): String =
        runCatching { Action.valueOf(имя).word }.getOrDefault(имя)

    /** Понятие словами: латиница человеку ничего не говорит. */
    private fun словоПонятия(понятие: String): String = СЛОВА[понятие] ?: понятие

    private companion object {

        /** Метка запуска сверки: вид общий с синтезом, а списки — разные. */
        const val МЕТКА = "reconcile"

        /** Действия, решающие судьбу кандидата: после них строка ввода закрыта. */
        val ТЕРМИНАЛЬНЫЕ = setOf(
            Action.ACCEPT_NEW, Action.MERGE_INTO, Action.REFINE,
            Action.GENERALIZE, Action.MARK_CONTESTED, Action.FIX_INPUT,
        )

        /**
         * Потолок среза — из истины онтологии («срез ≤ 50 фактов и
         * сущностей»): числа в коде нет, оно читается оттуда же, откуда
         * читаются пороги идентичности.
         */
        val ПОТОЛОК: Int = Regex("≤\\s*(\\d+)")
            .find(GeneratedOntology.reconciliation["batching"].orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull()
            ?: error("потолок среза не назван в ОНТОЛОГИЯ-ФОРМИРОВАНИЯ (reconciliation.batching)")

        /**
         * Понятия словами — теми же, какими они названы на экране поля
         * знаний. Перечень понятий здесь не заводится: слова даются тем,
         * которые описаны онтологией.
         */
        val СЛОВА: Map<String, String> = mapOf(
            "stakeholder" to "сторона",
            "need" to "нужда",
            "goal" to "цель",
            "service" to "сервис",
            "constraint" to "рамка",
            "assumption" to "допущение",
            "milestone" to "этап",
            "normative_document" to "норматив",
        )
    }
}
