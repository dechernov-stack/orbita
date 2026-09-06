// Экран архитектуры (ТЗ §4.3, сцена 7): слои OA · SA · Цепочки · LA · PA,
// состав узлов и КАРТОЧКА КОМПОНЕНТА ГРАНЯМИ.
//
// Главное здесь — грани: компонент не строка состава с массой, а узел, к
// которому цепляется всё, чем живёт модель. Пустая грань не молчит: она
// говорит, что в ней ожидается и к какой точке — лестницей зрелости с полки.
import { useCallback, useEffect, useState } from 'react'
import { api, type ArchLayer, type ComponentCard, type ComponentRow, type ParameterRow } from './api'

const ТОЧКИ = ['MCR', 'SRR', 'SDR', 'PDR']

export function ArchitectureScreen({ project }: { project: string | null }) {
  const [узлы, setУзлы] = useState<ComponentRow[]>([])
  const [выбран, setВыбран] = useState<string | null>(null)
  const [точка, setТочка] = useState('SDR')
  const [слои, setСлои] = useState<ArchLayer[]>([])
  const [отказ, setОтказ] = useState<string | null>(null)

  const перечитать = useCallback(() => {
    if (!project) return
    api.components(project)
      .then((r) => {
        setУзлы(r.items)
        setВыбран((текущий) => текущий ?? r.items[0]?.code ?? null)
      })
      .catch((e) => setОтказ(String(e.message ?? e)))
    api.architecture(project).then((r) => setСлои(r.layers)).catch(() => undefined)
  }, [project])

  useEffect(перечитать, [перечитать])

  if (!project) {
    return <div className="v2-card"><div className="v2-empty">Сначала откройте проект.</div></div>
  }

  return (
    <>
      {отказ && <div className="v2-card"><div className="v2-locked">{отказ}</div></div>}

      <div className="v2-card">
        <div className="v2-card__head">
          <span className="v2-card__title">Слои архитектуры</span>
          <span className="v2-card__count">{слои.filter((с) => с.lines.length > 0).length} из {слои.length}</span>
        </div>
        <div className="v2-layers">
          {слои.map((слой) => (
            <div key={слой.key} className="v2-layer">
              <div className="v2-layer__head">
                <span className="v2-layer__key">{слой.key}</span> {слой.title}
                <span className="v2-card__count">{слой.lines.length}</span>
              </div>
              {слой.lines.length === 0 ? (
                <div className="v2-empty__why">
                  {слой.key === 'OA' && 'способности и деятельности заказчика — появятся из целей и сценариев'}
                  {слой.key === 'SA' && 'функции системы — что она делает, безотносительно устройства'}
                  {слой.key === 'chains' && 'цепочки: порядок функций, дающий наблюдаемый результат'}
                  {слой.key === 'LA' && 'логические компоненты — группировка функций до выбора железа'}
                  {слой.key === 'PA' && 'узлы состава: то, что заказывается, делается и весит'}
                </div>
              ) : (
                <ul className="v2-tree">
                  {слой.lines.map((с, i) => <li key={`${слой.key}-${i}`}>{с.what}</li>)}
                </ul>
              )}
            </div>
          ))}
        </div>
      </div>

      <div className="v2-card">
        <div className="v2-card__head">
          <span className="v2-card__title">Состав системы</span>
          <span className="v2-card__count">{узлы.length}</span>
          <span className="v2-head__spacer" />
          <label className="v2-inline">Ступень
            <select value={точка} onChange={(e) => setТочка(e.target.value)}
              title="к какой точке считать закрытость граней">
              {ТОЧКИ.map((т) => <option key={т} value={т}>{т}</option>)}
            </select>
          </label>
        </div>
        {узлы.length === 0 ? (
          <div className="v2-empty">
            Состав пуст.
            <span className="v2-empty__why">
              Состав берётся каркасом класса миссии: узлы добавляются и снимаются, а не изобретаются с нуля.
            </span>
          </div>
        ) : (
          <ul className="v2-tree">
            {узлы.map((у) => (
              <li key={у.code}>
                <button type="button" className="v2-tree__node" aria-current={у.code === выбран ? 'true' : undefined}
                  title={у.nature === 'behaviour'
                    ? 'поведенческий компонент: функции, режимы, ресурсы — и обязательный носитель'
                    : 'физический носитель: масса, габарит, тепло, крепление'}
                  onClick={() => setВыбран(у.code)}>
                  {у.code} · {у.name}
                </button>
                <span className="v2-dim"> уровень {у.level} · {у.nature === 'behaviour' ? 'поведение' : 'носитель'}</span>
              </li>
            ))}
          </ul>
        )}
      </div>

      {выбран && <Карточка project={project} code={выбран} gate={точка} />}
    </>
  )
}

