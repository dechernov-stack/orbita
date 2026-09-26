// Реестр допущений (шип 5 §2, вкладка «Допущения» Постановки) — по
// диспозиции, правило поставки 23.09-b (`assumption_register_rule`): в реестре
// (MCReport §10, SEMP) — факты с диспозицией «допущение» независимо от
// пометы; факт «П» без решения — кандидат, в §10 не печатается. Колонки:
// допущение · откуда · диспозиция · срок-точка. Чипы — по диспозиции.
//
// Приём допущением — окном: владелец и точка подтверждения обязательны,
// чем подтвердится и цена ошибки — по желанию (`assumption_accept_rule`);
// окно общее — им же принимает допущения вкладка «Факты» поля знаний.
import { useContext, useState } from 'react'
import { ConfirmBox, useConfirm } from '../../ui/Confirm'
import { api, type FactRow, type Gate } from '../api'
import type { ЧипОтбора } from '../ui/chips'
import { ПлотностьКонтекст } from '../ui/density'
import type { МассовоеДействие } from '../ui/mass'
import { СЛОВО_РЕШЕНИЯ } from '../words'
import { допущениеНерешено, строкиДопущений, type ДанныеПостановки } from './data'
import { Реестр, type Колонка } from './registry'

/** Точки по умолчанию, если фаза ещё не прочитана. */
const ТОЧКИ_ПО_УМОЛЧАНИЮ: [string, string][] = [['internal_review', 'внутренний обзор'], ['MCR', 'MCR'], ['KDP-A', 'KDP-A']]

/** Утверждение допущения словами: у вида «допущение» — само значение, у прочих — предмет, сказуемое, значение. */
export function допущениеСловами(ф: FactRow): string {
  if (ф.kind === 'assumption') return ф.value
  return `${ф.subject}: ${ф.predicate} — ${ф.value}${ф.unit ? ` ${ф.unit}` : ''}`
}

/** Откуда факт: документ и якорь либо рука эксперта. */
export function откудаФакт(ф: FactRow): string {
  if (ф.manual) return 'ручной ввод'
  return [ф.material, ф.anchor].filter(Boolean).join(' · ') || '—'
}

export function ОкноДопущения({ project, факты, точки, автор, onDone, onCancel }: {
  project: string
  /** Коды фактов, которые принимаются допущением. */
  факты: string[]
  точки: [string, string][]
  автор: string
  onDone: (итог: string) => void
  onCancel: () => void
}) {
  const [владелец, setВладелец] = useState('')
  const [точка, setТочка] = useState(точки[0]?.[0] ?? 'MCR')
  const [проверка, setПроверка] = useState('')
  const [цена, setЦена] = useState('')
  const [занято, setЗанято] = useState(false)
  const [отказ, setОтказ] = useState<string | null>(null)
  const допустить = () => {
    setЗанято(true); setОтказ(null)
    Promise.allSettled(факты.map((ф) => api.disposeFact(project, ф, 'assumed', 'принято допущением до подтверждения', автор, {
      owner: владелец.trim(), confirm_by: точка, validation: проверка.trim(), impact_if_wrong: цена.trim(),
    })))
      .then((итоги) => {
        const отказы = итоги.filter((и) => и.status === 'rejected') as PromiseRejectedResult[]
        if (отказы.length > 0) setОтказ(String(отказы[0].reason?.message ?? отказы[0].reason))
        onDone(отказы.length === 0 ? `допущений принято: ${факты.length}` : `принято ${факты.length - отказы.length}, отказов ${отказы.length}`)
      })
      .finally(() => setЗанято(false))
  }
  return (
    <div className="v2-object" data-why="работа" aria-label="окно приёма допущения">
      <div className="v2-object__head"><h3>Принять допущением: {факты.length === 1 ? факты[0] : `${факты.length} фактов`}</h3></div>
      <div className="v2-object__facets v2-object__facets--2">
        <div className="v2-facet">
          <label htmlFor="v2-доп-владелец">Владелец</label>
          <input id="v2-доп-владелец" value={владелец} onChange={(e) => setВладелец(e.target.value)} />
          <div className="v2-facet__hint">кто отвечает за подтверждение — обязательно</div>
        </div>
        <div className="v2-facet">
          <label htmlFor="v2-доп-точка">Точка подтверждения</label>
          <select id="v2-доп-точка" value={точка} onChange={(e) => setТочка(e.target.value)}>
            {точки.map(([к, слово]) => <option key={к} value={к}>{слово}</option>)}
          </select>
          <div className="v2-facet__hint">к этой точке допущение держит её, пока не подтверждено</div>
        </div>
        <div className="v2-facet">
          <label htmlFor="v2-доп-проверка">Чем подтвердится</label>
          <input id="v2-доп-проверка" value={проверка} onChange={(e) => setПроверка(e.target.value)} />
          <div className="v2-facet__hint">замер, расчёт, запрос поставщику — по желанию</div>
        </div>
        <div className="v2-facet">
          <label htmlFor="v2-доп-цена">Цена ошибки</label>
          <input id="v2-доп-цена" value={цена} onChange={(e) => setЦена(e.target.value)} />
          <div className="v2-facet__hint">что будет, если допущение неверно — по желанию</div>
        </div>
      </div>
      {отказ && <div className="v2-facet__err">{отказ}</div>}
      <div className="v2-form__actions">
        <button type="button" className="v2-btn v2-btn--primary" disabled={занято || !владелец.trim()}
          title={владелец.trim() ? 'поставить диспозицию «допущение»: до подтверждения точка держится им' : 'назовите владельца: без него диспозиция не ставится'}
          onClick={допустить}>{занято ? 'Записываю…' : 'Допустить'}</button>
        <button type="button" className="v2-link" onClick={onCancel} title="ничего не менять">отмена</button>
      </div>
    </div>
  )
}

