// Публичный контракт модуля «api» (слой фасад).
//
// Тонкие маршруты по модулям: файл ≤ 300 строк, домена внутри нет.
//
// Правило ТЗ-BACKEND §2.1: другие модули видят ТОЛЬКО этот пакет.
// Реализация живёт в orbita.api.internal и наружу не выносится.
package orbita.api.api

object ModuleInfo {
    const val NAME: String = "api"
    const val LAYER: String = "фасад"
    const val PURPOSE: String = "Тонкие маршруты по модулям: файл ≤ 300 строк, домена внутри нет."
}
