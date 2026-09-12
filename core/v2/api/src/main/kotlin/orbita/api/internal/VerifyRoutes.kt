// Маршруты верификации документа против поля знаний (ЗНАНИЯ-V2, мера ПМИ-6
// п. 2.10).
//
// Отдельным файлом, а не дописыванием в DocRoutes: тот уже 280 строк при
// лимите 300 (ТЗ-BACKEND §3), и верификация — другой вопрос. Полнота
// отвечает «всё ли заполнено», базирование — «что зафиксировали», а здесь
// спрашивают третье: СХОДИТСЯ ЛИ написанное с полем.
//
// Правок нет ни одной. Ни «исправить», ни тем более «исправить всё»: служба
// заводит отчёт, исправляет человек своей правкой в своей сцене. Всё, что
// умеет этот файл помимо чтения, — перенести находку в замечание обзора тем
// же путём, каким замечания заводит точка.
package orbita.api.internal

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.api.api.Actor
import orbita.documents.api.DocumentView
import orbita.documents.api.Documents
import orbita.documents.api.Verification
import orbita.documents.api.VerificationReport
import orbita.kernel.api.EntityStore
import orbita.kernel.api.KnowledgeFlag
import orbita.process.api.RoleRefusedException

class VerifyRoutes(
    private val store: EntityStore,
    private val documents: Documents,
    private val verification: Verification,
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    private val записи = GateRecords(store, mapper)

    private val поДокументу = Regex("/v2/documents/([a-z_]+)/(verify|verification)")
    private val одинОтчёт = Regex("/v2/verification/(VR-[0-9]+)")
    private val закрыть = Regex("/v2/verification/(VR-[0-9]+)/close")
    private val перенос = Regex("/v2/verification/(VR-[0-9]+)/items/([0-9]+)/finding")

    /**
     * Роли обзора — те же, что заводят замечание на точке (PointRoutes):
     * замечание обзора пишет тот, кто обзор ведёт. Без учётки (стенд без
     * входа) роли не спрашиваются — отказывать некому.
     */
    private val обзор = setOf("lead", "lead_se", "da_review")

    fun handle(
        method: String,
        path: String,
        query: Map<String, String>,
        body: String?,
        actor: Actor? = null,
    ): V2Router.Ответ? {
        if (!path.startsWith("/v2/verification/") && !поДокументу.matches(path)) return null
        val проект = query["project"]?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("укажите ?project=<код проекта>")
        // Ворота флага — до разбора пути: на проекте прохода ПМИ-5 проверки
        // против поля нет целиком, и отказ обязан сказать, что делать.
        if (!KnowledgeFlag.on(store, проект)) return выключено()
        return when {
            method == "POST" && поДокументу.matches(path) && path.endsWith("/verify") ->
                проверить(проект, документ(path), разобрать(body))

            method == "GET" && поДокументу.matches(path) && path.endsWith("/verification") ->
                отчёты(проект, документ(path))

            method == "POST" && перенос.matches(path) -> {
                val (код, номер) = перенос.matchEntire(path)!!.destructured
                вЗамечание(проект, код, номер.toInt(), разобрать(body), actor)
            }

            method == "POST" && закрыть.matches(path) ->
                закрытие(проект, закрыть.matchEntire(path)!!.groupValues[1], разобрать(body))

            method == "GET" && одинОтчёт.matches(path) ->
                V2Router.Ответ(200, вид(verification.report(проект, одинОтчёт.matchEntire(path)!!.groupValues[1])))

            else -> null
        }
    }

    /**
     * Автоотчёт после базирования: документ зафиксирован — самое время
     * спросить, сходится ли он с полем.
     *
     * Зовётся СБОКУ, маршрутом базирования: базирует документ его
     * собственный адрес, и второй записи базовой линии здесь не заводится.
     * `null` — поле знаний v2 на проекте выключено: на проекте прохода
     * базирование идёт в точности как прежде, без единой новой записи.
     */
    fun послеБазирования(
        project: String,
        document: String,
        author: String,
        baseline: String? = null,
    ): ObjectNode? {
        if (!KnowledgeFlag.on(store, project)) return null
        return вид(verification.verify(project, document, author, baseline))
    }

    private fun проверить(проект: String, документ: String, тело: ObjectNode): V2Router.Ответ {
        val отчёт = verification.verify(
            проект, документ,
            author = тело.path("author").asText("").trim(),
            baseline = тело.path("baseline").asText("").ifBlank { null },
        )
        // 201, а не 200: проверка НИЧЕГО не правит, но отчёт — новая запись,
        // и каждая проверка заводит свой. Обновляемый отчёт стирал бы
        // историю расхождения.
        return V2Router.Ответ(201, вид(отчёт))
    }

    private fun отчёты(проект: String, документ: String): V2Router.Ответ {
        val узел = mapper.createObjectNode().put("document", документ)
        val массив = узел.putArray("items")
        verification.reports(проект, документ).forEach { массив.add(вид(it)) }
        return V2Router.Ответ(200, узел)
    }

    private fun закрытие(проект: String, код: String, тело: ObjectNode): V2Router.Ответ = V2Router.Ответ(
        200,
        вид(
            verification.close(
                проект, код,
                author = тело.path("author").asText("").trim(),
                reason = тело.path("reason").asText("").trim(),
            ),
        ),
    )

    /**
     * Находка → замечание обзора: единственное действие этого файла, которое
     * что-то заводит. Возврат идёт в сцену РАЗДЕЛА, где находка сидит: иначе
     * замечание попадает в общий список и чинить его некому.
     */
    private fun вЗамечание(
        проект: String,
        код: String,
        номер: Int,
        тело: ObjectNode,
        actor: Actor?,
    ): V2Router.Ответ {
        val роли = actor?.roles
        if (роли != null && роли.none { it in обзор }) {
            throw RoleRefusedException(
                "замечание обзора заводит руководитель проекта, ведущий системный инженер или DA; " +
                    "ваша роль — ${роли.joinToString(", ").ifBlank { "нет" }}",
            )
        }
        val запись = verification.report(проект, код)
        val находка = запись.items.getOrNull(номер - 1)
            ?: throw NoSuchElementException(
                "в отчёте «$код» находки № $номер нет: находок ${запись.items.size}",
            )
        val автор = тело.path("author").asText("").trim()
        require(автор.isNotBlank()) { "замечание заводит человек: назовите автора" }
        val карточка = documents.document(проект, запись.document)
        val точка = тело.path("gate").asText("").ifBlank { карточка.gate }
        val род = тело.path("kind").asText("").ifBlank { if (точка == "MCR") "rfa" else "finding" }
        require(род in setOf("finding", "rfa", "rid")) { "вид замечания: finding · rfa · rid, получено «$род»" }
        val сцена = тело.path("returns_to_scene").asText("").trim()
            .ifBlank { сценаРаздела(карточка, находка.element).orEmpty() }
        require(сцена.isNotBlank()) {
            "раздел находки не назвал сцену возврата — назовите её: {\"returns_to_scene\":\"9\"}"
        }
        val замечание = записи.addFinding(
            проект, точка,
            text = "${находка.issue.word} · ${находка.element}: ${находка.detail}" +
                (находка.proposal?.takeIf { it.isNotBlank() }?.let { " (предложение: $it)" } ?: ""),
            scene = сцена, author = автор, kind = род, question = null,
            // Сцена рождения замечания — сцена обзора (истина схем: finding
            // рождается в 15–17), а не сцена, куда оно возвращает работу.
            bornIn = when (точка) { "MCR" -> "16"; "KDP-A" -> "18"; else -> "15" },
        )
        return V2Router.Ответ(
            201,
            PhaseJson.замечание(замечание, mapper).put("verification", код).put("item", номер),
        )
    }

    private fun сценаРаздела(карточка: DocumentView, элемент: String): String? {
        // Адрес находки — `mid`: код элемента либо «код#строка» у запроса.
        val код = элемент.substringBefore("#")
        return карточка.sections.firstOrNull { раздел -> раздел.elements.any { it.code == код } }
            ?.scenes?.firstOrNull()
    }

    /** Отчёт — ЕДИНСТВЕННЫЙ сериализатор верификации в маршрутах. */
    private fun вид(отчёт: VerificationReport): ObjectNode {
        val узел = mapper.createObjectNode()
            .put("code", отчёт.code)
            .put("document", отчёт.document)
            .put("baseline", отчёт.baseline ?: "")
            .put("baseline_name", отчёт.baselineName ?: "")
            .put("at", отчёт.at)
            .put("status", отчёт.status)
            .put("open", отчёт.isOpen)
            // Сводка словами: «проверять нечего» либо счёт находок по родам —
            // её и показывает строка базирования.
            .put("summary", отчёт.summary)
            .put("total", отчёт.items.size)
        val роды = узел.putObject("by_issue")
        отчёт.byIssue().forEach { (род, сколько) -> роды.put(род.code, сколько) }
        val массив = узел.putArray("items")
        отчёт.items.forEachIndexed { индекс, находка ->
            массив.addObject()
                // Номер находки в отчёте — им она переносится в замечание.
                .put("n", индекс + 1)
                .put("element", находка.element)
                .put("issue", находка.issue.code)
                .put("issue_word", находка.issue.word)
                .put("detail", находка.detail)
                .put("proposal", находка.proposal ?: "")
        }
        return узел
    }

    private fun выключено(): V2Router.Ответ = V2Router.Ответ(
        409,
        mapper.createObjectNode()
            .put("error", "поле знаний v2 на этом проекте выключено: документ против поля здесь не проверяется")
            .put(
                "what_to_do",
                "проверяйте на проекте с полем знаний v2 — оно включается при открытии проекта: " +
                    "POST /v2/projects {\"knowledge_v2\":true}",
            ),
    )

    private fun документ(path: String): String = поДокументу.matchEntire(path)!!.groupValues[1]

    private fun разобрать(body: String?): ObjectNode =
        (mapper.readTree(body ?: "{}") as? ObjectNode) ?: mapper.createObjectNode()
}
