// Экран «Документы» (шип 5 §5): вкладки — «Комплект» (документы фазы) и по
// вкладке на открытый документ. Документ — список разделов слева, тело
// раздела справа; печать и базирование — действиями, проверка против поля —
// эксперт-режим.
//
// Пустой раздел не молчит: он говорит, каких сцен ждёт, — по этой строке
// владелец проверяет проход, не открывая ни одной формы.
import { useEffect, useState } from 'react'
import { api, type DocView } from './api'
import { DocumentBody } from './documents/body'
import { ВкладкаКомплект } from './documents/kit'
import { Вкладки, type Вкладка } from './ui/tabs'

export { DocumentBody } from './documents/body'
export { FieldCheck } from './documents/fieldcheck'

export function Documents({ project, onGoScene, onGoField, expert = false }: {
  project: string | null
  /** Переход «к месту»: сцена, которой раздел наполняется, и зачем идём. */
  onGoScene?: (сцена: string, зачем?: string) => void
  /** Переход в поле знаний: там заводятся темы — открытые вопросы фазы. */
  onGoField?: () => void
  /** Эксперт-режим: проверка документа против поля. */
  expert?: boolean
}) {
  const [список, setСписок] = useState<DocView[] | null>(null)
  const [отказ, setОтказ] = useState<string | null>(null)
  /** Открытые документы — по вкладке на каждый; «Комплект» — всегда первой. */
  const [открытые, setОткрытые] = useState<string[]>([])
  const [вкладка, setВкладка] = useState<string>('комплект')

  const перечитать = () => {
    if (!project) return
    api.documents(project)
      .then((r) => setСписок(r.items))
      .catch((e) => setОтказ(String(e.message ?? e)))
  }
  useEffect(перечитать, [project])

  if (!project) return <div className="v2-panel" data-why="следующий-клик"><div className="v2-empty">Проект не выбран.</div></div>
  if (отказ) return <div className="v2-panel" data-why="почему-нельзя"><div className="v2-locked">{отказ}</div></div>

  const открыть = (код: string) => {
    setОткрытые((было) => (было.includes(код) ? было : [...было, код]))
    setВкладка(код)
  }
  const закрыть = (код: string) => {
    setОткрытые((было) => было.filter((к) => к !== код))
    setВкладка('комплект')
    перечитать()
  }
  const вкладки: Вкладка<string>[] = [
    { key: 'комплект', word: 'Комплект', count: список === null ? '…' : список.length, hint: 'документы фазы: полнота к ступени, выпуски, базирование; открыть, собрать PDF, базировать' },
    ...открытые.map((код) => {
      const д = (список ?? []).find((х) => х.code === код)
      return {
        key: код, word: д?.title ?? код, count: д ? `${д.complete} из ${д.total}` : null,
        health: д ? (д.complete === д.total ? 'ok' as const : 'debt' as const) : null,
        hint: д ? `${д.title}: полнота ${д.complete} из ${д.total} к ${д.gate}` : 'документ',
      }
    }),
  ]
  const текущая = вкладка === 'комплект' || открытые.includes(вкладка) ? вкладка : 'комплект'
  return (
    <div className="v2-panel" data-why="следующий-клик">
      <Вкладки label="документы" current={текущая} onChange={setВкладка} items={вкладки} />
      {текущая === 'комплект'
        ? <ВкладкаКомплект project={project} список={список ?? []} onOpen={открыть} onChanged={перечитать} onError={setОтказ} />
        : <DocumentBody key={текущая} project={project} code={текущая} expert={expert}
            onGoScene={onGoScene} onGoField={onGoField} onClose={() => закрыть(текущая)} />}
    </div>
  )
}
