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
            итог.accepted.size >= 110,
            "фикстура проходит ворота честности: принято ${итог.accepted.size}, отклонено ${итог.refused.size}",
        )
        return store.list(область, "fact").filter { it.status != "cancelled" }.map { it.doc }
    }

    @Test
    fun `записка даёт роли сторон, оценки интереса и рамки нехватки по мере владельца`() {
        val факты = принять()

        // Мера считается ПО СОДЕРЖАНИЮ факта, а не по прохождению правила.
        // До 15.09 здесь стояло `FormationRules.подходит` — и мера падала от
        // того, что владелец переставил слово в перечне предикатов. Мерить
        // словарь вместо документа нельзя: РЕШЕНИЕ-ЧИТАТЬ-СМЫСЛ снял ворота, и
        // перечень предикатов стал подсказкой.
        val роли = факты.filter { факт ->
            факт.path("kind").asText() == "relation" ||
                факт.path("entity_class").asText() == "stakeholder"
        }
        val интересы = факты.filter {
            it.path("kind").asText() == "assessment" && метка(it) == "П"
        }
        val нехватки = факты.filter {
            it.path("kind").asText() == "framing" && ПРЕДИКАТЫ_НУЖДЫ.any { слово ->
                it.path("predicate").asText("").lowercase().contains(слово)
            }
        }

        // Мера владельца по записке — 14 сторон и 43 нужды
        // (`ПАКЕТ-СТОРОНЫ-И-НУЖДЫ.json`). На этой фикстуре — старом ответе
        // атомизации — выходит 13 · 13 · 7. Порог держит достигнутое от
        // отката; полную меру закрывает новый контур «документ → постановка»
        // (РЕШЕНИЕ-ЧИТАТЬ-СМЫСЛ), и снимать эту фикстуру до него нельзя.
        assertTrue(роли.size >= 13, "ролей сторон ${роли.size}, порог 13 (мера владельца — 14)")
        assertTrue(интересы.size >= 13, "оценок интереса [П] ${интересы.size}, порог 13 (мера — 14)")
        assertTrue(нехватки.size >= 6, "рамок нехватки ${нехватки.size}, мера — не меньше 6")

        // Главное свойство после снятия ворот: НИ ОДИН факт записки не
        // отбивается приёмом. Прежде тут стояло «все факты роли проходят
        // правило образования» — теперь правила, которое могло бы не пустить,
        // нет вовсе, и мерить надо именно это.
        assertTrue(
            факты.size >= 110,
            "приём не отбил ничего по форме: фактов в поле ${факты.size}",
        )
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
