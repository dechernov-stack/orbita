// Экран 6 — баллистика: глобус с трассами группировки.
//
// Собственной модели движения в клиенте НЕТ (STEP-7-9 §8.1): траектории
// приходят CZML-потоком с сервера, из того же пропагатора, который считает
// видимость. Cesium здесь — средство отображения, а не расчёта.
import { useEffect, useRef, useState } from 'react'
import {
  Color,
  CzmlDataSource,
  Ion,
  JulianDate,
  Viewer,
} from 'cesium'
import 'cesium/Build/Cesium/Widgets/widgets.css'
import { api } from '../api/client'

export function Globe() {
  const container = useRef<HTMLDivElement>(null)
  const [error, setError] = useState<string | null>(null)
  const [status, setStatus] = useState('загрузка трасс…')

  useEffect(() => {
    if (!container.current) return
    let viewer: Viewer | undefined
    let cancelled = false

    // Токен Ion не задан: используется база без внешних тайлов. Ключей
    // в репозитории нет и быть не должно.
    Ion.defaultAccessToken = ''

    const start = async () => {
      try {
        const czml = await api.globe()
        if (cancelled || !container.current) return
        viewer = new Viewer(container.current, {
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
        viewer.scene.globe.baseColor = Color.fromCssColorString('#1b3a5c')
        viewer.scene.globe.showGroundAtmosphere = true

        const source = await CzmlDataSource.load(czml as object[])
        await viewer.dataSources.add(source)
        viewer.clock.currentTime = JulianDate.clone(viewer.clock.startTime)
        viewer.clock.shouldAnimate = true
        await viewer.zoomTo(source)
        setStatus(`трасс: ${source.entities.values.length}`)
      } catch (e) {
        setError(String(e))
      }
    }
    void start()

    return () => {
      cancelled = true
      viewer?.destroy()
    }
  }, [])

  return (
    <div style={{ display: 'grid', gridTemplateRows: 'auto 1fr', minHeight: 0 }}>
      <div className="topbar" style={{ borderTop: '1px solid var(--border)' }}>
        <span className="secondary">
          Трассы приходят CZML-потоком с сервера; собственной модели движения в клиенте нет.
        </span>
        <span className="mono secondary">{error ? `ошибка: ${error}` : status}</span>
      </div>
      <div ref={container} style={{ minHeight: 0, background: '#10141a' }} />
    </div>
  )
}
