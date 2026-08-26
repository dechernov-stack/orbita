// Перенос spec/presentation_semantics.py в тесты, один в один: 46 проверок.
// Часть эталона относится к core/bal (роза KPI, Парето, тепловая карта) —
// функции вызываются оттуда, второй реализации нормировки в проекте нет.
package orbita.out

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.bal.HeatCell
import orbita.bal.Horizon
import orbita.bal.RadarOption
import orbita.bal.UnknownAxisException
import orbita.bal.VizData
import orbita.bal.normalizeAxis
import orbita.bal.paretoFrontByAxes
import orbita.bal.radarSeries
import orbita.mod.DemoModel
import orbita.mod.store.Link
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.abs

class PresentationSemanticsTest {

    private val mapper = ObjectMapper()

    private val options = listOf(
        RadarOption(
            "Walker 40/5",
            mapOf(
                "quality" to 0.82, "cost" to 100.0, "reliability" to 0.9,
                "energy" to 68.0, "deployment_days" to 120.0, "launch_campaigns" to 1.0,
            ),
        ),
        RadarOption(
            "Walker 24/3",
            mapOf(
                "quality" to 0.61, "cost" to 62.0, "reliability" to 0.7,
                "energy" to 71.0, "deployment_days" to 90.0, "launch_campaigns" to 1.0,
            ),
        ),
        RadarOption(
            "ССО 30/3",
            mapOf(
                "quality" to 0.55, "cost" to 78.0, "reliability" to 0.8,
                "energy" to 80.0, "deployment_days" to 200.0, "launch_campaigns" to 2.0,
            ),
        ),
    )

    @Nested
    @DisplayName("Роза KPI: нормировка с учётом направления")
    inner class Normalization {

        private val quality = normalizeAxis(options.map { it.values.getValue("quality") }, "quality")
        private val cost = normalizeAxis(options.map { it.values.getValue("cost") }, "cost")

        @Test
        fun `лучшее качество даёт 1_0`() {
            assertEquals(1.0, quality.max(), 1e-12)
            assertEquals(0, quality.indexOf(quality.max()))
        }

        @Test
        fun `наименьшая стоимость даёт 1_0`() {
            assertEquals(1.0, cost.max(), 1e-12)
            assertEquals(1, cost.indexOf(cost.max()))
        }

        @Test
        fun `дорогой вариант не выглядит хорошим`() {
            assertTrue(cost[0] < cost[1], "${cost[0]} vs ${cost[1]}")
        }

        @Test
        fun `вырожденный случай не делит на ноль`() {
            assertEquals(listOf(1.0, 1.0, 1.0), normalizeAxis(listOf(5.0, 5.0, 5.0), "cost"))
        }

        @Test
        fun `показатель без направления отклонён`() {
            assertThrows<UnknownAxisException> { normalizeAxis(listOf(1.0, 2.0), "unknown_axis") }
        }
    }

    @Nested
    @DisplayName("Роза KPI: сопоставимость диаграмм")
    inner class Comparability {

        private val axes = listOf("quality", "cost", "reliability")
        private val three = radarSeries(options, axes)
        private val two = radarSeries(options.take(2), axes)

        @Test
        fun `значения относительны сравниваемому набору`() {
            assertTrue(three.normalizedOver != two.normalizedOver)
        }

        @Test
        fun `диаграммы разных наборов несопоставимы`() {
            assertFalse(three.comparableWith(two))
        }

        @Test
        fun `та же диаграмма сопоставима сама с собой`() {
            assertTrue(three.comparableWith(radarSeries(options, axes)))
        }

        // Нормировка зависит от границ набора: удаление крайнего варианта
        // смещает значения промежуточных, а значения самих крайних не меняет.
        @Test
        fun `удаление крайнего варианта меняет значение промежуточного`() {
            assertTrue(
                abs(three.series[1].values[0] - two.series[1].values[0]) > 1e-9,
                "${three.series[1].values[0]} → ${two.series[1].values[0]}",
            )
        }

        @Test
        fun `значение крайнего варианта при этом не меняется`() {
            assertEquals(three.series[0].values[0], two.series[0].values[0], 1e-12)
        }

        @Test
        fun `диаграмма несёт состав набора, по которому нормирована`() {
            assertEquals(3, three.normalizedOver.size)
            // Наружу состав уходит той же сериализацией, что и на экране сравнения.
            assertTrue(mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(three).has("normalizedOver"))
        }

        @Test
        fun `число осей совпадает с числом значений`() {
            assertTrue(three.series.all { it.values.size == three.axes.size })
        }
    }

