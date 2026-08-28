// Экран 4 — карта спроса (Ш2 мастера, TZ-USR-004).
//
// Слои задаются здесь, карта собирается на сервере. Ни веса ячеек, ни доля
// от максимума, ни пик «час × месяц» в клиенте не считаются: вторая
// нормировка разошлась бы с первой молча — обе показали бы число, просто
// разное (STEP-7-9, ловушка 2).
//
// Проекция равнопромежуточная, а сетка равноплощадная: у полюсов ячейка
// выглядит крупнее, чем весит. Поэтому рядом с картой стоит широтный профиль —
// в нём вес пояса виден числом, а не размером пятна.
import { useEffect, useState } from 'react'
import { api } from '../api/client'
import { MapView } from '../ui/MapView'
import { edit, type StoredSummary } from '../api/edit'
import type { DemandLayersRequest, DemandMapView, ReferenceScenarioRow } from '../api/types'

const CLASSES = ['A_prime', 'B_prime', 'C_prime']
const CLASS_LABEL: Record<string, string> = {
  A_prime: "A′",
  B_prime: "B′",
  C_prime: "C′",
}

type Population = DemandLayersRequest['population'][number]

const EMPTY: Population = {
  id: '',
  lat: 45,
  lon: 0,
  pop_density_per_km2: 40,
  terminals_per_capita: 0.02,
  msgs_per_terminal_day: 4,
  consumer_class: 'A_prime',
}

