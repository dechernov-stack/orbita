// Схема на экране и модель дела — из ОДНОГО шаблона.
//
// Приёмка РЕШЕНИЯ-ПРОЦЕСС-НА-ЭКРАНЕ: «схема порождена из того же CMMN, что
// исполняет движок (тест: изменение шаблона меняет и схему, и поведение)».
// Здесь это и проверяется буквально: правим шаблон — меняются оба.
package orbita.process

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.process.api.GateEvaluator
import orbita.process.api.ProcessFactory
import orbita.process.internal.CmmnGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OneSourceTest {

    private val mapper = ObjectMapper()

    /** Шаблон из двух сцен: у первой — мероприятие, у второй — вход по первой. */
    private fun шаблон(мероприятие: String): ObjectNode = mapper.readTree(
        """
        {"code":"PHT-TEST","title":"Проверочная фаза","standard":"NASA-7120","phase":"Pre-Phase A",
         "scenes":[
           {"key":"1","title":"Первая","order":1,"role":"lead","entry":[],
            "exit":[{"check":"always_ok","title":"условие"}],
            "activities":[{"code":"A.1","name":"$мероприятие","goal":"цель",
                           "role":"lead","track":"design","inputs":[],
                           "outputs":[{"what":"нужды","kind":"need","min":1}],
                           "surface":"scene:1#a"}]},
           {"key":"2","title":"Вторая","order":2,"role":"lead",
            "entry":[{"check":"scene_done:1","title":"первая прожита"}],
            "exit":[],"activities":[]}],
         "points":[{"key":"P1","title":"Точка","order":1,"offset_days":10,
                    "criteria":[{"check":"scene_done:1","blocking":true,"title":"первая прожита"}]}],
         "lanes":[]}
        """.trimIndent(),
    ) as ObjectNode

    private fun движок(док: ObjectNode, нужд: Int) = ProcessFactory.engine(
        template = { док },
        evaluator = object : GateEvaluator {
            override fun why(project: String, check: String): String? =
                if (check == "always_ok") null else "условие «$check» не выполнено"
        },
        passedGates = { mutableSetOf() },
        gatePlan = { emptyMap() },
        outputCounter = { _, вид -> if (вид == "need") нужд else 0 },
    )

    @Test
    fun `имя мероприятия из шаблона видно и в схеме, и в модели дела`() {
        val док = шаблон("Уточнение потребностей")
        val вид = движок(док, нужд = 0).view("PJ-TEST")
        val дело = вид.scenes.first().activities.single()
        assertEquals("Уточнение потребностей", дело.name, "схема берёт имя из шаблона")

        val xml = CmmnGenerator.изШаблона(док)
        assertTrue(
            xml.contains("""<humanTask id="act_1_A_1" name="Уточнение потребностей"/>"""),
            "мероприятие обязано стать human task в модели дела: $xml",
        )

        // Правим ШАБЛОН — меняются оба: и схема, и модель, которую исполняет движок.
        val правленый = шаблон("Выявление пользователей")
        assertEquals(
            "Выявление пользователей",
            движок(правленый, нужд = 0).view("PJ-TEST").scenes.first().activities.single().name,
        )
        val новыйXml = CmmnGenerator.изШаблона(правленый)
        assertTrue(новыйXml.contains("""name="Выявление пользователей"/>"""), новыйXml)
        assertFalse(новыйXml.contains("Уточнение потребностей"), "старое имя из модели уходит")
    }

    @Test
    fun `состояние мероприятия меняется от ДАННЫХ, а не от шаблона`() {
        val док = шаблон("Уточнение потребностей")

        val пусто = движок(док, нужд = 0).view("PJ-TEST")
        assertEquals("AVAILABLE", пусто.scenes.first().activities.single().state.name)
        assertEquals(0, пусто.scenes.first().activities.single().outputs.single().count)

        val сДанными = движок(док, нужд = 4).view("PJ-TEST")
        val дело = сДанными.scenes.first().activities.single()
        assertEquals(4, дело.outputs.single().count, "счётчик выхода берётся из данных")
        assertEquals("DONE", дело.state.name, "выходы есть — мероприятие закрылось само, без кнопки «готово»")
    }
}
