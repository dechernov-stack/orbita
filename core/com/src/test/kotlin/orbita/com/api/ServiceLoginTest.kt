// Служебный вход стенда (27.09): пускать по ключу — только в режиме telegram и
// когда ключ задан; сравнение — константного времени, префикс верного ключа не
// проходит; чужой/пустой ключ — отказ, не «выключено».
package orbita.com.api

import orbita.com.api.ServiceLogin.Итог
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ServiceLoginTest {
    private val ключ = "s3rvice-key-абвгд"

    @Test
    fun `нет режима telegram или нет ключа — маршрута нет`() {
        assertEquals(Итог.ВЫКЛ, ServiceLogin.решение(telegramMode = false, key = ключ, предъявлен = ключ))
        assertEquals(Итог.ВЫКЛ, ServiceLogin.решение(telegramMode = true, key = null, предъявлен = ключ))
        assertEquals(Итог.ВЫКЛ, ServiceLogin.решение(telegramMode = true, key = "", предъявлен = ключ))
    }

    @Test
    fun `верный ключ — ОК, чужой и пустой — ОТКАЗ`() {
        assertEquals(Итог.ОК, ServiceLogin.решение(telegramMode = true, key = ключ, предъявлен = ключ))
        assertEquals(Итог.ОТКАЗ, ServiceLogin.решение(telegramMode = true, key = ключ, предъявлен = "другой"))
        assertEquals(Итог.ОТКАЗ, ServiceLogin.решение(telegramMode = true, key = ключ, предъявлен = null))
        assertEquals(Итог.ОТКАЗ, ServiceLogin.решение(telegramMode = true, key = ключ, предъявлен = ""))
    }

    @Test
    fun `префикс или продолжение верного ключа не проходит — сравнение по всей длине`() {
        assertEquals(Итог.ОТКАЗ, ServiceLogin.решение(telegramMode = true, key = ключ, предъявлен = ключ.dropLast(1)))
        assertEquals(Итог.ОТКАЗ, ServiceLogin.решение(telegramMode = true, key = ключ, предъявлен = ключ + "x"))
    }
}
