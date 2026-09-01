// Граница модуля (TZ-MOD-002, TZ-COM-007): каждый вход валидируется по схеме
// целевого типа; невалидные данные отклоняются с путём до поля, правилом и,
// для нарушений Р1–Р9, идентификатором ADR (TZ-MOD-003). Объекты ядра шага 2
// принимаются через контур требований: связи выводятся из документов.
package orbita.com.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.mod.model.CoreType
import orbita.mod.model.Lifecycle
import orbita.mod.model.inputVersionsComplete
import orbita.mod.model.resolveScenario
import orbita.mod.schema.SchemaRegistry
import orbita.mod.schema.SchemaValidationException
import orbita.mod.schema.ValidationError
import orbita.mod.store.ModelViolationException
import orbita.mod.store.tx
import orbita.mod.store.LinkStore
import orbita.mod.store.ObjectStore
import orbita.mod.store.ParamStore
import orbita.mod.store.ResultStore
import orbita.mod.store.StoredObject
import orbita.bal.VisibilityPrecompute
import orbita.net.LoRaWanAdapter
import orbita.net.validateAdapterContract
import orbita.out.DemandViews
import orbita.out.Matrices
import orbita.out.MaturityReports
import orbita.out.ScreenViews
import orbita.out.SpacecraftViews
import orbita.ai.PromptPackageBuilder
import orbita.ai.ProposalScreening
import orbita.ai.ResponseParser
import orbita.out.WizardViews
import orbita.req.ReqService
import orbita.usr.TerminalRules
import java.sql.Connection

class Boundary(private val registry: SchemaRegistry, private val conn: Connection) {

    val objects = ObjectStore(conn)
    val auth = orbita.mod.store.AuthStore(conn)
    val links = LinkStore(conn)
    val params = ParamStore(conn)
    val results = ResultStore(conn)
    val req = ReqService(conn, registry)
    val matrices = Matrices(req)
    val maturity = MaturityReports(req)

    /** Готовые строки экранов: расчётов в клиенте нет (STEP-6 §3.2). */
    val screens = ScreenViews(req)

    /** Экраны мастера Ш1–Ш7 (STEP-7-9 §9.1). */
    val wizard = WizardViews(req)

    /** Экран 4: карта спроса — слои и веса считаются здесь (TZ-USR-004). */
    val demand = DemandViews()

    /** Экран 5: бюджеты аппарата — масса, энергетика, линии, маяк (TZ-KA). */
    val spacecraft = SpacecraftViews()

    /**
     * ИИ-контур (TZ-AI). Генерация происходит ВНЕ системы: инженер копирует
     * пакет во внешний интерфейс LLM и вставляет ответ обратно. Здесь —
     * сборка пакета, локальный разбор и структурный фильтр.
     */
    val packages = PromptPackageBuilder(registry = registry)
    val parser = ResponseParser()
    val screening = ProposalScreening()

    /** Расчётный контур шага 3: адаптер протокола и предрасчёт геометрии. */
    val protocolAdapter = LoRaWanAdapter()
    val visibility = VisibilityPrecompute()

    /** Импорт — третий канал в модель (шаг 14, ADR-024): правовой режим источников. */
    val importPolicy = orbita.mod.model.ImportPolicy()

    /**
     * Рабочий слой (шаг 15): ввод, правка и отмена через интерфейс. Правила —
     * те же, что у приёма и импорта; собственных у форм нет.
     */
    val editing: Editing by lazy { Editing(this) }

    /** Спина процесса (блок B, ADR-029): прохождение точек и возвраты. */
    val gatePassing: GatePassing by lazy { GatePassing(this) }

    /** Процесс к точке (МВП-П1): задания-разрывы и личный разрез готовности. */
    val processTasks: ProcessTasks by lazy { ProcessTasks(this) }

    /** Сравнение построений (МВП-М2) — из того же интеграла видимости. */
    val compareMetrics: orbita.bal.CompareMetrics by lazy {
        orbita.bal.CompareMetrics(visibility)
    }

    /** Служба ИИ (П5): профиль → промпт → вызов → фильтр → журнал. */
    val ai: AiService by lazy { AiService(this, orbita.ai.HttpProviderTransport()) }

    /** Соединение — журналу вызовов и прочим служебным хранилищам. */
    val connection: Connection get() = conn

    /** Реестр схем — службе ИИ: предложение проверяется схемой целевого вида. */
    val schemas: SchemaRegistry get() = registry

    private val terminalRules = TerminalRules(registry)

