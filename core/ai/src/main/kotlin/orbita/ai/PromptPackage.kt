// Промпт-пакет (TZ-AI-001). Эталон spec/ai_semantics.py, один в один.
//
// Пакет несёт контекст, задание и схему ответа СТРУКТУРОЙ. «Верни JSON вот
// такого вида» внутри задания означало бы, что разбор угадывает (ловушка 5).
// Схема берётся из реестра нормативных схем: второй копии схемы целевого
// объекта в проекте не заводится.
package orbita.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.mod.schema.SchemaRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Вид пакета: чем питается, что порождает и какому объекту модели соответствует ответ. */
data class PackageKind(
    val id: String,
    val input: String,
    val output: String,
    /** Имя нормативной схемы целевого объекта; null — объект в модели не описан. */
    val targetSchema: String?,
    /** JSON Pointer внутрь схемы, если целевой объект — её часть. */
    val targetPointer: String? = null,
    /** Виды, чья поимённая выборка нужна операции (СТАРТ-В3 §1);
        остальное уходит агрегатом — счётчик и занятый диапазон id. */
    val contextTypes: List<String> = emptyList(),
    /** Правила задания, специфичные виду (Д2: 11 правил разбора документа).
        Живут в реестре видов — расширение перечня не трогает код. */
    val rules: List<String> = emptyList(),
)

/**
 * Перечень видов пакетов. Расширяется без изменения кода (TZ-AI-001):
 * ресурс по умолчанию, переопределение — файлом из ORBITA_PACKAGE_KINDS.
 */
class PackageKinds(private val kinds: Map<String, PackageKind>) {

    val ids: Set<String> get() = kinds.keys

    fun of(id: String): PackageKind =
        kinds[id] ?: throw UnknownPackageKindException(id, kinds.keys)

    companion object {
        private val mapper = ObjectMapper()

        fun default(): PackageKinds {
            System.getenv("ORBITA_PACKAGE_KINDS")?.let {
                return fromJson(Files.readString(Path.of(it)))
            }
            val res = PackageKinds::class.java.getResourceAsStream("/orbita/ai/prompt-package-kinds.json")
                ?: error("prompt-package-kinds.json resource is missing")
            return res.use { fromJson(it.readAllBytes().decodeToString()) }
        }

        fun fromJson(json: String): PackageKinds = PackageKinds(
            mapper.readTree(json).path("kinds").associate { n ->
                val id = n.path("id").asText()
                id to PackageKind(
                    id = id,
                    input = n.path("input").asText(""),
                    output = n.path("output").asText(""),
                    targetSchema = n.path("target_schema").takeIf { it.isTextual }?.asText(),
                    targetPointer = n.path("target_pointer").takeIf { it.isTextual }?.asText(),
                    contextTypes = n.path("context_types").map { it.asText() },
                    rules = n.path("rules").map { it.asText() },
                )
            },
        )
    }
}

class UnknownPackageKindException(kind: String, known: Set<String>) :
    IllegalArgumentException("неизвестный вид пакета: $kind (известны: ${known.sorted()})")

class UnmodelledTargetException(kind: String, note: String) :
    IllegalStateException("вид пакета $kind: $note")

data class PromptPackage(
    val id: String,
    val kind: String,
    val context: JsonNode,
    val task: String,
    val responseSchema: JsonNode?,
) {
    fun toJson(mapper: ObjectMapper = ObjectMapper()): ObjectNode {
        val n = mapper.createObjectNode()
        n.put("id", id)
        n.put("kind", kind)
        n.set<ObjectNode>("context", context)
        n.put("task", task)
        responseSchema?.let { n.set<ObjectNode>("response_schema", it) }
        return n
    }
}

/** Замечания к пакету; пустой список — пакет полон. */
fun packageIssues(pkg: PromptPackage): List<String> {
    val issues = mutableListOf<String>()
    if (pkg.id.isBlank()) issues += "в пакете нет поля id"
    if (pkg.kind.isBlank()) issues += "в пакете нет поля kind"
    if (pkg.context.isMissingNode || pkg.context.isNull || pkg.context.isEmpty) {
        issues += "в пакете нет поля context"
    }
    if (pkg.task.isBlank()) issues += "в пакете нет поля task"
    when {
        pkg.responseSchema == null || pkg.responseSchema.isMissingNode || pkg.responseSchema.isNull ->
            issues += "в пакете нет поля response_schema"
        !pkg.responseSchema.isObject ->
            issues += "схема ответа должна быть структурой, а не текстом"
    }
    return issues
}

class PromptPackageBuilder(
    private val kinds: PackageKinds = PackageKinds.default(),
    private val registry: SchemaRegistry? = null,
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    /** Пакет с явно заданной схемой ответа. */
    fun build(kind: String, context: JsonNode, task: String, responseSchema: JsonNode): PromptPackage {
        kinds.of(kind)   // неизвестный вид отклоняется до сборки
        return PromptPackage(packageId(kind, context, task, responseSchema), kind, context, task, responseSchema)
    }

    /**
     * Пакет со схемой ответа ИЗ РЕЕСТРА: схема соответствует целевому объекту
     * модели по построению, а не по договорённости (TZ-AI-001, ACCEPTANCE 2).
     */
    fun build(kind: String, context: JsonNode, task: String): PromptPackage {
        val k = kinds.of(kind)
        val reg = registry ?: error("schema registry is required to derive the response schema")
        val name = k.targetSchema ?: throw UnmodelledTargetException(
            kind, "объект модели не описан схемой — схему ответа выводить не из чего",
        )
        val schema = k.targetPointer?.let { reg.raw(name).at(it) } ?: reg.raw(name)
        if (schema.isMissingNode) {
            throw UnmodelledTargetException(kind, "в схеме $name нет пути ${k.targetPointer}")
        }
        return build(kind, context, task, schema)
    }

    /**
     * Идентификатор воспроизводим по СОДЕРЖИМОМУ: одинаковые контекст и задание
     * дают тот же идентификатор, изменение задания — другой. Каноническая форма
     * с сортировкой ключей обязательна, иначе порядок полей влияет на результат.
     */
    private fun packageId(kind: String, context: JsonNode, task: String, schema: JsonNode): String {
        val body = mapper.createObjectNode()
        body.put("kind", kind)
        body.set<ObjectNode>("context", context)
        body.put("task", task)
        body.set<ObjectNode>("response_schema", schema)
        // BLAKE2b в стандартном JCA нет; от функции здесь требуется только
        // детерминированность и чувствительность к содержимому (ср. ADR-014).
        val digest = MessageDigest.getInstance("SHA-256").digest(canonicalJson(body).toByteArray())
        return "PP-" + digest.take(4).joinToString("") { "%02x".format(it) }
    }
}

/** Каноническая форма JSON: ключи объектов отсортированы, порядок массивов сохранён. */
fun canonicalJson(node: JsonNode): String = when {
    node.isObject -> node.properties().sortedBy { it.key }
        .joinToString(",", "{", "}") { (k, v) -> "${quote(k)}:${canonicalJson(v)}" }
    node.isArray -> node.joinToString(",", "[", "]") { canonicalJson(it) }
    node.isTextual -> quote(node.asText())
    node.isNull -> "null"
    else -> node.asText()
}

private fun quote(s: String): String = buildString {
    append('"')
    s.forEach { c ->
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }
    append('"')
}
