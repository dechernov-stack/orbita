// Канон Д1: текст документа → блоки с якорями.
//
// Разбор ДЕТЕРМИНИРОВАННЫЙ: одна и та же версия документа даёт один и тот
// же канон и те же якоря. На этом держится всё остальное — факт без якоря
// не существует, а якорь обязан указывать на блок, который человек найдёт
// глазами, не выходя из системы.
//
// Якорь: `s<номер раздела>` у заголовка, `s<раздел>#<номер абзаца>` у
// абзаца. Ничего умного: нумерация по порядку, и она воспроизводима.
package orbita.knowledge.internal

import java.security.MessageDigest

/** Блок канона: якорь, вид, текст. */
data class Блок(val anchor: String, val kind: String, val text: String)

data class КанонДок(val blocks: List<Блок>, val fingerprint: String) {
    /** Выжимка для промпта: якорь и текст блока, ничего сверх. */
    fun выжимка(предел: Int = 60_000): String {
        val sb = StringBuilder()
        for (б in blocks) {
            val строка = "[${б.anchor}] ${б.text}\n"
            if (sb.length + строка.length > предел) break
            sb.append(строка)
        }
        return sb.toString()
    }
}

internal object Canon {

    /**
     * Канон текста. Заголовком считается короткая строка без точки в конце
     * либо строка, начинающаяся с `#`, — этого достаточно и для markdown, и
     * для текста, извлечённого из документа.
     */
    fun of(text: String): КанонДок {
        val блоки = mutableListOf<Блок>()
        var раздел = 0
        var абзац = 0
        text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.forEach { строка ->
            if (заголовок(строка)) {
                раздел += 1
                абзац = 0
                блоки += Блок("s$раздел", "heading", строка.removePrefix("#").trim())
            } else {
                абзац += 1
                блоки += Блок("s$раздел#$абзац", "para", строка)
            }
        }
        val отпечаток = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
        return КанонДок(блоки, отпечаток)
    }

    private fun заголовок(строка: String): Boolean =
        строка.startsWith("#") ||
            (строка.length <= 90 && !строка.endsWith(".") && !строка.endsWith(";") &&
                строка.count { it == ' ' } <= 12 && строка.firstOrNull()?.isDigit() != true)
}
