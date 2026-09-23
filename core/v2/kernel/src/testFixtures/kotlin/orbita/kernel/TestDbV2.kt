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
            // Текстовый индекс базы знаний (шип 4 §2) — тоже таблица ядра.
            it.execute("DELETE FROM orbita_kernel.index_block")
        }
    }

    /**
     * Документ прежней формы прямо в текущую строку записи — для тестов миграций
     * понятий: сторож записи истиной новых имён прежние не пропустит, а на
     * стенде такие записи лежат с прежних выкатов. Схема БД — только отсюда:
     * соседние модули к ней не ходят (ТЗ-BACKEND §2.2).
     */
    fun подложитьДокумент(id: String, документ: String) {
        conn.prepareStatement("UPDATE orbita_kernel.entity SET doc = ?::jsonb WHERE id = ? AND valid_to IS NULL").use {
            it.setString(1, документ); it.setString(2, id); it.executeUpdate()
        }
    }
}
