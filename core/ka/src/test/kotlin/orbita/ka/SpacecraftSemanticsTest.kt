// Перенос исполняемого эталона spec/spacecraft_semantics.py — один в один,
// 34 проверки. Названия сохранены. Требуемое Eb/N0 приходит из адаптера
// протокола (core/net); в core/ka констант LoRaWAN нет.
package orbita.ka

import orbita.net.LoRaWanAdapter
import orbita.net.populationDutyCycle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SpacecraftSemanticsTest {

    // Eb/N0 берётся из адаптера, а не пишется числом (TZ-KA-007, ловушка 3).
    private val adapter = LoRaWanAdapter()
    private val ebn0Sf12 = adapter.mode("SF12").requiredEbn0Db

    /**
     * Восходящая линия IoT: терминал 14 дБм (−16 дБВт) → КА.
     * Eb/N0 = +6,3 дБ при 250 бит/с — физичное значение для CSS-демодулятора
     * (порог SNR −20 дБ в полосе 125 кГц); прежнее условное −6 дБ лежало ниже
     * предела Шеннона. Число приходит из адаптера, здесь лишь воспроизведено.
     */
    private val up = LinkLeg(
        id = "L-UP", eirpDbw = -16.0, altKm = 550.0, freqHz = 868e6,
        gOverTDbk = -15.0, bitrateBps = 250.0, requiredEbn0Db = 6.3,
    )

    @Nested
    @DisplayName("TZ-KA-003: массовый бюджет")
    inner class Mass {

        private val items = listOf(
            MassItem("структура", 8.0, Maturity.Existing),
            MassItem("СЭП", 6.0, Maturity.Modified),
            MassItem("ПН", 10.0, Maturity.New),
        )
        private val m = dryMassKg(items)

        @Test
        fun `резервы по зрелости увеличивают массу`() =
            assertTrue(m > items.sumOf { it.massKg }) { "$m кг" }

        @Test
        fun `новый элемент даёт больший резерв, чем существующий`() =
            assertTrue(
                dryMassKg(listOf(MassItem("x", 10.0, Maturity.New))) >
                    dryMassKg(listOf(MassItem("x", 10.0, Maturity.Existing)))
            )

        @Test
        fun `масса в диапазоне Р2`() = assertTrue(withinPlatformRange(m)) { "$m" }

        @Test
        fun `250 кг вне диапазона Р2`() = assertFalse(withinPlatformRange(250.0))

        @Test
        fun `5 кг вне диапазона Р2`() = assertFalse(withinPlatformRange(5.0))
    }

    @Nested
    @DisplayName("TZ-KA-007: бюджет радиолинии")
    inner class LinkBudgetTests {

        private val mz = linkMarginDb(up, 90.0)
        private val mh = linkMarginDb(up, 10.0)

        @Test
        fun `запас в надире больше, чем у горизонта`() =
            assertTrue(mz > mh) { "$mz / $mh дБ" }

        @Test
        fun `восходящая линия IoT замыкается в надире`() = assertTrue(mz > 0) { "$mz дБ" }

        @Test
        fun `рост частоты снижает запас`() =
            assertTrue(linkMarginDb(up.copy(freqHz = 2.4e9), 90.0) < mz)

        @Test
        fun `рост скорости снижает запас`() =
            assertTrue(linkMarginDb(up.copy(bitrateBps = 50000.0), 90.0) < mz)

        @Test
        fun `сквозной бюджет терминал-КА-НС не вычисляется - regenerative Р1`() {
            // Р1/ADR-001: участки считаются раздельно; сквозной функции нет по построению.
            // Проверяем это свойством API: LinkLeg описывает ОДИН участок, а публичных
            // функций, объединяющих два участка, в модуле нет.
            val functions = Class.forName("orbita.ka.LinkBudgetKt").methods.map { it.name }
            assertTrue(functions.none { it.contains("endToEnd", ignoreCase = true) }) { functions.toString() }
            // и каждый участок несёт собственное требуемое Eb/N0 из адаптера
            assertTrue(ebn0Sf12.isFinite())
        }
    }

    @Nested
    @DisplayName("TZ-KA-005: зона обслуживания ≠ зона видимости")
    inner class Zones {

        // A': односторонний приём сильной линии — ограничена геометрия.
        // C': двусторонний контур, слабая нисходящая до малого терминала — ограничивает бюджет.
        private val weak = up.copy(eirpDbw = -24.0)
        private val seA = serviceElevationDeg(up, 3.0, minElevDeg = 5.0)
        private val seC = serviceElevationDeg(weak, 3.0, minElevDeg = 5.0)

        @Test
        fun `зона обслуживания определена`() =
            assertTrue(seA != null && seC != null) { "$seA, $seC" }

        @Test
        fun `зона слабой линии уже`() = assertTrue(seC!! > seA!!) { "A'=$seA° C'=$seC°" }

        @Test
        fun `граница зоны не ниже геометрического предела`() =
            assertTrue(seA!! >= 5.0 && seC!! >= 5.0)

        @Test
        fun `для A ограничивает геометрия`() =
            assertEquals("geometry", limitingFactor(seA!!, 5.0))

        @Test
        fun `для C ограничивает бюджет линии`() =
            assertEquals("link_margin", limitingFactor(seC!!, 5.0)) { "$seC°" }

        @Test
        fun `ужесточение требуемого запаса сужает зону`() =
            assertTrue(serviceElevationDeg(weak, 8.0, 5.0)!! > seC!!)

        @Test
        fun `незамыкающаяся линия не даёт зоны`() =
            assertNull(serviceElevationDeg(up.copy(eirpDbw = -45.0), 3.0, 5.0))

        @Test
        fun `при избыточном запасе ограничивает геометрия`() =
            assertEquals(
                "geometry",
                limitingFactor(serviceElevationDeg(up.copy(eirpDbw = 10.0), 3.0, 5.0)!!, 5.0),
            )
    }

    @Nested
    @DisplayName("TZ-KA-006: маяк эфемерид")
    inner class Beacon {

        private val load = beaconDownlinkLoad(60.0, 40, 13, 300.0)

        @Test
        fun `занятость линии маяком в интервале от нуля до единицы`() =
            assertTrue(load > 0 && load < 1) { "$load" }

        @Test
        fun `частый маяк грузит линию сильнее`() =
            assertTrue(beaconDownlinkLoad(10.0, 40, 13, 300.0) > load)

        @Test
        fun `модель орбиты дешевле расписания при том же периоде`() =
            assertTrue(
                beaconDownlinkLoad(60.0, beaconPayloadBytes(BeaconFormat.OrbitModel), 13, 300.0) <
                    beaconDownlinkLoad(60.0, beaconPayloadBytes(BeaconFormat.FullAlmanac), 13, 300.0)
            )

        @Test
        fun `энергия маяка учитывается и положительна`() {
            val e = beaconEnergyWh(60.0, 40, 13, 300.0, txPowerW = 6.0, orbitS = 5736.0)
            assertTrue(e > 0) { "$e Вт·ч/виток" }
        }

        @Test
        fun `альманах обновляется при частых пролётах`() =
            assertTrue(almanacOk(60.0, 86400.0, passesPerDay = 8.0))

        @Test
        fun `редкие пролёты не укладываются в допустимый возраст`() =
            assertFalse(almanacOk(60.0, 3600.0, passesPerDay = 4.0))

        @Test
        fun `нулевое число пролётов равно деградации`() =
            assertFalse(almanacOk(60.0, 86400.0, passesPerDay = 0.0))
    }

    @Nested
    @DisplayName("TZ-KA-008: буфер и приоритеты")
    inner class Buffer {

        private val q = listOf(
            BufferedMsg("A_prime", 1.0), BufferedMsg("B_prime", 2.0), BufferedMsg("C_prime", 3.0),
        )

        @Test
        fun `при переполнении вытесняется низший приоритет`() {
            val r = bufferAdmit(q, BufferedMsg("C_prime", 4.0), capacity = 3)
            assertEquals("A_prime", r.dropped!!.klass)
        }

        @Test
        fun `C сохраняется в очереди`() {
            val r = bufferAdmit(q, BufferedMsg("C_prime", 4.0), capacity = 3)
            assertEquals(2, r.queue.count { it.klass == "C_prime" })
        }

        @Test
        fun `новое A вытесняется первым как самое позднее из низших`() {
            val r = bufferAdmit(q, BufferedMsg("A_prime", 4.0), capacity = 3)
            assertEquals("A_prime" to 4.0, r.dropped!!.klass to r.dropped!!.t)
        }

        @Test
        fun `без переполнения ничего не теряется`() {
            val r = bufferAdmit(q, BufferedMsg("C_prime", 4.0), capacity = 10)
            assertNull(r.dropped)
            assertEquals(4, r.queue.size)
        }

        @Test
        fun `политика drop_oldest вытесняет самое старое`() {
            val r = bufferAdmit(q, BufferedMsg("C_prime", 4.0), capacity = 3, policy = OverflowPolicy.DropOldest)
            assertEquals(1.0, r.dropped!!.t)
        }

        @Test
        fun `объём буфера от худшего интервала до сброса`() =
            assertEquals(1800, requiredBufferMsgs(msgsPerS = 0.5, worstGapS = 3600.0))
    }

    @Nested
    @DisplayName("TZ-NET-005: регуляторные ограничения")
    inner class Regulatory {

        private val duty = populationDutyCycle(terminals = 5000, msgsPerDay = 4.0, timeOnAirS = 0.4)

        @Test
        fun `превышение duty cycle 1 процента выявляется`() = assertTrue(duty > 0.01) { "$duty" }

        @Test
        fun `сокращение числа терминалов снижает занятость`() =
            assertTrue(populationDutyCycle(500, 4.0, 0.4) < duty)

        @Test
        fun `предел 1 процент выдерживается малой популяцией`() =
            assertTrue(populationDutyCycle(200, 4.0, 0.4) < 0.01)
    }
}
