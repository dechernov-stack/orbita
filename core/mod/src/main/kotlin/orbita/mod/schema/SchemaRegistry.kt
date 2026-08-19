// Реестр нормативных схем (TZ-MOD-001, STEP-1 §1.3): все схемы загружаются при
// старте, $ref разрешаются только внутрь schemas/ по префиксу $id.
package orbita.mod.schema

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.PathType
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.relativeTo

/**
 * Ошибка валидации на границе: путь до поля и нарушенное правило (TZ-MOD-002);
 * для нарушений Р1–Р9 — идентификатор ADR (TZ-MOD-003).
 */
data class ValidationError(
    val path: String,
    val rule: String,
    val message: String,
    val adr: String? = null,
) {
    override fun toString(): String = buildString {
        append(path.ifEmpty { "/" }).append(": ").append(message).append(" [rule: ").append(rule).append(']')
        adr?.let { append(" [").append(it).append(']') }
    }
}

class SchemaValidationException(val schemaName: String, val errors: List<ValidationError>) :
    RuntimeException("validation against '$schemaName' failed: " + errors.joinToString("; "))

class SchemaRegistry(schemasDir: Path) {

    private val mapper = ObjectMapper()

    private val config: SchemaValidatorsConfig = SchemaValidatorsConfig.builder()
        .pathType(PathType.JSON_POINTER)
        .locale(Locale.ROOT) // сообщения об ошибках — английские (CLAUDE.md §3)
        .build()

    private val factory: JsonSchemaFactory =
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012) { builder ->
            builder.schemaMappers { m -> m.mapPrefix(ID_PREFIX, schemasDir.toUri().toString()) }
        }

    /** Имена схем: путь без суффикса .schema.json, напр. contracts/spacecraft, core/service. */
    val names: List<String> = Files.walk(schemasDir).use { paths ->
        paths.filter { it.fileName.toString().endsWith(".schema.json") }
            .map { it.relativeTo(schemasDir).toString().removeSuffix(".schema.json") }
            .sorted()
            .toList()
    }

    private val schemas: Map<String, JsonSchema> = names.associateWith { name ->
        factory.getSchema(SchemaLocation.of("$ID_PREFIX$name.schema.json"), config)
    }

    init {
        // fail fast: любой неразрешимый $ref обнаруживается при старте, а не на первом запросе
        schemas.values.forEach { it.initializeValidators() }
    }

    fun schema(name: String): JsonSchema =
        schemas[name] ?: throw IllegalArgumentException(
            "unknown schema '$name'; known: ${names.joinToString(", ")}"
        )

    fun parse(json: String): JsonNode = mapper.readTree(json)

    /** Валидация документа по схеме; пустой список — документ валиден. */
    fun validate(name: String, doc: JsonNode): List<ValidationError> =
        schema(name).validate(doc).map { m ->
            val path = m.instanceLocation.toString()
            ValidationError(
                path = path,
                rule = m.type,
                message = m.message,
                adr = AdrMap.adrFor(m.schemaLocation.toString(), path),
            )
        }

    /** Валидация с отказом: невалидный вход отклоняется исключением (TZ-MOD-002). */
    fun require(name: String, doc: JsonNode) {
        val errors = validate(name, doc)
        if (errors.isNotEmpty()) throw SchemaValidationException(name, errors)
    }

    companion object {
        /** Префикс $id всех схем комплекта; $ref за пределы schemas/ не разрешаются. */
        const val ID_PREFIX = "https://kis.local/schemas/"
    }
}
