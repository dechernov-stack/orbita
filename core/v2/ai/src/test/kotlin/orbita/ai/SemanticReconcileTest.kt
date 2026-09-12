// Вторая ступень сверки: смысл — только там, где ключ промолчал.
//
// Здесь проверяется не «модель ответила», а ворота вокруг её ответа: дубль по
// смыслу доходит до человека только с НАЗВАННЫМ отличием, батч из десяти
// строк стоит одного вызова, повтор того же среза не стоит ничего, а молчащий
// канал не уносит с собой находки ступени 1.
//
// Ступень 1 здесь — двойник: настоящая живёт в знаниях и модулю ИИ не видна
// (её internal кончается на границе модуля). Двойник пишет запуск ТОЙ ЖЕ
// формой, какой его пишет и читает сверка знаний, — именно эту форму вторая
// ступень дописывает, и разойдись она, тест перестанет что-либо значить.
package orbita.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.ai.api.Answer
import orbita.ai.api.AiService
import orbita.ai.api.ProviderUnavailable
import orbita.ai.api.Transport
import orbita.ai.internal.JournalService
import orbita.ai.internal.ReconcilePrompt
import orbita.ai.internal.SemanticCandidate
import orbita.ai.internal.SemanticReconciler
import orbita.ai.internal.SliceEntry
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.kernel.api.Provenance
import orbita.knowledge.api.Action
import orbita.knowledge.api.Applied
import orbita.knowledge.api.Authority
import orbita.knowledge.api.Candidate
import orbita.knowledge.api.Comparison
import orbita.knowledge.api.FactSource
import orbita.knowledge.api.FieldDifference
import orbita.knowledge.api.Finding
import orbita.knowledge.api.Match
import orbita.knowledge.api.Question
import orbita.knowledge.api.Reconcile
import orbita.knowledge.api.ReconcileItem
import orbita.knowledge.api.ReconcileRun
import orbita.knowledge.api.Verdict
import orbita.knowledge.schema.GeneratedOntology
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SemanticReconcileTest {

    private val mapper = ObjectMapper()
    private val store: EntityStore = ПамятьСущностей()
    private val проект = "PJ-7001"
    private val область = Area.Project(проект)
    private val автор = "Иванов И."
    private val роль = "ведущий СИ"
    private val провенанс = Provenance(Channel.MANUAL, автор)

    /** Принятая нужда, с которой и пойдёт сравнение по смыслу. */
    private fun принятаяНужда(): Entity = store.create(
        "ND-0001", "need", область, "3",
        mapper.createObjectNode()
            .put("statement", "батарея на весь срок без обслуживания")
            .put("stakeholder", "Оператор")
            .put("qos_class", "TBR"),
        провенанс,
    )

    private fun кандидат(localId: String = "c1", формулировка: String = "автономность 3 года"): Candidate =
        Candidate(
            localId = localId,
            concept = "need",
            payload = mapper.createObjectNode()
                .put("statement", формулировка)
                .put("stakeholder", "Оператор"),
        )

    private fun ответМодели(
        localId: String = "c1",
        target: String = "ND-0001",
        confidence: Double = 0.9,
        поля: Boolean = true,
        отличие: Boolean = true,
    ): String {
        val узел = mapper.createObjectNode()
        узел.put("local_id", localId)
        узел.put("target", target)
        узел.put("question", "duplicate")
        узел.put("verdict", "augment")
        узел.put("confidence", confidence)
        if (поля) узел.putArray("compared_fields").add("statement").add("measure")
        if (отличие) {
            узел.putObject("difference")
                .put("field", "measure")
                .put("mine", "3 года")
                .put("theirs", "весь срок без обслуживания")
        }
        узел.put("why", "одна и та же мысль: питание живёт весь срок без обслуживания")
        return mapper.createArrayNode().add(узел).toString()
    }

    private fun собрать(
        канал: Канал,
        дубль: String? = null,
        помета: String = "",
    ): Pair<SemanticReconciler, AiService> {
        val служба: AiService = JournalService(store, канал, mapper)
        val ступень1 = ПамятьСверки(store, mapper, дубль, помета)
        return SemanticReconciler(store, ступень1, служба, mapper) to служба
    }

    private fun запуск(код: String): JsonNode = assertNotNull(store.byCode(область, код)).doc

    // --- находки второй ступени --------------------------------------------

    @Test
    fun `дубль по смыслу найден с названным отличием`() {
        принятаяНужда()
        val канал = Канал(ответМодели())
        val (сверка, служба) = собрать(канал)

        val итог = сверка.preview(проект, listOf(кандидат()), автор, роль, semantic = true)

        val предмет = итог.items.single()
        val поСмыслу = предмет.findings.filter { it.match == Match.SEMANTIC }
        assertEquals(1, поСмыслу.size, "находка по смыслу обязана дойти до человека: ${предмет.findings}")
        val находка = поСмыслу.single()
        assertEquals("ND-0001", находка.target)
        assertEquals(Verdict.AUGMENT, находка.verdict)
        // «Похоже» без «чем именно» — брак: отличие названо ПОЛЕМ и обоими значениями.
        assertEquals("measure", находка.difference?.field)
        assertEquals("3 года", находка.difference?.mine)
        assertEquals("весь срок без обслуживания", находка.difference?.theirs)
        assertTrue(находка.comparedFields.isNotEmpty(), "поля сравнения обязаны быть названы")
        assertTrue(
            находка.offers.contains(Action.MERGE_INTO) && находка.offers.contains(Action.DISMISS),
            "человеку нужно, что нажать: ${находка.offers}",
        )
        // Судьба строки ввода поменялась вместе с находкой: она больше не «новое».
        assertEquals(Verdict.AUGMENT, предмет.verdict)
        assertTrue(итог.aiCalled, "живой вызов обязан быть виден в запуске")
        assertEquals(1, канал.вызовов, "вторая ступень звонит один раз на срез")
        assertEquals(listOf("reconcile"), служба.journal(проект).map { it.kind })
    }

    @Test
    fun `батч из десяти вводов стоит одного вызова`() {
        принятаяНужда()
        val канал = Канал("[]")
        val (сверка, служба) = собрать(канал)
        val вводы = (1..10).map { кандидат("c$it", "нужда номер $it") }

        сверка.preview(проект, вводы, автор, роль, semantic = true)

        assertEquals(1, канал.вызовов, "десять строк ввода — один промпт и один вызов")
        assertEquals(1, служба.journal(проект).size, "в журнале одна запись на батч")
        assertTrue(
            канал.последний.contains("c10"),
            "в один промпт обязаны войти все строки ввода батча",
        )
    }

    @Test
    fun `повтор того же среза отвечается из журнала`() {
        принятаяНужда()
        val канал = Канал(ответМодели())
        val (сверка, служба) = собрать(канал)

        сверка.preview(проект, listOf(кандидат()), автор, роль, semantic = true)
        сверка.preview(проект, listOf(кандидат()), автор, роль, semantic = true)

        assertEquals(1, канал.вызовов, "тот же срез второй раз не оплачивается")
        assertEquals(1, служба.journal(проект).size, "журнал ИИ на повторе не прирастает")
        assertTrue(
            запуск("SR-0002").path("diff").path("semantic").path("cached").asBoolean(false),
            "второй запуск обязан сказать словами, что ответ взят из журнала",
        )
    }

    // --- отказные: ворота вокруг ответа модели ------------------------------

    @Test
    fun `ответ без отличия и без полей сравнения до человека не доходит`() {
        принятаяНужда()
        val безОтличия = mapper.readTree(ответМодели(localId = "c1", отличие = false)).get(0)
        val безПолей = mapper.readTree(ответМодели(localId = "c2", поля = false)).get(0)
        val канал = Канал(mapper.createArrayNode().add(безОтличия).add(безПолей).toString())
        val (сверка, _) = собрать(канал)

        val итог = сверка.preview(
            проект,
            listOf(кандидат("c1"), кандидат("c2", "срок активного существования 3 года")),
            автор, роль, semantic = true,
        )

        assertTrue(
            итог.items.none { предмет -> предмет.findings.any { it.match == Match.SEMANTIC } },
            "находка без названного отличия не показывается вовсе",
        )
        assertFalse(итог.aiCalled, "живых находок нет — и живого вызова в запуске не видно")
        val отброшенные = запуск("SR-0001").path("diff").path("refused")
        assertEquals(2, отброшенные.size(), "оба брака обязаны лечь в диф с причиной: $отброшенные")
        assertTrue(
            отброшенные.any { it.path("reason").asText().contains("чем именно") },
            "причина отказа обязана называться словами: $отброшенные",
        )
        assertTrue(
            итог.items.all { it.note.contains("отброшен") },
            "человек читает причину там, где принимает решение: ${итог.items.map { it.note }}",
        )
    }

    @Test
    fun `находка ниже порога близости понятия снимается`() {
        принятаяНужда()
        val порог = GeneratedOntology.of("need").identity.threshold
        val канал = Канал(ответМодели(confidence = порог - 0.3))
        val (сверка, _) = собрать(канал)

        val итог = сверка.preview(проект, listOf(кандидат()), автор, роль, semantic = true)

        assertTrue(
            итог.items.single().findings.none { it.match == Match.SEMANTIC },
            "ниже порога понятия это не дубль — находки нет",
        )
        val снятые = запуск("SR-0001").path("diff").path("dropped")
        assertEquals(1, снятые.size())
        assertTrue(снятые.get(0).path("reason").asText().contains("порог"), "причина снятия — порог понятия")
    }

    @Test
    fun `придуманный код цели отвергается`() {
        принятаяНужда()
        val канал = Канал(ответМодели(target = "ND-9999"))
        val (сверка, _) = собрать(канал)

        val итог = сверка.preview(проект, listOf(кандидат()), автор, роль, semantic = true)

        assertTrue(
            итог.items.single().findings.none { it.match == Match.SEMANTIC },
            "запись, которой в поле нет, основанием не бывает",
        )
        assertTrue(
            запуск("SR-0001").path("diff").path("refused").get(0).path("reason").asText().contains("придумано"),
            "придуманное основание называется прямо",
        )
    }

    @Test
    fun `дубль по ключу вторую ступень не зовёт`() {
        принятаяНужда()
        val канал = Канал(ответМодели())
        // Ступень 1 нашла дубль по ключу: спрашивать модель не о чем.
        val (сверка, служба) = собрать(канал, дубль = "ND-0001")

        val итог = сверка.preview(проект, listOf(кандидат()), автор, роль, semantic = true)

        assertEquals(0, канал.вызовов, "дубль по ключу найден БЕЗ живого вызова")
        assertTrue(служба.journal(проект).isEmpty(), "журнал ИИ не прирастает ни на запись")
        assertFalse(итог.aiCalled)
        assertEquals("ND-0001", итог.items.single().findings.single().target)
    }

    @Test
    fun `текстовое противоречие при совпавшем ключе уходит второй ступени`() {
        принятаяНужда()
        val канал = Канал(ответМодели())
        // Ключ совпал, но текст числом не сравнивается — ступень 1 сама
        // оставила этот вопрос смыслу и сказала об этом пометой.
        val (сверка, _) = собрать(
            канал,
            дубль = "ND-0001",
            помета = "противоречие по полю «statement» проверяется второй ступенью: текст числом не сравнивается",
        )

        сверка.preview(проект, listOf(кандидат()), автор, роль, semantic = true)

        assertEquals(1, канал.вызовов, "помету ступени 1 обязаны выполнить, а не потерять")
    }

    @Test
    fun `без просьбы о смысле вторая ступень молчит`() {
        принятаяНужда()
        val канал = Канал(ответМодели())
        val (сверка, служба) = собрать(канал)

        сверка.preview(проект, listOf(кандидат()), автор, роль, semantic = false)

        assertEquals(0, канал.вызовов, "семантику надстраивают по просьбе, а не всегда")
        assertTrue(служба.journal(проект).isEmpty())
    }

    @Test
    fun `при недоступном канале находки ступени 1 отдаются`() {
        принятаяНужда()
        val канал = Канал("", ProviderUnavailable("ключа нет"))
        val (сверка, _) = собрать(канал)

        val итог = сверка.preview(проект, listOf(кандидат()), автор, роль, semantic = true)

        val предмет = итог.items.single()
        assertEquals(1, предмет.findings.size, "находки ключа молчание канала не уносит")
        assertEquals(Match.KEY, предмет.findings.single().match)
        assertTrue(итог.note.contains(SemanticReconciler.НЕДОСТУПНО), "запуск обязан сказать о канале: ${итог.note}")
        assertTrue(итог.note.contains("что делать"), "отказ без «что делать» человеку бесполезен: ${итог.note}")
        assertFalse(
            запуск("SR-0001").path("diff").path("semantic").path("called").asBoolean(true),
            "вызова не было — так и записано",
        )
    }

    @Test
    fun `принятое второй ступенью не переписывается`() {
        val принятая = принятаяНужда()
        val канал = Канал(ответМодели())
        val (сверка, _) = собрать(канал)

        сверка.preview(проект, listOf(кандидат()), автор, роль, semantic = true)

        val после = assertNotNull(store.byCode(область, "ND-0001"))
        assertEquals(принятая.version, после.version, "принятая нужда не правится: служба показывает, решает человек")
        assertEquals(
            "батарея на весь срок без обслуживания",
            после.doc.path("statement").asText(),
            "формулировка принятого остаётся словами человека",
        )
    }

    // --- промпт второй ступени ---------------------------------------------

    @Test
    fun `промпт несёт правила понятия и требования к ответу`() {
        val текст = ReconcilePrompt.of(
            projectName = "КС IoT",
            candidates = listOf(SemanticCandidate("c1", "need", mapOf("statement" to "автономность 3 года"))),
            accepted = listOf(SliceEntry("ND-0001", "нужда", "statement: батарея на весь срок")),
            facts = listOf(SliceEntry("F-0001", "факт", "платформа — автономность — 3 года", Authority.MANDATORY)),
            omitted = 7,
        )

        assertTrue(текст.contains("## Требования к ответу"), текст)
        assertTrue(текст.contains("compared_fields"), "ворота ответа названы промпту")
        assertTrue(
            текст.contains(GeneratedOntology.of("need").identity.threshold.toString()),
            "порог близости берётся у онтологии, а не выдумывается промптом",
        )
        assertTrue(текст.contains(Authority.word(Authority.MANDATORY)), "ранг факта в срезе назван словами")
        assertTrue(текст.contains("7"), "урезанный срез называет, сколько осталось за потолком")
        assertTrue(текст.contains(ReconcilePrompt.VERSION), "версия шаблона меняет отпечаток промпта")
    }

    @Test
    fun `промпт без строк ввода не собирается`() {
        val беда = assertFailsWith<IllegalArgumentException> {
            ReconcilePrompt.of("КС IoT", emptyList(), emptyList(), emptyList())
        }
        assertTrue(беда.message!!.contains("вызова быть не должно"), беда.message!!)
    }
}

