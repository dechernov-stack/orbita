// Карта покрытия (шаг 16 §2.2): среднее и худшее окно доступности по ячейкам
// карты спроса на выбранном горизонте усреднения.
//
// Клиент красит и подписывает. Класс ячейки, пороги, средние и суточное
// взвешивание профилем активности посчитаны сервером (ловушка 2): второй
// расчёт в клиенте разошёлся бы с первым молча.
//
// Пустые состояния — рабочие: сервер отвечает 409 с текстом, адресованным
// инженеру, и шагом мастера, где заводится недостающее. Экран этот текст
// показывает, а не превращает в «ошибку».
import { useEffect, useState } from 'react'
import { api, ApiError } from '../api/client'
import { edit, type StoredSummary } from '../api/edit'
import type { CoverageView } from '../api/types'

type Horizon = 'orbit' | 'day' | 'run'

const HORIZON_LABEL: Record<Horizon, string> = {
  orbit: 'виток',
  day: 'сутки',
  run: 'весь прогон',
}

/** Раскраска по классу с сервера — единственное, что решает клиент. */
const CLASS_COLOR: Record<string, string> = {
  ok: '#3fb27f',
  degraded: '#ffd166',
  gap: '#ff5964',
}

const CLASS_LABEL: Record<string, string> = {
  ok: 'ровное',
  degraded: 'рваное',
  gap: 'провал',
}

const fmtShare = (v: number) => `${(v * 100).toFixed(1)}%`
const fmtMin = (s?: number) => (s === undefined ? '—' : `${(s / 60).toFixed(0)} мин`)

export function Coverage() {
  const [scenarios, setScenarios] = useState<StoredSummary[]>([])
  const [scenario, setScenario] = useState<string>('')
  const [horizon, setHorizon] = useState<Horizon>('day')
  const [view, setView] = useState<CoverageView | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

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
    if (!scenario) return
    setLoading(true)
    setNotice(null)
    setError(null)
    api
      .coverage(scenario, horizon)
      .then((v) => setView(v))
      .catch((e) => {
        setView(null)
        if (e instanceof ApiError && e.status === 409) {
          // рабочее состояние инженера, а не отказ: сервер сказал, чего
          // не хватает и на каком шаге мастера это заводится
          try {
            setNotice(String(JSON.parse(e.message.slice(e.message.indexOf('{'))).error))
          } catch {
            setNotice(e.message)
          }
        } else {
          setError(String(e))
        }
      })
      .finally(() => setLoading(false))
  }, [scenario, horizon])

  if (scenarios.length === 0 && !error) {
    return (
      <div className="empty">
        Сценариев в модели нет: заведите сценарий на Ш5 «Входы моделирования» — карта
        покрытия считается по его ссылкам на группировку и карту спроса.
      </div>
    )
  }

  return (
    <div className="pane" style={{ overflow: 'auto' }}>
      <div style={{ padding: '8px 8px 0', display: 'flex', gap: 8, alignItems: 'center' }}>
        <span className="secondary">Сценарий:</span>
        <select value={scenario} onChange={(e) => setScenario(e.target.value)}>
          {scenarios.map((s) => (
            <option key={s.id} value={s.id}>
              {s.id}
              {s.title ? ` — ${s.title}` : ''}
            </option>
          ))}
        </select>
        <span className="secondary">Горизонт:</span>
        {(Object.keys(HORIZON_LABEL) as Horizon[]).map((h) => (
          <button key={h} className="tab" aria-selected={horizon === h} onClick={() => setHorizon(h)}>
            {HORIZON_LABEL[h]}
          </button>
        ))}
      </div>

      {error && <div className="warn" style={{ padding: 8 }}>Ошибка: {error}</div>}
      {notice && <div className="empty">{notice}</div>}
      {loading && !view && !notice && <div className="empty">Расчёт покрытия…</div>}

      {view && (
        <div style={{ padding: 12 }}>
          <svg
            viewBox="-180 -90 360 180"
            preserveAspectRatio="none"
            style={{ width: '100%', height: 280, background: '#0d1b2a', border: '1px solid var(--border)' }}
          >
            {[-60, -30, 0, 30, 60].map((lat) => (
              <line key={`lat${lat}`} x1={-180} x2={180} y1={-lat} y2={-lat} stroke="#22384f" strokeWidth={lat === 0 ? 1 : 0.5} />
            ))}
            {[-120, -60, 0, 60, 120].map((lon) => (
              <line key={`lon${lon}`} y1={-90} y2={90} x1={lon} x2={lon} stroke="#22384f" strokeWidth={lon === 0 ? 1 : 0.5} />
            ))}
            {view.cells.map((cell) => (
              <rect
                key={cell.cell_id}
                x={cell.lon_deg}
                y={-cell.lat_deg}
                width={8}
                height={8}
                fill={CLASS_COLOR[cell.class]}
                opacity={cell.class === 'gap' ? 0.9 : 0.35 + 0.6 * cell.availability_mean}
              >
                <title>
                  {cell.cell_id}: среднее {fmtShare(cell.availability_mean)}, худшее окно{' '}
                  {fmtShare(cell.availability_worst)} · {CLASS_LABEL[cell.class]}
                </title>
              </rect>
            ))}
          </svg>
          <p className="secondary" style={{ marginBottom: 0 }}>
            Горизонт «{HORIZON_LABEL[view.horizon]}»: по каждой ячейке — среднее и{' '}
            <b>худшее окно</b>; провалы покрытия среднее по прогону скрывает. Цвет — класс,
            посчитанный сервером:{' '}
            {(['ok', 'degraded', 'gap'] as const).map((c) => (
              <span key={c} style={{ marginRight: 8 }}>
                <span style={{ color: CLASS_COLOR[c] }}>■</span> {CLASS_LABEL[c]}
              </span>
            ))}
          </p>

          <h3 style={{ fontSize: 13 }}>Ячейки</h3>
          <table>
            <thead>
              <tr>
                <th style={{ width: 110 }}>Ячейка</th>
                <th style={{ width: 90 }}>Среднее</th>
                <th style={{ width: 110 }}>Худшее окно</th>
                {horizon === 'day' && <th style={{ width: 130 }}>С проф. активности</th>}
                <th style={{ width: 90 }}>Окон</th>
                <th style={{ width: 110 }}>Макс. разрыв</th>
                <th style={{ width: 120 }}>Повторный обзор</th>
                <th>Класс</th>
              </tr>
            </thead>
            <tbody>
              {view.cells.map((cell) => (
                <tr key={cell.cell_id}>
                  <td className="mono">{cell.cell_id}</td>
                  <td className="num">{fmtShare(cell.availability_mean)}</td>
                  <td className="num">{fmtShare(cell.availability_worst)}</td>
                  {horizon === 'day' && (
                    <td className="num">
                      {cell.availability_weighted === undefined ? '—' : fmtShare(cell.availability_weighted)}
                    </td>
                  )}
                  <td className="num">{cell.access_windows}</td>
                  <td className="num">{fmtMin(cell.max_gap_s)}</td>
                  <td className="num">{fmtMin(cell.revisit_s)}</td>
                  <td>
                    <span style={{ color: CLASS_COLOR[cell.class] }}>■</span> {CLASS_LABEL[cell.class]}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
