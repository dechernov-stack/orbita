// Внешняя модель (ADR-048, шип G): адаптер Capella читает модель capellambse
// (только чтение) и отдаёт элементы; без адаптера показывается учебная
// fixture из ресурса — с баннером, что это не интеграция. Соответствие
// с нашим составом — по полю `external_identity` узла.
package orbita.architecture.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.architecture.api.ExternalElement
import orbita.architecture.api.ExternalMapping
import orbita.architecture.api.ExternalModel
import orbita.architecture.api.ExternalModelView
import orbita.kernel.api.Area
import orbita.kernel.api.EntityStore
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal class CapellaExternalModel(
    private val store: EntityStore,
    private val mapper: ObjectMapper,
    private val adapterUrl: String? = System.getenv("ORBITA_CAPELLA_URL")?.takeIf { it.isNotBlank() },
    private val client: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build(),
) : ExternalModel {

    override fun view(project: String): ExternalModelView {
        val (источник, тело, баннер) = адаптер() ?: fixture()
        val элементы = тело.path("elements").map {
            ExternalElement(
                it.path("uuid").asText(""), it.path("type").asText(""), it.path("layer").asText(""),
                it.path("name").asText(""), it.path("parent_uuid").asText("").ifBlank { null },
            )
        }
        val поUuid = элементы.associateBy { it.uuid }
        val соответствия = store.list(Area.Project(project), "component")
            .filter { it.status != "cancelled" }
            .mapNotNull { узел ->
                val uuid = узел.doc.path("external_identity").asText("").ifBlank { return@mapNotNull null }
                ExternalMapping(uuid, узел.code, поUuid[uuid]?.name ?: "элемента с таким UUID в модели нет")
            }
        return ExternalModelView(источник, тело.path("model_id").asText(""), баннер, элементы, соответствия)
    }

    private fun адаптер(): Triple<String, JsonNode, String?>? {
        val url = adapterUrl ?: return null
        val ответ = runCatching {
            client.send(
                HttpRequest.newBuilder(URI.create(url.trimEnd('/') + "/elements")).timeout(Duration.ofSeconds(60)).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        }.getOrNull()
        if (ответ == null || ответ.statusCode() != 200) {
            val (_, тело, _) = fixture()
            val причина = ответ?.let { "адаптер ответил ${it.statusCode()}: ${it.body().take(120)}" } ?: "адаптер не отвечает"
            return Triple("fixture", тело, "модель — учебная fixture: $причина ($url); в модель система не пишет никогда (ADR-048)")
        }
        return Triple("capella", mapper.readTree(ответ.body()), null)
    }

    private fun fixture(): Triple<String, JsonNode, String?> {
        val ресурс = javaClass.getResourceAsStream("/orbita/architecture/capella-fixture.json")
            ?: error("fixture внешней модели не собрана в jar")
        val тело = mapper.readTree(ресурс)
        return Triple("fixture", тело, тело.path("banner").asText(""))
    }
}
