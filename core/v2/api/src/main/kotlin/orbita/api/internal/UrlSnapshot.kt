// Снимок источника по ссылке (шип E п. 4, ИНТЕЛЛЕКТУАЛЬНАЯ-ЗАГРУЗКА §2).
//
// Два пути: рендер в Chromium (служба `snapshot`, ORBITA_SNAPSHOT_URL —
// контейнер профиля compose) и прямая выгрузка HTML без рендера. Страница
// на JS без рендера пуста — как страница Спутникс; в этом случае система
// ОТКАЗЫВАЕТ словами и предлагает PDF, а не кладёт пустой материал,
// который выглядел бы как «по ссылке ничего нет».
package orbita.api.internal

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate

/** Отказ снимка — с причиной и предложением, что прислать вместо ссылки. */
class SnapshotRefused(message: String) : IllegalArgumentException(message)

data class Snapshot(val text: String, val renderer: String, val date: String)

class UrlSnapshot(
    /** Адрес службы рендера (browserless: POST /content {url}); пусто — рендера нет. */
    private val renderUrl: String? = System.getenv("ORBITA_SNAPSHOT_URL")?.takeIf { it.isNotBlank() },
    private val mapper: ObjectMapper = ObjectMapper(),
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
) {
    fun fetch(ссылка: String): Snapshot {
        require(ссылка.startsWith("http://") || ссылка.startsWith("https://")) {
            "ссылка должна начинаться с http:// или https://"
        }
        val сегодня = LocalDate.now().toString()
        val сырой = скачать(ссылка)
        val текст = безРазметки(сырой)
        if (!безРендера(сырой, текст)) return Snapshot(текст, "без рендера (HTML как есть)", сегодня)
        // Страница на JS: без браузера в ней нет текста. Есть служба — рендерим.
        val адрес = renderUrl ?: throw SnapshotRefused(
            "страница «$ссылка» без рендера пуста (приложение на JS), а служба снимка Chromium не поднята " +
                "(ORBITA_SNAPSHOT_URL). Пришлите PDF страницы или вставьте текст — либо поднимите профиль snapshot",
        )
        val отрисовано = безРазметки(отрисовать(адрес, ссылка))
        if (отрисовано.length < МИНИМУМ_ТЕКСТА) throw SnapshotRefused(
            "страница «$ссылка» и после рендера в Chromium почти пуста (${отрисовано.length} знаков) — " +
                "пришлите PDF или текст: факты без текста не существуют",
        )
        return Snapshot(отрисовано, "Chromium ($адрес)", сегодня)
    }

    private fun скачать(ссылка: String): String {
        val запрос = HttpRequest.newBuilder(URI.create(ссылка))
            .timeout(Duration.ofSeconds(25))
            // Заголовок — только ASCII: HttpClient отвергает кириллицу в нём.
            .header("User-Agent", "Orbita/2 knowledge-field")
            .GET().build()
        val ответ = try {
            client.send(запрос, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw IllegalArgumentException("по ссылке не удалось получить страницу: ${e.message ?: e::class.simpleName}")
        }
        require(ответ.statusCode() in 200..299) { "по ссылке ответ ${ответ.statusCode()} — страницы нет" }
        require(ответ.body().length <= ПРЕДЕЛ_БАЙТ) { "страница больше двух мегабайт — приложите файлом" }
        return ответ.body()
    }

    /** Рендер службой: browserless `POST /content` отдаёт HTML после исполнения JS. */
    private fun отрисовать(адрес: String, ссылка: String): String {
        val тело = mapper.createObjectNode().put("url", ссылка).toString()
        val запрос = HttpRequest.newBuilder(URI.create(адрес.trimEnd('/') + "/content"))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(тело)).build()
        val ответ = try {
            client.send(запрос, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw SnapshotRefused("служба снимка не ответила (${e.message ?: e::class.simpleName}) — пришлите PDF страницы")
        }
        if (ответ.statusCode() !in 200..299) throw SnapshotRefused("служба снимка ответила ${ответ.statusCode()} — пришлите PDF страницы")
        return ответ.body()
    }

    companion object {
        const val ПРЕДЕЛ_БАЙТ: Int = 2_000_000
        const val МИНИМУМ_ТЕКСТА: Int = 300

        /** Разметка снята, скрипты и стили выброшены, пробелы схлопнуты. */
        fun безРазметки(html: String): String = html
            .replace(Regex("(?is)<(script|style|noscript)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>|</h[1-6]>|</tr>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .lines().map { it.trim().replace(Regex("[ \\t]+"), " ") }.filter { it.isNotBlank() }.joinToString("\n")

        /** Страница без рендера: текста почти нет, а каркас приложения есть. */
        fun безРендера(html: String, текст: String): Boolean {
            if (текст.length >= МИНИМУМ_ТЕКСТА) return false
            val каркас = Regex("(?i)id=\"(root|app|__next|__nuxt)\"|__NEXT_DATA__|ng-version|data-reactroot").containsMatchIn(html)
            return каркас || текст.length < МИНИМУМ_ТЕКСТА
        }
    }
}
