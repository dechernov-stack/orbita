package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.schema.SchemaRegistry
import orbita.out.DocumentGenerator
import orbita.out.DocumentTemplate
import orbita.out.ModelSnapshot
import orbita.out.SpacecraftConditions

fun main() {
    val mapper = ObjectMapper()
    val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    TestDb.truncateAll()
    DemoProject.seed(boundary)
    val model = ModelSnapshot.of(
        boundary.objects, mapper,
        options = boundary.results.activeForScenario(DEMO_SCENARIO, "kpi").map { it.payload },
        budgets = ModelSnapshot.budgetsOf(
            boundary.spacecraft.build(
                boundary.objects.current(DemoProject.DEMO_SPACECRAFT)!!.doc,
                SpacecraftConditions(altKm = 550.0),
            ),
            mapper,
        ),
    )
    val g = DocumentGenerator(mapper)
    for (t in DocumentTemplate.entries) {
        val d = g.render(model, t)
        println("=".repeat(74))
        println("${d.body.path("title").asText()}  [${t.source}]  слепок ${d.digest}")
        d.body.path("sections").forEach { s ->
            val n = s.path("items").size()
            println("  %d. %-34s %s".format(s.path("number").asInt(), s.path("title").asText(),
                if (n == 0) "✗ ПУСТ" else "$n зап."))
        }
        println("  разрывов: ${d.gaps.size}")
        d.gaps.groupBy { it.section }.toSortedMap().forEach { (sec, gs) ->
            println("    раздел $sec: " + gs.take(2).joinToString("; ") { it.what } +
                if (gs.size > 2) " … и ещё ${gs.size - 2}" else "")
        }
    }
}
