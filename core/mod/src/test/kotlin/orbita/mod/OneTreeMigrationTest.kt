// V038 (ADR-044): модель аппарата растворяется в дереве состава без потерь.
// SQL переноса гоняется на базе уровня V037 с данными в СТАРОЙ форме — ровно
// той, что лежит на стенде: узел «КА» как system космического сегмента,
// вхождение ×1, построение walker, сценарий со spacecraft_ref.
package orbita.mod

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class OneTreeMigrationTest {
    private val mapper = ObjectMapper()

    /** База, доведённая до V037: все миграции, кроме проверяемой. */
    private fun dbBeforeV038(dbName: String): java.sql.Connection {
        TestDb.conn.createStatement().use {
            it.execute("DROP DATABASE IF EXISTS $dbName")
            it.execute("CREATE DATABASE $dbName")
        }
        val url = System.getenv("ORBITA_TEST_DB_URL")!!.substringBeforeLast('/') + "/" + dbName
        val conn = java.sql.DriverManager.getConnection(
            url, System.getenv("ORBITA_TEST_DB_USER"), System.getenv("ORBITA_TEST_DB_PASSWORD"),
        )
        Files.list(RepoPaths.migrationsDir()).use { s ->
            s.filter { it.fileName.toString().matches(Regex("V[0-9]+__.*\\.sql")) }
                .sorted(compareBy { it.fileName.toString() })
                .filter { !it.fileName.toString().startsWith("V038__") && !it.fileName.toString().startsWith("V039__") }
                .forEach { f -> conn.createStatement().use { it.execute(Files.readString(f)) } }
        }
        return conn
    }

    private fun insert(conn: java.sql.Connection, id: String, type: String, doc: String, project: String = "PJ-0001", status: String = "Draft") {
        conn.prepareStatement(
            "INSERT INTO objects(id, type, doc, status, version, created_by, project_id) VALUES (?, ?::object_type, ?::jsonb, ?::lifecycle, '1', 'test', ?)",
        ).use { ps ->
            ps.setString(1, id)
            ps.setString(2, type)
            ps.setString(3, doc)
            ps.setString(4, status)
            ps.setString(5, project)
            ps.executeUpdate()
        }
    }

    private fun current(conn: java.sql.Connection, id: String): com.fasterxml.jackson.databind.JsonNode? =
        conn.prepareStatement("SELECT doc::text FROM objects WHERE id = ? AND valid_to IS NULL").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) mapper.readTree(rs.getString(1)) else null }
        }

    private fun query(conn: java.sql.Connection, sql: String): List<com.fasterxml.jackson.databind.JsonNode> =
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                buildList { while (rs.next()) add(mapper.readTree(rs.getString(1))) }
            }
        }

    @Test
    fun `модель аппарата растворяется в узел КА с поддеревом, построение — вхождение x N`() {
        dbBeforeV038("orbita_one_tree").use { conn ->
            insert(conn, "PJ-0001", "project", """{"id":"PJ-0001","name":"п","lifecycle":{"status":"Draft","version":"1"}}""")
            insert(conn, "CM-0001", "component", """{"id":"CM-0001","name":"Космический сегмент","kind":"segment","lifecycle":{"status":"Draft","version":"1"}}""")
            insert(conn, "CM-0002", "component", """{"id":"CM-0002","name":"КА платформы IoT","kind":"system","segment":"space","parent":"CM-0001","lifecycle":{"status":"Draft","version":"1"}}""")
            insert(conn, "CM-0003", "component", """{"id":"CM-0003","name":"ПН IoT","kind":"subsystem","parent":"CM-0002","lifecycle":{"status":"Draft","version":"1"}}""")
            insert(conn, "CU-0001", "component_usage", """{"id":"CU-0001","definition_ref":"CM-0002","quantity":1,"lifecycle":{"status":"Draft","version":"1"}}""")
            insert(conn, "CN-0001", "constellation", """{"id":"CN-0001","name":"Walker 40/5","kind":"walker_delta","walker":{"total":40,"planes":5,"phasing":1,"altitude_km":550,"inclination_deg":53},"lifecycle":{"status":"Draft","version":"1"}}""")
            insert(conn, "CN-0002", "constellation", """{"id":"CN-0002","name":"4x52 + 3xССО","kind":"walker_delta","subgroups":[{"name":"Наклонная","kind":"walker_delta","planes":4,"per_plane":1,"altitude_km":550,"inclination_deg":52},{"name":"ССО","kind":"sso","planes":3,"per_plane":1,"altitude_km":600,"ltan_h":10.5}],"lifecycle":{"status":"Draft","version":"1"}}""")
            insert(conn, "SP-0001", "spacecraft", """{"id":"SP-0001","preset":"cubesat_16u",
                "platform":{"dry_mass_kg":30,"design_life_years":5,
                    "power":{"sa_area_m2":0.18,"sa_efficiency":0.29,"battery_wh":120},
                    "attitude":{"pointing_accuracy_deg":1},
                    "mel":[{"name":"Корпус","subsystem":"structure","mass_kg":8,"maturity":"existing"},
                           {"name":"Маховики","subsystem":"adcs","mass_kg":1.2,"maturity":"existing","quantity":3},
                           {"name":"Полезная нагрузка","subsystem":"payload","mass_kg":6.5,"maturity":"new"}]},
                "payload":{"architecture":"regenerative","links":[{"id":"RL-UP","role":"user_uplink","band_hz":868000000,"tx_power_w":0.1,"antenna":{"type":"patch","gain_dbi":6}}],
                    "onboard":{"buffer_mb":64,"priority_policy":["A_prime"]},"ephemeris_beacon":{"enabled":true,"period_s":60,"format":"orbit_model"}},
                "modes":[{"name":"standby","power_w":6.0,"orbit_fraction":0.4}],
                "lifecycle":{"status":"Draft","version":"1"}}""")
            insert(conn, "SC-0001", "scenario", """{"id":"SC-0001","name":"с","constellation_ref":"CN-0002","spacecraft_ref":"SP-0001",
                "demand_map_ref":"DM-0001","ground_stations_ref":"GS-0001","protocol_adapter_ref":"PA-0001",
                "delivery_mode":"store_and_forward","epoch":"2026-03-20T00:00:00Z","duration_s":86400,"rng_seed":42,
                "input_versions":{"CN-0002":"1","SP-0001":"1","DM-0001":"1","GS-0001":"1","PA-0001":"1"},
                "lifecycle":{"status":"Baseline","version":"1"}}""", status = "Baseline")

            conn.createStatement().use { it.execute(Files.readString(RepoPaths.migrationsDir().resolve("V038__one_tree_carriers.sql"))) }
            conn.createStatement().use { it.execute(Files.readString(RepoPaths.migrationsDir().resolve("V039__carrier_subtree_usages.sql"))) }

            // 1. единственный «системный» узел космического сегмента усыновлён как КА — новой версией
            val ka = current(conn, "CM-0002")!!
            assertEquals("element", ka.path("kind").asText())
            assertEquals("spacecraft", ka.path("profile").path("role").asText())
            assertEquals("cubesat_16u", ka.path("profile").path("preset").asText())
            assertEquals("standby", ka.path("profile").path("modes")[0].path("name").asText())
            assertEquals("2", ka.path("lifecycle").path("version").asText())
            assertEquals(2, query(conn, "SELECT doc::text FROM objects WHERE id = 'CM-0002' ORDER BY pk").size, "старая версия узла осталась в истории")

            // 2. поддерево: платформа с параметрами анкеты, подсистемы под ней, ПН рядом
            val nodes = query(conn, "SELECT doc::text FROM objects WHERE valid_to IS NULL AND type = 'component' AND doc->>'parent' = 'CM-0002' ORDER BY id")
            val roles = nodes.map { it.path("profile").path("role").asText() }
            assertTrue("platform" in roles && "payload" in roles, "под КА нет платформы/ПН: $roles")
            val platform = nodes.first { it.path("profile").path("role").asText() == "platform" }
            val params = platform.path("parameters").associate { it.path("name").asText() to it.path("quantity") }
            assertEquals(30.0, params.getValue("dry_mass").path("value").asDouble())
            assertEquals("kg", params.getValue("dry_mass").path("unit").asText())
            assertEquals("Wh", params.getValue("battery_energy").path("unit").asText())
            assertEquals("a", params.getValue("design_life").path("unit").asText())
            val subs = query(conn, "SELECT doc::text FROM objects WHERE valid_to IS NULL AND type = 'component' AND doc->>'parent' = '${platform.path("id").asText()}' ORDER BY id")
            assertEquals(listOf("Корпус", "Маховики"), subs.map { it.path("name").asText() })
            assertEquals(3.0, subs[1].path("parameters").first { it.path("name").asText() == "quantity" }.path("quantity").path("value").asDouble())
            val payload = nodes.first { it.path("profile").path("role").asText() == "payload" }
            assertEquals("regenerative", payload.path("profile").path("architecture").asText())
            assertEquals(1, payload.path("profile").path("links").size())
            assertTrue(payload.path("profile").path("onboard").path("buffer_mb").isMissingNode, "объём буфера — параметр, не профиль")
            assertEquals(64.0, payload.path("parameters").first { it.path("name").asText() == "buffer_size" }.path("quantity").path("value").asDouble())
            assertEquals(6.5, payload.path("parameters").first { it.path("name").asText() == "mass" }.path("quantity").path("value").asDouble())

            // 3. построения → вхождения КА ×N: walker одной группой, подгруппы — по одной
            val usages = query(conn, "SELECT doc::text FROM objects WHERE valid_to IS NULL AND type = 'component_usage' AND doc->>'definition_ref' = 'CM-0002' ORDER BY id")
            val byCn = usages.filter { it.has("constellation_ref") }.groupBy { it.path("constellation_ref").asText() }
            assertEquals(listOf(40), byCn.getValue("CN-0001").map { it.path("quantity").asInt() })
            assertEquals(listOf(4, 3), byCn.getValue("CN-0002").map { it.path("quantity").asInt() })
            assertEquals(listOf("Наклонная", "ССО"), byCn.getValue("CN-0002").map { it.path("subgroup").asText() })
            assertTrue(usages.any { it.path("id").asText() == "CU-0001" }, "прежнее вхождение ×1 осталось")
            // V039: поддерево КА из модели получило вхождения под вхождением проекта ×1
            val subUsages = query(conn, "SELECT doc::text FROM objects WHERE valid_to IS NULL AND type = 'component_usage' AND doc->>'parent_usage' = 'CU-0001' ORDER BY id")
            val subDefs = subUsages.map { it.path("definition_ref").asText() }
            assertTrue(platform.path("id").asText() in subDefs && payload.path("id").asText() in subDefs, "платформа и ПН без вхождений: $subDefs")
            val wheels = subs[1].path("id").asText()
            val wheelUsage = query(conn, "SELECT doc::text FROM objects WHERE valid_to IS NULL AND type = 'component_usage' AND doc->>'definition_ref' = '$wheels'").single()
            assertEquals(3, wheelUsage.path("quantity").asInt(), "кратность подсистемы — из параметра quantity")
            assertEquals(platform.path("id").asText(), query(conn, "SELECT doc::text FROM objects WHERE id = '${wheelUsage.path("parent_usage").asText()}' AND valid_to IS NULL").single().path("definition_ref").asText())
            // повторный запуск ничего не плодит
            conn.createStatement().use { it.execute(Files.readString(RepoPaths.migrationsDir().resolve("V039__carrier_subtree_usages.sql"))) }
            assertEquals(subUsages.size, query(conn, "SELECT doc::text FROM objects WHERE valid_to IS NULL AND type = 'component_usage' AND doc->>'parent_usage' = 'CU-0001'").size)

            // 4. сценарий ссылается на вхождение своего построения; версии входов — с новым ключом
            val sc = current(conn, "SC-0001")!!
            // базированный сценарий: не правка на месте, а новая версия в том же
            // статусе по процедуре изменения — старая закрыта с основанием
            assertEquals("2", sc.path("lifecycle").path("version").asText())
            assertEquals("Baseline", query(conn, "SELECT to_jsonb(status::text) FROM objects WHERE id = 'SC-0001' AND valid_to IS NULL").single().asText())
            assertEquals("V038", query(conn, "SELECT to_jsonb(change_ref) FROM objects WHERE id = 'SC-0001' AND valid_to IS NOT NULL").single().asText())
            assertTrue(sc.path("spacecraft_ref").isMissingNode)
            val carrier = sc.path("carrier_ref").asText()
            assertEquals(byCn.getValue("CN-0002").first().path("id").asText(), carrier)
            assertTrue(sc.path("input_versions").has(carrier) && !sc.path("input_versions").has("SP-0001"))

            // 5. старый объект — Cancelled новой версией с адресом, куда растворён; в истории читается
            assertNull(query(conn, "SELECT doc::text FROM objects WHERE id = 'SP-0001' AND valid_to IS NULL AND status <> 'Cancelled'").firstOrNull())
            val cancelled = current(conn, "SP-0001")!!
            assertEquals("CM-0002", cancelled.path("dissolved_into").asText())
            assertEquals("Cancelled", cancelled.path("lifecycle").path("status").asText())
            assertEquals(30.0, query(conn, "SELECT doc::text FROM objects WHERE id = 'SP-0001' ORDER BY pk LIMIT 1")[0].path("platform").path("dry_mass_kg").asDouble())
        }
    }

    @Test
    fun `без усыновляемого узла КА создаётся новым под космическим сегментом`() {
        dbBeforeV038("orbita_one_tree_new").use { conn ->
            insert(conn, "PJ-0002", "project", """{"id":"PJ-0002","name":"п","lifecycle":{"status":"Draft","version":"1"}}""", "PJ-0002")
            insert(conn, "CM-0001", "component", """{"id":"CM-0001","name":"Космический сегмент","kind":"segment","segment":"space","lifecycle":{"status":"Draft","version":"1"}}""", "PJ-0002")
            insert(conn, "SP-0001", "spacecraft", """{"id":"SP-0001","platform":{"dry_mass_kg":12,"power":{"sa_area_m2":0.1,"sa_efficiency":0.3,"battery_wh":50},"attitude":{"pointing_accuracy_deg":2}},
                "payload":{"architecture":"regenerative","links":[],"onboard":{"buffer_mb":8,"priority_policy":["A_prime"]}},"lifecycle":{"status":"Draft","version":"1"}}""", "PJ-0002")
            conn.createStatement().use { it.execute(Files.readString(RepoPaths.migrationsDir().resolve("V038__one_tree_carriers.sql"))) }
            val ka = query(conn, "SELECT doc::text FROM objects WHERE valid_to IS NULL AND type = 'component' AND doc->'profile'->>'role' = 'spacecraft'").single()
            assertEquals("CM-0001", ka.path("parent").asText())
            assertEquals("element", ka.path("kind").asText())
            assertTrue(ka.path("name").asText().contains("SP-0001"), "имя нового узла называет, откуда он: ${ka.path("name")}")
            val usage = query(conn, "SELECT doc::text FROM objects WHERE valid_to IS NULL AND type = 'component_usage' AND doc->>'definition_ref' = '${ka.path("id").asText()}'").single()
            assertEquals(1, usage.path("quantity").asInt())
        }
    }
}
