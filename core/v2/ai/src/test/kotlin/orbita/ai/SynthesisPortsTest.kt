// Договор типов синтеза: что предложением НЕ является.
//
// Проверяется не «конструктор собирается», а то, чего служба не вправе выдать
// за предложение: «похоже» без «чем именно», предложение без факта-основания,
// противоречие без рангов обоих сторон, запуск без отпечатка среза. Это те
// самые случаи, которые модель выдаёт охотнее всего, а экран показать не
// может — поэтому брак ловится конструктором, а не отзывом с приёмки.
package orbita.ai

import orbita.ai.api.Basis
import orbita.ai.api.Diff
import orbita.ai.api.FormationProposal
import orbita.ai.api.SynthesisRun
import orbita.ai.api.Verdict
import orbita.kernel.schema.GeneratedKinds
import orbita.knowledge.api.Authority
import orbita.knowledge.api.FactSource
import orbita.knowledge.api.SourceMark
import orbita.knowledge.schema.GeneratedOntology
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SynthesisPortsTest {

    private val изЗаписки = Basis(
        factId = "F-0007",
        material = "M-0001",
        anchor = "блок-12",
        authority = Authority.MANDATORY,
        mark = SourceMark.И,
    )

    private val отЭксперта = Basis(
        factId = "F-0042",
        authority = Authority.EXPERT,
        mark = SourceMark.И,
        source = FactSource.FromExpert(account = "ivanov", role = "ведущий СИ", at = "2026-09-12"),
    )

    private fun предложение(
        verdict: Verdict = Verdict.NEW,
        basis: List<Basis> = listOf(изЗаписки),
        targetRef: String? = null,
        diffField: String = "",
        rankHint: String = "",
        concept: String = "need",
        confidence: Double? = null,
    ) = FormationProposal(
        concept = concept,
        payload = mapOf("statement" to "связь в Арктике без наземной инфраструктуры"),
        basis = basis,
        verdict = verdict,
        sourceMark = SourceMark.И,
        targetRef = targetRef,
        diffField = diffField,
        confidence = confidence,
        rankHint = rankHint,
    )

    @Test
    fun `предложение нового понятия несёт основание и мишени не требует`() {
        val п = предложение()
        assertEquals(Verdict.NEW, п.verdict)
        assertEquals("new", п.verdict.code)
        assertEquals(1, п.basis.size)
        assertTrue(п.targetRef == null, "у нового понятия мишени нет")
    }

    @Test
    fun `основание эксперта называет учётку, роль и дату вместо якоря`() {
        val п = предложение(basis = listOf(отЭксперта))
        val источник = п.basis.single().source
        assertTrue(источник is FactSource.FromExpert, "экспертное основание — ветка эксперта, не документа")
        assertEquals("ведущий СИ", (источник as FactSource.FromExpert).role)
        assertTrue(п.basis.single().anchor == null, "у руки документа нет — якоря быть не может")
    }

    @Test
    fun `вердикты названы теми же словами, что в истине онтологии`() {
        // Вторая копия перечня вердиктов разошлась бы с правилами сверки молча:
        // экран показал бы группу, которой в правилах нет.
        Verdict.entries.forEach { вердикт ->
            assertTrue(
                GeneratedOntology.reconciliation.containsKey(вердикт.code),
                "вердикт «${вердикт.code}» не описан в ОНТОЛОГИЯ-ФОРМИРОВАНИЯ",
            )
        }
    }

    // ——— отказные ———

    @Test
    fun `предложение без факта-основания не создаётся`() {
        val беда = assertFailsWith<IllegalArgumentException> { предложение(basis = emptyList()) }
        assertTrue("брак предложения" in (беда.message ?: ""), беда.message ?: "")
    }

    @Test
    fun `вердикт о принятом без названного поля отличия — брак`() {
        listOf(Verdict.AUGMENT, Verdict.CONTRADICT, Verdict.CONFIRM).forEach { вердикт ->
            val беда = assertFailsWith<IllegalArgumentException>("вердикт «${вердикт.code}» прошёл без отличия") {
                предложение(verdict = вердикт, targetRef = "N-0003", diffField = "", rankHint = "обязательный против справочного")
            }
            assertTrue("поле отличия" in (беда.message ?: ""), беда.message ?: "")
        }
    }

    @Test
    fun `вердикт о принятом без мишени — брак`() {
        val беда = assertFailsWith<IllegalArgumentException> {
            предложение(verdict = Verdict.AUGMENT, targetRef = null, diffField = "qos_class")
        }
        assertTrue("о каком именно понятии" in (беда.message ?: ""), беда.message ?: "")
    }

    @Test
    fun `противоречие без рангов обоих оснований — брак`() {
        val беда = assertFailsWith<IllegalArgumentException> {
            предложение(verdict = Verdict.CONTRADICT, targetRef = "GL-0001", diffField = "year", rankHint = "")
        }
        assertTrue("ранги" in (беда.message ?: ""), беда.message ?: "")
    }

    @Test
    fun `противоречие показывает ранги обоих, а победителя не называет`() {
        val п = предложение(
            verdict = Verdict.CONTRADICT,
            targetRef = "GL-0001",
            diffField = "year",
            rankHint = "${Authority.word(Authority.MANDATORY)} против ${Authority.word(Authority.REFERENCE)}",
        )
        assertTrue(Authority.word(Authority.MANDATORY) in п.rankHint, п.rankHint)
        assertTrue(Authority.word(Authority.REFERENCE) in п.rankHint, п.rankHint)
        // Победителя нет и быть не может: ранг подсказывает, решает человек.
        assertTrue(
            п.payload.keys.none { it.contains("winner") || it.contains("победит") },
            "в предложении появилось поле победителя: ${п.payload.keys}",
        )
    }

    @Test
    fun `понятие вне онтологии не образуется`() {
        val беда = assertFailsWith<IllegalStateException> { предложение(concept = "веха") }
        assertTrue("ОНТОЛОГИЯ-ФОРМИРОВАНИЯ" in (беда.message ?: ""), беда.message ?: "")
        assertTrue(GeneratedOntology.byCode.containsKey("need"), "понятие нужды в онтологии обязано быть")
    }

    @Test
    fun `уверенность вне единичного отрезка отвергается`() {
        assertFailsWith<IllegalArgumentException> { предложение(confidence = 1.4) }
        assertFailsWith<IllegalArgumentException> { предложение(confidence = -0.1) }
    }

    @Test
    fun `основание без кода факта и с неизвестным рангом отвергается`() {
        assertFailsWith<IllegalArgumentException> { Basis(factId = " ") }
        val беда = assertFailsWith<IllegalArgumentException> { Basis(factId = "F-0001", authority = "важный") }
        assertTrue("ранг основания" in (беда.message ?: ""), беда.message ?: "")
    }

    // ——— диф четырьмя группами ———

    @Test
    fun `диф раскладывает предложения по вердиктам`() {
        val противоречие = предложение(
            verdict = Verdict.CONTRADICT,
            targetRef = "GL-0001",
            diffField = "year",
            rankHint = "обязательный против справочного",
        )
        val диф = Diff.of(listOf(предложение(), противоречие))
        assertEquals(2, диф.size)
        assertEquals(1, диф.counts()["new"])
        assertEquals(1, диф.counts()["contradict"])
        assertEquals(0, диф.counts()["augment"])
        assertEquals(2, диф.all().size)
    }

    @Test
    fun `предложение в чужой группе дифа отвергается`() {
        val противоречие = предложение(
            verdict = Verdict.CONTRADICT,
            targetRef = "GL-0001",
            diffField = "year",
            rankHint = "обязательный против справочного",
        )
        val беда = assertFailsWith<IllegalStateException> { Diff(new = listOf(противоречие)) }
        assertTrue("группе" in (беда.message ?: ""), беда.message ?: "")
    }

    // ——— запуск синтеза ———

    private fun запуск(
        status: String = "done",
        sliceFingerprint: String = "aa11bb22",
        ontologyVersion: String = GeneratedOntology.ontologyVersion,
        sliceSize: Int = 50,
        error: String? = null,
    ) = SynthesisRun(
        id = "SR-0001",
        trigger = "manual",
        sliceFingerprint = sliceFingerprint,
        sliceSize = sliceSize,
        ontologyVersion = ontologyVersion,
        status = status,
        note = "срез урезан: 50 из 137",
        error = error,
    )

    @Test
    fun `запуск помечен версией правил образования и размером среза`() {
        val з = запуск()
        assertEquals(GeneratedOntology.ontologyVersion, з.ontologyVersion)
        assertEquals(50, з.sliceSize)
        assertTrue("срез урезан" in (з.note ?: ""), з.note ?: "")
        assertTrue(з.cached.not(), "живой запуск не помечается кэшем")
    }

    @Test
    fun `статусы запуска берутся из истины схем`() {
        assertEquals(
            GeneratedKinds.of(SynthesisRun.KIND).statusModel?.split("|"),
            SynthesisRun.statuses,
            "статусная модель обязана читаться из вида, а не из второй копии в коде",
        )
    }

    @Test
    fun `запуск без отпечатка среза не создаётся`() {
        val беда = assertFailsWith<IllegalArgumentException> { запуск(sliceFingerprint = "") }
        assertTrue("отпечатка среза" in (беда.message ?: ""), беда.message ?: "")
    }

    @Test
    fun `запуск без версии правил образования не создаётся`() {
        assertFailsWith<IllegalArgumentException> { запуск(ontologyVersion = "") }
    }

    @Test
    fun `статус вне статусной модели вида отвергается`() {
        val беда = assertFailsWith<IllegalArgumentException> { запуск(status = "готово") }
        assertTrue("статусной модели" in (беда.message ?: ""), беда.message ?: "")
    }

    @Test
    fun `отказ запуска без названной причины не создаётся`() {
        val беда = assertFailsWith<IllegalArgumentException> { запуск(status = SynthesisRun.ERROR) }
        assertTrue("причины" in (беда.message ?: ""), беда.message ?: "")
        // С названной причиной отказ — законное состояние запуска.
        assertEquals(SynthesisRun.ERROR, запуск(status = SynthesisRun.ERROR, error = "канал недоступен").status)
    }

    @Test
    fun `размер среза отрицательным не бывает`() {
        assertFailsWith<IllegalArgumentException> { запуск(sliceSize = -1) }
    }
}
