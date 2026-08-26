// В3 (приёмка §3): реестр прав — файл конфигурации; тест сверяет покрытие
// write-маршрутов и запрещает возвращение перечней ролей в код.
package orbita.req

import orbita.mod.RepoPaths
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PermissionsCoverageTest {

    @Test
    fun `каждый write-маршрут HttpApi накрыт правилом реестра`() {
        val src = RepoPaths.repoRoot()
            .resolve("core/com/src/main/kotlin/orbita/com/api/HttpApi.kt")
            .toFile().readText()
        val literals = Regex("\"(/(?:edit|objects|gates|ai|views|library|import|export)[a-z0-9_/.{}-]*)\"")
            .findAll(src).map { it.groupValues[1] }.distinct().toList()
        val uncovered = literals.filterNot { path ->
            listOf("POST", "PUT", "PATCH", "DELETE").any { m ->
                Permissions.default.ruleFor(m, path) != null
            }
        }
        assertTrue(uncovered.isEmpty()) {
            "write-маршруты без правила реестра (fail-closed сработает, но правило обязано быть): $uncovered"
        }
    }

    @Test
    fun `перечни ролей живут в permissions_json, не в коде маршрутизатора`() {
        val src = RepoPaths.repoRoot()
            .resolve("core/com/src/main/kotlin/orbita/com/api/HttpApi.kt")
            .toFile().readText()
        // константа-перечень ролей в коде — отказ (СТАРТ-В3 §3)
        assertTrue(!Regex("setOf\\(\\s*\"(lead|lead_se|specialist|sma|da_review|reader)\"").containsMatchIn(src)) {
            "перечень ролей найден в HttpApi.kt — правам место в permissions.json"
        }
    }
}
