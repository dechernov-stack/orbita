// Выгрузка демонстрационного проекта «Орбита-IoT» из эталона.
//
// ОДИН ЗАГРУЗЧИК НА ВЕСЬ ПРОЕКТ. Заполнение базы (core/com) и проверка
// документов (core/out) берут проект отсюда, а не каждый своим способом:
// второй загрузчик разошёлся бы с первым на первом же изменении эталона —
// и разошёлся бы молча.
package orbita.mod

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.concurrent.TimeUnit

object DemoModel {

    private val mapper = ObjectMapper()

    /** Выгрузка проекта из эталона `spec/demo_project.py`. Второй копии данных нет. */
    fun load(): JsonNode {
        val script = RepoPaths.repoRoot().resolve("spec/demo_project.py")
        val process = ProcessBuilder("python3", script.toString(), "--dump")
            .directory(RepoPaths.repoRoot().toFile())
            .redirectErrorStream(false)
            .start()
        val out = process.inputStream.readAllBytes().decodeToString()
        val err = process.errorStream.readAllBytes().decodeToString()
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            error("не удалось выгрузить демо-проект из эталона: $err")
        }
        return mapper.readTree(out)
    }
}
