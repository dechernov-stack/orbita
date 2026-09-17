// Чем закрывается нужда — правило владельца, не догадка кода.
//
// Истина 17.09 (ОНТОЛОГИЯ-ФОРМИРОВАНИЯ, `need.coverage_expected` и
// `need.coverage_rule`): «чем нужда закрывается: service · constraint ·
// programme · requirement; по умолчанию из роли носителя … инженер правит»;
// «выход сцены 6 „каждая нужда покрыта сервисом“ считает только нужды с
// coverage_expected=service; нужды регуляторов закрываются ограничениями
// (сцены 5/8), нужды поставщиков и партнёров — пакетами WBS и оценками
// (сцена 12)».
//
// Зачем понадобилось: на ПМИ-7 четыре нужды Роскосмоса и ГКРЧ держали сцену 6
// навсегда — сервисами системы они не закрываются и по смыслу, а выход сцены
// требовал сервис у каждой. Владелец ответил истиной, и правило пришло сюда
// генерацией: карта «роль → вид покрытия» второй копии в коде не имеет.
//
// Поле у нужды сильнее карты: «инженер правит». Пока его нет в истине схем,
// правку принять некуда — карта работает как значение по умолчанию.
package orbita.knowledge.api

import com.fasterxml.jackson.databind.JsonNode
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry
import orbita.knowledge.schema.GeneratedOntology

object Coverage {

    /** Понятие, у которого поле живёт, — нужда. */
    const val CONCEPT: String = "need"

    /** Имя поля по истине онтологии. */
    const val FIELD: String = "coverage_expected"

    /** Вид покрытия, который считает выход сцены 6. */
    const val SERVICE: String = "service"

    /** Поле носителя у нужды и тип связи к нему — те же, что у сверки. */
    private const val НОСИТЕЛЬ: String = "stakeholder"
    private const val OWNS: String = "owns"
    private const val СНЯТО: String = "cancelled"

    /** Правило дословно: им человеку объясняется, почему нужда не держит сцену. */
    val rule: String = GeneratedOntology.byCode[CONCEPT]?.coverageRule.orEmpty()

    /** Роль носителя → вид покрытия. Карта целиком из истины. */
    val byRole: Map<String, String> = GeneratedOntology.coverageByRole

    /** Что сцена ждёт по нуждам — словами истины (сцены 6, 8, 12). */
    val sceneNotes: Map<String, String> = GeneratedOntology.sceneExitNotes

    /**
     * Чем закрывается эта нужда: названное у неё поле, иначе — по роли
     * носителя. Роль вне карты истины покрытия не получает, и нужда такого
     * носителя сцену 6 не держит: правило о ней молчит, а придумывать за
     * истину нельзя.
     */
    fun expected(need: JsonNode, role: String?): String? =
        need.path(FIELD).asText("").trim().ifBlank { null }
            ?: role?.trim()?.ifBlank { null }?.let { byRole[it] }

    /** Ждёт ли нужда сервиса: по этому вопросу считается выход сцены 6. */
    fun expectsService(need: JsonNode, role: String?): Boolean = expected(need, role) == SERVICE

    /**
     * Готовый ответчик для оценщика готовности: он живёт слоем ниже и истины
     * онтологии не видит, поэтому правило приходит к нему функцией.
     */
    fun resolver(store: EntityStore, links: LinkRegistry?): (Entity) -> String? = { нужда ->
        expected(нужда.doc, рольНосителя(store, links, нужда))
    }

    /** Роль носителя: связью `owns`, а если её нет — полем документа нужды. */
    fun рольНосителя(store: EntityStore, links: LinkRegistry?, нужда: Entity): String? {
        val поСвязи = links?.to(нужда.id, OWNS)
            ?.mapNotNull { store.byId(it.from) }
            ?.firstOrNull { it.kind == "stakeholder" && it.status != СНЯТО }
        if (поСвязи != null) return поСвязи.doc.path("role").asText("").trim().ifBlank { null }
        val названный = нужда.doc.path(НОСИТЕЛЬ)
        val имя = (if (названный.isObject) названный.path("code").asText("") else названный.asText("")).trim()
        if (имя.isBlank()) return null
        val сторона = store.byCode(нужда.area, имя)?.takeIf { it.kind == "stakeholder" && it.status != СНЯТО }
            ?: store.list(нужда.area, "stakeholder").firstOrNull {
                it.status != СНЯТО && it.doc.path("name").asText("").trim().equals(имя, ignoreCase = true)
            }
        return сторона?.doc?.path("role")?.asText("")?.trim()?.ifBlank { null }
    }
}
