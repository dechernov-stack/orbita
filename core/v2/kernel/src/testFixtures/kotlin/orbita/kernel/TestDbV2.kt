// Обвязка тестов ядра v2: подключение к тестовой БД и накат миграций v2.
//
// Схема orbita_kernel живёт отдельно от таблиц v1, поэтому тесты v2 не
// мешают тестам v1 и наоборот.
package orbita.kernel

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

object TestDbV2 {

    val repoRoot: Path = Path.of(System.getenv("ORBITA_REPO_ROOT") ?: ".").toAbsolutePath().normalize()

    val conn: Connection by lazy {
        val url = System.getenv("ORBITA_TEST_DB_URL") ?: "jdbc:postgresql://localhost:5433/orbita_test"
        val user = System.getenv("ORBITA_TEST_DB_USER") ?: "orbita"
        val password = System.getenv("ORBITA_TEST_DB_PASSWORD") ?: "orbita"
        DriverManager.getConnection(url, user, password).also { накатить(it) }
    }

    private fun накатить(conn: Connection) {
        val файл = repoRoot.resolve("db/migrations/V100__v2_kernel.sql")
        conn.createStatement().use { it.execute(файл.toFile().readText()) }
    }

    fun очистить() {
        conn.createStatement().use {
            it.execute("TRUNCATE orbita_kernel.entity, orbita_kernel.link RESTART IDENTITY")
        }
    }
}
