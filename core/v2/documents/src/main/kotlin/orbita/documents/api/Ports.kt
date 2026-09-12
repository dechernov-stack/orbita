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
    /** Версия документа: версия записи плюс число тезисов — растёт с каждым обновлением (условие «FA обновлён»). */
    val version: Int = 1,
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
    /**
     * Авторы — ИЗ ЭЛЕМЕНТОВ, а не из подписи под печатью: документ пишут
     * те, кто завёл его содержание, и титул обязан называть именно их.
     */
    val authors: List<String> = emptyList(),
    /**
     * Базовая линия на момент печати: имя и тег. Лист без неё не отвечает
     * на главный вопрос читателя — «это зафиксированное состояние или
     * черновик, который завтра другой».
     */
    val baseline: String = "",
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

/**
 * Связный текст раздела, написанный живой моделью из его элементов.
 *
 * Модель НЕ источник сведений: она излагает то, что уже есть в разделе.
 * Поэтому у рендеринга две проверки — числа и квалификаторы, — и обе
 * атомарны: раздел принимается целиком либо не принимается вовсе.
 * Полупринятый текст хуже отсутствующего: в нём не видно, где враньё.
 */
data class RenderedSection(
    val section: String,
    val title: String,
    val text: String,
    val model: String,
    /** Пусто — текст принят. Иначе текст ОТКЛОНЁН и причины названы. */
    val refusals: List<String>,
    /** Сказано, но не отклонено: человек смотрит и решает сам. */
    val notes: List<String> = emptyList(),
) {
    val accepted: Boolean get() = refusals.isEmpty()
}

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

    /**
     * «Написать связно»: раздел прозой из его же элементов.
     *
     * Число, которого нет ни в одном элементе, — отказ: документ не место,
     * где числа появляются. Пропавший или добавленный квалификатор
     * («не менее», «только») — тоже отказ: он меняет смысл требования, а
     * не изложение.
     */
    fun write(project: String, code: String, section: String, author: String): RenderedSection

    /** Принятые связные тексты разделов документа. */
    fun renderings(project: String, code: String): List<RenderedSection>
}

// --- Верификация документа против поля знаний ------------------------------
//
// Документ собран из выходов сцен и тезисов человека, а поле знаний живёт
// своей жизнью: приходит новая версия источника, эксперт заводит факт,
// разбор находит противоречие между документами. Отсюда вопрос, на который
// не отвечает ни полнота, ни базирование: СХОДИТСЯ ЛИ написанное с полем.
//
// Правило одно и оно жёсткое: верификация НИЧЕГО НЕ ПРАВИТ. Она заводит
// отчёт с находками — и всё. Молча исправленный документ — худшее, что
// может случиться с документом: читатель видит текст, а не то, что текст
// однажды разошёлся с основанием и кто-то это сгладил. Поэтому у порта нет
// ни «исправить», ни «исправить всё»: исправляет человек, своей правкой, в
// своей сцене.

/**
 * Род находки — истина схем (`verification_report.items.issue`).
 *
 * Четыре и только четыре: тезис без основания · число, которого нет в поле ·
 * противоречие оснований · устаревшая опора. Пятый род пришлось бы сначала
 * завести в YAML.
 */
enum class Issue(val code: String, val word: String) {
    NO_BASIS("no_basis", "без основания"),
    NUMBER_VS_FACT("number_vs_fact", "число против факта"),
    CONTRADICTION("contradiction", "противоречие"),
    STALE("stale", "устарело"),
    ;

    companion object {
        /** Род из хранимого значения; неизвестное — отказ, а не тихое умолчание. */
        fun of(code: String): Issue = entries.firstOrNull { it.code == code }
            ?: error(
                "рода находки «$code» в истине схем нет: " +
                    entries.joinToString(" · ") { it.code },
            )
    }
}

/**
 * Находка верификации.
 *
 * @property element `mid` документа — тот же адрес, по которому считается
 *   расхождение с базовой линией: находка обязана вести В МЕСТО, а не в
 *   документ вообще
 * @property detail словами, с ОБОИМИ значениями и их рангами: «похоже» без
 *   «чем именно» человек проверить не может
 * @property proposal что можно сделать. Предложение, а не действие: кнопки
 *   «исправить» у находки нет
 */
data class VerificationItem(
    val element: String,
    val issue: Issue,
    val detail: String,
    val proposal: String? = null,
) {
    init {
        require(element.isNotBlank()) {
            "находка без элемента документа не говорит, где смотреть, — такой находки не бывает"
        }
        require(detail.isNotBlank()) {
            "находка «${issue.word}» без объяснения словами бесполезна: человек обязан понять, что не сошлось"
        }
    }
}

/**
 * Отчёт верификации: снимок расхождений документа с полем на момент `at`.
 *
 * Отчёт НЕ обновляется: каждая проверка заводит новый. Обновляемый отчёт
 * стирал бы историю расхождения — а вопрос «когда это разошлось и что мы
 * тогда решили» и есть главный вопрос обзора.
 */
data class VerificationReport(
    val code: String,
    val document: String,
    /** Код снимка базирования, против которого проверяли; пусто — против текущего состояния. */
    val baseline: String? = null,
    /** Человеческое имя той же линии («внутренний обзор»). */
    val baselineName: String? = null,
    val items: List<VerificationItem>,
    val at: String,
    val status: String = OPEN,
    /** Сводка словами: «проверять нечего» либо счёт находок по родам. */
    val summary: String = "",
) {
    /** Разобран ли отчёт человеком. Закрытие — решение, а не срок давности. */
    val isOpen: Boolean get() = status == OPEN

    /** Счёт находок по родам — в порядке перечня, без пустых. */
    fun byIssue(): Map<Issue, Int> = Issue.entries
        .associateWith { род -> items.count { it.issue == род } }
        .filterValues { it > 0 }

    companion object {
        const val OPEN: String = "open"
        const val CLOSED: String = "closed"
    }
}

/**
 * Проверка документа против поля знаний.
 *
 * Метода «исправить» здесь нет намеренно: служба показывает расхождение и
 * предлагает, правит человек. Отсюда же отсутствие «исправить всё» — оптом
 * можно только не читать.
 */
interface Verification {

    /**
     * Проверить документ и записать отчёт.
     *
     * Ничего, кроме самого отчёта, в модели не меняется: версии документа,
     * версии сущностей и диспозиции фактов остаются прежними до единицы.
     *
     * @param baseline имя базовой линии, против которой проверять; пусто —
     *   против текущего состояния поля. Линия нужна роду «устарело»: без неё
     *   не с чем сравнить возраст опоры
     */
    fun verify(
        project: String,
        document: String,
        author: String,
        baseline: String? = null,
    ): VerificationReport

    /** Отчёты проекта; `document` пусто — по всем документам. Порядок — по времени. */
    fun reports(project: String, document: String? = null): List<VerificationReport>

    fun report(project: String, code: String): VerificationReport

    /**
     * Закрыть отчёт: находки разобраны.
     *
     * Закрытие — решение ЧЕЛОВЕКА с причиной; отчёт при этом не редактируется
     * и находки из него не исчезают.
     */
    fun close(project: String, code: String, author: String, reason: String): VerificationReport
}
