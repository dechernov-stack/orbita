// Библиотека референсных сценариев потребления (TZ-USR-006). Редактируется
// без изменения кода: JSON-ресурс, переопределение — ORBITA_SCENARIO_LIBRARY.
// Состав фиксируется версией и входит в состав карты спроса.
//
// Проверка полноты — та же, что у пресетов платформ (шаг 10.3): сценарий
// с нулевым темпом сообщений или без географии даёт карту спроса, которая
// выглядит посчитанной. Библиотека отказывается собраться целиком.
package orbita.usr

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.model.requireLibraryComplete
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

data class ReferenceScenario(
    val id: String,
    val name: String,
    val consumerClass: String,
    val geography: String,
    val msgsPerTerminalDay: Double,
    val payloadBytes: Int,
    val mobilityModel: String,
    val seeds: List<SeedObject>,
    val terminalProfile: JsonNode,
    /** Основание оценки: откуда взяты темп, размер сообщения и число терминалов. */
    val source: String,
)

class ScenarioLibrary(json: String = defaultJson()) {

    /** Версия состава библиотеки — свёртка содержимого (TZ-USR-006). */
    val version: String = MessageDigest.getInstance("SHA-256")
        .digest(json.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    val scenarios: List<ReferenceScenario> = mapper.readTree(json).path("scenarios").toList()
        .also { requireLibraryComplete(it, REQUIRED_FIELDS, "сценарная библиотека потребителей") }
        .map { s ->
            val msgs = s.path("msgs_per_terminal_day").asDouble()
            val klass = s.path("consumer_class").asText()
            ReferenceScenario(
                id = s.path("id").asText(),
                name = s.path("name").asText(),
                consumerClass = klass,
                geography = s.path("geography").asText(""),
                msgsPerTerminalDay = msgs,
                payloadBytes = s.path("payload_bytes").asInt(),
                mobilityModel = s.path("mobility_model").asText("static"),
                seeds = s.path("seeds").map { seed ->
                    SeedObject(
                        cellId = seed.path("cell_id").asText(),
                        lat = seed.path("lat").asDouble(),
                        lon = seed.path("lon").asDouble(0.0),
                        terminals = seed.path("terminals").asDouble(),
                        msgsPerTerminalDay = msgs,
                        klass = klass,
                    )
                },
                terminalProfile = s.path("terminal_profile"),
                source = s.path("source").asText(),
            )
        }

    fun byId(id: String): ReferenceScenario =
        scenarios.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("unknown scenario '$id'; known: ${scenarios.map { it.id }}")

    /** Разворачивание сценария в сценарный слой карты спроса (TZ-USR-004, слой 3). */
    fun expandSeeds(id: String): List<SeedObject> = byId(id).seeds

    companion object {
        private val mapper = ObjectMapper()

        /**
         * Обязательные поля сценария. `seeds` и `terminal_profile` — пустой
         * список и пустой объект здесь означают отсутствие: сценарий без
         * опорных ячеек не разворачивается в слой карты, а без профиля
         * терминала нечем считать радиолинию.
         */
        val REQUIRED_FIELDS: List<String> = listOf(
            "id", "name", "consumer_class", "geography", "msgs_per_terminal_day",
            "payload_bytes", "mobility_model", "seeds", "terminal_profile", "source",
        )

        fun defaultJson(): String {
            System.getenv("ORBITA_SCENARIO_LIBRARY")?.let { return Files.readString(Path.of(it)) }
            return ScenarioLibrary::class.java.getResourceAsStream("/orbita/usr/scenario-library.json")!!
                .use { it.readAllBytes().decodeToString() }
        }
    }
}
