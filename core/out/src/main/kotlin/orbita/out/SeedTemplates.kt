// Тестовый источник шаблонов (нитка Б.1): данные бывшего enum читаются из
// сида — те же, что сеются в библиотечную область стенда.
package orbita.out

import orbita.mod.RepoPaths

object SeedTemplates {
    val all: List<TemplateData> by lazy {
        TemplateSeed.load(RepoPaths.repoRoot().resolve("data/library/document-templates.json"))
    }

    fun of(code: String): TemplateData =
        all.firstOrNull { it.code == code }
            ?: throw IllegalArgumentException("неизвестный шаблон документа: " + code)
}
