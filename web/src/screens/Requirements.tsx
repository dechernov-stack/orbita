// Экран 3 — требования: дерево, условие, свёртка, V&V.
// Экран 3б — карточка требования в правой панели.
//
// Все числа и строки берутся из /views/requirement-tree и /views/requirements/{id}
// как есть. Клиент не считает ни глубину отступа, ни свёртку, ни критерий успеха.
import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { RequirementCard, RequirementRow, RequirementTreeView } from '../api/types'
import { BudgetGauge, Condition, StatusDot, Verification } from '../ui/parts'

export function Requirements() {
  const [tree, setTree] = useState<RequirementTreeView | null>(null)
  const [selected, setSelected] = useState<string | null>(null)
  const [card, setCard] = useState<RequirementCard | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [onlyViolated, setOnlyViolated] = useState(false)

  useEffect(() => {
    api.requirementTree().then(setTree).catch((e) => setError(String(e)))
  }, [])

  useEffect(() => {
    if (!selected) return setCard(null)
    api.requirementCard(selected).then(setCard).catch((e) => setError(String(e)))
  }, [selected])

  if (error) return <div className="empty">Ошибка обращения к API: {error}</div>
  if (!tree) return <div className="empty">Загрузка…</div>

  // Порядок обхода задан деревом с сервера: roots и children — оттуда же.
  const ordered: RequirementRow[] = []
  const byId = new Map(tree.rows.map((r) => [r.id, r]))
  const walk = (id: string) => {
    const row = byId.get(id)
    if (!row) return
    ordered.push(row)
    ;(tree.children[id] ?? []).forEach(walk)
  }
  tree.roots.forEach(walk)
  const rows = onlyViolated ? ordered.filter((r) => r.budgetOverrun) : ordered

  return (
    <div className="split">
      <div className="pane">
        <div style={{ padding: '8px 8px 0' }}>
          <button
            className="tab"
            aria-selected={onlyViolated}
            onClick={() => setOnlyViolated((v) => !v)}
          >
            Бюджет нарушен
          </button>
        </div>
        <table>
          <thead>
            {/* Ширины заданы всем колонкам, включая формулировку. При
                table-layout: fixed колонка без ширины получает лишь остаток —
                и когда слева появился мастер, остатка не осталось вовсе:
                формулировка схлопнулась до полусотни пикселей. */}
            <tr>
              <th style={{ width: 120 }}>ID</th>
              <th style={{ width: 240 }}>Требование</th>
              <th style={{ width: 130 }}>Условие</th>
              <th style={{ width: 200 }}>Свёртка</th>
              <th style={{ width: 190 }}>Метод V&amp;V</th>
              <th style={{ width: 80 }}>Статус</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr
                key={row.id}
                aria-selected={row.id === selected}
                onClick={() => setSelected(row.id)}
              >
                <td>
                  {/* отступ по уровню — depth пришёл с сервера */}
                  <span style={{ paddingLeft: row.depth * 16 }}>
                    <span className="twisty">{row.hasChildren ? '▸' : ''}</span>
                    <span className="id">{row.id}</span>
                  </span>
                </td>
                <td>
                  <span className="truncate">{row.statement}</span>
                </td>
                <td>
                  <Condition condition={row.condition} />
                </td>
                <td>
                  <BudgetGauge bar={row.budget} />
                </td>
                <td>
                  <Verification method={row.method} approach={row.approach} issues={row.planIssues} />
                </td>
                <td>
                  <StatusDot status={row.status} />
                  <span className="secondary">{row.status}</span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <aside className="pane pane--side">
        {card ? <Card card={card} /> : <div className="secondary">Выберите требование</div>}
      </aside>
    </div>
  )
}

/** Экран 3б: карточка требования — структурное условие и события верификации. */
function Card({ card }: { card: RequirementCard }) {
  return (
    <div>
      <h2 style={{ fontSize: 15, margin: '0 0 4px' }}>
        <span className="id">{card.row.id}</span>
      </h2>
      <p style={{ marginTop: 0 }}>{card.row.statement}</p>

      <div className="card">
        <h3>Условие</h3>
        <div>
          <div className="field">
            <label>Показатель</label>
            <span>{card.row.condition?.name ?? '—'}</span>
          </div>
          <div className="field">
            <label>Значение</label>
            <Condition condition={card.row.condition} />
          </div>
          <div className="field">
            <label>Единица</label>
            {/* код СИ хранится в модели, подпись — только для отображения */}
            <span className="mono">
              {card.row.condition?.unit ?? '—'}
              {card.row.condition?.unitLabel && (
                <span className="secondary"> · {card.row.condition.unitLabel}</span>
              )}
            </span>
          </div>
        </div>
      </div>

      <div className="card">
        <h3>Верификация</h3>
        <div>
          <div className="field">
            <label>Критерий успеха</label>
            <span className="secondary mono">{card.successCriterion ?? '—'}</span>
          </div>
          {card.events.length === 0 && <div className="amber">△ события верификации не запланированы</div>}
          {card.events.map((event) => (
            <div key={event.id} className="field">
              <label>
                <span className="id">{event.id}</span> · {event.kind} · {event.phase}
                {event.closes && <span className="chip"> закрывающее</span>}
              </label>
              <div>
                <span className="chip">{event.method}</span>{' '}
                {event.approach ? (
                  <span className="secondary">{event.approach}</span>
                ) : (
                  <span className="amber">△ не описано, как выполняется проверка</span>
                )}
              </div>
              {event.means && <div className="secondary">Чем: {event.means}</div>}
              {event.evidenceRef && (
                <div>
                  <span className="chip">{event.evidenceRef}</span>{' '}
                  {event.evidenceStale && <span className="warn chip">устарело</span>}
                </div>
              )}
              {event.issues.map((issue) => (
                <div key={issue} className="amber">
                  △ {issue}
                </div>
              ))}
            </div>
          ))}
        </div>
      </div>

      <div className="card">
        <h3>Трассировка</h3>
        <div>
          <div className="field">
            <label>Источники</label>
            {card.sources.map((s) => (
              <span key={s} className="chip">
                {s}
              </span>
            ))}
          </div>
          <div className="field">
            <label>Распределено на</label>
            {card.allocatedTo.map((a) => (
              <span key={a} className="chip">
                {a}
              </span>
            ))}
          </div>
        </div>
      </div>
    </div>
  )
}
