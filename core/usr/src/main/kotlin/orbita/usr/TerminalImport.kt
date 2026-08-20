// Импорт профиля терминала из каталога устройств LoRaWAN (шаг 14, ADR-024).
// Поведение — эталон spec/import_semantics.py::map_terminal, один в один.
//
// ОТОБРАЖЕНИЕ, А НЕ ДОВЕРИЕ: запись каталога переводится в поля нашего
// профиля и идёт через ТОТ ЖЕ ФИЛЬТР, что рукописная (TerminalRules плюс
// правила импорта). Неизвестное значение перечисления — например, регион,
// которого нет в нашем перечне, — не подставляется наугад: поле остаётся
// пустым, и запись отбраковывается фильтром. Угаданный регион дал бы
// правдоподобный профиль с чужим частотным планом.
//
// НЕЗНАКОМЫЕ ПОЛЯ ИСТОЧНИКА СОХРАНЯЮТСЯ в `source_extras`, а не отбрасываются:
// выброшенное при импорте не восстановить, а что из него понадобится —
// выяснится позже.
package orbita.usr

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.mod.model.provenanceIssues

/** Регионы источника → наш перечень. Неизвестный регион ОСТАЁТСЯ null. */
val REGION_MAP: Map<String, String> = mapOf(
    "EU863-870" to "EU868",
    "US902-928" to "US915",
    "RU864-870" to "RU864",
)

/** Поля источника, у которых есть отображение; остальные уходят в source_extras. */
private val KNOWN_FIELDS = setOf(
    "name", "macVersion", "maxEIRP", "supportsClassC", "regionalParametersVersion", "region",
)

object TerminalImport {

    /**
     * Запись каталога (устройство + профиль) → черновик нашего профиля.
     * Класс потребителя выводится из СВОЙСТВ профиля, а не задаётся источником:
     * поддержка класса C у устройства означает возможность оперативного
     * управления — наш C′.
     */
    fun mapTerminal(
        device: JsonNode,
        profile: JsonNode,
        provenance: JsonNode,
        mapper: ObjectMapper = ObjectMapper(),
    ): ObjectNode {
        val out = mapper.createObjectNode()
        out.put("name", device.path("name").asText(null))
        out.put(
            "consumer_class",
            if (profile.path("supportsClassC").asBoolean(false)) "C_prime" else "A_prime",
        )
        REGION_MAP[profile.path("region").asText("")]
            ?.let { out.put("regulatory_region", it) }
            ?: out.putNull("regulatory_region")
        val radio = out.putObject("radio")
        profile.path("maxEIRP").takeIf { it.isNumber }?.let { radio.put("eirp_dbm", it.asDouble()) }
        out.set<ObjectNode>("provenance", provenance.deepCopy())

        val extras = out.putObject("source_extras")
        (device.properties() + profile.properties())
            .filter { (k, _) -> k !in KNOWN_FIELDS }
            .forEach { (k, v) -> extras.set<ObjectNode>(k, v.deepCopy()) }
        return out
    }

    /**
     * Фильтр импортированной записи — ровно те замечания, без которых запись
     * непригодна для дальнейшего заполнения. Полная проверка профиля идёт
     * дальше ТЕМИ ЖЕ TerminalRules, что для рукописного: здесь только то,
     * что рукописный ввод не проверяет, — происхождение.
     */
    fun screen(record: JsonNode): List<String> = buildList {
        if (record.path("consumer_class").asText("").isBlank()) add("класс не определён")
        if (record.path("regulatory_region").let { it.isNull || it.asText("").isBlank() }) {
            add("регуляторный регион не определён")
        }
        if (!record.path("radio").path("eirp_dbm").isNumber) add("не задана мощность передачи")
        addAll(provenanceIssues(record.path("provenance")))
    }
}
