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
    private val gates: Gates = Gates(),
) {
    val objects = ObjectStore(conn)
    val links = LinkStore(conn)
    val results = ResultStore(conn)
    private val baselining = Baselining(quality)

    // ---------- приём объектов: валидация + связи из документа (TZ-REQ-003) ----------

    /** Нужда (TZ-REQ-001): стейкхолдер обязателен схемой; traces_down порождает связи. */
    fun ingestNeed(json: String, createdBy: String = "api"): StoredObject {
        val doc = registry.parse(json)
        registry.require("core/need", doc)
        return conn.tx {
            val stored = create(doc, "need", createdBy)
            doc.path("traces_down").forEach { sv -> links.add(stored.id, sv.asText(), "trace") }
            stored
        }
    }

    /** Сервис (TZ-REQ-002): QoS-профили по классам обязательны схемой (Р9). */
    fun ingestService(json: String, createdBy: String = "api"): StoredObject {
        val doc = registry.parse(json)
        registry.require("core/service", doc)
        return conn.tx {
            val stored = create(doc, "service", createdBy)
            doc.path("traces_up").forEach { nd ->
                if (links.linksTo(stored.id, "trace").none { it.fromId == nd.asText() }) {
                    links.add(nd.asText(), stored.id, "trace")
                }
            }
            stored
        }
    }

    /**
     * Требование (TZ-REQ-003, TZ-REQ-005): ссылка на сервис без consumer_class
     * отклоняется (Р9); распределение на несуществующий элемент отклоняется.
     * Связи выводятся из документа — матрицы формируются из них, не вручную.
     */
    fun ingestRequirement(json: String, createdBy: String = "api"): StoredObject {
        val doc = registry.parse(json)
        registry.require("core/requirement", doc)
        doc.path("traces_up").forEach { t ->
            val ref = t.path("ref").asText()
            if (ref.startsWith("SV-") && t.path("consumer_class").asText("").isBlank()) {
                throw ModelViolationException(
                    "TZ-REQ-003 (Р9/ADR-009): reference to service $ref requires consumer_class"
                )
            }
        }
        // CR-001/CR-003: распределение — объект {component|interface, kind, rationale}
        doc.path("allocated_to").forEach { a ->
            val target = a.path("component").asText("").ifBlank { a.path("interface").asText("") }
            objects.current(target)
                ?: throw ModelViolationException("TZ-REQ-005: allocation to missing element $target")
        }
        // CR-003: интерфейсное требование распределяется на интерфейс с двумя сторонами
        interfaceAllocationValid(doc, productTree()).let { (ok, why) ->
            if (!ok) throw ModelViolationException("CR-003 (ADR-019): $why")
        }
        // CR-001: декомпозиция — отдельная связь derive, не trace
        doc.path("derives_from").forEach { parent ->
            objects.current(parent.asText())
                ?: throw ModelViolationException(
                    "TZ-REQ-005 (ADR-017): derive from missing requirement ${parent.asText()}"
                )
        }
        return conn.tx {
            val stored = create(doc, "requirement", createdBy)
            doc.path("traces_up").forEach { t ->
                links.add(t.path("ref").asText(), stored.id, "trace",
                    t.path("consumer_class").asText(null))
            }
            doc.path("allocated_to").forEach { a ->
                val target = a.path("component").asText("").ifBlank { a.path("interface").asText("") }
                links.add(
                    stored.id, target, "allocation",
                    allocationKind = a.path("kind").asText("full"),
                    rationale = a.path("rationale").asText(null),
                )
            }
            // CR-003: вид декомпозиции — свойство СВЯЗИ, а не документа (ADR-017/019).
            // Документ объявляет родителей; по умолчанию это распределение бюджета,
            // производное требование помечается операцией deriveAs.
            doc.path("derives_from").forEach { parent ->
                links.add(parent.asText(), stored.id, "derive", derivationKind = "allocated")
            }
            stored
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
    fun inconsistentDecompositions(): List<Pair<String, RollupResult>> =
        objects.listCurrent()
            .filter { it.type == "requirement" && it.status != Lifecycle.Cancelled }
            .mapNotNull { r ->
                val result = rollupFor(r.id)
                if (result.applicable && (result.error != null || result.consistent == false)) {
                    r.id to result
                } else null
            }

    /** Элемент архитектуры (TZ-REQ-005). */
    fun ingestComponent(json: String, createdBy: String = "api"): StoredObject {
        val doc = registry.parse(json)
        registry.require("core/component", doc)
        return create(doc, "component", createdBy)
    }

    /** Интерфейс IF-NNNN: две стороны ответственности (CR-003/ADR-019). */
    fun ingestInterface(json: String, createdBy: String = "api"): StoredObject {
        val doc = registry.parse(json)
        val owners = doc.path("owners")
        if (!owners.isArray || owners.size() != 2) {
            throw ModelViolationException(
                "CR-003 (ADR-019): interface ${doc.path("id").asText()} requires exactly two sides (owners)"
            )
        }
        return create(doc, "interface", createdBy)
    }

    /** Свидетельство EV-NNNN (CR-003/ADR-019). */
    fun ingestEvidence(json: String, createdBy: String = "api"): StoredObject {
        val doc = registry.parse(json)
        registry.require("core/evidence", doc)
        return create(doc, "evidence", createdBy)
    }

    /**
     * Валидация VA-NNNN: привязка к ожиданию стейкхолдера, не к требованию
     * (CR-003/ADR-019). Прикладные правила дополняют схему.
     */
    fun ingestValidation(json: String, createdBy: String = "api"): StoredObject {
        val doc = registry.parse(json)
        registry.require("core/validation", doc)
        val issues = validationIssues(doc)
        if (issues.isNotEmpty()) {
            throw ModelViolationException("CR-003 (ADR-019): " + issues.joinToString("; "))
        }
        return create(doc, "validation", createdBy)
    }

    /**
     * Риск RSK-NNNN (шаг 7). Прикладные правила дополняют схему: полнота
     * формулировки, стратегия и срок выше порога критичности, остаточная
     * оценка не выше исходной. Те же правила применяются к предложениям ИИ —
     * упрощённой версии для них нет.
     */
    fun ingestRisk(json: String, createdBy: String = "api"): StoredObject {
        val doc = registry.parse(json)
        registry.require("core/risk", doc)
        val issues = riskIssues(doc).toMutableList()
        if (!residualOk(doc)) {
            issues += "остаточный риск выше исходного"
        }
        if (issues.isNotEmpty()) {
            throw ModelViolationException("NPR 8000.4: " + issues.joinToString("; "))
        }
        val stored = create(doc, "risk", createdBy)
        // связь риска с затронутыми объектами выводится из документа
        doc.path("affects").forEach { a ->
            val target = a.asText("")
            if (target.isNotBlank()) links.add(stored.id, target, "trace")
        }
        return stored
    }

    /** Активные и закрытые риски: закрытый сохраняется в реестре. */
    fun risks(): List<JsonNode> = objects.listCurrent()
        .filter { it.type == "risk" && it.status != Lifecycle.Cancelled }
        .map { it.doc }

    private fun create(doc: JsonNode, type: String, createdBy: String): StoredObject {
        val lifecycle = doc.path("lifecycle")
        return objects.create(
            id = doc.path("id").asText(),
            type = type,
            doc = doc,
            status = Lifecycle.valueOf(lifecycle.path("status").asText(Lifecycle.Draft.name)),
            version = lifecycle.path("version").asText("1"),
            createdBy = createdBy,
        )
    }

    // ---------- статусы и базирование (TZ-REQ-006) ----------

    /**
     * Перевод статуса. В Baseline требование переводится только при выполнении
     * условий (TZ-REQ-004/006/007): качество, TBD/TBR, метод верификации.
     * Переход выполняется новой версией с закрытием интервала (ObjectStore.transition),
     * поэтому отчёт зрелости строится на произвольную дату по истории статусов.
     */
    fun promote(
        id: String,
        target: Lifecycle,
        createdBy: String = "system",
        at: java.time.OffsetDateTime = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC),
    ): StoredObject {
        val cur = objects.current(id) ?: throw NoSuchElementException("object '$id' not found")
        if (target == Lifecycle.Baseline && cur.type == "requirement" && cur.status != Lifecycle.Baseline) {
            val (ok, reasons) = baselining.canBaseline(cur.doc)
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
    fun needsWithoutServices(): List<String> = queryIds(
        """SELECT o.id FROM objects o
            WHERE o.valid_to IS NULL AND o.type = 'need' AND o.status <> 'Cancelled'
              AND NOT EXISTS (
                  SELECT 1 FROM links l
                    JOIN objects s ON s.id = l.to_id AND s.valid_to IS NULL AND s.type = 'service'
                   WHERE l.from_id = o.id AND l.kind = 'trace')
            ORDER BY o.id"""
    )

    /** Элементы без назначенных требований (TZ-REQ-005). */
    fun elementsWithoutRequirements(): List<String> = queryIds(
        """SELECT o.id FROM objects o
            WHERE o.valid_to IS NULL AND o.type = 'component'
              AND NOT EXISTS (
                  SELECT 1 FROM links l WHERE l.to_id = o.id AND l.kind = 'allocation')
            ORDER BY o.id"""
    )

    /**
     * Требования к пересмотру (TZ-REQ-006): у их trace-источника текущая версия
     * новее версии самого требования — источник менялся после базирования связи.
     */
    fun reviewCandidates(): List<String> = queryIds(
        """SELECT DISTINCT r.id FROM objects r
             JOIN links l ON l.to_id = r.id AND l.kind = 'trace'
             JOIN objects s ON s.id = l.from_id AND s.valid_to IS NULL
            WHERE r.type = 'requirement' AND r.valid_to IS NULL AND r.status <> 'Cancelled'
              AND s.valid_from > r.valid_from
            ORDER BY r.id"""
    )

    // ---------- зрелость (TZ-REQ-008) ----------

    /** Дерево изделия: элементы с родителями и интерфейсы с двумя сторонами (CR-003). */
    fun productTree(): Map<String, ProductNode> = objects.listCurrent()
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
    fun inconsistentAllocations(): List<Triple<String, String, String>> {
        val tree = productTree()
        val byId = objects.listCurrent().filter { it.type == "requirement" }.associateBy { it.id }
        return links.list("derive").mapNotNull { link ->
            val parent = byId[link.fromId] ?: return@mapNotNull null
            val child = byId[link.toId] ?: return@mapNotNull null
            val (ok, why) = allocationConsistent(parent.doc, child.doc, tree)
            if (!ok) Triple(parent.id, child.id, why!!) else null
        }
    }

    /** Спецификация элемента: требования, распределённые на него (представление). */
    fun specificationOf(componentId: String): List<String> =
        componentSpecification(
            objects.listCurrent().filter { it.type == "requirement" }.map { it.doc }, componentId,
        )

    /** Состояние верификации требования по его событиям (CR-003). */
    fun verificationStateOf(id: String): VerificationState {
        val cur = objects.current(id) ?: throw NoSuchElementException("object '$id' not found")
        return verificationState(cur.doc)
    }

    /** Снимки объектов на дату (по истории версий шага 1) либо текущие. */
    fun snapshotsAt(at: java.time.OffsetDateTime?): List<ObjectSnapshot> =
        (at?.let { objects.sliceAt(it) } ?: objects.listCurrent()).map { ObjectSnapshot.of(it) }

    fun readiness(gate: String, at: java.time.OffsetDateTime? = null): List<GateGap> =
        gates.readiness(snapshotsAt(at), gate)

    private fun queryIds(sql: String): List<String> = conn.createStatement().use { st ->
        st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
    }
}
