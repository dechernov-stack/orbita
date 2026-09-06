// Stub-рендер: раздел строками, читаемыми человеком.
//
// Это НЕ проза — это таблицы и списки из элементов, и своей задачи хватает:
// по такому разделу владелец проверяет сцены, а не литературу. Живой
// LLM-рендер придёт тем же портом и заменит только текст, оставив
// структуру, опоры и сторожа на месте (ОБЗОР-DOCPILOT §Решения 3).
//
// Печать обязана быть человеческим текстом: служебных ключей и латинских
// имён видов здесь нет — колонки называет шаблон по-русски.
package orbita.documents.internal

import orbita.documents.api.ElementKind
import orbita.documents.api.SectionView

internal object StubRender {

    fun lines(раздел: SectionView): List<String> {
        val строки = mutableListOf<String>()
        раздел.elements.forEach { элемент ->
            when (элемент.kind) {
                ElementKind.STATEMENT -> {
                    элемент.text?.let { строки += it }
                    элемент.notes.forEach { строки += "  помета: $it" }
                }
                else -> {
                    строки += элемент.title
                    if (элемент.rows.isEmpty()) {
                        строки += "  — сведений нет: " + when {
                            элемент.waitingScenes.isEmpty() -> "запрос не дал строк"
                            // Сквозной раздел питается всей фазой: перечень из
                            // десяти сцен не говорит ничего.
                            элемент.waitingScenes.size > 3 -> "наполняется по ходу фазы"
                            else -> "ждёт сцен " + элемент.waitingScenes.joinToString(", ")
                        }
                    } else {
                        строки += "  " + элемент.columns.joinToString(" | ") { it.title }
                        элемент.rows.forEach { строка ->
                            строки += "  " + строка.joinToString(" | ") { it.ifBlank { "—" } }
                        }
                    }
                }
            }
        }
        // Итог раздела не повторяет то, что уже сказал каждый пустой
        // элемент: в печати это было двумя одинаковыми строками подряд.
        return строки
    }
}
