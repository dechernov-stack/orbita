// Д1: разбор — рядом с файлом, имя по отпечатку (хеш файла + версия
// разборщика). Мера решения: повторный вызов по тому же документу НИЧЕГО
// не пересчитывает — служба и промпты работают с готовым разбором, файл
// второй раз не читается.
package orbita.com.api

import orbita.out.DocumentParse
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.out.ParseLexicon
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DocumentParseStoreTest {

    private val mapper = ObjectMapper()

    @TempDir
    lateinit var files: Path

    private val lexicon = ParseLexicon(
        unitSpellings = mapOf("млрд руб." to "BRUB"),
        terms = listOf("Карта спроса"),
        toCanon = { v, u -> if (u == "BRUB") v * 1000 to "MRUB" else null },
    )

    /** Формат для хранилища безразличен — берём md (разбор docx проверен в core:out). */
    private fun note(text: String): ByteArray = "# Записка\n\n$text\n".toByteArray()

    @Test
    fun `разбор кладётся рядом с файлом и читается картой и каноном`() {
        val bytes = note("Инвестиции первого этапа — 7–9 млрд руб.")
        val fingerprint = DocumentParseStore.parseAndStore(
            files.toString(), "SD-0003", "записка.md", bytes, lexicon,
        )
        assertNotNull(fingerprint)
        assertEquals(DocumentParse.fingerprint(bytes, lexicon), fingerprint)

        val map = DocumentParseStore.mapOf(files.toString(), "SD-0003")!!
        assertEquals("SD-0003", map.path("source_document").asText())
        assertEquals(fingerprint, map.path("fingerprint").asText())
        assertTrue(map.path("summary").path("blocks").asInt() > 0)
        assertEquals("MRUB", map.path("numbers")[0].path("canonical").path("unit").asText())

        val canon = DocumentParseStore.canonOf(files.toString(), "SD-0003")!!
        assertTrue("Инвестиции первого этапа — 7–9 млрд руб." in canon)
        assertTrue("{#b0}" in canon)
        // карта текста НЕ несёт: текст живёт в каноне единственный раз
        assertTrue("Инвестиции первого этапа" !in map.toString())
    }

    @Test
    fun `повторный вызов не пересчитывает - разбор берётся отпечатком`() {
        val bytes = note("Текст записки.")
        DocumentParseStore.parseAndStore(files.toString(), "SD-0003", "записка.md", bytes, lexicon)
        val mapFile = files.resolve("SD-0003/parse/${DocumentParse.fingerprint(bytes, lexicon)}.json")
        val stamp = Files.getLastModifiedTime(mapFile)

        val again = DocumentParseStore.parseAndStore(
            files.toString(), "SD-0003", "записка.md", bytes, lexicon,
        )
        assertEquals(DocumentParse.fingerprint(bytes, lexicon), again)
        assertEquals(stamp, Files.getLastModifiedTime(mapFile)) { "разбор пересчитан повторно" }
    }

    @Test
    fun `правка файла - новый разбор, прежний остаётся`() {
        val first = note("Первая редакция.")
        val second = note("Вторая редакция, с правками.")
        DocumentParseStore.parseAndStore(files.toString(), "SD-0003", "з.md", first, lexicon)
        DocumentParseStore.parseAndStore(files.toString(), "SD-0003", "з.md", second, lexicon)

        val dir = files.resolve("SD-0003/parse")
        val maps = Files.list(dir).use { s -> s.filter { it.toString().endsWith(".json") }.count() }
        assertEquals(2, maps) { "разборы разных редакций обязаны сосуществовать" }
        assertTrue("Вторая редакция" in DocumentParseStore.canonOf(files.toString(), "SD-0003")!!)
    }

    @Test
    fun `урожай Д2 рядом не подменяет карту разбора`() {
        val bytes = note("Текст записки.")
        val fingerprint = DocumentParseStore.parseAndStore(
            files.toString(), "SD-0003", "записка.md", bytes, lexicon,
        )!!
        // урожай ложится тем же каталогом и тоже .json — но это другой слой
        DocumentHarvest.store(
            files.toString(), "SD-0003", fingerprint,
            mapper.readTree("""{"kind":"document_semantic_parse","source_document":"SD-0003","items":[]}"""),
        )
        val map = DocumentParseStore.mapOf(files.toString(), "SD-0003")!!
        assertTrue(map.has("structure") && map.has("parser_version")) { "картой пришёл урожай: $map" }
        assertEquals(fingerprint, map.path("fingerprint").asText())
        assertTrue(DocumentHarvest.of(files.toString(), "SD-0003")!!.path("kind").asText()
            == "document_semantic_parse")
    }

    @Test
    fun `разбора нет - карта и канон молчат, а не падают`() {
        assertNull(DocumentParseStore.mapOf(files.toString(), "SD-9999"))
        assertNull(DocumentParseStore.canonOf(files.toString(), "SD-9999"))
    }
}