    @Nested
    @DisplayName("Парето для отображения")
    inner class Pareto {

        @Test
        fun `недоминируемые определены`() {
            assertEquals(listOf("Walker 24/3", "Walker 40/5"), paretoFrontByAxes(options))
        }

        @Test
        fun `доминируемый вариант исключён`() {
            assertFalse("ССО 30/3" in paretoFrontByAxes(options))
        }

        @Test
        fun `единственный вариант доминируем быть не может`() {
            assertEquals(listOf("Walker 40/5"), paretoFrontByAxes(options.take(1)))
        }
    }

    @Nested
    @DisplayName("Полосы бюджета")
    inner class Budget {

        private val fits = budgetSegments(
            100.0, listOf(BudgetSegment("Платформа", 60.0), BudgetSegment("ПН", 30.0)),
        )
        private val over = budgetSegments(
            100.0, listOf(BudgetSegment("Платформа", 60.0), BudgetSegment("ПН", 52.0)),
        )

        @Test
        fun `сегменты дополнены резервом`() {
            assertTrue(fits.segments.last().reserve)
        }

        @Test
        fun `сумма сегментов равна пределу`() {
            assertEquals(100.0, fits.segments.sumOf { it.value }, 1e-12)
        }

        @Test
        fun `остаток вычислен`() {
            assertEquals(10.0, fits.remaining, 1e-12)
            assertFalse(fits.overrun)
        }

        @Test
        fun `превышение помечено, а не обрезано`() {
            assertTrue(over.overrun)
            assertEquals(12.0, over.overrunValue!!, 1e-12)
        }

        @Test
        fun `при превышении резерв не добавляется`() {
            assertTrue(over.segments.none { it.reserve })
        }

        @Test
        fun `остаток при превышении отрицателен`() {
            assertEquals(-12.0, over.remaining, 1e-12)
        }
    }

    @Nested
    @DisplayName("Дерево из связей")
    inner class Trees {

        private val nodes = listOf("RQ-0100", "RQ-0101", "RQ-0102", "RQ-0110")
        private val links = listOf(
            Link("RQ-0100", "RQ-0101", "derive", null),
            Link("RQ-0100", "RQ-0102", "derive", null),
            Link("RQ-0100", "RQ-0110", "trace", null),
        )
        private val tree = buildTree(nodes, links)

        @Test
        fun `корни определены`() {
            assertEquals(listOf("RQ-0100", "RQ-0110"), tree.roots)
        }

        @Test
        fun `потомки только по связи derive`() {
            assertEquals(listOf("RQ-0101", "RQ-0102"), tree.children.getValue("RQ-0100"))
        }

        @Test
        fun `связь trace деревом не является`() {
            assertTrue("RQ-0110" in tree.roots)
        }

        @Test
        fun `глубина потомка равна 1`() {
            assertEquals(1, tree.depthOf("RQ-0101"))
        }

        @Test
        fun `цикла нет`() {
            assertFalse(treeCycle(links))
        }

        @Test
        fun `цикл выявляется`() {
            assertTrue(treeCycle(links + Link("RQ-0101", "RQ-0100", "derive", null)))
        }
    }

