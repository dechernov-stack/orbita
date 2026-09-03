// Операции контура требований поверх хранилища шага 1 (TZ-REQ-001…008).
// Механика версий и Baseline-защита переиспользуются из core/mod, не дублируются.
package orbita.req

import com.fasterxml.jackson.databind.JsonNode
import orbita.mod.model.Lifecycle
import orbita.mod.schema.SchemaRegistry
import orbita.mod.store.LinkStore
import orbita.mod.store.ModelViolationException
import orbita.mod.store.ObjectStore
import orbita.mod.store.ResultStore
import orbita.mod.store.StoredObject
import orbita.mod.store.tx
import java.sql.Connection

class ReqService(
    private val conn: Connection,
    private val registry: SchemaRegistry,
    private val quality: QualityControl = QualityControl(),
    val gates: Gates = Gates(),
) {
    val objects = ObjectStore(conn)
    val links = LinkStore(conn)
    val results = ResultStore(conn)
    private val baselining = Baselining(quality)

    // ---------- приём объектов: валидация + связи из документа (TZ-REQ-003) ----------

    /**
     * Прикладные правила вида объекта — сверх схемы. ЕДИНСТВЕННОЕ их место:
     * применяются и при приёме (`ingest*`), и при правке через интерфейс
     * (шаг 15 §1.3), и при импорте (ADR-024), и в фильтре предложений ИИ.
     *
     * Отдельная «облегчённая» проверка для форм была бы ровно той ошибкой,
     * что уже случалась с предложениями ИИ на шаге 5: расхождение заводится
     * не в правилах, а во втором их экземпляре.
     */
    private val gateLabels: Map<String, String> by lazy {
        val res = ReqService::class.java.getResourceAsStream("/orbita/req/gate-labels-ru.json")
            ?: return@lazy emptyMap()
        com.fasterxml.jackson.databind.ObjectMapper().readTree(res).properties()
            .filter { (k, _) -> !k.startsWith("_") }
            .associate { (k, v) -> k to v.asText() }
    }

    fun gateLabel(gate: String): String =
        (gateLabels[gate] ?: gate).replaceFirstChar { it.uppercase() }

    /** Родительный падеж подписи — для «раньше …» (внутреннего обзора). */
    private fun gateLabelGen(gate: String): String = when (gate) {
        "internal_review" -> "внутреннего обзора"
        "Launch" -> "пуска"
        "EOM" -> "завершения миссии"
        else -> gateLabels[gate] ?: gate
    }

    /**
     * Автораспределение на корень (ОТВЕТЫ-Т1-ДОП §2): при появлении
     * ЕДИНСТВЕННОГО корня дерева состава требования уровня проекта без
     * носителя получают его — хранимой правкой с происхождением auto_root,
     * автором-человеком (чьё действие породило корень) и ОДНОЙ сводной
     * записью-основанием. Идемпотентно: только пустые allocated_to.
     */
    fun autoAllocateOnRoot(projectId: String, author: String): List<String> {
        val cur = objects.listCurrent(projectId)
        val roots = cur.filter {
            it.type == "component_usage" && it.status != Lifecycle.Cancelled &&
                it.doc.path("parent_usage").asText("").isBlank()
        }
        val rootDef = roots.singleOrNull()?.doc?.path("definition_ref")?.asText("")?.ifBlank { null }
            ?: return emptyList()
        val targets = cur.filter {
            it.type == "requirement" && it.status != Lifecycle.Cancelled &&
                it.doc.path("level").asText() == "project" &&
                it.doc.path("allocated_to").none { a ->
                    a.path("component").asText("").isNotBlank() || a.path("interface").asText("").isNotBlank()
                }
        }
        if (targets.isEmpty()) return emptyList()
        val note = "Распределено на корень: ${targets.size} " +
            (if (targets.size == 1) "требование" else "требований") +
            " уровня проекта — автоматически, по правилу уровня проекта"
        return targets.map { r ->
            val doc = r.doc.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
            doc.putArray("allocated_to").addObject()
                .put("component", rootDef).put("kind", "full")
                .putObject("provenance").put("source", "auto_root")
            val stored = objects.change(r.id, doc, changeRef = note, createdBy = author)
            syncLinks("requirement", stored.id, stored.doc, stored.projectId)
            stored.id
        }
    }

    /**
     * Симметрия отмены (ОТВЕТЫ-Т1-ДОП §2): корень уходит — автосвязи гаснут
     * той же сводной записью; РУЧНЫЕ распределения на корень — отказ с
     * перечнем (исполняет вызывающий канал по возвращённому списку).
     */
    fun releaseAutoRoot(projectId: String, rootDef: String, author: String): Pair<List<String>, List<String>> {
        val cur = objects.listCurrent(projectId).filter {
            it.type == "requirement" && it.status != Lifecycle.Cancelled
        }
        val onRoot = cur.filter { r ->
            r.doc.path("allocated_to").any { it.path("component").asText("") == rootDef }
        }
        val manual = onRoot.filter { r ->
            r.doc.path("allocated_to").any {
                it.path("component").asText("") == rootDef &&
                    it.path("provenance").path("source").asText("") != "auto_root"
            }
        }.map { it.id }
        if (manual.isNotEmpty()) return emptyList<String>() to manual
        val auto = onRoot.filter { r ->
            r.doc.path("allocated_to").any { it.path("provenance").path("source").asText("") == "auto_root" }
        }
        val note = "Снято распределение на корень: ${auto.size} " +
            (if (auto.size == 1) "требование" else "требований") +
            " уровня проекта — отмена корня"
        val released = auto.map { r ->
            val doc = r.doc.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
            doc.remove("allocated_to")
            val stored = objects.change(r.id, doc, changeRef = note, createdBy = author)
            syncLinks("requirement", stored.id, stored.doc, stored.projectId)
            stored.id
        }
        return released to emptyList()
    }

    fun requireApplicationRules(type: String, doc: JsonNode) {
        when (type) {
            // Круг 2 стартового потока: порядок дат вех — инвариант, ОДНО
            // правило на сервере (создание проекта, паспорт, любая правка
            // дат). Частично заданные даты законны: сравниваются только
            // соседние ЗАДАННЫЕ; «дата не задана» — законно всегда.
            "project" -> {
                var prevGate: String? = null
                var prevDue: String? = null
                doc.path("milestones").forEach { m ->
                    val gate = m.path("gate").asText("")
                    val due = m.path("due").asText("")
                    if (due.isNotBlank()) {
                        if (prevDue != null && due < prevDue!!) {
                            throw ModelViolationException(
                                "${gateLabel(gate)} не может быть раньше ${gateLabelGen(prevGate!!)} — " +
                                    "даты идут по порядку точек",
                            )
                        }
                        prevGate = gate
                        prevDue = due
                    }
                }
            }
            // В2.1: композиция вхождений — строго дерево: один владелец
            // (одно поле parent_usage) и ацикличность; разделяемое — связи
            // uses/hosted_on, не второй родитель. Определение обязано
            // существовать: вхождение без определения — не вхождение.
            "component_usage" -> {
                val definition = doc.path("definition_ref").asText("")
                val def = objects.current(definition)
                    ?: throw ModelViolationException(
                        "В2.1: definition_ref '$definition' not found — вхождение ссылается на определение")
                if (def.type != "component") {
                    throw ModelViolationException("В2.1: '$definition' is not a component definition")
                }
                val selfId = doc.path("id").asText("")
                var cursor = doc.path("parent_usage").asText("")
                val visited = mutableSetOf<String>()
                while (cursor.isNotBlank()) {
                    if (cursor == selfId || cursor in visited) {
                        throw ModelViolationException(
                            "В2.1: composition cycle through '$cursor' — дерево вхождений ациклично")
                    }
                    visited += cursor
                    val parent = objects.current(cursor)
                        ?: throw ModelViolationException("В2.1: parent_usage '$cursor' not found")
                    if (parent.type != "component_usage") {
                        throw ModelViolationException("В2.1: parent_usage '$cursor' is not a component_usage")
                    }
                    cursor = parent.doc.path("parent_usage").asText("")
                }
            }
            "requirement" -> {
                doc.path("traces_up").forEach { t ->
                    val ref = t.path("ref").asText()
                    if (ref.startsWith("SV-") && t.path("consumer_class").asText("").isBlank()) {
                        throw ModelViolationException(
                            "TZ-REQ-003 (Р9/ADR-009): reference to service $ref requires consumer_class"
                        )
                    }
                    // Трассировка — в существующий объект, как и распределение
                    // (TZ-REQ-005): прежде 212 требований молча записались со
                    // ссылками на невнесённые сервисы, и нить трассировки
                    // родилась порванной (находка второго захода: пропущенный
                    // шаг «нужды -> сервисы» никто не назвал)
                    if (ref.isNotBlank() && objects.current(ref) == null) {
                        throw ModelViolationException(
                            "TZ-REQ-003: trace to missing object '$ref' — источник не внесён " +
                                "(не пропущен ли шаг внесения сервисов или нужд?)"
                        )
                    }
                }
                // CR-001/CR-003: распределение — объект {component|interface, kind, rationale}
                doc.path("allocated_to").forEach { a ->
                    val target = a.path("component").asText("").ifBlank { a.path("interface").asText("") }
                        .ifBlank { a.path("model_element").asText("") }
                    objects.current(target)
                        ?: throw ModelViolationException("TZ-REQ-005: allocation to missing element $target")
                }
                // CR-003: распределение интерфейсного требования на интерфейс —
                // проверка ПОЛНОТЫ, и она стоит на базировании (Baselining), а
                // не здесь: на шаге «требования из сервисов» дерева изделия ещё
                // нет, и запрет записи останавливал бы работу (CR-002 ловушка 5).
                // CR-001: декомпозиция — отдельная связь derive, не trace
                doc.path("derives_from").forEach { parent ->
                    objects.current(parent.asText())
                        ?: throw ModelViolationException(
                            "TZ-REQ-005 (ADR-017): derive from missing requirement ${parent.asText()}"
                        )
                }
                // ADR-050: покрытие — функцией, цепочкой, иллюстрацией; ссылка
                // на несуществующее не принимается, как и всякая другая
                listOf("satisfied_by" to "function", "realized_by" to "function_chain",
                       "illustrated_by" to "model_element").forEach { (field, type) ->
                    doc.path(field).forEach { ref ->
                        objects.current(ref.asText())?.takeIf { it.type == type }
                            ?: throw ModelViolationException(
                                "ADR-050: $field ведёт на отсутствующий $type ${ref.asText()}",
                            )
                    }
                }
                // ADR-045: связь требования — с обоснованием, к существующему
                // требованию и не к себе; противоречие с самим собой — бессмыслица
                val selfId = doc.path("id").asText("")
                doc.path("relations").forEach { rel ->
                    val ref = rel.path("ref").asText("")
                    if (ref == selfId) throw ModelViolationException("ADR-045: связь ${rel.path("kind").asText()} требования $selfId на само себя")
                    objects.current(ref)?.takeIf { it.type == "requirement" }
                        ?: throw ModelViolationException("ADR-045: связь ${rel.path("kind").asText()} на отсутствующее требование $ref")
                    if (rel.path("rationale").asText("").isBlank()) {
                        throw ModelViolationException("ADR-045: связь ${rel.path("kind").asText()} $selfId → $ref без обоснования не принимается")
                    }
                }
            }

            // ADR-047: функция следует из существующих нужд/сервисов/ConOps/функций
            // и распределяется на существующие узлы состава либо интерфейсы
            "function" -> {
                doc.path("traces_up").forEach { t ->
                    val ref = t.path("ref").asText("")
                    if (ref.isNotBlank() && objects.current(ref) == null) {
                        throw ModelViolationException("ADR-047: функция следует из отсутствующего объекта $ref")
                    }
                }
                doc.path("allocated_to").forEach { a ->
                    val target = a.path("component").asText("").ifBlank { a.path("interface").asText("") }
                    objects.current(target)
                        ?: throw ModelViolationException("ADR-047: функция распределена на отсутствующий узел $target")
                }
            }

            "interface" -> {
                val owners = doc.path("owners")
                if (!owners.isArray || owners.size() != 2) {
                    throw ModelViolationException(
                        "CR-003 (ADR-019): interface ${doc.path("id").asText()} requires exactly two sides (owners)"
                    )
                }
            }

            // Шаг 17 C3: решение decided без выбора или обоснования — запись
            // «решили, но не скажем что», хуже отсутствия записи
            "decision" -> {
                if (doc.path("status").asText() == "decided") {
                    val selected = doc.path("selected").asText("")
                    val names = doc.path("alternatives").map { it.path("name").asText() }
                    val issues = buildList {
                        if (selected.isBlank()) add("нет выбранной альтернативы")
                        else if (selected !in names) add("выбранная альтернатива '$selected' не среди перечисленных")
                        if (doc.path("rationale").asText("").isBlank()) add("нет обоснования")
                    }
                    if (issues.isNotEmpty()) {
                        throw ModelViolationException("C3: решение decided — " + issues.joinToString("; "))
                    }
                }
            }

            // Блок C: закрытое замечание без ответа — «устранили, но не скажем как»
            "review_item" -> {
                val closed = doc.path("status").asText() == "closed"
                if (closed && doc.path("response").asText("").isBlank()) {
                    throw ModelViolationException(
                        "C: замечание закрывается с ответом (response) — как устранено"
                    )
                }
                // Критическое замечание не закрывается голым текстом (находка
                // второго захода): текст — обещание, подтверждение — ссылки на
                // изменённые карточки и выпуски документов. Некритическим
                // (comment/question/recommendation) достаточно ответа.
                val refs = doc.path("resolution_refs").map { it.asText() }
                if (closed && doc.path("classification").asText() == "critical" && refs.isEmpty()) {
                    throw ModelViolationException(
                        "C: критическое замечание закрывается с подтверждением — " +
                            "укажите в resolution_refs изменённые карточки и/или выпуски " +
                            "документов, которыми оно устранено"
                    )
                }
                // Ссылка на несуществующий объект — не подтверждение, а опечатка
                refs.forEach { ref ->
                    objects.current(ref) ?: throw ModelViolationException(
                        "C: resolution_refs ссылается на несуществующий объект '$ref'"
                    )
                }
            }

            // Шаг 17 C5: одобрение безымянным не бывает
            "document_issue" -> {
                if (doc.path("status").asText() == "approved" &&
                    doc.path("approved_by").asText("").isBlank()
                ) {
                    throw ModelViolationException("C5: approved требует approved_by — кто одобрил")
                }
            }

            "validation" -> validationIssues(doc).takeIf { it.isNotEmpty() }?.let {
                throw ModelViolationException("CR-003 (ADR-019): " + it.joinToString("; "))
            }

            "risk" -> {
                val issues = riskIssues(doc).toMutableList()
                if (!residualOk(doc)) issues += "остаточный риск выше исходного"
                if (issues.isNotEmpty()) {
                    throw ModelViolationException("NPR 8000.4: " + issues.joinToString("; "))
                }
            }
        }
    }

    /** Нужда (TZ-REQ-001): стейкхолдер обязателен схемой; traces_down порождает связи. */
    fun ingestNeed(
        json: String,
        createdBy: String = "api",
        projectId: String = ObjectStore.DEFAULT_PROJECT,
    ): StoredObject {
        val doc = registry.parse(json)
        registry.require("core/need", doc)
        return conn.tx {
            val stored = create(doc, "need", createdBy, projectId)
            // связи — тем же пересчётом, что и при правке (ADR-027): один вход
            syncLinks("need", stored.id, doc, projectId)
            stored
        }
    }

    /** Сервис (TZ-REQ-002): QoS-профили по классам обязательны схемой (Р9). */
    fun ingestService(
        json: String,
        createdBy: String = "api",
        projectId: String = ObjectStore.DEFAULT_PROJECT,
    ): StoredObject {
        val doc = registry.parse(json)
        registry.require("core/service", doc)
        return conn.tx {
            val stored = create(doc, "service", createdBy, projectId)
            syncLinks("service", stored.id, doc, projectId)
            stored
        }
    }

    /**
     * Требование (TZ-REQ-003, TZ-REQ-005): ссылка на сервис без consumer_class
     * отклоняется (Р9); распределение на несуществующий элемент отклоняется.
     * Связи выводятся из документа — матрицы формируются из них, не вручную.
     */
    fun ingestRequirement(
        json: String,
        createdBy: String = "api",
        projectId: String = ObjectStore.DEFAULT_PROJECT,
    ): StoredObject {
        val doc = registry.parse(json)
        registry.require("core/requirement", doc)
        requireApplicationRules("requirement", doc)
        return conn.tx {
            val stored = create(doc, "requirement", createdBy, projectId)
            // CR-003: вид декомпозиции — свойство СВЯЗИ (ADR-017/019); документ
            // объявляет родителей, по умолчанию распределение бюджета, производное
            // помечается deriveAs. Сами связи — тем же пересчётом, что и правка.
            syncLinks("requirement", stored.id, doc, projectId)
            stored
        }
    }

    /**
     * Пересчёт связей объекта по документу (ADR-027, шаг 16 §3.1): документ —
     * единственный источник trace, allocation и derive. Ссылка исчезла — связь
     * удаляется; появилась — создаётся; уцелевшая правится НА МЕСТЕ, и
     * derivation_kind, выставленный deriveAs, переживает правку.
     *
     * Связь need→service объявляется с двух сторон (need.traces_down и
     * service.traces_up): удаляется, только когда её не объявляет НИ ОДИН
     * из двух документов — иначе правка сервиса молча рвала бы нить,
     * которую нужда продолжает объявлять.
     *
     * Эталон spec/link_semantics.py, один в один.
     */
    fun syncLinks(
        type: String,
        id: String,
        doc: JsonNode,
        projectId: String = ObjectStore.DEFAULT_PROJECT,
    ) {
        data class Attrs(
            val consumerClass: String? = null,
            val allocationKind: String? = null,
            val rationale: String? = null,
            val derivationKind: String? = null,
        )

        fun desired(objType: String, objId: String, d: JsonNode): Map<Triple<String, String, String>, Attrs> =
            buildMap {
                when (objType) {
                    "need" -> d.path("traces_down").forEach { sv ->
                        put(Triple(objId, sv.asText(), "trace"), Attrs())
                    }
                    "service" -> d.path("traces_up").forEach { nd ->
                        put(Triple(nd.asText(), objId, "trace"), Attrs())
                    }
                    // ConOps-сценарий разворачивает нужды (Шаг 17 C1)
                    "conops" -> d.path("traces_up").forEach { nd ->
                        put(Triple(nd.asText(), objId, "trace"), Attrs())
                    }
                    // Цель миссии выводится из нужд (блок C; БП-PPA О2)
                    "mission_goal" -> d.path("traces_up").forEach { nd ->
                        put(Triple(nd.asText(), objId, "trace"), Attrs())
                    }
                    // ADR-048: связь требования с внешним элементом — распределение
                    // с обоснованием «вид: почему»; матрицы и impact видят элемент носителем
                    "arch_link" -> put(
                        Triple(d.path("requirement").asText(), d.path("element").asText(), "allocation"),
                        Attrs(allocationKind = "full", rationale = d.path("relation").asText("") + ": " + d.path("rationale").asText("")),
                    )
                    // ADR-050: цепочка — трассировка от сценария и нити на функции шагов
                    "function_chain" -> {
                        d.path("traces_up").forEach { ref -> put(Triple(ref.asText(), objId, "trace"), Attrs()) }
                        d.path("steps").forEach { step ->
                            put(Triple(objId, step.path("function").asText(), "allocation"), Attrs(allocationKind = "full"))
                        }
                    }
                    // ADR-052: способность (OA) привязана к целям, сервисам, нуждам и
                    // сценариям; покрытия она не даёт — это слой «зачем»
                    "capability" -> d.path("traced_to").forEach { t ->
                        put(Triple(t.path("ref").asText(), objId, "trace"), Attrs(rationale = t.path("rationale").asText(null)))
                    }
                    // ADR-052: логический компонент (LA) группирует функции и
                    // РАЗВОРАЧИВАЕТСЯ на узлы состава — второго дерева не заводит
                    "logical_component" -> {
                        d.path("functions").forEach { fn ->
                            put(Triple(objId, fn.asText(), "allocation"), Attrs(allocationKind = "full"))
                        }
                        d.path("deployed_to").forEach { cm ->
                            put(Triple(objId, cm.asText(), "hosted_on"), Attrs())
                        }
                    }
                    // ADR-047: функция — трассировка от источника, распределение на узлы
                    "function" -> {
                        d.path("traces_up").forEach { t ->
                            put(Triple(t.path("ref").asText(), objId, "trace"), Attrs(consumerClass = t.path("consumer_class").asText(null)))
                        }
                        d.path("allocated_to").forEach { a ->
                            val target = a.path("component").asText("").ifBlank { a.path("interface").asText("") }
                            put(Triple(objId, target, "allocation"), Attrs(allocationKind = a.path("kind").asText("full"), rationale = a.path("rationale").asText(null)))
                        }
                    }
                    "requirement" -> {
                        d.path("traces_up").forEach { t ->
                            put(
                                Triple(t.path("ref").asText(), objId, "trace"),
                                Attrs(consumerClass = t.path("consumer_class").asText(null)),
                            )
                        }
                        d.path("allocated_to").forEach { a ->
                            val target = a.path("component").asText("").ifBlank { a.path("interface").asText("") }
                                .ifBlank { a.path("model_element").asText("") }
                            put(
                                Triple(objId, target, "allocation"),
                                Attrs(
                                    allocationKind = a.path("kind").asText("full"),
                                    rationale = a.path("rationale").asText(null),
                                ),
                            )
                        }
                        d.path("derives_from").forEach { parent ->
                            put(Triple(parent.asText(), objId, "derive"), Attrs(derivationKind = "allocated"))
                        }
                        // ADR-045: связи с обоснованием. refines — уточнение
                        // (derived, в свёртку не входит), derives — разбиение
                        // (allocated, входит); противоречие — своим видом связи;
                        // depends_on живёт в документе, в таблицу не кладётся
                        d.path("relations").forEach { rel ->
                            val ref = rel.path("ref").asText("")
                            val why = rel.path("rationale").asText("").ifBlank { null }
                            when (rel.path("kind").asText("")) {
                                "refines" -> put(Triple(ref, objId, "derive"), Attrs(derivationKind = "derived", rationale = why))
                                "derives" -> put(Triple(ref, objId, "derive"), Attrs(derivationKind = "allocated", rationale = why))
                                "conflicts_with" -> put(Triple(objId, ref, "conflict"), Attrs(rationale = why))
                            }
                        }
                    }
                }
            }

        val want = desired(type, id, doc).toMutableMap()
        // В2: развитие определения, связь состав↔WBS, разделяемое вхождений,
        // стоимость на элементе работ — всё из документа (ADR-027)
        doc.path("evolves_from").asText("").takeIf { it.isNotBlank() }?.let { prev ->
            want[Triple(prev, id, "evolves")] = Attrs()
        }
        doc.path("wbs_refs").forEach { wb ->
            want[Triple(id, wb.asText(), "wbs")] = Attrs()
        }
        // связи вхождений (uses · hosted_on · evolves) — видом связи как есть;
        // связи ТРЕБОВАНИЯ (ADR-045) разложены выше в desired: их виды —
        // decompose с обоснованием и conflict, не имена из документа
        if (type != "requirement") {
            doc.path("relations").forEach { r ->
                val kindName = r.path("kind").asText("")
                val ref = r.path("ref").asText("")
                if (kindName.isNotBlank() && ref.isNotBlank()) {
                    want[Triple(id, ref, kindName)] = Attrs()
                }
            }
        }
        doc.path("wbs_ref").asText("").takeIf { it.isNotBlank() }?.let { wb ->
            want[Triple(id, wb, "wbs")] = Attrs()
        }
        // Применение библиотечного фрагмента — у любого вида с полем applies
        // (ЗАДАЧА-CODE-БИБЛИОТЕКА §3): связь выводится из документа, как и
        // остальные (ADR-027). Статус и обоснование остаются в документе.
        doc.path("applies").path("ref").asText("").takeIf { it.isNotBlank() }?.let { proto ->
            want[Triple(id, proto, "applies")] = Attrs()
        }
        val existing = when (type) {
            "need" -> links.linksFrom(id, "trace")
            "service", "conops", "mission_goal" -> links.linksTo(id, "trace")
            "requirement" ->
                links.linksTo(id, "trace") + links.linksFrom(id, "allocation") + links.linksTo(id, "derive") +
                    links.linksFrom(id, "conflict")
            "function" -> links.linksTo(id, "trace") + links.linksFrom(id, "allocation")
            "function_chain" -> links.linksTo(id, "trace") + links.linksFrom(id, "allocation")
            "capability" -> links.linksTo(id, "trace")
            "logical_component" -> links.linksFrom(id, "allocation")
            "arch_link" -> links.linksFrom(doc.path("requirement").asText(""), "allocation")
                .filter { it.toId == doc.path("element").asText("") }
            else -> emptyList()
        } + links.linksFrom(id, "applies") + links.linksTo(id, "evolves") +
            links.linksFrom(id, "wbs") + links.linksFrom(id, "uses") + links.linksFrom(id, "hosted_on")

        fun declaredByOtherEnd(link: orbita.mod.store.Link): Boolean {
            if (link.kind != "trace" || type == "requirement") return false
            val otherId = if (type == "need") link.toId else link.fromId
            val other = objects.current(otherId) ?: return false
            return desired(other.type, other.id, other.doc)
                .containsKey(Triple(link.fromId, link.toId, link.kind))
        }

        existing.forEach { link ->
            val key = Triple(link.fromId, link.toId, link.kind)
            val attrs = want.remove(key)
            when {
                attrs != null -> links.updateAttrs(
                    link.fromId, link.toId, link.kind,
                    consumerClass = attrs.consumerClass,
                    allocationKind = attrs.allocationKind,
                    rationale = attrs.rationale,
                )
                declaredByOtherEnd(link) -> Unit
                else -> links.remove(link.fromId, link.toId, link.kind)
            }
        }
        want.forEach { (key, attrs) ->
            links.add(
                key.first, key.second, key.third,
                consumerClass = attrs.consumerClass,
                allocationKind = attrs.allocationKind,
                rationale = attrs.rationale,
                derivationKind = attrs.derivationKind,
                projectId = projectId,
            )
        }
    }

    /**
     * Вид декомпозиции задаётся явно (CR-003/ADR-019): allocated участвует
     * в свёртке бюджета, derived — нет (производное требование рождается из
     * проектного решения и долей родительской величины не является).
     */
    fun deriveAs(parentId: String, childId: String, derivationKind: String) {
        require(derivationKind in setOf("allocated", "derived")) {
            "ADR-019: derivation kind must be 'allocated' or 'derived', got '$derivationKind'"
        }
        conn.prepareStatement(
            "UPDATE links SET derivation_kind = ? WHERE from_id = ? AND to_id = ? AND kind = 'derive'"
        ).use { ps ->
            ps.setString(1, derivationKind)
            ps.setString(2, parentId)
            ps.setString(3, childId)
            if (ps.executeUpdate() == 0) {
                throw NoSuchElementException("derive link $parentId -> $childId not found")
            }
        }
    }

    /**
     * Состоятельность декомпозиции требования (CR-001/ADR-017): свёртка условий
     * дочерних требований по связям derive против родительского бюджета.
     */
    fun rollupFor(requirementId: String): RollupResult {
        val parent = objects.current(requirementId)
            ?: throw NoSuchElementException("object '$requirementId' not found")
        // CR-003: в бюджет входят только распределённые потомки, производные — нет
        val childIds = rollupChildIds(requirementId, links.linksFrom(requirementId, "derive"))
        val children = childIds.mapNotNull { objects.current(it) }.map { it.doc.path("mop") }
        return rollupCheck(parent.doc.path("mop"), children)
    }

    /** Требования, декомпозиция которых превышает родительский бюджет. */
    fun inconsistentDecompositions(projectId: String? = null): List<Pair<String, RollupResult>> =
        objects.listCurrent(projectId)
            .filter { it.type == "requirement" && it.status != Lifecycle.Cancelled }
            .mapNotNull { r ->
                val result = rollupFor(r.id)
                if (result.applicable && (result.error != null || result.consistent == false)) {
                    r.id to result
                } else null
            }

    /** Элемент архитектуры (TZ-REQ-005). */
    fun ingestComponent(
        json: String,
        createdBy: String = "api",
        projectId: String = ObjectStore.DEFAULT_PROJECT,
    ): StoredObject {
        val doc = registry.parse(json)
        registry.require("core/component", doc)
        return create(doc, "component", createdBy, projectId)
    }

    /** Интерфейс IF-NNNN: две стороны ответственности (CR-003/ADR-019). */
    fun ingestInterface(
        json: String,
        createdBy: String = "api",
        projectId: String = ObjectStore.DEFAULT_PROJECT,
    ): StoredObject {
        val doc = registry.parse(json)
        registry.require("core/interface", doc)
        requireApplicationRules("interface", doc)
        return create(doc, "interface", createdBy, projectId)
    }

    /**
     * Сценарий ConOps (Шаг 17 C1): против него выполняется валидация, из него
     * наполняется документ «Концепция применения». Связи с нуждами — тем же
     * пересчётом, что и у всех (ADR-027).
     */
    fun ingestConops(
        json: String,
        createdBy: String = "api",
        projectId: String = ObjectStore.DEFAULT_PROJECT,
    ): StoredObject {
        val doc = registry.parse(json)
        registry.require("core/conops", doc)
        doc.path("traces_up").forEach { nd ->
            objects.current(nd.asText())
                ?: throw ModelViolationException(
                    "C1: conops разворачивает несуществующую нужду ${nd.asText()}"
                )
        }
        return conn.tx {
            val stored = create(doc, "conops", createdBy, projectId)
            syncLinks("conops", stored.id, doc, projectId)
            stored
        }
    }

    /**
     * ADR-050: запись модели системы. Входы — параметры узлов дерева и выходы
     * других моделей; ссылка на несуществующий узел или интерфейс не
     * принимается (на полке узлов проекта нет — там вход описывается словами).
     */
    fun ingestSystemModel(json: String, createdBy: String = "api", projectId: String = ObjectStore.DEFAULT_PROJECT): StoredObject {
        val doc = registry.parse(json)
        registry.require("core/system-model", doc)
        fun requireRefs(node: JsonNode) {
            node.path("interface_ref").asText("").takeIf { it.isNotBlank() }?.let { iface ->
                objects.current(iface)?.takeIf { it.type == "interface" }
                    ?: throw ModelViolationException("ADR-050: модель привязана к отсутствующему интерфейсу $iface")
            }
            node.path("inputs").forEach { input ->
                input.path("node").asText("").takeIf { it.isNotBlank() }?.let { cm ->
                    objects.current(cm)?.takeIf { it.type == "component" }
                        ?: throw ModelViolationException("ADR-050: вход модели ведёт на отсутствующий узел $cm")
                }
                input.path("interface").asText("").takeIf { it.isNotBlank() }?.let { iface ->
                    objects.current(iface)?.takeIf { it.type == "interface" }
                        ?: throw ModelViolationException("ADR-050: вход модели ведёт на отсутствующий интерфейс $iface")
                }
            }
        }
        requireRefs(doc)
        doc.path("parts").forEach { requireRefs(it) }
        // Прокси без пометки — витрина (ловушка 4): выход прокси-модели обязан
        // называть себя прокси
        if (doc.path("status").asText("") == "proxy" && doc.path("outputs").any { !it.path("proxy").asBoolean(false) }) {
            throw ModelViolationException(
                "ADR-050: модель ${doc.path("code").asText("")} объявлена прокси, а выход не помечен proxy — прокси без пометки это витрина",
            )
        }
        return create(doc, "system_model", createdBy, projectId)
    }

    /** ADR-050: функциональная цепочка — шаги существующими функциями, источник — сценарий или нужда. */
    fun ingestFunctionChain(json: String, createdBy: String = "api", projectId: String = ObjectStore.DEFAULT_PROJECT): StoredObject {
        val doc = registry.parse(json)
        registry.require("core/function-chain", doc)
        doc.path("steps").forEach { step ->
            val fn = step.path("function").asText("")
            objects.current(fn)?.takeIf { it.type == "function" }
                ?: throw ModelViolationException("ADR-050: шаг цепочки ведёт на отсутствующую функцию $fn")
        }
        doc.path("traces_up").forEach { ref ->
            objects.current(ref.asText())
                ?: throw ModelViolationException("ADR-050: цепочка следует из отсутствующего объекта ${ref.asText()}")
        }
        return conn.tx {
            val stored = create(doc, "function_chain", createdBy, projectId)
            syncLinks("function_chain", stored.id, doc, projectId)
            stored
        }
    }

    /**
     * ADR-052: способность слоя OA. Привязка ведёт на существующие цели,
     * сервисы, нужды и сценарии — служба не заводит их за инженера; подсказка
     * полки («нужды класса A′») остаётся текстом, пока связь не поставлена.
     */
    fun ingestCapability(json: String, createdBy: String = "api", projectId: String = ObjectStore.DEFAULT_PROJECT): StoredObject {
        val doc = registry.parse(json)
        registry.require("core/capability", doc)
        doc.path("traced_to").forEach { t ->
            val ref = t.path("ref").asText("")
            objects.current(ref) ?: throw ModelViolationException("ADR-052: способность привязана к отсутствующему объекту $ref")
        }
        return conn.tx {
            val stored = create(doc, "capability", createdBy, projectId)
            syncLinks("capability", stored.id, doc, projectId)
            stored
        }
    }

    /**
     * ADR-052: логический компонент слоя LA. Группирует существующие функции и
     * разворачивается на узлы дерева состава: несуществующая функция или узел —
     * отказ, потому что развёртывание в пустоту не объясняет ничего.
     */
    fun ingestLogicalComponent(json: String, createdBy: String = "api", projectId: String = ObjectStore.DEFAULT_PROJECT): StoredObject {
        val doc = registry.parse(json)
        registry.require("core/logical-component", doc)
        doc.path("functions").forEach { fn ->
            objects.current(fn.asText())?.takeIf { it.type == "function" }
                ?: throw ModelViolationException("ADR-052: логический компонент группирует отсутствующую функцию ${fn.asText()}")
        }
        doc.path("deployed_to").forEach { cm ->
            objects.current(cm.asText())?.takeIf { it.type == "component" }
                ?: throw ModelViolationException("ADR-052: логический компонент развёрнут на отсутствующий узел ${cm.asText()}")
        }
        return conn.tx {
            val stored = create(doc, "logical_component", createdBy, projectId)
            syncLinks("logical_component", stored.id, doc, projectId)
            stored
        }
    }

    /** ADR-048: внешний элемент модели — узел-ссылка, идентичность по UUID; связей сам не несёт. */
    fun ingestModelElement(json: String, createdBy: String = "api", projectId: String = ObjectStore.DEFAULT_PROJECT): StoredObject {
        val doc = registry.parse(json)
        registry.require("core/model-element", doc)
        return create(doc, "model_element", createdBy, projectId)
    }

    /** ADR-048: связь требования с внешним элементом — обоснование обязательно, стороны существуют. */
    fun ingestArchLink(json: String, createdBy: String = "api", projectId: String = ObjectStore.DEFAULT_PROJECT): StoredObject {
        val doc = registry.parse(json)
        registry.require("core/arch-link", doc)
        val rq = doc.path("requirement").asText(""); val me = doc.path("element").asText("")
        objects.current(rq)?.takeIf { it.type == "requirement" } ?: throw ModelViolationException("ADR-048: связь на отсутствующее требование $rq")
        objects.current(me)?.takeIf { it.type == "model_element" } ?: throw ModelViolationException("ADR-048: связь на отсутствующий элемент модели $me")
        if (doc.path("rationale").asText("").isBlank()) throw ModelViolationException("ADR-048: связь $rq → $me без обоснования не принимается")
        return conn.tx {
            val stored = create(doc, "arch_link", createdBy, projectId)
            syncLinks("arch_link", stored.id, doc, projectId)
            stored
        }
    }

    /** ADR-047: функция — слой между нуждами и узлами; связи выводятся из документа. */
    fun ingestFunction(
        json: String,
        createdBy: String = "api",
        projectId: String = ObjectStore.DEFAULT_PROJECT,
    ): StoredObject {
        val doc = registry.parse(json)
        registry.require("core/function", doc)
        requireApplicationRules("function", doc)
        return conn.tx {
            val stored = create(doc, "function", createdBy, projectId)
            syncLinks("function", stored.id, doc, projectId)
            stored
        }
    }

    /** Цель миссии (блок C): traces_up разворачивает нужды связями, как ConOps. */
    fun ingestMissionGoal(
        json: String,
        createdBy: String = "api",
        projectId: String = ObjectStore.DEFAULT_PROJECT,
    ): StoredObject {
        val doc = registry.parse(json)
        registry.require("core/mission-goal", doc)
        return conn.tx {
            val stored = create(doc, "mission_goal", createdBy, projectId)
            syncLinks("mission_goal", stored.id, doc, projectId)
            stored
        }
    }

    /** Свидетельство EV-NNNN (CR-003/ADR-019). */
    fun ingestEvidence(
        json: String,
        createdBy: String = "api",
        projectId: String = ObjectStore.DEFAULT_PROJECT,
    ): StoredObject {
        val doc = registry.parse(json)
        registry.require("core/evidence", doc)
        return create(doc, "evidence", createdBy, projectId)
    }

    /**
     * Валидация VA-NNNN: привязка к ожиданию стейкхолдера, не к требованию
     * (CR-003/ADR-019). Прикладные правила дополняют схему.
     */
    fun ingestValidation(
        json: String,
        createdBy: String = "api",
        projectId: String = ObjectStore.DEFAULT_PROJECT,
    ): StoredObject {
        val doc = registry.parse(json)
        registry.require("core/validation", doc)
        requireApplicationRules("validation", doc)
        // Шаг 17 C1: conops_ref был строкой в никуда — валидация против
        // несуществующего сценария не проверка, а обещание проверки
        val conopsRef = doc.path("conops_ref").asText("")
        if (conopsRef.isNotBlank()) {
            val target = objects.current(conopsRef)
            if (target == null || target.type != "conops") {
                throw ModelViolationException(
                    "C1: валидация ссылается на несуществующий сценарий ConOps $conopsRef"
                )
            }
            // ссылка не создаёт связи, поэтому границу проекта проверяем здесь (ADR-022)
            if (target.projectId != projectId) {
                throw ModelViolationException(
                    "ADR-022: валидация проекта $projectId ссылается на сценарий " +
                        "$conopsRef чужого проекта ${target.projectId}"
                )
            }
        }
        return create(doc, "validation", createdBy, projectId)
    }

    /**
     * Риск RSK-NNNN (шаг 7). Прикладные правила дополняют схему: полнота
     * формулировки, стратегия и срок выше порога критичности, остаточная
     * оценка не выше исходной. Те же правила применяются к предложениям ИИ —
     * упрощённой версии для них нет.
     */
    fun ingestRisk(
        json: String,
        createdBy: String = "api",
        projectId: String = ObjectStore.DEFAULT_PROJECT,
    ): StoredObject {
        val doc = registry.parse(json)
        registry.require("core/risk", doc)
        requireApplicationRules("risk", doc)
        val stored = create(doc, "risk", createdBy, projectId)
        // связь риска с затронутыми объектами выводится из документа
        doc.path("affects").forEach { a ->
            val target = a.asText("")
            if (target.isNotBlank()) links.add(stored.id, target, "trace", projectId = projectId)
        }
        return stored
    }

    /**
     * Что мешает базированию — без попытки перевода (шаг 15 §1.3). Та же
     * функция, что решает переход в [promote]: черновик допускает неполноту,
     * Baseline — нет, и причины называются поимённо.
     */
    fun baselineIssues(type: String, doc: JsonNode, projectId: String? = null): List<String> =
        if (type == "requirement") baselining.canBaseline(doc, productTree(projectId)).second else emptyList()

    /** Полный вердикт базирования: блокирующее, отводимое, отведённое. */
    fun baselineVerdict(type: String, doc: JsonNode, projectId: String? = null): BaselineVerdict =
        if (type == "requirement") baselining.verdict(doc, productTree(projectId))
        else BaselineVerdict(emptyList(), emptySet(), emptyMap())

    /** Активные и закрытые риски: закрытый сохраняется в реестре. */
    fun risks(projectId: String? = null): List<JsonNode> = objects.listCurrent(projectId)
        .filter { it.type == "risk" && it.status != Lifecycle.Cancelled }
        .map { it.doc }

    private fun create(
        doc: JsonNode,
        type: String,
        createdBy: String,
        projectId: String,
    ): StoredObject {
        val lifecycle = doc.path("lifecycle")
        return objects.create(
            id = doc.path("id").asText(),
            type = type,
            doc = doc,
            status = Lifecycle.valueOf(lifecycle.path("status").asText(Lifecycle.Draft.name)),
            version = lifecycle.path("version").asText("1"),
            createdBy = createdBy,
            projectId = projectId,
        )
    }

    // ---------- статусы и базирование (TZ-REQ-006) ----------

    /**
     * Перевод статуса. В Baseline требование переводится только при выполнении
     * условий (TZ-REQ-004/006/007): качество, TBD/TBR, метод верификации.
     * Переход выполняется новой версией с закрытием интервала (ObjectStore.transition),
     * поэтому отчёт зрелости строится на произвольную дату по истории статусов.
     */
    /**
     * Применима ли зрелость к виду: её несёт схема (lifecycle) ЛИБО требуют
     * планки ворот (риск: в схеме цикла зрелости нет, но Д6 зреет к MCR —
     * §7.1е). Замечание обзора не подходит ни по одному критерию — живёт
     * только собственным циклом open → answered/closed. Этим же критерием
     * судит защита правки базированного: колоночный статус вида без
     * зрелости — не основание запирать правку (находка второго захода:
     * замечание с историческим Baseline не закрывалось).
     */
    fun maturityApplies(type: String): Boolean {
        val coreType = orbita.mod.model.CoreType.byDbType(type)
        return registry.raw(coreType.schemaName).path("properties").has("lifecycle") ||
            type in gates.typesWithStatusBar
    }

    fun promote(
        id: String,
        target: Lifecycle,
        createdBy: String = "ci-runner",
        at: java.time.OffsetDateTime = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC),
    ): StoredObject {
        val cur = objects.current(id) ?: throw NoSuchElementException("object '$id' not found")
        if (!maturityApplies(cur.type)) {
            throw orbita.mod.store.ModelViolationException(
                "TZ-COM-003: вид '${cur.type}' живёт собственным циклом (поле status) — " +
                    "статусная модель зрелости к нему не применяется",
            )
        }
        if (target == Lifecycle.Baseline && cur.type == "requirement" && cur.status != Lifecycle.Baseline) {
            val (ok, reasons) = baselining.canBaseline(cur.doc, productTree(cur.projectId))
            if (!ok) throw BaselineBlockedException(reasons)
        }
        return objects.transition(id, target, createdBy, at)
    }

    // ---------- верификация (TZ-REQ-007) ----------

    /**
     * Свидетельство по evidence_ref: ссылка на результат моделирования (pk).
     * Значение извлекается по имени MOP, запасной ключ — «value».
     * Устаревший результат честно передаётся как stale — статус решает verificationStatus.
     */
    fun evidenceFor(req: JsonNode): (String) -> Evidence? = { ref ->
        ref.toLongOrNull()?.let { pk ->
            results.byPk(pk)?.let { res ->
                val mopName = req.path("mop").path("name").asText("")
                val v = res.payload.path(mopName).takeIf { it.isNumber }
                    ?: res.payload.path("value").takeIf { it.isNumber }
                Evidence(v?.asDouble(), res.stale)
            }
        }
    }

    fun verificationStatusOf(id: String): VerificationStatus {
        val cur = objects.current(id) ?: throw NoSuchElementException("object '$id' not found")
        return verificationStatus(cur.doc, evidenceFor(cur.doc))
    }

    // ---------- отчёты целостности (TZ-REQ-001/002/005) ----------

    /** Нужды без единого сервиса-потомка (TZ-REQ-001). */
    fun needsWithoutServices(projectId: String? = null): List<String> = queryIds(
        """SELECT o.id FROM objects o
            WHERE o.valid_to IS NULL AND o.type = 'need' AND o.status <> 'Cancelled'
              AND NOT EXISTS (
                  SELECT 1 FROM links l
                    JOIN objects s ON s.id = l.to_id AND s.valid_to IS NULL AND s.type = 'service'
                   WHERE l.from_id = o.id AND l.kind = 'trace')""" +
            projectFilter(projectId) + " ORDER BY o.id",
        projectId,
    )

    /** Элементы без назначенных требований (TZ-REQ-005). */
    fun elementsWithoutRequirements(projectId: String? = null): List<String> = queryIds(
        """SELECT o.id FROM objects o
            WHERE o.valid_to IS NULL AND o.type = 'component'
              AND NOT EXISTS (
                  SELECT 1 FROM links l WHERE l.to_id = o.id AND l.kind = 'allocation')""" +
            projectFilter(projectId) + " ORDER BY o.id",
        projectId,
    )

    /**
     * Требования к пересмотру (TZ-REQ-006): у их trace-источника текущая версия
     * новее версии самого требования — источник менялся после базирования связи.
     */
    fun reviewCandidates(projectId: String? = null): List<String> = queryIds(
        """SELECT DISTINCT r.id FROM objects r
             JOIN links l ON l.to_id = r.id AND l.kind = 'trace'
             JOIN objects s ON s.id = l.from_id AND s.valid_to IS NULL
            WHERE r.type = 'requirement' AND r.valid_to IS NULL AND r.status <> 'Cancelled'
              AND s.valid_from > r.valid_from""" +
            (if (projectId != null) " AND r.project_id = ?" else "") + " ORDER BY r.id",
        projectId,
    )

    // ---------- зрелость (TZ-REQ-008) ----------

    /** Дерево изделия: элементы с родителями и интерфейсы с двумя сторонами (CR-003). */
    fun productTree(projectId: String? = null): Map<String, ProductNode> = objects.listCurrent(projectId)
        .filter { it.type == "component" || it.type == "interface" }
        .associate { o ->
            o.id to ProductNode(
                id = o.id,
                kind = if (o.type == "interface") "interface" else o.doc.path("kind").asText("component"),
                parent = o.doc.path("parent").asText("").ifBlank { null },
                owners = o.doc.path("owners").map { it.asText() },
            )
        }

    /**
     * Требования-потомки, распределённые вне области родителя (CR-003 п. 6).
     * Возвращает (родитель, потомок, причина).
     */
    fun inconsistentAllocations(projectId: String? = null): List<Triple<String, String, String>> {
        val tree = productTree(projectId)
        val byId = objects.listCurrent(projectId).filter { it.type == "requirement" }.associateBy { it.id }
        return links.list("derive", projectId).mapNotNull { link ->
            val parent = byId[link.fromId] ?: return@mapNotNull null
            val child = byId[link.toId] ?: return@mapNotNull null
            val (ok, why) = allocationConsistent(parent.doc, child.doc, tree)
            if (!ok) Triple(parent.id, child.id, why!!) else null
        }
    }

    /** Спецификация элемента: требования, распределённые на него (представление). */
    fun specificationOf(componentId: String): List<String> {
        // область — проект элемента: спецификация не тянет чужие требования (ADR-022)
        val scope = objects.current(componentId)?.projectId
        return componentSpecification(
            objects.listCurrent(scope).filter { it.type == "requirement" }.map { it.doc }, componentId,
        )
    }

    /** Состояние верификации требования по его событиям (CR-003). */
    fun verificationStateOf(id: String): VerificationState {
        val cur = objects.current(id) ?: throw NoSuchElementException("object '$id' not found")
        return verificationState(cur.doc)
    }

    /** Снимки объектов на дату (по истории версий шага 1) либо текущие. */
    fun snapshotsAt(at: java.time.OffsetDateTime?, projectId: String? = null): List<ObjectSnapshot> =
        (at?.let { objects.sliceAt(it, projectId) } ?: objects.listCurrent(projectId))
            .map { ObjectSnapshot.of(it) }

    fun readiness(gate: String, at: java.time.OffsetDateTime? = null, projectId: String? = null): List<GateGap> =
        gates.readiness(snapshotsAt(at, projectId), gate)

    private fun projectFilter(projectId: String?): String =
        if (projectId != null) " AND o.project_id = ?" else ""

    private fun queryIds(sql: String, projectId: String? = null): List<String> =
        conn.prepareStatement(sql).use { ps ->
            if (projectId != null) ps.setString(1, projectId)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
}
