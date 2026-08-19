// Тестовая БД (STEP-1 §1.6: PostgreSQL — Testcontainers либо локальная БД).
// Локальная БД поднимается скриптом ci/local_db.sh; параметры можно переопределить
// переменными ORBITA_TEST_DB_URL / _USER / _PASSWORD (так работает CI).
package orbita.mod

import orbita.mod.store.DbConfig
import orbita.mod.store.Migrator
import java.sql.Connection

object TestDb {

    private val config = DbConfig(
        url = System.getenv("ORBITA_TEST_DB_URL") ?: "jdbc:postgresql://127.0.0.1:5432/orbita_test",
        user = System.getenv("ORBITA_TEST_DB_USER") ?: "orbita",
        password = System.getenv("ORBITA_TEST_DB_PASSWORD") ?: "orbita",
    )

    /** Одно соединение на прогон: схема пересоздаётся и мигрируется один раз. */
    val conn: Connection by lazy {
        val c = config.open()
        c.createStatement().use { it.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public") }
        Migrator(RepoPaths.migrationsDir()).migrate(c)
        c
    }

    /** Полная очистка данных между группами проверок эталона. */
    fun truncateAll() {
        conn.createStatement().use {
            it.execute("TRUNCATE objects, links, params, param_deps, results RESTART IDENTITY CASCADE")
        }
    }
}
