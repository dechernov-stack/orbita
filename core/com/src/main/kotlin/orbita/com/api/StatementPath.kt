// Ф-12: проводник постановки. Владелец: «цели и нужды есть, а дальше маршрут
// не строится — с пустыми сервисами надо ЗНАТЬ, что идти в Инструменты».
// Неочевидное знание пути — дефект системы, а не свойство инженера.
//
// Здесь считается состояние сквозной цепочки постановки: замысел → цели →
// нужды → сервисы → требования. Каждое звено знает, сделано ли оно (и
// сколько), а первое несделанное несёт ПРИГЛАШЕНИЕ с адресом действия:
// куда идти и каким видом операции это собирается.
//
// Считает сервер: клиент показывает, но не вычисляет (правило обхода кода
// клиента) и не хранит собственного представления о порядке работ.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

object StatementPath {

    private val mapper = ObjectMapper()

    /** Звено цепочки: что это, сделано ли, чем и куда вести инженера. */
    data class Link(
        val key: String,
        val title: String,
        val count: Int,
        val done: Boolean,
        /** Экран, где звено делается. */
        val screen: String,
        /** Вид пакета службы, если звено собирается генерацией. */
        val kind: String? = null,
        /** Приглашение: что предлагается сделать. */
        val invitation: String,
        /** Почему это следующий шаг — словами, а не молчанием. */
        val why: String,
    )

    fun of(boundary: Boundary, projectId: String): List<Link> {
        val own = boundary.objects.listCurrent(projectId).filter { it.status.name != "Cancelled" }
        fun count(type: String) = own.count { it.type == type }
        val passport = boundary.objects.current(projectId)?.doc ?: mapper.createObjectNode()
        val intent = StatementSources(boundary).intentOf(passport)
        val goals = count("mission_goal")
        val needs = count("need")
        val services = count("service")
        val requirements = count("requirement")
        return listOf(
            Link(
                "intent", "Замысел миссии", if (intent == null) 0 else 1, intent != null,
                screen = "startpath",
                invitation = "задать замысел",
                why = "без замысла генерация постановки заблокирована: промпт даст общие места",
            ),
            Link(
                "goals", "Цели миссии", goals, goals > 0,
                screen = "aiservice", kind = "mission_to_goals",
                invitation = "собрать цели из замысла",
                why = "цели миссии — первое, что выводится из замысла и материалов",
            ),
            Link(
                "needs", "Нужды стейкхолдеров", needs, needs > 0,
                screen = "aiservice", kind = "mission_to_needs",
                invitation = "собрать нужды",
                why = "нужды выводятся из целей и профилей стейкхолдеров",
            ),
            Link(
                "services", "Сервисы", services, services > 0,
                screen = "aiservice", kind = "needs_to_services",
                invitation = "собрать сервисы из нужд",
                why = "сервис — ответ на нужду; без сервисов требованиям не на что опереться",
            ),
            Link(
                "requirements", "Требования", requirements, requirements > 0,
                screen = "aiservice", kind = "services_to_requirements",
                invitation = "собрать требования из сервисов",
                why = "требования выводятся из сервисов и их QoS-профилей",
            ),
        )
    }

    /** Первое несделанное звено — оно и есть следующий шаг постановки. */
    fun next(links: List<Link>): Link? = links.firstOrNull { !it.done }

    fun toJson(boundary: Boundary, projectId: String): ObjectNode {
        val links = of(boundary, projectId)
        val out = mapper.createObjectNode()
        val arr = out.putArray("links")
        links.forEach { l ->
            val n = arr.addObject()
            n.put("key", l.key)
            n.put("title", l.title)
            n.put("count", l.count)
            n.put("done", l.done)
            n.put("screen", l.screen)
            l.kind?.let { n.put("kind", it) }
            n.put("invitation", l.invitation)
            n.put("why", l.why)
        }
        val next = next(links)
        if (next == null) {
            out.put("complete", true)
            out.put("summary", "цепочка постановки пройдена: замысел, цели, нужды, сервисы, требования")
        } else {
            out.put("complete", false)
            out.put("summary", "следующий шаг постановки: ${next.invitation} — ${next.why}")
            val n = out.putObject("next")
            n.put("key", next.key)
            n.put("title", next.title)
            n.put("screen", next.screen)
            next.kind?.let { n.put("kind", it) }
            n.put("invitation", next.invitation)
            n.put("why", next.why)
        }
        return out
    }
}
