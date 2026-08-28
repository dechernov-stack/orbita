// Экран 4 — карта спроса (Ш2 мастера, TZ-USR-004) — рабочее место АНАЛИЗА
// спроса (замечание 28.08): карта ГЛАВНАЯ и тянется с окном; сводка и
// широтный профиль — сворачиваемой боковой панелью; числа — общим правилом
// (ui/Num: без float-хвостов, веса — процентами полосками); хеш версии —
// в подсказке, не в продуктовом месте. Затравка данных (популяции,
// сценарии) — отдельным местом: Инструменты → «Затравка спроса».
//
// Ни веса ячеек, ни доля от максимума, ни пик «час × месяц» в клиенте не
// считаются (ловушка 2) — всё приходит посчитанным с сервера.
import { useEffect, useState } from 'react'
import { api } from '../api/client'
import { MapView } from '../ui/MapView'
import { Num, ShareBar, fmtNum } from '../ui/Num'
import { edit, type StoredSummary } from '../api/edit'
import type { DemandMapView } from '../api/types'

const CLASS_LABEL: Record<string, string> = {
  A_prime: "A′",
  B_prime: "B′",
  C_prime: "C′",
}

export function Demand({ demandMapId }: { demandMapId?: string }) {
  const [view, setView] = useState<DemandMapView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [storedMaps, setStoredMaps] = useState<StoredSummary[]>([])
  const [mapId, setMapId] = useState<string | undefined>(demandMapId)
  const [panelOpen, setPanelOpen] = useState(true)

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
    setError(null)
    if (!mapId) return
    // Хранимая карта (ADR-021): ячейки и веса из сохранённого документа,
    // на который ссылается сценарий, а не пересчитанные заново.
    api.demandStored(mapId).then(setView).catch((e) => setError(String(e)))
  }, [mapId])

  if (storedMaps.length === 0 && !error) {
    return (
      <div className="empty">
        Хранимых карт спроса нет. Данные затравки (популяции, сценарии
        библиотеки) вносятся в «Инструменты → Затравка спроса»; карта, на
        которую ссылается сценарий, появится здесь.
      </div>
    )
  }

  const latTotal = view?.latitudeProfile.reduce((a, b) => a + b.weight, 0) ?? 0

  return (
    <div style={{ display: 'flex', flexDirection: 'column', minHeight: 0, flex: 1 }}>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', padding: '6px 0' }}>
        <span className="secondary">Карта:</span>
        <select
          value={mapId ?? ''}
          onChange={(e) => setMapId(e.target.value)}
          title={view ? `карта из модели, на неё ссылается сценарий · версия ${view.version}` : 'карта из модели'}
        >
          {storedMaps.map((m) => (
            <option key={m.id} value={m.id}>{m.id}{m.title ? ` — ${m.title}` : ''}</option>
          ))}
        </select>
        <span className="secondary" title="равнопромежуточная проекция при равноплощадной сетке: у полюсов ячейка выглядит крупнее, чем весит — вес пояса читается числом в профиле">
          проекция равнопромежуточная — вес пояса см. в профиле
        </span>
        <div style={{ flex: 1 }} />
        <button className="rr-assign"
          title={panelOpen ? 'спрятать сводку — карте всё место' : 'показать сводку и широтный профиль'}
          onClick={() => setPanelOpen((v) => !v)}>
          {panelOpen ? 'сводка ⟩' : '⟨ сводка'}
        </button>
      </div>
      {error && <div className="warn" style={{ padding: 8 }}>Ошибка: {error}</div>}
      {view && (
        <div style={{ display: 'flex', gap: 10, minHeight: 0, flex: 1, alignItems: 'stretch' }}>
          <div style={{ flex: 1, minWidth: 0 }}>
            <MapView
              height="calc(100vh - 230px)"
              demandCells={view.cells.map((cell) => ({
                id: cell.id,
                latDeg: cell.latDeg,
                lonDeg: cell.lonDeg,
                halfLatDeg: cell.halfLatDeg,
                halfLonDeg: cell.halfLonDeg,
                intensity: cell.intensity,
                tip: `${cell.id}: ${fmtNum(cell.msgsPerDay)} сообщ./сут · ` +
                  Object.entries(cell.byClass).map(([k, v]) => `${CLASS_LABEL[k] ?? k} ${fmtNum(v)}`).join(' · '),
              }))}
            />
          </div>
          {panelOpen && (
            <aside style={{ width: 280, flex: 'none', overflowY: 'auto' }}>
              <div className="card">
                <h3>Сводка спроса</h3>
                <div>
                  <div className="field">
                    <label>Всего сообщений в сутки</label>
                    <Num v={view.totalMsgsPerDay} />
                  </div>
                  <div className="field">
                    <label>Пик, сообщ./с</label>
                    <Num v={view.peak.msgsPerS} />
                    <div className="secondary">
                      {view.peak.profiled
                        ? `худший час ${view.peak.hour}, месяц ${view.peak.month}`
                        : 'профили активности не заданы — активность равномерная'}
                    </div>
                  </div>
                  <div className="field">
                    <label>Терминалов по классам</label>
                    {Object.entries(view.terminalsByClass).map(([klass, count]) => (
                      <div key={klass} style={{ display: 'flex', gap: 6, alignItems: 'baseline' }}>
                        <span style={{ width: 22 }}>{CLASS_LABEL[klass] ?? klass}</span>
                        <Num v={count} />
                      </div>
                    ))}
                  </div>
                  <div className="field">
                    <label>Сообщений по классам, в сутки</label>
                    {Object.entries(view.byClass).map(([klass, msgs]) => (
                      <div key={klass} style={{ display: 'flex', gap: 6, alignItems: 'baseline' }}>
                        <span style={{ width: 22 }}>{CLASS_LABEL[klass] ?? klass}</span>
                        <Num v={msgs} />
                      </div>
                    ))}
                  </div>
                </div>
              </div>
              <div className="card">
                <h3>Широтный профиль</h3>
                <div>
                  <p className="secondary" style={{ margin: '0 0 6px' }}>
                    вес пояса — долей от всего спроса
                  </p>
                  {view.latitudeProfile.filter((b) => b.weight > 0).map((band) => (
                    <div key={band.bandDeg}
                      style={{ display: 'flex', gap: 8, alignItems: 'center', padding: '1px 0' }}>
                      <span className="mono" style={{ width: 40 }}>{band.bandDeg}°</span>
                      <ShareBar share={latTotal > 0 ? band.weight / latTotal : 0} />
                    </div>
                  ))}
                </div>
              </div>
              {view.issues.length > 0 && (
                <div className="card">
                  <h3>Замечания</h3>
                  <div>
                    {view.issues.map((issue) => (
                      <div key={issue} className="amber">△ {issue}</div>
                    ))}
                  </div>
                </div>
              )}
            </aside>
          )}
        </div>
      )}
    </div>
  )
}