// Зашитого идентификатора карты больше нет (шаг 16 §3.2): по умолчанию
// берётся ПЕРВАЯ хранимая карта спроса, выбор — из хранимых.
export function Demand({ demandMapId }: { demandMapId?: string }) {
  const [library, setLibrary] = useState<ReferenceScenarioRow[]>([])
  const [populations, setPopulations] = useState<Population[]>([])
  const [scenarioIds, setScenarioIds] = useState<string[]>([])
  const [view, setView] = useState<DemandMapView | null>(null)
  const [draft, setDraft] = useState<Population>(EMPTY)
  const [selected, setSelected] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  /** Показывается хранимая карта, пока инженер не начал собирать свою. */
  const [editing, setEditing] = useState(false)
  const [storedMaps, setStoredMaps] = useState<StoredSummary[]>([])
  const [mapId, setMapId] = useState<string | undefined>(demandMapId)

  useEffect(() => {
    edit
      .list('demand_map')
      .then((rows) => {
        setStoredMaps(rows)
        if (!demandMapId && rows.length > 0) setMapId((cur) => cur ?? rows[0].id)
      })
      .catch((e) => setError(String(e)))
  }, [demandMapId])

  useEffect(() => {
    api.demandLibrary().then(setLibrary).catch((e) => setError(String(e)))
  }, [])

  useEffect(() => {
    setError(null)
    if (!editing) {
      if (!mapId) return
      // Хранимая карта (ADR-021): ячейки и веса из сохранённого документа,
      // на который ссылается сценарий, а не пересчитанные заново.
      api.demandStored(mapId).then(setView).catch((e) => setError(String(e)))
      return
    }
    api
      .demand({ population: populations, point_objects: [], scenario_ids: scenarioIds })
      .then(setView)
      .catch((e) => setError(String(e)))
  }, [mapId, editing, populations, scenarioIds])

  const addPopulation = () => {
    const id = draft.id.trim()
    if (!id) return
    setEditing(true)
    setPopulations((prev) => [...prev.filter((p) => p.id !== id), { ...draft, id }])
    setDraft({ ...EMPTY, id: '' })
  }

  const toggleScenario = (id: string) => {
    setEditing(true)
    setScenarioIds((prev) => (prev.includes(id) ? prev.filter((s) => s !== id) : [...prev, id]))
  }

  const contribution = view?.contributions.find((c) => c.id === selected)

  return (
    <div className="split">
      <div className="pane">
        <div style={{ padding: '8px 8px 0' }}>
          {editing ? (
            <button className="tab" onClick={() => setEditing(false)}>
              ← к хранимой карте
            </button>
          ) : (
            <select
              value={mapId ?? ''}
              onChange={(e) => setMapId(e.target.value)}
              title="карта из модели, на неё ссылается сценарий"
            >
              {storedMaps.map((m) => (
                <option key={m.id} value={m.id}>{m.id}</option>
              ))}
            </select>
          )}
          <span className="secondary"> Слои: </span>
          <span className="chip">население {populations.length}</span>
          {library.map((s) => (
            <button
              key={s.id}
              className="tab"
              aria-selected={scenarioIds.includes(s.id)}
              onClick={() => toggleScenario(s.id)}
              title={`${s.geography} · ${CLASS_LABEL[s.consumerClass] ?? s.consumerClass} · ${s.mobilityModel}`}
            >
              {s.name}
            </button>
          ))}
        </div>

        {error && <div className="warn" style={{ padding: 8 }}>Ошибка: {error}</div>}

        {view && view.cells.length === 0 ? (
          <div className="empty">
            Карта пуста: включите сценарий библиотеки или добавьте популяцию справа.
          </div>
        ) : (
          view && (
            <div style={{ padding: 12 }}>
              <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start' }}>
                {/* §6.3а: вьюер один на систему — превью спроса тем же
                    MapView, что ёмкость и запас; интенсивность — от сервера */}
                <div style={{ flex: 1 }}>
                  <MapView
                    height={300}
                    demandCells={view.cells.map((cell) => ({
                      id: cell.id,
                      latDeg: cell.latDeg,
                      lonDeg: cell.lonDeg,
                      intensity: cell.intensity,
                      tip: `${cell.id}: ${cell.msgsPerDay} сообщ./сут, вес ${cell.weight}`,
                    }))}
                  />
                </div>
                <div style={{ width: 180 }}>
                  <h3 style={{ fontSize: 13, margin: '0 0 4px' }}>Широтный профиль</h3>
                  <table>
                    <thead>
                      <tr>
                        <th>Пояс</th>
                        <th style={{ textAlign: 'right' }}>Вес спроса</th>
                      </tr>
                    </thead>
                    <tbody>
                      {view.latitudeProfile.map((band) => (
                        <tr key={band.bandDeg}>
                          <td className="mono">{band.bandDeg}°</td>
                          <td className="num">{band.weight}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
              <p className="secondary" style={{ marginBottom: 0 }}>
                Яркость ячейки — доля от максимума карты (посчитана сервером). Проекция
                равнопромежуточная: у полюсов ячейка выглядит крупнее, чем весит, — вес
                смотрите в профиле.
              </p>

              <h3 style={{ fontSize: 13 }}>Ячейки</h3>
              <table>
                <thead>
                  <tr>
                    <th style={{ width: 90 }}>Ячейка</th>
                    <th style={{ width: 70 }}>Широта</th>
                    <th style={{ width: 110 }}>Площадь, км²</th>
                    <th style={{ width: 130 }}>Сообщ./сут</th>
                    <th style={{ width: 110 }}>Вес</th>
                    <th>По классам</th>
                  </tr>
                </thead>
                <tbody>
                  {view.cells.map((cell) => (
                    <tr key={cell.id} onClick={() => setSelected(cell.id)}>
                      <td>
                        <span className="id">{cell.id}</span>
                      </td>
                      <td className="num">{cell.latDeg}</td>
                      <td className="num">{cell.areaKm2}</td>
                      <td className="num">{cell.msgsPerDay}</td>
                      <td className="num">{cell.weight}</td>
                      <td>
                        {Object.entries(cell.byClass).map(([klass, msgs]) => (
                          <span key={klass} className="chip">
                            {CLASS_LABEL[klass] ?? klass} {msgs}
                          </span>
                        ))}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )
        )}
      </div>

      <aside className="pane pane--side">
        {view && (
          <div className="card">
            <h3>Вклад в спрос</h3>
            <div>
              <div className="field">
                <label>Версия карты</label>
                <span className="mono">{view.version}</span>
              </div>
              <div className="field">
                <label>Всего сообщений в сутки</label>
                <span className="mono">{view.totalMsgsPerDay}</span>
              </div>
              <div className="field">
                <label>Пик, сообщ./с</label>
                <span className="mono">{view.peak.msgsPerS}</span>
                <div className="secondary">
                  {view.peak.profiled
                    ? `худший час ${view.peak.hour}, месяц ${view.peak.month}`
                    : 'профили активности не заданы — активность равномерная'}
                </div>
              </div>
              <div className="field">
                <label>Терминалов по классам</label>
                {Object.entries(view.terminalsByClass).map(([klass, count]) => (
                  <span key={klass} className="chip">
                    {CLASS_LABEL[klass] ?? klass} {count}
                  </span>
                ))}
              </div>
              {contribution && (
                <div className="field">
                  <label>Доля ячейки {contribution.id}</label>
                  <span className="mono">{contribution.share}</span>
                </div>
              )}
            </div>
          </div>
        )}

        {view && view.issues.length > 0 && (
          <div className="card">
            <h3>Замечания</h3>
            <div>
              {view.issues.map((issue) => (
                <div key={issue} className="amber">
                  △ {issue}
                </div>
              ))}
            </div>
          </div>
        )}

        <div className="card">
          <h3>Добавить популяцию</h3>
          <div>
            <div className="field">
              <label>Ячейка</label>
              <input
                value={draft.id}
                onChange={(e) => setDraft({ ...draft, id: e.target.value })}
                placeholder="например, p45"
              />
            </div>
            <div className="field">
              <label>Широта, °</label>
              <input
                type="number"
                value={draft.lat}
                onChange={(e) => setDraft({ ...draft, lat: Number(e.target.value) })}
              />
            </div>
            <div className="field">
              <label>Долгота, °</label>
              <input
                type="number"
                value={draft.lon}
                onChange={(e) => setDraft({ ...draft, lon: Number(e.target.value) })}
              />
            </div>
            <div className="field">
              <label>Плотность, чел./км²</label>
              <input
                type="number"
                value={draft.pop_density_per_km2}
                onChange={(e) =>
                  setDraft({ ...draft, pop_density_per_km2: Number(e.target.value) })
                }
              />
            </div>
            <div className="field">
              <label>Терминалов на жителя</label>
              <input
                type="number"
                step="0.001"
                value={draft.terminals_per_capita}
                onChange={(e) =>
                  setDraft({ ...draft, terminals_per_capita: Number(e.target.value) })
                }
              />
            </div>
            <div className="field">
              <label>Сообщений на терминал в сутки</label>
              <input
                type="number"
                value={draft.msgs_per_terminal_day}
                onChange={(e) =>
                  setDraft({ ...draft, msgs_per_terminal_day: Number(e.target.value) })
                }
              />
            </div>
            <div className="field">
              <label>Класс терминала</label>
              <div className="tabs">
                {CLASSES.map((klass) => (
                  <button
                    key={klass}
                    className="tab"
                    aria-selected={draft.consumer_class === klass}
                    onClick={() => setDraft({ ...draft, consumer_class: klass })}
                  >
                    {CLASS_LABEL[klass]}
                  </button>
                ))}
              </div>
            </div>
            <button className="tab" onClick={addPopulation} disabled={!draft.id.trim()}>
              Добавить популяцию
            </button>
          </div>
        </div>

        {populations.length > 0 && (
          <div className="card">
            <h3>Популяции</h3>
            <div>
              {populations.map((p) => (
                <div key={p.id} className="field">
                  <label>{p.id}</label>
                  <span className="chip">{CLASS_LABEL[p.consumer_class] ?? p.consumer_class}</span>
                  <button
                    className="tab"
                    onClick={() => setPopulations((prev) => prev.filter((x) => x.id !== p.id))}
                  >
                    убрать
                  </button>
                </div>
              ))}
              <p className="secondary">
                Собранная здесь карта — черновик поверх хранимой. Сохранённая карта
                <span className="mono"> {demandMapId} </span>
                остаётся тем, на что ссылается сценарий: подменять её незаметно
                для расчёта нельзя (ADR-021).
              </p>
            </div>
          </div>
        )}
      </aside>
    </div>
  )
}
