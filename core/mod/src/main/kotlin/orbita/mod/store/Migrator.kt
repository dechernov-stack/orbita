// Применение миграций db/migrations/VNNN__*.sql (STEP-1 §1.4).
package orbita.mod.store

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import kotlin.streams.asSequence

/**
 * Мигратор с контролем целостности: применённая миграция фиксируется с контрольной
 * суммой, изменение уже применённого файла — ошибка. Запись в schema_migrations
 * не считается доказательством схемы: после применения фактическое состояние
 * сверяется с information_schema ([SchemaCheck]).
 */
class Migrator(private val migrationsDir: Path) {

    fun migrate(conn: Connection) {
        conn.createStatement().use {
            it.execute(
                """CREATE TABLE IF NOT EXISTS schema_migrations(
                     version    text PRIMARY KEY,
                     checksum   text NOT NULL,
                     applied_at timestamptz NOT NULL DEFAULT now())"""
            )
        }
        val applied = buildMap {
            conn.createStatement().use { st ->
                st.executeQuery("SELECT version, checksum FROM schema_migrations").use { rs ->
                    while (rs.next()) put(rs.getString(1), rs.getString(2))
                }
            }
        }
        val files = Files.list(migrationsDir).use { s ->
            s.asSequence().filter { it.fileName.toString().matches(Regex("V[0-9]+__.*\\.sql")) }
                .sortedBy { it.fileName.toString() }.toList()
        }
        check(files.isNotEmpty()) { "no migrations found in $migrationsDir" }

        for (file in files) {
            val version = file.fileName.toString().substringBefore("__")
            val sql = Files.readString(file)
            val checksum = sha256(sql)
            val known = applied[version]
            if (known != null) {
                check(known == checksum) {
                    "migration $version was modified after being applied (checksum mismatch)"
                }
                continue
            }
            conn.tx {
                // Весь файл — одним запросом: PostgreSQL сам разбирает набор операторов,
                // включая тела функций в долларовых кавычках.
                conn.createStatement().use { it.execute(sql) }
                conn.prepareStatement(
                    "INSERT INTO schema_migrations(version, checksum) VALUES (?, ?)"
                ).use { ps ->
                    ps.setString(1, version)
                    ps.setString(2, checksum)
                    ps.executeUpdate()
                }
            }
        }
        SchemaCheck.verify(conn)
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}

/**
 * Сверка фактической схемы с ожидаемой моделями. Расхождение роняет запуск:
 * работать поверх разошедшейся схемы опаснее, чем не запуститься.
 */
object SchemaCheck {

    private val requiredTables = listOf("objects", "links", "params", "param_deps", "results")

    fun verify(conn: Connection) {
        val existing = buildSet {
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema()"
                ).use { rs -> while (rs.next()) add(rs.getString(1)) }
            }
        }
        val missing = requiredTables.filterNot { it in existing }
        check(missing.isEmpty()) { "schema check failed: missing tables $missing" }

        // V002: класс потребителя на связи (TZ-REQ-003)
        val hasConsumerClass = conn.createStatement().use { st ->
            st.executeQuery(
                """SELECT count(*) FROM information_schema.columns
                    WHERE table_schema = current_schema() AND table_name = 'links'
                      AND column_name = 'consumer_class'"""
            ).use { rs -> rs.next(); rs.getLong(1) > 0 }
        }
        check(hasConsumerClass) { "schema check failed: links.consumer_class is missing (V002)" }

        val hasGuard = conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT count(*) FROM pg_trigger WHERE tgname = 'objects_baseline_guard'"
            ).use { rs -> rs.next(); rs.getLong(1) > 0 }
        }
        check(hasGuard) { "schema check failed: trigger objects_baseline_guard is missing (TZ-COM-003)" }
    }
}
