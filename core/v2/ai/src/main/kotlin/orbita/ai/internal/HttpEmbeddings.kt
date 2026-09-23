// Эмбеддинги — провайдером по совместимому с OpenAI контракту `/v1/embeddings`
// (OpenAI, Voyage через шлюз, локальный сервер вроде text-embeddings-inference
// или Ollama). Провайдер называется окружением стенда: ORBITA_EMBED_URL ·
// ORBITA_EMBED_KEY · ORBITA_EMBED_MODEL · ORBITA_EMBED_DIM. Не задан — индекс
// работает лексически, и это сказано в сводке, а не спрятано.
package orbita.ai.internal

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.knowledge.api.Embeddings
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class HttpEmbeddings(
    private val url: String,
    private val key: String?,
    private val model: String,
    private val mapper: ObjectMapper = ObjectMapper(),
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build(),
) : Embeddings {

    override fun embed(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val тело = mapper.createObjectNode().put("model", model)
        тело.putArray("input").also { м -> texts.forEach { м.add(it) } }
        val запрос = HttpRequest.newBuilder(URI.create(url.trimEnd('/') + "/v1/embeddings"))
            .timeout(Duration.ofSeconds(120))
            .header("Content-Type", "application/json")
            .also { if (!key.isNullOrBlank()) it.header("Authorization", "Bearer $key") }
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(тело)))
            .build()
        val ответ = client.send(запрос, HttpResponse.BodyHandlers.ofString())
        check(ответ.statusCode() in 200..299) { "эмбеддинги: провайдер ответил ${ответ.statusCode()}: ${ответ.body().take(300)}" }
        val данные = mapper.readTree(ответ.body()).path("data")
        check(данные.isArray && данные.size() == texts.size) { "эмбеддинги: ответ без data на каждый текст" }
        return данные.sortedBy { it.path("index").asInt() }.map { э ->
            val вектор = э.path("embedding")
            FloatArray(вектор.size()) { i -> вектор[i].floatValue() }
        }
    }

    companion object {
        /** Провайдер из окружения; нет адреса — нет провайдера (поиск лексический). */
        fun fromEnv(mapper: ObjectMapper = ObjectMapper(), env: (String) -> String? = System::getenv): HttpEmbeddings? {
            val адрес = env("ORBITA_EMBED_URL")?.trim().orEmpty()
            if (адрес.isBlank()) return null
            return HttpEmbeddings(адрес, env("ORBITA_EMBED_KEY"), env("ORBITA_EMBED_MODEL")?.ifBlank { null } ?: "text-embedding-3-small", mapper)
        }

        fun dimFromEnv(env: (String) -> String? = System::getenv): Int = env("ORBITA_EMBED_DIM")?.toIntOrNull() ?: 1024
    }
}
