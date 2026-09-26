// Реестр ограничений Р (шип 5 §2): вкладка «Ограничения» Постановки и тело
// сцены 5 — один компонент. Колонки: код · формулировка · тип · откуда ·
// типовое или своё (канал провенанса: с полки библиотеки — типовое) ·
// отменено (кто, когда, почему; история — в карточке). Чипы: по типу ·
// отменённые.
import { useContext, useState } from 'react'
import { ConfirmBox, useConfirm } from '../../ui/Confirm'
import { api, type EntityRow } from '../api'
import type { ЧипОтбора } from '../ui/chips'
import { ПлотностьКонтекст } from '../ui/density'
import type { МассовоеДействие } from '../ui/mass'
import { Карточка } from '../ui/objectcard'
import { словом } from './data'
import { откуда } from './goals'
import { Реестр, type Колонка } from './registry'
import type { ПропсыРеестра } from './stakeholders'

/** Формулировка ограничения: поле истины, а у прежних записей — «текст». */
export function формулировкаОграничения(о: EntityRow): string {
  return String(о.doc.statement ?? о.doc.text ?? о.code)
}

/** Отмена словами: кто · когда · почему; null — ограничение действует. */
export function отменаСловами(о: EntityRow): string | null {
  const от = о.doc.cancelled
  if (!от || typeof от !== 'object') return null
  const { by, at, reason } = от as Record<string, unknown>
  return [by, at ? String(at).slice(0, 10) : null, reason].filter(Boolean).map(String).join(' · ') || 'отменено'
}

export function РеестрОграничений({ project, данные, onChanged, onGoScene }: ПропсыРеестра) {
  const { ограничения } = данные
  const вид = данные.виды.constraint
  const плотность = useContext(ПлотностьКонтекст)
  const автор = плотность.кто || 'инженер'
  const [итог, setИтог] = useState<string | null>(null)
  const [ask, спросить, закрыть] = useConfirm()

  const тип = (коды: string[]) => спросить({
    question: `Тип ${коды.length === 1 ? `ограничения ${коды[0]}` : `ограничений (${коды.length})`}.`,
    ok: 'Записать',
    choice: { label: 'тип', options: (вид?.enums?.type ?? []).map((к) => [к, словом(вид, 'type', к)] as [string, string]) },
    onOk: (т) => Promise.allSettled(коды.map((к) => api.patchEntity(project, к, { type: т }, автор, 'тип ограничения')))
      .then((итоги) => {
        const отказы = итоги.filter((и) => и.status === 'rejected').length
        setИтог(отказы === 0 ? `тип записан: ${коды.length}` : `записано ${коды.length - отказы}, отказов ${отказы}`)
        onChanged()
      }),
  })

  const колонки: Колонка<EntityRow>[] = [
    { key: 'код', title: 'Код', cell: (о) => о.code, className: 'v2-mono', hint: 'код стабилен: на него ссылаются промпты и трассировки' },
    { key: 'формулировка', title: 'Формулировка', cell: (о) => формулировкаОграничения(о) },
    { key: 'тип', title: 'Тип', cell: (о) => словом(вид, 'type', о.doc.type ?? о.doc.category) || <span className="v2-dim">не задан</span> },
    { key: 'откуда', title: 'Откуда', cell: (о) => <span className="v2-dim">{откуда(о)}</span> },
    {
      key: 'типовое', title: 'Типовое или своё', hint: 'типовое — с полки класса миссии; своё — заведено в проекте',
      cell: (о) => (о.channel === 'shelf' ? 'типовое' : 'своё'),
    },
    {
      key: 'отменено', title: 'Отменено',
      cell: (о) => {
        const от = отменаСловами(о)
        return от ? <span className="v2-warn">{от}</span> : <span className="v2-dim">действует</span>
      },
    },
  ]
  const типы = Array.from(new Set(ограничения.map((о) => String(о.doc.type ?? '')).filter(Boolean)))
  const чипы: ЧипОтбора<EntityRow>[] = [
    ...типы.map((т) => ({ key: `тип-${т}`, word: словом(вид, 'type', т), group: 'тип', test: (о: EntityRow) => о.doc.type === т })),
    { key: 'отменённые', word: 'отменённые', group: 'отмена', test: (о) => отменаСловами(о) !== null, hint: 'отменённые ограничения — с тем, кто, когда и почему' },
  ]
  const массово: МассовоеДействие[] = [{ key: 'тип', икон: 'править', слово: 'тип', run: тип }]

  return (
    <>
      {итог && <div className="v2-note-line">{итог}</div>}
      <Реестр label="реестр ограничений" строки={ограничения} ключ={(о) => о.code} колонки={колонки} чипы={чипы}
        поиск={{ placeholder: 'найти ограничение', text: формулировкаОграничения }}
        массово={массово}
        пусто={<>Ограничений пока нет.<span className="v2-empty__why">Рамки задаются на сцене 5 и дальше работают запретами для службы и проверок.</span></>}
        карточка={(о, закрытьКарточку) => вид
          ? (
            <Карточка project={project} row={о} spec={вид} заголовок={формулировкаОграничения(о)} onSaved={onChanged} onClose={закрытьКарточку}
              кМесту={onGoScene ? { слово: 'к месту: сцена 5', go: () => onGoScene('5', `ограничение ${о.code}`) } : undefined} />
          )
          : <div className="v2-empty">Читаю истину вида…</div>} />
      <ConfirmBox request={ask} onClose={закрыть} />
    </>
  )
}