    /**
     * Приём объекта ядра. Типы контура требований идут через ReqService —
     * прикладные правила (Р9, flow down) и связи из документа; сценарий
     * сохраняется напрямую после валидации по схеме.
     */
    fun ingest(
        type: CoreType,
        json: String,
        createdBy: String = "api",
        projectId: String = orbita.mod.store.ObjectStore.DEFAULT_PROJECT,
    ): StoredObject = when (type) {
        CoreType.Need -> req.ingestNeed(json, createdBy, projectId)
        CoreType.Service -> req.ingestService(json, createdBy, projectId)
        CoreType.Requirement -> req.ingestRequirement(json, createdBy, projectId)
        CoreType.Component -> req.ingestComponent(json, createdBy, projectId)
        // CR-003: свидетельства, валидации и интерфейсы — самостоятельные объекты
        CoreType.Evidence -> req.ingestEvidence(json, createdBy, projectId)
        CoreType.Validation -> req.ingestValidation(json, createdBy, projectId)
        CoreType.Interface -> req.ingestInterface(json, createdBy, projectId)
        CoreType.Risk -> req.ingestRisk(json, createdBy, projectId)
        CoreType.Conops -> req.ingestConops(json, createdBy, projectId)
        CoreType.MissionGoal -> req.ingestMissionGoal(json, createdBy, projectId)
        // Блок C: замечание обзора несёт правило закрытия сверх схемы
        CoreType.ReviewItem -> {
            val doc = parse(json)
            registry.require(type.schemaName, doc)
            req.requireApplicationRules("review_item", doc)
            store(type, doc, createdBy, projectId)
        }
        // Блок C: альтернативы, стоимость, ODA, WBS — схема и статусная модель;
        // ADR-030: исходный документ — тем же общим путём
        CoreType.Alternative, CoreType.CostEstimate, CoreType.Oda, CoreType.WbsElement,
        CoreType.AiProfile, CoreType.SourceDocument,
        // Библиотечные полки (СТРУКТУРА-БИБЛИОТЕКИ §2) — общим путём: схема
        // и статусная модель, прикладных правил сверх схемы у полок нет
        CoreType.NormativeDocument, CoreType.MissionClass, CoreType.StakeholderProfile -> {
            val doc = parse(json)
            validate(type, doc)
            store(type, doc, createdBy, projectId)
        }
        CoreType.ComponentUsage -> {
            val doc = parse(json)
            validate(type, doc)   // схема + правила В2.1: дерево, ацикличность
            val stored = store(type, doc, createdBy, projectId)
            // появление ЕДИНСТВЕННОГО корня раздаёт носителя проектным
            // требованиям (ОТВЕТЫ-Т1-ДОП §2) — автором действия, не службой
            req.autoAllocateOnRoot(projectId, createdBy)
            stored
        }
        CoreType.TypicalRisk, CoreType.LibraryFragment, CoreType.DocumentTemplate,
        CoreType.SectionText, CoreType.SavedView, CoreType.Task, CoreType.UnitRegistry,
        // Ф-13: стейкхолдер проекта — простой объект по схеме, как полочные виды
        CoreType.Glossary, CoreType.GeoMask, CoreType.PropertyForm, CoreType.Stakeholder,
        // Чек-лист обзора — данные полки: инспекция ведётся по нему, но
        // сам он такой же объект по схеме, как прочие полочные виды
        CoreType.PhaseTask, CoreType.ReviewChecklist, CoreType.QualityDictionary -> {
            val doc = parse(json)
            registry.require(type.schemaName, doc)
            store(type, doc, createdBy, projectId)
        }
        // Решение проверяется правилом C3 сверх схемы; технология, проект и
        // выпуск документа — схема и статусная модель, общий путь сохранения
        CoreType.Decision -> {
            val doc = parse(json)
            registry.require(type.schemaName, doc)
            req.requireApplicationRules("decision", doc)
            store(type, doc, createdBy, projectId)
        }
        CoreType.DocumentIssue -> {
            val doc = parse(json)
            registry.require(type.schemaName, doc)
            // Шаг 17 C5: создание — это выпуск. Сразу approved не бывает:
            // одобрение — отдельное действие поверх выпущенного.
            if (doc.path("status").asText() != "issued") {
                throw orbita.mod.store.ModelViolationException(
                    "C5: выпуск создаётся со статусом issued; approved — правкой выпущенного"
                )
            }
            store(type, doc, createdBy, projectId)
        }
        CoreType.Technology, CoreType.Project -> {
            val doc = parse(json)
            registry.require(type.schemaName, doc)
            // круг 2 стартового потока: порядок дат вех — одно правило
            req.requireApplicationRules(type.dbType, doc)
            // проект — контейнер сам себе (ADR-022): контекст ему не нужен
            val owner = if (type == CoreType.Project) doc["id"].asText() else projectId
            store(type, doc, createdBy, owner)
        }
        CoreType.Scenario -> {
            val doc = parse(json)
            registry.require(type.schemaName, doc)
            if (doc is ObjectNode) stampInputVersions(doc)
            // Ссылки разрешаются ДО сохранения: сценарий со ссылкой в никуда
            // не расчётный случай, а обещание невоспроизводимого результата.
            val problems = resolveScenario(doc) { ref -> objects.current(ref)?.let { CoreType.byDbType(it.type) } } +
                inputVersionsComplete(doc)
            if (problems.isNotEmpty()) {
                throw ModelViolationException("сценарий ${doc.path("id").asText()}: " + problems.joinToString("; "))
            }
            requireRefsInProject(doc, projectId)
            store(type, doc, createdBy, projectId)
        }
        // CR-005/ADR-021: входы моделирования. Прикладных правил связей у них
        // нет — только схема и статусная модель, поэтому общий путь сохранения.
        CoreType.Constellation, CoreType.Spacecraft, CoreType.DemandMap,
        CoreType.TerminalProfile, CoreType.GroundStations, CoreType.ProtocolAdapter -> {
            val doc = parse(json)
            registry.require(type.schemaName, doc)
            if (type == CoreType.TerminalProfile) {
                // класс терминала подчиняется правилам TZ-USR-001 сверх схемы
                terminalRules.validate(doc).takeIf { it.isNotEmpty() }
                    ?.let { throw SchemaValidationException(type.schemaName, it) }
            }
            store(type, doc, createdBy, projectId)
        }
    }

