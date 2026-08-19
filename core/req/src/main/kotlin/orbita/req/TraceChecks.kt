// Целостность связей цифровой нити (TZ-REQ-003, TZ-REQ-005, TZ-REQ-002).
// Чистые функции — эталон spec/requirements_semantics.py, один в один;
// SQL-варианты тех же отчётов — LinkStore (запросы 3–4) и ReqService.
package orbita.req

import com.fasterxml.jackson.databind.JsonNode
import orbita.mod.store.Link

/** Требования без источника (вверх) — разрыв цифровой нити. */
fun traceGaps(objects: List<ObjectSnapshot>, links: List<Link>): List<String> {
    val hasParent = links.filter { it.kind == "trace" }.map { it.toId }.toSet()
    return objects.filter { it.type == "requirement" && it.status != "Cancelled" && it.id !in hasParent }
        .map { it.id }.sorted()
}

/** Ссылка требования на сервис обязана нести класс потребителя (Р9/ADR-009). */
fun serviceLinkValid(link: Link, objects: List<ObjectSnapshot>): Boolean {
    val source = objects.firstOrNull { it.id == link.fromId } ?: return true
    return if (source.type == "service") link.consumerClass != null else true
}

/** (нераспределённые системные требования, элементы без требований) — TZ-REQ-005. */
fun allocationCoverage(objects: List<ObjectSnapshot>, links: List<Link>): Pair<List<String>, List<String>> {
    val alloc = links.filter { it.kind == "allocation" }
    val allocated = alloc.map { it.fromId }.toSet()
    val coveredElements = alloc.map { it.toId }.toSet()
    val unallocated = objects.filter {
        it.type == "requirement" && it.level == "system" &&
            it.status != "Cancelled" && it.id !in allocated
    }.map { it.id }.sorted()
    val bare = objects.filter { it.type == "component" && it.id !in coveredElements }
        .map { it.id }.sorted()
    return unallocated to bare
}

/** TZ-REQ-002: классы, присутствующие в карте спроса, но не покрытые профилями сервиса. */
fun uncoveredConsumerClasses(service: JsonNode, demandClasses: Set<String>): List<String> {
    val profiled = service.path("qos_profiles").map { it.path("consumer_class").asText() }.toSet()
    return (demandClasses - profiled).sorted()
}
