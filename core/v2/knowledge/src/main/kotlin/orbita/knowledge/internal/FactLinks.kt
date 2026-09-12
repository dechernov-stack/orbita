// Связи МЕЖДУ фактами: разбор возвращает не только атомы, но и то, чем они
// друг другу приходятся — подтверждает · противоречит · уточняет · то же самое.
//
// Почему связью, а не полем в документе факта: поле живёт ВНУТРИ одного
// документа и корпуса не переживает, а связь держится концами за факты разных
// материалов (мера §3 задания — связи фактов между документами). Поле
// `conflicts` остаётся вычисляемым представлением того же знания для экрана и
// экспорта, и ломать его незачем.
//
// Здесь нет ни одного вызова модели: ответ разбора приходит готовым, всё
// остальное считается по данным.
package orbita.knowledge.internal

import com.fasterxml.jackson.databind.JsonNode
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry
import orbita.kernel.api.Provenance

/** Связь факта с фактом так, как её читает экран: тип, концы кодами, причина. */
internal data class СвязьФакта(val type: String, val from: String, val to: String, val rationale: String)

internal class FactLinks(
    private val store: EntityStore,
    private val links: LinkRegistry?,
) {

    internal companion object {

        /**
         * Чем факт бывает связан с фактом. Перечень узкий намеренно: реестр
         * связей знает больше типов, но между двумя АТОМАМИ осмысленны только
         * эти четыре, а «похоже» связью не бывает вовсе.
         */
        val междуФактами: Set<String> = setOf("supports", "contradicts", "refines", "same_as")

        /**
         * Связи без направления: «противоречит» и «то же самое» читаются с
         * любого конца, и зеркальная связь была бы той же связью дважды.
         */
        private val безНаправления: Set<String> = setOf("contradicts", "same_as")
    }

    /** Что принято и что отклонено поимённо: разбор чинится, а не подчищается молча. */
    internal data class Итог(val accepted: Int, val refused: List<String>)

    /**
     * Принять блок `links` ответа разбора.
     *
     * Концы приходят НОМЕРАМИ фактов в том же ответе — теми же, которыми
     * ссылается план, — поэтому карта `поНомеру` берётся готовой: при
     * повторном приёме она указывает на уже существующие факты, и связь не
     * ставится второй раз.
     *
     * @param поНомеру номер факта в ответе → его код в проекте
     */
    fun relate(область: Area, узел: JsonNode, поНомеру: Map<Int, String>, author: String): Итог {
        if (!узел.isArray || узел.size() == 0) return Итог(0, emptyList())
        val реестр = links ?: return Итог(0, listOf("связи фактов не поставлены: реестра связей нет"))
        val отказы = mutableListOf<String>()
        var поставлено = 0
        // Уже стоящие связи каждого конца читаются ОДИН раз: на десятке связей
        // запрос на пару ходил бы в реестр десятки раз за тем же ответом.
        val соседи = mutableMapOf<String, MutableList<СвязьФакта>>()
        fun связи(код: String): MutableList<СвязьФакта> =
            соседи.getOrPut(код) { factLinks(область, код).toMutableList() }

        узел.forEachIndexed { i, с ->
            val тип = с.path("type").asText("").trim()
            if (тип !in междуФактами) {
                отказы += "связь $i: тип «$тип» между фактами не бывает — " +
                    "подтверждает (supports) · противоречит (contradicts) · " +
                    "уточняет (refines) · то же самое (same_as)"
                return@forEachIndexed
            }
            val откуда = конец(область, с.path("from"), поНомеру)
            val куда = конец(область, с.path("to"), поНомеру)
            if (откуда == null || куда == null) {
                отказы += "связь $i «$тип»: конец «${имяКонца(с.path("from"))} → " +
                    "${имяКонца(с.path("to"))}» не найден среди фактов этого разбора"
                return@forEachIndexed
            }
            if (откуда.code == куда.code) {
                отказы += "связь $i «$тип»: оба конца — факт ${откуда.code}; " +
                    "факт не связывается сам с собой"
                return@forEachIndexed
            }
            val причина = с.path("rationale").asText("").trim()
            if (причина.isBlank()) {
                отказы += "связь $i «$тип» (${откуда.code} → ${куда.code}): без обоснования — " +
                    "связь без причины неотличима от случайной"
                return@forEachIndexed
            }
            // Повторный приём того же разбора связь не удваивает: реестр
            // дублей не отсеивает, и вторая такая же связь на экране читалась
            // бы как второе свидетельство.
            if (связи(откуда.code).any { совпало(тип, it, откуда.code, куда.code) }) return@forEachIndexed
            реестр.link(
                тип, откуда.id, куда.id,
                Provenance(Channel.SERVICE, author, source = откуда.doc.path("material").asText("").ifBlank { null }),
                rationale = причина,
            )
            val поставленная = СвязьФакта(тип, откуда.code, куда.code, причина)
            связи(откуда.code).add(поставленная)
            связи(куда.code).add(поставленная)
            поставлено += 1
        }
        return Итог(поставлено, отказы)
    }

    /**
     * Связи факта обоими концами — читальная сторона: по ней экран говорит
     * «подтверждается фактом F-0007 из ТЗ», а приём тем же ответом узнаёт
     * связь, которая уже стоит.
     */
    fun factLinks(область: Area, код: String): List<СвязьФакта> {
        val реестр = links ?: return emptyList()
        val факт = store.byCode(область, код)?.takeIf { it.kind == "fact" } ?: return emptyList()
        val изРеестра = реестр.from(факт.id).filter { it.type in междуФактами }
            .map { СвязьФакта(it.type, факт.code, кодом(it.to), it.rationale.orEmpty()) } +
            реестр.to(факт.id).filter { it.type in междуФактами }
                .map { СвязьФакта(it.type, кодом(it.from), факт.code, it.rationale.orEmpty()) }
        return изРеестра
            .distinctBy { listOf(it.type, it.from, it.to) }
            .sortedWith(compareBy({ it.type }, { it.from }, { it.to }))
    }

    /** Связь уже стоит? У связи без направления зеркальная пара — та же связь. */
    private fun совпало(тип: String, связь: СвязьФакта, а: String, б: String): Boolean =
        связь.type == тип && (
            (связь.from == а && связь.to == б) ||
                (тип in безНаправления && связь.from == б && связь.to == а)
            )

    /**
     * Конец связи: номер факта в ответе разбора либо его код. Чужой код
     * концом не становится — связь ставится между фактами, а не между чем
     * попало.
     */
    private fun конец(область: Area, узел: JsonNode, поНомеру: Map<Int, String>): Entity? {
        val код = when {
            узел.isNumber -> поНомеру[узел.asInt()]
            else -> узел.asText("").trim().let { текст ->
                текст.toIntOrNull()?.let { поНомеру[it] } ?: текст.ifBlank { null }
            }
        } ?: return null
        return store.byCode(область, код)?.takeIf { it.kind == "fact" }
    }

    /** Конец словами для отказа: номер или код — тем, чем его назвал разбор. */
    private fun имяКонца(узел: JsonNode): String = узел.asText("").ifBlank { "—" }

    private fun кодом(идентификатор: String): String = store.byId(идентификатор)?.code ?: идентификатор
}
