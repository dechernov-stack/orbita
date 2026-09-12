// Ступень 2 сверки: «то же самое, сказанное иначе» — живым вызовом.
//
// Порядок ступеней и есть гарантия «сначала бесплатно, потом токены»
// (СВЕРКА-РУЧНОГО-ВВОДА): детерминированная сверка знаний отрабатывает
// ВСЕГДА и первой, а сюда доходит только то, где ключ идентичности понятия не
// совпал. Если ключ ответил на вопрос о дубле, живого вызова не будет вовсе —
// это прямая мера пакета: «дубль по ключу найден БЕЗ вызова, журнал ИИ не
// прирастает».
//
// Один вызов на срез: десять строк ввода одного батча собираются в ОДИН
// промпт по срезу ≤ 50 записей (потолок назван онтологией), а повтор того же
// среза отвечается из журнала по отпечатку промпта — второй записи kind =
// reconcile в журнале не появляется.
//
// Ворота ответа — на нашей стороне, как и у разбора материала. Модель вправе
// ошибиться; система — нет. Находка без названных полей сравнения ИЛИ без
// отличия до человека не доходит: «похоже» без «чем именно» — брак
// (ОНТОЛОГИЯ-ФОРМИРОВАНИЯ, reconciliation.rights). Отброшенное не пропадает
// молча — оно ложится в диф запуска отдельной группой с причиной.
//
// Чего здесь нет и быть не может: слияния, правки принятого и выбора
// победителя в противоречии. Служба показывает, решает человек — единственный
// путь изменения модели остаётся у `apply` знаний, и он делегируется как есть.
package orbita.ai.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.ai.api.AiService
import orbita.ai.api.Answer
import orbita.ai.api.ProviderUnavailable
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.kernel.api.Provenance
import orbita.kernel.schema.GeneratedKinds
import orbita.knowledge.api.Action
import orbita.knowledge.api.Applied
import orbita.knowledge.api.Authority
import orbita.knowledge.api.Candidate
import orbita.knowledge.api.Comparison
import orbita.knowledge.api.Disposition
import orbita.knowledge.api.FieldDifference
import orbita.knowledge.api.Finding
import orbita.knowledge.api.Match
import orbita.knowledge.api.Question
import orbita.knowledge.api.Reconcile
import orbita.knowledge.api.ReconcileItem
import orbita.knowledge.api.ReconcileRun
import orbita.knowledge.api.Verdict
import orbita.knowledge.schema.GeneratedOntology
import kotlin.math.roundToInt

/**
 * Сверка с семантической ступенью поверх детерминированной.
 *
 * @param deterministic ступень 1 ГОТОВОЙ из знаний: собирать её здесь заново
 *   нельзя — иначе порядок «сначала ключ, потом токены» держался бы
 *   договорённостью, а не устройством
 */
