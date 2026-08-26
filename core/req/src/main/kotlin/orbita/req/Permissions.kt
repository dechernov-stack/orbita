// Реестр прав (В3, СТАРТ-В3 §2.3): «маршрут/действие → роль» — ДАННЫЕ
// рядом с gates.json, не константы в коде. Проверка — на сервере в каждом
// маршруте; write-маршрут без правила закрыт (fail-closed).
package orbita.req

import com.fasterxml.jackson.databind.ObjectMapper

data class PermissionRule(
    val methods: Set<String>,
    val path: Regex,
    val allow: Set<String>,
    val why: String,
    /** Спец-проверка владельца узла для специалиста — исполняет сервер. */
    val ownerGuard: Boolean,
)

class Permissions(private val rules: List<PermissionRule>) {

    /** Первое правило, накрывающее запрос; null — записи без правила нет. */
    fun ruleFor(method: String, path: String): PermissionRule? =
        rules.firstOrNull { method in it.methods && it.path.containsMatchIn(path) }

    val all: List<PermissionRule> get() = rules

    companion object {
        val default: Permissions by lazy {
            val res = Permissions::class.java.getResourceAsStream("/orbita/req/permissions.json")
                ?: error("permissions.json resource is missing")
            val n = res.use { ObjectMapper().readTree(it) }
            Permissions(
                n.path("rules").map { r ->
                    PermissionRule(
                        methods = r.path("methods").map { it.asText() }.toSet(),
                        path = Regex(r.path("path").asText()),
                        allow = r.path("allow").map { it.asText() }.toSet(),
                        why = r.path("why").asText(""),
                        ownerGuard = r.path("owner_guard").asBoolean(false),
                    )
                },
            )
        }
    }
}
