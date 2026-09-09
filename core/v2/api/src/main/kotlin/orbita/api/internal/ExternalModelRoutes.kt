// Экран «Внешняя модель» (ADR-048, шип G): элементы модели Capella либо
// fixture с баннером, соответствие узлам состава. Только чтение.
package orbita.api.internal

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.architecture.api.ExternalModel

class ExternalModelRoutes(private val model: ExternalModel, private val mapper: ObjectMapper) {
    fun handle(method: String, path: String, query: Map<String, String>): V2Router.Ответ? {
        if (method != "GET" || path != "/v2/external-model") return null
        val проект = query["project"]?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("укажите ?project=<код проекта>")
        val вид = model.view(проект)
        val узел = mapper.createObjectNode()
        узел.put("source", вид.source).put("model_id", вид.modelId).put("banner", вид.banner)
        val элементы = узел.putArray("elements")
        вид.elements.forEach { э ->
            элементы.addObject().put("uuid", э.uuid).put("type", э.type).put("layer", э.layer).put("name", э.name).put("parent_uuid", э.parentUuid)
        }
        val соответствия = узел.putArray("mapping")
        вид.mapping.forEach { с -> соответствия.addObject().put("uuid", с.uuid).put("component", с.component).put("name", с.name) }
        return V2Router.Ответ(200, узел)
    }
}
