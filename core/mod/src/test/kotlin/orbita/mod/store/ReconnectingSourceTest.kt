// Соединение ядра переживает смерть backend (ПРИЁМКА-KNOWLEDGE-REMARKS
// §Стенд): backend убивается `pg_terminate_backend` с соседнего соединения —
// так же, как его убивает recovery базы или пересоздание контейнера, —
// и следующий запрос обязан пройти без перезапуска процесса.
package orbita.mod.store

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

class ReconnectingSourceTest {

    private val url = System.getenv("ORBITA_TEST_DB_URL")!!
    private val user = System.getenv("ORBITA_TEST_DB_USER")!!
    private val password = System.getenv("ORBITA_TEST_DB_PASSWORD")!!

    private fun pid(c: Connection): Int = c.createStatement().use { st ->
        st.executeQuery("SELECT pg_backend_pid()").use { rs -> rs.next(); rs.getInt(1) }
    }

    /** Убить backend чужой сессии — как это делает recovery или SIGTERM базы. */
    private fun убить(pid: Int) {
        DriverManager.getConnection(url, user, password).use { c ->
            c.createStatement().use { it.execute("SELECT pg_terminate_backend($pid)") }
        }
    }

    private fun открыть(): Connection = ReconnectingSource(url, user, password).connection()

    @Test
    fun `после гибели backend следующий запрос идёт на свежем соединении без исключения`() {
        val conn = открыть()
        try {
            val было = pid(conn)
            убить(было)
            Thread.sleep(ReconnectingSource.ОКНО_ДОВЕРИЯ_МС + 200)
            val стало = pid(conn)
            assertNotEquals(было, стало, "прокси обязан взять свежее соединение из пула")
            assertEquals(стало, pid(conn), "живое соединение не подменяется")
        } finally { conn.close() }
    }

    @Test
    fun `обрыв в окне доверия — одно честное исключение с признаком обрыва, дальше живёт`() {
        val conn = открыть()
        try {
            val было = pid(conn)
            убить(было)
            val e = assertThrows<SQLException> { pid(conn) }
            assertTrue(ReconnectingSource.умерло(e), "обрыв должен узнаваться: ${e.sqlState} ${e.message}")
            assertNotEquals(было, pid(conn), "следующий вызов — уже на свежем соединении")
        } finally { conn.close() }
    }

    @Test
    fun `обрыв внутри транзакции не подменяет соединение молча — наружу причина обрыва`() {
        val conn = открыть()
        try {
            val было = pid(conn)
            val e = assertThrows<SQLException> {
                conn.tx {
                    убить(было)
                    pid(conn)
                }
            }
            assertTrue(ReconnectingSource.умерло(e), "наружу — обрыв базы, а не «cannot rollback»: ${e.message}")
            assertTrue(conn.autoCommit, "после транзакции соединение снова в автокоммите")
            assertNotEquals(было, pid(conn))
        } finally { conn.close() }
    }

    @Test
    fun `транзакция на живом соединении работает как прежде`() {
        val conn = открыть()
        try {
            conn.createStatement().use { it.execute("CREATE TEMP TABLE t_reconnect(x int)") }
            conn.tx { conn.createStatement().use { it.execute("INSERT INTO t_reconnect VALUES (1)") } }
            assertThrows<IllegalStateException> {
                conn.tx {
                    conn.createStatement().use { it.execute("INSERT INTO t_reconnect VALUES (2)") }
                    error("откат")
                }
            }
            val n = conn.createStatement().use { st ->
                st.executeQuery("SELECT count(*) FROM t_reconnect").use { rs -> rs.next(); rs.getInt(1) }
            }
            assertEquals(1, n, "откат транзакции сохранился")
        } finally { conn.close() }
    }

    @Test
    fun `close закрывает пул целиком`() {
        val conn = открыть()
        pid(conn)
        assertFalse(conn.isClosed)
        conn.close()
        assertTrue(conn.isClosed)
        assertThrows<SQLException> { pid(conn) }
    }

    @Test
    fun `умерло отличает обрыв соединения от обычной ошибки запроса`() {
        assertTrue(ReconnectingSource.умерло(SQLException("This connection has been closed.", "08003")))
        assertTrue(ReconnectingSource.умерло(SQLException("An I/O error occurred while sending to the backend.", "08006")))
        assertTrue(ReconnectingSource.умерло(SQLException("FATAL: terminating connection due to administrator command", "57P01")))
        assertFalse(ReconnectingSource.умерло(SQLException("syntax error at or near \"x\"", "42601")))
        assertFalse(ReconnectingSource.умерло(SQLException("duplicate key value violates unique constraint", "23505")))
    }
}
