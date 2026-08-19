// Параметры и граф зависимостей (TZ-MOD-004, TZ-MOD-005, TZ-COM-005, TZ-AI-004).
// Единица и происхождение обязательны на уровне типов (Quantity) и на уровне DDL;
// цикл выявляется при вводе зависимости; stale — по графу зависимостей, не по времени.
package orbita.mod.store

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.model.Quantity
import java.sql.Connection

data class StoredParam(
    val objectId: String,
    val name: String,
    val value: Double?,
    val unit: String,
    val provenance: JsonNode,
    val formula: String?,
    val isTpm: Boolean,
)

data class UnacceptedAiProposal(val objectId: String, val name: String, val promptPackageId: String?)

class ParamStore(private val conn: Connection, private val mapper: ObjectMapper = ObjectMapper()) {

    /** Типобезопасный ввод: Quantity не существует без единицы и происхождения (TZ-MOD-004). */
    fun put(objectId: String, name: String, quantity: Quantity, formula: String? = null) =
        putRaw(objectId, name, quantity.value, quantity.unit, quantity.provenance.toJson(mapper), formula)

    /**
     * Сырой ввод (граница API): ограничения модели обеспечивает DDL —
     * unit_not_blank, prov_has_source, value_or_formula, ai_needs_accept.
     */
    fun putRaw(
        objectId: String,
        name: String,
        value: Double?,
        unit: String,
        provenance: JsonNode,
        formula: String? = null,
        isTpm: Boolean = false,
        tpm: JsonNode? = null,
    ): Unit = mappingConstraints {
        conn.prepareStatement(
            """INSERT INTO params(object_id, name, value, unit, provenance, formula, is_tpm, tpm)
               VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?::jsonb)
               ON CONFLICT (object_id, name) DO UPDATE
                 SET value = EXCLUDED.value, unit = EXCLUDED.unit,
                     provenance = EXCLUDED.provenance, formula = EXCLUDED.formula,
                     is_tpm = EXCLUDED.is_tpm, tpm = EXCLUDED.tpm"""
        ).use { ps ->
            ps.setString(1, objectId)
            ps.setString(2, name)
            if (value != null) ps.setDouble(3, value) else ps.setNull(3, java.sql.Types.DOUBLE)
            ps.setString(4, unit)
            ps.setString(5, mapper.writeValueAsString(provenance))
            ps.setString(6, formula)
            ps.setBoolean(7, isTpm)
            ps.setString(8, tpm?.let { mapper.writeValueAsString(it) })
            ps.executeUpdate()
        }
    }

    fun get(objectId: String, name: String): StoredParam? =
        conn.prepareStatement(
            "SELECT $COLUMNS FROM params WHERE object_id = ? AND name = ?"
        ).use { ps ->
            ps.setString(1, objectId)
            ps.setString(2, name)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toParam() else null }
        }

    /**
     * Ввод зависимости «(objectId, name) зависит от (depObjectId, depName)».
     * Цикл выявляется до вставки (TZ-MOD-005; db/queries.sql, запрос 7):
     * проверяется достижимость (objectId, name) из будущей зависимости —
     * именно в этом направлении (STEP-1, ловушка 4).
     */
    fun addDependency(objectId: String, name: String, depObjectId: String, depName: String) {
        if (wouldCreateCycle(objectId, name, depObjectId, depName)) {
            throw CycleException(
                "TZ-MOD-005: dependency ($objectId.$name) -> ($depObjectId.$depName) would create a cycle"
            )
        }
        mappingConstraints {
            conn.prepareStatement(
                "INSERT INTO param_deps(object_id, name, dep_object_id, dep_name) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING"
            ).use { ps ->
                ps.setString(1, objectId)
                ps.setString(2, name)
                ps.setString(3, depObjectId)
                ps.setString(4, depName)
                ps.executeUpdate()
            }
        }
    }

    /** Запрос 7: создаст ли зависимость from → to цикл. */
    fun wouldCreateCycle(fromObjectId: String, fromName: String, toObjectId: String, toName: String): Boolean =
        conn.prepareStatement(
            """WITH RECURSIVE reach AS (
                   SELECT dep_object_id AS o, dep_name AS n FROM param_deps
                    WHERE object_id = ? AND name = ?
                   UNION
                   SELECT d.dep_object_id, d.dep_name FROM param_deps d
                     JOIN reach r ON d.object_id = r.o AND d.name = r.n
               )
               SELECT EXISTS (SELECT 1 FROM reach WHERE o = ? AND n = ?)"""
        ).use { ps ->
            ps.setString(1, toObjectId)
            ps.setString(2, toName)
            ps.setString(3, fromObjectId)
            ps.setString(4, fromName)
            ps.executeQuery().use { rs -> rs.next(); rs.getBoolean(1) }
        }

    /**
     * Запрос 6, каскад stale (TZ-MOD-005): изменение параметра помечает устаревшими
     * результаты, чьи входы зависят от него — по графу зависимостей, не по времени
     * (STEP-1, ловушка 5). Возвращает число помеченных результатов.
     */
    fun markStaleFor(objectId: String, name: String): Int =
        conn.prepareStatement(
            """WITH RECURSIVE affected AS (
                   SELECT object_id, name FROM param_deps WHERE dep_object_id = ? AND dep_name = ?
                   UNION
                   SELECT d.object_id, d.name FROM param_deps d
                     JOIN affected a ON d.dep_object_id = a.object_id AND d.dep_name = a.name
               )
               UPDATE results SET stale = true
                WHERE NOT stale AND (jsonb_exists(input_versions, ?)
                   OR EXISTS (SELECT 1 FROM affected a WHERE jsonb_exists(input_versions, a.object_id)))"""
        ).use { ps ->
            ps.setString(1, objectId)
            ps.setString(2, name)
            ps.setString(3, objectId)
            ps.executeUpdate()
        }

    /** Запрос 8: неакцептованные предложения ИИ (TZ-AI-004, TZ-OUT-002). */
    fun unacceptedAiProposals(): List<UnacceptedAiProposal> = conn.createStatement().use { st ->
        st.executeQuery(
            """SELECT object_id, name, provenance->'ai'->>'prompt_package_id' AS package
                 FROM params
                WHERE provenance->>'source' = 'ai_proposed'
                  AND (provenance->'ai'->>'accepted')::boolean IS NOT TRUE
                ORDER BY object_id, name"""
        ).use { rs ->
            buildList {
                while (rs.next()) add(
                    UnacceptedAiProposal(rs.getString(1), rs.getString(2), rs.getString(3))
                )
            }
        }
    }

    /**
     * Выборка параметров для расчётов: неакцептованное предложение ИИ
     * не участвует (TZ-AI-004, TZ-MOD-004).
     */
    fun effectiveParams(objectId: String): List<StoredParam> =
        conn.prepareStatement(
            """SELECT $COLUMNS FROM params
                WHERE object_id = ?
                  AND NOT (provenance->>'source' = 'ai_proposed'
                           AND (provenance->'ai'->>'accepted')::boolean IS NOT TRUE)
                ORDER BY name"""
        ).use { ps ->
            ps.setString(1, objectId)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toParam()) } }
        }

    private fun java.sql.ResultSet.toParam() = StoredParam(
        objectId = getString("object_id"),
        name = getString("name"),
        value = getDouble("value").let { if (wasNull()) null else it },
        unit = getString("unit"),
        provenance = mapper.readTree(getString("provenance")),
        formula = getString("formula"),
        isTpm = getBoolean("is_tpm"),
    )

    private companion object {
        const val COLUMNS = "object_id, name, value, unit, provenance::text AS provenance, formula, is_tpm"
    }
}
