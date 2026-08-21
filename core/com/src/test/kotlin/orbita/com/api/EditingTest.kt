// Эталон spec/editing_semantics.py (26 проверок) один в один на настоящей базе.
//
// Эталон работает со списком словарей; здесь тот же порядок операций идёт через
// хранилище с интервальной версионностью, поэтому версия — строка колонки,
// а «объект остаётся» проверяется чтением из базы, а не длиной списка.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.model.CoreType
import orbita.mod.model.Lifecycle
import orbita.mod.schema.SchemaRegistry
import orbita.mod.store.IdReuseException
import orbita.mod.store.VersionConflictException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class EditingTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val editing = Editing(boundary, mapper)

    private val ENGINEER_A = "инженер А"
    private val ENGINEER_B = "инженер Б"

    @BeforeEach
    fun clean() = TestDb.truncateAll()

    /** Нужда как представитель вида: правила у видов свои, механика правки общая. */
    private fun needDraft(statement: String = "Система должна обеспечивать доставку телеметрии."): ObjectNode {
        val doc = mapper.createObjectNode()
        doc.put("statement", statement)
        doc.putObject("stakeholder").put("name", "Оператор сети").put("role", "operator").put("priority", 2)
        return doc
    }

    // ---------- Создание ----------

    @Test
    @DisplayName("создание: идентификатор, черновик, происхождение, автор, версия")
    fun создание() {
        val obj = editing.create(CoreType.Need, needDraft(), author = ENGINEER_A)

        assertEquals("ND-0001", obj.id) { "объект получает идентификатор" }
        assertEquals(Lifecycle.Draft, obj.status) { "объект создаётся черновиком" }
        assertEquals(SOURCE_MANUAL, obj.doc.path("provenance").path("source").asText()) {
            "происхождение — ручной ввод"
        }
        assertEquals(ENGINEER_A, obj.doc.path("provenance").path("author").asText()) {
            "автор зафиксирован"
        }
        assertEquals("1", obj.version) { "версия начинается с единицы" }
        // автор правки живёт и в колонке хранилища: по ней строится история
        assertEquals(ENGINEER_A, obj.createdBy)
    }

    @Test
    @DisplayName("создание: повторный идентификатор отклонён")
    fun повторный_идентификатор() {
        editing.create(CoreType.Need, needDraft(), author = ENGINEER_A)
        val same = needDraft("Вторая нужда с тем же идентификатором дозволена быть не должна.")
        same.put("id", "ND-0001")
        assertThrows(IdReuseException::class.java) {
            editing.create(CoreType.Need, same, author = ENGINEER_B)
        }
    }

    @Test
    @DisplayName("создание: следующий идентификатор не переиспользует отменённый")
    fun идентификаторы_не_переиспользуются() {
        editing.create(CoreType.Need, needDraft(), author = ENGINEER_A)
        val second = editing.create(CoreType.Need, needDraft("Вторая нужда проекта, отменяемая ниже."), ENGINEER_A)
        editing.cancel(second.id, author = ENGINEER_A)
        assertEquals("ND-0003", editing.nextId(CoreType.Need)) {
            "отменённый ND-0002 своего имени не отдаёт (TZ-MOD-007)"
        }
    }

    // ---------- Изменение и одновременная работа ----------

    @Test
    @DisplayName("изменение: правка на актуальной версии принята, версия увеличена")
    fun правка_на_актуальной_версии() {
        val obj = editing.create(CoreType.Need, needDraft(), author = ENGINEER_A)
        val changes = mapper.createObjectNode()
        changes.putObject("stakeholder").put("name", "Оператор сети").put("role", "operator").put("priority", 1)

        val updated = editing.update(CoreType.Need, obj.id, changes, baseVersion = "1", author = ENGINEER_A)
        assertEquals("2", updated.version) { "версия увеличена" }
        assertEquals(1, updated.doc.path("stakeholder").path("priority").asInt()) { "правка принята" }
        assertEquals("2", updated.doc.path("lifecycle").path("version").asText()) {
            "версия документа не расходится с версией хранилища"
        }
    }

    @Test
    @DisplayName("одновременная работа: правка на устаревшей версии отклонена с показом чужой")
    fun конфликт_версий() {
        val obj = editing.create(CoreType.Need, needDraft(), author = ENGINEER_A)
        val byA = mapper.createObjectNode().put("statement", "Формулировка, записанная инженером А.")
        editing.update(CoreType.Need, obj.id, byA, baseVersion = "1", author = ENGINEER_A)

        val byB = mapper.createObjectNode().put("statement", "Формулировка, записанная инженером Б.")
        val conflict = assertThrows(VersionConflictException::class.java) {
            editing.update(CoreType.Need, obj.id, byB, baseVersion = "1", author = ENGINEER_B)
        }

        assertEquals("1", conflict.yourBase) { "названа версия, на которой основана правка" }
        assertEquals("2", conflict.currentVersion) { "названа актуальная версия" }
        assertEquals(ENGINEER_A, conflict.changedBy) { "показано, кто изменил" }
        assertEquals(
            "Формулировка, записанная инженером А.",
            conflict.theirValues.getValue("statement").asText(),
        ) { "показано чужое значение" }

        val stored = boundary.objects.current(obj.id)!!
        assertEquals("Формулировка, записанная инженером А.", stored.doc.path("statement").asText()) {
            "чужая правка не затёрта"
        }
    }

    @Test
    @DisplayName("одновременная работа: повтор с актуальной версией проходит, автор зафиксирован")
    fun повтор_с_актуальной_версией() {
        val obj = editing.create(CoreType.Need, needDraft(), author = ENGINEER_A)
        editing.update(
            CoreType.Need, obj.id,
            mapper.createObjectNode().put("statement", "Формулировка, записанная инженером А."),
            baseVersion = "1", author = ENGINEER_A,
        )
        val second = editing.update(
            CoreType.Need, obj.id,
            mapper.createObjectNode().put("statement", "Формулировка, записанная инженером Б."),
            baseVersion = "2", author = ENGINEER_B,
        )
        assertEquals("3", second.version) { "повтор с актуальной версией проходит" }
        assertEquals(ENGINEER_B, second.createdBy) { "последний автор зафиксирован" }
        assertEquals(ENGINEER_A, boundary.objects.history(obj.id)[1].createdBy) {
            "автор каждой версии остаётся в истории"
        }
    }

    // ---------- Базирование и правила ----------

    @Test
    @DisplayName("базирование: неполный черновик существует, но не базируется")
    fun неполный_черновик_не_базируется() {
        // Черновик как он выходит из формы первым заходом: формулировка есть,
        // показатель и план верификации ещё не назначены.
        val draft = requirementDraft()
        draft.remove("verification_events")
        draft.remove("mop")
        draft.put("statement", "Аппарат ограничен по сухой массе.")
        val stored = editing.create(CoreType.Requirement, draft, author = ENGINEER_A)

        val issues = editing.promotionIssues(stored.id)
        assertTrue(issues.size >= 2) { "названы все причины, а не первая: $issues" }
        assertNotNull(boundary.objects.current(stored.id)) { "неполный черновик существует" }
        assertEquals(Lifecycle.Draft, boundary.objects.current(stored.id)!!.status)
    }

    @Test
    @DisplayName("базирование: полный объект базируется; правила те же, что для импорта и ИИ")
    fun полный_объект_базируется() {
        val stored = editing.create(CoreType.Requirement, requirementDraft(), author = ENGINEER_A)
        assertEquals(emptyList<String>(), editing.promotionIssues(stored.id)) {
            "полный объект базируется"
        }
        // те же функции: фильтр предложений ИИ спрашивает у core/req то же самое
        assertEquals(
            boundary.req.baselineIssues("requirement", boundary.objects.current(stored.id)!!.doc),
            editing.promotionIssues(stored.id),
        ) { "правила те же, что для импорта и предложений ИИ" }
    }

    @Test
    @DisplayName("базирование: правка базированного объекта блокируется с названной причиной")
    fun правка_базированного_блокируется() {
        val stored = editing.create(CoreType.Requirement, requirementDraft(), author = ENGINEER_A)
        boundary.req.promote(stored.id, Lifecycle.Baseline, createdBy = ENGINEER_A)
        val based = boundary.objects.current(stored.id)!!

        val blocked = assertThrows(BaselineEditBlockedException::class.java) {
            editing.update(
                CoreType.Requirement, stored.id,
                mapper.createObjectNode().put("statement", "Иная формулировка требования, вписанная мимо процедуры."),
                baseVersion = based.version, author = ENGINEER_A,
            )
        }
        assertTrue(blocked.reason.contains("процедуру")) { "названа причина блокировки: ${blocked.reason}" }
    }

    // ---------- Удаление и отмена ----------

    @Test
    @DisplayName("удаление: статус Cancelled, объект остаётся, версия увеличена")
    fun отмена_объекта() {
        val obj = editing.create(CoreType.Need, needDraft(), author = ENGINEER_A)
        editing.update(
            CoreType.Need, obj.id,
            mapper.createObjectNode().put("statement", "Формулировка после первой правки инженера А."),
            baseVersion = "1", author = ENGINEER_A,
        )
        val cancelled = editing.cancel(obj.id, author = ENGINEER_A)

        assertEquals(Lifecycle.Cancelled, cancelled.status) { "удаление даёт статус Cancelled" }
        assertEquals("3", cancelled.version) { "версия увеличена при отмене" }
        val stored = boundary.objects.current(obj.id)
        assertNotNull(stored) { "объект остаётся в хранилище" }
        assertEquals(Lifecycle.Cancelled, stored!!.status)
    }

    @Test
    @DisplayName("отмена действия: история доступна, предыдущая версия восстановима")
    fun отмена_действия() {
        val obj = editing.create(CoreType.Need, needDraft(), author = ENGINEER_A)
        editing.update(
            CoreType.Need, obj.id,
            mapper.createObjectNode().put("statement", "Формулировка, записанная инженером А."),
            baseVersion = "1", author = ENGINEER_A,
        )
        editing.update(
            CoreType.Need, obj.id,
            mapper.createObjectNode().put("statement", "Формулировка, записанная инженером Б."),
            baseVersion = "2", author = ENGINEER_B,
        )

        assertEquals(3, editing.history(obj.id).size) { "история версий доступна" }
        val prev = boundary.objects.previous(obj.id)!!
        assertEquals("Формулировка, записанная инженером А.", prev.doc.path("statement").asText()) {
            "предыдущая версия извлекается"
        }

        val undone = editing.undo(obj.id, author = ENGINEER_B)!!
        assertEquals("Формулировка, записанная инженером А.", undone.doc.path("statement").asText()) {
            "содержание предыдущей версии восстановлено"
        }
        assertEquals("4", undone.version) { "откат сам является версией: история не переписана" }
        assertEquals(4, editing.history(obj.id).size)
    }

    @Test
    @DisplayName("отмена действия: для единственной версии отмены нет")
    fun отмена_единственной_версии() {
        val obj = editing.create(CoreType.Need, needDraft(), author = ENGINEER_A)
        assertNull(boundary.objects.previous(obj.id)) { "откатывать не к чему" }
        assertNull(editing.undo(obj.id, author = ENGINEER_A))
    }

    /**
     * Требование, проходящее качество, TBD и план верификации (CR-002/CR-003).
     * Форма взята с требования эталонного демо-проекта: собственный набор полей
     * разошёлся бы со схемой, а вместе с ней и с проверяемым поведением.
     */
    private fun requirementDraft(): ObjectNode {
        val doc = mapper.createObjectNode()
        doc.put("level", "system")
        doc.put("statement", "Сухая масса космического аппарата не должна превышать 100 кг.")
        doc.put("category", "performance")
        doc.put("owner", "вед. системный инженер")
        val mop = doc.putObject("mop")
        mop.put("name", "Сухая масса").put("operator", "le").put("rollup", "sum")
        mop.putObject("value").put("value", 100.0).put("unit", "kg")
        doc.putArray("traces_up").addObject().put("ref", "ND-0001")
        doc.putObject("lifecycle").put("status", "Draft").put("version", "1")

        val calc = doc.putArray("verification_events").addObject()
        calc.put("id", "VE-0001").put("method", "analysis").put("kind", "preliminary")
            .put("phase", "PhaseA").put("level", "system").put("closes", false).put("status", "planned")
            .put("approach", "Суммирование масс подсистем по MEL с резервами по зрелости элементов")
            .put("means", "Сводный перечень оборудования (MEL)")
        val test = (doc.path("verification_events") as com.fasterxml.jackson.databind.node.ArrayNode).addObject()
        test.put("id", "VE-0002").put("method", "test").put("kind", "qualification")
            .put("phase", "PhaseD").put("level", "system").put("closes", true).put("status", "planned")
            .put("design_version", "v1")
            .put("approach", "Взвешивание собранного аппарата после интеграции с фиксацией в протоколе")
            .put("means", "Весовой стенд, поверенное оборудование")
        return doc
    }
}
