// Подключение к PostgreSQL (ADR-011). Параметры — из окружения.
package orbita.mod.store

import java.sql.Connection
import java.sql.DriverManager

data class DbConfig(
    val url: String,
    val user: String,
    val password: String,
) {
    /**
     * Соединение ядра. Не голое DriverManager, а прокси поверх пула с
     * проверкой и переподключением: одно соединение без переподключения
     * превращало любой обрыв (пересоздание контейнера, recovery базы) в
     * мёртвый стенд до docker restart — процесс жив, healthcheck красный,
     * вход отвечает «This connection has been closed» (08.09, семь раз).
     */
    fun open(): Connection = ReconnectingSource(url, user, password).connection()

    /** Голое соединение без пула — для миграций и разовых утилит. */
    fun openPlain(): Connection = DriverManager.getConnection(url, user, password)

    companion object {
        fun fromEnv(): DbConfig = DbConfig(
            url = System.getenv("ORBITA_DB_URL") ?: "jdbc:postgresql://127.0.0.1:5432/orbita",
            user = System.getenv("ORBITA_DB_USER") ?: "orbita",
            password = System.getenv("ORBITA_DB_PASSWORD") ?: "orbita",
        )
    }
}

/**
 * Транзакция: commit при успехе, rollback при любом исключении.
 * Вложенный вызов НЕ управляет границами: commit посреди внешней транзакции
 * разрушил бы «всё или ничего» пакетных операций — границы держит внешняя.
 */
fun <T> Connection.tx(block: () -> T): T {
    if (!autoCommit) return block()
    val prev = autoCommit
    autoCommit = false
    try {
        val r = block()
        commit()
        return r
    } catch (e: Throwable) {
        // Откат на мёртвом соединении сам падает; наружу важна ПРИЧИНА
        // (обрыв базы), а не «cannot rollback» поверх неё — по причине
        // HttpApi решает, повторять ли запрос.
        runCatching { rollback() }
        throw e
    } finally {
        autoCommit = prev
    }
}
