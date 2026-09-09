// Фаза в JSON — одно место на все маршруты.
//
// Вид фазы читают и сцены, и сквозные экраны; держать две копии этой
// развёртки значило бы заводить два ответа на один вопрос.
package orbita.api.internal

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.process.api.ActivityView
import orbita.process.api.ConditionView
import orbita.process.api.FindingView
import orbita.process.api.GateView
import orbita.process.api.PhaseView

internal object PhaseJson {

    /** Мероприятия одинаковы в сцене и в дорожке — развёртка одна. */
    private fun мероприятия(массив: com.fasterxml.jackson.databind.node.ArrayNode, список: List<ActivityView>) {
        список.forEach { дело ->
            val у = массив.addObject()
            у.put("code", дело.code)
            у.put("name", дело.name)
            у.put("goal", дело.goal)
            у.put("role", дело.role)
            у.put("track", дело.track)
            у.put("method_group", дело.methodGroup)
            у.put("surface", дело.surface)
            у.put("state", дело.state.name.lowercase())
            val входы = у.putArray("inputs")
            дело.inputs.forEach { входы.add(it) }
            val выходы = у.putArray("outputs")
            дело.outputs.forEach { выход ->
                выходы.addObject()
                    .put("what", выход.what).put("kind", выход.kind)
                    .put("min", выход.min).put("count", выход.count)
                    .put("produced_in", выход.producedIn).put("satisfied", выход.satisfied)
            }
            val держит = у.putArray("blocked_by")
            дело.blockedBy.forEach { держит.add(it) }
            val откроет = у.putArray("opens")
            дело.opens.forEach { откроет.add(it) }
        }
    }

    fun вид(фаза: PhaseView, mapper: ObjectMapper): ObjectNode {
        val узел = mapper.createObjectNode()
        узел.put("project", фаза.project)
        узел.put("standard", фаза.standard)
        узел.put("phase", фаза.phase)
        узел.put("current_scene", фаза.currentScene)
        val сцены = узел.putArray("scenes")
        фаза.scenes.forEach { сцена ->
            val с = сцены.addObject()
            с.put("key", сцена.key)
            с.put("title", сцена.title)
            с.put("order", сцена.order)
            с.put("role", сцена.role)
            с.put("question", сцена.question)
            с.put("state", сцена.state.name.lowercase())
            с.put("output", сцена.output)
            сцена.window?.let { (начало, конец) ->
                с.putObject("window").put("start", начало).put("end", конец)
            }
            val причины = с.putArray("blockers")
            сцена.blockers.forEach { причины.add(it) }
            с.put("instance_of", сцена.instanceOf)
            с.put("node", сцена.node)
            val связи = с.putArray("depends")
            сцена.links.forEach { л -> связи.addObject().put("on", л.on).put("type", л.type).put("why", л.why) }
            val ждут = с.putArray("awaited_by")
            сцена.awaitedBy.forEach { ждут.add(it) }
            val потоки = с.putArray("input_flows")
            сцена.inputFlows.forEach { потоки.add(it) }
            условия(с.putArray("entry"), сцена.entry)
            условия(с.putArray("exit"), сцена.exit)
            мероприятия(с.putArray("activities"), сцена.activities)
            val шаги = с.putArray("steps")
            сцена.steps.forEach { шаг ->
                шаги.addObject()
                    .put("title", шаг.title)
                    .put("place", шаг.place)
                    .put("hint", шаг.hint)
                    .put("done", шаг.done)
            }
        }
        val дорожки = узел.putArray("lanes")
        фаза.lanes.forEach { дорожка ->
            val д = дорожки.addObject()
            д.put("key", дорожка.key)
            д.put("title", дорожка.title)
            д.put("of", дорожка.of)
            мероприятия(д.putArray("activities"), дорожка.activities)
        }

        val точки = узел.putArray("gates")
        фаза.gates.forEach { точки.add(точка(it, mapper)) }
        return узел
    }

    private fun условия(массив: com.fasterxml.jackson.databind.node.ArrayNode, список: List<ConditionView>) {
        список.forEach { у ->
            массив.addObject()
                .put("title", у.title).put("check", у.check)
                .put("passed", у.passed).put("why", у.why)
                .put("blocking", у.blocking)
        }
    }

    /** Точка целиком: критерии, экспертиза с позициями, замечания, решение, матрица. */
    fun точка(т: GateView, mapper: ObjectMapper): ObjectNode {
        val узел = mapper.createObjectNode()
        узел.put("key", т.key)
        узел.put("title", т.title)
        узел.put("order", т.order)
        узел.put("planned_date", т.plannedDate)
        узел.put("passed", т.passed)
        узел.put("role", т.role)
        узел.put("opens_phase", т.opensPhase)
        узел.put("checklist_of", т.checklistOf)
        узел.put("legend_note", т.legendNote)
        val блок = узел.putArray("blocking")
        т.blocking.forEach { блок.add(it) }
        условия(узел.putArray("criteria"), т.criteria)
        т.expertise?.let { э ->
            val у = узел.putObject("expertise")
            у.put("goal", э.goal)
            у.put("source", э.source)
            val вопросы = у.putArray("questions")
            э.questions.forEach { вопросы.add(it) }
            val результаты = у.putArray("results")
            э.results.forEach { результаты.add(it) }
            val итог = у.putArray("main_outcome")
            э.mainOutcome.forEach { итог.add(it) }
            val позиции = у.putArray("positions")
            э.positions.forEach { позиции.add(позиция(it, mapper)) }
        }
        val замечания = узел.putArray("findings")
        т.findings.forEach { замечания.add(замечание(it, mapper)) }
        т.decision?.let { д ->
            узел.putObject("decision")
                .put("by", д.by).put("at", д.at).put("outcome", д.outcome).put("note", д.note)
        }
        val матрица = узел.putArray("matrix")
        т.matrix.forEach { матрица.add(позиция(it, mapper)) }
        return узел
    }

    /** Позиция зрелости — одна развёртка и для экспертизы, и для матрицы. */
    private fun позиция(п: orbita.process.api.PositionView, mapper: ObjectMapper): ObjectNode {
        val узел = mapper.createObjectNode()
            .put("artifact", п.artifact).put("maturity", п.maturity)
            .put("our_ref", п.ourRef).put("check", п.check)
            .put("passed", п.passed).put("why", п.why).put("blocking", п.blocking)
        if (п.codes.isNotEmpty()) {
            val коды = узел.putObject("codes")
            п.codes.forEach { (к, в) -> коды.put(к, в) }
        }
        return узел
    }

    fun замечание(з: FindingView, mapper: ObjectMapper): ObjectNode = mapper.createObjectNode()
        .put("code", з.code)
        .put("text", з.text)
        .put("scene", з.scene)
        .put("gate", з.gate)
        .put("status", з.status)
        .put("author", з.author)
        .put("kind", з.kind)
        .put("question", з.question)
        .put("closed_by", з.closedBy)

}
