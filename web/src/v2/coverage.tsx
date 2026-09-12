// Матрица покрытия нужд (МОДЕЛЬ-ДАННЫХ §5: всё вычисляется).
//
// Клетка не бывает просто пустой: если нужда не покрыта, строка говорит,
// чего именно не хватает — цели, сервиса или носителя. Края матрицы видны:
// стороны без нужд показаны отдельно, чтобы не потеряться между строк.
import { useEffect, useState } from 'react'
import { api, type EntityRow, type CoverageMatrix } from './api'

/**
 * З-04: сетка «влияние × интерес» — стороны по влиянию на проект (решает ·
 * влияет · информируется) и силе 1–5; цвет — отношение. Ячейка пустая —
 * пустая: разбор предлагает влияние из формулировок, инженер правит карандашом.
 */
function СеткаВлияния({ стороны }: { стороны: EntityRow[] }) {
  const колонки: [string, string][] = [['informed', 'информируется'], ['influences', 'влияет'], ['decides', 'решает']]
  const строки = [5, 4, 3, 2, 1]
  const без = стороны.filter((с) => !с.doc.influence || !с.doc.power)
  const тон = (attitude: unknown) => attitude === 'supports' ? 'v2-ok' : attitude === 'resists' ? 'v2-warn' : 'v2-muted'
  return (
    <div className="v2-card" data-why="работа">
      <div className="v2-card__head">
        <span className="v2-card__title">Влияние × сила</span>
        <span className="v2-card__count">{стороны.length}</span>
      </div>
      <table className="v2-table">
        <thead><tr><th>сила</th>{колонки.map(([k, t]) => <th key={k}>{t}</th>)}</tr></thead>
        <tbody>
          {строки.map((сила) => (
            <tr key={сила}>
              <td className="v2-mono">{сила}</td>
              {колонки.map(([k]) => (
                <td key={k}>
                  {стороны.filter((с) => с.doc.influence === k && Number(с.doc.power) === сила).map((с) => (
                    <div key={с.id} className={тон(с.doc.attitude)} title={`${с.code} · ${String(с.doc.role ?? '')}${с.doc.attitude ? ` · ${String(с.doc.attitude)}` : ''}`}>{String(с.doc.name ?? с.code)}</div>
                  ))}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
      {без.length > 0 && (
        <div className="v2-hint">Без влияния или силы: {без.map((с) => String(с.doc.name ?? с.code)).join(' · ')} — задать в сцене 3 карандашом.</div>
      )}
    </div>
  )
}

export function Coverage({ project }: { project: string | null }) {
  const [стороны, setСтороны] = useState<EntityRow[]>([])
  const [матрица, setМатрица] = useState<CoverageMatrix | null>(null)
  const [отказ, setОтказ] = useState<string | null>(null)

  useEffect(() => {
    if (!project) return
    api.coverage(project).then(setМатрица).catch((e) => setОтказ(String(e.message ?? e)))
    api.entities(project, 'stakeholder').then((r) => setСтороны(r.items)).catch(() => undefined)
  }, [project])

  if (!project) {
    return (
      <div className="v2-card">
        <div className="v2-empty">
          Проект не выбран.
          <span className="v2-empty__why">Матрица считается по связям проекта.</span>
        </div>
      </div>
    )
  }
  if (отказ) return <div className="v2-card"><div className="v2-locked">{отказ}</div></div>
  if (!матрица) return <div className="v2-card"><div className="v2-empty">Считаю покрытие…</div></div>

  return (
    <>
      <СеткаВлияния стороны={стороны} />
      <div className="v2-card">
        <div className="v2-card__head">
          <span className="v2-card__title">Покрытие нужд</span>
          <span className="v2-card__count">{матрица.covered} из {матрица.total}</span>
        </div>
        <p className="v2-empty__why">{матрица.summary}</p>
        {матрица.needs.length > 0 && (
          <table className="v2-table">
            <thead>
              <tr><th>Нужда</th><th>Чья</th><th>Цели</th><th>Сервисы</th><th>Состояние</th></tr>
            </thead>
            <tbody>
              {матрица.needs.map((н) => (
                <tr key={н.code}>
                  <td>
                    <span className="v2-mono">{н.code}</span> {н.statement}
                  </td>
                  <td>{н.owner ?? <span className="v2-warn">ничья</span>}</td>
                  <td>{н.goals.length === 0 ? '—' : н.goals.join('; ')}</td>
                  <td>{н.services.length === 0 ? '—' : н.services.join('; ')}</td>
                  <td>
                    {н.covered
                      ? <span className="v2-ok">покрыта</span>
                      : <span className="v2-warn">{н.gap}</span>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {матрица.stakeholders_without_needs.length > 0 && (
        <div className="v2-card">
          <div className="v2-card__head">
            <span className="v2-card__title">Стороны без нужд</span>
            <span className="v2-card__count">{матрица.stakeholders_without_needs.length}</span>
          </div>
          <p className="v2-empty__why">
            Эти стороны названы, но чего они хотят — не записано. Пока так, сцена 3 не закроется.
          </p>
          <ul className="v2-why">
            {матрица.stakeholders_without_needs.map((с) => <li key={с}>{с}</li>)}
          </ul>
        </div>
      )}
    </>
  )
}