/**
 * Двойник ступени 1: пишет запуск той же формой, что сверка знаний.
 *
 * @param дубль код принятой записи, найденной по ключу; null — ключ не совпал,
 *   и вопрос о дубле уходит второй ступени
 */
private class ПамятьСверки(
    private val store: EntityStore,
    private val mapper: ObjectMapper,
    private val дубль: String? = null,
    private val помета: String = "",
) : Reconcile {

    private var счётчик = 0

    override fun preview(
        project: String,
        candidates: List<Candidate>,
        author: String,
        role: String,
        semantic: Boolean,
    ): ReconcileRun {
        val область = Area.Project(project)
        val документ = mapper.createObjectNode()
        документ.put("trigger", "manual")
        документ.put("slice_fingerprint", "отпечаток-ступени-1")
        документ.put("slice_size", 0)
        документ.putArray("proposals")
        val диф = документ.putObject("diff")
        Verdict.entries.forEach { диф.putArray(it.name.lowercase()) }
        candidates.forEach { кандидат ->
            val запись = строкаВвода(область, кандидат, author, role)
            (диф.get(запись.path("verdict").asText()) as ArrayNode).add(запись)
        }
        документ.put("ontology_version", GeneratedOntology.ontologyVersion)
        документ.put("cached", false)
        документ.putArray("tags").add("reconcile")
        документ.put("notes", "сверка ступени 1 без вызова службы")
        счётчик += 1
        val запуск = store.create(
            "SR-%04d".format(счётчик), "synthesis_run", область, null, документ,
            Provenance(Channel.MANUAL, author), status = "done",
        )
        return вид(запуск)
    }

    override fun run(project: String, run: String): ReconcileRun =
        вид(store.byCode(Area.Project(project), run) ?: error("запуска «$run» нет"))

    override fun open(project: String): List<ReconcileRun> = emptyList()

    override fun apply(
        project: String,
        run: String,
        localId: String,
        finding: Int,
        action: Action,
        target: String?,
        reason: String,
        author: String,
    ): Applied = Applied(emptyList(), emptyList(), emptyList(), emptyList(), "двойник модель не меняет")

    private fun строкаВвода(область: Area, кандидат: Candidate, author: String, role: String): ObjectNode {
        val факт = store.create(
            "F-${кандидат.localId}", "fact", область, null,
            mapper.createObjectNode()
                .put("kind", "framing")
                .put("subject", кандидат.payload.path("stakeholder").asText(""))
                .put("predicate", "нуждается в")
                .put("value", кандидат.payload.path("statement").asText(""))
                .put("authority", Authority.EXPERT)
                .put("disposition", "adopted"),
            Provenance(Channel.MANUAL, author),
        )
        val запись = mapper.createObjectNode()
        запись.put("local_id", кандидат.localId)
        запись.put("concept", кандидат.concept)
        запись.put("origin", кандидат.origin.name)
        запись.put("candidate_fact", факт.code)
        запись.put("authority", Authority.EXPERT)
        запись.putObject("source").put("account", author).put("role", role).put("at", "2026-09-12")
        запись.set<JsonNode>("payload", кандидат.payload)
        val находки = запись.putArray("findings")
        находки.add(находкаДубля())
        запись.put("verdict", if (дубль == null) "new" else "augment")
        запись.putArray("blocking")
        запись.put("note", помета)
        return запись
    }

    private fun находкаДубля(): ObjectNode {
        val узел = mapper.createObjectNode()
        узел.put("question", Question.DUPLICATE.name)
        узел.put("verdict", if (дубль == null) Verdict.NEW.name else Verdict.AUGMENT.name)
        дубль?.let { узел.put("target", it) }
        узел.put("match", Match.KEY.name)
        узел.put("confidence", 1.0)
        val поля = узел.putArray("compared_fields")
        поля.add("stakeholder").add("statement_core")
        if (дубль != null) {
            узел.putObject("difference")
                .put("comparison", "DIFFERS").put("field", "qos_class")
                .put("mine", "").put("theirs", "TBR").put("reason", "класс обслуживания не назван")
        }
        узел.putArray("basis")
        val действия = узел.putArray("offers")
        действия.add(Action.ACCEPT_NEW.name).add(Action.DISMISS.name)
        узел.put("blocking", false)
        return узел
    }

    private fun вид(запуск: Entity): ReconcileRun {
        val предметы = Verdict.entries.flatMap { вердикт ->
            запуск.doc.path("diff").path(вердикт.name.lowercase()).map { предмет(it) }
        }
        return ReconcileRun(
            id = запуск.code,
            status = запуск.status,
            sliceFingerprint = запуск.doc.path("slice_fingerprint").asText(""),
            ontologyVersion = запуск.doc.path("ontology_version").asText(""),
            aiCalled = предметы.any { предмет -> предмет.findings.any { it.match == Match.SEMANTIC } },
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
        findings = запись.path("findings").map { находка(it) },
        blocking = запись.path("blocking").map { it.asText() },
        decided = запись.path("decided").asText("").ifBlank { null }?.let { Action.valueOf(it) },
        note = запись.path("note").asText(""),
    )

    private fun находка(узел: JsonNode): Finding = Finding(
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
}

/** Канал к модели: считает вызовы и помнит последний промпт — сети в тесте нет. */
private class Канал(private val ответ: String, private val беда: ProviderUnavailable? = null) : Transport {

    var вызовов: Int = 0
    var последний: String = ""

    override fun ask(prompt: String, model: String?, maxTokens: Int?): Answer {
        вызовов += 1
        последний = prompt
        беда?.let { throw it }
        return Answer(ответ, "модель-теста", 1200, 300)
    }
}

/** Хранилище в памяти: у модуля ИИ нет оснастки базы, а проверяются правила. */
private class ПамятьСущностей : EntityStore {

    private val записи = linkedMapOf<String, Entity>()
    private val история = mutableMapOf<String, MutableList<Entity>>()
    private var счётчик = 0

    override fun create(
        code: String,
        kind: String,
        area: Area,
        bornIn: String?,
        doc: JsonNode,
        provenance: Provenance,
        status: String,
    ): Entity {
        счётчик += 1
        val сейчас = OffsetDateTime.now()
        val сущность = Entity("id-$счётчик", code, kind, area, bornIn, status, 1, provenance, doc, сейчас, сейчас)
        записи[сущность.id] = сущность
        история.getOrPut(сущность.id) { mutableListOf() }.add(сущность)
        return сущность
    }

    override fun update(id: String, doc: JsonNode, provenance: Provenance, status: String?): Entity {
        val было = записи.getValue(id)
        val стало = было.copy(
            doc = doc, provenance = provenance, status = status ?: было.status,
            version = было.version + 1, updatedAt = OffsetDateTime.now(),
        )
        записи[id] = стало
        история.getOrPut(id) { mutableListOf() }.add(стало)
        return стало
    }

    override fun byId(id: String): Entity? = записи[id]

    override fun byCode(area: Area, code: String): Entity? =
        записи.values.firstOrNull { it.area == area && it.code == code }

    override fun list(area: Area, kind: String?): List<Entity> =
        записи.values.filter { it.area == area && (kind == null || it.kind == kind) }.sortedBy { it.code }

    override fun ofKind(kind: String): List<Entity> = записи.values.filter { it.kind == kind }

    override fun history(id: String): List<Entity> = история[id].orEmpty()
}
