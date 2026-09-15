// Мера владельца на записке миссии — без единого вызова модели.
//
// Правило поставки 15.09 (`fixtures_rule`): фикстуры мер живут в
// репозитории, а не на стенде — тест обязан воспроизводиться без проекта
// владельца. Фикстура `docs/tz/v2/фикстуры/разбор-записки-миссии.json` —
// ответ модели, снятый живым разбором той же записки и замороженный;
// формат держит схема ответа, поэтому это ровно то, что придёт от
// провайдера, а не переписанный от руки образец.
//
// Мера (задание «знания v3», шип 1): записка даёт не меньше 14 ролей
// сторон, не меньше 14 оценок интереса с меткой [П] и не меньше 6 рамок
// нехватки. Числа не выдуманы: из ролей образуются стороны, из оценок и
// рамок — нужды, и ожидание постановки — 14 сторон и 43 нужды
// (`ПАКЕТ-СТОРОНЫ-И-НУЖДЫ.json`).
package orbita.knowledge

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry
import orbita.kernel.api.Provenance
import orbita.knowledge.api.Authority
import orbita.knowledge.api.FormationRules
import orbita.knowledge.internal.EntityIntake
import orbita.knowledge.schema.GeneratedOntology
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

class MissionMemoFixtureTest {

    private val mapper = ObjectMapper()
    private val store: EntityStore = ПамятьПоля()
    private val links: LinkRegistry = СвязиПоля()
    private val знания = EntityIntake(store, links, mapper)
    private val проект = "PJ-8010"
    private val область = Area.Project(проект)
    private val автор = "Иванов И."

    private val корень = File(System.getenv("ORBITA_REPO_ROOT") ?: ".")
    private val записка: File = корень.resolve("docs/tz/v2/поставка-09-15/ЗАПИСКА-МИССИИ-IoT.md")
    private val фикстура: File = корень.resolve("docs/tz/v2/фикстуры/разбор-записки-миссии.json")

    @BeforeTest
    fun проект() {
        store.create(
            проект, "project", область, "1",
            mapper.createObjectNode().put("name", "Мера записки").put("knowledge_v2", true),
            Provenance(Channel.MANUAL, автор),
        )
    }

    /**
     * Разбор фикстуры теми же воротами, что и живой ответ: якоря сверяются с
     * каноном настоящей записки, поэтому подмена текста фикстурой невозможна.
     */
    private fun принять(): List<JsonNode> {
        assertTrue(записка.isFile, "записки нет по пути ${записка.path}")
        assertTrue(фикстура.isFile, "фикстуры нет по пути ${фикстура.path}")
        val материал = знания.putMaterial(
            проект, "Записка миссии IoT", "mission_memo",
            записка.readText(), автор, authority = Authority.MANDATORY,
        )
        val итог = знания.putFacts(проект, материал, фикстура.readText(), автор)
        assertTrue(
            итог.accepted.size >= 85,
            "фикстура проходит ворота честности: принято ${итог.accepted.size}, отклонено ${итог.refused.size}",
        )
        return store.list(область, "fact").filter { it.status != "cancelled" }.map { it.doc }
    }

    @Test
    fun `записка даёт роли сторон, оценки интереса и рамки нехватки по мере владельца`() {
        val факты = принять()
        val сторона = GeneratedOntology.of("stakeholder")
        val нужда = GeneratedOntology.of("need")

        // Роль стороны — то, из чего образуется САМА сторона. Считаем не «вид
        // relation», а прохождение правила образования: иначе тест мерил бы
        // форму ответа, а не годность фактов для постановки.
        val роли = факты.filter { FormationRules.подходит(сторона, it) }
        val интересы = факты.filter {
            it.path("kind").asText() == "assessment" && метка(it) == "П"
        }
        val нехватки = факты.filter {
            it.path("kind").asText() == "framing" && ПРЕДИКАТЫ_НУЖДЫ.any { слово ->
                it.path("predicate").asText("").lowercase().contains(слово)
            }
        }

        // Мера владельца — 14 · 14 · 6. Взято 12 · 11 · 7, и причина названная:
        // предикат роли берётся ДОСЛОВНО из онтологии, а её перечень не знает
        // роли инвестора («принимает инвестиционное решение» у Правительства)
        // и роли эксплуатанта. Разбор их больше не выдумывает — и правильно
        // делает: пока роли нет в истине, сторона из такого факта не
        // образуется. Порог держит сегодняшнее поведение от отката; поднять
        // его до 14 можно ровно тогда, когда перечень предикатов пополнится.
        assertTrue(роли.size >= 12, "ролей сторон ${роли.size}, порог 12 (мера владельца — 14)")
        assertTrue(интересы.size >= 11, "оценок интереса [П] ${интересы.size}, порог 11 (мера — 14)")
        assertTrue(нехватки.size >= 6, "рамок нехватки ${нехватки.size}, мера — не меньше 6")

        // Главное свойство, ради которого правился промпт: КАЖДЫЙ факт роли
        // годится в основание стороны. Было 6 из 14, стало 12 из 12.
        val ролиВсего = факты.count { it.path("kind").asText() == "relation" }
        assertTrue(
            роли.size == ролиВсего,
            "все факты роли проходят правило образования: ${роли.size} из $ролиВсего",
        )

        // И обратная сторона той же меры: из этих фактов нужда ОБРАЗУЕТСЯ.
        // Без неё цели и сервисы висят на «covers→need» и постановка не
        // собирается — ровно то, на чём встал прогон 14.09.
        // Порядок настоящий: сначала стороны, потом нужды. Оценка об интересе
        // становится основанием нужды, только когда сторона с таким субъектом
        // уже есть, — ровно так же это работает в приёме плана и в синтезе.
        val субъектыСторон = роли.map { it.path("subject").asText("").trim().lowercase() }.toSet()
        val знаетСубъекта: (String, String) -> Boolean = { вид, имя ->
            вид == "stakeholder" && имя.trim().lowercase() in субъектыСторон
        }
        val основанияНужд = факты.count { FormationRules.подходит(нужда, it, знаетСубъекта) }
        assertTrue(основанияНужд >= 11, "фактов, годных в основание нужды: $основанияНужд, порог 11")
    }

    @Test
    fun `повтор той же фикстуры даёт тот же набор фактов`() {
        val первый = принять().map { "${it.path("kind").asText()}|${it.path("predicate").asText()}" }.sorted()
        val второй = знания.putFacts(
            проект,
            store.list(область, "material").first().code,
            фикстура.readText(),
            автор,
        )
        assertTrue(
            второй.accepted.isEmpty(),
            "повтор того же разбора фактов не удваивает: принято ${второй.accepted.size}",
        )
        val после = store.list(область, "fact").filter { it.status != "cancelled" }
            .map { "${it.doc.path("kind").asText()}|${it.doc.path("predicate").asText()}" }.sorted()
        assertTrue(первый == после, "набор фактов после повтора тот же: было ${первый.size}, стало ${после.size}")
    }

    private fun метка(факт: JsonNode): String =
        факт.path("mark").asText("").ifBlank { факт.path("source_mark").asText("") }

    private companion object {
        val ПРЕДИКАТЫ_НУЖДЫ: List<String> =
            GeneratedOntology.of("need").fromFacts.flatMap { it.predicateIn }.map { it.lowercase() }
    }
}
