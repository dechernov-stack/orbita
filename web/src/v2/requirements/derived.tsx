// Грань «Выведено из» карточки требования (27.09, ответ владельца «блокеры
// Phase A»): связь derives_from с обоснованием уже в истине (`link.subtype:
// derivation`), а записать её было нечем — системное требование заводилось
// формой без родителя, и условие SRR «каждое системное выведено из
// проектного с обоснованием» закрывалось только маршрутом деривации.
//
// Родитель — пикером требований проекта, обоснование — обязательным полем,
// уточнение — словом истины. Пишет общий маршрут связей; снимает — он же.
import { useCallback, useEffect, useState } from 'react'
import { api, type KindSpec, type LinkRow, type RequirementRow } from '../api'
import { useАвтор } from '../research'

export function ВыведеноИз({ project, т }: { project: string; т: RequirementRow }) {
  const [связи, setСвязи] = useState<LinkRow[]>([])
  const [кандидаты, setКандидаты] = useState<RequirementRow[]>([])
  const [связь, setСвязь] = useState<KindSpec | null>(null)
  const [новая, setНовая] = useState({ to: '', rationale: '', subtype: 'derivation' })
  const [отказ, setОтказ] = useState<string | null>(null)
  const [занято, setЗанято] = useState(false)
  const [автор] = useАвтор()

  const перечитать = useCallback(() => {
    api.links(project, т.code, 'derives_from')
      .then((r) => setСвязи(r.items.filter((с) => с.direction === 'from')))
      .catch((e) => setОтказ(String((e as Error).message ?? e)))
  }, [project, т.code])
  useEffect(перечитать, [перечитать])
  useEffect(() => {
    // Родитель — требование уровнем выше; проектные — первыми.
    api.requirements(project)
      .then((r) => setКандидаты(r.items.filter((к) => к.code !== т.code)
        .sort((а, б) => Number(б.level === 'project') - Number(а.level === 'project'))))
      .catch(() => setКандидаты([]))
    api.kind('link').then(setСвязь).catch(() => setСвязь(null))
  }, [project, т.code])

  const слово = (поле: string, код: string | null) => (код ? связь?.enum_labels?.[поле]?.[код] ?? код : '')
  const помеха = !новая.to ? 'родитель не выбран' : !новая.rationale.trim() ? 'обоснование не названо: связь без причины неотличима от случайной' : null
  const связать = () => {
    if (помеха) return
    setЗанято(true); setОтказ(null)
    api.addLink(project, { type: 'derives_from', from: т.code, to: новая.to, rationale: новая.rationale.trim(), subtype: новая.subtype }, автор || 'инженер')
      .then(() => { setНовая({ to: '', rationale: '', subtype: новая.subtype }); перечитать() })
      .catch((e) => setОтказ(String((e as Error).message ?? e)))
      .finally(() => setЗанято(false))
  }
  const снять = (id: string) => {
    setЗанято(true); setОтказ(null)
    api.removeLink(project, id, автор || 'инженер').then(перечитать)
      .catch((e) => setОтказ(String((e as Error).message ?? e)))
      .finally(() => setЗанято(false))
  }

  return (
    <div aria-label={`выведено из: ${т.code}`}>
      {связи.length === 0
        ? <div className="v2-dim">{т.level === 'system' ? 'родителя нет — системное требование ниоткуда не выведено (условие SRR)' : 'родителя нет'}</div>
        : связи.map((с) => (
          <div key={с.id} className="v2-dim">
            <span className="v2-mono">{с.other}</span> · {с.other_title.slice(0, 80)}
            {с.subtype && <> · {слово('subtype', с.subtype)}</>}
            {с.rationale && <> · «{с.rationale.slice(0, 120)}»</>}
            <button type="button" className="v2-link" disabled={занято} title={`снять связь «выведено из ${с.other}»`}
              aria-label={`снять связь с ${с.other}`} onClick={() => снять(с.id)}> ×</button>
          </div>
        ))}
      {отказ && <div className="v2-locked">{отказ}</div>}
      <div className="v2-form v2-form--row">
        <label className="v2-inline">из
          <select aria-label="родительское требование" value={новая.to} onChange={(e) => setНовая({ ...новая, to: e.target.value })}>
            <option value="">— требование-родитель —</option>
            {кандидаты.map((к) => <option key={к.code} value={к.code}>{к.code} · {(к.title || к.statement).slice(0, 60)}</option>)}
          </select>
        </label>
        <label className="v2-inline">уточнение
          <select aria-label="вид уточнения" value={новая.subtype} onChange={(e) => setНовая({ ...новая, subtype: e.target.value })}>
            {(связь?.enums?.subtype ?? ['derivation']).map((к) => <option key={к} value={к}>{слово('subtype', к)}</option>)}
          </select>
        </label>
        <label className="v2-inline">обоснование
          <input aria-label="обоснование вывода" autoComplete="off" value={новая.rationale}
            placeholder="суточная норма проектного уровня делится на витки"
            onChange={(e) => setНовая({ ...новая, rationale: e.target.value })} />
        </label>
        <button type="button" className="v2-primary" disabled={Boolean(помеха) || занято}
          title={помеха ?? 'записать связь «выведено из» с обоснованием'} onClick={связать}>
          {занято ? 'Записываю…' : 'Вывести'}
        </button>
      </div>
    </div>
  )
}
