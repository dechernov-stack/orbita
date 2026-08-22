// Экран 9 — готовность к контрольной точке (Ш7 мастера) и сборка пакета
// передачи (шаг 15: пакет — выход всего пути, и получать его надо кнопкой,
// а не обращением к API мимо интерфейса).
//
// Что базировать и до какого статуса — решает сервер по реестру ворот.
// Клиент показывает разрывы, а не вычисляет их.
import { useEffect, useState } from 'react'
import { api } from '../api/client'
import { edit, type StoredSummary } from '../api/edit'
import type { MaturityView, ReadinessView } from '../api/types'

const GATES = ['MCR', 'SRR', 'SDR', 'PDR']

/** Части пакета передачи: состав определяет сервер, клиент их только называет. */
interface PackageSummary {
  parts: Array<{ name: string }>
  bytes: number
  url: string
}

export function Readiness() {
  const [gate, setGate] = useState('SRR')
  const [view, setView] = useState<ReadinessView | null>(null)
  const [maturity, setMaturity] = useState<MaturityView | null>(null)
  const [candidates, setCandidates] = useState<string[] | null>(null)
  const [scenarios, setScenarios] = useState<StoredSummary[]>([])
  const [scenario, setScenario] = useState('')
  const [pkg, setPkg] = useState<PackageSummary | null>(null)
  const [pkgError, setPkgError] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    setView(null)
    setMaturity(null)
    api.readiness(gate).then(setView).catch((e) => setError(String(e)))
    // Зрелость пакета — основная таблица экрана (шаг 16 §2.4, TZ-OUT-003)
    api.maturity(gate).then(setMaturity).catch((e) => setError(String(e)))
  }, [gate])

  useEffect(() => {
    api.reviewCandidates().then(setCandidates).catch((e) => setError(String(e)))
    // Сценарий для пакета передачи выбирается из хранимых (шаг 16 §3.2)
    edit
      .list('scenario')
      .then((rows) => {
        setScenarios(rows)
        if (rows.length > 0) setScenario((cur) => cur || rows[0].id)
      })
      .catch((e) => setError(String(e)))
  }, [])

  if (error) return <div className="empty">Ошибка обращения к API: {error}</div>

  return (
    <div className="split">
      <div className="pane" style={{ padding: 16 }}>
        <h2 style={{ fontSize: 15, marginTop: 0 }}>Готовность к контрольной точке</h2>
        <div className="tabs" style={{ marginBottom: 12 }}>
          {GATES.map((g) => (
            <button key={g} className="tab" aria-selected={g === gate} onClick={() => setGate(g)}>
              {g}
            </button>
          ))}
        </div>

        {!view ? (
          <div className="secondary">Загрузка…</div>
        ) : (
          <>
            <div style={{ display: 'flex', gap: 24, marginBottom: 16 }}>
              <div>
                <div className="secondary">Объектов готово</div>
                <div className="mono" style={{ fontSize: 24 }}>
                  {view.readyObjects} / {view.totalObjects}
                </div>
              </div>
              <div>
                <div className="secondary">Разрывов</div>
                <div className={`mono${view.gaps.length > 0 ? ' warn' : ''}`} style={{ fontSize: 24 }}>
                  {view.gaps.length}
                </div>
              </div>
            </div>

            {view.ready ? (
              <div className="card">
                <h3>Готово</h3>
                <div>Все объекты дошли до статуса, требуемого точкой {view.gate}.</div>
              </div>
            ) : (
              <table>
                <thead>
                  <tr>
                    <th style={{ width: 120 }}>Объект</th>
                    <th style={{ width: 140 }}>Статус сейчас</th>
                    <th style={{ width: 140 }}>Требуется</th>
                    <th>Что сделать</th>
                  </tr>
                </thead>
                <tbody>
                  {view.gaps.map((gap) => (
                    <tr key={gap.id}>
                      <td>
                        <span className="id">{gap.id}</span>
                      </td>
                      <td className="secondary">{gap.actual}</td>
                      <td className="mono">{gap.required}</td>
                      <td className="secondary">
                        перевести из «{gap.actual}» в «{gap.required}»
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            {/* Основная таблица зрелости (шаг 16 §2.4, TZ-OUT-003): разрывы
                по видам, TBD, разрывы трассировки, непокрытые — считает сервер */}
            <h2 style={{ fontSize: 15 }}>Зрелость пакета к {gate}</h2>
            {!maturity ? (
              <div className="secondary">Загрузка отчёта зрелости…</div>
            ) : maturity.ready ? (
              <div className="card">
                <h3>Пакет зрел</h3>
                <div>Разрывов, TBD и непокрытых требований к точке {maturity.gate} нет.</div>
              </div>
            ) : (
              <>
                <div className="warn" style={{ padding: 8 }}>
                  Блокирует: {maturity.blocking.join('; ')}
                </div>
                <table>
                  <thead>
                    <tr>
                      <th style={{ width: 130 }}>Вид</th>
                      <th style={{ width: 110 }}>Объект</th>
                      <th style={{ width: 140 }}>Сейчас</th>
                      <th style={{ width: 140 }}>Требуется</th>
                      <th>Владелец</th>
                    </tr>
                  </thead>
                  <tbody>
                    {Object.entries(maturity.gaps_by_type).flatMap(([type, gaps]) =>
                      gaps.map((g) => (
                        <tr key={`${type}-${g.id}`}>
                          <td>{type}</td>
                          <td className="mono">{g.id}</td>
                          <td className="secondary">{g.actual}</td>
                          <td className="mono">{g.required}</td>
                          <td className="secondary">{g.owner ?? '—'}</td>
                        </tr>
                      )),
                    )}
                    {maturity.open_tbd.map((t) => (
                      <tr key={`tbd-${t.id}`}>
                        <td>TBD/TBR</td>
                        <td className="mono">{t.id}</td>
                        <td className="secondary" colSpan={2}>не закрыто</td>
                        <td className="secondary">{t.owner ?? '—'}</td>
                      </tr>
                    ))}
                    {maturity.trace_breaks.map((id) => (
                      <tr key={`trace-${id}`}>
                        <td>трассировка</td>
                        <td className="mono">{id}</td>
                        <td className="secondary" colSpan={3}>разрыв нити</td>
                      </tr>
                    ))}
                    {maturity.unverified.map((id) => (
                      <tr key={`unv-${id}`}>
                        <td>верификация</td>
                        <td className="mono">{id}</td>
                        <td className="secondary" colSpan={3}>требование не покрыто</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </>
            )}

            {/* Требования, чей источник моложе их самих (шаг 16 §2.4) */}
            {candidates && candidates.length > 0 && (
              <div className="warn" style={{ padding: 8, marginTop: 8 }}>
                К рассмотрению — источник изменился после требования:{' '}
                <span className="mono">{candidates.join(', ')}</span>
              </div>
            )}
          </>
        )}
      </div>

      <aside className="pane pane--side">
        <div className="card">
          <h3>Пакет передачи</h3>
          <div>
            <p className="secondary">
              Выход всего пути: модель, трассировка, матрицы верификации и валидации, реестр
              рисков и отчёт зрелости — одной операцией.
            </p>
            <div className="field">
              <span className="secondary">Сценарий: </span>
              <select value={scenario} onChange={(e) => setScenario(e.target.value)}>
                {scenarios.map((s) => (
                  <option key={s.id} value={s.id}>{s.id}</option>
                ))}
              </select>
            </div>
            <button
              type="button"
              className="tab tab--primary"
              disabled={!scenario}
              onClick={() => void collect(scenario, setPkg, setPkgError)}
            >
              Собрать пакет передачи
            </button>
            {pkgError && <div className="warn" role="alert">{pkgError}</div>}
            {pkg && (
              <div style={{ marginTop: 8 }}>
                <div className="secondary">Частей: {pkg.parts.length}, объём {pkg.bytes} байт</div>
                <table>
                  <thead>
                    <tr>
                      <th>Часть пакета</th>
                    </tr>
                  </thead>
                  <tbody>
                    {pkg.parts.map((part) => (
                      <tr key={part.name}>
                        <td className="wrap">{part.name}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <a className="tab" href={pkg.url} download="orbita-transfer-package.json">
                  Скачать пакет
                </a>
              </div>
            )}
          </div>
        </div>

        <div className="card">
          <h3>Что означает точка</h3>
          <div className="secondary">
            Реестр ворот задаёт, до какого статуса должен дойти каждый вид объекта к контрольной
            точке. Требования к SDR строже, чем к SRR: список разрывов растёт не потому, что
            модель ухудшилась, а потому, что планка выше.
          </div>
        </div>
      </aside>
    </div>
  )
}

/**
 * Сборка пакета: состав частей приходит с сервера, клиент считает только
 * количество записей в каждой части — то, что видно глазом в самом ответе.
 */
async function collect(
  scenario: string,
  setPkg: (p: PackageSummary | null) => void,
  setError: (e: string | null) => void,
) {
  setError(null)
  setPkg(null)
  try {
    // Сценарий обязателен (шаг 16 §3.2); маршрут живёт в слое API
    const text = await api.transferPackage(scenario)
    const body = JSON.parse(text) as Record<string, unknown>
    // Состав пакета называет сервер; клиент перечисляет части и не считает
    // по ним ничего: пересчёт содержимого на экране — вторая реализация
    // правила «что входит в пакет» (STEP-6 §3.2).
    setPkg({
      parts: Object.keys(body).map((name) => ({ name })),
      bytes: new TextEncoder().encode(text).length,
      url: URL.createObjectURL(new Blob([text], { type: 'application/json' })),
    })
  } catch (e) {
    setError(String(e))
  }
}
