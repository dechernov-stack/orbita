// Стандартные вехи жизненного цикла за горизонтом Формулирования
// (NPR 7120.5): предлагаются кнопкой на ленте цикла — инженер не печатает
// PDR/CDR руками, а правит даты у предзаполненного ряда.
package orbita.req

import com.fasterxml.jackson.databind.ObjectMapper

object LifecycleOutlook {
    private val mapper = ObjectMapper()

    /** Пары (веха, фаза) в порядке жизненного цикла. */
    fun default(): List<Pair<String, String>> {
        val res = LifecycleOutlook::class.java.getResourceAsStream("/orbita/req/lifecycle-outlook.json")
            ?: error("lifecycle-outlook.json resource is missing")
        val n = res.use { mapper.readTree(it.readAllBytes()) }
        return n.path("milestones").map { it.path("gate").asText() to it.path("phase").asText() }
    }
}
