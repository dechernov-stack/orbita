// Ключ идентичности: дубль обязан находиться БЕЗ вызова модели — и обязан
// НЕ находиться там, где данных на ключ не хватает.
//
// Ни один тест здесь не поднимает базу и не зовёт службу: ступень 1 сверки
// по построению работает на данных кандидата, справочнике и словаре. Если
// для ключа однажды понадобится вызов, мера «дубль по ключу без живого
// вызова» будет потеряна — и сломается именно этот файл.
package orbita.knowledge

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.knowledge.internal.ЕдиницаКанона
import orbita.knowledge.internal.IdentityKeys
import orbita.knowledge.internal.Normalize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdentityKeysTest {

    private val mapper = ObjectMapper()
    private val ключи = IdentityKeys()

    /** Ключ нужды по онтологии — сторона плюс ядро формулировки. */
    private val поляНужды = listOf("stakeholder", "statement_core")

    private fun нужда(сторона: String, формулировка: String) =
        mapper.createObjectNode().put("stakeholder", сторона).put("statement", формулировка)

    @Test
    fun `организационная форма в имени стороны ключ не меняет`() {
        assertEquals(
            ключи.nameNormalized("Космическая связь").value,
            ключи.nameNormalized("ФГУП «Космическая связь»").value,
            "форма собственности живёт полем карточки, а не различает организации",
        )
    }

    @Test
    fun `повтор нужды иными словами того же смысла даёт тот же ключ`() {
        val первая = ключи.of(поляНужды, нужда("SH-0001", "Необходимо обеспечить связь в Арктике"))
        val вторая = ключи.of(поляНужды, нужда("SH-0001", "В Арктике требуется связь"))
        assertEquals(первая?.value, вторая?.value, "порядок слов и служебные слова второй нужды не заводят")
    }

    @Test
    fun `ключ называет поля, из которых сложился`() {
        val ключ = ключи.of(поляНужды, нужда("SH-0001", "Связь в Арктике"))
        assertEquals(listOf("stakeholder", "statement"), ключ?.fields, "находка обязана сказать, чем именно сравнивали")
    }

    @Test
    fun `рамка узнаётся ключом своей границы`() {
        val рамка = mapper.createObjectNode().put("statement", "масса платформы не более 100 кг")
        рамка.putObject("bound").put("key", "mass").put("op", "le").put("value", 100).put("unit", "кг")
        val ключ = ключи.of(listOf("bound.key|statement_core"), рамка)
        assertEquals("mass", ключ?.value)
        assertEquals(listOf("bound.key"), ключ?.fields)
    }

    @Test
    fun `показатель цели без ключа поля узнаётся размерностью единицы`() {
        val сНоменклатурой = IdentityKeys(
            Normalize(mapOf("мка" to ЕдиницаКанона("МКА", "количество", true, 1.0, "linear"))),
        )
        val цель = mapper.createObjectNode().put("statement", "развернуть группировку")
        цель.putObject("measure").put("op", ">=").put("value", 180).put("unit", "МКА")
        val ключ = сНоменклатурой.of(listOf("statement_core", "measure.key"), цель)
        assertTrue(ключ!!.value.endsWith("размерность:количество"), "ключ цели держится на том, ЧТО меряем")
    }

    /**
     * З-05: цели МG-0041…0048 (формулировка) и MG-0049…0053 (та же цель с
     * числами) читались как разные — числа сидели в ядре, а показатель — в
     * ключе. Теперь ключ цели — ядро формулировки после нормализации названий
     * этапов; числа — к показателю, и совпавший ключ даёт «дополнить меру».
     */
    @Test
    fun `цель с числами и без — один ключ, название этапа — одно слово`() {
        val словами = mapper.createObjectNode().put("statement", "Лётная демонстрация: подтвердить работу сервиса")
        val сЧислами = mapper.createObjectNode().put("statement", "Лётная демонстрация 2–4 КА: подтвердить работу сервиса")
        сЧислами.putObject("measure").put("op", "=").put("min", 2).put("max", 4).put("unit", "КА")
        val первый = ключи.ofConcept("goal", словами)
        val второй = ключи.ofConcept("goal", сЧислами)
        assertNotNull(первый, "цель без показателя ключ получает: ключ — формулировка")
        assertEquals(первый!!.value, второй!!.value, "числа — к показателю, не к ключу")
        assertTrue("летная_демонстрация" in первый.value, "название этапа — одно слово ключа (ё→е плоско): ${первый.value}")
        val иной = ключи.ofConcept("goal", mapper.createObjectNode().put("statement", "Национальная система: подтвердить работу сервиса"))
        assertNotEquals(первый.value, иной!!.value, "другой этап — другая цель")
    }

    @Test
    fun `ключ нужды берётся из онтологии, а не из второго перечня в коде`() {
        val кандидат = нужда("SH-0001", "Связь в Арктике")
        assertEquals(
            ключи.of(поляНужды, кандидат)?.value,
            ключи.ofConcept("need", кандидат)?.value,
            "перечень полей ключа живёт в ОНТОЛОГИЯ-ФОРМИРОВАНИЯ",
        )
    }

    // --- отказные: система не склеивает и не выдумывает -----------------

    @Test
    fun `понятие вне онтологии ключа не получает`() {
        assertFailsWith<IllegalStateException>("понятие вне онтологии не образуется") {
            ключи.ofConcept("веха_придуманная_в_коде", нужда("SH-0001", "Связь в Арктике"))
        }
    }

    @Test
    fun `та же формулировка у другой стороны даёт другой ключ`() {
        val наша = ключи.of(поляНужды, нужда("SH-0001", "Связь в Арктике"))
        val чужая = ключи.of(поляНужды, нужда("SH-0002", "Связь в Арктике"))
        assertNotEquals(наша?.value, чужая?.value, "нужда принадлежит стороне — двух сторон одна нужда не склеивает")
    }

    @Test
    fun `у нужды без стороны ключ не складывается`() {
        val безСтороны = mapper.createObjectNode().put("statement", "Связь в Арктике")
        assertNull(
            ключи.of(поляНужды, безСтороны),
            "на неполном вводе ключ не выдумывается — кандидат идёт к воротам нехватки",
        )
    }

    @Test
    fun `отрицание из ключа не выбрасывается`() {
        val есть = ключи.of(поляНужды, нужда("SH-0001", "Связь в Арктике требуется"))
        val нет = ключи.of(поляНужды, нужда("SH-0001", "Связь в Арктике не требуется"))
        assertNotEquals(есть?.value, нет?.value, "снять «не» значит склеить требование с его противоположностью")
    }

    @Test
    fun `цель с единицей вне справочника получает показателем саму единицу`() {
        // Не догадка, а сама единица: «180 МКА» и «200 МКА» — об одном результате,
        // «180 МКА» и «2 сценария» — о разных. Пока ключа не было вовсе, повторный
        // приём заводил восемь целей вторым экземпляром (216, 17.09).
        val цель = mapper.createObjectNode().put("statement", "развернуть группировку")
        цель.putObject("measure").put("value", 180).put("unit", "МКА")
        val таЖе = mapper.createObjectNode().put("statement", "развернуть группировку")
        таЖе.putObject("measure").put("value", 200).put("unit", "мка")
        val другая = mapper.createObjectNode().put("statement", "развернуть группировку")
        другая.putObject("measure").put("value", 2).put("unit", "сценария")

        val ключ = assertNotNull(ключи.of(listOf("statement_core", "measure.key"), цель))
        assertEquals(ключ.value, ключи.of(listOf("statement_core", "measure.key"), таЖе)!!.value)
        assertTrue(ключ.value != ключи.of(listOf("statement_core", "measure.key"), другая)!!.value)
    }
}
