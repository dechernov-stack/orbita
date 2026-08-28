// §6.3а МВП-М3: ВЬЮЕР ОДИН НА СИСТЕМУ — карта спроса, ёмкости, запаса и
// масок живут на одной подоснове (Leaflet, офлайн-контуры Natural Earth из
// npm-пакета; тайлов и интернета нет). Стартовый вид — Россия в кадре,
// север вверху, кнопка «домой» возвращает его; поворота карты нет как
// класса. Слои независимыми переключателями: спрос (по классам A′/B′/C′),
// ёмкость (проходо-минуты), запас (обслуживаемо/спрос — метрика 6), маски
// зон, станции, трассы подгрупп своим цветом. Клиент КРАСИТ по статистике
// сервера (min/max — map_stats), не считает.
import { useEffect, useRef, useState } from 'react'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { feature } from 'topojson-client'
import type { Topology, GeometryCollection } from 'topojson-specification'
import worldData from 'world-atlas/countries-110m.json'
import { api } from '../api/client'
import { edit } from '../api/edit'
import type { CoverageView } from '../api/types'

/** Палитра трасс подгрупп — индекс от сервера, цвета клиента (раскраска). */
const TRACK_COLORS = ['#ff5964', '#ffd166', '#3fb27f', '#9b5de5', '#00bbf9', '#f15bb5']

/** Шкала ёмкости: тёмно-синий → зелёный → жёлтый (низ → верх). */
const SCALE_STOPS: Array<[number, number, number]> = [
  [13, 43, 84], [24, 98, 121], [58, 152, 116], [138, 199, 92], [244, 222, 88],
]

function scaleColor(t: number): string {
  // раскраска, не расчёт модели: CSS принимает дробные каналы rgb()
  const x = t < 0 ? 0 : t > 1 ? 1 : t
  const pos = x * (SCALE_STOPS.length - 1)
  let i = 0
  while (i < SCALE_STOPS.length - 2 && pos >= i + 1) i += 1
  const f = pos - i
  const a = SCALE_STOPS[i]
  const b = SCALE_STOPS[i + 1]
  const c = a.map((v, k) => v + (b[k] - v) * f)
  return `rgb(${c[0]},${c[1]},${c[2]})`
}

const OUT_OF_VIEW = '#3a4450'

/** Ячейка standalone-слоя спроса (конструктор карты): интенсивность 0..1 — от сервера. */
export interface DemandPreviewCell {
  id: string
  latDeg: number
  lonDeg: number
  halfLatDeg: number
  halfLonDeg: number
  intensity: number
  tip: string
}

interface Props {
  scenario?: string
  view?: CoverageView | null
  /** Слой спроса без выдачи покрытия — превью конструктора карты спроса. */
  demandCells?: DemandPreviewCell[]
  height?: number | string
  initialLayers?: Partial<Record<LayerKey, boolean>>
}

type LayerKey = 'capacity' | 'margin' | 'demand' | 'masks' | 'stations' | 'tracks'

/** §6.3: Россия в кадре, север вверху; «домой» возвращает этот вид. */
const HOME: { center: [number, number]; zoom: number } = { center: [62, 95], zoom: 3 }

