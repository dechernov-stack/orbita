// Экран 11 — спецификация элемента: работа «от компонента», а не от требования.
//
// Источник каждого требования и вид декомпозиции приходят с сервера: клиент не
// решает, производное требование или распределённое, — он рисует то, что решила
// модель (ADR-019).
import { useEffect, useState } from 'react'
import { api } from '../api/client'
import { edit } from '../api/edit'
import type { ComponentSpecification } from '../api/types'
import { BudgetGauge, Condition, StatusDot } from '../ui/parts'

export function ComponentSpec({
  componentId,
  onSelectRequirement,
}: {
  componentId: string
  /** Переход к требованию из строки спецификации (шаг 16 §3.3). */
  onSelectRequirement?: (id: string) => void
}) {
  const [spec, setSpec] = useState<ComponentSpecification | null>(null)
  const [error, setError] = useState<string | null>(null)
  type ParamRow = Awaited<ReturnType<typeof edit.listParams>>[number]
  const [params, setParams] = useState<ParamRow[]>([])
  const [paramError, setParamError] = useState<string | null>(null)
  const [draft, setDraft] = useState({ name: '', value: '', unit: '', formula: '', depId: '', depName: '' })

  const loadParams = () => {
    edit.listParams(componentId).then(setParams).catch((e) => setParamError(String(e)))
  }

  useEffect(() => {
    api.componentSpecification(componentId).then(setSpec).catch((e) => setError(String(e)))
    loadParams()
  }, [componentId])

  if (error) return <div className="empty">Ошибка обращения к API: {error}</div>
  if (!spec) return <div className="empty">Загрузка…</div>

  return (
    <div className="split">
      <div className="pane">
        <h2 style={{ fontSize: 15, margin: '12px 16px 8px' }}>
          Спецификация элемента <span className="id">{spec.componentId}</span>
        </h2>
        <table>
          <thead>
            <tr>
              <th style={{ width: 100 }}>ID</th>
              <th>Требование</th>
              <th style={{ width: 140 }}>Условие</th>
              <th style={{ width: 160 }}>Источник</th>
              <th style={{ width: 180 }}>V&amp;V</th>
              <th style={{ width: 90 }}>Статус</th>
            </tr>
          </thead>
          <tbody>
            {spec.rows.map((row) => (
              <tr
                key={row.id}
                onClick={() => onSelectRequirement?.(row.id)}
                style={onSelectRequirement ? { cursor: 'pointer' } : undefined}
              >
                <td>
                  <span className="id">{row.id}</span>
                </td>
                <td>
                  <span className="truncate">{row.statement}</span>
                </td>
                <td>
                  <Condition condition={row.condition} />
                </td>
                <td>
                  {row.source ? (
                    <span
                      className={`chip${row.derivationKind === 'derived' ? ' chip--derived' : ''}`}
                      title={row.derivationKind === 'derived' ? 'производное' : 'распределённое'}
                    >
                      {row.source}
                    </span>
                  ) : (
                    <span className="secondary">—</span>
                  )}
                  {row.derivationKind === 'derived' && (
                    <span className="secondary"> производное</span>
                  )}
                </td>
                <td>
                  <span className="secondary">{row.verificationState}</span>{' '}
                  <span className="mono">
                    {row.eventsDone} из {row.eventsTotal}
                  </span>
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
        <div className="card">
          <h3>Бюджеты</h3>
          <div>
            {Object.keys(spec.budgets).length === 0 && (
              <span className="secondary">свёрток на этом элементе нет</span>
            )}
            {Object.entries(spec.budgets).map(([name, bar]) => (
              <div key={name} className="field">
                <label>{name}</label>
                <BudgetGauge bar={bar} />
              </div>
            ))}
          </div>
        </div>
        <div className="card">
          <h3>Параметры</h3>
          <div>
            <p className="secondary">
              TZ-MOD-005: величина не существует без единицы; зависимость параметра —
              вход каскада stale. Неакцептованное ИИ в действующие не входит.
            </p>
            {paramError && <div className="warn">{paramError}</div>}
            {params.length > 0 && (
              <table>
                <thead>
                  <tr>
                    <th>Имя</th>
                    <th style={{ textAlign: 'right' }}>Значение</th>
                    <th>Ед.</th>
                    <th>Происх.</th>
                  </tr>
                </thead>
                <tbody>
                  {params.map((p) => (
                    <tr key={p.name} title={p.formula ? `формула: ${p.formula}` : undefined}>
                      <td className="mono">{p.name}{p.is_tpm ? ' · TPM' : ''}</td>
                      <td className="num">{p.value ?? '—'}</td>
                      <td className="mono">{p.unit}</td>
                      <td className="secondary">{p.source}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            <div className="field">
              <input placeholder="имя" value={draft.name}
                onChange={(e) => setDraft({ ...draft, name: e.target.value })} style={{ width: 90 }} />
              <input placeholder="знач." value={draft.value}
                onChange={(e) => setDraft({ ...draft, value: e.target.value })} style={{ width: 60 }} />
              <input placeholder="ед. СИ" value={draft.unit}
                onChange={(e) => setDraft({ ...draft, unit: e.target.value })} style={{ width: 60 }} />
              <button title="у характеристики обязаны быть имя и единица из справочника"
                type="button"
                className="tab"
                disabled={!draft.name || !draft.unit}
                onClick={() => {
                  setParamError(null)
                  edit
                    .putParam(componentId, draft.name, draft.value ? Number(draft.value) : null, draft.unit, draft.formula || undefined)
                    .then(() => { setDraft({ ...draft, name: '', value: '', formula: '' }); loadParams() })
                    .catch((e) => setParamError(String(e)))
                }}
              >
                Задать
              </button>
            </div>
            <div className="field">
              <input placeholder="зависит от: объект" value={draft.depId}
                onChange={(e) => setDraft({ ...draft, depId: e.target.value })} style={{ width: 110 }} />
              <input placeholder="параметр" value={draft.depName}
                onChange={(e) => setDraft({ ...draft, depName: e.target.value })} style={{ width: 90 }} />
              <button
                type="button"
                className="tab"
                disabled={!draft.name || !draft.depId || !draft.depName}
                onClick={() => {
                  setParamError(null)
                  edit
                    .addParamDependency(componentId, draft.name, draft.depId, draft.depName)
                    .then(() => setDraft({ ...draft, depId: '', depName: '' }))
                    .catch((e) => setParamError(String(e)))
                }}
                title="изменение источника пометит зависимый параметр устаревшим"
              >
                Связать
              </button>
            </div>
          </div>
        </div>
      </aside>
    </div>
  )
}
