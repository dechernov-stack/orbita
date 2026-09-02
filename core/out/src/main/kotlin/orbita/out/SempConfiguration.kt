// SEMP, разделы 4 и 7: соответствие процессов регламента механизмам системы
// и перечень среды работ. Это КОНФИГУРАЦИЯ, а не сочинение документа: она
// одинакова для всех проектов стенда и живёт данными (ресурс рядом с
// operations.json и document-kits.json) — правится без пересборки ядра.
package orbita.out

import com.fasterxml.jackson.databind.ObjectMapper

object SempConfiguration {

    /**
     * Строка таблицы соответствия: процесс NPR 7123.1 → чем сделан → где живёт.
     * Процесс вне области Формулирования несёт tailoring — отклонение названо.
     */
    data class Process(
        val number: Int,
        val process: String,
        val mechanism: String,
        val place: String,
        val tailoring: String? = null,
    )

    /** Строка перечня среды: область работ → чем ведётся. */
    data class Tool(val area: String, val tool: String)

    private val mapper = ObjectMapper()

    private val data by lazy {
        val stream = SempConfiguration::class.java
            .getResourceAsStream("/orbita/out/semp-configuration.json")
            ?: error("нет ресурса конфигурации SEMP: orbita/out/semp-configuration.json")
        stream.use { mapper.readTree(it) }
    }

    fun processes(): List<Process> = data.path("processes").map {
        Process(
            it.path("number").asInt(0),
            it.path("process").asText(""),
            it.path("mechanism").asText(""),
            it.path("place").asText(""),
            it.path("tailoring").asText("").ifBlank { null },
        )
    }

    fun tools(): List<Tool> = data.path("tools").map {
        Tool(it.path("area").asText(""), it.path("tool").asText(""))
    }
}
