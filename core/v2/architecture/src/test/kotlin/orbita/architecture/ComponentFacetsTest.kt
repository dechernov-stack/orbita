// Волна 3, архитектура: компонент как узел MBSE.
//
// Проверяются ворота волны — «ПО управления КА описано гранями»: у
// поведенческого компонента есть развёртывание, режимы и элементы обмена, а
// пустая грань говорит, чего в ней ждут и к какой точке. Лестница зрелости
// берётся С ПОЛКИ: это данные поставки, а не память инженера.
package orbita.architecture

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.architecture.api.ArchitectureFactory
import orbita.architecture.api.Maturity
import orbita.architecture.api.Nature
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.KernelFactory
import orbita.kernel.api.Provenance
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ComponentFacetsTest {

    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val архитектура = ArchitectureFactory.architecture(store, links, mapper)
    private val провенанс = Provenance(Channel.MANUAL, "Ведущий СИ")
    private val проект = "PJ-A01"
    private val область = Area.Project(проект)

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        store.create(проект, "project", область, "1", mapper.createObjectNode(), провенанс)
        // Полка шаблонов — из поставки: лестница зрелости приходит данными.
        val файл = TestDbV2.repoRoot.resolve("docs/tz/v2/ПОЛКА-ШАБЛОНЫ-КОМПОНЕНТОВ.json").toFile()
        store.create(
            "TCS-9002", "component_template_shelf", Area.Library, null,
            mapper.readTree(файл), Provenance(Channel.PACKAGE, "поставка"),
        )
    }

    private fun узел(код: String, имя: String, род: String, уровень: Int = 4) = store.create(
        код, "component", область, "8",
        mapper.readTree("""{"name":"$имя","level":$уровень,"nature":"$род","kind":"assembly"}"""),
        провенанс,
    )

    /** Собирает пример из ТЗ: ПО управления КА на БЦВМ со всеми гранями. */
    private fun пример(): Pair<String, String> {
        val бцвм = узел("OBC-CPU", "БЦВМ", "node")
        val по = узел("OBC-SW", "ПО управления КА", "behaviour")
        архитектура.deploy(проект, "OBC-SW", "OBC-CPU", "Ведущий СИ", "поведение исполняется на БЦВМ")

        val фдир = store.create(
            "F-16", "function", область, "7",
            mapper.readTree("""{"name":"FDIR","layer":"SA","allocated_to":["${по.id}"]}"""), провенанс,
        )
        val стык = store.create(
            "IF-TTC-OBC", "interface", область, "8",
            mapper.readTree(
                """{"name":"кадры TM/TC","type":"data","a":"${по.id}","b":"${бцвм.id}",
                    "direction":"bi","requirement_classes":["data"]}""",
            ),
            провенанс,
        )
        val обмен = store.create(
            "EX-TC", "exchange", область, "8",
            mapper.readTree("""{"name":"команда","interface":"${стык.id}","payload":"TC"}"""), провенанс,
        )
        store.create(
            "XI-TC-FRAME", "exchange_item", область, "8",
            mapper.readTree(
                """{"name":"TC-frame","type":"operation","exchanges":["${обмен.id}"],
                    "elements":[{"name":"apid"},{"name":"seq"},{"name":"cmd_id"}]}""",
            ),
            провенанс,
        )
        store.create(
            "SM-OBC-SW", "state_machine", область, "8",
            mapper.readTree(
                """{"owner":"${по.id}","initial":"init",
                    "states":[{"code":"init","name":"Инициализация","kind":"mode","active_functions":[]},
                              {"code":"safe","name":"Безопасный","kind":"mode","active_functions":["${фдир.id}"]}],
                    "transitions":[{"from":"init","to":"safe","trigger":{"event":"FDIR level ≥ 2"}}]}""",
            ),
            провенанс,
        )
        store.create(
            "OBC-SW.cpu_load", "parameter", область, "8",
            mapper.readTree(
                """{"target":"${по.id}","key":"cpu_load","name":"загрузка CPU",
                    "measure":{"value":45,"unit":"%"},"origin":"shelf","required_to":"SRR",
                    "maturity_class":"estimated","uncertainty":30}""",
            ),
            провенанс,
        )
        store.create(
            "TECH-FDIR", "technology", область, "8",
            mapper.readTree(
                """{"name":"алгоритмы FDIR","component":"${по.id}","trl_current":5,
                    "trl_required":6,"required_by":"PDR"}""",
            ),
            провенанс,
        )
        store.create(
            "RQ-S-08", "requirement", область, "8",
            mapper.readTree(
                """{"level":"subsystem","title":"защита команд","carrier":"${по.id}",
                    "statement":"ПО должно проверять подпись команды.","category":"safety",
                    "verification_method":"inspection","ears_pattern":"ubiquitous"}""",
            ),
            провенанс,
        )
        return бцвм.id to по.id
    }

    @Test
    fun `карточка ПО управления собирается гранями`() {
        пример()
        val карточка = архитектура.card(проект, "OBC-SW", "SDR")
        assertEquals(Nature.BEHAVIOUR, карточка.component.nature)

        fun грань(ключ: String) = карточка.facets.single { it.key == ключ }
        assertTrue(
            грань("deployment").lines.single().what.contains("развёрнут на OBC-CPU"),
            "поведение обязано знать свой носитель: ${грань("deployment").lines}",
        )
        assertTrue(грань("functions").lines.any { it.what.contains("F-16") }, "функции узла: ${грань("functions").lines}")
        assertTrue(грань("interfaces").lines.any { it.what.contains("IF-TTC-OBC") })
        assertTrue(
            грань("exchange_items").lines.any { it.what.contains("XI-TC-FRAME") && it.what.contains("apid") },
            "элемент обмена обязан показывать СТРУКТУРУ: ${грань("exchange_items").lines}",
        )
        assertTrue(
            грань("modes").lines.any { it.what.contains("Безопасный") && it.what.contains("F-16") },
            "режим обязан показывать, что в нём активно: ${грань("modes").lines}",
        )
        assertTrue(грань("parameters").lines.any { it.what.contains("cpu_load") })
        assertTrue(грань("requirements").lines.any { it.what.contains("RQ-S-08") })
        assertTrue(грань("technologies").lines.any { it.what.contains("TRL 5") })
    }

    @Test
    fun `пустая грань говорит, чего в ней ждут и к какой точке`() {
        пример()
        val бюджеты = архитектура.card(проект, "OBC-SW", "SDR").facets.single { it.key == "budgets" }
        assertTrue(бюджеты.empty, "бюджетов в примере нет — это и проверяется")
        assertNotNull(бюджеты.expected, "пустая грань обязана сказать, чего в ней ждут")
        assertTrue(
            бюджеты.expected!!.contains("вклад"),
            "ожидание — словами инженера, а не «нет данных»: ${бюджеты.expected}",
        )
        assertEquals("SDR", бюджеты.requiredTo, "срок грани берётся с полки, а не из головы")
    }

    @Test
    fun `поведенческий компонент без носителя — разрыв, и развернуть можно только на node`() {
        узел("OBC-CPU", "БЦВМ", "node")
        узел("PL-SW", "ПО полезной нагрузки", "behaviour")

        val разрывы = архитектура.card(проект, "PL-SW", "SRR").gaps
        assertTrue(
            разрывы.any { it.facet == "deployment" && it.what.contains("не развёрнут") },
            "behaviour без носителя — брак модели: $разрывы",
        )

        val отказ = assertFailsWith<IllegalArgumentException> {
            архитектура.deploy(проект, "OBC-CPU", "PL-SW", "Ведущий СИ", "наоборот")
        }
        assertTrue(
            отказ.message!!.contains("не поведенческий"),
            "разворачивают поведение на носителе, а не наоборот: ${отказ.message}",
        )
    }

    @Test
    fun `лестница зрелости монотонна — к SDR спрашивают больше, чем к MCR`() {
        пример()
        val кMCR = архитектура.gaps(проект, "MCR").filter { it.component == "OBC-SW" }.map { it.facet }.toSet()
        val кSDR = архитектура.gaps(проект, "SDR").filter { it.component == "OBC-SW" }.map { it.facet }.toSet()
        assertTrue(
            кSDR.containsAll(кMCR),
            "грань, обязательная на ступени N, обязательна и на N+1: MCR=$кMCR SDR=$кSDR",
        )
        assertTrue(кSDR.size > кMCR.size, "к архитектуре спрашивают больше, чем к концепции: $кMCR → $кSDR")
        assertTrue(
            "modes" !in кMCR && "modes" !in кSDR,
            "режимы у этого узла описаны — разрывом они быть не могут: $кSDR",
        )
        assertTrue(
            архитектура.gaps(проект, "MCR").none { it.component == "OBC-CPU" && it.facet == "identity" },
            "идентичность заполнена всегда: она и есть сам узел",
        )
    }

    @Test
    fun `конструктор правит лестницу с обоснованием, но жёсткую грань не снимает`() {
        val сэп = узел("EPS", "Система электропитания", "node", 3)

        val доПравки = архитектура.card(проект, "EPS", "SRR").gaps.map { it.facet }.toSet()
        assertTrue(
            setOf("supplier", "risks", "parameters", "models").all { it in доПравки },
            "лестница шаблона спрашивает эти грани к SRR: $доПравки",
        )

        // Лестница шаблона — умолчание: конструктор переносит грань позже и
        // снимает лишнее, но только С ОБОСНОВАНИЕМ и не жёсткое.
        store.update(
            сэп.id,
            сэп.doc.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().set(
                "maturity_tailoring",
                mapper.readTree(
                    """[{"target":"supplier","action":"shift","to_gate":"PDR",
                         "rationale":"поставщик выбирается после конкурса","by":"Ведущий СИ"},
                        {"target":"risks","action":"remove",
                         "rationale":"риски СЭП ведутся общим реестром платформы","by":"Ведущий СИ"},
                        {"target":"parameters","action":"remove",
                         "rationale":"анкету заполним потом","by":"Ведущий СИ"},
                        {"target":"models","action":"remove"}]""",
                ),
            ),
            провенанс,
        )

        val послеПравки = архитектура.card(проект, "EPS", "SRR").gaps.map { it.facet }.toSet()
        assertTrue("supplier" !in послеПравки, "перенос на PDR снимает грань со ступени SRR: $послеПравки")
        assertTrue("risks" !in послеПравки, "снятие с обоснованием работает: $послеПравки")
        assertTrue(
            "parameters" in послеПравки,
            "жёсткая грань не снимается ничем: масса и мощность к MCR — это регламент, а не вкус",
        )
        assertTrue(
            "models" in послеПравки,
            "правка без обоснования не считается: отклонение без причины неотличимо от забывчивости",
        )
    }

    @Test
    fun `слои архитектуры и параметр как сущность с классом зрелости`() {
        val (_, по) = пример()
        store.create(
            "CH-01", "functional_chain", область, "8",
            mapper.readTree(
                """{"name":"исполнить команду","steps":["${store.byCode(область, "F-16")!!.id}"]}""",
            ),
            провенанс,
        )

        val слои = архитектура.layers(проект).associateBy { it.key }
        assertEquals(listOf("OA", "SA", "chains", "LA", "PA"), архитектура.layers(проект).map { it.key })
        assertTrue(слои.getValue("SA").lines.any { it.what.contains("F-16") }, "функции — слой системного анализа")
        assertTrue(
            слои.getValue("chains").lines.any { it.what.contains("CH-01") && it.what.contains("F-16") },
            "цепочка показывает свои шаги: ${слои.getValue("chains").lines}",
        )
        assertTrue(слои.getValue("PA").lines.any { it.what.contains("OBC-SW") && it.what.contains("behaviour") })

        val параметр = архитектура.parameters(проект, "OBC-SW").single()
        assertEquals("cpu_load", параметр.key)
        assertEquals(Maturity.ESTIMATED, параметр.maturity)
        assertEquals(20, параметр.maturity.reservePercent, "резерв класса зрелости — данные полки, а не догадка")
        assertEquals("shelf", параметр.origin, "параметр без происхождения не принимается")
        assertEquals(по, store.byCode(область, "OBC-SW")!!.id)
    }
}
