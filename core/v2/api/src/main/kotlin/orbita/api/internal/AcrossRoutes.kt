// Сквозные экраны v2: полки, материалы и загрузка, поле знаний, матрица
// покрытия, «мои задания», перечень сущностей и фиксация точки.
//
// Вынесены из V2Router отдельным файлом по правилу ТЗ-BACKEND §3 (файл
// тонкий, домена внутри нет): экраны сквозные, к одной сцене не привязаны.
package orbita.api.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.formulation.api.Formulation
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry
import orbita.kernel.api.Provenance
import orbita.knowledge.api.Intake
import orbita.library.api.Shelves
import orbita.process.api.ProcessEngine

class AcrossRoutes(
    private val store: EntityStore,
    private val links: LinkRegistry,
    private val engine: ProcessEngine,
    private val shelves: Shelves,
    private val intake: Intake,
    private val formulation: Formulation,
    private val mapper: ObjectMapper = ObjectMapper(),
    /**
     * Текст из двоичного файла (docx · pdf · xlsx · pptx) — извлекатель
     * подставляет граница; null — материал принимается только текстом и
     * ссылкой. Формат живёт вне ядра v2 (ADR-064: форматы — на границе).
     */
    private val extract: ((fileName: String, bytes: ByteArray) -> String?)? = null,
) {

    fun handle(method: String, path: String, query: Map<String, String>, body: String?): V2Router.Ответ? = when {
        // Полки: загрузка поставки и просмотр.
        method == "POST" && path == "/v2/shelves" -> положитьНаПолку(разобрать(body))

        method == "GET" && path == "/v2/shelves" -> полка(требуется(query, "kind"))

        // Библиотека (экран 10 шипа 2): каталог полок строками и окно взятия.
        method == "GET" && path == "/v2/library/catalog" -> каталог(требуется(query, "project"))

        method == "GET" && path == "/v2/library/take/preview" ->
            окноВзятия(требуется(query, "project"), требуется(query, "shelf"), null)

        method == "POST" && path == "/v2/library/take" -> взять(требуется(query, "project"), разобрать(body))

        // Глоссарий: интерфейс говорит терминами NASA, соответствия — здесь.
        // Поиск идёт по ЛЮБОМУ из имён: инженер помнит своё.
        method == "GET" && path == "/v2/glossary" -> глоссарий(query["q"])

        // Волна 2: материалы, задание загрузки и план действий.
        method == "GET" && path == "/v2/materials" -> материалы(требуется(query, "project"))
        method == "POST" && path == "/v2/materials" -> материал(требуется(query, "project"), разобрать(body))

        method == "POST" && path == "/v2/intake" -> заданиеЗагрузки(требуется(query, "project"), разобрать(body))

        method == "GET" && path == "/v2/facts" -> фактология(требуется(query, "project"))

        // Матрица покрытия: не хранится, а считается по связям.
        method == "GET" && path == "/v2/coverage" -> покрытие(требуется(query, "project"))

        method == "GET" && path == "/v2/my-tasks" -> задания(требуется(query, "project"), query["role"])

        method == "GET" && path == "/v2/entities" ->
            перечень(требуется(query, "project"), требуется(query, "kind"))

        else -> null
    }

    private fun положитьНаПолку(тело: JsonNode): V2Router.Ответ {
        val вид = тело.path("kind").asText("")
        val код = тело.path("code").asText("")
        require(вид.isNotBlank() && код.isNotBlank()) { "полке нужны вид и код записи" }
        // Состояние выкладки и КОД, в который она легла, — часть ответа:
        // поставщик обязан видеть, что повтор ничего не переписал и что акт
        // ушёл в уже лежащую карточку, а не верить своему счётчику.
        val итог = shelves.put(вид, код, тело.path("doc"), автор(тело), status = тело.path("status").asText("").ifBlank { null })
        return V2Router.Ответ(201, mapper.createObjectNode()
            .put("code", итог.item.code).put("kind", итог.item.kind)
            .put("state", итог.state.name.lowercase()))
    }

    private fun глоссарий(запрос: String?): V2Router.Ответ {
        val полка = shelves.of("glossary_cross_terms").firstOrNull()
        val ответ = mapper.createObjectNode()
        val массив = ответ.putArray("items")
        val игла = запрос?.trim()?.lowercase().orEmpty()
        полка?.doc?.path("items")?.forEach { термин ->
            val строки = listOf("term_nasa", "ru_equivalent", "en_full", "romanov")
                .map { термин.path(it).asText("") }
            if (игла.isEmpty() || строки.any { it.lowercase().contains(игла) }) {
                массив.add(термин)
            }
        }
        ответ.put(
            "note",
            if (полка == null) "глоссарий не загружен: python3 tools/v2/load_shelves.py"
            else "интерфейс — в терминах NASA; здесь соответствия РК-11КТ и схемы Романова",
        )
        return V2Router.Ответ(200, ответ)
    }

    private fun полка(вид: String): V2Router.Ответ {
        val массив = mapper.createArrayNode()
        shelves.of(вид).forEach { запись ->
            массив.addObject().put("code", запись.code).put("kind", запись.kind)
                .set<JsonNode>("doc", запись.doc)
        }
        val ответ = mapper.createObjectNode()
        ответ.set<JsonNode>("items", массив)
        return V2Router.Ответ(200, ответ)
    }

    // ── Библиотека: каталог полок и окно взятия (экран 10 шипа 2) ──────────
    //
    // Полка — не витрина: с неё БЕРУТ в проект. До шипа 2 экран умел одно —
    // перечислить виды и число записей, и владелец сказал прямо: «с любой
    // полки можно взять». Здесь каталог строками и окно взятия; само взятие
    // делегируется тому, у кого механизм уже есть (каркас состава — сцена 7,
    // каркас работ — программатика), а плоская полка кладётся КОПИЕЙ записи
    // тем же видом с истоком «код полки».
    //
    // Чего у полки нет механизма — то не придумывается: строка каталога несёт
    // причину словами, и запертая кнопка объясняет себя (Ф-11). Поля взятого
    // берутся из записи полки как есть: соответствия полей, которых истина
    // схем не назвала, здесь не изобретаются — это вопрос владельцу.

    /**
     * Полка каталога.
     *
     * @property вид вид записи полки в области library
     * @property имя как полка зовётся человеку, пока её содержимое не назвало
     *   себя само (`title` поставки)
     * @property контейнер поле-контейнер записи-полки: его размер и есть
     *   число записей. null — полка ПЛОСКАЯ: записей столько, сколько
     *   карточек этого вида лежит в библиотеке
     * @property чем чем полка наполнена — словом; у полки-контейнера слово
     *   берётся из подписи поля истины схем, здесь — только для плоских
     * @property цель вид записи ПРОЕКТА, которым живёт взятое; null — взятия
     *   с этой полки нет, и `почему` называет причину
     * @property механизм готовый механизм взятия: его зовёт экран, а не этот
     *   маршрут — второй реализации взятия не бывает
     * @property почему почему взять нельзя — словами; с `цель` не живёт
     */
    private data class Полка(
        val вид: String,
        val имя: String,
        val контейнер: String? = null,
        val чем: String? = null,
        val цель: String? = null,
        val механизм: String? = null,
        val почему: String? = null,
    )

    /**
     * Полки библиотеки в порядке каталога.
     *
     * Список назван руками намеренно: «полка» — решение продукта, а не
     * следствие вида. По содержимому её не угадать — у норматива есть
     * перечень пунктов, но полка нормативов — это ОДИННАДЦАТЬ актов, а не
     * один акт с одиннадцатью пунктами. Имя поля-контейнера сверяется с
     * истиной схем на чтении (`содержимое`), поэтому разойтись с ней молча
     * список не может.
     */
    private val ПОЛКИ: List<Полка> = listOf(
        Полка(
            "pbs_template", "Каркас состава", контейнер = "nodes",
            цель = "component", механизм = "frame",
        ),
        Полка(
            "wbs_template", "Каркас работ", контейнер = "packages",
            цель = "wbs_package", механизм = "wbs",
        ),
        Полка("constraint", "Рамки Р класса миссии", чем = "рамки", цель = "constraint"),
        Полка("normative_document", "Нормативы", чем = "нормативы", цель = "normative_document"),
        Полка("process_catalog", "Процессы системной инженерии", чем = "процессы", цель = "process_catalog"),
        Полка(
            "interface_template", "Типовые стыки", контейнер = "interfaces",
            почему = "стык в проекте держит стороны ссылками на узлы состава, а полка называет их " +
                "кодами и направление словом — соответствия полей истина схем не назвала; " +
                "пока не названа, стык заводится на экране архитектуры",
        ),
        Полка(
            "architecture_template", "Архитектура Arcadia", контейнер = "functions",
            почему = "содержимое полки лежит слоями, а истина схем ждёт перечни функций, обменов и " +
                "цепочек одним списком — пока поставка не выровнена, брать с полки нечего",
        ),
        Полка(
            "model_template", "Модели системы", контейнер = "models",
            почему = "поля моделей на полке названы иначе, чем у записи модели в проекте, и " +
                "соответствия истина схем не назвала; модель заводится на экране моделей",
        ),
        Полка(
            "component_template_shelf", "Шаблоны компонентов", контейнер = "templates",
            почему = "вида «шаблон компонента» истина схем не называет — лестница ступеней и анкеты " +
                "читаются карточкой узла на экране состава",
        ),
        Полка(
            "document_template", "Шаблоны документов", контейнер = "sections",
            почему = "шаблон документа выбирает сам документ — копии в проекте он не держит",
        ),
        Полка(
            "phase_template", "Шаблоны фаз", контейнер = "scenes",
            почему = "шаблон фазы ставится при заведении проекта и меняется решением точки",
        ),
        Полка(
            "mission_class", "Классы миссии", чем = "классы",
            почему = "класс миссии выбирается в паспорте проекта — его рамки и полки приходят при заведении",
        ),
        Полка(
            "typical_requirement", "Типовые требования", чем = "типовые требования",
            почему = "типовое требование ложится на носителя — возьмите его на экране требований, " +
                "там носитель и выбирается",
        ),
        Полка(
            "glossary_cross_terms", "Глоссарий", контейнер = "items",
            почему = "глоссарий — общий справочник: его читают все проекты, в проект он не копируется",
        ),
        Полка(
            "lifecycle_process_reference", "Методология жизненного цикла", контейнер = "stages",
            почему = "вида этой полки истина схем не называет — стадии и мероприятия читаются картой фазы",
        ),
    )

    /** Каталог полок строками: что лежит, сколько, какой версии и сколько уже взято. */
    private fun каталог(проект: String): V2Router.Ответ {
        val ответ = mapper.createObjectNode()
        val строки = ответ.putArray("items")
        val пустые = ответ.putArray("empty")
        ПОЛКИ.forEach { п ->
            val записи = записиПолки(п.вид)
            if (записи.isEmpty()) { пустые.add(п.имя); return@forEach }
            if (п.контейнер == null) строки.add(строкаПлоской(проект, п, записи))
            else записи.forEach { строки.add(строкаКонтейнера(проект, п, it)) }
        }
        ответ.put(
            "empty_note",
            if (пустые.isEmpty()) "" else "пустые полки наполняются поставкой внешнего контура, не руками",
        )
        return V2Router.Ответ(200, ответ)
    }

    /** Записи полки: снятое с учёта на полке не лежит. */
    private fun записиПолки(вид: String): List<orbita.kernel.api.Entity> =
        store.list(Area.Library, вид).filter { it.status != СНЯТО }.sortedBy { it.code }

    /**
     * Содержимое полки-контейнера: перечень записей в названном поле.
     *
     * Поле сверяется с истиной схем: вид, которому истина не назвала такого
     * поля, содержимого не отдаёт — иначе каталог считал бы что попало.
     * Виды вне истины (полки поставки 03.09, допуск сторожа записи) читаются
     * как есть: их полей истине назвать нечем.
     */
    private fun содержимое(вид: String, поле: String, документ: JsonNode): List<JsonNode> {
        val спец = orbita.kernel.schema.GeneratedKinds.byCode[вид]
        if (спец != null && поле !in спец.fields) return emptyList()
        return документ.path(поле).filter { it.isObject }
    }

    /** Чем полка наполнена — словом: подпись поля из истины схем, иначе слово списка. */
    private fun чемНаполнена(п: Полка): String {
        val подпись = п.контейнер?.let {
            orbita.kernel.schema.GeneratedKinds.byCode[п.вид]?.labels?.get(it)
        }
        return подпись?.lowercase() ?: п.чем.orEmpty()
    }

    private fun имяЗаписи(документ: JsonNode, код: String): String =
        listOf("name", "title", "statement", "designation")
            .firstNotNullOfOrNull { документ.path(it).asText("").trim().ifBlank { null } }
            ?: код

    private fun строкаКонтейнера(проект: String, п: Полка, полка: orbita.kernel.api.Entity): ObjectNode {
        val записи = содержимое(п.вид, п.контейнер!!, полка.doc)
        val узел = основаниеСтроки(п)
            .put("code", полка.code)
            .put("title", имяЗаписи(полка.doc, п.имя))
            .put("count", записи.size)
            .put("version", полка.doc.path("version").asInt(полка.version))
        return узел.put("taken", взятых(проект, п, полка.code, записи.map { it.path("code").asText() }))
    }

    private fun строкаПлоской(проект: String, п: Полка, записи: List<orbita.kernel.api.Entity>): ObjectNode {
        val узел = основаниеСтроки(п)
            // Код плоской полки — её вид: карточек много, а полка одна.
            .put("code", п.вид)
            .put("title", п.имя)
            .put("count", записи.size)
            // Версия у плоской полки не одна на всех — и выдумывать её нечем.
            .put("version", 0)
        return узел.put("taken", взятых(проект, п, п.вид, записи.map { it.code }))
    }

    private fun основаниеСтроки(п: Полка): ObjectNode = mapper.createObjectNode()
        .put("kind", п.вид)
        .put("what", чемНаполнена(п))
        .put("takeable", п.цель != null)
        .put("mechanism", п.механизм)
        .put("why", п.почему)

    /**
     * Сколько записей полки уже в проекте: по коду ИЛИ по истоку.
     *
     * Двумя дорогами намеренно: каркас состава кладёт узлы своими кодами
     * (совпадение кодов), а рамки класса миссии приходят при заведении
     * проекта с истоком-полкой. Считать одной дорогой значило бы предлагать
     * «взять» то, что уже взято.
     */
    private fun взятых(проект: String, п: Полка, полка: String, коды: List<String>): Int {
        val цель = п.цель ?: return 0
        val набор = коды.toSet()
        return store.list(Area.Project(проект), цель).count {
            it.status != СНЯТО && (it.code in набор || it.provenance.source == полка)
        }
    }

    /**
     * Окно взятия: записи полки с пометами «рекомендовано», «есть в проекте»
     * и «нужен родитель», и предпросмотр — что возьмётся и что станет доступно.
     *
     * @param выбор коды, отмеченные человеком; null — рекомендованный набор
     */
    private fun окноВзятия(проект: String, полка: String, выбор: List<String>?): V2Router.Ответ {
        val (п, запись) = найтиПолку(полка)
        val записи: List<JsonNode> = if (п.контейнер == null) {
            // Плоская полка: карточка сама себе запись; код лежит рядом с
            // документом, поэтому он подставляется в копию для окна.
            записиПолки(п.вид).mapNotNull { з -> (з.doc as? ObjectNode)?.deepCopy()?.put("code", з.code) }
        } else {
            содержимое(п.вид, п.контейнер, запись!!.doc)
        }
        val глубина = глубинаПолки(запись)
        val область = Area.Project(проект)
        val вПроекте = п.цель?.let { цель ->
            store.list(область, цель).filter { it.status != СНЯТО }
        }.orEmpty()
        val естьКоды = вПроекте.map { it.code }.toSet()
        val изПолки = вПроекте.count { it.provenance.source == полка }
        val рекомендованные = записи.filter { рекомендовано(it, глубина) }.map { it.path("code").asText() }
        val отмечено = (выбор ?: рекомендованные).toSet()

        val ответ = mapper.createObjectNode()
            .put("shelf", полка)
            .put("kind", п.вид)
            .put("title", запись?.let { имяЗаписи(it.doc, п.имя) } ?: п.имя)
            .put("what", чемНаполнена(п))
            .put("takeable", п.цель != null)
            .put("mechanism", п.механизм)
            .put("why", п.почему)
        val строки = ответ.putArray("items")
        var возьмём = 0
        var уже = 0
        var заперто = 0
        записи.forEach { з ->
            val код = з.path("code").asText("").trim()
            if (код.isEmpty()) return@forEach
            val есть = код in естьКоды
            val родитель = з.path("parent").asText("").trim()
            // Родитель нужен, когда его нет ни в проекте, ни в том же наборе:
            // сирота в составе — не взятие, а брак.
            val нужен = родитель.isNotEmpty() && родитель !in естьКоды && родитель !in отмечено
            val строка = строки.addObject()
                .put("code", код)
                .put("title", имяЗаписи(з, код))
                .put("parent", родитель.ifBlank { null })
                .put("level", з.path("level").asInt(0))
                .put("recommended", код in отмечено)
                .put("taken", есть)
            val нужно = строка.putArray("needs")
            if (нужен && !есть) {
                val родительЗапись = записи.firstOrNull { it.path("code").asText() == родитель }
                нужно.addObject().put("code", родитель)
                    .put("title", родительЗапись?.let { имяЗаписи(it, родитель) } ?: родитель)
            }
            // Счёт — про ОТМЕЧЕННОЕ: неотмеченная запись без родителя никого не
            // держит, она просто не берётся. Иначе «заперто» считало бы всю полку.
            when {
                есть -> уже += 1
                код in отмечено && нужен -> заперто += 1
                код in отмечено -> возьмём += 1
            }
        }
        ответ.put("take", возьмём).put("already", уже).put("blocked", заперто)
        ответ.set<JsonNode>("unlocks", станутДоступны(проект, п, отмечено + естьКоды))
        ответ.put(
            "note",
            when {
                п.почему != null -> п.почему
                возьмём == 0 && уже > 0 -> "всё отмеченное уже в проекте: повтор ничего не удваивает"
                возьмём == 0 -> "не отмечено ни одной записи — отметьте, что взять"
                изПолки > 0 -> "с этой полки в проект уже взято $изПолки"
                else -> "взятое помнит исток: видно, что пришло с полки, а что заведено руками"
            },
        )
        return V2Router.Ответ(200, ответ)
    }

    /** Полка по коду строки каталога: контейнер — записью, плоская — видом. */
    private fun найтиПолку(полка: String): Pair<Полка, orbita.kernel.api.Entity?> {
        ПОЛКИ.firstOrNull { it.контейнер == null && it.вид == полка }?.let { return it to null }
        ПОЛКИ.forEach { п ->
            if (п.контейнер == null) return@forEach
            записиПолки(п.вид).firstOrNull { it.code == полка }?.let { return п to it }
        }
        throw NoSuchElementException("такой полки в библиотеке нет — каталог называет те, что есть")
    }

    /** «уровни 0–3 всегда» — правило САМОЙ полки; своего у кода нет. */
    private fun глубинаПолки(полка: orbita.kernel.api.Entity?): Int? =
        полка?.doc?.path("rules")?.map { it.asText() }
            ?.firstNotNullOfOrNull { ГЛУБИНА_ПОЛКИ.find(it)?.groupValues?.get(2)?.toIntOrNull() }

    /**
     * Рекомендовано ли брать запись по умолчанию.
     *
     * Решает полка, а не экран: сперва её собственная помета записи
     * (`default_take`), затем её правило глубины («уровни 0–3 всегда»).
     * Полка, не сказавшая ни того, ни другого, рекомендует себя целиком.
     */
    private fun рекомендовано(запись: JsonNode, глубина: Int?): Boolean {
        val помета = запись.path("default_take")
        if (помета.isBoolean) return помета.asBoolean()
        val уровень = запись.path("level")
        if (глубина != null && уровень.isNumber) return уровень.asInt() <= глубина
        return true
    }

    /**
     * Что станет доступно после взятия — считает СЕРВЕР.
     *
     * Взятые узлы состава открывают типовые стыки (обе стороны теперь в
     * проекте) и типовые функции (носитель теперь есть): до взятия положить
     * их некуда. Счёт идёт по кодам записей полок, а не по догадке экрана.
     */
    private fun станутДоступны(проект: String, п: Полка, узлы: Set<String>): com.fasterxml.jackson.databind.node.ArrayNode {
        val массив = mapper.createArrayNode()
        if (п.цель != "component" || узлы.isEmpty()) return массив
        val область = Area.Project(проект)
        val стыкиПроекта = store.list(область, "interface").filter { it.status != СНЯТО }.map { it.code }.toSet()
        val стыки = записиПолки("interface_template").sumOf { полка ->
            содержимое("interface_template", "interfaces", полка.doc).count { с ->
                с.path("code").asText() !in стыкиПроекта &&
                    с.path("a").asText("") in узлы && с.path("b").asText("") in узлы
            }
        }
        if (стыки > 0) массив.addObject().put("kind", "interface").put("count", стыки)
        val функцииПроекта = store.list(область, "function").filter { it.status != СНЯТО }.map { it.code }.toSet()
        val функции = записиПолки("architecture_template").sumOf { полка ->
            функцииПолки(полка.doc).count { ф ->
                ф.path("code").asText() !in функцииПроекта && носители(ф).any { it in узлы }
            }
        }
        if (функции > 0) массив.addObject().put("kind", "function").put("count", функции)
        return массив
    }

    /**
     * Функции архитектурной полки: поле истины схем, а у поставки — слоями.
     *
     * Поставка 03.09 разложила функции по слоям (объекты верхнего уровня), и
     * истина схем этого не назвала — допуск сторожа записи держит слои
     * поимённо и зовёт это вопросом владельцу. Читать их можно: отказ сторожа
     * про ЗАПИСЬ, не про чтение. Слои не перечисляются по именам — берётся
     * любой объект полки, у которого есть перечень функций.
     */
    private fun функцииПолки(документ: JsonNode): List<JsonNode> {
        val свои = содержимое("architecture_template", "functions", документ)
        if (свои.isNotEmpty()) return свои
        return документ.properties().filter { (_, значение) -> значение.isObject }
            .flatMap { (_, слой) -> слой.path("functions").filter { it.isObject } }
    }

    /** Носители функции: полка называет их кодом либо перечнем кодов. */
    private fun носители(функция: JsonNode): List<String> {
        val поле = функция.path("allocated_to")
        return if (поле.isArray) поле.map { it.asText() } else listOfNotNull(поле.asText("").ifBlank { null })
    }

    /**
     * Взять с полки: предпросмотр отмеченного (`dry_run`) либо само взятие.
     *
     * Полка с готовым механизмом взятия отвечает отказом СЛОВАМИ — её берёт
     * экран тем механизмом, который уже есть; второй реализации взятия в
     * продукте не бывает (правило 2: одно правило — одно место).
     */
    private fun взять(проект: String, тело: JsonNode): V2Router.Ответ {
        val полка = тело.path("shelf").asText("").trim()
        require(полка.isNotBlank()) { "не названо, с какой полки брать" }
        val отмечено = тело.path("items").map { it.asText() }.filter { it.isNotBlank() }
        if (тело.path("dry_run").asBoolean(false)) return окноВзятия(проект, полка, отмечено)
        val (п, _) = найтиПолку(полка)
        п.почему?.let { throw IllegalArgumentException(it) }
        п.механизм?.let { throw IllegalArgumentException(отказМеханизма(it)) }
        val цель = п.цель ?: throw IllegalArgumentException("с этой полки в проект ничего не кладётся")
        require(п.контейнер == null) {
            "содержимое полки кладётся в проект своим механизмом, а не копией записи"
        }
        require(отмечено.isNotEmpty()) { "не отмечено ни одной записи — отметьте, что взять" }
        val область = Area.Project(проект)
        val автор = автор(тело)
        val было = store.list(область, цель).filter { it.status != СНЯТО }.map { it.code }.toSet()
        val рождение = orbita.kernel.schema.GeneratedKinds.byCode[цель]?.bornIn?.substringBefore(',')?.trim()
        var создано = 0
        var уже = 0
        val нет = mutableListOf<String>()
        отмечено.forEach { код ->
            if (код in было) { уже += 1; return@forEach }
            val карточка = store.byCode(Area.Library, код)?.takeIf { it.kind == п.вид && it.status != СНЯТО }
            if (карточка == null) { нет += код; return@forEach }
            // Документ переносится КАК ЕСТЬ: поля записи полки — те же поля
            // того же вида, и сторож записи проверяет их по истине схем.
            store.create(
                код, цель, область, рождение, карточка.doc.deepCopy<ObjectNode>(),
                Provenance(Channel.SHELF, автор, source = п.вид),
            )
            создано += 1
        }
        val ответ = mapper.createObjectNode()
            .put("shelf", п.вид)
            .put("created", создано)
            .put("already", уже)
        ответ.put(
            "note",
            "взято $создано" + (if (уже > 0) ", уже было $уже" else "") +
                (if (нет.isEmpty()) "" else "; на полке не нашлось: ${нет.joinToString(" · ")}") +
                "; взятое помнит исток — видно, что пришло с полки",
        )
        return V2Router.Ответ(201, ответ)
    }

    /** Кто берёт полку вместо библиотеки — словами, а не молчанием. */
    private fun отказМеханизма(механизм: String): String = when (механизм) {
        "frame" -> "каркас состава берётся своим механизмом: «Взять каркас» на экране концепции — " +
            "маршрут библиотеки эту работу не повторяет"
        "wbs" -> "каркас работ берётся своим механизмом: окно пакетов на экране работ — " +
            "маршрут библиотеки эту работу не повторяет"
        else -> "у этой полки свой механизм взятия — библиотека его не подменяет"
    }

    private val снимок = UrlSnapshot(mapper = mapper)

    /** Материалы проекта: тип, объём, прежняя версия — чтобы новую версию класть поверх старой. */
    private fun материалы(проект: String): V2Router.Ответ {
        val массив = mapper.createArrayNode()
        store.list(Area.Project(проект), "material").sortedBy { it.code }.forEach { м ->
            массив.addObject()
                .put("code", м.code)
                .put("name", м.doc.path("name").asText(м.code))
                .put("kind", м.doc.path("kind").asText("reference"))
                .put("chars", м.doc.path("chars").asInt(0))
                .put("supersedes", м.doc.path("supersedes").asText("").ifBlank { null })
                .put("created_at", м.createdAt.toString())
                // Ранг доверия — отдельное поле рядом с типом: тип остаётся
                // режимом разбора, доверие называет человек. Профиль
                // содержимого (Д2а) ставит разбор — по нему экран показывает
                // доли блоков, а не гадает по расширению файла.
                .put("rank", м.doc.path("rank").asText("").ifBlank { null })
                // Роль документа: ею решается, ЧТО из него может образоваться.
                // Экран показывает её у каждого документа и даёт прочитать
                // уже лежащий — без роли документ читается как обстановка.
                .put("role", м.doc.path("role").asText("").ifBlank { null })
                .also { у ->
                    м.doc.path("profile").takeIf { it.isObject }?.let { у.set<JsonNode>("profile", it) }
                }
        }
        val ответ = mapper.createObjectNode()
        ответ.set<JsonNode>("items", массив)
        return V2Router.Ответ(200, ответ)
    }

    private fun материал(проект: String, тело: JsonNode): V2Router.Ответ {
        val ссылка = тело.path("url").asText("").trim()
        // Ссылка — второй вход в поле знаний (замечание прохода 08.09):
        // текст берётся по ней здесь и кладётся снимком, как и вставленный.
        // Снимок, а не живая ссылка: страница завтра другая, а факт с
        // якорем обязан оставаться проверяемым. Страница без рендера —
        // отказ словами (шип E п. 4), не пустой материал.
        // Двоичный файл (ПМИ-5, замечание 12.09 «только md»): docx · pdf · xlsx ·
        // pptx приходят base64, текст извлекает сервер — тем же извлекателем,
        // что у документов v1; формат не читается — отказ словами, не пустой материал.
        val имяФайла = тело.path("filename").asText("").trim()
        val файлBase64 = тело.path("file_base64").asText("")
        val извлечённый = if (файлBase64.isNotBlank()) {
            val извлекатель = extract ?: throw IllegalArgumentException("файлы на этом стенде не читаются: вставьте текст")
            val байты = try { java.util.Base64.getDecoder().decode(файлBase64.substringAfter(",")) } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("файл не разобрался как base64")
            }
            require(имяФайла.isNotBlank()) { "у файла нет имени — по расширению узнаётся формат" }
            извлекатель(имяФайла, байты)?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException(
                    "файл «$имяФайла» не прочитался: читаются docx · pdf · xlsx · pptx · txt · md; " +
                        "скан без текстового слоя тоже не читается — приложите текст",
                )
        } else null
        val снятый = if (тело.path("text").asText("").isBlank() && извлечённый == null && ссылка.isNotBlank()) снимок.fetch(ссылка) else null
        val текст = тело.path("text").asText("").ifBlank { извлечённый ?: снятый?.text ?: "" }
        require(текст.isNotBlank()) { "у материала нет текста: вставьте текст, приложите файл либо дайте ссылку" }
        val шапка = снятый?.let { "Источник: $ссылка · снимок ${it.date} · ${it.renderer}\n\n" }
            ?: извлечённый?.let { "Источник: файл $имяФайла · текст извлечён сервером\n\n" } ?: ""
        val код = intake.putMaterial(
            проект,
            тело.path("name").asText("").ifBlank { имяФайла.substringBeforeLast('.').ifBlank { ссылка.ifBlank { "материал" } } },
            тело.path("kind").asText("").ifBlank { тело.path("type").asText("reference") },
            шапка + текст,
            автор(тело),
            supersedes = тело.path("supersedes").asText("").ifBlank { null },
            // Ранг доверия приходит С ФОРМЫ и передаётся как есть: пустое —
            // решение ядра, а не умолчание маршрута. На проекте поля знаний
            // v2 ядро отвечает отказом «ранг доверия материала обязателен»,
            // на проекте прохода выводит ранг по прежнему типу входного.
            rank = тело.path("rank").asText("").trim().ifBlank { null },
            // Роль документа (истина схем, `material.role`): ею решается, что
            // из него выписывается. Пусто — читаем как обстановку, и читатель
            // говорит об этом словами, а не молча.
            role = тело.path("role").asText("").trim().ifBlank { null },
        )
        val ответ = mapper.createObjectNode().put("code", код).put("from_url", ссылка.isNotBlank()).put("chars", текст.length)
        // Ранг возвращается тот, который ПОСТАВИЛО ядро: форма показывает не
        // то, что отправила, а то, с чем материал теперь живёт.
        store.byCode(Area.Project(проект), код)?.doc?.let { карточка ->
            ответ.put("rank", карточка.path("rank").asText(""))
            карточка.path("notes").asText("").takeIf { it.isNotBlank() }?.let { ответ.put("rank_note", it) }
        }
        извлечённый?.let { ответ.put("extracted_from", имяФайла) }
        снятый?.let { ответ.put("snapshot_renderer", it.renderer).put("snapshot_date", it.date) }
        тело.path("supersedes").asText("").takeIf { it.isNotBlank() }?.let { ответ.put("supersedes", it) }
        return V2Router.Ответ(201, ответ)
    }

    private fun заданиеЗагрузки(проект: String, тело: JsonNode): V2Router.Ответ {
        val задание = intake.plan(
            проект,
            тело.path("material").asText(""),
            тело.path("intent").asText("разбери по сущностям"),
            автор(тело),
        )
        val узел = mapper.createObjectNode()
        узел.put("task", задание.id)
        узел.put("material", задание.material)
        узел.put("intent", задание.intent)
        узел.put("note", задание.note)
        val факты = узел.putArray("facts")
        задание.facts.forEach { факты.add(KindJson.факт(mapper, it)) }
        val план = узел.putArray("plan")
        задание.plan.forEach { действие ->
            план.addObject()
                .put("kind", действие.kind)
                .put("title", действие.title)
                .put("effect", действие.effect)
        }
        return V2Router.Ответ(200, узел)
    }

    private fun фактология(проект: String): V2Router.Ответ {
        val массив = mapper.createArrayNode()
        // Тем же построителем, что и заведение: список и заведение писались
        // разным кодом, и `manual` был в одном, но не в другом (правило 2).
        intake.facts(проект).forEach { массив.add(KindJson.факт(mapper, it)) }
        val ответ = mapper.createObjectNode()
        ответ.set<JsonNode>("items", массив)
        return V2Router.Ответ(200, ответ)
    }

    private fun покрытие(проект: String): V2Router.Ответ {
        val матрица = formulation.coverage(проект)
        val ответ = mapper.createObjectNode()
        ответ.put("total", матрица.total)
        ответ.put("covered", матрица.covered)
        ответ.put("summary", матрица.summary)
        val строки = ответ.putArray("needs")
        матрица.needs.forEach { нужда ->
            val n = строки.addObject()
            n.put("code", нужда.code)
            n.put("statement", нужда.statement)
            n.put("owner", нужда.ownerName)
            n.put("covered", нужда.covered)
            n.put("gap", нужда.gap)
            n.put("expected", нужда.expected)
            n.put("note", нужда.note)
            val цели = n.putArray("goals")
            нужда.goals.forEach { цели.add(it) }
            val сервисы = n.putArray("services")
            нужда.services.forEach { сервисы.add(it) }
        }
        val прочее = ответ.putObject("other_coverage")
        матрица.otherCoverage.forEach { (вид, число) -> прочее.put(вид, число) }
        val края = ответ.putArray("stakeholders_without_needs")
        матрица.stakeholdersWithoutNeeds.forEach { края.add(it) }
        return V2Router.Ответ(200, ответ)
    }

    private fun задания(проект: String, роль: String?): V2Router.Ответ {
        val фаза = engine.view(проект)
        val массив = mapper.createArrayNode()
        фаза.scenes
            .filter { it.state != orbita.process.api.SceneState.DONE }
            .filter { роль == null || it.role == роль }
            .forEach { сцена ->
                сцена.blockers.forEach { причина ->
                    массив.addObject()
                        .put("scene", сцена.key)
                        .put("scene_title", сцена.title)
                        .put("role", сцена.role)
                        .put("what", причина)
                        // Сцена закрыта — работать нельзя; открыта — это моя работа
                        .put("waiting", сцена.state == orbita.process.api.SceneState.LOCKED)
                }
            }
        val ответ = mapper.createObjectNode()
        ответ.put("project", проект)
        ответ.set<JsonNode>("items", массив)
        ответ.put(
            "note",
            if (массив.isEmpty) "разрывов нет: все сцены фазы прожиты"
            else "разрывы берутся из условий сцен; закрывается разрыв работой в своей сцене",
        )
        return V2Router.Ответ(200, ответ)
    }

    private fun перечень(проект: String, вид: String): V2Router.Ответ {
        val область = Area.Project(проект)
        val массив = mapper.createArrayNode()
        // Снятое с учёта («Отменить пакет») в перечне сцены не живёт: иначе
        // после отмены на экране оставались 33 нужды-призрака (216, 17.09).
        store.list(область, вид).filter { it.status != СНЯТО }.forEach { сущность ->
            val узел = массив.addObject()
            узел.put("id", сущность.id)
            узел.put("code", сущность.code)
            узел.put("status", сущность.status)
            узел.set<JsonNode>("doc", сущность.doc)
            // Сила стороны по умолчанию (З-02): предложение [П] из роли по карте
            // истины; сетка показывает его серым, инженер правит кликом по ячейке.
            if (вид == "stakeholder" && сущность.doc.path("power").asText("").isBlank()) {
                orbita.knowledge.schema.GeneratedOntology.powerMap[сущность.doc.path("role").asText("")]
                    ?.let { узел.put("power_proposed", it) }
            }
            val носители = links.to(сущность.id, "owns").map { it.from }
            if (носители.isNotEmpty()) {
                val массивНосителей = узел.putArray("owned_by")
                носители.forEach { массивНосителей.add(it) }
            }
            val покрытия = links.to(сущность.id, "covers").map { it.from }
            if (покрытия.isNotEmpty()) {
                val массивПокрытий = узел.putArray("covered_by")
                покрытия.forEach { массивПокрытий.add(it) }
            }
        }
        val ответ = mapper.createObjectNode()
        ответ.set<JsonNode>("items", массив)
        return V2Router.Ответ(200, ответ)
    }


    private fun разобрать(тело: String?): JsonNode =
        if (тело.isNullOrBlank()) mapper.createObjectNode() else mapper.readTree(тело)

    private fun автор(тело: JsonNode): String =
        тело.path("author").asText("").ifBlank { "стенд" }

    private fun требуется(query: Map<String, String>, имя: String): String =
        query[имя] ?: throw IllegalArgumentException("нужен параметр «$имя»")
}

/** Статус снятого с учёта — тот же, что ставит отмена пакета. */
private const val СНЯТО: String = "cancelled"

/**
 * «уровни 0–3 всегда» в правилах полки: вторая цифра — глубина взятия.
 *
 * Правило живёт в САМОЙ полке: окно взятия не решает, что рекомендовать, —
 * оно читает решение полки (то же правило читает и взятие каркаса в сцене 7).
 */
private val ГЛУБИНА_ПОЛКИ: Regex = Regex("уровни\\s+(\\d+)\\s*[–-]\\s*(\\d+)\\s+всегда")