export function РеестрДопущений({ project, данные, onChanged, точки }: {
  project: string
  данные: ДанныеПостановки
  onChanged: () => void
  /** Точки фазы: срок подтверждения допущения. */
  точки?: Gate[]
}) {
  const строки = строкиДопущений(данные.факты)
  const плотность = useContext(ПлотностьКонтекст)
  const автор = плотность.кто || 'инженер'
  const [окно, setОкно] = useState<string[] | null>(null)
  const [итог, setИтог] = useState<string | null>(null)
  const [ask, спросить, закрыть] = useConfirm()
  const вариантыТочек: [string, string][] = точки && точки.length > 0 ? точки.map((т) => [т.key, т.title]) : ТОЧКИ_ПО_УМОЛЧАНИЮ

  const решить = (коды: string[], решение: 'rejected' | 'noted', вопрос: string) => спросить({
    question: вопрос,
    ok: СЛОВО_РЕШЕНИЯ[решение],
    input: { label: 'почему так решили', required: true },
    onOk: (причина) => Promise.allSettled(коды.map((к) => api.disposeFact(project, к, решение, причина, автор)))
      .then((итоги) => {
        const отказы = итоги.filter((и) => и.status === 'rejected').length
        setИтог(отказы === 0 ? `${СЛОВО_РЕШЕНИЯ[решение]}: ${коды.length}` : `${СЛОВО_РЕШЕНИЯ[решение]} ${коды.length - отказы}, отказов ${отказы}`)
        onChanged()
      }),
  })

  const колонки: Колонка<FactRow>[] = [
    { key: 'код', title: 'Код', cell: (ф) => ф.code ?? ф.id, className: 'v2-mono' },
    { key: 'допущение', title: 'Допущение', cell: допущениеСловами },
    { key: 'откуда', title: 'Откуда', cell: (ф) => <span className="v2-dim">{откудаФакт(ф)}</span>, className: 'v2-nowrap' },
    {
      key: 'диспозиция', title: 'Диспозиция', className: 'v2-nowrap', hint: 'решение по факту словами истины; «П» без решения — кандидат, в §10 не печатается',
      cell: (ф) => (допущениеНерешено(ф)
        ? <span className="v2-warn">{СЛОВО_РЕШЕНИЯ.free}</span>
        : СЛОВО_РЕШЕНИЯ[ф.disposition ?? 'free'] ?? ф.disposition),
    },
    {
      key: 'срок', title: 'Срок-точка', hint: 'точка, к которой допущение подтверждается',
      cell: (ф) => ф.assumption?.confirm_by
        ? `${ф.assumption.confirm_by}${ф.assumption.owner ? ` · ${ф.assumption.owner}` : ''}`
        : <span className="v2-dim">—</span>,
    },
  ]
  const диспозиции = Array.from(new Set(строки.map((ф) => ф.disposition || 'free')))
  const чипы: ЧипОтбора<FactRow>[] = диспозиции.map((д) => ({
    key: `д-${д}`, word: д === 'free' ? 'без решения' : СЛОВО_РЕШЕНИЯ[д] ?? д, group: 'диспозиция',
    test: (ф: FactRow) => (ф.disposition || 'free') === д,
  }))
  const массово: МассовоеДействие[] = [
    { key: 'допустить', икон: 'принять', слово: 'принять допущением', клавиша: 'A', run: (коды) => setОкно(коды) },
    { key: 'отклонить', икон: 'отклонить', слово: 'отклонить', клавиша: 'R', run: (коды) => решить(коды, 'rejected', `Отклонить ${коды.length === 1 ? коды[0] : `факты (${коды.length})`}: в §10 они не попадут.`) },
    { key: 'учесть', икон: 'отложить', слово: 'учесть', run: (коды) => решить(коды, 'noted', `Учесть ${коды.length === 1 ? коды[0] : `факты (${коды.length})`} без приёма допущением.`) },
  ]

  return (
    <>
      {итог && <div className="v2-note-line">{итог}</div>}
      {окно && (
        <ОкноДопущения project={project} факты={окно} точки={вариантыТочек} автор={автор}
          onDone={(т) => { setИтог(т); setОкно(null); onChanged() }} onCancel={() => setОкно(null)} />
      )}
      <Реестр label="реестр допущений" строки={строки} ключ={(ф) => ф.code ?? ф.id} колонки={колонки} чипы={чипы}
        поиск={{ placeholder: 'найти допущение', text: допущениеСловами }}
        массово={массово}
        наборы={(видимые) => [{ key: 'нерешённые', word: 'все без решения', keys: видимые.filter(допущениеНерешено).map((ф) => ф.code ?? ф.id) }]}
        пусто={<>Допущений нет.<span className="v2-empty__why">Кандидаты — факты «П» из документов; допущением факт становится решением: владелец и точка.</span></>}
        карточка={(ф) => <КарточкаДопущения ф={ф} />} />
      <ConfirmBox request={ask} onClose={закрыть} />
    </>
  )
}