    @Nested
    @DisplayName("Тепловая карта: три горизонта")
    inner class Heatmap {

        private val cells = listOf(HeatCell("c1", listOf(1.0, 1.0, 0.0, 0.0)))
        // пик активности приходится на провал покрытия
        private val peakAtGap = listOf(0.2, 0.2, 3.0, 3.0)

        private val instant = VizData.availability(cells, Horizon.Instant)
        private val period = VizData.availability(cells, Horizon.Period)
        private val daily = VizData.availability(cells, Horizon.Daily, peakAtGap)

        @Test
        fun `мгновенный срез берёт первое значение`() {
            assertEquals(1.0, instant.getValue("c1"))
        }

        @Test
        fun `среднее за период не взвешено`() {
            assertEquals(0.5, period.getValue("c1"), 1e-12)
        }

        @Test
        fun `взвешивание активностью занижает доступность`() {
            assertTrue(
                daily.getValue("c1") < period.getValue("c1"),
                "${daily.getValue("c1")} < ${period.getValue("c1")}",
            )
        }

        @Test
        fun `невзвешенное среднее завысило бы доступность`() {
            assertTrue(period.getValue("c1") > daily.getValue("c1"))
        }

        @Test
        fun `неизвестный горизонт отклонён`() {
            assertThrows<IllegalArgumentException> { Horizon.of("weekly") }
        }
    }

