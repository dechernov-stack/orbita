// Импорт ReqIF после сноса своего экспорта (ADR-064): черновик собирается
// и из файла прежнего канала, и из файла StrictDoc-канала, чужие атрибуты
// видны, служебные — нет.
package orbita.out

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReqifImportTest {
    private val mapper = ObjectMapper()
    private fun т(s: String) = mapper.readTree("\"$s\"")

    @Test
    fun `прежний словарь имён — те же поля черновика`() {
        val so = SpecObject("SO-1", "ST-REQUIREMENT", mapOf(
            "ReqIF.ForeignID" to т("RQ-0001"), "ReqIF.Text" to т("КА должен…"), "Level" to т("system"),
            "MeasureOperator" to т("le"), "MeasureValue" to mapper.readTree("100"), "MeasureUnit" to т("кг"),
            "Status" to т("Draft"), "X-custom" to т("своё"),
        ))
        val черновик = fromSpecObject(so, mapper)
        assertEquals("RQ-0001", черновик.path("id").asText())
        assertEquals("system", черновик.path("level").asText())
        assertEquals("le", черновик.path("mop").path("operator").asText())
        assertEquals(100.0, черновик.path("mop").path("value").path("value").asDouble())
        assertEquals("Draft", черновик.path("lifecycle").path("status").asText())
        assertEquals("своё", черновик.path("custom").asText(), "прежний префикс X- читается полем")
        assertFalse(черновик.has("foreign_attributes"))
    }

    @Test
    fun `словарь грамматики Орбиты (StrictDoc) — те же поля, MID не чужой, незнакомое — чужое`() {
        val so = SpecObject("o1", "REQUIREMENT_x", mapOf(
            "ReqIF.ForeignID" to т("RQ-0002"), "ReqIF.Text" to т("Масса не более 100 кг."), "LEVEL" to т("project"),
            "MOP_OP" to т("le"), "MOP_VALUE" to т("100"), "MOP_UNIT" to т("кг"), "VERIFICATION_METHOD" to т("test"),
            "MID" to т("o123"), "DOORS_Object_Heading" to т("1.2"),
        ))
        val черновик = fromSpecObject(so, mapper)
        assertEquals("RQ-0002", черновик.path("id").asText())
        assertEquals("project", черновик.path("level").asText())
        assertEquals("le", черновик.path("mop").path("operator").asText())
        assertEquals("test", черновик.path("verification_method").asText())
        assertTrue(черновик.path("foreign_attributes").has("DOORS_Object_Heading"), "чужой атрибут виден")
        assertFalse(черновик.path("foreign_attributes").has("MID"), "MID — служебное StrictDoc")
    }
}
