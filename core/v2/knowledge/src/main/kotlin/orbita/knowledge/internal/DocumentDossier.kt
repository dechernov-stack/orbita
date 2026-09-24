// Документ как источник — целиком (шип 4 §3, РЕШЕНИЕ-ДОКУМЕНТ-ПЕРВИЧЕН §3–6).
//
// После разбора видно триста фактов и не видно, что принёс каждый источник,
// что из него принято и что за что зацепилось. Здесь документ снова виден:
// досье (резюме · прогоны · вклад · сущности · полезность), два взгляда
// («документ → что вышло», «сущность → на чём стоит»), откат вклада одним
// действием, приём целиком/по разделам/выборочно/«только в контекст»,
// взятие разобранного документа в другой проект без вызова модели и карта
// пробелов устава по двенадцати пунктам записки миссии.
//
// Ни одного вызова модели: всё считается по данным — фактам, связям
// `derived_from_fact` и прогонам разбора (`parse_run`).
package orbita.knowledge.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry
import orbita.kernel.api.Provenance
import orbita.knowledge.api.BasisFact
import orbita.knowledge.api.BasisView
import orbita.knowledge.api.Disposition
import orbita.knowledge.api.DocumentAccept
import orbita.knowledge.api.Dossier
import orbita.knowledge.api.DossierEntity
import orbita.knowledge.api.GapItem
import orbita.knowledge.api.GapMap
import orbita.knowledge.api.Intake
import orbita.knowledge.api.ParseRunView
import orbita.knowledge.api.RollbackReport
import orbita.knowledge.api.TakeReport

