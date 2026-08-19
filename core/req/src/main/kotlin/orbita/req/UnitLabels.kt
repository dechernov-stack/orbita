// Локализация подписей единиц (CR-001 п. 6). Единицы модели — коды СИ;
// подписи живут в конфигурации, а не в коде. Неизвестная единица выводится
// кодом, а не теряется.
package orbita.req

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path

class UnitLabels(json: String = defaultJson()) {

    private val labels: Map<String, String> = mapper.readTree(json).path("labels")
        .let { node -> buildMap { node.fields().forEach { (k, v) -> put(k, v.asText()) } } }

    /** Подпись единицы; неизвестный код возвращается как есть. */
    fun label(unitCode: String): String = labels[unitCode] ?: unitCode

    /** Готовая функция подстановки для renderConstraint. */
    fun asFunction(): (String) -> String = ::label

    companion object {
        private val mapper = ObjectMapper()

        /** Язык подписей выбирается конфигурацией: ORBITA_UNIT_LABELS — путь к файлу. */
        fun defaultJson(): String {
            System.getenv("ORBITA_UNIT_LABELS")?.let { return Files.readString(Path.of(it)) }
            return UnitLabels::class.java.getResourceAsStream("/orbita/req/unit-labels-ru.json")!!
                .use { it.readAllBytes().decodeToString() }
        }
    }
}
