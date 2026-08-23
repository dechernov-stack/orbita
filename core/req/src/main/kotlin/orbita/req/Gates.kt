// Готовность пакета к контрольной точке (TZ-REQ-008, TZ-OUT-003). Поведение —
// эталон spec/requirements_semantics.py::readiness. Требуемые статусы задаются
// конфигурацией контрольной точки, не кодом.
package orbita.req

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.store.StoredObject
import java.nio.file.Files
import java.nio.file.Path

/** Облегчённый снимок объекта для отчётов зрелости и целостности. */
data class ObjectSnapshot(
    val id: String,
    val type: String,
    val status: String,
    val owner: String? = null,
    val level: String? = null,
) {
    companion object {
        fun of(o: StoredObject): ObjectSnapshot = ObjectSnapshot(
            o.id, o.type, o.status.name,
            owner = o.doc.path("owner").asText(null),
            level = o.doc.path("level").asText(null),
        )
    }
}

/** Расхождение зрелости: объект не достиг требуемого статуса. */
data class GateGap(
    val id: String,
    val type: String,
    val actual: String,
    val required: String,
    val owner: String? = null,
)

class Gates(private val gates: Map<String, Map<String, String>> = load()) {

    val gateNames: Set<String> get() = gates.keys

    /** Объекты, не достигшие требуемой зрелости; Cancelled не участвует. */
    fun readiness(objects: List<ObjectSnapshot>, gate: String): List<GateGap> {
        val required = gates[gate]
            ?: throw IllegalArgumentException("unknown gate '$gate'; configured: ${gates.keys}")
        return objects.mapNotNull { o ->
            val need = required[o.type] ?: return@mapNotNull null
            if (o.status == "Cancelled") return@mapNotNull null
            if (ORDER.indexOf(o.status) < ORDER.indexOf(need)) {
                GateGap(o.id, o.type, o.status, need, o.owner)
            } else null
        }.sortedBy { it.id }
    }

    companion object {
        val ORDER = listOf("Draft", "Preliminary", "Approved", "Baseline")

        private val mapper = ObjectMapper()

        /** Конфигурация: ресурс gates.json, переопределение — ORBITA_GATES (путь к файлу). */
        fun load(): Map<String, Map<String, String>> {
            val json = System.getenv("ORBITA_GATES")?.let { Files.readString(Path.of(it)) }
                ?: Gates::class.java.getResourceAsStream("/orbita/req/gates.json")!!
                    .use { it.readAllBytes().decodeToString() }
            val n = mapper.readTree(json)
            return buildMap {
                n.fields().forEach { (gate, types) ->
                    if (gate.startsWith("_")) return@forEach
                    put(gate, buildMap {
                        types.fields().forEach { (t, s) ->
                            // служебные ключи точки: пояснение и реестр пробелов, не статусы
                            if (!t.startsWith("_") && t != "pending") put(t, s.asText())
                        }
                    })
                }
            }
        }
    }
}
