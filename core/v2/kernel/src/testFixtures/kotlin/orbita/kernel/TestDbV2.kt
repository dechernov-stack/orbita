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

    // Накатываются ВСЕ миграции ядра v2 по порядку: тест, знающий только про
    // первую, начинает врать, как только схема поехала дальше.
    private fun накатить(conn: Connection) {
        repoRoot.resolve("db/migrations").toFile()
            .listFiles { f -> f.name.startsWith("V1") && f.name.endsWith(".sql") }
            .orEmpty().sortedBy { it.name }
            .forEach { файл -> conn.createStatement().use { it.execute(файл.readText()) } }
    }

    fun очистить() {
        conn.createStatement().use {
            it.execute("TRUNCATE orbita_kernel.entity, orbita_kernel.link RESTART IDENTITY")
        }
    }
}
