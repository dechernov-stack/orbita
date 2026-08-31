// Словарь линта — ДАННЫМИ ПОЛКИ: список неопределённых слов растёт от
// прогона к прогону, и инженер вносит найденное с экрана, а не ждёт
// пересборки ядра.
//
// Полка перекрывает ресурс ЦЕЛИКОМ: пустой список означает «правило не
// проверяется». Тайно вернувшееся умолчание объясняло бы пометы, которых
// инженер не заводил, — а он должен видеть ровно то, что внёс.
package orbita.req

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QualityDictionaryTest {

    private val mapper = ObjectMapper()

    private fun требование(текст: String) = mapper.readTree(
        """{"id":"RQ-9002","level":"system","category":"functional","owner":"инженер",
            "statement":"$текст","verification_events":[],"traces_up":[{"ref":"ND-0001"}],
            "lifecycle":{"status":"Draft","version":"1"}}""",
    )

    @Test
    fun `слово, внесённое в словарь полки, начинает ловиться`() {
        val словарь = mapper.readTree(
            """{"id":"QD-9001","name":"Словарь","vague_words":["по мере готовности"],
                "modal_words":["должен","должна"],"conjunction_regexes":[],
                "goal_words":[],"negative_words":[],"passive_starts":[],
                "measured_categories":[]}""",
        )
        val контроль = QualityControl(QualityRules.fromShelf(словарь))
        val ноты = контроль.lint(требование("Система должна передавать телеметрию по мере готовности."))
        val нота = ноты.single { it.id == "L-C2" }
        assertTrue("по мере готовности" in нота.text) { нота.text }

        // умолчания сборки в этом словаре нет — и оно не подмешивается
        assertTrue(контроль.lint(требование("Система должна работать при необходимости.")).isEmpty()) {
            "«при необходимости» нет в словаре полки: помета взяться не может"
        }
    }

    @Test
    fun `пустой список означает «правило не проверяется», а не умолчание`() {
        val пустой = mapper.readTree(
            """{"id":"QD-9002","name":"Пустой","vague_words":[],"goal_words":[],
                "negative_words":[],"passive_starts":[],"conjunction_regexes":[],
                "modal_words":[],"measured_categories":[]}""",
        )
        val ноты = QualityControl(QualityRules.fromShelf(пустой))
            .lint(требование("Должно быть обеспечено резервирование при необходимости."))
        assertEquals(emptyList<LintNote>(), ноты) {
            "все списки пусты — линт молчит; тайного возврата умолчаний нет"
        }
    }

    @Test
    fun `без полки работает ресурс сборки — поведение прежнее`() {
        val ноты = QualityControl().lint(требование("Система должна работать при необходимости."))
        assertTrue(ноты.any { it.id == "L-C2" }) { "умолчание живо, пока полки нет" }
    }
}
