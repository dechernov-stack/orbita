// Порты обмена (шип F, ЗАДАНИЕ-CODE-БОЛЬШОЕ): StrictDoc-канал по грамматике
// Орбиты, ReqIF штатный (сам StrictDoc), выгрузка знаний с отпечатком,
// пакет точки одним архивом.
//
// Собственного конвертера форматов здесь нет и не будет (ADR-049): ядро
// раскладывает реестр в грамматику поле в поле, а .sdoc, ReqIF и разбор
// делает служба StrictDoc. Служба — порт: тесты идут без контейнера.
package orbita.exchange.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/** Грамматика и документ StrictDoc одного проекта. */
data class SdocBundle(val sgra: String, val sdoc: String)

/** Снимок базирования .sdoc: файлы на диске, диф двух снимков — обычный diff. */
data class SdocBaseline(val dir: String, val sdoc: String, val sgra: String, val at: String, val author: String)

/**
 * Кандидат из импорта .sdoc: поля грамматики Орбиты — своими именами,
 * чужие поля — отдельно, чтобы было видно, что приехало сверх схемы.
 */
data class ImportedRequirement(
    val uid: String,
    val title: String?,
    val statement: String?,
    val category: String?,
    val level: String?,
    val priority: String?,
    val rationale: String?,
    val acceptanceCriteria: String?,
    val verificationMethod: String?,
    /** name · operator · value · unit — показатель парой. */
    val mop: Map<String, String>,
    val foreign: Map<String, String>,
    /** ссылка → роль (Traces, Derives…). */
    val relations: List<Pair<String, String>>,
)

/**
 * Итог импорта: кандидаты И задание загрузки с планом — в модель канал
 * сам не пишет, требование появится только принятием действия в поле
 * знаний (то же правило, что у разбора документа).
 *
 * @property task код задания загрузки; пусто — кандидатов нет
 * @property material код материала (.sdoc положен входным документом)
 */
data class SdocImport(val candidates: List<ImportedRequirement>, val task: String?, val material: String?, val note: String)

/** Часть выгрузки знаний: ключ, имя файла, заголовок. Порядок постоянен. */
data class KnowledgePart(val key: String, val file: String, val title: String)

/**
 * Пакет знаний: файлы С ШАПКОЙ, в которой стоит отпечаток всей выгрузки.
 * Файл, вырванный из пакета, всё равно знает, частью чего он был.
 */
data class KnowledgeBundle(val fingerprint: String, val files: Map<String, String>)

/**
 * Отпечаток выгрузки знаний: sha256 по отсортированным файлам — имя и тело
 * БЕЗ шапки, первые 16 знаков. Тот же алгоритм, что у первой версии
 * (core/com KnowledgeExport): пакет, собранный там и здесь по одним
 * знаниям, сверяется одним числом.
 */
object KnowledgeFingerprint {
    const val HEADER_END: String = "<!-- /отпечаток -->\n"

    fun of(files: Map<String, String>): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        files.toSortedMap().forEach { (name, body) ->
            digest.update(name.toByteArray())
            // шапка с отпечатком в хеш не входит — иначе он зависел бы от себя;
            // пустая строка после шапки — её часть, и файл с шапкой даёт тот
            // же отпечаток, что тело без неё
            digest.update(body.substringAfter(HEADER_END, body).removePrefix("\n").toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(16)
    }
}

/** Сверка отпечатка: ответ внешней службы против нынешних знаний. */
data class FingerprintCheck(val current: String, val said: String, val ok: Boolean, val warning: String?)

/** Пакет точки одним архивом. */
data class PointPackage(val fileName: String, val bytes: ByteArray, val entries: List<String>, val fingerprint: String)

/**
 * Итог импорта чужого файла обмена: кандидаты со всеми атрибутами и задание
 * загрузки с планом — в модель канал сам не пишет.
 */
data class ForeignImport(val candidates: List<ForeignCandidate>, val task: String?, val material: String?, val note: String)

/**
 * Служба обмена (ops/exchange): разбор файла обмена библиотекой. Ответ —
 * `{title, objects[{identifier, type, type_name, values, std}], relations}`;
 * формат знает служба, ядро — нет.
 */
fun interface ReqifParser {
    fun parse(xml: String): JsonNode
}

/** Служба StrictDoc не настроена или не отвечает: отказ словами, не пустой файл. */
class ExchangeUnavailable(message: String) : IllegalStateException(message)

/**
 * Служба StrictDoc: сборка .sdoc из раскладки, штатный экспорт ReqIF,
 * разбор документа средствами самого StrictDoc.
 */
interface StrictDocService {
    fun build(payload: JsonNode): SdocBundle
    fun reqif(payload: JsonNode): String
    /** @return `{"requirements": [...]}` — как отдаёт служба */
    fun parse(sdoc: String, sgra: String?): JsonNode
}

interface Exchange {
    /** Раскладка реестра в грамматику: нужды · сервисы · требования с показателем · нити. */
    fun sdocPayload(project: String): ObjectNode

    fun sdoc(project: String): SdocBundle

    fun reqif(project: String): String

    fun baseline(project: String, author: String): SdocBaseline

    /** Импорт .sdoc: кандидаты и задание загрузки; в модель не пишет. */
    fun importSdoc(project: String, sdoc: String, sgra: String?, author: String): SdocImport

    /** Импорт чужого файла обмена: кандидаты со всеми атрибутами и план; в модель не пишет. */
    fun importForeign(project: String, xml: String, author: String): ForeignImport

    fun knowledgeParts(): List<KnowledgePart>

    /** @param parts ключи частей; пусто — все */
    fun knowledge(project: String, parts: Set<String>? = null): KnowledgeBundle

    /** Отпечаток, названный внешней службой, против нынешнего: устарел — предупреждение, не отказ. */
    fun verify(project: String, said: String): FingerprintCheck

    /**
     * Пакет точки: JSON точки, печать документов к ней, .sdoc с грамматикой,
     * пакет знаний, манифест. Печать и точка приходят готовыми — печатать
     * и считать точки умеют свои модули.
     *
     * @param documents код документа → PDF
     */
    fun pointPackage(project: String, gate: String, gateTitle: String, point: JsonNode, documents: Map<String, ByteArray>): PointPackage
}
