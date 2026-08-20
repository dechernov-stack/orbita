// Экран 2 — сервисы и профили QoS по классам потребителей (Ш2 мастера).
//
// Непокрытый класс определяет сервер, сверяя профили с классами карты спроса
// (Р9: классы не усредняются). Клиент показывает вывод, а не выводит его.
import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { ServiceRow } from '../api/types'
import { StatusDot } from '../ui/parts'

const CLASS_LABEL: Record<string, string> = {
  A_prime: "A′ односторонний",
  B_prime: "B′ с подтверждением",
  C_prime: "C′ оперативное управление",
}

export function Services() {
  const [rows, setRows] = useState<ServiceRow[] | null>(null)
  const [selected, setSelected] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api.services().then(setRows).catch((e) => setError(String(e)))
  }, [])

  if (error) return <div className="empty">Ошибка обращения к API: {error}</div>
  if (!rows) return <div className="empty">Загрузка…</div>

  const service = rows.find((r) => r.id === selected) ?? rows[0]

  return (
    <div className="split">
      <div className="pane">
        <table>
          <thead>
            <tr>
              <th style={{ width: 100 }}>ID</th>
              <th>Сервис</th>
              <th style={{ width: 180 }}>Классы</th>
              <th style={{ width: 160 }}>Требований</th>
              <th style={{ width: 90 }}>Статус</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr
                key={row.id}
                aria-selected={row.id === service?.id}
                onClick={() => setSelected(row.id)}
              >
                <td>
                  <span className="id">{row.id}</span>
                </td>
                <td>
                  <span className="truncate">{row.name}</span>
                </td>
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
                  <span className="secondary">{row.status}</span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <aside className="pane pane--side">
        {service && (
          <div>
            <h2 style={{ fontSize: 15, margin: '0 0 8px' }}>{service.name}</h2>
            {service.uncoveredClasses.map((klass) => (
              <div key={klass} className="amber field">
                △ класс {CLASS_LABEL[klass] ?? klass} присутствует в карте спроса, профиль не задан
              </div>
            ))}
            {service.profiles.map((profile) => (
              <div key={profile.consumerClass} className="card">
                <h3>{CLASS_LABEL[profile.consumerClass] ?? profile.consumerClass}</h3>
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
                          <td>
                            <span className="truncate">{moe.name}</span>
                          </td>
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
        )}
      </aside>
    </div>
  )
}
