// Экран 7 — сравнение вариантов построения: роза KPI, Парето, оси.
//
// Нормировку считает сервер. Соблазн пересчитать её здесь велик — но именно
// так и появилась бы вторая реализация правила направления показателя, и
// диаграмма нарисовала бы дорогой вариант хорошим (STEP-7-9, ловушка 2).
import { useEffect, useState } from 'react'
import { api, ApiError } from '../api/client'
import { edit, type StoredSummary } from '../api/edit'
import type { ComparisonView, StaleResultRow } from '../api/types'

const SIZE = 260
const CENTER = SIZE / 2
const RADIUS = SIZE / 2 - 30
const PALETTE = ['#0b5fff', '#1a7f37', '#bf8700']

export function Comparison() {
  const [scenarios, setScenarios] = useState<StoredSummary[]>([])
  const [scenario, setScenario] = useState('')
  const [view, setView] = useState<ComparisonView | null>(null)
  const [stale, setStale] = useState<StaleResultRow[]>([])
  const [notice, setNotice] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  // Сценарий выбирается из хранимых, не зашивается (шаг 16 §3.2)
  useEffect(() => {
    edit
      .list('scenario')
      .then((rows) => {
        setScenarios(rows)
        if (rows.length > 0) setScenario((cur) => cur || rows[0].id)
      })
      .catch((e) => setError(String(e)))
    // Пометка «результат устарел» живёт здесь (шаг 16 §2.4, TZ-MOD-007):
    // устаревший результат в сравнение не входит, но исчезнуть молча не должен
    api.staleResults().then(setStale).catch((e) => setError(String(e)))
  }, [])

  useEffect(() => {
    if (!scenario) return
    setNotice(null)
    setError(null)
    api
      .comparison(scenario)
      .then(setView)
      .catch((e) => {
        setView(null)
        if (e instanceof ApiError && e.status === 409) {
          // «вариантов меньше двух» — рабочее состояние инженера, не отказ
          try {
            setNotice(String(JSON.parse(e.message.slice(e.message.indexOf('{'))).error))
          } catch {
            setNotice(e.message)
          }
        } else {
          setError(String(e))
        }
      })
  }, [scenario])

  const staleHere = stale.filter((r) => r.scenario_id === scenario)

  if (scenarios.length === 0 && !error) {
    return (
      <div className="empty">
        Сценариев в модели нет: заведите сценарий на Ш5 «Входы моделирования».
      </div>
    )
  }
  if (error) return <div className="empty">Ошибка обращения к API: {error}</div>

  const selector = (
    <div className="pane__tools">
      <span className="secondary">Сценарий:</span>
      <select value={scenario} onChange={(e) => setScenario(e.target.value)}>
        {scenarios.map((s) => (
          <option key={s.id} value={s.id}>{s.id}{s.title ? ` — ${s.title}` : ''}</option>
        ))}
      </select>
      {staleHere.length > 0 && (
        <span className="warn">
          результаты устарели: {staleHere.length} — входы изменились после расчёта, пересчитайте
        </span>
      )}
    </div>
  )

  if (notice)
    return (
      <div className="pane">
        {selector}
        <div className="empty">{notice}</div>
      </div>
    )
  if (!view)
    return (
      <div className="pane">
        {selector}
        <div className="empty">Загрузка…</div>
      </div>
    )

  return (
    <div className="split">
      <div className="pane" style={{ padding: 16 }}>
        {selector}
        <h2 style={{ fontSize: 15, marginTop: 0 }}>Роза KPI</h2>
        <Radar view={view} />
        <p className="secondary" style={{ maxWidth: 560 }}>
          Значения нормированы по набору{' '}
          <span className="mono">{view.radar.normalizedOver.join(' · ')}</span>. Диаграммы,
          построенные по разным наборам, несопоставимы: удаление крайнего варианта смещает
          значения промежуточных.
        </p>

        <h2 style={{ fontSize: 15 }}>Варианты</h2>
        <table>
          <thead>
            <tr>
              <th>Вариант</th>
              {view.axes.map((a) => (
                <th key={a} style={{ width: 120, textAlign: 'right' }}>
                  {a}
                </th>
              ))}
              <th style={{ width: 120 }}>Парето</th>
            </tr>
          </thead>
          <tbody>
            {view.options.map((option) => (
              <tr key={option.name}>
                <td>{option.name}</td>
                {view.axes.map((a) => (
                  <td key={a} className="num">
                    {option.values[a]}
                  </td>
                ))}
                <td>
                  {view.paretoFront.includes(option.name) ? (
                    <span className="chip">недоминируем</span>
                  ) : (
                    <span className="secondary">доминируется</span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <aside className="pane pane--side">
        <div className="card">
          <h3>Фронт Парето</h3>
          <div>
            {view.paretoFront.map((name) => (
              <div key={name} className="field">
                <span className="chip">{name}</span>
              </div>
            ))}
            <p className="secondary">
              Направление показателя учтено: стоимость и срок развёртывания — «меньше лучше».
            </p>
          </div>
        </div>
      </aside>
    </div>
  )
}

/** Лепестковая диаграмма по УЖЕ нормированным значениям: клиент их не считает. */
function Radar({ view }: { view: ComparisonView }) {
  const axes = view.radar.axes
  const angle = (i: number) => (Math.PI * 2 * i) / axes.length - Math.PI / 2
  // fraction, а не value: это доля радиуса для отрисовки, а не величина модели.
  // Совпадение имени с полем модели читалось бы как расчёт над ним.
  const point = (i: number, fraction: number) => {
    const r = RADIUS * fraction
    return `${CENTER + r * Math.cos(angle(i))},${CENTER + r * Math.sin(angle(i))}`
  }

  return (
    <svg width={SIZE} height={SIZE} role="img" aria-label="Роза KPI">
      {[0.25, 0.5, 0.75, 1].map((ring) => (
        <polygon
          key={ring}
          points={axes.map((_, i) => point(i, ring)).join(' ')}
          fill="none"
          stroke="var(--border)"
        />
      ))}
      {axes.map((axisName, i) => (
        <g key={axisName}>
          <line
            x1={CENTER}
            y1={CENTER}
            x2={CENTER + RADIUS * Math.cos(angle(i))}
            y2={CENTER + RADIUS * Math.sin(angle(i))}
            stroke="var(--border)"
          />
          <text
            x={CENTER + (RADIUS + 16) * Math.cos(angle(i))}
            y={CENTER + (RADIUS + 16) * Math.sin(angle(i))}
            textAnchor="middle"
            fontSize="11"
            fill="var(--text-secondary)"
          >
            {axisName}
          </text>
        </g>
      ))}
      {view.radar.series.map((s, si) => (
        <polygon
          key={s.name}
          points={s.values.map((v, i) => point(i, v)).join(' ')}
          fill={PALETTE[si % PALETTE.length]}
          fillOpacity="0.12"
          stroke={PALETTE[si % PALETTE.length]}
          strokeWidth="1.5"
        />
      ))}
    </svg>
  )
}
