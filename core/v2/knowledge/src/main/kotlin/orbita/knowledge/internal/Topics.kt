// Слияние тем: один предмет — одна тема, как бы его ни звали документы.
//
// Тема — АДРЕС среза для синтеза. Пока «телеметрия» из записки и «сбор
// телеметрии» из ТЗ живут двумя темами, срез рвётся по документам, и синтез
// перестаёт идти «по всему полю». Слияние чинит адрес и НИЧЕГО не
// переписывает: факты остаются при своих темах, а чтение и счёт идут по
// цепочке `merged_into` до головы. Отменить слияние поэтому — снять одно поле,
// а не собирать факты обратно.
//
// Сливает ЧЕЛОВЕК. Тождество тем ИИ видит и предлагает связью `same_as`, но
// щелчок делает инженер: «похоже» без «чем именно» — брак, а принятое не
// меняется молча (ОНТОЛОГИЯ-ФОРМИРОВАНИЯ, rights и invariants).
package orbita.knowledge.internal

import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry
import orbita.kernel.api.Provenance

internal class Topics(
    private val store: EntityStore,
    private val links: LinkRegistry?,
) {

    /** Темы области одним чтением: цепочки считаются по снимку, а не по запросу на тему. */
    fun all(область: Area): List<Entity> = store.list(область, "topic")

    /**
     * Головы — то, что видно человеку: слитая тема с экрана уходит в свою
     * голову. Голова считается тем же ходом, что и цепочка: тема, слитая в
     * НЕСУЩЕСТВУЮЩУЮ (перенос проекта, правка мимо слияния), остаётся сама
     * себе головой — иначе её факты пропали бы с экрана вместе с ней.
     */
    fun heads(темы: List<Entity>): List<Entity> {
        val поКоду = темы.associateBy { it.code }
        return темы.filter { head(поКоду, it).code == it.code }
    }

    /**
     * Голова цепочки слияний.
     *
     * Кольцо в данных (правка мимо слияния, перенос проекта) чтение тем
     * вешать не имеет права: дойдя до уже пройденной темы, возвращаем то,
     * что есть, — экран покажет тему, а не повиснет.
     */
    fun head(темы: List<Entity>, тема: Entity): Entity = head(темы.associateBy { it.code }, тема)

    fun head(поКоду: Map<String, Entity>, тема: Entity): Entity {
        var текущая = тема
        val пройдено = mutableSetOf(текущая.code)
        while (true) {
            val дальше = слитаВ(текущая).ifBlank { return текущая }
            val следующая = поКоду[дальше]?.takeIf { пройдено.add(it.code) } ?: return текущая
            текущая = следующая
        }
    }

    /** Голова → коды всех тем её цепочки, вместе с самой головой: по ним считаются факты. */
    fun chains(темы: List<Entity>): Map<String, Set<String>> {
        val поКоду = темы.associateBy { it.code }
        val итог = mutableMapOf<String, MutableSet<String>>()
        темы.forEach { т -> итог.getOrPut(head(поКоду, т).code) { mutableSetOf() }.add(т.code) }
        return итог
    }

    /**
     * Слить тему в другую. Возвращает ГОЛОВУ цепочки — тот адрес, по
     * которому теперь читаются факты обеих.
     *
     * @param reason чем именно темы оказались одним предметом; уходит в
     *   обоснование связи `same_as` — связь без причины неотличима от случайной
     */
    fun merge(область: Area, topic: String, into: String, author: String, reason: String): Entity {
        // Слияние — решение человека, и автор у него обязан быть назван: ИИ
        // предлагает, сливает клик. Без автора слияние неотличимо от
        // самовольного, а отменять его придётся тому, кто его не делал.
        require(author.isNotBlank()) {
            "слияние тем без автора не ставится: ИИ предлагает — сливает человек"
        }
        val темы = all(область)
        val тема = темы.firstOrNull { it.code == topic }
            ?: throw IllegalArgumentException("темы «$topic» нет в проекте")
        val цель = темы.firstOrNull { it.code == into }
            ?: throw IllegalArgumentException("темы «$into» нет в проекте: слить можно только в существующую")
        val голова = head(темы, цель)
        require(голова.code != тема.code) {
            "тема «$topic» и так голова цепочки «$into»: слияние закольцевалось бы, " +
                "и факты потеряли бы адрес"
        }
        val было = слитаВ(тема)
        require(было.isBlank() || было == голова.code) {
            "тема «$topic» уже слита в «$было»: второй головы у темы не бывает — " +
                "сначала снимите прежнее слияние"
        }
        val адрес = разрешена(тема)
        val адресГоловы = разрешена(голова)
        // Две темы, разрешённые в РАЗНЫЕ сущности, — не один предмет: слить их
        // значит выбрать за человека, какая сущность настоящая.
        require(адрес.isBlank() || адресГоловы.isBlank() || адрес == адресГоловы) {
            "тема «$topic» разрешена в «$адрес», а «${голова.code}» — в «$адресГоловы»: " +
                "слияние выбрало бы за человека, какая сущность настоящая"
        }
        val причина = обоснование(тема, голова, author, reason)

        if (было != голова.code) {
            store.update(
                тема.id,
                (тема.doc.deepCopy() as ObjectNode).put("merged_into", голова.code),
                Provenance(Channel.MANUAL, author, source = голова.code),
            )
        }
        // Разрешение не теряется при слиянии: если адрес нашла слитая тема, а
        // голова ещё нет, он переезжает на голову — иначе принятые факты
        // слитой темы остались бы без адреса к точке.
        if (адрес.isNotBlank() && адресГоловы.isBlank()) {
            val документ = (голова.doc.deepCopy() as ObjectNode).put("resolved_to", адрес)
            тема.doc.path("resolved_kind").asText("").takeIf { it.isNotBlank() }
                ?.let { документ.put("resolved_kind", it) }
            store.update(голова.id, документ, Provenance(Channel.MANUAL, author, source = тема.code), status = "resolved")
        }
        // Тождество тем видно в модели связью, а не только полем карточки:
        // по `same_as` слияние читается тем же способом, каким его предлагали.
        связать(тема, голова, author, причина)
        return store.byCode(область, голова.code) ?: голова
    }

    private fun связать(тема: Entity, голова: Entity, author: String, причина: String) {
        val реестр = links ?: return
        val уже = реестр.from(тема.id, "same_as").any { it.to == голова.id } ||
            реестр.to(тема.id, "same_as").any { it.from == голова.id }
        if (уже) return
        реестр.link("same_as", тема.id, голова.id, Provenance(Channel.MANUAL, author), rationale = причина)
    }

    private fun обоснование(тема: Entity, голова: Entity, author: String, reason: String): String {
        val хвост = reason.trim()
        return "«${метка(тема)}» и «${метка(голова)}» — один предмет; слил $author" +
            (if (хвост.isBlank()) "" else ": $хвост")
    }

    private fun метка(тема: Entity): String = тема.doc.path("label").asText("").ifBlank { тема.code }

    private fun слитаВ(тема: Entity): String = тема.doc.path("merged_into").asText("")

    private fun разрешена(тема: Entity): String = тема.doc.path("resolved_to").asText("")
}
