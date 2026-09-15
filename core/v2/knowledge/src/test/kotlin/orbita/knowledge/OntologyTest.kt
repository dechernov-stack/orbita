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

import com.fasterxml.jackson.databind.node.TextNode
import orbita.kernel.api.QosClass
import orbita.kernel.schema.GeneratedKinds
import orbita.kernel.schema.Layer
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
    fun `понятия постановки — от стороны до возможности`() {
        assertEquals(
            listOf(
                "stakeholder", "need", "goal", "service", "constraint",
                "assumption", "milestone", "normative_document", "opportunity",
            ),
            GeneratedOntology.concepts.map { it.code },
            "перечень понятий и их порядок идут из истины онтологии",
        )
    }

    @Test
    fun `понятие без ключа дубля отказывает поимённо, а не сверяет наугад`() {
        val сКлючом = GeneratedOntology.concepts.filter { it.identity != null }
        val безКлюча = GeneratedOntology.concepts.filter { it.identity == null }

        сКлючом.forEach { понятие ->
            assertTrue(
                понятие.identityOrFail.key.isNotEmpty(),
                "у понятия «${понятие.code}» нет ключа дубля — сверять нечем",
            )
            val порог = понятие.identityOrFail.threshold
            assertTrue(
                порог > 0 && порог <= 1,
                "у понятия «${понятие.code}» порог близости $порог вне (0, 1]",
            )
        }
        // Ворота 6 истины — «дубль по ключу идентичности». Понятие, которому
        // владелец ключа ещё не назвал, не сверяется наугад: отказ называет и
        // понятие, и файл истины, в котором ключа не хватает.
        безКлюча.forEach { понятие ->
            val беда = assertFailsWith<IllegalStateException>("«${понятие.code}» сверился без ключа") {
                понятие.identityOrFail
            }
            assertTrue(понятие.code in (беда.message ?: ""), беда.message ?: "")
            assertTrue("ОНТОЛОГИЯ-ФОРМИРОВАНИЯ.yaml" in (беда.message ?: ""), беда.message ?: "")
        }
    }

    @Test
    fun `нужда узнаётся по стороне и сути, норматив — только по обозначению`() {
        assertEquals(listOf("stakeholder", "statement_core"), нужда.identityOrFail.key)
        assertEquals(0.8, нужда.identityOrFail.threshold, 1e-9, "та же потребность иными словами — дубль")
        assertEquals(listOf("designation_natural"), норматив.identityOrFail.key)
        // Порог 1.0 — это «только точное совпадение обозначения»: два разных
        // норматива с похожими названиями сливать нельзя ни при какой близости.
        assertEquals(1.0, норматив.identityOrFail.threshold, 1e-9, "норматив по смыслу не сливается")
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

    /**
     * Решение владельца 12.09 («ЗНАНИЯ-V2-ПРИНЯТЫ» §2): у вехи появился свой
     * вид. Онтология называет его сама — `target_kind`; код соответствий не
     * держит, и вид, названный онтологией, обязан быть в истине схем.
     */
    @Test
    fun `веха называет свой вид, и вид этот в истине схем есть`() {
        val веха = GeneratedOntology.of("milestone")
        assertEquals("milestone", веха.targetKindCode, "онтология называет вид вехи: ${веха.targetKind}")
        val спец = GeneratedKinds.of("milestone")
        assertEquals(Layer.L1, спец.layer, "веха — слой L1 по истине схем")
        assertTrue(
            listOf("name", "kind", "source").all { it in спец.requiredFields },
            "у вехи обязательны имя, род и основание: ${спец.requiredFields}",
        )
        assertTrue(
            веха.targetKind.orEmpty().contains("gate"),
            "истина обязана сказать, что вехи технологий остаются точкой: ${веха.targetKind}",
        )
    }

    /**
     * Решение владельца 12.09 §1: класс обслуживания нужды обязателен, но
     * значение TBR допустимо до сцены сервисов. Онтология и схема обязаны
     * говорить одно — иначе сверка потребует того, чего схема не требует.
     */
    @Test
    fun `класс обслуживания нужды допускает TBR с владельцем и воротами`() {
        assertTrue(
            нужда.fields["qos_class"].orEmpty().contains("TBR"),
            "онтология обязана назвать TBR допустимым: ${нужда.fields["qos_class"]}",
        )
        assertTrue("qos_class" in GeneratedKinds.of("need").requiredFields, "поле обязательно по истине схем")
        assertEquals("6", QosClass.GATE, "ворота TBR — сцена, в которой заводятся сервисы")
        assertTrue(!QosClass.assigned(TextNode.valueOf("TBR")), "слово TBR классом не считается")
        assertTrue(QosClass.assigned(TextNode.valueOf("B′")), "ссылка на класс справочника — назначенный класс")
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
