// Условия ворот, которые знает контур документов (шип D): позиция
// экспертизы или строка матрицы зрелости про ДОКУМЕНТ сводится к тому,
// что мы умеем измерить — начат · полон к ступени · базирован.
//
// Критерий только читает (правило 1, 08.09): здесь нет ни `ensure`, ни
// `write`, ни `baseline` — только вопросы к порту документов.
package orbita.documents.internal

import orbita.documents.api.Documents
import orbita.readiness.api.CheckResult
import orbita.readiness.api.ExtraChecks

class DocumentChecks(private val documents: Documents) : ExtraChecks {

    override fun of(project: String, check: String): CheckResult? {
        val части = check.split(":")
        val имя = части[0]
        if (имя !in setOf("document_started", "document_complete", "document_baselined", "document_section_started", "document_version_min")) return null
        val шаблон = части.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: return CheckResult.no("условие «$check» не назвало шаблон документа")
        val документ = documents.list(project).firstOrNull { it.template == шаблон }
            ?: return CheckResult.no("документа по шаблону «$шаблон» в проекте нет — его заводит своя сцена")
        return when (имя) {
            // Начат — по СОДЕРЖАНИЮ (строки запроса или тезис в любом разделе), а
            // не по полноте к ступени: документ Phase A к MCR не ждут вовсе, и
            // считать его «пустым» из-за этого значило бы врать.
            "document_started" ->
                if (документ.complete > 0 || документ.sections.any { начат(it) }) CheckResult.ok
                else CheckResult.no("документ «${документ.title}» ещё пуст: ни в одном разделе нет ни строки, ни тезиса")
            "document_complete" -> {
                val ступень = части.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "MCR"
                val кСтупени = documents.document(project, документ.code, ступень)
                if (кСтупени.complete >= кСтупени.total) CheckResult.ok
                else CheckResult.no(
                    "документ «${кСтупени.title}» к $ступень: полны ${кСтупени.complete} из ${кСтупени.total} разделов",
                )
            }
            // Phase A (10.09): раздел начат — есть строки запроса либо тезис.
            "document_section_started" -> {
                val номер = части.getOrNull(2)?.takeIf { it.isNotBlank() }
                    ?: return CheckResult.no("условие «$check» не назвало раздел")
                val раздел = документ.sections.firstOrNull { it.no == номер }
                    ?: return CheckResult.no("в документе «${документ.title}» нет раздела $номер")
                if (начат(раздел)) CheckResult.ok
                else CheckResult.no("раздел $номер «${раздел.title}» документа «${документ.title}» пуст: " + раздел.waiting.joinToString("; ").ifBlank { "нужны строки или тезис" })
            }
            // Phase A (10.09): документ обновлён — версия записи не ниже требуемой.
            "document_version_min" -> {
                val нужно = части.getOrNull(2)?.toIntOrNull() ?: 2
                if (документ.version >= нужно) CheckResult.ok
                else CheckResult.no("документ «${документ.title}» версии ${документ.version} из $нужно: обновите его (новые тезисы или разделы)")
            }
            else ->
                if (documents.baselines(project, документ.code).isNotEmpty()) CheckResult.ok
                else CheckResult.no("документ «${документ.title}» не базирован: нет ни одной линии базирования")
        }
    }

    /** Раздел начат: есть строки запроса либо текст тезиса. */
    private fun начат(раздел: orbita.documents.api.SectionView): Boolean =
        раздел.elements.any { it.rows.isNotEmpty() || !it.text.isNullOrBlank() }
}
