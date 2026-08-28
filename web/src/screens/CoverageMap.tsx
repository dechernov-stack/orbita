// §6 МВП-М1: плоская карта с зумом — Leaflet, офлайн-подложка (контуры
// стран Natural Earth из npm-пакета, тайл-серверов и интернета нет).
// Слои независимыми переключателями: ёмкость (ячейки канвасом, шкала с
// числовой легендой), маски зон, станции, трассы подгрупп своим цветом.
// Клиент КРАСИТ по статистике сервера (min/max — map_stats), не считает.
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

interface Props {
  scenario: string
  view: CoverageView
}

type LayerKey = 'capacity' | 'masks' | 'stations' | 'tracks'

export function CoverageMap({ scenario, view }: Props) {
  const holder = useRef<HTMLDivElement>(null)
  const mapRef = useRef<L.Map | null>(null)
  const groups = useRef<Record<LayerKey, L.LayerGroup>>({} as Record<LayerKey, L.LayerGroup>)
  const loaded = useRef<Set<LayerKey>>(new Set())
  const [layers, setLayers] = useState<Record<LayerKey, boolean>>({
    capacity: true, masks: false, stations: false, tracks: false,
  })
  const [note, setNote] = useState<string | null>(null)
  const [trackLegend, setTrackLegend] = useState<Array<{ name: string; color: string }>>([])

  // карта и офлайн-подложка — один раз
  useEffect(() => {
    if (!holder.current || mapRef.current) return
    const map = L.map(holder.current, {
      renderer: L.canvas(),
      center: [50, 60],
      zoom: 3,
      minZoom: 2,
      maxZoom: 10,
      worldCopyJump: true,
      attributionControl: false,
    })
    const world = feature(
      worldData as unknown as Topology,
      (worldData as unknown as { objects: { countries: GeometryCollection } }).objects.countries,
    )
    L.geoJSON(world, {
      style: { color: '#5b6b7c', weight: 0.6, fillColor: '#22303f', fillOpacity: 1 },
      interactive: false,
    }).addTo(map)
    ;(['capacity', 'masks', 'stations', 'tracks'] as LayerKey[]).forEach((k) => {
      groups.current[k] = L.layerGroup()
    })
    groups.current.capacity.addTo(map)
    mapRef.current = map
    return () => { map.remove(); mapRef.current = null; loaded.current.clear() }
  }, [])

  // слой ёмкости — из данных выдачи покрытия (значения и min/max — сервера)
  useEffect(() => {
    const g = groups.current.capacity
    if (!g) return
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

    if (layers.masks && !loaded.current.has('masks')) {
      loaded.current.add('masks')
      api.geoMasks(scenario)
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
    if (layers.tracks && !loaded.current.has('tracks')) {
      loaded.current.add('tracks')
      api.groundTracks(scenario)
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

  const { pass_minutes_min: lo, pass_minutes_max: hi } = view.map_stats
  const LAYER_TITLES: Record<LayerKey, string> = {
    capacity: 'ёмкость', masks: 'маски зон', stations: 'станции', tracks: 'трассы подгрупп',
  }

  return (
    <div>
      <div style={{ display: 'flex', gap: 6, alignItems: 'center', padding: '6px 0', flexWrap: 'wrap' }}>
        <span className="secondary">Слои:</span>
        {(Object.keys(LAYER_TITLES) as LayerKey[]).map((k) => (
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
        {note && <span className="warn">{note}</span>}
      </div>
      <div ref={holder} style={{ height: 420, border: '1px solid var(--border)', background: '#0d1b2a' }} />
      {/* §5: легенда с числами — min/max от сервера (map_stats) */}
      <div style={{ display: 'flex', gap: 10, alignItems: 'center', padding: '6px 0' }}
        title="ёмкость ячейки: сумма длительностей сервисных пролётов всех КА за прогон">
        <span className="secondary">проходо-мин/прогон:</span>
        <span className="mono">{lo.toFixed(1)}</span>
        <span style={{
          width: 160, height: 10, borderRadius: 5,
          background: `linear-gradient(90deg, ${[0, 0.25, 0.5, 0.75, 1].map((t) => scaleColor(t)).join(',')})`,
        }} title="шкала линейная между min и max по карте" />
        <span className="mono">{hi.toFixed(1)}</span>
        <span className="secondary" title="ячейка, которую построение не видит вовсе, — не зелёная и не нулевая на шкале, а честно серая">
          <span style={{ color: OUT_OF_VIEW }}>■</span> вне зоны · {view.map_stats.cells_out_of_view}
        </span>
        <span className="secondary" title="баланс: сумма по всем ячейкам карты — сходится с агрегатом построения">
          Σ по карте: <span className="mono">{view.map_stats.pass_minutes_total.toFixed(0)}</span> мин
        </span>
      </div>
    </div>
  )
}
