// Ф-06: библиотека запрашивает данные, а не ждёт молча. Анкета характеристик
// носителя показывает, ЧТО нужно и в каком формате: имя поля, единица из
// справочника, обязательность, подсказка из глоссария. Три пути заполнения
// названы прямо на форме — рукой, типовым с полки, даташитом через разбор.
//
// Ни одного значения клиент не считает: заполненность, происхождение и
// счётчики приходят с сервера.
import { useEffect, useState } from 'react'
import { api } from '../api/client'
import { Num } from '../ui/Num'
import type { DataRequestsView } from '../api/types'

const ROLE_LABEL: Record<string, string> = {
  platform: 'Платформа',
  payload: 'Полезная нагрузка',
  terminal: 'Терминал потребителя',
  ground_station: 'Наземная станция',
}

export function DataRequests({ onGo }: { onGo?: (screen: string) => void }) {
  const [view, setView] = useState<DataRequestsView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [open, setOpen] = useState<string | null>(null)

  useEffect(() => {
    api.dataRequests().then(setView).catch((e) => setError(String(e)))
  }, [])

  if (error) return <div className="warn" style={{ padding: 8 }}>Запросы данных: {error}</div>
  if (!view || view.requests.length === 0) return null

  return (
    <div className="card">
      <h3>Библиотека запрашивает данные · не заполнено <Num v={view.missing_total} /></h3>
      <div>
        <p className="secondary" style={{ marginTop: 0 }}>
          Анкеты характеристик приходят с полки: поле знает свою единицу,
          обязательность и подсказку. Заполнить можно тремя путями — рукой,
          типовым компонентом с полки либо даташитом: разбор документа
          подставит значения с координатами блоков, а вы их сверите.
        </p>
        {view.requests.map((r) => {
          const opened = open === r.form
          return (
            <div key={r.form} style={{ padding: '4px 0', borderBottom: '1px solid var(--line, #2223)' }}>
              <div style={{ display: 'flex', gap: 8, alignItems: 'baseline', flexWrap: 'wrap' }}>
                <button className="rr-assign" onClick={() => setOpen(opened ? null : r.form)}
                  title={`${ROLE_LABEL[r.role] ?? r.role} · анкета ${r.form}: ` +
                    (opened ? 'свернуть' : 'показать поля')}>
                  {opened ? '▾' : '▸'} {r.name}
                </button>
                {r.missing > 0 ? (
                  <span className="amber" title="обязательные поля, которых система ещё не знает">
                    не заполнено {r.missing}
                  </span>
                ) : (
                  <span className="secondary">анкета закрыта</span>
                )}
                {r.holder && <span className="secondary mono" title="объект модели, который держит эти данные">{r.holder}</span>}
              </div>
              {opened && (
                <>
                  {r.note && <div className="secondary" style={{ padding: '2px 0' }}>{r.note}</div>}
                  <div style={{ overflowX: 'auto' }}>
                    <table className="rr-table">
                      <thead>
                        <tr>
                          <th>Характеристика</th>
                          <th title="единица из справочника — в ней система ждёт значение">Единица</th>
                          <th>Значение</th>
                          <th title="откуда пришло: из модели или из даташита с координатой блока">Источник</th>
                        </tr>
                      </thead>
                      <tbody>
                        {r.fields.map((f) => (
                          <tr key={f.key}>
                            <td title={f.hint}>
                              {f.name}
                              {f.required && <span className="amber" title="обязательное поле"> ·</span>}
                              {f.options && f.options.length > 0 && (
                                <div className="secondary mono">{f.options.join(' · ')}</div>
                              )}
                            </td>
                            <td className="mono">{f.unit ?? '—'}</td>
                            <td>
                              {f.filled
                                ? <span className="mono">{f.value}</span>
                                : <span className="amber" title="данные не заданы — это разрыв готовности">не заданы</span>}
                            </td>
                            <td className="secondary">
                              {f.from === 'model'
                                ? 'модель'
                                : f.from?.startsWith('harvest:')
                                  ? `даташит ${f.from.slice('harvest:'.length)}`
                                  : '—'}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                  <div className="toolbar" style={{ padding: '6px 0', gap: 6 }}>
                    <span className="secondary">три пути заполнения:</span>
                    <button className="rr-assign" onClick={() => onGo?.('spacecraft')}
                      title="ввести значения формой модели аппарата">рукой</button>
                    <button className="rr-assign" onClick={() => onGo?.('shelves')}
                      title="взять типовой компонент с полки библиотеки">типовым с полки</button>
                    <button className="rr-assign" onClick={() => onGo?.('docparse')}
                      title="приложить даташит: разбор подставит значения с координатами блоков">
                      даташитом
                    </button>
                  </div>
                </>
              )}
            </div>
          )
        })}
      </div>
    </div>
  )
}
