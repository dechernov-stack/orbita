// Экран 2 — сервисы и профили QoS по классам потребителей (Ш2 мастера)
// и их ввод руками (шаг 15).
//
// Непокрытый класс определяет сервер, сверяя профили с классами карты спроса
// (Р9: классы не усредняются). Клиент показывает вывод, а не выводит его.
//
// Подписи классов приходят с сервера одной таблицей: собственный словарь
// на экране расходился бы с соседними (шаг 15 §2, дефект 2).
import { useCallback, useEffect, useState } from 'react'
import { api } from '../api/client'
import type { ServiceRow } from '../api/types'
import { ObjectEditor } from '../ui/ObjectEditor'
import { StatusDot } from '../ui/parts'
import { useSession } from '../ui/session'
import { SortTh, useSort } from '../ui/sort'

export function Services() {
  const { label } = useSession()
  const [rows, setRows] = useState<ServiceRow[] | null>(null)
  const [selected, setSelected] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  // Правка — отдельное действие: выбор строки показывает карточку сервиса,
  // а не подменяет её формой. Замечание о непокрытом классе нужно видеть
  // до того, как начал править, а не вместо этого.
  const [editing, setEditing] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const reload = useCallback(
    () =>
      api
        .services()
        .then((next) => {
          setRows(next)
          setError(null)
        })
        .catch((e) => setError(String(e))),
    [],
  )

  useEffect(() => {
    void reload()
  }, [reload])


  // Хуки — ДО ранних возвратов (React #310): сортировка считается и на
  // пустом списке, а «Загрузка…» отдаётся ниже
  // Сортировка заголовком (§2.4): классы и требования — числом позиций
  const { sorted, sort, toggle } = useSort(rows ?? [], {
    id: (r) => r.id,
    name: (r) => r.name,
    classes: (r) => r.profiles.length,
    requirements: (r) => r.requirements.length,
    status: (r) => r.status,
  })

  if (error) return <div className="empty">Ошибка обращения к API: {error}</div>
  if (!rows) return <div className="empty">Загрузка…</div>

  // Без выбора показывается первый сервис: экран открывают, чтобы увидеть
  // покрытие классов, и пустая панель прячет ровно это.
  const service = rows.find((r) => r.id === selected) ?? rows[0]

  return (
    <div className="split">
      <div className="pane">
        <div className="pane__tools">
          <button
            type="button"
            className="tab tab--primary"
            onClick={() => {
              setCreating(true)
              setEditing(true)
              setSelected(null)
            }}
          >
            + Добавить сервис
          </button>
        </div>

        {rows.length === 0 && (
          <div className="empty">
            Сервисов пока нет. Сервис — это то, чем нужда реализуется: заведите его
            и укажите нужду в traces_up.
          </div>
        )}

        <table>
          <thead>
            <tr>
              <SortTh label="ID" sortKey="id" sort={sort} onToggle={toggle} width={100} />
              <SortTh label="Сервис" sortKey="name" sort={sort} onToggle={toggle} />
              <SortTh label="Классы" sortKey="classes" sort={sort} onToggle={toggle} width={180} />
              <SortTh label="Требований" sortKey="requirements" sort={sort} onToggle={toggle} width={160} />
              <SortTh label="Статус" sortKey="status" sort={sort} onToggle={toggle} width={90} />
            </tr>
          </thead>
          <tbody>
            {sorted.map((row) => (
              <tr
                key={row.id}
                aria-selected={row.id === service?.id}
                onClick={() => {
                  setSelected(row.id)
                  setCreating(false)
                  setEditing(false)
                }}
              >
                <td>
                  <span className="id">{row.id}</span>
                </td>
                <td className="wrap">{row.name}</td>
                <td>
                  {['A_prime', 'B_prime', 'C_prime'].map((klass) => {
                    const has = row.profiles.some((p) => p.consumerClass === klass)
                    return (
                      <span
                        key={klass}
                        className="chip"
                        style={has ? { background: '#eef4ff', borderColor: 'var(--accent)' } : undefined}
                        title={has ? 'профиль задан' : 'профиля нет'}
                      >
                        {klass.replace('_prime', '′')}
                      </span>
                    )
                  })}
                </td>
                <td className="mono">{row.requirements.length}</td>
                <td>
                  <StatusDot status={row.status} />
                  <span className="secondary">{label('lifecycle', row.status)}</span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <aside className="pane pane--side">
        {creating || editing ? (
          <ObjectEditor
            kind="service"
            schemaName="core/service"
            title="сервис"
            id={creating ? null : selected}
            onSaved={(id) => {
              setCreating(false)
              setSelected(id)
              void reload()
            }}
            onCancelled={() => {
              setSelected(null)
              setEditing(false)
              void reload()
            }}
          />
        ) : service ? (
          <div>
            <div className="pane__tools" style={{ padding: '0 0 8px' }}>
              <button type="button" className="tab" onClick={() => setEditing(true)}>
                Править сервис
              </button>
            </div>
            {service.uncoveredClasses.map((klass) => (
              <div key={klass} className="amber field">
                △ класс {label('consumer_class', klass)} присутствует в карте спроса, профиль не задан
              </div>
            ))}
            {service.profiles.map((profile) => (
              <div key={profile.consumerClass} className="card">
                <h3>{label('consumer_class', profile.consumerClass)}</h3>
                <div>
                  <table>
                    <thead>
                      <tr>
                        <th>Показатель</th>
                        <th style={{ width: 90, textAlign: 'right' }}>Целевое</th>
                        <th style={{ width: 60 }}>Ед.</th>
                      </tr>
                    </thead>
                    <tbody>
                      {profile.moe.map((moe) => (
                        <tr key={moe.id}>
                          <td className="wrap">{label('moe_name', moe.name)}</td>
                          <td className="num">{moe.target ?? '—'}</td>
                          <td className="mono">{moe.unit ?? '—'}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            ))}
            <div className="card">
              <h3>Трассировка</h3>
              <div>
                <div className="field">
                  <label>Из нужд</label>
                  {service.needs.map((n) => (
                    <span key={n} className="chip">
                      {n}
                    </span>
                  ))}
                </div>
                <div className="field">
                  <label>В требования</label>
                  {service.requirements.map((r) => (
                    <span key={r} className="chip">
                      {r}
                    </span>
                  ))}
                </div>
              </div>
            </div>
          </div>
        ) : (
          <div className="secondary">Выберите сервис для правки или добавьте новый.</div>
        )}
      </aside>
    </div>
  )
}
