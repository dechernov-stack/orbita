// Реестр схем v2: читает СГЕНЕРИРОВАННЫЕ схемы из schemas/v2.
//
// Правило ТЗ-BACKEND §2.6: вид вне YAML не существует, поле вне схемы —
// отказ. Поэтому реестр не знает рукописных схем: он видит ровно то, что
// сгенерировал tools/v2/gen_schemas.py, а сторож в CI держит их в согласии
// с истиной YAML.
package orbita.kernel.internal

import com.fasterxml.jackson.databind.JsonNode
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion
import orbita.kernel.api.SchemaRegistry
import orbita.kernel.schema.GeneratedKinds
import java.nio.file.Files
import java.nio.file.Path

class GeneratedSchemaRegistry(schemasV2Dir: Path) : SchemaRegistry {

    private val префикс = "https://kis.local/schemas/v2/"

    private val конфигурация: SchemaValidatorsConfig = SchemaValidatorsConfig.builder().build()

    private val фабрика: JsonSchemaFactory =
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012) { builder ->
            builder.schemaMappers { m -> m.mapPrefix(префикс, schemasV2Dir.toUri().toString()) }
        }

    private val имена: List<String> = Files.list(schemasV2Dir).use { пути ->
        пути.filter { it.fileName.toString().endsWith(".schema.json") }
            .map { it.fileName.toString().removeSuffix(".schema.json") }
            .sorted()
            .toList()
    }

    private val схемы: Map<String, JsonSchema> = имена.associateWith { имя ->
        фабрика.getSchema(SchemaLocation.of("$префикс$имя.schema.json"), конфигурация)
    }

    override fun kinds(): List<String> = имена.filterNot { it.startsWith("_") }

    override fun problems(kind: String, doc: JsonNode): List<String> {
        // Вид, которого нет в истине схем, не существует: молчать нельзя.
        GeneratedKinds.of(kind)
        val схема = схемы[kind] ?: error("схема вида «$kind» не сгенерирована — перегенерируйте схемы")
        return схема.validate(doc).map { "${it.instanceLocation}: ${it.message}" }
    }
}
