// Сид шаблонов документов (нитка Б.1): данные бывшего enum, источник для
// наполнения библиотечной области и для тестов рендера. Продовый выпуск
// работает ТОЛЬКО по библиотечным объектам document_template.
package orbita.out

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path

object TemplateSeed {
    fun load(file: Path): List<TemplateData> {
        val mapper = ObjectMapper()
        return mapper.readTree(Files.readString(file)).path("templates").map { t ->
            TemplateData(
                code = t.path("code").asText(),
                title = t.path("title").asText(),
                source = t.path("source").asText(),
                sections = t.path("sections").map { sc ->
                    SectionTemplate(
                        number = sc.path("number").asInt(),
                        title = sc.path("title").asText(),
                        expects = sc.path("expects").asText(""),
                    )
                },
            )
        }
    }

    fun of(file: Path, code: String): TemplateData =
        load(file).firstOrNull { it.code == code }
            ?: throw IllegalArgumentException("неизвестный шаблон документа: " + code)
}
