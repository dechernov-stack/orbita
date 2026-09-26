// Отпечаток промпта — один на систему: им журнал ИИ (`ai_call.fingerprint`)
// узнаёт повтор вызова, и им же запись связного текста (`rendering.
// prompt_fingerprint`) находит свой вызов в журнале — секунды и токены текста
// берутся оттуда (ответ владельца 26.09). Две копии алгоритма в двух модулях
// разошлись бы молча, и секунды у текста пропали бы без единой ошибки.
package orbita.kernel.api

import java.security.MessageDigest

object Fingerprints {
    /** SHA-256 промпта, первые 32 шестнадцатеричных знака. */
    fun prompt(prompt: String): String =
        MessageDigest.getInstance("SHA-256").digest(prompt.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
}
