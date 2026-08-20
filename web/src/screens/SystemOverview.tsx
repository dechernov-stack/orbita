// Экран 12 — система в целом: бюджеты, состояние верификации, риски, проблемы.
// Предобзорный экран: весь проект виден разом.
//
// Критичность клеток матрицы рисков посчитана сервером. Раскрасить их по
// собственной формуле означало бы завести вторую матрицу — и однажды один
// риск оказался бы красным здесь и жёлтым в реестре (STEP-7-9, ловушка 2).
import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { SystemOverview as Overview } from '../api/types'
import { BudgetGauge } from '../ui/parts'

const CRITICALITY_CLASS: Record<string, string> = {
  low: 'cell--low',
  medium: 'cell--medium',
  high: 'cell--high',
}

export function SystemOverview() {
  const [view, setView] = useState<Overview | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api.systemOverview().then(setView).catch((e) => setError(String(e)))
  }, [])

  if (error) return <div className="empty">Ошибка обращения к API: {error}</div>
  if (!view) return <div className="empty">Загрузка…</div>

  return (
    <div className="split">
      <div className="pane" style={{ padding: 16 }}>
        <h2 style={{ fontSize: 15, marginTop: 0 }}>Система в целом</h2>

        <div style={{ display: 'flex', gap: 24, marginBottom: 16 }}>
          <Stat label="Требований" value={view.requirements} />
          <Stat label="Элементов и стыков" value={view.components} />
          <Stat label="Рисков активно" value={view.riskSummary.active} />
          <Stat label="Проблем" value={view.problems.length} warn={view.problems.length > 0} />
        </div>

        <div className="card">
          <h3>Бюджеты</h3>
          <div>
            {Object.entries(view.budgets).map(([id, bar]) => (
              <div key={id} className="field">
                <label>
                  <span className="id">{id}</span>
                </label>
                <BudgetGauge bar={bar} />
              </div>
            ))}
            {Object.keys(view.budgets).length === 0 && (
              <span className="secondary">свёрток в проекте нет</span>
            )}
          </div>
        </div>

        <div className="card">
          <h3>Состояние верификации</h3>
          <div>
            {Object.entries(view.verification).map(([state, count]) => (
              <div key={state} className="field">
                <label>{state}</label>
                <span className="mono">{count}</span>
              </div>
            ))}
          </div>
        </div>

        <div className="card">
          <h3>Матрица рисков</h3>
          <div>
            <RiskMatrix view={view} />
            <p className="secondary">
              Матрица несимметрична: тяжёлые последствия весят больше вероятности.
              Редкое тяжёлое событие не теряется среди частых мелких.
            </p>
          </div>
        </div>
      </div>

      <aside className="pane pane--side">
        <div className="card">
          <h3>Проблемы</h3>
          <div>
            {view.problems.length === 0 && <span className="secondary">не найдено</span>}
            {view.problems.map((problem) => (
              <div key={problem} className="field warn">
                △ {problem}
              </div>
            ))}
          </div>
        </div>

        <div className="card">
          <h3>Реестр рисков</h3>
          <div>
            <div className="field">
              <label>Всего / активных</label>
              <span className="mono">
                {view.riskSummary.total} / {view.riskSummary.active}
              </span>
            </div>
            <div className="field">
              <label>К эскалации</label>
              {view.riskSummary.escalate.map((id) => (
                <span key={id} className="chip">
                  {id}
                </span>
              ))}
            </div>
            <div className="field">
              <label>Закрытые сохранены</label>
              {view.riskSummary.closedRetained.map((id) => (
                <span key={id} className="chip">
                  {id}
                </span>
              ))}
            </div>
          </div>
        </div>
      </aside>
    </div>
  )
}

function Stat({ label, value, warn }: { label: string; value: number; warn?: boolean }) {
  return (
    <div>
      <div className="secondary">{label}</div>
      <div className={`mono${warn ? ' warn' : ''}`} style={{ fontSize: 24 }}>
        {value}
      </div>
    </div>
  )
}

/** Матрица 5×5 как навигация по критичности; класс клетки пришёл с сервера. */
function RiskMatrix({ view }: { view: Overview }) {
  return (
    <table className="matrix">
      <tbody>
        {[5, 4, 3, 2, 1].map((impact) => (
          <tr key={impact}>
            <th className="mono">{impact}</th>
            {[1, 2, 3, 4, 5].map((probability) => {
              const cell = view.riskMatrix.find(
                (c) => c.impact === impact && c.probability === probability,
              )
              return (
                <td
                  key={probability}
                  className={`cell ${CRITICALITY_CLASS[cell?.criticality ?? 'low']}`}
                  title={`вероятность ${probability}, последствия ${impact}: ${cell?.criticality}`}
                >
                  {cell?.risks.join(' ')}
                </td>
              )
            })}
          </tr>
        ))}
        <tr>
          <th />
          {[1, 2, 3, 4, 5].map((p) => (
            <th key={p} className="mono">
              {p}
            </th>
          ))}
        </tr>
      </tbody>
    </table>
  )
}
