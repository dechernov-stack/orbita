// Экран 6 — баллистика: глобус с трассами группировки и расписание пролётов.
//
// Собственной модели движения в клиенте НЕТ (STEP-7-9 §8.1): траектории, зоны
// обслуживания, станции и ячейки спроса приходят CZML-потоком с сервера, из
// того же пропагатора, который считает видимость. Cesium — средство
// отображения, а не расчёта. Времена окон посчитаны сервером в UTC; клиент
// подсвечивает текущую строку и мотает шкалу к началу окна — это синхронизация
// вида, а не вычисление.
//
// Конфигурация — из хранимой группировки по ссылке сценария (шаг 16 §2.3):
// зашитой строки параметров больше нет; отсутствие сценария — рабочее
// состояние с объяснением, а не пустой глобус.
import { useEffect, useRef, useState } from 'react'
import {
  Color,
  CzmlDataSource,
  Ion,
  JulianDate,
  Viewer,
  ArcType,
  Cartesian3,
} from 'cesium'
import 'cesium/Build/Cesium/Widgets/widgets.css'
import { COAST } from '../assets/coastlines'
import { api, ApiError } from '../api/client'
import { edit, type StoredSummary } from '../api/edit'
import type { GlobeView } from '../api/types'

const fmtTime = (iso: string) => iso.slice(11, 19)
const fmtMin = (s: number) => `${(s / 60).toFixed(1)} мин`

