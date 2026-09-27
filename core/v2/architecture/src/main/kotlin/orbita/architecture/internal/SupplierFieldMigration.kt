// Поставщик узла — полем, не связью (диф владельца 27.09-b: `component.supplier:
// ref stakeholder`). «Владеет» у нас — носитель нужды; поставщик узла — другое
// отношение, и его читают лестница к SDR, ICD и пакет точки.
//
// До дифа поставщик писался связью «владеет» сторона → узел (сквозной прогон
// 27.09 и строка долга карточки узла). Миграция при старте переносит такие связи
// в поле узла и снимает их; связь «владеет» сторона → нужда не трогается.
// Идемпотентна: связи к узлам больше нет — переносить нечего.
package orbita.architecture.internal

import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.kernel.api.Channel
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry
import orbita.kernel.api.Provenance

internal class SupplierFieldMigration(private val store: EntityStore, private val links: LinkRegistry) {

    fun run(): String {
        var перенесено = 0
        var снято = 0
        val служба = Provenance(Channel.SERVICE, "служба: поставщик узла полем (диф 27.09-b)")
        store.ofKind("component").forEach { узел ->
            val связи = links.to(узел.id, "owns").filter { store.byId(it.from)?.kind == "stakeholder" }
            if (связи.isEmpty()) return@forEach
            if (узел.doc.path("supplier").asText("").isBlank()) {
                val документ = (узел.doc.deepCopy() as ObjectNode).put("supplier", связи.first().from)
                store.update(узел.id, документ, служба)
                перенесено += 1
            }
            связи.forEach { links.unlink(it.id, служба); снято += 1 }
        }
        return "поставщик узла полем: перенесено $перенесено, снято связей «владеет» к узлам $снято"
    }
}
