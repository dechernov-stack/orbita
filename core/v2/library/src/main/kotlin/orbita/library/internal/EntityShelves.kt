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
import orbita.library.api.ShelfState
import orbita.library.api.ShelfWrite
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

    override fun matching(kind: String, doc: JsonNode): ShelfItem? =
        поКлючу(kind, doc)?.let(::запись)

    override fun phaseTemplate(code: String): JsonNode =
        item(code)?.doc
            ?: изФайла(code)
            ?: error(
                "шаблона фазы «$code» нет ни на полке, ни в поставке — " +
                    "загрузите полки: python3 tools/v2/load_shelves.py",
            )

    /**
     * Естественный ключ вида: поле, по которому запись узнаётся не кодом,
     * а собой.
     *
     * Норматив — это акт с обозначением. Один и тот же ПП РФ приходит на
     * полку РАЗНЫМИ дорогами: поставкой из записки (`load_normatives.py`,
     * код из обозначения) и принятым фактом живого разбора (код `NR-000N`).
     * Без естественного ключа он ляжет дважды, и у ограничения окажется два
     * разных `normative_basis` на один и тот же акт.
     */
    private val естественныйКлюч = mapOf("normative_document" to "designation")

    /**
     * Обозначение как ИМЯ акта, а не как строка символов.
     *
     * Один и тот же акт цитируют по-разному: «№2216» и «№ 2216», с
     * редакцией в скобках и без неё. Редакция — это ПОЛЕ карточки
     * (`edition`), а не часть имени: иначе каждая новая редакция заводила
     * бы новый акт вместо новой версии старого. Поэтому в ключе снимаются
     * регистр, пробелы, точки и скобочные уточнения; цифры, номера и даты
     * остаются — на них акт и держится.
     */
    private fun имяАкта(значение: String): String =
        значение.replace(Regex("\\([^)]*\\)"), "").lowercase().replace(Regex("[\\s.,]+"), "")

    private fun поКлючу(kind: String, doc: JsonNode): orbita.kernel.api.Entity? {
        val поле = естественныйКлюч[kind] ?: return null
        val имя = doc.path(поле).asText("").let(::имяАкта).takeIf { it.isNotBlank() } ?: return null
        return store.list(Area.Library, kind).firstOrNull { имяАкта(it.doc.path(поле).asText()) == имя }
    }

    /**
     * Положить запись поставки на полку. Повторная загрузка того же
     * содержимого ничего не меняет: полка идемпотентна, иначе каждая
     * выкладка плодила бы версии на пустом месте.
     */
    override fun put(kind: String, code: String, doc: JsonNode, author: String): ShelfWrite {
        val прежняя = store.byCode(Area.Library, code) ?: поКлючу(kind, doc)
        val провенанс = Provenance(Channel.SHELF, author, source = "поставка v2")
        return when {
            прежняя == null -> ShelfWrite(
                запись(store.create(code, kind, Area.Library, null, doc, провенанс)), ShelfState.CREATED,
            )
            прежняя.doc == doc -> ShelfWrite(запись(прежняя), ShelfState.UNCHANGED)
            else -> ShelfWrite(запись(store.update(прежняя.id, doc, провенанс)), ShelfState.UPDATED)
        }
    }

    private fun запись(сущность: orbita.kernel.api.Entity) =
        ShelfItem(сущность.kind, сущность.code, сущность.doc)
}