export function Globe() {
  const container = useRef<HTMLDivElement>(null)
  const viewerRef = useRef<Viewer | undefined>(undefined)
  const [scenarios, setScenarios] = useState<StoredSummary[]>([])
  const [scenario, setScenario] = useState('')
  const [view, setView] = useState<GlobeView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [status, setStatus] = useState('загрузка…')
  /** Номер строки расписания, накрывающей текущее время шкалы. */
  const [activeRow, setActiveRow] = useState<number>(-1)

  // Сценарий выбирается из хранимых, не зашивается (шаг 16 §3.2)
  useEffect(() => {
    edit
      .list('scenario')
      .then((rows) => {
        setScenarios(rows)
        if (rows.length > 0) setScenario((cur) => cur || rows[0].id)
      })
      .catch((e) => setError(String(e)))
  }, [])

  useEffect(() => {
    if (!scenario || !container.current) return
    let cancelled = false

    // Токен Ion не задан: используется база без внешних тайлов. Ключей
    // в репозитории нет и быть не должно.
    Ion.defaultAccessToken = ''

    const start = async () => {
      setNotice(null)
      setError(null)
      setStatus('загрузка трасс…')
      try {
        const data = await api.globe(scenario)
        if (cancelled || !container.current) return
        setView(data)

        if (!viewerRef.current) {
          viewerRef.current = new Viewer(container.current, {
            // Внешних тайлов нет намеренно: ключей в репозитории быть не должно,
            // а зависимость от чужого сервиса делала бы экран неработающим
            // там, где сети нет. Глобус рисуется однотонным — трассы видны и так.
            baseLayer: false,
            baseLayerPicker: false,
            geocoder: false,
            homeButton: false,
            sceneModePicker: false,
            navigationHelpButton: false,
            animation: true,
            timeline: true,
            terrainProvider: undefined,
          })
          viewerRef.current.scene.globe.baseColor = Color.fromCssColorString('#1b3a5c')
          viewerRef.current.scene.globe.showGroundAtmosphere = true
          // Подложка Земли (§3.7): береговые линии Natural Earth из репозитория —
          // офлайн, без ключей и сети; глобус не рисуется от руки
          // 20 км над эллипсоидом и заметная ширина: на поверхности линии
          // тонули под заливкой зон обслуживания
          const coastMaterial = Color.fromCssColorString('#cfe0ff').withAlpha(0.85)
          for (const line of COAST as Array<Array<[number, number]>>) {
            if (line.length < 2) continue
            viewerRef.current.entities.add({
              polyline: {
                positions: line.map(([lat, lon]) => Cartesian3.fromDegrees(lon, lat, 20000)),
                width: 1.4,
                material: coastMaterial,
                arcType: ArcType.GEODESIC,
              },
            })
          }
        }
        const viewer = viewerRef.current
        viewer.dataSources.removeAll()
        const source = await CzmlDataSource.load(data.czml as object[])
        await viewer.dataSources.add(source)
        viewer.clock.currentTime = JulianDate.clone(viewer.clock.startTime)
        viewer.clock.shouldAnimate = true
        await viewer.zoomTo(source)
        setStatus(
          `аппаратов: ${data.czml.filter((p) => (p as { id?: string }).id?.startsWith('SAT-')).length}` +
            ` · станций: ${data.czml.filter((p) => (p as { id?: string }).id?.startsWith('gs-')).length}` +
            ` · окон: ${data.passes_total}`,
        )
      } catch (e) {
        setView(null)
        if (e instanceof ApiError && e.status === 409) {
          try {
            setNotice(String(JSON.parse(e.message.slice(e.message.indexOf('{'))).error))
          } catch {
            setNotice(e.message)
          }
        } else {
          setError(String(e))
        }
      }
    }
    void start()

    return () => {
      cancelled = true
    }
  }, [scenario])

  // Экран уходит — Cesium уходит с ним
  useEffect(
    () => () => {
      viewerRef.current?.destroy()
      viewerRef.current = undefined
    },
    [],
  )

  // Подсветка строки, накрывающей текущее время шкалы: синхронизация вида
  useEffect(() => {
    const viewer = viewerRef.current
    if (!viewer || !view) return
    const listener = () => {
      // уход с экрана уничтожает viewer, а тик может прийти после: обращение
      // к часам уничтоженного viewer роняло всё приложение в белый экран
      if (viewer.isDestroyed()) return
      const now = JulianDate.toIso8601(viewer.clock.currentTime)
      const i = view.passes.findIndex((p) => p.start_utc <= now && now <= p.end_utc)
      setActiveRow((cur) => (cur === i ? cur : i))
    }
    viewer.clock.onTick.addEventListener(listener)
    return () => {
      if (!viewer.isDestroyed()) viewer.clock.onTick.removeEventListener(listener)
    }
  }, [view])

  const seekTo = (iso: string) => {
    const viewer = viewerRef.current
    if (!viewer) return
    viewer.clock.currentTime = JulianDate.fromIso8601(iso)
    viewer.clock.shouldAnimate = true
  }

  if (scenarios.length === 0 && !error) {
    return (
      <div className="empty">
        Сценариев в модели нет: заведите сценарий на Ш5 «Входы моделирования» — глобус
        строится по его ссылкам на группировку, станции и карту спроса.
      </div>
    )
  }

  return (
    // gridArea main: экран кладётся прямо в сетку .app, и без области корень
    // уезжает в автопоток. Класс topbar внутри экрана не используется — он
    // несёт grid-area: top и растаскивал раскладку (дефект виден показом).
    <div style={{ gridArea: 'main', display: 'grid', gridTemplateRows: 'auto 1fr', minHeight: 0, minWidth: 0 }}>
      <div style={{ borderBottom: '1px solid var(--border)', padding: '6px 8px', display: 'flex', gap: 8, alignItems: 'center' }}>
        <span className="secondary">Сценарий:</span>
        <select value={scenario} onChange={(e) => setScenario(e.target.value)}>
          {scenarios.map((s) => (
            <option key={s.id} value={s.id}>
              {s.id}
              {s.title ? ` — ${s.title}` : ''}
            </option>
          ))}
        </select>
        <span className="secondary">
          Трассы, зоны обслуживания, станции и ячейки спроса — CZML-потоком с сервера.
        </span>
        <span className="mono secondary" style={{ marginLeft: 'auto' }}>
          {error ? `ошибка: ${error}` : status}
        </span>
      </div>
      {notice ? (
        <div className="empty">{notice}</div>
      ) : (
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 340px', minHeight: 0 }}>
          <div ref={container} style={{ minHeight: 0, background: '#10141a' }} />
          <div style={{ overflow: 'auto', borderLeft: '1px solid var(--border)' }}>
            <h3 style={{ fontSize: 13, margin: '8px 8px 4px' }}>Расписание пролётов</h3>
            <p className="secondary" style={{ margin: '0 8px 6px', fontSize: 12 }}>
              Строка подсвечивается синхронно со шкалой; выбор строки ведёт время к началу окна.
              {view?.passes_truncated &&
                ` Показаны первые ${view.passes.length} из ${view.passes_total} окон.`}
            </p>
            <table>
              <thead>
                <tr>
                  <th>Аппарат</th>
                  <th>Цель</th>
                  <th>Начало</th>
                  <th>Конец</th>
                  <th style={{ textAlign: 'right' }}>Длит.</th>
                </tr>
              </thead>
              <tbody>
                {view?.passes.map((p, i) => (
                  <tr
                    key={`${p.spacecraft_ref}-${p.target_ref}-${p.start_utc}`}
                    onClick={() => seekTo(p.start_utc)}
                    style={{
                      cursor: 'pointer',
                      background: i === activeRow ? 'rgba(11,95,255,0.18)' : undefined,
                      // вне зоны обслуживания пролёт есть, а сервиса нет — строка гаснет
                      opacity: p.in_service_zone ? 1 : 0.45,
                    }}
                    title={p.in_service_zone ? undefined : 'видимость есть, зона обслуживания не достигается'}
                  >
                    <td className="mono">{p.spacecraft_ref}</td>
                    <td className="mono">{p.target_ref}</td>
                    <td className="num">{fmtTime(p.start_utc)}</td>
                    <td className="num">{fmtTime(p.end_utc)}</td>
                    <td className="num">{fmtMin(p.duration_s)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  )
}
