// Ф-13 (шип 3): матрица «стейкхолдер × нужды». Владелец: связь нужд со
// стейкхолдерами была неявной, а круг стейкхолдеров шире потребителей —
// регуляторы, операторы, поставщики, учреждаемые организации.
//
// Тройное состояние каждой нужды в матрице:
//   заявлена  — нужда названа, но требованием не покрыта;
//   покрыта   — есть требование, которое на неё ссылается;
//   закрыта   — покрывающее требование имеет событие верификации.
//
// Края видимы: стейкхолдер без нужд и нужда без носителя — не тишина, а
// строка со своим состоянием. Ни одно из состояний не хранится: всё
// вычисляется по модели.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

object StakeholderCoverage {

    private val mapper = ObjectMapper()

    fun toJson(boundary: Boundary, projectId: String): ObjectNode {
        val own = boundary.objects.listCurrent(projectId).filter { it.status.name != "Cancelled" }
        val stakeholders = own.filter { it.type == "stakeholder" }.sortedBy { it.id }
        val needs = own.filter { it.type == "need" }.sortedBy { it.id }
        val requirements = own.filter { it.type == "requirement" }

        // покрытие: требование ссылается на нужду трассой вверх
        val coveringByNeed = HashMap<String, MutableList<String>>()
        requirements.forEach { rq ->
            rq.doc.path("traces_up").forEach { t ->
                val ref = t.path("ref").asText("")
                if (ref.startsWith("ND-")) coveringByNeed.getOrPut(ref) { mutableListOf() }.add(rq.id)
            }
        }
        val verified = requirements.filter { rq ->
            rq.doc.path("verification_events").any { it.path("closes").asBoolean(false) }
        }.map { it.id }.toSet()

        fun stateOf(needId: String): String {
            val covering = coveringByNeed[needId].orEmpty()
            return when {
                covering.isEmpty() -> "declared"
                covering.any { it in verified } -> "verified"
                else -> "covered"
            }
        }

        val out = mapper.createObjectNode()
        out.put("stakeholders", stakeholders.size)
        out.put("needs", needs.size)
        out.put("declared", needs.count { stateOf(it.id) == "declared" })
        out.put("covered", needs.count { stateOf(it.id) == "covered" })
        out.put("verified", needs.count { stateOf(it.id) == "verified" })

        val rows = out.putArray("rows")
        stakeholders.forEach { sh ->
            val mine = needs.filter { it.doc.path("stakeholder_ref").asText("") == sh.id }
            val n = rows.addObject()
            n.put("id", sh.id)
            n.put("name", sh.doc.path("name").asText(sh.id))
            n.put("role", sh.doc.path("role").asText(""))
            n.put("establishes", sh.doc.path("establishes").asBoolean(false))
            // уже обобщён? тогда отмечать его к переносу незачем — это видно
            sh.doc.path("profile_ref").asText("").takeIf { it.isNotBlank() }
                ?.let { n.put("profile_ref", it) }
            sh.doc.path("interest").asText("").takeIf { it.isNotBlank() }?.let { n.put("interest", it) }
            val supplies = sh.doc.path("supplies").map { it.asText() }
            if (supplies.isNotEmpty()) {
                val arr = n.putArray("supplies")
                supplies.forEach { cmId ->
                    val cm = boundary.objects.current(cmId)
                    val a = arr.addObject()
                    a.put("id", cmId)
                    a.put("name", cm?.doc?.path("name")?.asText(cmId) ?: cmId)
                    // через узел поставщик достаёт и анкету его характеристик
                    a.put("has_form", cm != null && formFor(boundary, cm.doc.path("role").asText("")) != null)
                }
            }
            n.put("needs", mine.size)
            n.put("covered", mine.count { stateOf(it.id) != "declared" })
            n.put("verified", mine.count { stateOf(it.id) == "verified" })
            val list = n.putArray("items")
            mine.forEach { nd ->
                val e = list.addObject()
                e.put("id", nd.id)
                e.put("statement", nd.doc.path("statement").asText(""))
                e.put("state", stateOf(nd.id))
                val covering = coveringByNeed[nd.id].orEmpty()
                if (covering.isNotEmpty()) {
                    val c = e.putArray("covered_by")
                    covering.forEach { c.add(it) }
                }
            }
            if (mine.isEmpty()) {
                n.put(
                    "empty_why",
                    "нужд за этим стейкхолдером не числится: либо они ещё не заведены, " +
                        "либо у них не назван носитель",
                )
            }
        }

        // Край матрицы: нужды без носителя. Их не видно ни в одной строке —
        // значит их надо показать отдельно, а не потерять.
        val orphans = needs.filter { it.doc.path("stakeholder_ref").asText("").isBlank() }
        val orphanArr = out.putArray("without_stakeholder")
        orphans.forEach { nd ->
            val e = orphanArr.addObject()
            e.put("id", nd.id)
            e.put("statement", nd.doc.path("statement").asText(""))
            e.put("state", stateOf(nd.id))
            nd.doc.path("stakeholder").path("name").asText("").takeIf { it.isNotBlank() }
                ?.let { e.put("named", it) }
        }
        out.put(
            "summary",
            when {
                stakeholders.isEmpty() && needs.isEmpty() ->
                    "ни стейкхолдеров, ни нужд: постановка ещё не начата"
                stakeholders.isEmpty() ->
                    "нужды есть, стейкхолдеров нет — носителя назвать некому"
                orphans.isEmpty() ->
                    "у всех ${needs.size} нужд назван носитель"
                else ->
                    "${orphans.size} из ${needs.size} нужд без носителя — их не видно ни в одной строке матрицы"
            },
        )
        return out
    }

    /** Есть ли на полке анкета характеристик для роли узла состава. */
    private fun formFor(boundary: Boundary, role: String): String? {
        if (role.isBlank()) return null
        return boundary.objects.listCurrent(orbita.mod.store.ObjectStore.LIBRARY_PROJECT)
            .firstOrNull { it.type == "property_form" && it.doc.path("role").asText() == role }
            ?.id
    }
}