    @Nested
    @DisplayName("Генерация документов")
    inner class Documents {

        private val generator = DocumentGenerator(mapper)
        private val model = mapper.readTree(
            """{"requirements":[{"id":"RQ-0100","statement":"Масса КА","category":"performance",
               "lifecycle":{"status":"Draft"}}],"needs":[],"components":[]}""",
        )

        @Test
        fun `повторная генерация идентична`() {
            assertEquals(
                generator.render(model, SeedTemplates.of("req_spec")).digest,
                generator.render(model, SeedTemplates.of("req_spec")).digest,
            )
        }

        @Test
        fun `генерация не изменяет модель`() {
            val snapshot = model.toString()
            generator.render(model, SeedTemplates.of("req_spec"))
            assertEquals(snapshot, model.toString())
        }

        @Test
        fun `другой шаблон даёт другой результат`() {
            assertTrue(
                generator.render(model, SeedTemplates.of("conops")).digest !=
                    generator.render(model, SeedTemplates.of("req_spec")).digest,
            )
        }

        // ---------- Структура разделов по приложениям регламента (шаг 11.1) ----------

        /** Демо-проект целиком: документ проверяется на настоящей модели, а не на образце. */
        private val demo = DemoModel.load()

        @Test
        fun `состав разделов спецификации совпадает с приложением 2`() {
            val doc = generator.render(demo, SeedTemplates.of("req_spec"))
            assertEquals("БП-PA, Приложение 2", doc.body.path("source").asText())
            assertEquals(
                listOf(
                    "Введение", "Требования уровня проекта", "Системные требования",
                    "Матрица трассировки", "Матрица верификации",
                ),
                doc.body.path("sections").map { it.path("title").asText() },
            )
            assertEquals(listOf(1, 2, 3, 4, 5), doc.body.path("sections").map { it.path("number").asInt() })
        }

        @Test
        fun `состав разделов ConOps совпадает с приложением 3`() {
            val doc = generator.render(demo, SeedTemplates.of("conops"))
            assertEquals("БП-PA, Приложение 3", doc.body.path("source").asText())
            assertEquals(7, doc.body.path("sections").size())
            assertEquals("Валидационные положения", doc.body.path("sections")[6].path("title").asText())
        }

        @Test
        fun `состав разделов описания архитектуры совпадает с приложением 4`() {
            val doc = generator.render(demo, SeedTemplates.of("architecture"))
            assertEquals("БП-PA, Приложение 4", doc.body.path("source").asText())
            assertEquals(7, doc.body.path("sections").size())
            assertEquals("Бюджеты", doc.body.path("sections")[6].path("title").asText())
        }

        /**
         * Раздел, который модель заполнить не может, ОСТАЁТСЯ в документе.
         * Выброшенный раздел делает документ на вид полным: читатель не отличит
         * «персонал не описан» от «персонал не нужен».
         */
        @Test
        fun `пустой раздел остаётся на месте и назван разрывом`() {
            val doc = generator.render(demo, SeedTemplates.of("conops"))
            val staffing = doc.body.path("sections").first { it.path("number").asInt() == 6 }
            assertEquals("Персонал и обеспечение", staffing.path("title").asText())
            assertTrue(staffing.path("items").isEmpty)
            val gap = doc.gaps.first { it.section == 6 }
            assertEquals("раздел пуст", gap.what)
            // разрыв несёт слова регламента, а не только номер раздела
            assertTrue(gap.expected.contains("Состав смен")) { gap.expected }
        }

        @Test
        fun `заполненный раздел разрывом не считается`() {
            val doc = generator.render(demo, SeedTemplates.of("conops"))
            assertTrue(doc.gaps.none { it.section == 7 && it.what == "раздел пуст" })
            assertTrue(doc.body.path("sections")[6].path("items").size() > 0)
        }

        /** Приложение 2 перечисляет атрибуты записи; отсутствие любого — разрыв. */
        @Test
        fun `требование без обоснования попадает в разрывы спецификации`() {
            val doc = generator.render(demo, SeedTemplates.of("req_spec"))
            val missing = doc.gaps.filter { it.what.contains("обоснование") }
            assertTrue(missing.isNotEmpty(), "разрыв по обоснованию не найден")
            assertTrue(missing.all { it.what.startsWith("RQ-") }) { missing.toString() }
        }

        @Test
        fun `запись требования несёт все атрибуты приложения 2`() {
            val doc = generator.render(demo, SeedTemplates.of("req_spec"))
            val record = doc.body.path("sections").first { it.path("number").asInt() == 3 }
                .path("items").first { it.path("id").asText() == "RQ-0100" }
            listOf(
                "id", "statement", "category", "source", "rationale",
                "mop", "verification_method", "status", "version", "owner",
            ).forEach { assertTrue(record.has(it)) { "нет поля $it: $record" } }
            assertEquals("RQ-0100", record.path("id").asText())
            assertEquals("вед. системный инженер", record.path("owner").asText())
            assertEquals("ND-0003", record.path("source").asText())
        }

        /** Строка матрицы верификации — на СОБЫТИЕ, а не на требование. */
        @Test
        fun `матрица верификации даёт строку на каждое событие`() {
            val doc = generator.render(demo, SeedTemplates.of("req_spec"))
            val rows = doc.body.path("sections").first { it.path("number").asInt() == 5 }.path("items")
            val events = demo.path("requirements").sumOf { it.path("verification_events").size() }
            assertEquals(events, rows.size())
            assertTrue(rows.all { it.path("phase").asText().isNotBlank() }) { "этап не указан" }
        }

        @Test
        fun `раздел обоснования архитектуры собран из сравнения вариантов`() {
            val doc = generator.render(demo, SeedTemplates.of("architecture"))
            val rationale = doc.body.path("sections").first { it.path("number").asInt() == 6 }
            assertEquals(demo.path("options").size(), rationale.path("items").size())
            assertTrue(rationale.path("items")[0].has("quality"))
        }

        @Test
        fun `на настоящей модели генерация тоже воспроизводима и не меняет модель`() {
            val snapshot = demo.toString()
            val first = generator.render(demo, SeedTemplates.of("architecture"))
            val second = generator.render(demo, SeedTemplates.of("architecture"))
            assertEquals(first.digest, second.digest)
            assertEquals(snapshot, demo.toString())
        }

        @Test
        fun `неизвестный шаблон отклонён`() {
            // «semp» был примером несуществующего, пока блок C его не завёл
            assertThrows<IllegalArgumentException> { SeedTemplates.of("no_such_template") }
        }
    }

