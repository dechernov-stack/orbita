// Онтология формирования — вторая истина поля знаний.
//
// Схемы видов говорят, какие у сущности поля. Онтология говорит, как понятие
// образуется из фактов, чем оно узнаётся среди принятых, что считается
// противоречием и без какой связи его нельзя принять. Этими правилами
// пользуются обе стороны сразу — детерминированная сверка и синтез, — поэтому
// проверяется не «файл разобрался», а именно те величины, на которые они
// опираются: перечень понятий, ключ дубля, порог близости, must_link.
//
// Отдельно держится отпечаток: правка истины без перегенерации обязана ловиться
// здесь, а не всплывать предложением, сделанным по устаревшим правилам.
package orbita.knowledge

import orbita.knowledge.schema.GeneratedOntology
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OntologyTest {

    private val истина: File = File(System.getenv("ORBITA_REPO_ROOT") ?: ".")
        .resolve("docs/tz/v2/ОНТОЛОГИЯ-ФОРМИРОВАНИЯ.yaml")

    private val нужда = GeneratedOntology.of("need")
    private val норматив = GeneratedOntology.of("normative_document")

    @Test
    fun `восемь понятий постановки — от стороны до норматива`() {
        assertEquals(
            listOf(
                "stakeholder", "need", "goal", "service",
                "constraint", "assumption", "milestone", "normative_document",
            ),
            GeneratedOntology.concepts.map { it.code },
            "перечень понятий и их порядок идут из истины онтологии",
        )
    }

    @Test
    fun `у каждого понятия есть ключ дубля и порог близости`() {
        GeneratedOntology.concepts.forEach { понятие ->
            assertTrue(
                понятие.identity.key.isNotEmpty(),
                "у понятия «${понятие.code}» не назван ключ дубля — узнавать повтор нечем",
            )
            assertTrue(
                понятие.identity.threshold > 0 && понятие.identity.threshold <= 1,
                "у понятия «${понятие.code}» порог близости ${понятие.identity.threshold} вне (0, 1]",
            )
            assertTrue(
                понятие.fromFacts.isNotEmpty(),
                "понятие «${понятие.code}» не сказано, из каких фактов образуется",
            )
        }
    }

    @Test
    fun `нужда узнаётся по стороне и сути, норматив — только по обозначению`() {
        assertEquals(listOf("stakeholder", "statement_core"), нужда.identity.key)
        assertEquals(0.8, нужда.identity.threshold, 1e-9, "та же потребность иными словами — дубль")
        assertEquals(listOf("designation_natural"), норматив.identity.key)
        // Порог 1.0 — это «только точное совпадение обозначения»: два разных
        // норматива с похожими названиями сливать нельзя ни при какой близости.
        assertEquals(1.0, норматив.identity.threshold, 1e-9, "норматив по смыслу не сливается")
    }

    @Test
    fun `нужда без стороны не принимается — must_link ведёт на сторону`() {
        assertEquals(listOf("owns→stakeholder"), нужда.mustLink)
        assertEquals(listOf("covers→need>=1"), GeneratedOntology.of("goal").mustLink)
        assertTrue(
            "covers→need>=1" in GeneratedOntology.of("service").mustLink,
            "сервис без нужды — предложение с пометой, а не сущность",
        )
    }

    @Test
    fun `противоречие названо полями — у цели это значение и год`() {
        assertEquals(listOf("measure", "year"), GeneratedOntology.of("goal").conflictOn)
        assertEquals(listOf("edition"), норматив.conflictOn)
        // У стороны противоречит роль, а не имя: одна и та же организация в
        // двух документах названа по-разному — это дубль, не конфликт.
        assertEquals(listOf("role"), GeneratedOntology.of("stakeholder").conflictOn)
    }

    @Test
    fun `ранги доверия — четыре ступени, обязательный первый`() {
        assertEquals(
            listOf("mandatory", "expert", "reference", "doubtful"),
            GeneratedOntology.authorityRanks,
            "порядок — по убыванию веса, им подсказывается сторона в противоречии",
        )
        assertEquals(
            GeneratedOntology.authorityRanks.toSet(),
            GeneratedOntology.authorityNotes.keys,
            "ранг без пояснения словами на экран не выйдет",
        )
    }

    @Test
    fun `инварианты названы словами — принятое синтезом молча не меняется`() {
        assertTrue(
            GeneratedOntology.invariants.any { it.contains("не меняется синтезом молча") },
            "инварианты онтологии не перенесены в код: ${GeneratedOntology.invariants}",
        )
        assertTrue(
            GeneratedOntology.reconciliation.containsKey("contradict"),
            "вердикты сверки берутся из истины, а не выдумываются маршрутом",
        )
    }

    // --- отказы ---

    @Test
    fun `понятие вне онтологии не образуется`() {
        val отказ = assertFailsWith<IllegalStateException> { GeneratedOntology.of("рабочий_пакет") }
        assertTrue(
            отказ.message.orEmpty().contains("вне онтологии не образуется"),
            "отказ обязан назвать причину, а не вернуть пустое понятие: ${отказ.message}",
        )
    }

    @Test
    fun `правка истины без перегенерации ловится отпечатком`() {
        assertTrue(истина.isFile, "истины онтологии нет по пути ${истина.path}")
        val отпечаток = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(истина.readBytes()),
        )
        assertEquals(
            отпечаток,
            GeneratedOntology.ontologyVersion,
            "истина онтологии правлена, а код не перегенерирован — " +
                "запустите python3 tools/v2/gen_ontology.py (сторож ci/checks.sh говорит то же)",
        )
    }
}
