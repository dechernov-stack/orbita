// Циклограмма из географических масок по HTTP (TZ-KA-009).
//
// Маски строятся из ХРАНИМЫХ карты спроса и станций демо-проекта, доли —
// по настоящей трассе Orekit. Ответ кладёт рядом ручные доли из модели:
// подстановка сгенерированных — решение инженера, не автоматика.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.schema.SchemaRegistry
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MaskScheduleApiTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val server = HttpApi(boundary).start(0)
    private val base = "http://127.0.0.1:${server.address.port}/api"
    private val client = HttpClient.newHttpClient()

    @BeforeAll
    fun seed() {
        TestDb.truncateAll()
        DemoProject.seed(boundary)
    }

    @AfterAll
    fun stop() = server.stop(0)

    private fun get(path: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI.create("$base$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    @DisplayName("TZ-KA-009: доли витка генерируются из хранимых карты и станций")
    fun `циклограмма генерируется из масок`() {
        // короткий горизонт: тесту нужна форма ответа, а не суточная статистика
        val r = get("/views/spacecraft/mask-schedule?duration_s=5700")
        assertEquals(200, r.statusCode())
        val body = mapper.readTree(r.body())

        assertEquals(16, body.path("mask_version").asText().length)
        assertTrue(body.path("rx_cells").asInt() > 1000) { "ячеек спроса: ${body.path("rx_cells")}" }
        assertEquals(3, body.path("downlink_cells").asInt())

        val generated = body.path("generated_orbit_fractions")
        val sum = listOf("standby", "rx", "downlink").sumOf { generated.path(it).asDouble() }
        assertEquals(1.0, sum, 1e-9)

        // доли модели ПЕРЕНЕСЕНЫ из масок (решение владельца продукта):
        // модель и генератор обязаны совпадать, пока карта и станции те же.
        // Эндпоинт здесь считает по короткой трассе, а модель заполнена
        // по суточной — сверка по точным числам идёт отдельным тестом ниже.
        val manual = body.path("model_orbit_fractions")
        val manualSum = listOf("standby", "rx", "downlink").sumOf { manual.path(it).asDouble() }
        assertEquals(1.0, manualSum, 1e-9)
        assertTrue(manual.path("rx").asDouble() > manual.path("downlink").asDouble()) {
            "география демо-проекта даёт приёму больше витка, чем сбросу: $manual"
        }
    }

    /**
     * Доли модели совпадают со сгенерированными на том же горизонте:
     * заполнение демо-проекта и эндпоинт идут одним путём (TZ-KA-009),
     * второй копии чисел нет.
     */
    @Test
    @DisplayName("TZ-KA-009: доли модели совпадают с генерацией на суточной трассе")
    fun `доли модели совпадают с генерацией`() {
        val r = get("/views/spacecraft/mask-schedule?duration_s=86400")
        assertEquals(200, r.statusCode())
        val body = mapper.readTree(r.body())
        val generated = body.path("generated_orbit_fractions")
        val manual = body.path("model_orbit_fractions")
        listOf("standby", "rx", "downlink").forEach { mode ->
            assertEquals(generated.path(mode).asDouble(), manual.path(mode).asDouble(), 1e-12) {
                "режим $mode: модель разошлась с маской"
            }
        }
    }
}
