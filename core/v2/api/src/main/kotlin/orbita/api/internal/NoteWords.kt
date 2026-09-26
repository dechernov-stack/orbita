// Подсказка поля словами (ответ владельца 26.09 на вопрос §7 шипа 5): в
// примечаниях истины встречаются коды — поля («phase_current»), значения
// перечня («behaviour … node»), поле другого вида («gate.kind»). При показе
// код поля подменяется его `label`, значение перечня — меткой истины, поле
// другого вида — меткой с именем вида. Сама истина не меняется: примечание
// остаётся как есть в `notes`, экран читает `note_words`.
package orbita.api.internal

import orbita.kernel.schema.Enums
import orbita.kernel.schema.GeneratedKinds
import orbita.kernel.schema.KindSpec

internal object NoteWords {

    /** Код в тексте: строчные латинские буквы, цифры и подчёркивание; «вид.поле» — одним токеном. */
    private val КОД = Regex("(?<![A-Za-z0-9_])[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)?(?![A-Za-z0-9_])")

    fun словами(вид: KindSpec, примечание: String): String {
        // Метки значений перечней своего вида: «behaviour» → «поведение (ПО)».
        val своиЗначения = вид.enums.keys.flatMap { поле -> Enums.значения(вид.code, поле).entries }
            .associate { it.key to it.value }
        // Значения после «вид.поле» читаются перечнем ЭТОГО поля: «gate.kind phase|technology».
        var чужиеЗначения: Map<String, String> = emptyMap()
        return КОД.replace(примечание) { м ->
            val токен = м.value
            if ('.' in токен) {
                val (кодВида, поле) = токен.split('.', limit = 2)
                val другой = GeneratedKinds.byCode[кодВида]
                val метка = другой?.labels?.get(поле)
                if (другой == null || метка == null) return@replace токен
                чужиеЗначения = Enums.значения(кодВида, поле)
                return@replace "«$метка» (${другой.title})"
            }
            вид.labels[токен]?.let { return@replace "«$it»" }
            чужиеЗначения[токен]?.let { return@replace "«$it»" }
            своиЗначения[токен]?.let { return@replace "«$it»" }
            токен
        }
    }
}
