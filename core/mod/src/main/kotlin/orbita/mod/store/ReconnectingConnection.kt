// Переподключающееся соединение поверх пула (ПРИЁМКА-KNOWLEDGE-REMARKS §Стенд).
//
// Ядро держит ОДИН объект Connection и раздаёт его хранилищам — так решено
// в ADR-011, и переписывать все хранилища под «соединение на вызов» ради
// стенда не нужно. Нужно другое: чтобы этот один объект переживал смерть
// сокета. Поэтому наружу отдаётся ПРОКСИ: каждый вызов уходит в живое
// соединение из пула; перед вызовом вне транзакции соединение проверяется
// (`isValid`), мёртвое выбрасывается и берётся свежее.
//
// Внутри транзакции (autoCommit = false) соединение НЕ подменяется: подмена
// посреди транзакции тихо потеряла бы уже сделанные записи. Мёртвое
// соединение там всплывает исключением, транзакция откатывается — честно.
package orbita.mod.store

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException

/**
 * Пул: одно рабочее соединение (ядро однопоточно по соединению) плюс запас
 * на переподключение; проверка перед выдачей и по возрасту.
 */
class ReconnectingSource(url: String, user: String, password: String) {

    val pool: HikariDataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = url
            username = user
            this.password = password
            maximumPoolSize = 2
            minimumIdle = 1
            connectionTestQuery = "SELECT 1"
            validationTimeout = 3_000
            // Соединение не живёт дольше получаса: Docker-сеть на стенде рвёт
            // долгие сокеты молча, и лучше обновить заранее, чем узнать на запросе.
            maxLifetime = 30 * 60 * 1_000L
            keepaliveTime = 5 * 60 * 1_000L
            connectionTimeout = 10_000
            poolName = "orbita"
            // Имя клиента в pg_stat_activity и в журнале базы (%a): при
            // следующем обрыве видно, чьё соединение рвалось.
            addDataSourceProperty("ApplicationName", "orbita-api")
            // Автокоммит по умолчанию — как у DriverManager: хранилища
            // сами открывают транзакции через Connection.tx.
            isAutoCommit = true
        },
    )

    /** Прокси-соединение: единственный объект, который видит остальное ядро. */
    fun connection(): Connection {
        val handler = Handler(pool)
        return Proxy.newProxyInstance(
            Connection::class.java.classLoader, arrayOf(Connection::class.java), handler,
        ) as Connection
    }

    private class Handler(private val pool: HikariDataSource) : InvocationHandler {
        @Volatile
        private var live: Connection? = null

        /** Момент последнего удачного вызова: свежеотвеченное соединение не проверяют. */
        @Volatile
        private var удачно = 0L

        /** Живое соединение: проверенное, при нужде — свежее из пула. */
        private fun живое(): Connection {
            val текущее = live
            if (текущее != null) {
                // Драйвер сам помечает соединение закрытым после ошибки
                // ввода-вывода в любом Statement — а Statement через прокси
                // не ходит. Поэтому `isClosed` (локальный, без запроса)
                // спрашивается всегда, даже внутри транзакции и окна доверия.
                val закрыто = runCatching { текущее.isClosed }.getOrDefault(true)
                if (!закрыто) {
                    val вТранзакции = runCatching { !текущее.autoCommit }.getOrDefault(false)
                    if (вТранзакции) return текущее
                    // `isValid` — это запрос к базе; гонять его перед каждым
                    // вызовом JDBC (их десятки на один HTTP-запрос) — удвоить
                    // нагрузку. Соединение, ответившее меньше секунды назад,
                    // считается живым; обрыв внутри секунды ловит повтор в HttpApi.
                    if (System.currentTimeMillis() - удачно < ОКНО_ДОВЕРИЯ_МС) return текущее
                    if (runCatching { текущее.isValid(2) }.getOrDefault(false)) return текущее
                }
                runCatching { текущее.close() }
            }
            return pool.connection.also { live = it }
        }

        /** Соединение умерло: сбросить, чтобы следующий вызов взял свежее. */
        private fun сбросить() {
            live?.let { runCatching { it.close() } }
            live = null
            удачно = 0L
        }

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            // Закрыть прокси = закрыть пул целиком: так ядро гасится на SIGTERM.
            if (method.name == "close" && args.isNullOrEmpty()) {
                сбросить(); pool.close(); return null
            }
            if (method.name == "isClosed" && args.isNullOrEmpty()) return pool.isClosed
            if (method.name == "toString") return "ReconnectingConnection(${pool.poolName})"
            if (method.name == "hashCode") return System.identityHashCode(proxy)
            if (method.name == "equals") return proxy === args?.get(0)
            val соединение = живое()
            return try {
                val r = if (args == null) method.invoke(соединение) else method.invoke(соединение, *args)
                удачно = System.currentTimeMillis()
                r
            } catch (e: InvocationTargetException) {
                val причина = e.targetException
                // Мёртвое соединение сбрасывается СРАЗУ: следующий запрос
                // возьмёт свежее, а этот честно падает — его повторяет слой HTTP.
                if (причина is SQLException && умерло(причина)) сбросить()
                throw причина
            }
        }
    }

    companion object {
        /** Окно, в котором отвечавшее соединение не перепроверяют. */
        const val ОКНО_ДОВЕРИЯ_МС = 1_000L

        /** SQLState 08xxx — «connection exception»; текст — драйвер PostgreSQL. */
        fun умерло(e: SQLException): Boolean =
            (e.sqlState?.startsWith("08") == true) ||
                (e.message?.contains("connection has been closed", ignoreCase = true) == true) ||
                (e.message?.contains("This connection has been closed", ignoreCase = true) == true) ||
                (e.message?.contains("terminating connection", ignoreCase = true) == true)
    }
}
