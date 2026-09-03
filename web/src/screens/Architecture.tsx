// Экран «Архитектура» (ADR-052): четыре слоя Arcadia вкладками.
//
// OA — зачем (способности и акторы), SA — что делает система (функции с
// обменами) и цепочки сценариев, LA — кто делает логически, PA — по каким
// стыкам это идёт. Требованиями архитектура не управляет: истина требования
// живёт в реестре, здесь показывается, ЧЕМ оно выполнено.
//
// Клиент ничего не считает: распределение, покрытие цепочек требованиями,
// заполненность анкет стыков и разрывы приходят готовыми.
import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { ArchitectureView } from '../api/types'

const TABS: Array<[string, string]> = [
  ['oa', 'OA · зачем'],
  ['sa', 'SA · что делает'],
  ['chains', 'Цепочки'],
  ['la', 'LA · кто делает'],
  ['pa', 'PA · стыки'],
]

const TYPE_LABEL: Record<string, string> = {
  RF: 'радиолиния', power: 'питание', data: 'данные и команды', thermal: 'тепло',
  mech: 'механика', env: 'среда', hmi: 'человек-машина', org: 'организационный',
}

const ROLE_LABEL: Record<string, string> = {
  customer: 'заказчик', regulator: 'регулятор', operator: 'оператор', consumer: 'потребитель',
  supplier: 'поставщик', partner: 'партнёр', established: 'учреждаемый',
}

