// Реестр рисков (макета нет — собран по образцу экрана 3: список слева,
// карточка справа, матрица 5×5 как навигация по критичности).
//
// Критичность и раскраска приходят с сервера. Считать их здесь означало бы
// завести вторую матрицу — и однажды риск оказался бы красным на одном экране
// и жёлтым на другом (STEP-7-9, ловушка 2).
import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { RiskRegisterView } from '../api/types'
import { SortTh, useSort } from '../ui/sort'

const CRITICALITY_CLASS: Record<string, string> = {
  low: 'cell--low',
  medium: 'cell--medium',
  high: 'cell--high',
}

type Risk = Record<string, unknown>

const str = (risk: Risk, key: string) => String(risk[key] ?? '')
const num = (risk: Risk, key: string) => Number(risk[key] ?? 0)

export function Risks() {
  const [view, setView] = useState<RiskRegisterView | null>(null)
  const [selected, setSelected] = useState<string | null>(null)
  const [filter, setFilter] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api.risks().then(setView).catch((e) => setError(String(e)))
  }, [])

  if (error) return <div className="empty">Ошибка обращения к API: {error}</div>
  if (!view) return <div className="empty">Загрузка…</div>

  const criticalityOf = (id: string) =>
    view.matrix.find((c) => c.risks.includes(id))?.criticality ?? 'low'

  const rows = filter ? view.risks.filter((r) => criticalityOf(str(r, 'id')) === filter) : view.risks
  // Сортировка заголовком (§2.4): вероятность, влияние и критичность — числами
  const РАНГ: Record<string, number> = { low: 1, medium: 2, high: 3, extreme: 4 }
  const { sorted, sort, toggle } = useSort(rows, {
    id: (r) => str(r, 'id'),
    statement: (r) => str(r, 'statement'),
    category: (r) => str(r, 'category'),
    p: (r) => num(r, 'probability') ?? 0,
    i: (r) => num(r, 'impact') ?? 0,
    severity: (r) => РАНГ[criticalityOf(str(r, 'id'))] ?? 0,
    state: (r) => str(r, 'status'),
  })
  const risk = view.risks.find((r) => str(r, 'id') === selected)

  return (
    <div className="split">
      <div className="pane">
        <div style={{ padding: '8px 8px 0' }}>
          {['high', 'medium', 'low'].map((level) => (
            <button
              key={level}
              className="tab"
              aria-selected={filter === level}
              onClick={() => setFilter(filter === level ? null : level)}
            >
              {level}
            </button>
          ))}
        </div>
        <table>
          <thead>
            <tr>
              <SortTh label="ID" sortKey="id" sort={sort} onToggle={toggle} width={100} />
              <SortTh label="Формулировка" sortKey="statement" sort={sort} onToggle={toggle} />
              <SortTh label="Категория" sortKey="category" sort={sort} onToggle={toggle} width={110} />
              <SortTh label="P" sortKey="p" sort={sort} onToggle={toggle} width={60} />
              <SortTh label="I" sortKey="i" sort={sort} onToggle={toggle} width={60} />
              <SortTh label="Критичность" sortKey="severity" sort={sort} onToggle={toggle} width={100} />
              <SortTh label="Состояние" sortKey="state" sort={sort} onToggle={toggle} width={90} />
            </tr>
          </thead>
          <tbody>
            {sorted.map((r) => (
              <tr
                key={str(r, 'id')}
                aria-selected={str(r, 'id') === selected}
                onClick={() => setSelected(str(r, 'id'))}
              >
                <td>
                  <span className="id">{str(r, 'id')}</span>
                </td>
                <td>
                  <span className="truncate">{str(r, 'statement')}</span>
                </td>
                <td className="secondary">{str(r, 'category')}</td>
                <td className="num">{num(r, 'probability')}</td>
                <td className="num">{num(r, 'impact')}</td>
                <td>
                  <span className={`chip ${CRITICALITY_CLASS[criticalityOf(str(r, 'id'))]}`}>
                    {criticalityOf(str(r, 'id'))}
                  </span>
                </td>
                <td className="secondary">{str(r, 'status')}</td>
              </tr>
            ))}
          </tbody>
        </table>

        <div style={{ padding: 16 }}>
          <h3 style={{ fontSize: 13 }}>Матрица критичности</h3>
          <table className="matrix">
            <tbody>
              {[5, 4, 3, 2, 1].map((impact) => (
                <tr key={impact}>
                  <th className="mono">{impact}</th>
                  {[1, 2, 3, 4, 5].map((probability) => {
                    const cell = view.matrix.find(
                      (c) => c.impact === impact && c.probability === probability,
                    )
                    return (
                      <td
                        key={probability}
                        className={`cell ${CRITICALITY_CLASS[cell?.criticality ?? 'low']}`}
                        onClick={() => setFilter(cell?.criticality ?? null)}
                        title={`вероятность ${probability}, последствия ${impact}`}
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
        </div>
      </div>

      <aside className="pane pane--side">
        {risk ? (
          <div>
            <h2 style={{ fontSize: 15, margin: '0 0 4px' }}>
              <span className="id">{str(risk, 'id')}</span>
            </h2>
            <p style={{ marginTop: 0 }}>{str(risk, 'statement')}</p>
            <div className="card">
              <h3>Оценка</h3>
              <div>
                <div className="field">
                  <label>Вероятность / последствия</label>
                  <span className="mono">
                    {num(risk, 'probability')} / {num(risk, 'impact')}
                  </span>
                </div>
                <div className="field">
                  <label>Критичность</label>
                  <span className={`chip ${CRITICALITY_CLASS[criticalityOf(str(risk, 'id'))]}`}>
                    {criticalityOf(str(risk, 'id'))}
                  </span>
                </div>
                <div className="field">
                  <label>Владелец</label>
                  {str(risk, 'owner')}
                </div>
              </div>
            </div>
            <div className="card">
              <h3>Реагирование</h3>
              <div>
                <div className="field">
                  <label>Стратегия</label>
                  {str(risk, 'strategy') || '—'}
                </div>
                <div className="field">
                  <label>Срок</label>
                  <span className="mono">{str(risk, 'due') || '—'}</span>
                </div>
                <div className="field">
                  <label>Мероприятия</label>
                  {Array.isArray(risk.actions) && risk.actions.length > 0
                    ? (risk.actions as string[]).map((a) => <div key={a}>· {a}</div>)
                    : '—'}
                </div>
              </div>
            </div>
            <div className="card">
              <h3>Затронуто</h3>
              <div>
                {Array.isArray(risk.affects) &&
                  (risk.affects as string[]).map((a) => (
                    <span key={a} className="chip">
                      {a}
                    </span>
                  ))}
              </div>
            </div>
          </div>
        ) : (
          <div>
            <div className="card">
              <h3>Сводка</h3>
              <div>
                <div className="field">
                  <label>Всего / активных</label>
                  <span className="mono">
                    {view.summary.total} / {view.summary.active}
                  </span>
                </div>
                <div className="field">
                  <label>К эскалации</label>
                  {view.summary.escalate.map((id) => (
                    <span key={id} className="chip">
                      {id}
                    </span>
                  ))}
                </div>
                <div className="field">
                  <label>Закрытые сохранены</label>
                  {view.summary.closedRetained.map((id) => (
                    <span key={id} className="chip">
                      {id}
                    </span>
                  ))}
                </div>
              </div>
            </div>
            <div className="secondary">Выберите риск или клетку матрицы</div>
          </div>
        )}
      </aside>
    </div>
  )
}
