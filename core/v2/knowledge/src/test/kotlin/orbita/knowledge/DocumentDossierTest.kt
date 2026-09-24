// Документ как источник — целиком (шип 4 §3, РЕШЕНИЕ-ДОКУМЕНТ-ПЕРВИЧЕН).
//
// Меры решения §8: второй устав — отказ с именем первого; досье документа —
// резюме, вклад по видам, сущности со ссылками; откат вклада «Приморья» —
// стороны и применения ушли, постановка из записки цела; переразбор новой
// версией промпта — старое superseded, дублей нет; документ из библиотеки
// взят в новый проект вместе с фактами без вызова модели; карта пробелов
// на нашей записке. Ни одного вызова модели: разбор приходит пакетом.
package orbita.knowledge

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry
import orbita.kernel.api.Provenance
import orbita.knowledge.api.Authority
import orbita.knowledge.api.Disposition
import orbita.knowledge.internal.EntityIntake
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DocumentDossierTest {

    private val mapper = ObjectMapper()
    private val store: EntityStore = ПамятьПоля()
    private val links: LinkRegistry = СвязиПоля()
    private val знания = EntityIntake(store, links, mapper)
    private val проект = "PJ-8030"
    private val область = Area.Project(проект)
    private val автор = "Иванов И."

    private val записка = """
# Замысел
Мы строим систему связи для коротких сообщений и телеметрии в Арктике.
Минтранс России — якорный заказчик системы.
Росморпорт — оператор портовой инфраструктуры.

# Нужды
Судовладельцам не хватает связи в высоких широтах.
""".trimIndent()

    private val приморье = """
# Обстановка Приморья
Росморпорт развивает порты Приморья.
Судовладельцам не хватает связи в высоких широтах.
Грузопоток СМП составит 160 млн т к 2035 году.
""".trimIndent()

    @BeforeTest
    fun проект() {
        store.create(
            проект, "project", область, "1",
            mapper.createObjectNode().put("name", "Досье документов"),
            Provenance(Channel.MANUAL, автор),
        )
    }

    private fun якоря(материал: String): List<String> = знания.canon(проект, материал).filter { it.kind != "heading" }.map { it.anchor }

    private fun факт(вид: String, субъект: String, утверждение: String, значение: String, якорь: String, единица: String? = null): String =
        """{"kind":"$вид","subject":"$субъект","predicate":"$утверждение","value":"$значение"${единица?.let { ",\"unit\":\"$it\"" } ?: ""},"quote":"$значение","source":{"anchor":"$якорь"},"mark":"И"}"""

    /** Пакет записки: заказчик, оператор, общая нужда — и план на три записи. */
    private fun пакетЗаписки(м: String): String {
        val я = якоря(м)
        return """{"summary":"Записка: заказчик и оператор названы, нужда судовладельцев, целей с числами нет.","topics":[],"facts":[
            ${факт("relation", "Минтранс России", "является заказчиком", "якорный заказчик", я[1])},
            ${факт("relation", "Росморпорт", "является оператором", "оператор портовой инфраструктуры", я[2])},
            ${факт("framing", "судовладельцы", "не хватает", "связи в высоких широтах", я[3])}
        ],"links":[],"actions":[
            {"kind":"create_entity","target_kind":"stakeholder","scene":"3","title":"завести сторону «Минтранс России»","preview":"","payload":{"name":"Минтранс России","role":"customer","interest":"телематика"},"facts":[0]},
            {"kind":"create_entity","target_kind":"stakeholder","scene":"3","title":"завести сторону «Росморпорт»","preview":"","payload":{"name":"Росморпорт","role":"operator","interest":"связь в портах"},"facts":[1]},
            {"kind":"create_entity","target_kind":"need","scene":"3","title":"завести нужду судовладельцев","preview":"","payload":{"statement":"связь в высоких широтах","owner":"Минтранс России"},"facts":[2]}
        ]}"""
    }

    /** Пакет Приморья: сторона-кандидат, та же нужда, чужая цель фактом. */
    private fun пакетПриморья(м: String): String {
        val я = якоря(м)
        return """{"summary":"Обстановка: порты Приморья, нужда судовладельцев подтверждается, чужая цель СМП.","topics":[],"facts":[
            ${факт("capability", "Росморпорт", "развивает", "порты Приморья", я[0])},
            ${факт("framing", "судовладельцы", "не хватает", "связи в высоких широтах", я[1])},
            ${факт("external_target", "СМП", "грузопоток к 2035", "160", я[2], "млн т")}
        ],"links":[],"actions":[
            {"kind":"create_entity","target_kind":"stakeholder","scene":"3","title":"завести сторону «Администрация портов Приморья»","preview":"","payload":{"name":"Администрация портов Приморья","role":"partner","interest":"порты"},"facts":[0]},
            {"kind":"create_entity","target_kind":"need","scene":"3","title":"нужда судовладельцев (подтверждение)","preview":"","payload":{"statement":"связь в высоких широтах","owner":"Минтранс России"},"facts":[1]}
        ]}"""
    }

    @Test
    fun `устав в проекте один — второй отказ с именем первого, новая версия через supersedes`() {
        val первый = знания.putMaterial(проект, "Записка миссии", "mission_memo", записка, автор, rank = Authority.MANDATORY, role = "charter")
        val отказ = assertFailsWith<IllegalArgumentException> {
            знания.putMaterial(проект, "Другая записка", "mission_memo", "# Иное\n\nТекст.", автор, rank = Authority.MANDATORY, role = "charter")
        }
        assertTrue("Записка миссии" in отказ.message!! && первый in отказ.message!!, отказ.message)
        // Тот же документ обстановкой — можно; новая версия устава — через supersedes.
        знания.putMaterial(проект, "Другая записка", "mission_memo", "# Иное\n\nТекст.", автор, rank = Authority.REFERENCE, role = "context")
        val версия2 = знания.putMaterial(проект, "Записка миссии v2", "mission_memo", записка + "\n\nДополнение.", автор, rank = Authority.MANDATORY, role = "charter", supersedes = первый)
        val отказ2 = assertFailsWith<IllegalArgumentException> {
            знания.putMaterial(проект, "Третья", "mission_memo", "# Ещё\n\nТекст.", автор, rank = Authority.MANDATORY, role = "charter")
        }
        assertTrue(версия2 in отказ2.message!!, "называется действующая версия, а не заменённая: ${отказ2.message}")
        // Смена роли лежащего документа — тем же правилом: обстановка уставом не становится, пока устав есть.
        val обстановка = store.list(область, "material").first { it.doc.path("role").asText() == "context" }.code
        val отказ3 = assertFailsWith<IllegalArgumentException> { знания.setRole(проект, обстановка, "charter", автор) }
        assertTrue(версия2 in отказ3.message!!, отказ3.message)
        assertEquals("tor", знания.setRole(проект, обстановка, "tor", автор))
        assertEquals("tor", store.byCode(область, обстановка)!!.doc.path("role").asText())
        assertFailsWith<IllegalArgumentException> { знания.setRole(проект, обстановка, "главный", автор) }
    }

    @Test
    fun `прогон разбора — факты несут его код, переразбор новой версией промпта устаревает отсутствующее без дублей`() {
        val м = знания.putMaterial(проект, "Записка миссии", "mission_memo", записка, автор, rank = Authority.MANDATORY, role = "charter")
        val первый = знания.putFacts(проект, м, пакетЗаписки(м), автор, promptVersion = "v1")
        assertEquals(3, первый.accepted.size, первый.refused.toString())
        val прогоны = store.list(область, "parse_run")
        assertEquals(1, прогоны.size)
        assertEquals("done", прогоны.single().status)
        assertEquals(3, прогоны.single().doc.path("facts").size())
        assertEquals("v1", прогоны.single().doc.path("prompt_version").asText())
        assertTrue(store.list(область, "fact").all { it.doc.path("parse_run").asText() == прогоны.single().code })
        val карточка = store.byCode(область, м)!!.doc
        assertEquals(прогоны.single().code, карточка.path("parse_run").asText())
        assertTrue(карточка.path("summary").asText().startsWith("Записка:"), "резюме легло в карточку")

        // Переразбор: тот же заказчик, оператора нет, новая рамка.
        val я = якоря(м)
        val второй = знания.putFacts(
            проект, м,
            """{"facts":[${факт("relation", "Минтранс России", "является заказчиком", "якорный заказчик", я[1])},
                ${факт("framing", "система", "строится для", "коротких сообщений и телеметрии", я[0])}]}""",
            автор, promptVersion = "v2",
        )
        assertEquals(1, второй.accepted.size, "повтор не удваивается, новое принимается")
        assertEquals(1, второй.repeated.size)
        assertTrue("устарело из прежних разборов 2" in второй.note, второй.note)
        val факты = знания.facts(проект)
        assertEquals(4, факты.size)
        assertEquals(2, факты.count { it.disposition == Disposition.SUPERSEDED })
        assertEquals(Disposition.FREE, факты.first { it.predicate == "является заказчиком" }.disposition)
        assertEquals(2, store.list(область, "parse_run").size)

        // Пакет без версии промпта (рука, тест) прежнее не трогает.
        val третий = знания.putFacts(проект, м, """{"facts":[${факт("event", "проект", "старт", "2027", я[0])}]}""", автор)
        assertEquals(1, третий.accepted.size)
        assertEquals(2, знания.facts(проект).count { it.disposition == Disposition.SUPERSEDED }, "без версии промпта ничего не устаревает")
    }

    @Test
    fun `досье и откат Приморья — стороны ушли, постановка из записки цела, общая нужда осталась с пометой`() {
        val записка = знания.putMaterial(проект, "Записка миссии", "mission_memo", записка, автор, rank = Authority.MANDATORY, role = "charter")
        val итог = знания.putFacts(проект, записка, пакетЗаписки(записка), автор, promptVersion = "чтение-v1")
        assertNotNull(итог.task, итог.refused.toString())
        val принято = знания.accept(проект, итог.task!!, listOf(0, 1, 2), автор)
        assertEquals(3, принято.codes.size, принято.notes.toString())

        val приморье = знания.putMaterial(проект, "Исследование Приморья", "analysis", приморье, автор, rank = Authority.REFERENCE, role = "context")
        val итогП = знания.putFacts(проект, приморье, пакетПриморья(приморье), автор, promptVersion = "чтение-v1")
        assertEquals(3, итогП.accepted.size, итогП.refused.toString())
        val принятоП = знания.accept(проект, итогП.task!!, listOf(0, 1), автор)
        assertEquals(2, принятоП.codes.size, принятоП.notes.toString())
        val нужда = store.list(область, "need").single()
        assertTrue(принятоП.notes.any { "та же нужда" in it }, "общая нужда узнана: ${принятоП.notes}")

        // Досье Приморья: резюме, вклад по видам, сущности — сторона только на нём, нужда — не только.
        val досье = знания.dossier(проект, приморье)
        assertEquals("context", досье.role)
        assertTrue(досье.summary!!.startsWith("Обстановка:"))
        assertEquals(mapOf("capability" to 1, "external_target" to 1, "framing" to 1), досье.factsByKind)
        assertEquals(2, досье.byDisposition["adopted"])
        assertEquals(1, досье.runs.size)
        val сторона = досье.entities.first { it.kind == "stakeholder" }
        assertTrue(сторона.onlyBasis, "сторона Приморья стоит только на нём")
        assertFalse(досье.entities.first { it.kind == "need" }.onlyBasis, "нужда стоит и на записке")
        assertTrue(досье.unique >= 1, "чужая цель — уникальный факт: ${досье.unique}")
        assertTrue(досье.usefulness >= 3)

        // Взгляд «сущность → на чём стоит»: у нужды два основания из двух документов с ролями.
        val основания = знания.basis(проект, нужда.code)
        assertEquals(setOf("charter", "context"), основания.facts.map { it.role }.toSet())
        assertTrue(основания.facts.all { it.anchor != null && it.quote != null })

        // Откат вклада Приморья.
        val откат = знания.rollback(проект, приморье, автор, "исследование не о нашей миссии")
        assertEquals(3, откат.facts)
        assertEquals(listOf(сторона.code), откат.entitiesCancelled)
        assertEquals(listOf(нужда.code), откат.entitiesKept)
        assertEquals(1, откат.runs)
        assertEquals("cancelled", store.byCode(область, сторона.code)!!.status)
        val нуждаПосле = store.byCode(область, нужда.code)!!
        assertEquals(нужда.status, нуждаПосле.status, "нужда с другим основанием не снимается")
        assertTrue("основание снято" in нуждаПосле.doc.path("notes").asText(), нуждаПосле.doc.path("notes").asText())
        // Постановка из записки цела: стороны записки живы, без помет; факты записки на месте.
        store.list(область, "stakeholder").filter { it.code != сторона.code }.forEach { с ->
            assertNotEquals("cancelled", с.status, с.code)
            assertFalse("снят" in с.doc.path("notes").asText(), с.code)
        }
        assertEquals(3, знания.facts(проект).size, "живые факты — только записки")
        assertTrue(знания.facts(проект).all { it.material == записка })
        assertEquals("rolled_back", store.list(область, "parse_run").first { it.doc.path("material").asText() == приморье }.status)
        assertEquals(1, знания.basis(проект, нужда.code).facts.size, "снятое основание нужде больше не приписывается")
        assertEquals(3, знания.dossier(проект, записка).entities.size, "досье записки: три сущности стоят на ней")

        // Повторный разбор Приморья после отката — факты заводятся заново, не считаются повтором.
        val снова = знания.putFacts(проект, приморье, пакетПриморья(приморье), автор, promptVersion = "чтение-v2")
        assertEquals(3, снова.accepted.size, "после отката документ разбирается заново")
        assertEquals(0, снова.repeated.size)
    }

    @Test
    fun `приём документа режимом — в контекст без сущностей, по разделам, отклонение с причиной`() {
        val м = знания.putMaterial(проект, "Записка миссии", "mission_memo", записка, автор, rank = Authority.MANDATORY, role = "charter")
        знания.putFacts(проект, м, пакетЗаписки(м), автор, promptVersion = "v1")

        val контекст = знания.acceptDocument(проект, м, "context_only", автор)
        assertEquals(3, контекст.facts)
        assertTrue(знания.facts(проект).all { it.disposition == Disposition.NOTED })
        assertTrue(store.list(область, "stakeholder").isEmpty(), "сущностей нет")
        assertEquals("context_only", store.byCode(область, м)!!.doc.path("accept_mode").asText())

        assertFailsWith<IllegalArgumentException> { знания.acceptDocument(проект, м, "rejected", автор) }
        assertFailsWith<IllegalArgumentException> { знания.acceptDocument(проект, м, "как-нибудь", автор) }

        // По разделам: раздел s2 — только нужда.
        val раздел = знания.canon(проект, м).first { "не хватает" in it.text }.anchor.substringBefore('#')
        val поРазделам = знания.acceptDocument(проект, м, "sections", автор, sections = listOf(раздел))
        assertEquals(1, поРазделам.facts)
        assertEquals(1, store.list(область, "need").size)
        assertTrue(store.list(область, "stakeholder").isEmpty(), "стороны из другого раздела не заведены")

        val целиком = знания.acceptDocument(проект, м, "whole", автор)
        assertEquals(3, целиком.facts)
        assertEquals(2, store.list(область, "stakeholder").size)
        assertEquals("whole", store.byCode(область, м)!!.doc.path("accept_mode").asText())

        val отклонён = знания.acceptDocument(проект, м, "rejected", автор, reason = "не наш профиль")
        assertEquals(0, отклонён.facts, "принятые человеком факты пакетом не переписываются")
        assertEquals("rejected", store.byCode(область, м)!!.doc.path("accept_mode").asText())
    }

    @Test
    fun `библиотека проекта — норматив берётся в другой проект с фактами и связями без вызова модели, устав — нет`() {
        val другой = "PJ-8031"
        store.create(другой, "project", Area.Project(другой), "1", mapper.createObjectNode().put("name", "Второй"), Provenance(Channel.MANUAL, автор))
        val норматив = знания.putMaterial(проект, "ПП РФ № 2216", "normative", "# Требования\n\nОператор обязан хранить данные 3 года.\nОператор обязан передавать телеметрию.", автор, rank = Authority.MANDATORY, role = "regulatory")
        val я = якоря(норматив)
        val итог = знания.putFacts(
            проект, норматив,
            """{"topics":[{"label":"хранение данных"}],"facts":[
                {"kind":"obligation","topic":"хранение данных","subject":"оператор","predicate":"обязан хранить данные","value":"3","unit":"года","quote":"хранить данные 3 года","source":{"anchor":"${я[0]}"},"mark":"И"},
                {"kind":"obligation","subject":"оператор","predicate":"обязан передавать","value":"телеметрию","quote":"передавать телеметрию","source":{"anchor":"${я[1]}"},"mark":"И"}
            ],"links":[{"from":0,"to":1,"type":"refines","rationale":"одно требование уточняет другое"}]}""",
            автор, promptVersion = "v1",
        )
        assertEquals(2, итог.accepted.size, итог.refused.toString())
        знания.dispose(проект, итог.accepted[0].code, Disposition.ADOPTED, "", автор)

        val взятие = знания.takeMaterial(проект, норматив, другой, автор)
        assertEquals(0, взятие.modelCalls)
        assertEquals(2, взятие.facts)
        assertEquals(1, взятие.links)
        assertEquals(1, взятие.topics)
        val факты = знания.facts(другой)
        assertEquals(2, факты.size)
        assertTrue(факты.all { it.material == взятие.material && it.disposition == Disposition.FREE }, "решения прежнего проекта не переезжают")
        assertEquals(1, факты.first { it.predicate == "обязан хранить данные" }.links.size)
        assertEquals("хранение данных", store.list(Area.Project(другой), "topic").single().doc.path("label").asText())
        val досье = знания.dossier(другой, взятие.material)
        assertEquals("regulatory", досье.role)
        assertEquals(1, досье.runs.size)
        assertTrue("вызова модели не было" in досье.runs.single().promptVersion)
        assertTrue("уже взят" in знания.takeMaterial(проект, норматив, другой, автор).note)

        val устав = знания.putMaterial(проект, "Записка", "mission_memo", записка, автор, rank = Authority.MANDATORY, role = "charter")
        val отказ = assertFailsWith<IllegalArgumentException> { знания.takeMaterial(проект, устав, другой, автор) }
        assertTrue("устав" in отказ.message!!, отказ.message)
    }

    @Test
    fun `карта пробелов устава — на записке владельца пункты найдены, на огрызке названы пропуски и запертые сцены`() {
        val корень = File(System.getenv("ORBITA_REPO_ROOT") ?: ".")
        val файл = корень.resolve("docs/tz/v2/поставка-09-15/ЗАПИСКА-МИССИИ-IoT.md")
        assertTrue(файл.isFile, "записки нет по пути ${файл.path}")
        val записка = знания.putMaterial(проект, "Записка миссии IoT", "mission_memo", файл.readText(), автор, rank = Authority.MANDATORY, role = "charter")
        val карта = знания.charterGaps(проект, записка)
        assertEquals(12, карта.items.size)
        assertTrue(карта.present >= 10, "записка владельца закрывает ≥ 10 пунктов: ${карта.missing}")
        assertTrue(карта.items.filter { it.present }.all { it.anchor != null }, "у найденного пункта есть якорь")
        assertTrue(карта.items.first { it.n == 6 }.present, "цели с годом найдены")

        val огрызок = знания.putMaterial(проект, "Огрызок", "mission_memo", "# Заметка\n\nПоговорили о связи.", автор, rank = Authority.REFERENCE, role = "context")
        val пробелы = знания.charterGaps(проект, огрызок)
        assertTrue(пробелы.missing.size >= 10, пробелы.missing.toString())
        assertTrue("2" in пробелы.blockedScenes && "4" in пробелы.blockedScenes, пробелы.blockedScenes.toString())
        assertTrue("не найдено пунктов" in пробелы.note)
    }
}
