// Вход через Telegram — тонкий клиент центрального шлюза tg-authgw
// (та же схема, что у afisha и ir-storyboard): логин делегирован шлюзу
// (один общий бот, группы под продукты), сессию шлюза проверяем ЛОКАЛЬНО —
// HMAC-SHA256 секретом продукта, продукт в теле, срок 21 день. Шлюз не на
// горячем пути: после входа человек живёт обычной сессией Орбиты.
//
// Включается ORBITA_AUTH_MODE=telegram + AUTHGW_URL + ORBITA_SESSION_SECRET
// (тот же секрет, что у продукта в AUTHGW_PRODUCTS шлюза). Без любого из
// них — режима нет, и сервер говорит об этом словами, а не молчит.
package orbita.com.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class TelegramGate(
    private val url: String? = env("AUTHGW_URL"),
    private val product: String = env("AUTHGW_PRODUCT") ?: "orbita",
    private val secret: String? = env("ORBITA_SESSION_SECRET"),
    private val mapper: ObjectMapper = ObjectMapper(),
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
    /** Часы — параметром: срок сессии проверяется тестом, а не ожиданием. */
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    /** Кто вошёл: telegram-id и имя, как их выдал шлюз. */
    data class Identity(val tid: Long, val name: String)

    /** Шлюз и секрет заданы — вход через Telegram возможен. */
    fun enabled(): Boolean = !url.isNullOrBlank() && !secret.isNullOrBlank()

    /** Чего не хватает для режима — для честного ответа whoami и журнала. */
    fun missing(): List<String> = buildList {
        if (url.isNullOrBlank()) add("AUTHGW_URL")
        if (secret.isNullOrBlank()) add("ORBITA_SESSION_SECRET")
    }

    /** Начать вход: шлюз выдаёт {token, deep_link} на общего бота. */
    fun start(): JsonNode = call(
        HttpRequest.newBuilder(URI.create("${url!!.trimEnd('/')}/start"))
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString("""{"product":"$product"}""")),
    )

    /** Опрос входа: {status: pending|approved|denied|expired, session?, name?}. */
    fun status(token: String): JsonNode = call(
        HttpRequest.newBuilder(
            URI.create("${url!!.trimEnd('/')}/status?token=" + URLEncoder.encode(token, Charsets.UTF_8)),
        ).GET(),
    )

    /**
     * Локальная проверка сессии шлюза: подпись (константное время), продукт
     * в теле, срок. Формат — `tg_authgw.session`: base64url(JSON{p,tid,name,iat}).base64url(HMAC).
     */
    fun verify(token: String?): Identity? {
        val ключ = secret ?: return null
        if (token.isNullOrBlank() || '.' !in token) return null
        val тело = token.substringBeforeLast('.')
        val подпись = token.substringAfterLast('.')
        val ожидаемая = sign(тело, ключ)
        if (!MessageDigest.isEqual(подпись.toByteArray(), ожидаемая.toByteArray())) return null
        val данные = runCatching { mapper.readTree(Base64.getUrlDecoder().decode(тело)) }.getOrNull() ?: return null
        if (данные.path("p").asText("") != product) return null
        if (now() - данные.path("iat").asLong(0) > SESSION_TTL) return null
        val tid = данные.path("tid").asLong(0)
        if (tid == 0L) return null
        return Identity(tid, данные.path("name").asText(""))
    }

    private fun call(builder: HttpRequest.Builder): JsonNode {
        val ответ = try {
            client.send(builder.timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString())
        } catch (e: java.io.IOException) {
            throw IllegalStateException("шлюз входа ($url) не отвечает: ${e.message}")
        }
        if (ответ.statusCode() >= 400) {
            throw IllegalStateException("шлюз входа ответил ${ответ.statusCode()}: ${ответ.body().take(200)}")
        }
        return mapper.readTree(ответ.body())
    }

    companion object {
        /** Срок сессии шлюза — 21 день, как у самого шлюза и у afisha. */
        const val SESSION_TTL: Long = 21L * 24 * 3600

        private fun env(имя: String): String? = System.getenv(имя)?.takeIf { it.isNotBlank() }

        private fun b64(байты: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(байты)

        private fun sign(тело: String, ключ: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(ключ.toByteArray(), "HmacSHA256"))
            return b64(mac.doFinal(тело.toByteArray()))
        }

        /** Выдача сессии — как у шлюза; в изделии не зовётся, нужна тестам и стенду без шлюза. */
        fun issue(product: String, secret: String, tid: Long, name: String, iat: Long, mapper: ObjectMapper = ObjectMapper()): String {
            val тело = b64(
                mapper.writeValueAsBytes(
                    linkedMapOf("p" to product, "tid" to tid, "name" to name, "iat" to iat),
                ),
            )
            return "$тело.${sign(тело, secret)}"
        }
    }
}
