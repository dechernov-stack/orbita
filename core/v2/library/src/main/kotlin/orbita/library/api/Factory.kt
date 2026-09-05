// Фабрика библиотеки: единственная законная дверь к реализации.
//
// Без неё вызывающему пришлось бы импортировать orbita.library.internal —
// а чужой internal невидим по правилу ТЗ-BACKEND §2.1, и это проверяет
// архитектурный тест. Фабрика возвращает ПОРТ: подменить реализацию можно,
// не трогая вызывающих.
package orbita.library.api

import com.fasterxml.jackson.databind.JsonNode
import orbita.kernel.api.EntityStore
import orbita.library.internal.EntityShelves

object LibraryFactory {
    fun shelves(store: EntityStore, fromFile: (String) -> JsonNode? = { null }): Shelves =
        EntityShelves(store, fromFile)
}
