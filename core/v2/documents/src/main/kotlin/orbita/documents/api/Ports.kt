// Документы: живой снимок сцен (ЗАДАНИЕ-ДОКУМЕНТЫ-СЕЙЧАС §1).
//
// Три правила, из которых всё следует:
//   · документ — НЕ текст, а структура: раздел = запрос к выходам сцен
//     плюс тезисы. Выполненная сцена даёт заполненный раздел;
//   · пустой раздел не молчит: он говорит, какой сцены ждёт;
//   · свободный текст — только тезис (statement), и число в нём без опоры
//     на элемент — помета, а не тихая правда.
package orbita.documents.api

/** Вид элемента раздела. Свободный текст — только у statement. */
enum class ElementKind { QUERY, STATEMENT, ENTITY_REF, FACT_REF, TABLE, FIGURE }

/** Колонка запроса: заголовок для человека и поле, откуда берётся. */
data class ColumnView(val title: String, val field: String)

/**
 * Элемент раздела в разрешённом виде: запрос уже посчитан.
 *
 * @property rows строки таблицы (для query/table); у тезиса пусто
 * @property minRows сколько строк ждёт шаблон на ступень
 * @property waitingScenes сцены, чьих выходов не хватает, когда строк мало
 */
data class ElementView(
    val code: String,
    val kind: ElementKind,
    val title: String,
    val columns: List<ColumnView>,
    val rows: List<List<String>>,
    val text: String?,
    /** Опоры тезиса: коды элементов или сущностей, откуда числа. */
    val supports: List<String>,
    val minRows: Int,
    val satisfied: Boolean,
    val waitingScenes: List<String>,
    /** Пометы: число без опоры и прочее, что видно на глаз. */
    val notes: List<String>,
)

data class SectionView(
    val no: String,
    val title: String,
    /** Сцены-источники раздела: из них он наполняется. */
    val scenes: List<String>,
    val elements: List<ElementView>,
    val complete: Boolean,
    /** Чего ждёт раздел — словами, с именами сцен. */
    val waiting: List<String>,
    /**
     * Ступень, к которой раздел обязан быть полон (`expects` шаблона).
     * Пусто — шаблон не назвал ступень, и раздел спрашивают всегда.
     */
    val expectedBy: String? = null,
    /**
     * Ждут ли этот раздел К УКАЗАННОЙ ступени. Раздел, которого ступень
     * ещё не ждёт, документ не держит: незаполненность и незрелость —
     * разные вещи, и валить их в одну цифру значит врать о готовности.
     */
    val dueNow: Boolean = true,
)

data class DocumentView(
    val code: String,
    val title: String,
    val template: String,
    val standard: String,
    val sections: List<SectionView>,
    /** Полнота к ступени: сколько разделов полны из скольких ОЖИДАЕМЫХ. */
    val complete: Int,
    val total: Int,
    /** Ступень, к которой считалась полнота. */
    val gate: String = "MCR",
    /** Разделы, которых эта ступень ещё не ждёт. */
    val notDueYet: Int = 0,
)

/** Строка «в документ» для мероприятия: куда попадает его работа. */
data class DocumentHint(
    val document: String,
    val section: String,
    val sectionTitle: String,
    val elements: Int,
    val filled: Int,
)

/** Готовый к печати текст: заголовок, разделы строками. */
data class PrintView(
    val title: String,
    val subtitle: String,
    val sections: List<PrintSection>,
)

data class PrintSection(val no: String, val title: String, val lines: List<String>)

/**
 * Элемент снимка: единица, по которой считается расхождение.
 *
 * `mid` — устойчивое имя элемента в документе (код тезиса, код запроса с
 * номером строки). Именно по нему идёт диф: без устойчивого имени любая
 * правка выглядит как «документ изменился целиком».
 */
data class BaselineElement(
    val mid: String,
    val kind: String,
    val section: String,
    val fields: Map<String, String>,
)

/** Базовая линия документа: снимок плюс отметка в репозитории базирований. */
data class DocumentBaseline(
    val name: String,
    val document: String,
    val elements: List<BaselineElement>,
    val by: String,
    val at: String,
    /** Имя аннотированного тега; пусто — репозиторий недоступен. */
    val tag: String,
    /** Коммит тега; пусто — отметка не поставлена, и это сказано вслух. */
    val commit: String,
    /** Почему отметки нет, если её нет. */
    val note: String = "",
)

/** Расхождение одного поля одного элемента. */
data class FieldChange(
    val mid: String,
    val section: String,
    /** `added` · `removed` · `changed` — что случилось с элементом. */
    val change: String,
    val field: String,
    val was: String,
    val now: String,
)

interface Documents {
    /** Завести документ проекта по шаблону полки; повтор — тот же документ. */
    fun ensure(project: String, templateCode: String, author: String): DocumentView

    fun list(project: String): List<DocumentView>

    /** Документ целиком: запросы посчитаны, полнота — к названной ступени. */
    fun document(project: String, code: String, gate: String = "MCR"): DocumentView

    /** Тезис — единственный свободный текст документа. */
    fun addStatement(
        project: String,
        code: String,
        section: String,
        text: String,
        supports: List<String>,
        author: String,
    ): SectionView

    /** Куда попадает работа сцены: строка «в документ» мероприятия. */
    fun hintsForScene(project: String, scene: String): List<DocumentHint>

    /** Связный текст stub-рендером: таблицы и списки из элементов. */
    fun render(project: String, code: String): PrintView

    /**
     * Печать в PDF теми же строками, что на экране. Печать НАСТОЯЩАЯ:
     * нет шрифта — нет файла, запасного «похожего» вывода не бывает.
     */
    fun print(project: String, code: String, projectName: String): ByteArray

    /**
     * Зафиксировать документ базовой линией.
     *
     * Имя базовой линии — человеческое («внутренний обзор»): им её и
     * зовут на точке. Повторное имя отклоняется: базовая линия
     * неизменяема, перебазирование заводит новое имя.
     */
    fun baseline(project: String, code: String, name: String, author: String): DocumentBaseline

    fun baselines(project: String, code: String): List<DocumentBaseline>

    /**
     * Расхождение документа с базовой линией — ПОЛЕВОЕ, по mid.
     *
     * @param to имя второй линии; пусто — сравнение с текущим состоянием
     */
    fun diff(project: String, code: String, from: String, to: String? = null): List<FieldChange>
}
