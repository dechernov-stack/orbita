// Подписи кодов перечислений (шаг 15 §2). Модель хранит коды (`operator`,
// `customer`, `regulator`), экран показывает подписи.
//
// Механизм тот же, что у единиц измерения (CR-001 п. 6, UnitLabels): таблица
// живёт в конфигурации и отдаётся клиенту, а не рассыпается по экранам
// собственными словарями. Рассыпанная подстановка уже приводила к тому, что
// на одном экране класс потребителя подписан, а на соседнем выходит кодом.
package orbita.req

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path

class EnumLabels(json: String = defaultJson()) {

    private val groups: Map<String, Map<String, String>> =
        mapper.readTree(json).path("groups").properties().associate { (group, values) ->
            group to values.properties().associate { (code, label) -> code to label.asText() }
        }

    /** Подпись кода в группе; неизвестный код возвращается как есть, а не теряется. */
    fun label(group: String, code: String): String = groups[group]?.get(code) ?: code

    /** Вся таблица — клиенту, чтобы подстановка шла на стороне представления. */
    fun all(): Map<String, Map<String, String>> = groups

    val groupNames: Set<String> get() = groups.keys

    companion object {
        private val mapper = ObjectMapper()

        /** Язык подписей выбирается конфигурацией: ORBITA_ENUM_LABELS — путь к файлу. */
        fun defaultJson(): String {
            System.getenv("ORBITA_ENUM_LABELS")?.let { return Files.readString(Path.of(it)) }
            return EnumLabels::class.java.getResourceAsStream("/orbita/req/enum-labels-ru.json")!!
                .use { it.readAllBytes().decodeToString() }
        }
    }
}