    /**
     * ADR-022: ссылки сценария не создают связей, поэтому граница проекта
     * проверяется здесь — сценарий одного проекта не считает по входам другого.
     */
    private fun requireRefsInProject(doc: JsonNode, projectId: String) {
        orbita.mod.model.SCENARIO_REF_FIELDS.keys.forEach { field ->
            val ref = doc.path(field).asText("")
            if (ref.isBlank()) return@forEach
            val target = objects.current(ref) ?: return@forEach
            if (target.projectId != projectId) {
                throw ModelViolationException(
                    "ADR-022: сценарий проекта $projectId ссылается на $ref " +
                        "чужого проекта ${target.projectId}"
                )
            }
        }
    }

    /**
     * Проверка документа БЕЗ сохранения — теми же функциями, что и приём:
     * схема вида плюс прикладные правила (`requireApplicationRules`), плюс
     * особенности отдельных видов. Используется правкой через интерфейс
     * (шаг 15 §1.3), чтобы у форм не завёлся собственный набор правил.
     */
    fun validate(type: CoreType, doc: JsonNode) {
        // Интерфейс схемы своего вида не имеет: в CoreType он делит схему
        // с элементом, а его id ей не соответствует — приём тоже её не требует.
        if (type != CoreType.Interface) registry.require(type.schemaName, doc)
        req.requireApplicationRules(type.dbType, doc)
        when (type) {
            CoreType.TerminalProfile -> terminalRules.validate(doc).takeIf { it.isNotEmpty() }
                ?.let { throw SchemaValidationException(type.schemaName, it) }

            CoreType.Scenario -> {
                if (doc is ObjectNode) stampInputVersions(doc)
                val problems = resolveScenario(doc) { ref -> objects.current(ref)?.let { CoreType.byDbType(it.type) } } +
                    inputVersionsComplete(doc)
                if (problems.isNotEmpty()) {
                    throw ModelViolationException(
                        "сценарий ${doc.path("id").asText()}: " + problems.joinToString("; ")
                    )
                }
            }

            else -> {}
        }
    }

    /** Сохранение объекта, у которого прикладных правил связей нет: схема и статус. */
    private fun store(type: CoreType, doc: JsonNode, createdBy: String, projectId: String): StoredObject {
        val lifecycle = doc.path("lifecycle")
        return objects.create(
            id = doc["id"].asText(),
            type = type.dbType,
            doc = doc,
            status = Lifecycle.valueOf(lifecycle.path("status").asText(Lifecycle.Draft.name)),
            version = lifecycle.path("version").asText("1"),
            createdBy = createdBy,
            projectId = projectId,
        )
    }

    /**
     * Валидация контракта между модулями без сохранения (TZ-COM-007).
     * Профиль терминала дополнительно проходит правила классов (TZ-USR-001).
     */
    fun validateContract(schemaName: String, json: String): List<ValidationError> = when (schemaName) {
        "contracts/terminal-profile" -> terminalRules.validate(parse(json))
        // адаптер без нисходящего канала несовместим с Р5/Р6 — правило вне схемы (TZ-NET-002)
        "contracts/protocol-adapter" -> registry.validate(schemaName, parse(json)) +
            validateAdapterContract(parse(json)).map {
                ValidationError("/mac/downlink_supported", "compatibility", it, adr = "ADR-005 (Р5)")
            }
        else -> registry.validate(schemaName, parse(json))
    }

