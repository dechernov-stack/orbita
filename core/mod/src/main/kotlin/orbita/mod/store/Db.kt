// Подключение к PostgreSQL (ADR-011). Параметры — из окружения.
package orbita.mod.store

import java.sql.Connection
import java.sql.DriverManager

data class DbConfig(
    val url: String,
    val user: String,
    val password: String,
) {
    fun open(): Connection = DriverManager.getConnection(url, user, password)

    companion object {
        fun fromEnv(): DbConfig = DbConfig(
            url = System.getenv("ORBITA_DB_URL") ?: "jdbc:postgresql://127.0.0.1:5432/orbita",
            user = System.getenv("ORBITA_DB_USER") ?: "orbita",
            password = System.getenv("ORBITA_DB_PASSWORD") ?: "orbita",
        )
    }
}

/** Транзакция: commit при успехе, rollback при любом исключении. */
fun <T> Connection.tx(block: () -> T): T {
    val prev = autoCommit
    autoCommit = false
    try {
        val r = block()
        commit()
        return r
    } catch (e: Throwable) {
        rollback()
        throw e
    } finally {
        autoCommit = prev
    }
}
