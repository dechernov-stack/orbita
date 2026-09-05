// Порты библиотеки (ТЗ-BACKEND §3, слой L0).
//
// Полка — не витрина: с неё берут в проект, и взятое помнит исток. Здесь
// объявлено ровно то, чем пользуются другие модули; наполнение полок идёт
// генератором из поставки внешнего контура, руками — отказ сторожа.
package orbita.library.api

import com.fasterxml.jackson.databind.JsonNode

/** Запись полки: вид, код и документ поставки. */
data class ShelfItem(val kind: String, val code: String, val doc: JsonNode)

interface Shelves {
    /** Все записи полки заданного вида. */
    fun of(kind: String): List<ShelfItem>

    /** Одна запись по коду; null — записи нет, и это состояние, а не сбой. */
    fun item(code: String): ShelfItem?

    /**
     * Шаблон фазы по стандарту. Стандарт по умолчанию задаётся проектом:
     * решение владельца от 05.09 — NASA-7120.
     */
    fun phaseTemplate(code: String): JsonNode
}