internal class DocumentDossier(
    private val store: EntityStore,
    private val links: LinkRegistry?,
    private val mapper: ObjectMapper,
    private val режимы: IntakeModes,
    private val связиФактов: FactLinks,
    private val темы: Topics,
    /** Приём знаний — для плана (приём документа) и диспозиций; свободный код — его же правило. */
    private val приём: Intake,
    private val следующий: (Area, String, String) -> String,
) {

    // --- досье ---------------------------------------------------------------

    fun dossier(project: String, material: String): Dossier {
        val область = Area.Project(project)
        val карточка = материал(область, material)
        val факты = живыеФакты(область, material)
        val прогоны = прогоны(область, material).map { п ->
            ParseRunView(
                п.code, п.doc.path("at").asText(""), п.doc.path("prompt_version").asText(""),
                п.doc.path("facts").size(), п.status, п.doc.path("rolled_back_by").asText("").ifBlank { null },
            )
        }
        // Пересечения с другими документами — по связям фактов и по смыслу
        // (субъект · утверждение · значение): подтверждён — supports/same_as с
        // фактом другого документа; спорен — contradicts или конфликт величин;
        // уникален — ни того, ни другого, и такого утверждения больше нигде нет.
        val чужиеКлючи = store.list(область, "fact")
            .filter { it.status != "cancelled" && it.doc.path("material").asText("").let { м -> м.isNotBlank() && м != material } }
            .map { ключСмысла(it.doc) }.toSet()
        var подтверждений = 0
        var противоречий = 0
        var уникальных = 0
        факты.forEach { ф ->
            val чужие = связиФактов.factLinks(область, ф.code).filter { с ->
                val другой = if (с.from == ф.code) с.to else с.from
                материалФакта(область, другой) != material
            }
            val подтверждён = чужие.any { it.type == "supports" || it.type == "same_as" }
            val спорен = чужие.any { it.type == "contradicts" } || ф.doc.path("conflicts").size() > 0
            if (подтверждён) подтверждений += 1
            if (спорен) противоречий += 1
            if (!подтверждён && !спорен && ключСмысла(ф.doc) !in чужиеКлючи) уникальных += 1
        }
        val сущности = сущностиНаФактах(факты).map { (с, основания) ->
            DossierEntity(
                с.code, с.kind, имя(с),
                onlyBasis = основания.all { it.doc.path("material").asText("") == material },
                scene = с.bornIn,
            )
        }
        val заменён = store.list(область, "material").firstOrNull { it.doc.path("supersedes").asText("") == material }?.code
        return Dossier(
            material = material,
            name = карточка.doc.path("name").asText(material),
            role = карточка.doc.path("role").asText("").ifBlank { null },
            rank = карточка.doc.path("rank").asText("").ifBlank { null },
            summary = карточка.doc.path("summary").asText("").ifBlank { null },
            version = карточка.version,
            chars = карточка.doc.path("chars").asInt(карточка.doc.path("text").asText("").length),
            blocks = Canon.of(карточка.doc.path("text").asText("")).blocks.size,
            supersedes = карточка.doc.path("supersedes").asText("").ifBlank { null },
            supersededBy = заменён,
            createdAt = карточка.createdAt.toString(),
            acceptMode = карточка.doc.path("accept_mode").asText("").ifBlank { null },
            runs = прогоны,
            factsByKind = факты.groupingBy { it.doc.path("kind").asText("") }.eachCount().toSortedMap(),
            byDisposition = факты.groupingBy { it.doc.path("disposition").asText("free") }.eachCount().toSortedMap(),
            unique = уникальных,
            confirms = подтверждений,
            contradicts = противоречий,
            entities = сущности,
            // Полезность СЧИТАЕТСЯ (РЕШЕНИЕ §3): сущностей + уникальных + подтверждений +
            // противоречий — слагаемые лежат рядом, число не приходится угадывать.
            usefulness = сущности.size + уникальных + подтверждений + противоречий,
            usefulnessNote = карточка.doc.path("usefulness").path("note").asText("").ifBlank { null },
        )
    }

    fun usefulnessNote(project: String, material: String, note: String, author: String): Dossier {
        val область = Area.Project(project)
        val карточка = материал(область, material)
        val документ = карточка.doc.deepCopy() as ObjectNode
        if (note.isBlank()) документ.remove("usefulness") else документ.putObject("usefulness").put("note", note.trim())
        store.update(карточка.id, документ, Provenance(Channel.MANUAL, author, source = material))
        return dossier(project, material)
    }

    // --- сущность → на чём стоит ----------------------------------------------

    fun basis(project: String, entity: String): BasisView {
        val область = Area.Project(project)
        val сущность = store.byCode(область, entity) ?: store.byCode(Area.Library, entity)
            ?: throw IllegalArgumentException("сущности «$entity» нет ни в проекте, ни на полке")
        val факты = links?.from(сущность.id, "derived_from_fact")
            ?.mapNotNull { store.byId(it.to) }?.filter { it.kind == "fact" }.orEmpty()
        val строки = факты.map { ф ->
            val код = ф.doc.path("material").asText("").ifBlank { null }
            val карточка = код?.let { store.byCode(область, it) }
            BasisFact(
                code = ф.code,
                kind = ф.doc.path("kind").asText(""),
                subject = ф.doc.path("subject").asText(""),
                predicate = ф.doc.path("predicate").asText(""),
                value = значение(ф.doc),
                quote = ф.doc.path("quote").asText("").ifBlank { null },
                anchor = ф.doc.path("anchor").asText("").ifBlank { null },
                material = код,
                materialName = карточка?.doc?.path("name")?.asText("")?.ifBlank { null },
                role = карточка?.doc?.path("role")?.asText("")?.ifBlank { null },
                rank = ф.doc.path("rank").asText("").ifBlank { карточка?.doc?.path("rank")?.asText("") }?.ifBlank { null },
                mark = ф.doc.path("mark").asText("И"),
                disposition = ф.doc.path("disposition").asText("free"),
                withdrawn = ф.status == "cancelled",
            )
        }
        val заметки = mutableListOf<String>()
        сущность.doc.path("notes").asText("").ifBlank { null }?.let { заметки += it }
        if (строки.isEmpty()) заметки += "оснований в поле знаний нет: запись заведена руками или до перестройки поля"
        return BasisView(сущность.code, сущность.kind, имя(сущность), строки, заметки)
    }

    // --- откат вклада ---------------------------------------------------------

    fun rollback(project: String, material: String, author: String, reason: String): RollbackReport {
        require(reason.isNotBlank()) {
            "откат без причины не делается: через год снятый документ без объяснения читается как случайность"
        }
        val область = Area.Project(project)
        val карточка = материал(область, material)
        val факты = живыеФакты(область, material)
        require(факты.isNotEmpty()) { "у документа «${карточка.doc.path("name").asText(material)}» нет живых фактов: откатывать нечего" }
        val снимаемые = факты.map { it.id }.toSet()
        val снятые = mutableListOf<String>()
        val оставленные = mutableListOf<String>()
        val провенанс = Provenance(Channel.MANUAL, author, source = material)
        val реестр = links
        if (реестр != null) {
            сущностиНаФактах(факты).forEach { (с, основания) ->
                val остальные = основания.filter { it.id !in снимаемые }
                val документ = с.doc.deepCopy() as ObjectNode
                if (остальные.isEmpty()) {
                    // Образована только из этого документа — уходит вместе с ним.
                    документ.put("notes", помета(документ, "снята откатом вклада документа $material: $reason"))
                    store.update(с.id, документ, провенанс, status = "cancelled")
                    снятые += с.code
                } else {
                    документ.put(
                        "notes",
                        помета(документ, "основание снято: документ $material откачен ($reason); стоит на ${остальные.joinToString(" · ") { it.code }}"),
                    )
                    store.update(с.id, документ, провенанс)
                    оставленные += с.code
                }
                // Нить к снятому факту снимается: доля знаний снятое основание не считает.
                реестр.from(с.id, "derived_from_fact").filter { it.to in снимаемые }.forEach { св ->
                    runCatching { реестр.unlink(св.id, провенанс) }
                }
            }
        }
        факты.forEach { ф ->
            store.update(ф.id, (ф.doc.deepCopy() as ObjectNode).put("source_note", "откат вклада документа: $reason"), провенанс, status = "cancelled")
        }
        val прогоны = прогоны(область, material).filter { it.status != "rolled_back" }
        прогоны.forEach { п ->
            store.update(п.id, (п.doc.deepCopy() as ObjectNode).put("rolled_back_by", author), провенанс, status = "rolled_back")
        }
        // План разбора этого документа больше не исполняется: его факты сняты.
        store.list(область, "intake_task")
            .filter { it.status != "cancelled" && it.doc.path("material").asText("") == material }
            .forEach { store.update(it.id, it.doc, провенанс, status = "cancelled") }
        store.update(
            карточка.id,
            (карточка.doc.deepCopy() as ObjectNode).put("notes", помета(карточка.doc, "вклад откачен ($author): $reason")),
            провенанс,
        )
        // Конфликты величин пересчитываются по живым фактам: снятый факт спорить не может.
        режимы.markConflicts(область, author)
        return RollbackReport(
            material = material,
            facts = факты.size,
            entitiesCancelled = снятые,
            entitiesKept = оставленные,
            runs = прогоны.size,
            note = "откат вклада «${карточка.doc.path("name").asText(material)}»: снято фактов ${факты.size}, " +
                "сущностей ${снятые.size}" +
                (if (оставленные.isEmpty()) "" else "; осталось на других основаниях ${оставленные.size} (помета «основание снято»)") +
                (if (прогоны.isEmpty()) "" else "; прогонов разбора откачено ${прогоны.size}"),
        )
    }

    // --- приём документа: целиком · по разделам · выборочно · в контекст · отклонить ---

    fun acceptDocument(
        project: String,
        material: String,
        mode: String,
        author: String,
        sections: List<String>,
        chosen: List<Int>,
        reason: String,
    ): DocumentAccept {
        val область = Area.Project(project)
        val карточка = материал(область, material)
        val режим = mode.trim()
        require(режим in РЕЖИМЫ_ПРИЁМА) { "режим приёма «$mode» неизвестен: ${РЕЖИМЫ_ПРИЁМА.joinToString(" · ")}" }
        val заметки = mutableListOf<String>()
        val созданные = mutableListOf<String>()
        var затронуто = 0
        when (режим) {
            "context_only", "rejected" -> {
                if (режим == "rejected") require(reason.isNotBlank()) { "отклонение документа без причины не ставится: назовите, почему он не берётся" }
                val цель = if (режим == "rejected") Disposition.REJECTED else Disposition.NOTED
                val повод = if (режим == "rejected") reason else "только в контекст: документ принят без сущностей"
                живыеФакты(область, material).forEach { ф ->
                    val было = Disposition.of(ф.doc.path("disposition").asText("free"))
                    // Принятое человеком решение по факту (adopted · assumed · contested) не переписывается пакетом.
                    if (было != Disposition.FREE && было != Disposition.NOTED && было != Disposition.SUPERSEDED) return@forEach
                    if (было == цель) return@forEach
                    приём.dispose(project, ф.code, цель, повод, author)
                    затронуто += 1
                }
            }
            else -> {
                val задание = store.list(область, "intake_task")
                    .filter { it.status != "cancelled" && it.doc.path("material").asText("") == material && it.doc.path("actions").size() > 0 }
                    .maxByOrNull { it.code }
                    ?: throw IllegalArgumentException(
                        "у документа «${карточка.doc.path("name").asText(material)}» нет плана разбора: сначала «Разобрать»; " +
                            "прочитанное документом принимается на вкладке постановки",
                    )
                val действия = задание.doc.path("actions")
                val все = (0 until действия.size()).toList()
                val номера = when (режим) {
                    "whole" -> все
                    "sections" -> {
                        require(sections.isNotEmpty()) { "приём по разделам без разделов: назовите якоря разделов канона (s3)" }
                        val разделы = sections.map { it.trim().substringBefore('#') }.toSet()
                        все.filter { i ->
                            действия.get(i).path("facts").any { к -> раздел(область, к.asText()) in разделы }
                        }
                    }
                    else -> {
                        require(chosen.isNotEmpty()) { "выборочный приём без выбора: отметьте действия плана" }
                        chosen.filter { it in все }
                    }
                }
                require(номера.isNotEmpty()) { "в выбранных разделах нет ни одного действия плана — принимать нечего" }
                val итог = приём.accept(project, задание.code, номера, author)
                созданные += итог.codes
                заметки += итог.notes
                затронуто = номера.size
            }
        }
        store.update(
            карточка.id,
            (карточка.doc.deepCopy() as ObjectNode).put("accept_mode", режим),
            Provenance(Channel.MANUAL, author, source = material),
        )
        val слово = РЕЖИМЫ_СЛОВАМИ[режим] ?: режим
        return DocumentAccept(
            material, режим, созданные, затронуто, заметки,
            note = when (режим) {
                "context_only" -> "документ оставлен в контексте: фактов учтено $затронуто, сущностей не заведено"
                "rejected" -> "документ отклонён: фактов отклонено $затронуто, в промпты они не идут"
                else -> "приём «$слово»: действий исполнено $затронуто, записей заведено ${созданные.size}" +
                    (if (заметки.isEmpty()) "" else "; замечаний ${заметки.size}")
            },
        )
    }

    // --- библиотека проекта: взять документ в другой проект ---------------------

    fun takeMaterial(fromProject: String, material: String, intoProject: String, author: String): TakeReport {
        require(fromProject != intoProject) { "документ берётся из ДРУГОГО проекта: в своём он уже лежит" }
        val откуда = Area.Project(fromProject)
        val куда = Area.Project(intoProject)
        require(store.list(куда, "project").isNotEmpty()) { "проекта «$intoProject» нет: брать некуда" }
        val исходник = материал(откуда, material)
        require(исходник.doc.path("role").asText("") != "charter") {
            "устав миссии в другой проект не берётся: у каждого проекта свой устав — загрузите записку этого проекта"
        }
        val уже = store.list(куда, "material").firstOrNull { it.provenance.source == "$fromProject/$material" && it.status != "cancelled" }
        if (уже != null) {
            return TakeReport(уже.code, "$fromProject/$material", живыеФакты(куда, уже.code).size, 0, 0, 0,
                "документ уже взят: ${уже.code} — повтор ничего не переписывает")
        }
        val код = следующий(куда, "material", "SD")
        val документ = исходник.doc.deepCopy() as ObjectNode
        listOf("supersedes", "accept_mode", "usefulness", "parse_run", "notes", "contribution").forEach { документ.remove(it) }
        val провенанс = Provenance(Channel.SERVICE, author, source = "$fromProject/$material")
        val новый = store.create(код, "material", куда, "2", документ, провенанс, status = исходник.status)
        val прогон = store.create(
            следующий(куда, "parse_run", "RN"), "parse_run", куда, null,
            mapper.createObjectNode().put("material", код)
                .put("prompt_version", "взято из библиотеки проекта $fromProject ($material): вызова модели не было")
                .put("at", java.time.OffsetDateTime.now().toString()),
            провенанс, status = "running",
        )
        // Темы — по метке: та же метка в новом проекте — та же тема, а не вторая.
        val темыКуда = mutableMapOf<String, String>()
        var темЗаведено = 0
        fun тема(старая: String): String? {
            val запись = store.byCode(откуда, старая) ?: return null
            val метка = запись.doc.path("label").asText("").ifBlank { return null }
            темыКуда[метка]?.let { return it }
            val все = темы.all(куда)
            val есть = все.firstOrNull { it.doc.path("label").asText() == метка }
            val кодТемы = есть?.let { темы.head(все, it).code } ?: store.create(
                следующий(куда, "topic", "TP"), "topic", куда, null,
                mapper.createObjectNode().put("label", метка), Provenance(Channel.SERVICE, author, source = "$fromProject/$material"),
            ).code.also { темЗаведено += 1 }
            темыКуда[метка] = кодТемы
            return кодТемы
        }
        val старые = живыеФакты(откуда, material).filter { !it.doc.path("manual").asBoolean(false) }
        val новыеПоСтарым = linkedMapOf<String, Entity>()
        val кодыФактов = mapper.createArrayNode()
        старые.forEach { ф ->
            val д = ф.doc.deepCopy() as ObjectNode
            // Решения прежнего проекта не переезжают: диспозиция, конфликты,
            // свидетельство сверки, пометы обновления — всё это заново.
            listOf("disposition_decision", "conflicts", "source_updated", "superseded", "source_note", "evidence", "confirmed_by", "assumption").forEach { д.remove(it) }
            д.put("disposition", "free")
            д.put("material", код)
            д.put("parse_run", прогон.code)
            if (д.path("source").isObject) (д.path("source") as ObjectNode).put("material", код)
            д.path("topic").asText("").ifBlank { null }?.let { старая -> тема(старая)?.let { д.put("topic", it) } ?: д.remove("topic") }
            val новыйФакт = store.create(
                следующий(куда, "fact", "F"), "fact", куда, ф.bornIn, д,
                Provenance(Channel.SERVICE, author, source = код, anchor = д.path("anchor").asText("").ifBlank { null }),
            )
            новыеПоСтарым[ф.id] = новыйФакт
            кодыФактов.add(новыйФакт.code)
        }
        // Связи между фактами документа переезжают с ним; связи к фактам
        // других документов — нет: там другой проект и другие факты.
        var связей = 0
        val реестр = links
        if (реестр != null) {
            старые.forEach { ф ->
                реестр.from(ф.id).filter { it.type in FactLinks.междуФактами && it.to in новыеПоСтарым }.forEach { св ->
                    реестр.link(св.type, новыеПоСтарым.getValue(ф.id).id, новыеПоСтарым.getValue(св.to).id, провенанс, rationale = св.rationale, subtype = св.subtype)
                    связей += 1
                }
            }
        }
        store.update(прогон.id, (прогон.doc.deepCopy() as ObjectNode).also { it.set<JsonNode>("facts", кодыФактов) }, провенанс, status = "done")
        store.update(новый.id, (новый.doc.deepCopy() as ObjectNode).put("parse_run", прогон.code), провенанс)
        режимы.markConflicts(куда, author)
        return TakeReport(
            material = код, from = "$fromProject/$material", facts = старые.size, links = связей, topics = темЗаведено, modelCalls = 0,
            note = "взят документ «${исходник.doc.path("name").asText(material)}» из проекта $fromProject: " +
                "фактов ${старые.size}, связей $связей, тем $темЗаведено; вызовов модели 0 — разбор переехал готовым",
        )
    }

    // --- карта пробелов устава ----------------------------------------------------

    fun charterGaps(project: String, material: String): GapMap {
        val область = Area.Project(project)
        val карточка = материал(область, material)
        val блоки = Canon.of(карточка.doc.path("text").asText("")).blocks
        val пункты = ПУНКТЫ_УСТАВА.map { п ->
            val найденные = блоки.filter { б -> п.слова.any { it.containsMatchIn(б.text.lowercase()) } }
            GapItem(п.n, п.what, найденные.isNotEmpty(), найденные.firstOrNull()?.anchor, найденные.size, п.scene, п.measure)
        }
        val нет = пункты.filter { !it.present }.map { it.n }
        val заперто = ВОРОТА_СЦЕН.filter { (_, нужны) -> нужны.any { it in нет } }.keys.toList()
        return GapMap(
            material = material,
            items = пункты,
            present = пункты.count { it.present },
            missing = нет,
            blockedScenes = заперто,
            note = if (нет.isEmpty()) "все двенадцать пунктов записки найдены по словам канона; полноту по мере считает человек"
            else "не найдено пунктов: ${нет.joinToString(", ")}" +
                (if (заперто.isEmpty()) "" else "; без них не закрываются сцены ${заперто.joinToString(", ")}") +
                " — допишите в записку и загрузите новой версией либо внесите руками с меткой [И]",
        )
    }

    // --- общее ------------------------------------------------------------------

    private fun материал(область: Area, код: String): Entity =
        store.byCode(область, код)?.takeIf { it.kind == "material" }
            ?: throw IllegalArgumentException("материала «$код» нет в проекте")

    private fun живыеФакты(область: Area, material: String): List<Entity> =
        store.list(область, "fact").filter { it.status != "cancelled" && it.doc.path("material").asText("") == material }

    private fun прогоны(область: Area, material: String): List<Entity> =
        store.list(область, "parse_run").filter { it.doc.path("material").asText("") == material }.sortedBy { it.code }

    /** Сущности, стоящие на фактах, — каждая со ВСЕМИ своими живыми основаниями. */
    private fun сущностиНаФактах(факты: List<Entity>): List<Pair<Entity, List<Entity>>> {
        val реестр = links ?: return emptyList()
        val сущности = linkedMapOf<String, Entity>()
        факты.forEach { ф ->
            реестр.to(ф.id, "derived_from_fact").forEach { св ->
                store.byId(св.from)?.takeIf { it.status != "cancelled" }?.let { сущности[it.id] = it }
            }
        }
        return сущности.values.map { с ->
            с to реестр.from(с.id, "derived_from_fact").mapNotNull { store.byId(it.to) }.filter { it.kind == "fact" && it.status != "cancelled" }
        }
    }

    private fun материалФакта(область: Area, код: String): String? =
        store.byCode(область, код)?.doc?.path("material")?.asText("")?.ifBlank { null }

    private fun раздел(область: Area, кодФакта: String): String =
        store.byCode(область, кодФакта)?.doc?.path("anchor")?.asText("")?.substringBefore('#').orEmpty()

    private fun значение(документ: JsonNode): String =
        документ.path("value").let { if (it.isObject) it.path("value").asText("") else it.asText("") }

    /** Смысл факта без места: субъект · утверждение · значение — по нему документы сравниваются между собой. */
    private fun ключСмысла(документ: JsonNode): String =
        документ.path("subject").asText("").trim().lowercase() + "|" +
            документ.path("predicate").asText("").trim().lowercase() + "|" + значение(документ).trim().lowercase()

    private fun помета(документ: JsonNode, текст: String): String =
        документ.path("notes").asText("").let { if (it.isBlank()) текст else "$it; $текст" }

    private fun имя(с: Entity): String =
        listOf("name", "title", "statement", "designation", "label", "text")
            .map { с.doc.path(it).asText("") }.firstOrNull { it.isNotBlank() } ?: с.code

    private class Пункт(val n: Int, val what: String, val scene: String, val measure: String, вхождения: List<String>) {
        val слова: List<Regex> = вхождения.map { Regex(it) }
    }

    private companion object {
        val РЕЖИМЫ_ПРИЁМА: List<String> = listOf("whole", "sections", "selective", "context_only", "rejected")
        val РЕЖИМЫ_СЛОВАМИ: Map<String, String> = mapOf(
            "whole" to "весь документ", "sections" to "по разделам", "selective" to "выборочно",
            "context_only" to "только в контекст", "rejected" to "отклонён",
        )

        /** Ворота сцен по ТРЕБОВАНИЯ-К-ЗАПИСКЕ-МИССИИ: сцена → пункты, без которых она не закрывается. */
        val ВОРОТА_СЦЕН: Map<String, List<Int>> = mapOf("2" to listOf(1, 2, 3), "4" to listOf(6), "6" to listOf(5), "5" to listOf(8))

        /**
         * Двенадцать пунктов записки — словами, по которым они узнаются в
         * каноне. Это узнавание по разметке и словам, а не суждение: «есть»
         * значит «в документе об этом написано», меру полноты считает человек.
         */
        val ПУНКТЫ_УСТАВА: List<Пункт> = listOf(
            Пункт(1, "Что мы строим — класс системы одной фразой", "2", "одна фраза, названо существительным-классом",
                listOf("что мы строим", "что строим", "класс системы", "назначение системы", "система связи", "мы создаём", "мы строим")),
            Пункт(2, "Чего мы не строим — границы", "2", "≥ 3 исключения",
                listOf("не строим", "чего мы не", "чего не", "границы", "не является", "вне рамок", "не входит", "не предмет")),
            Пункт(3, "Для кого — стороны с ролями", "2", "≥ 5 сторон с ролью",
                listOf("для кого", "сторон", "стейкхолдер", "заказчик", "участник", "потребител", "оператор")),
            Пункт(4, "Чего им не хватает — нужды", "3", "≥ 1 нужда у каждой стороны или общий список с привязкой",
                listOf("не хватает", "нужд", "потребност", "дефицит", "нехватк", "нет возможности")),
            Пункт(5, "Что система даёт — сервисы с классами обслуживания", "6", "≥ 3 сервиса, у каждого потребитель",
                listOf("сервис", "услуг", "что система даёт", "класс обслуживания", "qos")),
            Пункт(6, "Куда идём — цели с показателем, единицей и годом", "4", "≥ 3 цели, у каждой число, единица, год",
                listOf("цел[иьея].*20[2-5][0-9]", "к 20[2-5][0-9]", "показател", "горизонт")),
            Пункт(7, "Где — география и приоритеты", "5", "≥ 1 приоритетная зона",
                listOf("географ", "территор", "регион", "зон[аы] ", "покрыти", "арктик", "приоритет")),
            Пункт(8, "Рамки — запреты и границы", "5", "≥ 5 рамок, из них ≥ 2 с числом",
                listOf("рамк", "ограничен", "запрет", "не более", "не менее", "не превыша", "не допуска")),
            Пункт(9, "Этапы — что и когда, с числами", "11", "≥ 2 этапа с горизонтом",
                listOf("этап", "очеред", "фаза", "срок", "ввод в эксплуатац", "к 20[2-5][0-9] году")),
            Пункт(10, "Порядок величин — бюджет или диапазон, источник", "11", "≥ 1 диапазон с единицей",
                listOf("бюджет", "стоимост", "млрд", "млн руб", "порядок величин", "финансир")),
            Пункт(11, "Допущения и открытые вопросы", "12", "≥ 3 допущения с меткой [П]",
                listOf("допущени", "открыт\\w* вопрос", "\\[п\\]", "предполага", "на веру")),
            Пункт(12, "Метки источников [И] · [В] · [П]", "12", "метки у чисел и внешних утверждений",
                listOf("\\[и\\]", "\\[в\\]", "\\[п\\]")),
        )
    }
}
