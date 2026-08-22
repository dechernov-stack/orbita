// Перенос данных V0075 (снятие ограничения 4 из docs/STATUS.md): SQL шага
// переноса проверяется напрямую на базе уровня V001 — до появления
// констрейнтов V008, ради которых перенос и существует. Полную цепочку
// после переноса гоняют остальные тесты на каждой чистой базе.
package orbita.mod

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.store.Migrator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class ScenarioBackfillTest {

    private val mapper = ObjectMapper()
    private val backfillSql: String =
        Files.readString(RepoPaths.migrationsDir().resolve("V0075__scenario_backfill.sql"))

    /** База уровня V001; проверка id снята: до V008 входы моделирования с
     *  префиксами CN/SP/DM/GS/PA в таблицу попасть не могли, а перенос
     *  проверяется именно на их ссылках. */
    private fun dbAtV001(dbName: String): java.sql.Connection {
        TestDb.conn.createStatement().use {
            it.execute("DROP DATABASE IF EXISTS $dbName")
            it.execute("CREATE DATABASE $dbName")
        }
        val url = System.getenv("ORBITA_TEST_DB_URL")!!.substringBeforeLast('/') + "/" + dbName
        val conn = java.sql.DriverManager.getConnection(
            url, System.getenv("ORBITA_TEST_DB_USER"), System.getenv("ORBITA_TEST_DB_PASSWORD"),
        )
        val v1 = Files.readString(RepoPaths.migrationsDir().resolve("V001__init.sql"))
        conn.createStatement().use { it.execute(v1) }
        conn.createStatement().use { it.execute("ALTER TABLE objects DROP CONSTRAINT id_pattern") }
        return conn
    }

    private fun insert(conn: java.sql.Connection, id: String, type: String, doc: String) {
        conn.prepareStatement(
            "INSERT INTO objects(id, type, doc, status, version, created_by) VALUES (?, ?::object_type, ?::jsonb, 'Draft', '1', 'test')",
        ).use { ps ->
            ps.setString(1, id)
            ps.setString(2, type)
            ps.setString(3, doc)
            ps.executeUpdate()
        }
    }

    @Test
    fun `версии входов дополняются текущими, явные не перетираются`() {
        dbAtV001("orbita_backfill_ok").use { conn ->
            for ((id, version) in listOf("CN-0001" to "3", "SP-0001" to "2", "DM-0001" to "1",
                                          "GS-0001" to "1", "PA-0001" to "5")) {
                insert(conn, id, "component",
                    """{"id":"$id","lifecycle":{"status":"Draft","version":"$version"}}""")
            }
            insert(conn, "SC-0001", "scenario",
                """{"id":"SC-0001","constellation_ref":"CN-0001","spacecraft_ref":"SP-0001",
                    "demand_map_ref":"DM-0001","ground_stations_ref":"GS-0001",
                    "protocol_adapter_ref":"PA-0001","epoch":"2026-01-01T00:00:00Z",
                    "duration_s":86400,"rng_seed":7,
                    "input_versions":{"CN-0001":"99"}}""")

            conn.createStatement().use { it.execute(backfillSql) }

            val doc = conn.createStatement().use { st ->
                st.executeQuery("SELECT doc::text FROM objects WHERE id = 'SC-0001'").use { rs ->
                    rs.next(); mapper.readTree(rs.getString(1))
                }
            }
            val versions = doc["input_versions"]
            // явно записанная версия — заявка «считаю по той версии», не перетёрта
            assertEquals("99", versions["CN-0001"].asText())
            assertEquals("2", versions["SP-0001"].asText())
            assertEquals("5", versions["PA-0001"].asText())
            assertEquals(5, versions.size())
        }
    }

    @Test
    fun `сценарий без пяти ссылок останавливает перенос поимённо`() {
        dbAtV001("orbita_backfill_bad").use { conn ->
            insert(conn, "SC-0002", "scenario",
                """{"id":"SC-0002","constellation_ref":"CN-0001",
                    "epoch":"2026-01-01T00:00:00Z","duration_s":86400,"rng_seed":7}""")
            val failure = runCatching { conn.createStatement().use { it.execute(backfillSql) } }
            assertTrue(failure.isFailure)
            val message = failure.exceptionOrNull()?.message ?: ""
            // громкая ошибка со списком, а не загадочное нарушение констрейнта
            assertTrue("SC-0002" in message) { message }
            assertTrue("дополните ссылки" in message) { message }
        }
    }

    @Test
    fun `вход без объекта в базе тоже называется поимённо`() {
        dbAtV001("orbita_backfill_missing").use { conn ->
            // все пять ссылок есть, но объекты-входы отсутствуют: версию
            // зафиксировать не с чего — воспроизводимость не обеспечить
            insert(conn, "SC-0003", "scenario",
                """{"id":"SC-0003","constellation_ref":"CN-0001","spacecraft_ref":"SP-0001",
                    "demand_map_ref":"DM-0001","ground_stations_ref":"GS-0001",
                    "protocol_adapter_ref":"PA-0001","epoch":"2026-01-01T00:00:00Z",
                    "duration_s":86400,"rng_seed":7}""")
            val failure = runCatching { conn.createStatement().use { it.execute(backfillSql) } }
            assertTrue(failure.isFailure)
            assertTrue("SC-0003" in (failure.exceptionOrNull()?.message ?: ""))
        }
    }

    @Test
    fun `на пустой базе полная цепочка проходит и V0075 записан`() {
        val applied = TestDb.conn.createStatement().use { st ->
            st.executeQuery("SELECT version FROM schema_migrations ORDER BY version").use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }
        assertTrue("V0075" in applied) { applied.toString() }
        assertTrue(applied.indexOf("V0075") < applied.indexOf("V008")) { applied.toString() }
    }
}
