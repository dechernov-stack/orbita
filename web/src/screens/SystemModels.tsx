// Экран «Модели системы» (ADR-050): модель — ответ на инженерный вопрос, а не
// файл. Строка показывает вопрос, чем считается сегодня (расчёт · прокси ·
// внешний · не построена), точку, к которой ответ обязан быть, и сам ответ —
// выход С ДАТОЙ. Прокси помечен на выходе: непомеченный прокси был бы витриной.
//
// Разрывы ведут В РАЗНЫЕ МЕСТА: «модель не дала ответа» — сюда, «вход не
// задан» — в анкету узла, потому что чинится там. Клиент ничего не считает:
// состояние ответа, разрывы и связи «кормит» приходят готовыми.
import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { SystemModelInput, SystemModelsView } from '../api/types'

const STATUS_LABEL: Record<string, string> = {
  computed: 'расчёт',
  proxy: 'прокси',
  external: 'внешний инструмент',
  not_built: 'не построена',
}

function Inputs({ inputs, onGo }: { inputs: SystemModelInput[]; onGo?: (screen: string) => void }) {
  if (inputs.length === 0) return <span className="secondary">входы не описаны</span>
  return (
    <>
      {inputs.map((i, n) => (
        <div key={`${i.node ?? i.model ?? i.hint ?? n}-${n}`} style={{ fontSize: 12 }}>
          {i.node && <span className="mono">{i.node}</span>} {i.node_name}
          {i.param && (
            <>
              {' · '}
              <span className="mono">{i.param}</span>
              {i.filled === false && (
                <button
                  type="button"
                  className="rr-assign"
                  style={{ marginLeft: 6 }}
                  title="параметр узла не задан — чинится в анкете характеристик"
                  onClick={() => onGo?.('datarequests')}
                >
                  вход не задан → к анкете
                </button>
              )}
            </>
          )}
          {i.model && <span className="chip">от модели {i.model}</span>}
          {i.interface && <span className="mono"> {i.interface}</span>}
          {!i.node && !i.model && i.hint && <span className="secondary">{i.hint}</span>}
        </div>
      ))}
    </>
  )
}

