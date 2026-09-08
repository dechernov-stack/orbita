// Полка: один акт — одна карточка.
//
// Норматив приходит на полку двумя дорогами: поставкой из записки (код из
// обозначения) и принятым фактом живого разбора (код `NR-000N`). Если
// полка узнаёт запись только по коду, тот же ПП РФ ложится дважды, и у
// ограничения оказывается два разных основания на один и тот же акт.
// Здесь это закрыто тестом, а не аккуратностью загрузчика.
package orbita.library

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.KernelFactory
import orbita.library.api.LibraryFactory
import orbita.library.api.ShelfState
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ShelfTest {

    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val полки = LibraryFactory.shelves(store) { mapper.createObjectNode() }

    private fun норматив(обозначение: String, заголовок: String) =
        mapper.createObjectNode().put("designation", обозначение).put("title", заголовок)

    @BeforeTest
    fun чисто() = TestDbV2.очистить()

    @Test
    fun `повтор того же содержимого версий не плодит`() {
        val док = норматив("ПП РФ от 22.12.2020 № 2216", "Передача данных мониторинга")
        val первый = полки.put("normative_document", "ПП-2216", док, "поставка v2")
        assertEquals(ShelfState.CREATED, первый.state)

        val второй = полки.put("normative_document", "ПП-2216", док, "поставка v2")
        assertEquals(ShelfState.UNCHANGED, второй.state, "повтор ничего не переписал")
        assertEquals(1, store.history(store.byCode(Area.Library, "ПП-2216")!!.id).size,
            "версия на пустом месте не заведена")
    }

    @Test
    fun `акт без даты в обозначении — тот же акт, что с датой — номер важнее даты`() {
        полки.put("normative_document", "NR-0001", норматив("ПП РФ от 22.12.2020 № 2216", "Передача данных"), "поставка")
        // Так обозначение принёс разбор текста самого акта: без даты, с пробелом после №.
        val разбором = полки.put("normative_document", "NR-0005", норматив("ПП РФ № 2216", "Передача данных").put("edition", "с пунктами"), "разбор")
        assertEquals(ShelfState.UPDATED, разбором.state, "акт узнан по номеру, дата не мешает")
        assertEquals("NR-0001", разбором.item.code)
        assertEquals(1, полки.of("normative_document").size, "второй карточки не появилось")
        // Полное имя органа вместо аббревиатуры — тот же род и номер, тот же акт.
        val полным = полки.put("normative_document", "NR-0006",
            норматив("Постановление Правительства РФ от 22.12.2020 № 2216", "Передача данных"), "разбор текста акта")
        assertEquals(ShelfState.UPDATED, полным.state, "«Постановление Правительства РФ» = «ПП РФ»")
        assertEquals("NR-0001", полным.item.code)
        // Обозначение одним номером (так его читает разбор текста самого акта) — та же карточка.
        val номером = полки.put("normative_document", "NR-0008", норматив("№ 2216", "Передача данных"), "разбор текста акта")
        assertEquals(ShelfState.UPDATED, номером.state, "«№ 2216» ложится в карточку ПП № 2216, а не в двойник")
        assertEquals("NR-0001", номером.item.code)
        // А приказ с тем же номером — другой акт: род входит в ключ.
        val приказ = полки.put("normative_document", "NR-0007", норматив("Приказ Минтранса № 2216", "Иное"), "поставка")
        assertEquals(ShelfState.CREATED, приказ.state, "род акта различает ПП и приказ с одним номером")
        // А акт без номера сравнивается целиком — с датой.
        полки.put("normative_document", "NR-0002", норматив("Приказ Минтранса от 01.02.2012", "О порядке"), "поставка")
        val другой = полки.put("normative_document", "NR-0003", норматив("Приказ Минтранса от 01.03.2013", "О порядке"), "поставка")
        assertEquals(ShelfState.CREATED, другой.state, "другая дата без номера — другой акт")
    }

    @Test
    fun `акт с тем же обозначением ложится в свою карточку, а не в двойника`() {
        // Так его завёл живой разбор: код служебный, реквизитов почти нет.
        val разбором = полки.put(
            "normative_document", "NR-0001",
            норматив("ПП РФ от 22.12.2020 № 2216", "Передача данных мониторинга"),
            "разбор записки",
        )
        assertEquals(ShelfState.CREATED, разбором.state)

        // А так его несёт поставка: другой код, полные реквизиты.
        val поставкой = полки.put(
            "normative_document", "ПП-РФ-от-22122020-N-2216",
            норматив("ПП РФ от 22.12.2020 № 2216", "Передача данных мониторинга")
                .put("edition", "действует на 29.08.2026"),
            "поставка v2",
        )
        assertEquals(ShelfState.UPDATED, поставкой.state, "акт узнан по обозначению")
        assertEquals("NR-0001", поставкой.item.code, "поставка легла в уже лежащую карточку")
        assertEquals(1, полки.of("normative_document").size, "на полке одна карточка акта")
        assertEquals(
            "действует на 29.08.2026",
            полки.item("NR-0001")!!.doc.path("edition").asText(),
            "реквизиты поставки дополнили карточку разбора",
        )
    }

    @Test
    fun `акт узнаётся при иной цитате и при смене редакции`() {
        // Живой разбор цитирует так, как написано в записке: без пробела
        // после номера и без редакции.
        полки.put(
            "normative_document", "NR-0002",
            норматив("ПП РФ от 30.11.2024 №1701", "Удалённая идентификация БАС"),
            "разбор записки",
        )
        // Поставка несёт ту же норму в действующей редакции.
        val поставкой = полки.put(
            "normative_document", "ПП-1701",
            норматив(
                "ПП РФ от 30.11.2024 № 1701 (ред. ПП РФ от 02.02.2026 № 83)",
                "Удалённая идентификация БАС",
            ).put("edition", "в редакции от 02.02.2026"),
            "поставка v2",
        )
        assertEquals(ShelfState.UPDATED, поставкой.state, "пробел и скобки — не второй акт")
        assertEquals("NR-0002", поставкой.item.code)
        assertEquals(1, полки.of("normative_document").size, "редакция — версия карточки, не новый акт")
    }

    @Test
    fun `разные акты одного вида не склеиваются`() {
        полки.put(
            "normative_document", "MT-237",
            норматив("Приказ Минтранса России от 10.07.2020 № 237", "Оснащение ЖД состава"),
            "поставка v2",
        )
        val второй = полки.put(
            "normative_document", "MT-224",
            норматив("Приказ Минтранса России от 18.05.2026 № 224", "Оснащение ЖД состава с 2027"),
            "поставка v2",
        )
        assertEquals(ShelfState.CREATED, второй.state, "другой номер и дата — другой акт")
        assertEquals(2, полки.of("normative_document").size)
    }

    @Test
    fun `у вида без естественного ключа код остаётся единственным именем`() {
        val шаблон = mapper.createObjectNode().put("designation", "PHT").put("title", "Шаблон")
        полки.put("phase_template", "PHT-9001", шаблон, "поставка v2")
        val другой = полки.put("phase_template", "PHT-9002", шаблон, "поставка v2")
        assertEquals(ShelfState.CREATED, другой.state, "совпадение полей — не повод склеить шаблоны")
        assertEquals(2, полки.of("phase_template").size)
    }
}
