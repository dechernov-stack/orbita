// Сторож адресов трёх полок (ADR-052): архитектурная полка ссылается на узлы
// каркаса и стыки полки интерфейсов КОДАМИ — «@PL-S». Код, которого нет в
// соседней полке, не отказал бы при сборке пакета: он молча пришёл бы в проект
// ссылкой в пустоту, и матрица «функции × узлы» показала бы дыру без причины.
// Поэтому адреса сверяются здесь, до стенда, как проверки задач фазы.
package orbita.com.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArchitectureSeedsTest {

    private val mapper = ObjectMapper()

    private fun пакет(имя: String): JsonNode = mapper.readTree(
        RepoPaths.repoRoot().resolve("docs/tz/manual-run/packets/$имя").toFile(),
    )

    private fun пачка(имя: String): List<JsonNode> =
        пакет(имя).path("objects")[0].path("payload").path("objects").toList()

    private val каркас = пачка("18-каркас-pbs.json")
    private val стыки = пачка("19-интерфейсы.json")
    private val архитектура = пачка("20-архитектура-arcadia.json")
    private val работы = пачка("22-wbs.json")

    private fun коды(объекты: List<JsonNode>): Set<String> =
        объекты.mapNotNull { it.path("code").asText("").ifBlank { null } }.toSet()

    /** Все «@код», встречающиеся в пачке, с указанием, где именно. */
    private fun адреса(объекты: List<JsonNode>): List<Pair<String, String>> =
        объекты.flatMap { o ->
            Regex("@([A-Za-zА-Яа-я0-9_.\\-]+)").findAll(mapper.writeValueAsString(o))
                .map { it.groupValues[1] to o.path("id").asText() }.toList()
        }

    @Test
    fun `каждый адрес архитектурной полки существует в каркасе или в стыках`() {
        val известные = коды(каркас) + коды(стыки)
        val чужие = адреса(архитектура).filter { it.first !in известные }
        assertTrue(чужие.isEmpty(), "адреса в пустоту: " + чужие.joinToString { "${it.second} → @${it.first}" })
    }

    @Test
    fun `стороны стыков — узлы каркаса, и их ровно две`() {
        val узлы = коды(каркас)
        стыки.forEach { iface ->
            val owners = iface.path("owners").map { it.asText().removePrefix("@") }
            assertEquals(2, owners.size, "${iface.path("code").asText()}: сторон ${owners.size}")
            owners.forEach { code ->
                assertTrue(code in узлы, "${iface.path("code").asText()}: сторона $code не узел каркаса")
            }
        }
    }

    @Test
    fun `функции распределены на узлы, обмены идут по заведённым стыкам`() {
        val узлы = коды(каркас)
        val стыкиКоды = коды(стыки)
        val функции = архитектура.filter { it.path("id").asText().startsWith("FN-") }
        assertEquals(25, функции.size, "функций в полке: ${функции.size}")
        функции.forEach { fn ->
            val узел = fn.path("allocated_to").firstOrNull()?.path("component")?.asText("")?.removePrefix("@")
            assertTrue(!узел.isNullOrBlank() && узел in узлы,
                "${fn.path("code").asText()}: распределение на «$узел» вне каркаса")
            fn.path("exchanges").forEach { ex ->
                val стык = ex.path("interface").asText("").removePrefix("@")
                assertTrue(стык in стыкиКоды, "обмен ${ex.path("code").asText()} идёт по неизвестному стыку $стык")
            }
        }
    }

    @Test
    fun `цепочки ведут на функции полки, логические компоненты — на её функции и узлы каркаса`() {
        val свои = архитектура.map { it.path("id").asText() }.toSet()
        val узлы = коды(каркас)
        val цепочки = архитектура.filter { it.path("id").asText().startsWith("FC-") }
        assertEquals(6, цепочки.size, "цепочек в полке: ${цепочки.size}")
        цепочки.forEach { c ->
            (c.path("steps") + c.path("ack")).forEach { шаг ->
                val fn = шаг.path("function").asText()
                assertTrue(fn in свои, "${c.path("code").asText()}: шаг $fn не из полки")
            }
            c.path("capability").asText("").takeIf { it.isNotBlank() }?.let {
                assertTrue(it in свои, "${c.path("code").asText()}: способность $it не из полки")
            }
        }
        val логические = архитектура.filter { it.path("id").asText().startsWith("LC-") }
        assertEquals(9, логические.size, "логических компонентов: ${логические.size}")
        логические.forEach { lc ->
            lc.path("functions").forEach { fn ->
                assertTrue(fn.asText() in свои, "${lc.path("code").asText()}: функция ${fn.asText()} не из полки")
            }
            lc.path("deployed_to").forEach { cm ->
                val code = cm.asText().removePrefix("@")
                assertTrue(code in узлы, "${lc.path("code").asText()}: развёртывание на «$code» вне каркаса")
            }
        }
    }

    @Test
    fun `единицы анкет стыков есть в справочнике - иначе анкета спросит несуществующее`() {
        val реестр = пакет("07-справочник-единиц.json").path("objects")[0]
        val известные = buildSet {
            реестр.path("dimensions").forEach { d ->
                add(d.path("canon").asText())
                d.path("spellings").forEach { add(it.asText()) }
                d.path("inputs").forEach { i ->
                    add(i.path("unit").asText())
                    i.path("spellings").forEach { add(it.asText()) }
                }
            }
        }
        val чужие = стыки.flatMap { iface ->
            iface.path("expects").mapNotNull { поле ->
                поле.path("unit").asText("").takeIf { it.isNotBlank() && it !in известные }
                    ?.let { iface.path("code").asText() + "/" + поле.path("key").asText() + ": " + it }
            }
        }
        assertTrue(чужие.isEmpty(), "единицы вне справочника: $чужие")
    }

    @Test
    fun `пары WBS ведут на узлы каркаса, а сквозной пакет узлов не имеет`() {
        val узлы = коды(каркас)
        assertEquals(54, работы.size, "пакетов работ: ${работы.size}")
        val чужие = адреса(работы).filter { it.first !in узлы }
        assertTrue(чужие.isEmpty(), "пары в пустоту: " + чужие.joinToString { "${it.second} → @${it.first}" })
        val пар = работы.sumOf { it.path("component_refs").size() }
        assertTrue(пар >= 44, "пар с узлами: $пар")
        // Сквозной пакет живёт задачами фазы, а не составом: у 01–04 узлов нет
        // по построению. Пакет 10 «Интеграция и испытания» помечен сквозным и
        // при этом несёт испытательную базу SUP-TEST — расхождение ПОСТАВКИ с
        // её же текстом («сквозные 01–04, 10, 12 узлов не имеют»); подгонять
        // данные под текст служба не вправе, вопрос владельцу отдан отдельно.
        listOf("01", "02", "03", "04").forEach { код ->
            val пакет = работы.first { it.path("code").asText() == код }
            assertEquals(0, пакет.path("component_refs").size(),
                "$код: сквозной пакет узлов не имеет по построению")
            assertTrue(пакет.path("cross_cutting").asBoolean(false), "$код: пакет обязан быть помечен сквозным")
        }
        работы.filter { it.path("cross_cutting").asBoolean(false) && it.path("component_refs").size() > 0 }
            .forEach { пакет ->
                assertEquals("10", пакет.path("code").asText(),
                    "сквозной пакет с узлами известен ровно один (10, испытательная база): " +
                        пакет.path("code").asText())
            }
    }

    @Test
    fun `каждый узел второго и третьего уровня получил пакет работ - сам или от родителя`() {
        val поКоду = каркас.associateBy { it.path("code").asText() }
        val родитель = каркас.associate { узел ->
            val p = узел.path("parent").asText("")
            узел.path("code").asText() to (каркас.firstOrNull { it.path("id").asText() == p }?.path("code")?.asText() ?: "")
        }
        val спарен = работы.flatMap { пакет ->
            пакет.path("component_refs").map { it.asText().removePrefix("@") }
        }.toSet()
        fun покрыт(code: String): Boolean {
            var c = code
            var шаг = 0
            while (c.isNotBlank() && шаг < 12) {
                if (c in спарен) return true
                c = родитель[c] ?: ""
                шаг += 1
            }
            return false
        }
        val дыры = поКоду.values
            .filter { it.path("level").asInt(-1) in 2..3 }
            .map { it.path("code").asText() }
            .filterNot { покрыт(it) }
        assertTrue(дыры.isEmpty(), "узлы L2/L3 без пакета работ: $дыры")
    }

    @Test
    fun `данные полок полны - вне рекомендованного набора ровно то, что назвал владелец, флагов зависимости нет`() {
        // Решение Б3-01 ред. 2: optional/optional_on отозваны — ошибка пряталась в
        // данные. Элемент вне рекомендованного набора класса несёт default_take=false,
        // выбор — у инженера в окне взятия, зависимости считаются по ссылкам.
        val все = каркас + стыки + архитектура + работы
        assertTrue(все.none { it.has("optional") || it.has("optional_on") || it.path("exchanges").any { ex -> ex.has("optional_on") } }) {
            "флаги зависимости в данных полок недопустимы"
        }
        fun внеНабора(объекты: List<JsonNode>) =
            объекты.filter { it.has("default_take") && !it.path("default_take").asBoolean(true) }.map { it.path("code").asText() }.toSet()
        assertEquals(setOf("PL-ISL", "PL-PNT", "PL-P", "MCC-SIM", "SC-EXP"), внеНабора(каркас))
        assertEquals(setOf("IF-ISL", "IF-PNT-USER"), внеНабора(стыки))
        assertEquals(setOf("F-22", "F-23", "FC-06", "LC-ISL"), внеНабора(архитектура))
        assertEquals(setOf("05.02", "05.05", "05.06", "07.05", "10.03"), внеНабора(работы))
        assertTrue("USR-APP" !in внеНабора(каркас)) { "USR-APP — в рекомендованном наборе: стык «ЦОД ↔ потребители» без него бессмыслен" }
    }

    @Test
    fun `сторож ловит выдуманный код - иначе проверка была бы украшением`() {
        val порченая = mapper.readTree(
            mapper.writeValueAsString(архитектура).replace("@PL-S", "@ВЫДУМАННЫЙ-УЗЕЛ"),
        ).toList()
        val известные = коды(каркас) + коды(стыки)
        val чужие = адреса(порченая).filter { it.first !in известные }
        assertTrue(чужие.any { it.first == "ВЫДУМАННЫЙ-УЗЕЛ" },
            "подменённый код обязан всплыть в списке адресов в пустоту: $чужие")
    }

    @Test
    fun `пакеты полок собраны из поставки владельца, а не правлены руками`() {
        val процесс = ProcessBuilder("python3", "tools/build_shelf_packets.py", "--check")
            .directory(RepoPaths.repoRoot().toFile())
            .redirectErrorStream(true)
            .start()
        val вывод = процесс.inputStream.bufferedReader().readText()
        assertEquals(0, процесс.waitFor(), "сборка пакетов разошлась с поставкой:\n$вывод")
    }
}
