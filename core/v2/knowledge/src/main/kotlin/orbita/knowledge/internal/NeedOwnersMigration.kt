// Миграция понятий (ЗАДАНИЕ-ШИП-4 §1, ОНТОЛОГИЯ-АУДИТ часть 4): нужда — много
// носителей. До 24.09 одна формулировка лежала копией у каждой стороны
// (43 нужды на ПМИ-7 = 6 общих × стороны), и модель отказывалась повторять
// формулировки — и была права. Теперь одна формулировка — одна нужда, а
// носители — связи `owns` и зеркалящее их поле `stakeholders`.
//
// Что делает, с историей: копии одной формулировки в проекте сливаются в
// самую раннюю; её носители — объединение; связи копий (покрытие целями и
// сервисами, основания-факты, темы) переезжают на выжившую без удвоения;
// копия снимается с учёта версией с пометой «слито в …» — записи не
// удаляются. Прежнее поле `stakeholder` (строка) сходит в `stakeholders`.
// Идемпотентно: второй прогон ничего не меняет.
package orbita.knowledge.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry
import orbita.kernel.api.Provenance

internal class NeedOwnersMigration(
    private val store: EntityStore,
    private val links: LinkRegistry,
    private val mapper: ObjectMapper = ObjectMapper(),
) {
    /** Итог прогона словами: сколько слито, у скольких нужд носители зеркалированы. */
    data class Report(val merged: Int, val mirrored: Int, val notes: List<String>) {
        val changed: Boolean get() = merged > 0 || mirrored > 0
        override fun toString(): String =
            "нужда — много носителей: слито копий $merged, носители отражены у $mirrored нужд" +
                (if (notes.isEmpty()) "" else "; " + notes.joinToString("; "))
    }

    private val провенанс = Provenance(Channel.MANUAL, "миграция понятий 24.09 (нужда — много носителей)")

    fun run(): Report {
        var слито = 0
        var отражено = 0
        val заметки = mutableListOf<String>()
        val живые = store.ofKind("need").filter { it.status != "cancelled" }
        живые.groupBy { it.area }.forEach { (область, нужды) ->
            val ключи = IdentityKeys(mapper = mapper, область = область)
            // Копии одной формулировки — по ядру формулировки, тем же ключом, каким сверка узнаёт дубль.
            val группы = нужды.groupBy { ключи.statementCore(it.doc.path("statement").asText("")).value }
            группы.values.map { it.sortedBy { н -> н.createdAt } }.forEach { группа ->
                val выжившая = группа.first()
                группа.drop(1).forEach { копия ->
                    перенести(копия, выжившая)
                    снять(копия, выжившая)
                    слито += 1
                    заметки += "${копия.code} → ${выжившая.code}"
                }
            }
        }
        // Поле `stakeholders` — зеркало связей у КАЖДОЙ живой нужды (и без копий тоже).
        store.ofKind("need").filter { it.status != "cancelled" }.forEach { нужда ->
            if (отразить(нужда)) отражено += 1
        }
        return Report(слито, отражено, заметки)
    }

    /** Связи копии переезжают на выжившую: без удвоения и без потери направления. */
    private fun перенести(копия: Entity, выжившая: Entity) {
        links.to(копия.id).forEach { связь ->
            if (связь.from != выжившая.id && links.to(выжившая.id, связь.type).none { it.from == связь.from }) {
                links.link(связь.type, связь.from, выжившая.id, провенанс, rationale = связь.rationale ?: "перенесено с ${копия.code}", subtype = связь.subtype)
            }
            links.unlink(связь.id, провенанс)
        }
        links.from(копия.id).forEach { связь ->
            if (связь.to != выжившая.id && links.from(выжившая.id, связь.type).none { it.to == связь.to }) {
                links.link(связь.type, выжившая.id, связь.to, провенанс, rationale = связь.rationale ?: "перенесено с ${копия.code}", subtype = связь.subtype)
            }
            links.unlink(связь.id, провенанс)
        }
        // Носитель, названный прежним строковым полем, — тоже связь выжившей.
        носительПоПолю(копия)?.let { сторона ->
            if (links.to(выжившая.id, "owns").none { it.from == сторона.id }) {
                links.link("owns", сторона.id, выжившая.id, провенанс, rationale = "носитель копии ${копия.code}")
            }
        }
    }

    /** Копия снимается с учёта версией с пометой: история читается по версиям. */
    private fun снять(копия: Entity, выжившая: Entity) {
        val документ = копия.doc.deepCopy<JsonNode>() as ObjectNode
        документ.remove("stakeholder")
        документ.put("notes", listOfNotNull(копия.doc.path("notes").asText("").ifBlank { null },
            "слито в ${выжившая.code}: одна формулировка — одна нужда, носителей много (миграция 24.09)").joinToString("; "))
        store.update(копия.id, документ, провенанс, status = "cancelled")
    }

    /** Поле `stakeholders` = связи owns (плюс носитель из прежнего поля); true — запись изменилась. */
    private fun отразить(нужда: Entity): Boolean {
        носительПоПолю(нужда)?.let { сторона ->
            if (links.to(нужда.id, "owns").none { it.from == сторона.id }) {
                links.link("owns", сторона.id, нужда.id, провенанс, rationale = "носитель из прежнего поля stakeholder")
            }
        }
        val носители = links.to(нужда.id, "owns").map { it.from }.distinct()
        val было = нужда.doc.path("stakeholders").map { it.asText() }
        if (носители.isNotEmpty() && было == носители && !нужда.doc.has("stakeholder")) return false
        if (носители.isEmpty() && !нужда.doc.has("stakeholder")) return false
        val документ = нужда.doc.deepCopy<JsonNode>() as ObjectNode
        документ.remove("stakeholder")
        документ.putArray("stakeholders").also { м -> носители.forEach { м.add(it) } }
        store.update(нужда.id, документ, провенанс)
        return true
    }

    /** Сторона из прежнего строкового поля `stakeholder`: код, идентификатор либо имя. */
    private fun носительПоПолю(нужда: Entity): Entity? {
        val поле = нужда.doc.path("stakeholder")
        val имя = (if (поле.isObject) поле.path("code").asText("") else поле.asText("")).trim()
        if (имя.isBlank()) return null
        val область = нужда.area
        return (store.byCode(область, имя) ?: store.byId(имя)
            ?: store.list(область, "stakeholder").firstOrNull { it.doc.path("name").asText("").trim().equals(имя, ignoreCase = true) })
            ?.takeIf { it.kind == "stakeholder" }
    }
}
