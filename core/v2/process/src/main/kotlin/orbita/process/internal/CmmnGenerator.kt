// Генератор модели фазы: шаблон фазы (полка) → CMMN XML.
//
// Правило РЕШЕНИЕ-ДВИЖОК §2: «модель фазы порождается из шаблона фазы
// генератором; XML руками не правится». Поэтому здесь единственное место,
// где рождается XML, и никакого файла с моделью в репозитории нет.
//
// Соответствие (РЕШЕНИЕ-ДВИЖОК, таблица): сцена → stage, шаг → human task,
// точка → milestone, ворота → sentry с условием по данным Орбиты.
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
            определения.append(
                """      <stage id="scene_$к" name="${экранировать(сцена.path("title").asText())}"/>""",
            ).append('\n')
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
