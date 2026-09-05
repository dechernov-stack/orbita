// Матрица покрытия нужд (МОДЕЛЬ-ДАННЫХ §5: всё вычисляется).
//
// Клетка не бывает просто пустой: если нужда не покрыта, строка говорит,
// чего именно не хватает — цели, сервиса или носителя. Края матрицы видны:
// стороны без нужд показаны отдельно, чтобы не потеряться между строк.
import { useEffect, useState } from 'react'
import { api, type CoverageMatrix } from './api'

export function Coverage({ project }: { project: string | null }) {
  const [матрица, setМатрица] = useState<CoverageMatrix | null>(null)
  const [отказ, setОтказ] = useState<string | null>(null)

  useEffect(() => {
    if (!project) return
    api.coverage(project).then(setМатрица).catch((e) => setОтказ(String(e.message ?? e)))
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
