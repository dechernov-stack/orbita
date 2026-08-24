// Срез модели для документов и пакета передачи (TZ-OUT-001, TZ-OUT-006; шаг 11).
//
// ЗАЧЕМ ОТДЕЛЬНЫЙ СРЕЗ. Генерация документа — чистая функция значения модели
// (эталон spec/presentation_semantics.py). Значение должно откуда-то взяться,
// и взяться оно обязано из ПОЛНОЙ модели, а не из того подмножества, которое
// оказалось под рукой у вызывающего.
//
// Разница не косметическая. Раздел «Режимы и состояния» пуст в одном из двух
// случаев: либо в модели нет ни одного режима, либо режимы есть, но вызывающий
// их не передал. Первое — находка, о которой надо доложить на обзоре; второе —
// дефект сборщика документа. По самому документу они неразличимы, поэтому
// разрешаются здесь: срез собирается из хранилища целиком и одинаково для всех
// потребителей.
package orbita.out

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.mod.model.CoreType
import orbita.mod.model.Lifecycle
import orbita.mod.store.ObjectStore
import orbita.mod.store.StoredObject

/**
 * Типы контура требований в срезе: имя типа хранилища → поле среза.
 * Множественное число полей — то, как их называет генератор документов.
 */
private val COLLECTIONS = linkedMapOf(
    CoreType.Need.dbType to "needs",
    CoreType.Service.dbType to "services",
    CoreType.Requirement.dbType to "requirements",
    CoreType.Evidence.dbType to "evidence",
    CoreType.Validation.dbType to "validations",
    CoreType.Risk.dbType to "risks",
    CoreType.Scenario.dbType to "scenarios",
    // Блок C (Шаг 17): сценарии ConOps, технологии и решения — материал документов
    CoreType.Conops.dbType to "conops_scenarios",
    CoreType.Technology.dbType to "technologies",
    CoreType.Decision.dbType to "decisions",
    // Блок C задания «прогон до KDP B»: материал точек
    CoreType.MissionGoal.dbType to "mission_goals",
    CoreType.Alternative.dbType to "alternatives",
    CoreType.CostEstimate.dbType to "cost_estimates",
    CoreType.Oda.dbType to "oda_assessments",
    CoreType.ReviewItem.dbType to "review_items",
    CoreType.WbsElement.dbType to "wbs_elements",
)

/**
 * Входы моделирования — по одному объекту на вид (ADR-021). В срезе они лежат
 * под своим именем в единственном числе: документ спрашивает «какая
 * группировка», а не «какие группировки».
 */
private val SINGLETONS = linkedMapOf(
    // имя типа берётся у самого типа (dbType), а не выводится из имени
    // константы: `GroundStations` в базе — `ground_stations`, и вывод
    // приведением регистра дал бы `groundstations`, то есть тихую пустоту
    CoreType.Constellation.dbType to "constellation",
    CoreType.Spacecraft.dbType to "spacecraft",
    CoreType.DemandMap.dbType to "demand_map",
    CoreType.TerminalProfile.dbType to "terminal_profile",
    CoreType.GroundStations.dbType to "ground_stations",
    CoreType.ProtocolAdapter.dbType to "protocol_adapter",
)

object ModelSnapshot {

    /**
     * Срез текущего состояния модели. Отменённые объекты не входят: документ
     * описывает проект, а не его историю.
     *
     * Элементы и интерфейсы кладутся в `components` СЛОВАРЁМ идентификатор →
     * документ: так их отдаёт эталон демо-проекта, и генератор принимает обе
     * формы — но иметь две формы в одном проекте незачем.
     */
    fun of(
        objects: ObjectStore,
        mapper: ObjectMapper = ObjectMapper(),
        options: List<JsonNode> = emptyList(),
        budgets: List<JsonNode> = emptyList(),
        projectId: String? = null,
    ): ObjectNode {
        val current = objects.listCurrent(projectId).filter { it.status != Lifecycle.Cancelled }
        val model = mapper.createObjectNode()

        for ((type, field) in COLLECTIONS) {
            val arr = model.putArray(field)
            current.filter { it.type == type }.sortedBy { it.id }.forEach { arr.add(withLifecycle(it, mapper)) }
        }

        val components = model.putObject("components")
        current.filter { it.type == CoreType.Component.dbType || it.type == CoreType.Interface.dbType }
            .sortedBy { it.id }
            .forEach { components.set<ObjectNode>(it.id, withLifecycle(it, mapper)) }

        for ((type, field) in SINGLETONS) {
            // Видов входов по одному на проект, но хранилище этого не обещает.
            // Берётся первый по идентификатору — устойчиво и предсказуемо.
            current.firstOrNull { it.type == type }?.let { model.set<ObjectNode>(field, withLifecycle(it, mapper)) }
        }

        // Сам проект-контейнер (ADR-022): «Введение» документов и их шапки
        // читают его назначение, область и применимые документы. Без него
        // раздел §1 оставался пустым при любом действии инженера.
        current.firstOrNull { it.type == CoreType.Project.dbType }
            ?.let { model.set<ObjectNode>("project", withLifecycle(it, mapper)) }

        // Варианты сравнения живут в результатах моделирования, а не в объектах:
        // вызывающий достаёт их из ResultStore и передаёт сюда.
        val opts = model.putArray("options")
        options.forEach(opts::add)

        // Бюджеты СЧИТАЮТСЯ (SpacecraftViews), а не хранятся, поэтому приходят
        // готовыми снаружи: срез — это значение модели, а не место расчёта.
        val budget = model.putArray("budgets")
        budgets.forEach(budget::add)
        return model
    }

