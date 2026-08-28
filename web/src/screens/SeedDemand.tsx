// Затравка спроса (замечание 28.08 §4): инструмент НАБИВКИ данных карты —
// популяции руками и сценарии библиотеки — живёт в Инструментах, не на
// продуктовом экране анализа. Сборку карты считает сервер (ловушка 2);
// собранное сохраняется хранимой картой, на которую ссылается сценарий.
import { useEffect, useState } from 'react'
import { api } from '../api/client'
import { MapView } from '../ui/MapView'
import { Num, fmtNum } from '../ui/Num'
import type { DemandLayersRequest, DemandMapView, ReferenceScenarioRow } from '../api/types'

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

export function SeedDemand() {
  const [library, setLibrary] = useState<ReferenceScenarioRow[]>([])
  const [populations, setPopulations] = useState<Population[]>([])
  const [scenarioIds, setScenarioIds] = useState<string[]>([])
  const [view, setView] = useState<DemandMapView | null>(null)
  const [draft, setDraft] = useState<Population>(EMPTY)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api.demandLibrary().then(setLibrary).catch((e) => setError(String(e)))
  }, [])

  useEffect(() => {
    setError(null)
    if (populations.length === 0 && scenarioIds.length === 0) { setView(null); return }
    api
      .demand({ population: populations, point_objects: [], scenario_ids: scenarioIds })
      .then(setView)
      .catch((e) => setError(String(e)))
  }, [populations, scenarioIds])

  const addPopulation = () => {
    const id = draft.id.trim()
    if (!id) return
    setPopulations((prev) => [...prev.filter((p) => p.id !== id), { ...draft, id }])
    setDraft({ ...EMPTY, id: '' })
  }

  return (
    <div style={{ display: 'flex', gap: 12, minHeight: 0, flex: 1, alignItems: 'stretch', padding: '8px 0' }}>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div className="secondary" style={{ marginBottom: 6 }}>
          Наборы данных затравки: сценарии библиотеки — включением, популяции —
          формой справа. Сборка считается сервером; предпросмотр — картой ниже.
        </div>
        <div style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap', marginBottom: 6 }}>
          <span className="secondary" title="готовые сценарные наборы спроса — включаются в сборку">
            сценарии библиотеки:
          </span>
          {library.map((s) => (
            <button
              key={s.id}
              className="tab"
              aria-selected={scenarioIds.includes(s.id)}
              title={`${s.geography} · ${s.mobilityModel}`}
              onClick={() => setScenarioIds((prev) =>
                prev.includes(s.id) ? prev.filter((x) => x !== s.id) : [...prev, s.id])}
            >
              {s.name}
            </button>
          ))}
          <span className="chip" title="популяций, заданных руками">население · {populations.length}</span>
        </div>
        {error && <div className="warn" style={{ padding: 8 }}>Ошибка: {error}</div>}
        {view ? (
          <>
            <MapView
              height="calc(100vh - 330px)"
              demandCells={view.cells.map((cell) => ({
                id: cell.id,
                latDeg: cell.latDeg,
                lonDeg: cell.lonDeg,
                halfLatDeg: cell.halfLatDeg,
                halfLonDeg: cell.halfLonDeg,
                intensity: cell.intensity,
                tip: `${cell.id}: ${fmtNum(cell.msgsPerDay)} сообщ./сут`,
              }))}
            />
            <div className="secondary" style={{ padding: '4px 0' }}
              title={`версия сборки: ${view.version}`}>
              всего: <Num v={view.totalMsgsPerDay} unit="сообщ./сут" /> · ячеек {view.cells.length}
              {view.issues.length > 0 && <span className="amber"> · замечаний {view.issues.length}</span>}
            </div>
          </>
        ) : (
          <div className="empty">Включите сценарий библиотеки или добавьте популяцию — предпросмотр соберётся сервером.</div>
        )}
      </div>
      <aside style={{ width: 260, flex: 'none', overflowY: 'auto' }}>
        <div className="card">
          <h3>Добавить популяцию</h3>
          <div>
            <div className="field">
              <label>Ячейка</label>
              <input value={draft.id} placeholder="например, p45"
                onChange={(e) => setDraft({ ...draft, id: e.target.value })} />
            </div>
            <div className="field">
              <label>Широта, °</label>
              <input type="number" value={draft.lat}
                onChange={(e) => setDraft({ ...draft, lat: Number(e.target.value) })} />
            </div>
            <div className="field">
              <label>Долгота, °</label>
              <input type="number" value={draft.lon}
                onChange={(e) => setDraft({ ...draft, lon: Number(e.target.value) })} />
            </div>
            <div className="field">
              <label>Плотность, чел./км²</label>
              <input type="number" value={draft.pop_density_per_km2}
                onChange={(e) => setDraft({ ...draft, pop_density_per_km2: Number(e.target.value) })} />
            </div>
            <div className="field">
              <label>Терминалов на человека</label>
              <input type="number" step="0.001" value={draft.terminals_per_capita}
                onChange={(e) => setDraft({ ...draft, terminals_per_capita: Number(e.target.value) })} />
            </div>
            <div className="field">
              <label>Сообщений на терминал в сутки</label>
              <input type="number" value={draft.msgs_per_terminal_day}
                onChange={(e) => setDraft({ ...draft, msgs_per_terminal_day: Number(e.target.value) })} />
            </div>
            <div className="field">
              <label>Класс потребителей</label>
              <select value={draft.consumer_class}
                onChange={(e) => setDraft({ ...draft, consumer_class: e.target.value })}>
                {['A_prime', 'B_prime', 'C_prime'].map((c) => <option key={c} value={c}>{c}</option>)}
              </select>
            </div>
            <button className="tab tab--primary" onClick={addPopulation} disabled={!draft.id.trim()}>
              Добавить слой
            </button>
          </div>
        </div>
        {populations.length > 0 && (
          <div className="card">
            <h3>Популяции · {populations.length}</h3>
            <div>
              {populations.map((pop) => (
                <div key={pop.id} className="sp-file" style={{ padding: '3px 0' }}>
                  <span className="mono">{pop.id}</span>
                  <span className="secondary">{pop.lat}°, {pop.lon}°</span>
                  <button className="rr-assign" title="убрать слой из сборки"
                    onClick={() => setPopulations((prev) => prev.filter((p) => p.id !== pop.id))}>
                    ✕
                  </button>
                </div>
              ))}
            </div>
          </div>
        )}
      </aside>
    </div>
  )
}