/** Карточка компонента: вкладки-грани, разрывы ступени сверху. */
function Карточка({ project, code, gate }: { project: string; code: string; gate: string }) {
  const [карточка, setКарточка] = useState<ComponentCard | null>(null)
  const [грань, setГрань] = useState('identity')
  const [параметры, setПараметры] = useState<ParameterRow[]>([])
  const [отказ, setОтказ] = useState<string | null>(null)

  useEffect(() => {
    api.componentCard(project, code, gate)
      .then(setКарточка)
      .catch((e) => setОтказ(String(e.message ?? e)))
    api.parameters(project, code).then((r) => setПараметры(r.items)).catch(() => undefined)
  }, [project, code, gate])

  if (отказ) return <div className="v2-card"><div className="v2-locked">{отказ}</div></div>
  if (!карточка) return <div className="v2-card"><div className="v2-empty">Читаю карточку…</div></div>

  const текущая = карточка.facets.find((г) => г.key === грань) ?? карточка.facets[0]

  return (
    <div className="v2-card">
      <div className="v2-card__head">
        <span className="v2-card__title">{карточка.code} · {карточка.name}</span>
        <span className="v2-card__count">
          {карточка.nature === 'behaviour' ? 'поведение' : 'носитель'} · уровень {карточка.level}
        </span>
      </div>

      {карточка.gaps.length > 0 && (
        <div className="v2-locked">
          Ступень {gate} не закрыта — {карточка.gaps.length}:
          <ul>{карточка.gaps.map((р) => <li key={р.facet}>{р.what}</li>)}</ul>
        </div>
      )}

      <div className="v2-tabs" role="tablist" aria-label="грани компонента">
        {карточка.facets.map((г) => (
          <button key={г.key} type="button" role="tab" className="v2-tab"
            aria-selected={г.key === текущая.key}
            title={г.lines.length === 0 && г.required_to
              ? `пусто; обязательна к точке ${г.required_to}`
              : `${г.lines.length} записей`}
            onClick={() => setГрань(г.key)}>
            {г.title}
            <span className="v2-card__count">{г.lines.length || '—'}</span>
          </button>
        ))}
      </div>

      <div className="v2-facet__body">
        {текущая.lines.length > 0 ? (
          <ul className="v2-tree">
            {текущая.lines.map((с, i) => <li key={`${текущая.key}-${i}`}>{с.what}</li>)}
          </ul>
        ) : (
          <div className="v2-empty">
            {текущая.expected ?? 'здесь пока пусто'}
            <span className="v2-empty__why">
              {текущая.required_to
                ? `грань обязана быть закрыта к точке ${текущая.required_to} — так говорит лестница зрелости шаблона.`
                : 'к ближайшей точке эта грань не спрашивается.'}
            </span>
          </div>
        )}
      </div>

      {текущая.key === 'parameters' && параметры.length > 0 && (
        <table className="v2-table">
          <thead>
            <tr><th>Ключ</th><th>Значение</th><th>Происхождение</th><th>Зрелость</th><th>Резерв</th><th>К точке</th></tr>
          </thead>
          <tbody>
            {параметры.map((п) => (
              <tr key={п.key}>
                <td>{п.name || п.key}</td>
                <td>{JSON.stringify(п.measure)}</td>
                <td title="значение без источника проверить нечем">{п.origin}</td>
                <td>{п.maturity_class} ±{п.uncertainty}%</td>
                <td title="резерв свёрток по классу зрелости — данные полки">{п.reserve_percent}%</td>
                <td>{п.required_to}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}
