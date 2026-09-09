// ADR-066: роль «от имени» живёт в сессии и переживает перезапуск сервера
// (она в базе, не в памяти); снятие возвращает свою роль.
package orbita.com.api

import orbita.mod.TestDb
import orbita.mod.store.AuthStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ActingRoleTest {
    @Test
    fun `роль от имени хранится в сессии и снимается`() {
        val conn = TestDb.conn
        val auth = AuthStore(conn)
        // логин вида «tg:<id>» — как заводит вход через Telegram (V047 допускает его)
        val login = "tg:${System.nanoTime()}"
        auth.createUser(login, "случайный-пароль-1", "Чернов Д.")
        val token = auth.createSession(login)
        assertNull(auth.sessionUser(token)!!.actingRole, "по умолчанию — своя роль")
        auth.setActingRole(token, "specialist")
        val как = auth.sessionUser(token)
        assertNotNull(как)
        assertEquals("specialist", как!!.actingRole, "выбор пережил повторное чтение сессии — он в базе")
        auth.setActingRole(token, null)
        assertNull(auth.sessionUser(token)!!.actingRole)
    }
}
