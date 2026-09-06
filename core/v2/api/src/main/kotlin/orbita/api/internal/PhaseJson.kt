// Фаза в JSON — одно место на все маршруты.
//
// Вид фазы читают и сцены, и сквозные экраны; держать две копии этой
// развёртки значило бы заводить два ответа на один вопрос.
package orbita.api.internal

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.process.api.ActivityView
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
            val ждут = с.putArray("awaited_by")
            сцена.awaitedBy.forEach { ждут.add(it) }
            val потоки = с.putArray("input_flows")
            сцена.inputFlows.forEach { потоки.add(it) }
            fun условия(имя: String, список: List<orbita.process.api.ConditionView>) {
                val массив = с.putArray(имя)
                список.forEach { у ->
                    массив.addObject()
                        .put("title", у.title).put("check", у.check)
                        .put("passed", у.passed).put("why", у.why)
                }
            }
            условия("entry", сцена.entry)
            условия("exit", сцена.exit)
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
        фаза.gates.forEach { точка ->
            val т = точки.addObject()
            т.put("key", точка.key)
            т.put("title", точка.title)
            т.put("order", точка.order)
            т.put("planned_date", точка.plannedDate)
            т.put("passed", точка.passed)
            val блок = т.putArray("blocking")
            точка.blocking.forEach { блок.add(it) }
        }
        return узел
    }

}
