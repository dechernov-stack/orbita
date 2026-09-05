// Ядро сущности v2 (МОДЕЛЬ-ДАННЫХ-V2 §1).
//
// У любой сущности — один и тот же набор: кто она (kind), где живёт (area),
// В КАКОЙ СЦЕНЕ РОДИЛАСЬ (born_in), в каком состоянии, какой версии и откуда
// взялась (provenance). Модули ядро не переопределяют: попытка завести своё
// «почти такое же» — брак по ТЗ-BACKEND §2.4.
package orbita.kernel.api

import com.fasterxml.jackson.databind.JsonNode
import java.time.OffsetDateTime

/** Область: библиотека одна на систему, проектов много. */
sealed interface Area {
    /** Полки, справочники, шаблоны — общие для всех проектов. */
    data object Library : Area

    /** Факты конкретного проекта. */
    data class Project(val id: String) : Area

    fun asText(): String = when (this) {
        is Library -> "library"
        is Project -> "project:$id"
    }

    companion object {
        fun of(text: String): Area =
            if (text == "library") Library
            else Project(text.removePrefix("project:"))
    }
}

/**
 * Канал появления сущности. Это не украшение: по нему видно, чему верить.
 * Служба — происхождение, а не автор: автором остаётся человек.
 */
enum class Channel { MANUAL, PACKAGE, SERVICE, SHELF, IMPORT, EXAMPLE }

/**
 * Происхождение значения (МОДЕЛЬ-ДАННЫХ §0.7): канал, автор-человек,
 * источник с якорем и отпечатком. Обязательно у каждой сущности.
 */
data class Provenance(
    val channel: Channel,
    val author: String,
    val source: String? = null,
    val anchor: String? = null,
    val fingerprint: String? = null,
)

/**
 * Сущность ядра.
 *
 * @property code человеческий код: переименуемый, связи держатся за id
 * @property bornIn сцена рождения; null — у сущностей библиотеки, у них сцены нет
 * @property version битемпоральная версия: правка заводит новую, старая живёт
 */
data class Entity(
    val id: String,
    val code: String,
    val kind: String,
    val area: Area,
    val bornIn: String?,
    val status: String,
    val version: Int,
    val provenance: Provenance,
    val doc: JsonNode,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)
