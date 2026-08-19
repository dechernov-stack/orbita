// Буфер store-and-forward с приоритетами (TZ-KA-008).
// Эталон spec/spacecraft_semantics.py, один в один: приоритеты C' > B' > A',
// политики вытеснения, объём от худшего интервала до сброса на НС.
// Потери от переполнения учитываются ОТДЕЛЬНО от канальных.
package orbita.ka

import kotlin.math.ceil

/** Приоритет в буфере: меньше — важнее (C' оперативного управления вытесняется последним). */
val PRIORITY: Map<String, Int> = mapOf("C_prime" to 0, "B_prime" to 1, "A_prime" to 2)

data class BufferedMsg(val klass: String, val t: Double)

enum class OverflowPolicy { DropLowestPriority, DropOldest, RejectNew }

/** Результат приёма сообщения: новая очередь и вытесненное сообщение (null — потерь нет). */
data class AdmitResult(val queue: List<BufferedMsg>, val dropped: BufferedMsg?)

fun bufferAdmit(
    queue: List<BufferedMsg>,
    msg: BufferedMsg,
    capacity: Int,
    policy: OverflowPolicy = OverflowPolicy.DropLowestPriority,
): AdmitResult {
    val q = queue + msg
    if (q.size <= capacity) return AdmitResult(q, null)
    val victim = when (policy) {
        // худший приоритет, среди равных — самое позднее (эталон: новое A' уходит первым)
        OverflowPolicy.DropLowestPriority ->
            q.maxWithOrNull(compareBy({ PRIORITY.getValue(it.klass) }, { it.t }))!!
        OverflowPolicy.DropOldest -> q.minByOrNull { it.t }!!
        OverflowPolicy.RejectNew -> return AdmitResult(queue, msg)
    }
    val idx = q.indexOfFirst { it === victim }
    return AdmitResult(q.filterIndexed { i, _ -> i != idx }, victim)
}

/** Требуемый объём буфера, сообщений — от худшего интервала до сброса на НС. */
fun requiredBufferMsgs(msgsPerS: Double, worstGapS: Double): Int =
    ceil(msgsPerS * worstGapS).toInt()

/** Потери переполнения учитываются отдельно от канальных (TZ-KA-008). */
data class BufferLosses(val overflowDropped: Int, val admitted: Int) {
    val overflowRate: Double get() = if (admitted + overflowDropped == 0) 0.0
        else overflowDropped.toDouble() / (admitted + overflowDropped)
}
