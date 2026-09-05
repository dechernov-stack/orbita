// Оценщик условий ворот (реализация порта process.GateEvaluator).
//
// Ворота остаются НАШИМИ правилами: движок только исполняет их. Здесь
// условие переводится в вопрос к данным домена, а отказ — в причину
// словами, которую увидит инженер («у стейкхолдера СХ-2 нет ни одной
// нужды»), а не в голое «нельзя».
package orbita.readiness.internal

import orbita.kernel.api.Area
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry
import orbita.process.api.GateEvaluator

class DomainGateEvaluator(
    private val store: EntityStore,
    private val links: LinkRegistry,
    /** Сцены, признанные пройденными: их считает движок и передаёт сюда. */
    private val сценыПройдены: (String) -> Set<String>,
    private val воротаПройдены: (String) -> Set<String>,
) : GateEvaluator {

    override fun why(project: String, check: String): String? {
        val область = Area.Project(project)
        val (имя, аргумент) = check.split(":", limit = 2).let {
            it[0] to it.getOrNull(1)
        }
        return when (имя) {
            // Проект опознаётся КОДОМ: он же стоит в области сущностей
            // («project:PJ-0001»), и связывать одно с другим через внутренний
            // идентификатор значило бы держать два имени одного и того же.
            "project_exists" ->
                if (store.byCode(область, project) != null) null else "проект ещё не заведён"

            "points_planned" -> {
                val точки = store.list(область, "gate")
                if (точки.isEmpty()) "точки фазы не заведены"
                else точки.firstOrNull { it.doc.path("planned_date").asText("").isBlank() }
                    ?.let { "у точки «${it.doc.path("title").asText(it.code)}» нет даты" }
            }

            "intent_accepted" -> {
                val замысел = store.list(область, "intent").firstOrNull()
                when {
                    замысел == null -> "замысел не задан: без него сцена 3 закрыта"
                    замысел.status != "accepted" -> "замысел ещё не принят — примите его на сцене 2"
                    else -> null
                }
            }

            "stakeholders_min" -> {
                val нужно = (аргумент ?: "3").toInt()
                val есть = store.list(область, "stakeholder").size
                if (есть >= нужно) null
                else "стейкхолдеров $есть из $нужно: круг шире потребителей — регуляторы, операторы, учреждаемые"
            }

            "each_stakeholder_has_need" -> {
                val без = store.list(область, "stakeholder")
                    .filter { links.from(it.id, "owns").isEmpty() }
                if (без.isEmpty()) null
                else "без нужд: " + без.joinToString(", ") { it.doc.path("name").asText(it.code) }
            }

            "each_need_has_goal" -> {
                val без = store.list(область, "need")
                    .filter { нужда -> links.to(нужда.id, "covers").none { it.from.startsWith("goal") } }
                if (без.isEmpty()) null
                else "нужд без цели: ${без.size} — " +
                    без.take(3).joinToString("; ") { it.doc.path("statement").asText(it.code).take(60) }
            }

            "scene_done" -> {
                val ключ = аргумент ?: return "условие «$check» не назвало сцену"
                if (ключ in сценыПройдены(project)) null else "сцена $ключ ещё не прожита"
            }

            "gate_passed" -> {
                val ключ = аргумент ?: return "условие «$check» не назвало точку"
                if (ключ in воротаПройдены(project)) null else "точка $ключ ещё не пройдена"
            }

            // Условие, которого оценщик не знает, — не «выполнено по умолчанию»:
            // тихо пропустить ворота хуже, чем честно сказать, что правило не
            // реализовано (ТЗ §6.2: не гадать).
            else -> "условие «$check» ещё не реализовано в оценщике готовности"
        }
    }
}