    @Nested
    @DisplayName("Матрица верификации")
    inner class Matrix {

        private val requirements = mapper.readTree(
            """[
              {"id":"RQ-0100","verification_events":[
                {"id":"VE-0001","method":"analysis","level":"system","closes":false,
                 "approach":"Суммирование масс по MEL","status":"passed",
                 "evidence_ref":"EV-0001","evidence_stale":true},
                {"id":"VE-0002","method":"test","level":"system","closes":true,
                 "approach":"Взвешивание после интеграции","status":"planned"}]},
              {"id":"RQ-0200","verification_events":[
                {"id":"VE-0003","method":"inspection","level":"component",
                 "closes":true,"status":"planned"}]},
              {"id":"RQ-0300"}
            ]""",
        ).toList()

        private val view = verificationMatrixView(requirements)

        @Test
        fun `строка на событие, а не на требование`() {
            assertEquals(3, view.rows.size)
        }

        @Test
        fun `требование без событий попадает в разрывы`() {
            assertTrue(view.gaps.any { it.requirementId == "RQ-0300" })
        }

        @Test
        fun `событие без подхода попадает в разрывы`() {
            assertTrue(view.gaps.any { it.eventId == "VE-0003" })
        }

        @Test
        fun `устаревшее свидетельство помечено в строке`() {
            assertTrue(view.rows.any { it.evidenceStale })
        }

        @Test
        fun `ячейки не заполняются вручную`() {
            // подход есть в каждой строке как поле, пусть и пустое: пустая ячейка
            // означает разрыв, а не отсутствие колонки
            assertTrue(view.rows.all { it.approach.isNotEmpty() || view.gaps.any { g -> g.eventId == it.eventId } })
        }
    }

    @Nested
    @DisplayName("Экспорт и обмен")
    inner class Exchange {

        private val requirements = listOf(
            ExchangeRequirement(
                "RQ-0100",
                mapOf(
                    "statement" to mapper.readTree("\"a\""),
                    "operator" to mapper.readTree("\"le\""),
                    "value" to mapper.readTree("100"),
                ),
            ),
        )
        private val links = listOf(Link("ND-0001", "RQ-0100", "trace", null))

        // Обратное направление формата reqif-lite убрано (Шаг 16 §2.1): ввод идёт
        // настоящим ReqIF через службу обмена, круговой обмен проверяется на нём
        // (tools/check_reqif_roundtrip.py). Здесь проверяется выгрузка.
        @Test
        fun `атрибуты сохраняются при выгрузке`() {
            assertEquals(requirements, toExchange(requirements, links).requirements)
        }

        @Test
        fun `связи сохраняются при выгрузке`() {
            assertEquals(links, toExchange(requirements, links).links)
        }

        @Test
        fun `незнакомый атрибут не теряется молча`() {
            val custom = listOf(ExchangeRequirement("R", mapOf("custom_x" to mapper.readTree("7"))))
            val json = exchangeToJson(toExchange(custom, emptyList()), mapper)
            assertEquals(7, json["requirements"][0]["attributes"]["custom_x"].asInt())
        }
    }

    @Nested
    @DisplayName("Пакет передачи")
    inner class Transfer {

        private val full = mapper.readTree(
            """{"requirements":[{"id":"RQ-0100","status":"Baseline"},{"id":"RQ-0101","status":"Draft"}],
               "architecture":[],"parameters":[],"verification_matrix":[],"modeling_reports":[]}""",
        )

        @Test
        fun `полный пакет собран`() {
            assertTrue(transferPackage(full).complete)
        }

        @Test
        fun `небазированное перечислено предупреждением`() {
            assertEquals(listOf("RQ-0101"), transferPackage(full).warnings)
        }

        @Test
        fun `неполный пакет выявлен`() {
            val part = transferPackage(mapper.readTree("""{"requirements":[]}"""))
            assertFalse(part.complete)
            assertTrue("architecture" in part.missing)
        }
    }
}
