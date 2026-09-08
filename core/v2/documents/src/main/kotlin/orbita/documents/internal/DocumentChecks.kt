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
        if (имя !in setOf("document_started", "document_complete", "document_baselined")) return null
        val шаблон = части.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: return CheckResult.no("условие «$check» не назвало шаблон документа")
        val документ = documents.list(project).firstOrNull { it.template == шаблон }
            ?: return CheckResult.no("документа по шаблону «$шаблон» в проекте нет — его заводит своя сцена")
        return when (имя) {
            "document_started" ->
                if (документ.complete > 0) CheckResult.ok
                else CheckResult.no("документ «${документ.title}» ещё пуст: ни один раздел не полон")
            "document_complete" -> {
                val ступень = части.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "MCR"
                val кСтупени = documents.document(project, документ.code, ступень)
                if (кСтупени.complete >= кСтупени.total) CheckResult.ok
                else CheckResult.no(
                    "документ «${кСтупени.title}» к $ступень: полны ${кСтупени.complete} из ${кСтупени.total} разделов",
                )
            }
            else ->
                if (documents.baselines(project, документ.code).isNotEmpty()) CheckResult.ok
                else CheckResult.no("документ «${документ.title}» не базирован: нет ни одной линии базирования")
        }
    }
}