export function MapView({ scenario, view, demandCells, height, initialLayers }: Props) {
  const holder = useRef<HTMLDivElement>(null)
  const mapRef = useRef<L.Map | null>(null)
  const groups = useRef<Record<LayerKey, L.LayerGroup>>({} as Record<LayerKey, L.LayerGroup>)
  const loaded = useRef<Set<LayerKey>>(new Set())
  const [layers, setLayers] = useState<Record<LayerKey, boolean>>({
    capacity: Boolean(view), margin: false, demand: !view && Boolean(demandCells?.length),
    masks: false, stations: false, tracks: false,
    ...initialLayers,
  })
  const [demandClass, setDemandClass] = useState<string>('all')
  const [note, setNote] = useState<string | null>(null)
  const [trackLegend, setTrackLegend] = useState<Array<{ name: string; color: string }>>([])

  // карта и офлайн-подложка — один раз
  useEffect(() => {
    if (!holder.current || mapRef.current) return
    const map = L.map(holder.current, {
      renderer: L.canvas(),
      center: HOME.center,
      zoom: HOME.zoom,
      minZoom: 2,
      maxZoom: 10,
      worldCopyJump: true,
      attributionControl: false,
    })
    // Подложка контурами-линиями с разрезкой по антимеридиану: полигон
    // России идёт через 180° и рисовался «полосой» поперёк карты
    // (замечание 28.08 §5); сегмент со скачком долготы >180° разрывается
    const world = feature(
      worldData as unknown as Topology,
      (worldData as unknown as { objects: { countries: GeometryCollection } }).objects.countries,
    ) as unknown as { features: Array<{ geometry: { type: string; coordinates: unknown } }> }
    const rings: number[][][] = []
    world.features.forEach((f) => {
      const g = f.geometry
      if (g.type === 'Polygon') (g.coordinates as number[][][]).forEach((r) => rings.push(r))
      if (g.type === 'MultiPolygon') {
        (g.coordinates as number[][][][]).forEach((p) => p.forEach((r) => rings.push(r)))
      }
    })
    rings.forEach((ring) => {
      let seg: Array<[number, number]> = []
      const flush = () => {
        if (seg.length > 1) {
          L.polyline(seg, { color: '#5b6b7c', weight: 0.6, interactive: false }).addTo(map)
        }
        seg = []
      }
      ring.forEach(([lon, lat], i) => {
        if (i > 0) {
          const prev = ring[i - 1][0]
          if ((lon > prev ? lon - prev : prev - lon) > 180) flush()
        }
        seg.push([lat, lon])
      })
      flush()
    })
    ;(['capacity', 'margin', 'demand', 'masks', 'stations', 'tracks'] as LayerKey[]).forEach((k) => {
      groups.current[k] = L.layerGroup()
    })
    mapRef.current = map
    const onFs = () => setTimeout(() => map.invalidateSize(), 60)
    document.addEventListener('fullscreenchange', onFs)
    return () => {
      document.removeEventListener('fullscreenchange', onFs)
      map.remove(); mapRef.current = null; loaded.current.clear()
    }
  }, [])

  // слой ёмкости — из данных выдачи покрытия (значения и min/max — сервера)
  useEffect(() => {
    const g = groups.current.capacity
    if (!g || !view) return
    g.clearLayers()
    const { pass_minutes_min: lo, pass_minutes_max: hi } = view.map_stats
    const span = hi - lo
    view.cells.forEach((c) => {
      const dead = c.pass_minutes <= 0
      const color = dead ? OUT_OF_VIEW : scaleColor(span > 0 ? (c.pass_minutes - lo) / span : 0.5)
      const rect = L.rectangle(
        [[c.lat_deg - c.half_lat_deg, c.lon_deg - c.half_lon_deg],
          [c.lat_deg + c.half_lat_deg, c.lon_deg + c.half_lon_deg]],
        { stroke: false, fillColor: color, fillOpacity: dead ? 0.5 : 0.78 },
      )
      // §5: тултип ячейки — число, не только цвет; клик закрепляет подпись
      const text = dead
        ? `${c.cell_id}: вне зоны видимости построения`
        : `${c.cell_id}: ${c.pass_minutes.toFixed(1)} проходо-мин/прогон · доступность ${(c.availability_mean * 100).toFixed(1)}%`
      rect.bindTooltip(text, { sticky: true })
      rect.on('click', () => {
        const pinned = rect.getTooltip()?.options.permanent
        rect.unbindTooltip()
        rect.bindTooltip(text, pinned ? { sticky: true } : { permanent: true, interactive: true })
        if (!pinned) rect.openTooltip()
      })
      rect.addTo(g)
    })
  }, [view])

  // §6: слой «запас» — обслуживаемо/спрос (метрика 6), той же шкалой
  useEffect(() => {
    const g = groups.current.margin
    if (!g || !view) return
    g.clearLayers()
    const lo = view.map_stats.margin_min
    const hi = view.map_stats.margin_max
    const span = hi - lo
    view.cells.forEach((c) => {
      if (c.margin_min_per_msg === undefined) return // спроса в ячейке нет
      const color = scaleColor(span > 0 ? (c.margin_min_per_msg - lo) / span : 0.5)
      const rect = L.rectangle(
        [[c.lat_deg - c.half_lat_deg, c.lon_deg - c.half_lon_deg],
          [c.lat_deg + c.half_lat_deg, c.lon_deg + c.half_lon_deg]],
        { stroke: false, fillColor: color, fillOpacity: 0.78 },
      )
      rect.bindTooltip(
        `${c.cell_id}: запас ${c.margin_min_per_msg.toFixed(2)} проходо-мин/сообщение`,
        { sticky: true },
      )
      rect.addTo(g)
    })
  }, [view])

  // §6: слой «спрос» — та самая карта спроса, тем же вьюером; по классам
  useEffect(() => {
    const g = groups.current.demand
    if (!g) return
    g.clearLayers()
    if (view) {
      const maxBy = view.map_stats.demand_max_by_class ?? {}
      view.cells.forEach((c) => {
        const by = c.demand_by_class ?? {}
        const classes = demandClass === 'all' ? Object.keys(by) : [demandClass]
        const count = classes.reduce((a, cls) => a + (by[cls] ?? 0), 0)
        if (count <= 0) return
        const max = demandClass === 'all'
          ? Object.values(maxBy).reduce((m: number, v: number) => (v > m ? v : m), 1)
          : (maxBy[demandClass] ?? 1)
        const rect = L.rectangle(
          [[c.lat_deg - c.half_lat_deg, c.lon_deg - c.half_lon_deg],
            [c.lat_deg + c.half_lat_deg, c.lon_deg + c.half_lon_deg]],
          { stroke: false, fillColor: '#ffd166', fillOpacity: 0.15 + 0.7 * (count / max) },
        )
        rect.bindTooltip(
          `${c.cell_id}: спрос ${classes.map((cls) => `${cls}: ${by[cls] ?? 0}`).join(' · ')}`,
          { sticky: true },
        )
        rect.addTo(g)
      })
    } else {
      ;(demandCells ?? []).forEach((c) => {
        L.rectangle(
          [[c.latDeg - c.halfLatDeg, c.lonDeg - c.halfLonDeg],
            [c.latDeg + c.halfLatDeg, c.lonDeg + c.halfLonDeg]],
          { stroke: false, fillColor: '#ffd166', fillOpacity: 0.2 + 0.7 * c.intensity },
        ).bindTooltip(c.tip, { sticky: true }).addTo(g)
      })
    }
  }, [view, demandCells, demandClass])

  // ленивые слои — тянутся при первом включении
  useEffect(() => {
    const map = mapRef.current
    if (!map) return
    ;(Object.keys(layers) as LayerKey[]).forEach((k) => {
      const g = groups.current[k]
      if (!g) return
      if (layers[k] && !map.hasLayer(g)) g.addTo(map)
      if (!layers[k] && map.hasLayer(g)) map.removeLayer(g)
    })

    if (scenario && layers.masks && !loaded.current.has('masks')) {
      loaded.current.add('masks')
      api.geoMasks(scenario!)
        .then((m) => {
          const g = groups.current.masks
          m.rx.forEach(([lat, lon]) => L.circle([lat, lon], {
            radius: m.rx_radius_km * 1000, stroke: false, fillColor: '#3fb27f', fillOpacity: 0.12,
          }).bindTooltip('зона приёма (маска rx)', { sticky: true }).addTo(g))
          m.downlink.forEach(([lat, lon]) => L.circle([lat, lon], {
            radius: m.downlink_radius_km * 1000, color: '#ffd166', weight: 1,
            fillColor: '#ffd166', fillOpacity: 0.10,
          }).bindTooltip('зона сброса на станцию', { sticky: true }).addTo(g))
        })
        .catch((e) => setNote(`маски: ${String(e).slice(0, 120)}`))
    }
    if (layers.stations && !loaded.current.has('stations')) {
      loaded.current.add('stations')
      edit.list('ground_stations')
        .then(async (rows) => {
          if (rows.length === 0) { setNote('станций в модели нет'); return }
          const doc = (await edit.object(rows[0].id)).doc as {
            stations?: Array<{ id: string; name?: string; lat_deg: number; lon_deg: number }>
          }
          const g = groups.current.stations
          ;(doc.stations ?? []).forEach((st) => {
            L.circleMarker([st.lat_deg, st.lon_deg], {
              radius: 5, color: '#0d1b2a', weight: 1, fillColor: '#ffd166', fillOpacity: 1,
            }).bindTooltip(`${st.name || st.id} — наземная станция`, { sticky: true }).addTo(g)
          })
        })
        .catch((e) => setNote(`станции: ${String(e).slice(0, 120)}`))
    }
    if (scenario && layers.tracks && !loaded.current.has('tracks')) {
      loaded.current.add('tracks')
      api.groundTracks(scenario!)
        .then((t) => {
          const g = groups.current.tracks
          const legend: Array<{ name: string; color: string }> = []
          t.subgroups.forEach((sg) => {
            const color = TRACK_COLORS[sg.color_index % TRACK_COLORS.length]
            legend.push({ name: sg.name, color })
            sg.tracks.forEach((tr) => {
              // разрыв полилинии на антимеридиане: сегменты со скачком
              // долготы > 180° не соединяются линией через всю карту
              let seg: Array<[number, number]> = []
              const flush = () => {
                if (seg.length > 1) {
                  L.polyline(seg, { color, weight: 1.4, opacity: 0.9 })
                    .bindTooltip(`${sg.name} · ${tr.sat}`, { sticky: true })
                    .addTo(g)
                }
                seg = []
              }
              tr.points.forEach(([lat, lon], i) => {
                if (i > 0) {
                  const prev = tr.points[i - 1][1]
                  if ((lon > prev ? lon - prev : prev - lon) > 180) flush()
                }
                seg.push([lat, lon])
              })
              flush()
            })
          })
          setTrackLegend(legend)
        })
        .catch((e) => setNote(`трассы: ${String(e).slice(0, 120)}`))
    }
  }, [layers, scenario])

  // смена сценария — ленивые слои устарели
  useEffect(() => {
    loaded.current.clear()
    setTrackLegend([])
    ;(['masks', 'stations', 'tracks'] as LayerKey[]).forEach((k) => groups.current[k]?.clearLayers())
  }, [scenario])

  const LAYER_TITLES: Record<LayerKey, string> = {
    demand: 'спрос', capacity: 'ёмкость', margin: 'запас',
    masks: 'маски зон', stations: 'станции', tracks: 'трассы подгрупп',
  }
  const availableLayers = (Object.keys(LAYER_TITLES) as LayerKey[]).filter((k) => {
    if (k === 'demand') return Boolean(view) || Boolean(demandCells?.length)
    if (k === 'capacity' || k === 'margin') return Boolean(view)
    return Boolean(scenario)
  })

  return (
    <div>
      <div style={{ display: 'flex', gap: 6, alignItems: 'center', padding: '6px 0', flexWrap: 'wrap' }}>
        <span className="secondary">Слои:</span>
        {availableLayers.map((k) => (
          <button key={k} className="tab" aria-selected={layers[k]}
            title={`слой «${LAYER_TITLES[k]}» — независимый переключатель`}
            onClick={() => setLayers((prev) => ({ ...prev, [k]: !prev[k] }))}>
            {LAYER_TITLES[k]}
          </button>
        ))}
        {trackLegend.length > 0 && layers.tracks && (
          <span className="secondary" style={{ display: 'flex', gap: 10 }}>
            {trackLegend.map((l) => (
              <span key={l.name} title={`трассы подгруппы «${l.name}»`}>
                <span style={{ color: l.color }}>━</span> {l.name}
              </span>
            ))}
          </span>
        )}
        {layers.demand && view && (
          <span style={{ display: 'flex', gap: 4, alignItems: 'center' }}>
            <span className="secondary" title="класс потребителей слоя спроса">класс:</span>
            {['all', 'A_prime', 'B_prime', 'C_prime'].map((cls) => (
              <button key={cls} className="tab" aria-selected={demandClass === cls}
                title={cls === 'all' ? 'все классы вместе' : `только ${cls}`}
                onClick={() => setDemandClass(cls)}>
                {cls === 'all' ? 'все' : cls.replace('_prime', '′')}
              </button>
            ))}
          </span>
        )}
        <span style={{ flex: 1 }} />
        <button className="rr-assign" title="домой: Россия в кадре, север вверху"
          onClick={() => mapRef.current?.setView(HOME.center, HOME.zoom)}>
          ⌂ домой
        </button>
        <button className="rr-assign" title="карта на весь экран; Esc — назад"
          onClick={() => {
            const el = holder.current
            if (!el) return
            if (document.fullscreenElement) void document.exitFullscreen()
            else void el.requestFullscreen()
          }}>
          ⛶ развернуть
        </button>
        {note && <span className="warn">{note}</span>}
      </div>
      <div ref={holder} style={{ height: height ?? 420, border: '1px solid var(--border)', background: '#0d1b2a' }} />
      {/* §5: легенды с числами — min/max от сервера (map_stats) */}
      {view && layers.capacity && (
        <div style={{ display: 'flex', gap: 10, alignItems: 'center', padding: '6px 0' }}
          title="ёмкость ячейки: сумма длительностей сервисных пролётов всех КА за прогон">
          <span className="secondary">проходо-мин/прогон:</span>
          <span className="mono">{view.map_stats.pass_minutes_min.toFixed(1)}</span>
          <span style={{
            width: 160, height: 10, borderRadius: 5,
            background: `linear-gradient(90deg, ${[0, 0.25, 0.5, 0.75, 1].map((t) => scaleColor(t)).join(',')})`,
          }} title="шкала линейная между min и max по карте" />
          <span className="mono">{view.map_stats.pass_minutes_max.toFixed(1)}</span>
          <span className="secondary" title="ячейка, которую построение не видит вовсе, — не зелёная и не нулевая на шкале, а честно серая">
            <span style={{ color: OUT_OF_VIEW }}>■</span> вне зоны · {view.map_stats.cells_out_of_view}
          </span>
          <span className="secondary" title="баланс: сумма по всем ячейкам карты — сходится с агрегатом построения">
            Σ по карте: <span className="mono">{view.map_stats.pass_minutes_total.toFixed(0)}</span> мин
          </span>
        </div>
      )}
      {view && layers.margin && (
        <div style={{ display: 'flex', gap: 10, alignItems: 'center', padding: '6px 0' }}
          title="запас: проходо-минуты на сообщение спроса (метрика 6); ячейки без спроса в слое не рисуются">
          <span className="secondary">запас, проходо-мин/сообщение:</span>
          <span className="mono">{view.map_stats.margin_min.toFixed(2)}</span>
          <span style={{
            width: 160, height: 10, borderRadius: 5,
            background: `linear-gradient(90deg, ${[0, 0.25, 0.5, 0.75, 1].map((t) => scaleColor(t)).join(',')})`,
          }} />
          <span className="mono">{view.map_stats.margin_max.toFixed(2)}</span>
        </div>
      )}
    </div>
  )
}