    /**
     * Бюджеты аппарата для раздела «Бюджеты» описания архитектуры
     * (БП-PA, Приложение 4, раздел 7) из посчитанного экрана 5.
     *
     * Резерв показывается ОТДЕЛЬНОЙ величиной, а не растворяется в итоге:
     * «85 кг из 100» и «85 кг из 100, из них 8 кг резерва по зрелости» —
     * два разных сообщения, и на обзоре спрашивают именно второе.
     */
    fun budgetsOf(view: SpacecraftView, mapper: ObjectMapper = ObjectMapper()): List<ObjectNode> {
        val mass = mapper.createObjectNode()
        mass.put("kind", "mass")
        mass.put("unit", "kg")
        mass.put("nominal", view.mass.nominalKg)
        mass.put("system_margin_pct", view.mass.systemMarginPct)
        mass.put("dry", view.mass.dryMassKg)
        mass.put("wet", view.mass.wetMassKg)
        mass.put("reserve", view.mass.dryMassKg - view.mass.nominalKg)
        mass.put("within_platform_range", view.mass.withinPlatformRange)

        val power = mapper.createObjectNode()
        power.put("kind", "power")
        power.put("unit", "Wh")
        power.put("generated", view.power.generatedWh)
        power.put("consumed", view.power.consumedWh)
        power.put("reserve", view.power.balanceWh)
        power.put("planned_payload_duty", view.power.plannedPayloadDuty)
        power.put("allowed_payload_duty", view.power.allowedPayloadDuty)
        power.put("battery_dod", view.power.batteryDod)
        power.put("ok", view.power.balanceOk && view.power.dutyOk && view.power.dodOk)

        // Информационный бюджет — загрузка линии сброса маяком и полезной
        // нагрузкой; без маяка раздел о нём молчит, а не пишет ноль.
        val data = view.beacon?.let { b ->
            mapper.createObjectNode().apply {
                put("kind", "data")
                put("unit", "доля линии сброса")
                put("beacon_downlink_load", b.downlinkLoad)
                put("beacon_period_s", b.periodS)
                put("reserve", 1.0 - b.downlinkLoad)
            }
        }

        val tpm = mapper.createObjectNode()
        tpm.put("kind", "tpm")
        val rows = tpm.putArray("rows")
        view.tpm.forEach { r ->
            rows.addObject()
                .put("name", r.name).put("current", r.current).put("unit", r.unit)
                .put("target", r.target).put("margin_pct", r.marginPct)
                .put("required_margin_pct", r.requiredMarginPct).put("breached", r.breached)
        }
        return listOfNotNull(mass, power, data, tpm)
    }

    /**
     * Документ объекта плюс его статус и версия из хранилища. Статус —
     * свойство ХРАНИМОГО объекта, а не текста документа: спецификация
     * обязана показывать его тем же, что видит проверка зрелости.
     */
    private fun withLifecycle(o: StoredObject, mapper: ObjectMapper): ObjectNode {
        val doc = o.doc.deepCopy<ObjectNode>()
        doc.put("id", o.id)
        val lifecycle = doc.path("lifecycle").takeIf { it.isObject } as? ObjectNode
            ?: mapper.createObjectNode().also { doc.set<ObjectNode>("lifecycle", it) }
        lifecycle.put("status", o.status.name)
        lifecycle.put("version", o.version)
        return doc
    }
}