export function Architecture({ onGo }: { onGo?: (screen: string, kind?: string, target?: string) => void }) {
  const [view, setView] = useState<ArchitectureView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [tab, setTab] = useState('sa')

  useEffect(() => {
    api.architecture().then(setView).catch((e) => setError(String(e)))
  }, [])

  if (error) return <div className="empty">Ошибка обращения к API: {error}</div>
  if (!view) return <div className="empty">Загрузка архитектуры…</div>
  if (view.counts_functions === 0 && view.counts_capabilities === 0)
    return (
      <div className="empty">
        Архитектуры нет: возьмите полки «Каркас PBS», «Типовые интерфейсы» и «Архитектура (Arcadia)» —
        порядок важен, архитектура садится на заведённые узлы и стыки.
        {onGo && (
          <div style={{ marginTop: 8 }}>
            <button type="button" className="rr-assign" onClick={() => onGo('startpath')}>
              к полкам «Начала пути» →
            </button>
          </div>
        )}
      </div>
    )

  return (
    <div className="pane" style={{ display: 'flex', flexDirection: 'column', minHeight: 0 }}>
      <div className="rr-tbar">
        <h1 className="rr-h1">Архитектура</h1>
        <span className="secondary">
          способностей: {view.counts_capabilities} · функций: {view.counts_functions} ·
          цепочек: {view.counts_chains} · логических: {view.counts_logical} ·
          стыков: {view.counts_interfaces} · узлов: {view.counts_nodes}
        </span>
      </div>
      <div className="tabs" style={{ padding: '0 8px' }}>
        {TABS.map(([key, title]) => (
          <button key={key} type="button" className="tab" aria-selected={tab === key} onClick={() => setTab(key)}>
            {title}
          </button>
        ))}
      </div>
      {view.gaps.length > 0 && (
        <div className="warn" style={{ margin: '4px 8px', padding: 6 }}>
          Разрывы архитектуры: {view.gaps.length}
          <div style={{ marginTop: 4 }}>
            {view.gaps.slice(0, 6).map((g) => (
              <div key={g.what} style={{ fontSize: 12 }}>
                · {g.what}
                {onGo && g.place !== 'architecture' && (
                  <button type="button" className="rr-assign" style={{ marginLeft: 6 }} onClick={() => onGo(g.place)}>
                    к месту →
                  </button>
                )}
              </div>
            ))}
            {view.gaps.length > 6 && <div className="secondary">…и ещё {view.gaps.length - 6}</div>}
          </div>
        </div>
      )}
      <div className="workarea" style={{ overflow: 'auto' }}>
        {tab === 'oa' && (
          <>
            <table>
              <thead>
                <tr>
                  <th style={{ width: 70 }}>Код</th>
                  <th>Способность</th>
                  <th style={{ width: 280 }}>К чему привязана</th>
                </tr>
              </thead>
              <tbody>
                {view.oa.capabilities.map((c) => (
                  <tr key={c.id}>
                    <td className="mono">{c.code}</td>
                    <td>{c.name}</td>
                    <td>
                      {c.linked
                        ? c.traced_to.map((t) => (
                          <span key={t.ref} className="chip" title={t.name}>{t.ref}</span>
                        ))
                        : (
                          <span className="amber" title="подсказка полки; связь ставит инженер — служба не гадает">
                            △ не привязана · {c.hint || 'подсказки нет'}
                          </span>
                        )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <h3 style={{ padding: '8px 8px 0' }}>Акторы — предложение в стейкхолдеры</h3>
            <table>
              <thead>
                <tr>
                  <th style={{ width: 70 }}>ID</th>
                  <th style={{ width: 260 }}>Актор</th>
                  <th style={{ width: 120 }}>Роль</th>
                  <th>Операционные активности</th>
                </tr>
              </thead>
              <tbody>
                {view.oa.actors.map((a) => (
                  <tr key={a.id}>
                    <td className="mono">{a.id}</td>
                    <td>{a.name}</td>
                    <td>{ROLE_LABEL[a.role] ?? a.role}</td>
                    <td className="secondary">{a.note}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </>
        )}

        {tab === 'sa' && (
          <table>
            <thead>
              <tr>
                <th style={{ width: 52 }}>Код</th>
                <th style={{ width: 260 }}>Функция</th>
                <th style={{ width: 220 }}>На чём выполняется</th>
                <th>Обмены</th>
              </tr>
            </thead>
            <tbody>
              {view.sa.functions.map((f) => (
                <tr key={f.id}>
                  <td className="mono">{f.code}</td>
                  <td>{f.name}</td>
                  <td>
                    {f.allocated
                      ? f.allocated_to.map((a) => (
                        <div key={a.ref} style={{ fontSize: 12 }}>
                          <span className="mono">{a.ref}</span> {a.name}
                        </div>
                      ))
                      : <span className="bad">△ без узла</span>}
                  </td>
                  <td>
                    {f.exchanges.length === 0
                      ? <span className="secondary">—</span>
                      : f.exchanges.map((ex) => (
                        <div key={ex.code} style={{ fontSize: 12 }}>
                          {ex.name} → {ex.to_name || ex.to}
                          {ex.to_activity && <span className="secondary"> ({ex.to_activity})</span>}
                          {' · '}
                          <span className="chip" title={ex.interface_name}>{ex.interface_name || ex.interface}</span>
                        </div>
                      ))}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        {tab === 'chains' && (
          <table>
            <thead>
              <tr>
                <th style={{ width: 60 }}>Код</th>
                <th style={{ width: 240 }}>Цепочка</th>
                <th>Шаги</th>
                <th style={{ width: 200 }}>Требования</th>
              </tr>
            </thead>
            <tbody>
              {view.sa.chains.map((c) => (
                <tr key={c.id}>
                  <td className="mono">{c.code}</td>
                  <td>
                    {c.name}
                    {c.requirement_kinds.length > 0 && (
                      <div className="secondary" style={{ fontSize: 11 }}>{c.requirement_kinds.join('; ')}</div>
                    )}
                  </td>
                  <td style={{ fontSize: 12 }}>
                    {c.steps.map((s, n) => (
                      <span key={s.ref}>
                        {n > 0 && ' → '}
                        <span title={s.name}>{s.code || s.ref}</span>
                      </span>
                    ))}
                    {c.ack.length > 0 && (
                      <div className="secondary">обратный ход: {c.ack.map((a) => a.name).join(', ')}</div>
                    )}
                  </td>
                  <td>
                    {c.has_requirement
                      ? c.requirements.map((r) => <span key={r} className="chip">{r}</span>)
                      : (
                        <span className="bad" title="цепочка без сценарного требования — рисунок, за который никто не отвечает">
                          △ нет требования
                        </span>
                      )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        {tab === 'la' && (
          <table>
            <thead>
              <tr>
                <th style={{ width: 110 }}>Код</th>
                <th style={{ width: 240 }}>Логический компонент</th>
                <th>Функции</th>
                <th style={{ width: 260 }}>Развёрнут на узлы</th>
              </tr>
            </thead>
            <tbody>
              {view.logical_components.map((lc) => (
                <tr key={lc.id}>
                  <td className="mono">{lc.code}</td>
                  <td>{lc.name}</td>
                  <td style={{ fontSize: 12 }}>{lc.functions.map((f) => f.name).join(' · ')}</td>
                  <td style={{ fontSize: 12 }}>
                    {lc.deployed_to.map((d) => (
                      <div key={d.ref}><span className="mono">{d.ref}</span> {d.name}</div>
                    ))}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        {tab === 'pa' && (
          <table>
            <thead>
              <tr>
                <th style={{ width: 110 }}>Код</th>
                <th style={{ width: 220 }}>Стык</th>
                <th style={{ width: 120 }}>Тип</th>
                <th style={{ width: 220 }}>Стороны</th>
                <th style={{ width: 90 }}>Анкета</th>
                <th>Обмены и требования</th>
              </tr>
            </thead>
            <tbody>
              {view.interfaces.map((i) => (
                <tr key={i.id}>
                  <td className="mono">{i.code}</td>
                  <td>{i.name}</td>
                  <td>{TYPE_LABEL[i.type] ?? i.type}</td>
                  <td className="secondary">{i.sides}</td>
                  <td>
                    {i.filled === i.fields
                      ? <span>{i.filled}/{i.fields}</span>
                      : (
                        <span className="amber" title="поля анкеты стыка заполняются в «Запросах данных»">
                          {i.filled}/{i.fields}
                        </span>
                      )}
                  </td>
                  <td style={{ fontSize: 12 }}>
                    {i.exchanges.map((ex) => <span key={ex.code} className="chip" title={`от ${ex.from}`}>{ex.name}</span>)}
                    {i.requirements.map((r) => <span key={r} className="chip">{r}</span>)}
                    {i.exchanges.length === 0 && i.requirements.length === 0 && (
                      <span className="secondary">ни обменов, ни требований</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  )
}
