// Служебная учётка стенда (27.09) и её отзыв. Логин со схемой «service:…»
// принимается (V103), сессия читается, dropSessionsOf снимает ВСЕ сессии этой
// учётки и не трогает чужие — это и есть мгновенный отзыв ключа при рестарте.
package orbita.mod.store

import orbita.mod.TestDb
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AuthStoreServiceTest {
    private val store = AuthStore(TestDb.conn)

    @BeforeEach
    fun чисто() = TestDb.truncateAll()

    @Test
    fun `логин service-… принимается схемой, сессия читается служебной личностью`() {
        store.createUser("service:orbita-tools", "x".repeat(12), "служебный orbita-tools")
        val токен = store.createSession("service:orbita-tools", days = 1)
        val кто = store.sessionUser(токен)
        assertNotNull(кто)
        assertEquals("service:orbita-tools", кто!!.login)
        assertEquals("служебный orbita-tools", кто.displayName)
    }

    @Test
    fun `dropSessionsOf снимает все сессии учётки и не трогает чужие`() {
        store.createUser("service:orbita-tools", "x".repeat(12), "служебный orbita-tools")
        store.createUser("ivanov", "x".repeat(12), "Иванов И.")
        val служебный1 = store.createSession("service:orbita-tools")
        val служебный2 = store.createSession("service:orbita-tools")
        val чужой = store.createSession("ivanov")

        store.dropSessionsOf("service:orbita-tools")

        assertNull(store.sessionUser(служебный1), "первая служебная сессия снята")
        assertNull(store.sessionUser(служебный2), "вторая служебная сессия снята")
        assertNotNull(store.sessionUser(чужой), "чужая сессия жива")
    }
}