export function SystemModels({ onGo }: { onGo?: (screen: string, kind?: string, target?: string) => void }) {
  const [view, setView] = useState<SystemModelsView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [open, setOpen] = useState<ReadonlySet<string>>(new Set())

  useEffect(() => {
    api.systemModels().then(setView).catch((e) => setError(String(e)))
  }, [])

  if (error) return <div className="empty">Ошибка обращения к API: {error}</div>
  if (!view) return <div className="empty">Загрузка моделей…</div>
  if (view.models.length === 0)
    return (
      <div className="empty">
        Записей моделей нет: возьмите набор «Модели системы» с полки — он заводит записи со
        статусом «не построена», и точка спрашивает с них ответ, а не файл.
      </div>
    )

  const toggle = (id: string) =>
    setOpen((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })

  return (
    <div className="pane" style={{ display: 'flex', flexDirection: 'column', minHeight: 0 }}>
      <div className="rr-tbar">
        <h1 className="rr-h1">Модели системы</h1>
        <span className="secondary">
          записей: {view.total} · дали ответ: {view.answered}
          {view.gate ? ` · ближайшая точка: ${view.gate}` : ''}
        </span>
      </div>
      <div className="workarea" style={{ overflow: 'auto' }}>
        <table>
          <thead>
            <tr>
              <th style={{ width: 52 }}>Код</th>
              <th style={{ width: 200 }}>Модель</th>
              <th>Вопрос</th>
              <th style={{ width: 120 }}>Чем считается</th>
              <th style={{ width: 60 }}>К точке</th>
              <th style={{ width: 120 }}>Ответ</th>
            </tr>
          </thead>
          <tbody>
            {view.models.map((m) => (
              <>
                <tr
                  key={m.id}
                  className="pbs-row"
                  tabIndex={0}
                  onClick={() => toggle(m.id)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault()
                      toggle(m.id)
                    }
                  }}
                >
                  <td className="mono">{m.code}</td>
                  <td>{m.name}</td>
                  <td className="secondary">{m.question}</td>
                  <td>
                    <span className="chip" title={m.tool}>{STATUS_LABEL[m.status] ?? m.status}</span>
                  </td>
                  <td className="mono">{m.due_gate}</td>
                  <td>
                    {m.answered ? (
                      <span>дан{m.proxy_answer ? ' · прокси' : ''}</span>
                    ) : (
                      <span className="amber" title="ответ — это выход с датой; «файл есть» ответом не считается">
                        △ ответа нет
                      </span>
                    )}
                  </td>
                </tr>
                {open.has(m.id) && (
                  <tr key={`${m.id}-x`} className="rr-expand">
                    <td colSpan={6}>
                      <div className="rr-xgrid">
                        <div>
                          <div className="rr-xk">Входы</div>
                          <div className="rr-xv"><Inputs inputs={m.inputs} onGo={onGo} /></div>
                        </div>
                        <div>
                          <div className="rr-xk">Выходы</div>
                          <div className="rr-xv">
                            {m.outputs.length === 0
                              ? <span className="secondary">ответа нет — выходов не записано</span>
                              : m.outputs.map((o) => (
                                <div key={o.name} style={{ fontSize: 12 }}>
                                  {o.name}
                                  {o.proxy && <span className="chip">прокси</span>}
                                  {o.at && <span className="secondary"> · {o.at}</span>}
                                  {o.note && <span className="secondary"> · {o.note}</span>}
                                </div>
                              ))}
                          </div>
                        </div>
                        <div>
                          <div className="rr-xk">Кормит</div>
                          <div className="rr-xv">
                            {m.feeds.length === 0
                              ? <span className="secondary">— пусто</span>
                              : m.feeds.map((f) => <span key={f} className="chip">{f}</span>)}
                          </div>
                          {m.gaps.length > 0 && (
                            <>
                              <div className="rr-xk" style={{ marginTop: 8 }}>Разрывы</div>
                              <div className="rr-xv">
                                {m.gaps.map((g) => (
                                  <div key={`${g.code}:${g.what}`}>
                                    <span className="bad">{g.what}</span>
                                    {onGo && g.place !== 'models' && (
                                      <button type="button" className="rr-assign" style={{ marginLeft: 6 }} onClick={() => onGo(g.place)}>
                                        к месту →
                                      </button>
                                    )}
                                  </div>
                                ))}
                              </div>
                            </>
                          )}
                        </div>
                      </div>
                      {m.parts.length > 0 && (
                        <table style={{ marginTop: 8 }}>
                          <thead>
                            <tr>
                              <th style={{ width: 52 }}>Часть</th>
                              <th style={{ width: 200 }}>Участок</th>
                              <th>Стык</th>
                              <th style={{ width: 120 }}>Чем считается</th>
                              <th style={{ width: 60 }}>К точке</th>
                              <th style={{ width: 100 }}>Ответ</th>
                            </tr>
                          </thead>
                          <tbody>
                            {m.parts.map((p) => (
                              <tr key={p.code}>
                                <td className="mono">{p.code}</td>
                                <td>{p.name}<div className="secondary">{p.question}</div></td>
                                <td className="secondary">
                                  {p.interface
                                    ? <><span className="mono">{p.interface}</span> {p.interface_name}</>
                                    : <span title="интерфейс дерева состава для этого участка ещё не заведён">{p.interface_hint} · стык не заведён</span>}
                                </td>
                                <td><span className="chip">{STATUS_LABEL[p.status] ?? p.status}</span></td>
                                <td className="mono">{p.due_gate}</td>
                                <td>{p.answered ? 'дан' : <span className="amber">△ ответа нет</span>}</td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      )}
                    </td>
                  </tr>
                )}
              </>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
