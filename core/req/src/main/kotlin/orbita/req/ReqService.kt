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
        doc.path("allocated_to").forEach { cm ->
            objects.current(cm.asText())
                ?: throw ModelViolationException(
                    "TZ-REQ-005: allocation to missing element ${cm.asText()}"
                )
        }
        return conn.tx {
            val stored = create(doc, "requirement", createdBy)
            doc.path("traces_up").forEach { t ->
                links.add(t.path("ref").asText(), stored.id, "trace",
                    t.path("consumer_class").asText(null))
            }
            doc.path("allocated_to").forEach { cm -> links.add(stored.id, cm.asText(), "allocation") }
            stored
        }
    }

    /** Элемент архитектуры (TZ-REQ-005). */
    fun ingestComponent(json: String, createdBy: String = "api"): StoredObject {
        val doc = registry.parse(json)
        registry.require("core/component", doc)
        return create(doc, "component", createdBy)
    }

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

    /** Снимки объектов на дату (по истории версий шага 1) либо текущие. */
    fun snapshotsAt(at: java.time.OffsetDateTime?): List<ObjectSnapshot> =
        (at?.let { objects.sliceAt(it) } ?: objects.listCurrent()).map { ObjectSnapshot.of(it) }

    fun readiness(gate: String, at: java.time.OffsetDateTime? = null): List<GateGap> =
        gates.readiness(snapshotsAt(at), gate)

    private fun queryIds(sql: String): List<String> = conn.createStatement().use { st ->
        st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
    }
}
