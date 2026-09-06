// Генератор модели фазы: шаблон фазы (полка) → CMMN XML.
//
// Правило РЕШЕНИЕ-ДВИЖОК §2: «модель фазы порождается из шаблона фазы
// генератором; XML руками не правится». Поэтому здесь единственное место,
// где рождается XML, и никакого файла с моделью в репозитории нет.
//
// Соответствие (РЕШЕНИЕ-ДВИЖОК, таблица): сцена → stage, МЕРОПРИЯТИЕ →
// human task, точка → milestone, ворота → sentry с условием по данным
// Орбиты. Схема на экране и модель дела рождаются ЗДЕСЬ ЖЕ, из одного
// шаблона: разойтись им не с чем (РЕШЕНИЕ-ПРОЦЕСС-НА-ЭКРАНЕ §1).
package orbita.process.internal

import com.fasterxml.jackson.databind.JsonNode

object CmmnGenerator {

    /**
     * @param шаблон документ полки phase_template
     * @return XML модели дела: сцены со своими условиями входа и точки
     */
    fun изШаблона(шаблон: JsonNode): String {
        val ключ = шаблон.path("code").asText("PHT")
        val имя = шаблон.path("title").asText("Фаза")
        val сцены = шаблон.path("scenes").toList()
        val точки = шаблон.path("points").toList()

        val планИтемы = StringBuilder()
        val определения = StringBuilder()
        val сторожа = StringBuilder()

        сцены.forEach { сцена ->
            val к = сцена.path("key").asText()
            val входы = сцена.path("entry").toList()
            if (входы.isEmpty()) {
                планИтемы.append("""      <planItem id="pi_scene_$к" definitionRef="scene_$к"/>""").append('\n')
            } else {
                планИтемы.append(
                    """      <planItem id="pi_scene_$к" definitionRef="scene_$к">
        <entryCriterion id="entry_scene_$к" sentryRef="sentry_scene_$к"/>
      </planItem>""",
                ).append('\n')
                // Условие входа — переменная процесса; её выставляет оценщик
                // готовности после каждого события домена. Домена в движке нет.
                val условие = входы.joinToString(" && ") { "\${check_" + идентификатор(it.path("check").asText()) + "}" }
                сторожа.append(
                    """      <sentry id="sentry_scene_$к">
        <ifPart><condition><![CDATA[$условие]]></condition></ifPart>
      </sentry>""",
                ).append('\n')
            }
            // Мероприятия сцены — human task внутри её stage: единица
            // работы метода становится единицей работы движка.
            val дела = сцена.path("activities").toList()
            if (дела.isEmpty()) {
                определения.append(
                    """      <stage id="scene_$к" name="${экранировать(сцена.path("title").asText())}"/>""",
                ).append('\n')
            } else {
                val внутри = StringBuilder()
                дела.forEach { дело ->
                    val код = идентификатор(дело.path("code").asText())
                    внутри.append(
                        """        <planItem id="pi_act_${к}_$код" definitionRef="act_${к}_$код"/>""",
                    ).append('\n')
                }
                дела.forEach { дело ->
                    val код = идентификатор(дело.path("code").asText())
                    внутри.append(
                        """        <humanTask id="act_${к}_$код" name="${экранировать(дело.path("name").asText())}"/>""",
                    ).append('\n')
                }
                определения.append(
                    """      <stage id="scene_$к" name="${экранировать(сцена.path("title").asText())}">
$внутри      </stage>""",
                ).append('\n')
            }
        }

        точки.forEach { точка ->
            val к = идентификатор(точка.path("key").asText())
            val блокирующие = точка.path("criteria").filter { it.path("blocking").asBoolean(true) }
            планИтемы.append(
                """      <planItem id="pi_gate_$к" definitionRef="gate_$к">
        <entryCriterion id="entry_gate_$к" sentryRef="sentry_gate_$к"/>
      </planItem>""",
            ).append('\n')
            val условие = if (блокирующие.isEmpty()) "true"
            else блокирующие.joinToString(" && ") { "\${check_" + идентификатор(it.path("check").asText()) + "}" }
            сторожа.append(
                """      <sentry id="sentry_gate_$к">
        <ifPart><condition><![CDATA[$условие]]></condition></ifPart>
      </sentry>""",
            ).append('\n')
            определения.append(
                """      <milestone id="gate_$к" name="${экранировать(точка.path("title").asText())}"/>""",
            ).append('\n')
        }

        return """<?xml version="1.0" encoding="UTF-8"?>
<!-- ПОРОЖДЕНО CmmnGenerator из шаблона фазы $ключ — руками не правится -->
<definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL"
             xmlns:flowable="http://flowable.org/cmmn"
             targetNamespace="http://orbita/v2">
  <case id="${идентификатор(ключ)}" name="${экранировать(имя)}">
    <casePlanModel id="plan_${идентификатор(ключ)}" name="${экранировать(имя)}">
$планИтемы$сторожа$определения    </casePlanModel>
  </case>
</definitions>
"""
    }

    /** Ключи условий приходят из полки и могут нести двоеточие («scene_done:3»). */
    fun идентификатор(текст: String): String =
        текст.replace(Regex("[^A-Za-z0-9_]"), "_")

    private fun экранировать(текст: String): String =
        текст.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