    /** Границы «всё или ничего» для пакетных операций (импорт, ADR-024). */
    fun <T> transaction(block: () -> T): T = conn.tx(block)

    /** Ошибки схемы вида БЕЗ исключения — пакетный отчёт до записи. */
    fun schemaProblems(type: CoreType, doc: JsonNode): List<ValidationError> =
        if (type == CoreType.Interface) emptyList() else registry.validate(type.schemaName, doc)

    /** Проверка по имени схемы: Д2 — ответ службы схемой без объекта модели. */
    fun schemaProblems(schemaName: String, doc: JsonNode): List<ValidationError> =
        registry.validate(schemaName, doc)

    fun schemaNames(): List<String> = registry.names

    /** Схема вида как документ — правке нужны обязательные поля (шаг 15). */
    fun rawSchema(name: String): JsonNode = registry.raw(name)

    /** Допускает ли схема вида поле верхнего уровня — служебные поля правки
     *  навешиваются только тем, чья схема их несёт. */
    fun schemaAllows(type: CoreType, field: String): Boolean =
        registry.raw(type.schemaName).path("properties").has(field)

    /**
     * Схема с раскрытыми ссылками — для форм ввода (шаг 15 §2). Форма строится
     * ПО СХЕМЕ: перечень полей, обязательность и допустимые значения приходят
     * из нормативной структуры, а не переписываются в клиент, где разошлись бы
     * с ней молча. Клиенту незачем ходить за общими схемами отдельно.
     */
    fun bundledSchema(name: String): JsonNode = inline(registry.raw(name), mutableSetOf(name))

    private fun inline(node: JsonNode, seen: MutableSet<String>): JsonNode {
        if (node.isArray) {
            val arr = (node as com.fasterxml.jackson.databind.node.ArrayNode).deepCopy()
            for (i in 0 until arr.size()) arr.set(i, inline(arr[i], seen))
            return arr
        }
        if (!node.isObject) return node
        val obj = (node as com.fasterxml.jackson.databind.node.ObjectNode).deepCopy()
        val ref = obj.path("\$ref").asText("")
        if (ref.startsWith(SCHEMA_URI_PREFIX)) {
            val target = ref.removePrefix(SCHEMA_URI_PREFIX).removeSuffix(".schema.json")
            // Циклическая ссылка оставляется ссылкой: форма покажет её полем
            // структурного ввода, а не уйдёт в бесконечную подстановку.
            if (target in seen) return obj
            val resolved = inline(registry.raw(target), (seen + target).toMutableSet())
            val merged = (resolved as com.fasterxml.jackson.databind.node.ObjectNode).deepCopy()
            merged.remove(listOf("\$id", "\$schema"))
            // собственные поля ссылки (description и т.п.) важнее общих
            obj.properties().forEach { (k, v) -> if (k != "\$ref") merged.set<JsonNode>(k, v) }
            return merged
        }
        obj.properties().toList().forEach { (k, v) -> obj.set<JsonNode>(k, inline(v, seen)) }
        return obj
    }

    private companion object {
        const val SCHEMA_URI_PREFIX = "https://kis.local/schemas/"
    }

    /**
     * Показ автора (круг 2 портфеля §1.3) — ОДИН на систему: карта авторов →
     * учётка → отображаемое имя. «system» на экраны не выходит: это
     * безымянный служебный след, а не человек. Живёт здесь, потому что имена
     * авторов показывает не только портфель — активность схемы потока тоже.
     */
    fun humanAuthor(name: String): String {
        val login = auth.authorMap()[name] ?: name.takeIf { auth.displayNameOf(it) != null }
        return login?.let { auth.displayNameOf(it) }
            ?: if (orbita.req.ServiceAuthors.isService(name)) "служебная запись" else name
    }

    private fun parse(json: String): JsonNode = registry.parse(json)
    /**
     * Фиксация версий входов сценария (V008, TZ-MOD-007): версию знает система,
     * а не инженер — форма ввода словаря версий и не нужна, и вредна: версия,
     * набранная из головы, разойдётся с хранилищем молча. Явно заданные версии
     * НЕ перетираются: это заявка «считаю по той версии», и расхождение с
     * текущей — предмет stale-каскада, а не тихой правки.
     */
    private fun stampInputVersions(doc: ObjectNode) {
        val versions = doc.withObject("/input_versions")
        orbita.mod.model.SCENARIO_REF_FIELDS.keys.forEach { field ->
            val ref = doc.path(field).asText("")
            if (ref.isNotBlank() && versions.path(ref).isMissingNode) {
                objects.current(ref)?.let { versions.put(ref, it.version) }
            }
        }
    }

}
