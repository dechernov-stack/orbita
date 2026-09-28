// Величина факта — объектом measure с оператором (§0.2 шипа 6, миграция при
// старте). Прежде величину писали строкой `value` + top-level `unit`, оператор
// терялся. Здесь старые факты переносятся в форму истины: value = measure
// {op?, value, unit}, top-level unit снимается. Идемпотентно: факт без
// top-level unit (уже новая форма или текстовый) не трогается.
package orbita.knowledge.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.kernel.api.Channel
import orbita.kernel.api.EntityStore
import orbita.kernel.api.Provenance

internal class FactValueMigration(private val store: EntityStore, private val mapper: ObjectMapper = ObjectMapper()) {
    private val провенанс = Provenance(Channel.MANUAL, "миграция величины факта (§0.2: measure с оператором)")

    fun run(): String {
        var перенесено = 0
        store.ofKind("fact").forEach { факт ->
            val doc = факт.doc
            // Новая форма (value-объект) или факт без единицы — уже по истине.
            if (!doc.has("unit") || doc.path("value").isObject) return@forEach
            val d = doc.deepCopy() as ObjectNode
            val старое = doc.path("value").asText("")
            val единица = doc.path("unit").asText("").ifBlank { null }
            d.remove("unit")
            // положить разберёт префикс оператора старой строки («≤180») и
            // соберёт объект measure; не число — оставит текстом.
            ВеличинаФакта.положить(d, mapper, старое, единица, null)
            store.update(факт.id, d, провенанс)
            перенесено++
        }
        return "величина факта объектом measure: перенесено $перенесено"
    }
}
