// Полки, живущие в ядре: запись полки — обычная сущность области library.
//
// Отдельного хранилища у полок нет намеренно: у них те же версии, тот же
// провенанс и та же история, что у всего остального. Разница только в
// области (library вместо project) и в том, что born_in у них пуст —
// у сущности полки нет сцены рождения.
package orbita.library.internal

import com.fasterxml.jackson.databind.JsonNode
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.EntityStore
import orbita.kernel.api.Provenance
import orbita.library.api.ShelfItem
import orbita.library.api.Shelves

class EntityShelves(
    private val store: EntityStore,
    /** Запасной источник шаблонов: файлы поставки, пока полка не загружена. */
    private val изФайла: (String) -> JsonNode? = { null },
) : Shelves {

    override fun of(kind: String): List<ShelfItem> =
        store.list(Area.Library, kind).map { ShelfItem(it.kind, it.code, it.doc) }

    override fun item(code: String): ShelfItem? =
        store.byCode(Area.Library, code)?.let { ShelfItem(it.kind, it.code, it.doc) }

    override fun phaseTemplate(code: String): JsonNode =
        item(code)?.doc
            ?: изФайла(code)
            ?: error(
                "шаблона фазы «$code» нет ни на полке, ни в поставке — " +
                    "загрузите полки: python3 tools/v2/load_shelves.py",
            )

    /**
     * Положить запись поставки на полку. Повторная загрузка того же
     * содержимого ничего не меняет: полка идемпотентна, иначе каждая
     * выкладка плодила бы версии на пустом месте.
     */
    fun put(kind: String, code: String, doc: JsonNode, author: String): ShelfItem {
        val прежняя = store.byCode(Area.Library, code)
        val провенанс = Provenance(Channel.SHELF, author, source = "поставка v2")
        val сущность = when {
            прежняя == null -> store.create(code, kind, Area.Library, null, doc, провенанс)
            прежняя.doc == doc -> прежняя
            else -> store.update(прежняя.id, doc, провенанс)
        }
        return ShelfItem(сущность.kind, сущность.code, сущность.doc)
    }
}