/** Карточка допущения — только чтение: факт правится решением, а не полем. */
function КарточкаДопущения({ ф }: { ф: FactRow }) {
  const а = ф.assumption
  return (
    <div className="v2-object" aria-label={`карточка ${ф.code ?? ф.id}`}>
      <div className="v2-object__head">
        <span className="v2-mono">{ф.code ?? ф.id}</span>
        <h3>{допущениеСловами(ф)}</h3>
        <span className="v2-object__meta">{откудаФакт(ф)} · помета {ф.mark}</span>
      </div>
      <div className="v2-object__facets v2-object__facets--2">
        <div className="v2-facet"><label>Диспозиция</label><div className="v2-facet__ro">{СЛОВО_РЕШЕНИЯ[ф.disposition ?? 'free'] ?? ф.disposition}</div></div>
        <div className="v2-facet"><label>Владелец</label><div className="v2-facet__ro">{а?.owner || '—'}</div></div>
        <div className="v2-facet"><label>Точка подтверждения</label><div className="v2-facet__ro">{а?.confirm_by || '—'}</div></div>
        <div className="v2-facet"><label>Чем подтвердится</label><div className="v2-facet__ro">{а?.validation || '—'}</div></div>
        <div className="v2-facet"><label>Цена ошибки</label><div className="v2-facet__ro">{а?.impact_if_wrong || '—'}</div></div>
      </div>
      {ф.disposition === 'assumed' && !а?.owner && (
        <div className="v2-object__debt"><b>к точке:</b> владелец · точка подтверждения — примите допущение окном ещё раз</div>
      )}
    </div>
  )
}
