// Свёртки с резервами (ГЛУБИНА-КОМПОНЕНТА §2, ШАБЛОН-МОДЕЛИ М6).
//
// Резерва ДВА, и они разные: по классу зрелости КАЖДОГО параметра
// (оценка ±20 %, серийное ±5 %) и системный по ступени (MCR 20 % →
// PDR 5 %). Складывать их в одно число нельзя: инженер должен видеть,
// сколько он заложил на незрелость данных, а сколько — на незнание
// системы. Поэтому печатаются оба.
package orbita.models.internal

import orbita.kernel.api.Area
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.models.api.BudgetLine
import orbita.models.api.BudgetView

internal class Budgets(
    private val store: EntityStore,
    private val полкаМоделей: Entity?,
) {

    /** Какой ключ анкеты сворачивается каким видом бюджета. */
    private val ключиБюджета = mapOf(
        "mass" to setOf("mass_dry", "mass", "mass_wet"),
        "power" to setOf("power_avg", "power_peak", "power"),
        "compute" to setOf("cpu_load", "ram", "rom"),
        "data" to setOf("tm_rate", "tc_rate", "data_rate"),
        "energy" to setOf("energy", "battery_wh"),
    )

    /** Системный резерв по ступени — данные полки шаблонов компонентов. */
    private fun системный(gate: String): Int {
        val полка = store.list(Area.Library, "component_template_shelf").maxByOrNull { it.version }
        return полка?.doc?.path("system_margin_by_gate")?.path(gate.uppercase())?.asInt(0) ?: 0
    }

    private fun резервКласса(класс: String): Int {
        val полка = store.list(Area.Library, "component_template_shelf").maxByOrNull { it.version }
        val изПолки = полка?.doc?.path("maturity_classes")?.path(класс)?.asInt(-1) ?: -1
        return if (изПолки >= 0) изПолки else when (класс) {
            "measured" -> 2
            "off_the_shelf" -> 5
            "modified" -> 10
            else -> 20
        }
    }

    fun свёртка(project: String, kind: String, gate: String): BudgetView {
        val область = Area.Project(project)
        val ключи = ключиБюджета[kind] ?: emptySet()
        val строки = store.list(область, "parameter")
            .filter { ключи.isEmpty() || it.doc.path("key").asText() in ключи }
            .mapNotNull { параметр ->
                val значение = параметр.doc.path("measure").path("value")
                if (!значение.isNumber) return@mapNotNull null
                val класс = параметр.doc.path("maturity_class").asText("estimated")
                BudgetLine(
                    component = store.byId(параметр.doc.path("target").asText())?.code ?: "—",
                    value = значение.asDouble(),
                    unit = параметр.doc.path("measure").path("unit").asText(""),
                    maturity = класс,
                    classReserve = резервКласса(класс),
                    origin = параметр.doc.path("origin").asText("manual"),
                )
            }

        val сумма = строки.sumOf { it.value }
        val сКлассом = строки.sumOf { it.value * (1 + it.classReserve / 100.0) }
        val системныйПроцент = системный(gate)
        val сСистемным = сКлассом * (1 + системныйПроцент / 100.0)

        return BudgetView(
            kind = kind,
            gate = gate.uppercase(),
            lines = строки.sortedByDescending { it.value },
            sum = округлить(сумма),
            unit = строки.firstOrNull()?.unit ?: "",
            withClassReserve = округлить(сКлассом),
            systemMarginPercent = системныйПроцент,
            withSystemMargin = округлить(сСистемным),
            note = if (строки.isEmpty()) {
                "свёртка пуста: параметров вида «$kind» в проекте нет — заполните анкеты узлов"
            } else {
                "резерв по классам зрелости и системный резерв ступени ${gate.uppercase()} " +
                    "($системныйПроцент %) печатаются раздельно: это разные вещи"
            },
        )
    }

    private fun округлить(x: Double) = Math.round(x * 1000.0) / 1000.0
}
