// Подписи имён полей форм (хвост блока D, §3.6: коды в интерфейс не выходят).
// Механизм тот же, что у EnumLabels и UnitLabels: одна таблица в конфигурации,
// отдаётся клиенту целиком; словари, рассыпанные по экранам, расходятся молча.
package orbita.req

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path

class FieldLabels(json: String = defaultJson()) {

    private val fields: Map<String, String> =
        mapper.readTree(json).path("fields").properties()
            .associate { (name, label) -> name to label.asText() }

    /** Подпись поля; неизвестное поле возвращается кодом — видимый пробел. */
    fun label(field: String): String = fields[field] ?: field

    /** Вся таблица — клиенту: подстановка идёт на стороне представления. */
    fun all(): Map<String, String> = fields

    companion object {
        private val mapper = ObjectMapper()

        /** Язык подписей выбирается конфигурацией: ORBITA_FIELD_LABELS — путь к файлу. */
        fun defaultJson(): String {
            System.getenv("ORBITA_FIELD_LABELS")?.let { return Files.readString(Path.of(it)) }
            return FieldLabels::class.java.getResourceAsStream("/orbita/req/field-labels-ru.json")!!
                .use { it.readAllBytes().decodeToString() }
        }
    }
}
