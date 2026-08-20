// Сборка полезной нагрузки для службы обмена ReqIF (TZ-OUT-005, ADR-023).
//
// Разделение труда: отображение — здесь (эталон spec/reqif_semantics.py),
// XML — в службе (ops/exchange/reqif_service.py). Ядро не знает про XML,
// служба не знает про модель.
//
// НУЖДЫ И СЕРВИСЫ ВЫГРУЖАЮТСЯ ВМЕСТЕ С ТРЕБОВАНИЯМИ. Трассировка ведёт
// от них, а связь, у которой конец не существует в файле, семантически
// ломает файл для принимающего инструмента — это находит строгая проверка
// `reqif validate`, и нашлось это именно так.
package orbita.out

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

object ReqifExport {

    /**
     * Полезная нагрузка службы обмена из среза модели.
     *
     * Дата выгрузки — аргумент, а не чтение часов: экспорт фиксирует дату
     * (TZ-OUT-005), и одинаковый вход обязан давать одинаковый файл.
     */
    fun payload(
        model: JsonNode,
        links: List<ExchangeLink>,
        exportedAt: String,
        mapper: ObjectMapper = ObjectMapper(),
    ): ObjectNode {
        val requirements = model.path("requirements").toList()
        val specObjects = requirements.map { toSpecObject(it) }

        // незнакомые поля модели получают строковые атрибуты X-*
        val attributes = LinkedHashMap<String, String>()
        REQUIREMENT_MAP.forEach { attributes[it.name] = it.kind }
        specObjects.forEach { so -> so.values.keys.forEach { attributes.putIfAbsent(it, "string") } }

        val root = mapper.createObjectNode()
        root.put("title", "Орбита: выгрузка требований")
        root.put("exported_at", exportedAt)

        val datatypes = root.putObject("datatypes")
        attributes.forEach { (name, kind) ->
            val d = datatypes.putObject(name)
            d.put("kind", kind)
            REQIF_ENUM_VALUES[name]?.takeIf { kind == "enum" }?.let { values ->
                val arr = d.putArray("values")
                values.forEach(arr::add)
            }
        }
        datatypes.putObject("Stakeholder").put("kind", "string")

        val objectTypes = root.putObject("object_types")
        val reqType = objectTypes.putObject(SPEC_OBJECT_TYPES.getValue("requirement"))
        reqType.put("long_name", "Requirement")
        val reqAttrs = reqType.putObject("attributes")
        attributes.forEach { (name, kind) -> reqAttrs.put(name, kind) }

        val needType = objectTypes.putObject(SPEC_OBJECT_TYPES.getValue("need"))
        needType.put("long_name", "Need")
        needType.putObject("attributes")
            .put("ReqIF.ForeignID", "string").put("ReqIF.Text", "xhtml").put("Stakeholder", "string")

        val serviceType = objectTypes.putObject(SPEC_OBJECT_TYPES.getValue("service"))
        serviceType.put("long_name", "Service")
        serviceType.putObject("attributes")
            .put("ReqIF.ForeignID", "string").put("ReqIF.Text", "xhtml")

        val relationTypes = root.putObject("relation_types")
        SPEC_RELATION_TYPES.forEach { (kind, typeId) -> relationTypes.put(typeId, kind) }

        val objects = root.putArray("objects")
        specObjects.forEach { so ->
            val n = objects.addObject()
            n.put("identifier", so.identifier)
            n.put("type", so.type)
            val values = n.putObject("values")
            so.values.forEach { (k, v) -> values.set<ObjectNode>(k, v) }
        }
        model.path("needs").forEach { need ->
            val n = objects.addObject()
            n.put("identifier", reqifIdentifier("SO", need.path("id").asText()))
            n.put("type", SPEC_OBJECT_TYPES.getValue("need"))
            n.putObject("values")
                .put("ReqIF.ForeignID", need.path("id").asText())
                .put("ReqIF.Text", need.path("statement").asText(""))
                .put("Stakeholder", need.path("stakeholder").path("name").asText(""))
        }
        model.path("services").forEach { service ->
            val n = objects.addObject()
            n.put("identifier", reqifIdentifier("SO", service.path("id").asText()))
            n.put("type", SPEC_OBJECT_TYPES.getValue("service"))
            n.putObject("values")
                .put("ReqIF.ForeignID", service.path("id").asText())
                .put("ReqIF.Text", service.path("name").asText(""))
        }

        // Выгружаются только связи, оба конца которых в файле: остальное
        // сделало бы файл семантически сломанным для принимающего инструмента
        val exported = mutableSetOf<String>()
        objects.forEach { exported += it.path("identifier").asText() }
        val relations = root.putArray("relations")
        toSpecRelations(links)
            .filter { it.source in exported && it.target in exported }
            .forEach { r ->
                relations.addObject()
                    .put("identifier", r.identifier)
                    .put("type", r.type)
                    .put("source", r.source)
                    .put("target", r.target)
            }
        return root
    }
}
