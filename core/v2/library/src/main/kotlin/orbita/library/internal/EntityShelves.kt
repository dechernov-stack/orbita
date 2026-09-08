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
    /**
     * Имя акта для сравнения: РОД акта + НОМЕР. «ПП РФ от 22.12.2020 № 2216»,
     * «ПП РФ № 2216» и «Постановление Правительства РФ от 22.12.2020 № 2216»
     * — один акт (поймано живым прогоном: разбор норматива дважды завёл
     * вторую карточку рядом с первой). Дата и полное/краткое имя органа
     * в ключ не входят; у акта без номера ключ — всё обозначение целиком.
     */
    private fun имяАкта(значение: String): String {
        val безСкобок = значение.replace(Regex("\\([^)]*\\)"), "").trim()
        val номер = Regex("[№N]\\s*([0-9][0-9A-Za-zА-Яа-я./-]*)").find(безСкобок)?.groupValues?.get(1)?.lowercase()
        if (номер == null) return безСкобок.lowercase().replace(Regex("[\\s.,]+"), "")
        val текст = безСкобок.lowercase()
        val род = when {
            Regex("постановлени|\\bпп\\b|пп рф|правительств").containsMatchIn(текст) -> "пп"
            Regex("приказ").containsMatchIn(текст) -> "приказ"
            Regex("федеральн\\w* закон|\\bфз\\b|-фз").containsMatchIn(текст) -> "фз"
            Regex("\\bгост\\b").containsMatchIn(текст) -> "гост"
            Regex("\\bпнст\\b").containsMatchIn(текст) -> "пнст"
            Regex("тр тс|техническ\\w* регламент").containsMatchIn(текст) -> "тртс"
            Regex("распоряжени").containsMatchIn(текст) -> "распоряжение"
            // Род не назван («№ 2216» из текста самого акта): ключ по номеру с
            // пометой «?», и поиск по нему — по номеру среди любых родов.
            else -> "?"
        }
        return "$род№$номер"
    }

    private fun поКлючу(kind: String, doc: JsonNode): orbita.kernel.api.Entity? {
        val поле = естественныйКлюч[kind] ?: return null
        val имя = doc.path(поле).asText("").let(::имяАкта).takeIf { it.isNotBlank() } ?: return null
        val карточки = store.list(Area.Library, kind)
        карточки.firstOrNull { имяАкта(it.doc.path(поле).asText()) == имя }?.let { return it }
        // Обозначение только номером: акт с тем же номером любого рода —
        // лучше одна карточка, чем двойник; род уточнит следующая поставка.
        if (имя.startsWith("?№")) {
            val номер = имя.removePrefix("?")
            return карточки.firstOrNull { имяАкта(it.doc.path(поле).asText()).endsWith(номер) }
        }
        return null
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