class SemanticReconciler(
    private val store: EntityStore,
    private val deterministic: Reconcile,
    private val service: AiService,
    private val mapper: ObjectMapper = ObjectMapper(),
) : Reconcile {

    override fun preview(
        project: String,
        candidates: List<Candidate>,
        author: String,
        role: String,
        semantic: Boolean,
    ): ReconcileRun {
        // Ступень 1 идёт всегда и первой: её находки остаются, что бы ни
        // ответила модель и ответит ли она вообще.
        val ступень1 = deterministic.preview(project, candidates, author, role, semantic)
        if (!semantic) return ступень1
        val область = Area.Project(project)
        val спросить = безОтветаПоКлючу(candidates, ступень1)
        // Ключ закрыл все вопросы о дубле — живого вызова не будет.
        if (спросить.isEmpty()) return ступень1

        val срез = срезПоля(область, спросить)
        val промпт = ReconcilePrompt.of(
            projectName = имяПроекта(область, project),
            candidates = спросить.map { (кандидат, предмет) -> строкаВвода(кандидат, предмет) },
            accepted = срез.принятые,
            facts = срез.факты,
            omitted = срез.заПотолком,
        )
        val ответ = try {
            service.ask(project, KIND, промпт, maxTokens = БЮДЖЕТ_СВЕРКИ)
        } catch (беда: ProviderUnavailable) {
            // Канал молчит — это не потеря сверки: находки ключа уже записаны
            // и отдаются человеку, а запуск говорит словами, что делать.
            return недоступно(project, область, ступень1, author, беда)
        }
        return применить(project, область, ступень1, спросить, ответ, author, срез)
    }

    /** Запуск по коду: идемпотентно и без нового вызова — находки уже записаны. */
    override fun run(project: String, run: String): ReconcileRun = deterministic.run(project, run)

    override fun open(project: String): List<ReconcileRun> = deterministic.open(project)

    /** Единственный путь изменения модели остаётся у знаний: служба его не подменяет. */
    override fun apply(
        project: String,
        run: String,
        localId: String,
        finding: Int,
        action: Action,
        target: String?,
        reason: String,
        author: String,
    ): Applied = deterministic.apply(project, run, localId, finding, action, target, reason, author)

    // --- кого спрашивать ---------------------------------------------------

    /**
     * Строки ввода, на вопрос которых ключ не ответил.
     *
     * Дубль по ключу — тождество данных, и переспрашивать о нём модель значит
     * платить за уже известный ответ. Но есть и обратный случай: ключ совпал,
     * а сравнить ПО СУЩЕСТВУ ступень 1 не смогла — текстовое противоречие
     * («та же цель, сформулированная иначе») числом не сравнивается, и она
     * сама оставляет его смыслу пометой. Такую строку спросить обязаны, иначе
     * помета остаётся обещанием, которое никто не выполняет.
     */
    private fun безОтветаПоКлючу(
        candidates: List<Candidate>,
        ступень1: ReconcileRun,
    ): List<Pair<Candidate, ReconcileItem>> = candidates.mapNotNull { кандидат ->
        val предмет = ступень1.items.firstOrNull { it.localId == кандидат.localId } ?: return@mapNotNull null
        val ключСовпал = предмет.findings.any { it.question == Question.DUPLICATE && it.target != null }
        val ждётСмысла = ВТОРАЯ_СТУПЕНЬ.containsMatchIn(предмет.note)
        if (ключСовпал && !ждётСмысла) null else кандидат to предмет
    }

    private fun строкаВвода(кандидат: Candidate, предмет: ReconcileItem): SemanticCandidate = SemanticCandidate(
        localId = кандидат.localId,
        concept = кандидат.concept,
        fields = поляВвода(кандидат.payload),
        note = предмет.note,
    )

    private fun поляВвода(payload: JsonNode): Map<String, String> {
        val карта = linkedMapOf<String, String>()
        payload.fields().forEach { (имя, значение) ->
            if (карта.size < ПОЛЕЙ_В_СТРОКЕ && !пусто(значение)) карта[имя] = текстом(значение)
        }
        return карта
    }

    // --- срез поля ---------------------------------------------------------

    private class Срез(
        val принятые: List<SliceEntry>,
        val факты: List<SliceEntry>,
        val заПотолком: Int,
    ) {
        val размер: Int get() = принятые.size + факты.size
    }

    /**
     * Срез второй ступени: принятые записи тех же понятий и факты смежных тем.
     *
     * Принятые записи не урезаются: именно с ними идёт сравнение, и выкинуть
     * их значит спросить модель ни о чём. Урезается поле фактов — по рангу
     * доверия, весомое остаётся; сколько осталось за потолком, говорится
     * словами и модели, и человеку.
     */
    private fun срезПоля(область: Area, спросить: List<Pair<Candidate, ReconcileItem>>): Срез {
        val понятия = спросить.map { (кандидат, _) -> кандидат.concept }.distinct()
        val виды = понятия.mapNotNull { GeneratedKinds.byCode[it]?.code }.distinct()
        val принятые = виды
            .flatMap { вид -> store.list(куда(область, вид), вид) }
            .filter { it.status != "cancelled" }
            .sortedBy { it.code }
        val кандидатские = спросить.mapNotNull { (_, предмет) -> store.byCode(область, предмет.candidateFact) }
        val субъекты = кандидатские.map { субъект(it) }.filter { it.isNotBlank() }.toSet()
        val свои = спросить.map { (_, предмет) -> предмет.candidateFact }.toSet()
        val поле = store.list(область, "fact")
            .filter { it.status != "cancelled" }
            .filter { it.code !in свои }
            .filter { Disposition.of(it.doc.path("disposition").asText("")) in ЖИВЫЕ }
        val темы = поле.filter { субъект(it) in субъекты }.mapNotNull { тема(it) }.toSet()
        val смежные = поле.filter { субъект(it) in субъекты || тема(it) in темы }
        // Смежного не нашлось — в срез идёт поле по рангу: пустой срез делает
        // вызов бессмысленным, а платить за него всё равно придётся.
        val отобранные = смежные.ifEmpty { поле }
            .sortedWith(compareBy<Entity>({ Authority.weight(it.doc.path("authority").asText("")) }, { it.code }))
        val местоФактам = (ПОТОЛОК - принятые.size).coerceAtLeast(0)
        val вошли = принятые.take(ПОТОЛОК)
        val фактыСреза = отобранные.take(местоФактам)
        val всего = принятые.size + отобранные.size
        return Срез(
            принятые = вошли.map { SliceEntry(it.code, словами(it.kind), текстСущности(it)) },
            факты = фактыСреза.map {
                SliceEntry(it.code, "факт", текстФакта(it), it.doc.path("authority").asText(""))
            },
            заПотолком = (всего - вошли.size - фактыСреза.size).coerceAtLeast(0),
        )
    }

    /** Норматив общий для всех проектов: он живёт на полке, а не в проекте. */
    private fun куда(область: Area, вид: String): Area =
        if (вид == "normative_document") Area.Library else область

    private fun субъект(факт: Entity): String = факт.doc.path("subject").asText("").trim().lowercase()

    private fun тема(факт: Entity): String? = факт.doc.path("topic").asText("").ifBlank { null }

    private fun текстСущности(сущность: Entity): String {
        val имена = GeneratedKinds.byCode[сущность.kind]?.fields.orEmpty()
        return имена
            .filter { !пусто(сущность.doc.path(it)) }
            .take(ПОЛЕЙ_В_СТРОКЕ)
            .joinToString(" · ") { "$it: ${текстом(сущность.doc.path(it))}" }
            .ifBlank { сущность.code }
    }

    private fun текстФакта(факт: Entity): String {
        val д = факт.doc
        val величина = listOf(д.path("value").asText(""), д.path("unit").asText(""))
            .filter { it.isNotBlank() }.joinToString(" ")
        val источник = д.path("material").asText("").ifBlank { "ручной ввод" }
        val якорь = д.path("anchor").asText("").ifBlank { "без якоря" }
        val темаФакта = тема(факт) ?: "без темы"
        return "${д.path("subject").asText("")} — ${д.path("predicate").asText("")} — $величина " +
            "($источник, $якорь; тема: $темаФакта)"
    }

    private fun имяПроекта(область: Area, project: String): String =
        store.list(область, "project").firstOrNull()?.doc?.path("name")?.asText(project) ?: project

    // --- ответ второй ступени ----------------------------------------------

    private class Отказ(val localId: String, val reason: String, val raw: String)

    private class Итог(
        val находки: Map<String, List<Finding>>,
        val отказы: List<Отказ>,
        val снятые: List<Отказ>,
    )

    private sealed interface Разбор {
        class Годная(val finding: Finding) : Разбор
        class Брак(val reason: String) : Разбор
        class Ниже(val reason: String) : Разбор
    }

    private fun применить(
        project: String,
        область: Area,
        ступень1: ReconcileRun,
        спросить: List<Pair<Candidate, ReconcileItem>>,
        ответ: Answer,
        author: String,
        срез: Срез,
    ): ReconcileRun {
        val записанный = store.byCode(область, ступень1.id) ?: return ступень1
        val документ = записанный.doc.deepCopy<JsonNode>() as ObjectNode
        val итог = разобрать(область, ответ.text, спросить)
        итог.находки.forEach { (localId, находки) -> дописать(документ, localId, находки) }
        итог.отказы.forEach { пометить(документ, it, ОТБРОШЕНО, "ответ второй ступени отброшен") }
        итог.снятые.forEach { пометить(документ, it, НИЖЕ_ПОРОГА, "находка второй ступени снята") }
        сводка(документ, итог, ответ, срез, спросить.size)
        store.update(записанный.id, документ, Provenance(Channel.SERVICE, author))
        // Вид собирает ступень 1: одно чтение запуска на обе ступени, и
        // признак живого вызова считается по находкам «по смыслу».
        return deterministic.run(project, ступень1.id)
    }

    private fun разобрать(
        область: Area,
        текст: String,
        спросить: List<Pair<Candidate, ReconcileItem>>,
    ): Итог {
        val дерево = runCatching { mapper.readTree(очистить(текст)) }.getOrNull()
        if (дерево == null || !дерево.isArray) {
            return Итог(
                emptyMap(),
                listOf(Отказ("", "ответ второй ступени не массив находок — читать его нечем", обрезать(текст))),
                emptyList(),
            )
        }
        val понятия = спросить.associate { (кандидат, _) -> кандидат.localId to кандидат.concept }
        val находки = linkedMapOf<String, MutableList<Finding>>()
        val отказы = mutableListOf<Отказ>()
        val снятые = mutableListOf<Отказ>()
        дерево.forEach { узел ->
            val localId = узел.path("local_id").asText("").trim()
            val понятие = понятия[localId]
            if (понятие == null) {
                отказы += Отказ(
                    localId,
                    "строку ввода «$localId» на вторую ступень не отправляли: находке некуда вернуться",
                    обрезать(узел.toString()),
                )
                return@forEach
            }
            when (val разбор = находка(область, узел, GeneratedOntology.of(понятие).identity.threshold)) {
                is Разбор.Годная -> находки.getOrPut(localId) { mutableListOf() } += разбор.finding
                is Разбор.Брак -> отказы += Отказ(localId, разбор.reason, обрезать(узел.toString()))
                is Разбор.Ниже -> снятые += Отказ(localId, разбор.reason, обрезать(узел.toString()))
            }
        }
        return Итог(находки, отказы, снятые)
    }

    /**
     * Находка из ответа модели — либо названная причина, почему она не находка.
     *
     * Проверки идут по одной и называются словами: человеку видно не «ответ
     * не подошёл», а чего именно в нём не хватило.
     */
    private fun находка(область: Area, узел: JsonNode, порог: Double): Разбор {
        val вопрос = Question.entries.firstOrNull { it.name.equals(узел.path("question").asText(""), true) }
            ?: return Разбор.Брак("вопрос «${узел.path("question").asText("")}» второй ступени неизвестен")
        val ожидаемый = ПАРЫ[вопрос]
            ?: return Разбор.Брак("вопрос «${вопрос.word}» вторая ступень не решает: его считает сервер по онтологии")
        val вердикт = Verdict.entries.firstOrNull { it.name.equals(узел.path("verdict").asText(""), true) }
            ?: return Разбор.Брак("вердикт «${узел.path("verdict").asText("")}» неизвестен")
        if (вердикт != ожидаемый) {
            return Разбор.Брак(
                "вопрос «${вопрос.word}» и вердикт «${вердикт.word}» не сходятся: ждали «${ожидаемый.word}»",
            )
        }
        val цель = узел.path("target").asText("").trim()
        if (цель.isBlank()) return Разбор.Брак("находка не назвала, о какой записи поля сказано «${вердикт.word}»")
        // Придуманное основание — самый дорогой брак ответа: по нему человек
        // пошёл бы искать запись, которой нет.
        if (store.byCode(область, цель) == null && store.byCode(Area.Library, цель) == null) {
            return Разбор.Брак("записи «$цель» в поле нет: основание придумано")
        }
        val поля = узел.path("compared_fields").map { it.asText("").trim() }.filter { it.isNotBlank() }
        if (поля.isEmpty()) {
            return Разбор.Брак("находка не назвала полей сравнения — «похоже» без «чем именно» брак")
        }
        val отличие = узел.path("difference")
        if (!отличие.isObject) {
            return Разбор.Брак("находка без отличия: сказать «похоже» и не сказать «чем» нельзя")
        }
        val поле = отличие.path("field").asText("").trim()
        val моё = отличие.path("mine").asText("").trim()
        val чужое = отличие.path("theirs").asText("").trim()
        if (поле.isBlank() || (моё.isBlank() && чужое.isBlank())) {
            return Разбор.Брак("отличие названо без поля либо без значений сторон")
        }
        val уверенность = узел.path("confidence").asDouble(0.0)
        if (уверенность !in 0.0..1.0) return Разбор.Брак("уверенность вне 0…1: $уверенность")
        // Порог близости назван онтологией понятию: ниже него это не дубль и
        // не спор о том же самом — своего числа здесь не заводится.
        if (уверенность < порог) {
            return Разбор.Ниже("близость ${проценты(уверенность)} ниже порога понятия ${проценты(порог)}")
        }
        val сравнение = if (вердикт == Verdict.CONFIRM) Comparison.EQUAL else Comparison.DIFFERS
        val почему = узел.path("why").asText("").trim().ifBlank { "второй ступенью признано одним и тем же" }
        val хвост = if (вердикт != Verdict.CONTRADICT) "" else
            "; показаны оба значения, победителя выбирает человек"
        return runCatching {
            Разбор.Годная(
                Finding(
                    question = вопрос,
                    verdict = вердикт,
                    target = цель,
                    match = Match.SEMANTIC,
                    confidence = уверенность,
                    comparedFields = поля,
                    difference = FieldDifference(
                        comparison = сравнение,
                        field = поле,
                        mine = моё,
                        theirs = чужое,
                        reason = "$почему — по смыслу, близость ${проценты(уверенность)} " +
                            "при пороге ${проценты(порог)}$хвост",
                    ),
                    // Подтверждение опирается на факт, который его дал: он и
                    // есть основание к привязке.
                    basis = if (вердикт == Verdict.CONFIRM) listOf(цель) else emptyList(),
                    offers = ПРЕДЛОЖЕНИЯ[вердикт].orEmpty(),
                ),
            )
        }.getOrElse { Разбор.Брак("находка не собралась: ${it.message}") }
    }

    // --- запись в запуск ---------------------------------------------------

    private class Место(val группа: String, val индекс: Int, val запись: ObjectNode)

    /**
     * Дописать находки в строку ввода и переложить её в группу дифа по
     * итоговому вердикту: предложение в чужой группе молча рассыпает счётчики
     * экрана — «противоречит 0», пока противоречие лежит среди новых.
     */
    private fun дописать(документ: ObjectNode, localId: String, находки: List<Finding>) {
        val место = записьКандидата(документ, localId) ?: return
        val массив = место.запись.path("findings") as? ArrayNode ?: место.запись.putArray("findings")
        находки.forEach { массив.add(вЗапись(it)) }
        val новый = вердикт(массив)
        место.запись.put("verdict", группа(новый))
        if (группа(новый) != место.группа) переложить(документ, место, группа(новый))
    }

    private fun пометить(документ: ObjectNode, отказ: Отказ, вГруппу: String, пояснение: String) {
        val диф = документ.path("diff") as? ObjectNode ?: return
        val массив = диф.path(вГруппу) as? ArrayNode ?: диф.putArray(вГруппу)
        массив.addObject()
            .put("local_id", отказ.localId)
            .put("reason", отказ.reason)
            .put("raw", отказ.raw)
        // Причина видна и в самой строке ввода: человек читает её там, где
        // принимает решение, а не в служебной сводке запуска.
        val место = записьКандидата(документ, отказ.localId) ?: return
        val прежняя = место.запись.path("note").asText("")
        место.запись.put("note", склеить(прежняя, "$пояснение: ${отказ.reason}"))
    }

    /**
     * Сводка второй ступени — ВНУТРИ дифа запуска.
     *
     * Своего поля у неё в истине схем нет, а поле вне схемы не существует
     * (`synthesis_run` закрыт `additionalProperties: false`): выдумать его в
     * коде — значит завести документ, который не проходит собственную схему.
     * «Взято из журнала» при этом ложится в объявленный `cached`: он ровно об
     * этом и сказан.
     */
    private fun сводка(документ: ObjectNode, итог: Итог, ответ: Answer, срез: Срез, строк: Int) {
        val блок = (документ.path("diff") as? ObjectNode ?: документ.putObject("diff")).putObject(СВОДКА)
        блок.put("called", true)
        блок.put("cached", ответ.cached)
        блок.put("prompt_version", ReconcilePrompt.VERSION)
        блок.put("slice_size", срез.размер)
        блок.put("found", итог.находки.values.sumOf { it.size })
        блок.put("refused", итог.отказы.size)
        блок.put("dropped", итог.снятые.size)
        документ.put("cached", ответ.cached)
        val изЖурнала = if (ответ.cached) " · ответ взят из журнала: тот же срез уже сверялся" else ""
        документ.put(
            "notes",
            склеить(
                документ.path("notes").asText(""),
                "ступень 2 по смыслу: один вызов на срез ${срез.размер} записей, строк ввода $строк; " +
                    "находок ${итог.находки.values.sumOf { it.size }}, " +
                    "отброшено ${итог.отказы.size} (без поля отличия), " +
                    "ниже порога ${итог.снятые.size}$изЖурнала",
            ),
        )
    }

    private fun недоступно(
        project: String,
        область: Area,
        ступень1: ReconcileRun,
        author: String,
        беда: ProviderUnavailable,
    ): ReconcileRun {
        val записанный = store.byCode(область, ступень1.id) ?: return ступень1
        val документ = записанный.doc.deepCopy<JsonNode>() as ObjectNode
        (документ.path("diff") as? ObjectNode ?: документ.putObject("diff")).putObject(СВОДКА)
            .put("called", false)
            .put("unavailable", беда.message ?: "канал молчит")
            .put("prompt_version", ReconcilePrompt.VERSION)
        документ.put(
            "notes",
            склеить(документ.path("notes").asText(""), "$НЕДОСТУПНО: ${беда.message}. $ЧТО_ДЕЛАТЬ"),
        )
        store.update(записанный.id, документ, Provenance(Channel.SERVICE, author))
        return deterministic.run(project, ступень1.id)
    }

    private fun записьКандидата(документ: ObjectNode, localId: String): Место? {
        if (localId.isBlank()) return null
        val диф = документ.path("diff") as? ObjectNode ?: return null
        Verdict.entries.forEach { вердикт ->
            val имя = группа(вердикт)
            val массив = диф.path(имя) as? ArrayNode ?: return@forEach
            массив.forEachIndexed { индекс, узел ->
                if (узел is ObjectNode && узел.path("local_id").asText("") == localId) {
                    return Место(имя, индекс, узел)
                }
            }
        }
        return null
    }

    private fun переложить(документ: ObjectNode, место: Место, новая: String) {
        val диф = документ.path("diff") as? ObjectNode ?: return
        val прежний = диф.path(место.группа) as? ArrayNode ?: return
        прежний.remove(место.индекс)
        val целевой = диф.path(новая) as? ArrayNode ?: диф.putArray(новая)
        целевой.add(место.запись)
    }

    /**
     * Находка записью — той же формой, какой её пишет и читает ступень 1:
     * читатель у запуска один, и вторая форма записи разошлась бы с ним молча.
     */
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

    /** Вердикт строки ввода по всем её находкам — тот же порядок важности, что у ступени 1. */
    private fun вердикт(находки: ArrayNode): Verdict {
        val есть = находки.map { it.path("verdict").asText("") }
        fun естьЛи(вердикт: Verdict) = есть.any { it.equals(вердикт.name, true) }
        return when {
            естьЛи(Verdict.CONTRADICT) -> Verdict.CONTRADICT
            естьЛи(Verdict.AUGMENT) -> Verdict.AUGMENT
            естьЛи(Verdict.CONFIRM) -> Verdict.CONFIRM
            else -> Verdict.NEW
        }
    }

    private fun группа(вердикт: Verdict): String = вердикт.name.lowercase()

    private fun словами(вид: String): String = GeneratedKinds.byCode[вид]?.title ?: вид

    private fun пусто(узел: JsonNode): Boolean =
        узел.isMissingNode || узел.isNull || (узел.isValueNode && узел.asText("").isBlank()) ||
            (узел.isArray && узел.isEmpty)

    private fun текстом(узел: JsonNode): String = if (узел.isValueNode) узел.asText("") else узел.toString()

    private fun склеить(прежнее: String, добавка: String): String =
        if (прежнее.isBlank()) добавка else "$прежнее; $добавка"

    private fun проценты(доля: Double): String = "${(доля * 100).roundToInt()} %"

    /** Ответ модели в рамке кода — не повод потерять ответ. */
    private fun очистить(raw: String): String =
        raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

    private fun обрезать(текст: String): String =
        текст.trim().let { if (it.length <= ХВОСТ_ОТКАЗА) it else it.take(ХВОСТ_ОТКАЗА) + "…" }

    companion object {

        /** Вид вызова в журнале ИИ: по нему считается мера «один вызов на батч». */
        const val KIND: String = "reconcile"

        /**
         * Потолок ответа сверки. Находка — несколько строк, и десяти строк
         * ввода хватает с запасом: разбор документа просит свой бюджет, сверка
         * своего не раздувает.
         */
        const val БЮДЖЕТ_СВЕРКИ: Int = 8_000

        /** Группа дифа: ответы, отброшенные воротами — «похоже» без «чем именно». */
        const val ОТБРОШЕНО: String = "refused"

        /** Группа дифа: находки ниже порога близости понятия. */
        const val НИЖЕ_ПОРОГА: String = "dropped"

        /** Сводка вызова — внутри дифа: своего поля у неё в истине схем нет. */
        const val СВОДКА: String = "semantic"

        /**
         * Помета ступени 1 «это — второй ступени». Ею она передаёт сюда то,
         * что решить по данным не смогла: текстовое противоречие при
         * совпавшем ключе. Разбирать чужую помету текстом — плата за то, что
         * признака «ждёт смысла» у находки в портах знаний пока нет.
         *
         * `(?Ui)` обязателен: без него `\\w` считается по латинскому алфавиту и
         * русское «второй» не совпадает НИКОГДА — помета молча терялась бы.
         */
        val ВТОРАЯ_СТУПЕНЬ: Regex = Regex("(?Ui)втор\\w+ ступен")

        /** Слова отказа канала — ими же о нём говорит маршрут. */
        const val НЕДОСТУПНО: String = "вторая ступень сверки недоступна"

        const val ЧТО_ДЕЛАТЬ: String =
            "находки ступени 1 отданы; что делать: решить по ним либо повторить сверку, когда канал ответит"

        /**
         * Потолок среза — из истины онтологии («срез ≤ 50 фактов и
         * сущностей»): числа в коде нет, оно читается там же, где пороги
         * идентичности.
         */
        val ПОТОЛОК: Int = Regex("≤\\s*(\\d+)")
            .find(GeneratedOntology.reconciliation["batching"].orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull()
            ?: error("потолок среза не назван в ОНТОЛОГИЯ-ФОРМИРОВАНИЯ (reconciliation.batching)")

        /** Сколько полей записи показывать: срез должен читаться, а не тонуть. */
        const val ПОЛЕЙ_В_СТРОКЕ: Int = 8

        /** Сколько знаков брака ответа сохраняется для разбора причины. */
        const val ХВОСТ_ОТКАЗА: Int = 300

        /** Факты, живые для сверки: решение человека уже есть либо оно отложено. */
        val ЖИВЫЕ: Set<Disposition> = setOf(Disposition.ADOPTED, Disposition.NOTED)

        /**
         * Вопрос и вердикт идут парой. Нехватки здесь нет намеренно: её
         * считает сервер по `must_link`, и мнение модели о ней не спрашивается.
         */
        val ПАРЫ: Map<Question, Verdict> = mapOf(
            Question.DUPLICATE to Verdict.AUGMENT,
            Question.CONTRADICTION to Verdict.CONTRADICT,
            Question.CONNECTION to Verdict.CONFIRM,
        )

        /** Что человек может сделать с находкой смысла — одно действие на карточку. */
        val ПРЕДЛОЖЕНИЯ: Map<Verdict, List<Action>> = mapOf(
            Verdict.AUGMENT to listOf(
                Action.MERGE_INTO, Action.REFINE, Action.GENERALIZE, Action.ACCEPT_NEW, Action.DISMISS,
            ),
            Verdict.CONTRADICT to listOf(Action.MARK_CONTESTED, Action.FIX_INPUT, Action.DISMISS),
            Verdict.CONFIRM to listOf(Action.LINK_BASIS, Action.DISMISS),
        )
    }
}
