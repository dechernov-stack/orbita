// Вход через Telegram: сессия шлюза проверяется локально — подпись, продукт,
// срок; сессия, выданная Python-шлюзом (tg_authgw.session), читается той же
// проверкой; чужой продукт и подделка — отказ.
package orbita.com.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TelegramGateTest {
    private val секрет = "test-secret"
    private val сейчас = 1788000000L + 3600
    private fun gate(product: String = "orbita", secret: String? = секрет, now: Long = сейчас) =
        TelegramGate(url = "http://authgw.test", product = product, secret = secret, now = { now })

    /** Выдана `tg_authgw.session.issue("orbita", "test-secret", 103638135, "Дмитрий")` при iat=1788000000. */
    private val изPython =
        "eyJwIjoib3JiaXRhIiwidGlkIjoxMDM2MzgxMzUsIm5hbWUiOiJcdTA0MTRcdTA0M2NcdTA0MzhcdTA0NDJcdTA0NDBcdTA0MzhcdTA0MzkiLCJpYXQiOjE3ODgwMDAwMDB9" +
            ".acTVk2JC55XlpMTT_8sicamu4sg2LVO4YuOBATXLahA"

    @Test
    fun `сессия Python-шлюза читается локально — тот же HMAC, тот же формат`() {
        val кто = gate().verify(изPython)
        assertNotNull(кто)
        assertEquals(103638135L, кто!!.tid)
        assertEquals("Дмитрий", кто.name)
    }

    @Test
    fun `своя выдача и проверка совпадают, а чужой продукт, подделка и срок — отказ`() {
        val сессия = TelegramGate.issue("orbita", секрет, 42, "Иванов И.", 1788000000L)
        assertEquals(TelegramGate.Identity(42, "Иванов И."), gate().verify(сессия))
        assertNull(gate(product = "afisha").verify(сессия), "сессия одного продукта не годна другому")
        assertNull(gate(secret = "other").verify(сессия), "другой секрет — подпись не сходится")
        assertNull(gate().verify(сессия.dropLast(2) + "xx"), "подделанная подпись")
        assertNull(gate(now = 1788000000L + TelegramGate.SESSION_TTL + 1).verify(сессия), "21 день прошёл")
        assertNull(gate().verify(null)); assertNull(gate().verify("без точки"))
    }

    @Test
    fun `режим включён только при шлюзе и секрете — иначе названо, чего не хватает`() {
        assertTrue(gate().enabled())
        val без = TelegramGate(url = null, product = "orbita", secret = null)
        assertFalse(без.enabled())
        assertEquals(listOf("AUTHGW_URL", "ORBITA_SESSION_SECRET"), без.missing())
    }
}
