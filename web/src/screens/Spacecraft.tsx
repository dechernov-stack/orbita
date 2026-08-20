// Экран 5 — модель космического аппарата (Ш4 мастера).
//
// Экран задаёт модель, бюджеты считает сервер: масса с резервами по зрелости,
// энергобаланс худшего витка, запасы радиолиний, маяк, TPM. Клиент не
// складывает массы и не сравнивает запас с нулём — это правила (TZ-KA-002,
// TZ-KA-004, TZ-KA-005, TZ-KA-010), а не отрисовка.
//
// Скорость и требуемое Eb/N0 линий на экране не задаются: они приходят из
// адаптера протокола (TZ-NET-001). Иначе бюджет считался бы по произвольным
// цифрам, и «запас 6 дБ» ничего не значил бы.
import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { PresetRow, SpacecraftView } from '../api/types'

interface LinkDraft {
  id: string
  role: string
  band_hz: number
  tx_power_w: number
  g_over_t_db_k: number
  gain_dbi: number
  required_margin_db: number
}

interface MassDraft {
  name: string
  mass_kg: number
  maturity: string
}

const ROLE_LABEL: Record<string, string> = {
  user_uplink: 'Абонентская вверх',
  user_downlink: 'Абонентская вниз',
  feeder_uplink: 'Фидерная вверх',
  feeder_downlink: 'Фидерная вниз',
  isl: 'Межспутниковая',
  ttc: 'Командно-телеметрическая',
}

const MATURITY_LABEL: Record<string, string> = {
  new: 'новый',
  modified: 'доработанный',
  existing: 'существующий',
}

const INITIAL_LINKS: LinkDraft[] = [
  {
    id: 'RL-UP',
    role: 'user_uplink',
    band_hz: 868e6,
    tx_power_w: 0.1,
    g_over_t_db_k: -18,
    gain_dbi: 6,
    required_margin_db: 3,
  },
  {
    id: 'RL-DN',
    role: 'user_downlink',
    band_hz: 868e6,
    tx_power_w: 2,
    g_over_t_db_k: -22,
    gain_dbi: 6,
    required_margin_db: 3,
  },
]

const INITIAL_MASS: MassDraft[] = [
  { name: 'Конструкция', mass_kg: 8, maturity: 'existing' },
  { name: 'СЭП', mass_kg: 6, maturity: 'modified' },
  { name: 'СОС', mass_kg: 4, maturity: 'existing' },
  { name: 'БКУ', mass_kg: 3, maturity: 'existing' },
  { name: 'Полезная нагрузка', mass_kg: 9, maturity: 'new' },
]

export function Spacecraft() {
  const [presets, setPresets] = useState<PresetRow[]>([])
  const [presetId, setPresetId] = useState<string | null>(null)
  const [links, setLinks] = useState<LinkDraft[]>(INITIAL_LINKS)
  const [mass, setMass] = useState<MassDraft[]>(INITIAL_MASS)
  const [beaconPeriodS, setBeaconPeriodS] = useState(60)
  const [beaconFormat, setBeaconFormat] = useState('orbit_model')
  const [altKm, setAltKm] = useState(550)
  const [plannedDuty, setPlannedDuty] = useState(0.5)
  const [view, setView] = useState<SpacecraftView | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api
      .platformPresets()
      .then((rows) => {
        setPresets(rows)
        setPresetId((current) => current ?? rows[0]?.id ?? null)
      })
      .catch((e) => setError(String(e)))
  }, [])

  useEffect(() => {
    const preset = presets.find((p) => p.id === presetId)
    if (!preset) return
    setError(null)
    api
      .spacecraft({
        spacecraft: {
          id: 'SC-DEMO',
          preset: preset.id,
          platform: {
            dry_mass_kg: preset.dryMassKg,
            power: {
              sa_area_m2: preset.saAreaM2,
              sa_efficiency: 0.29,
              battery_wh: preset.batteryWh,
            },
            attitude: { pointing_accuracy_deg: 1 },
            design_life_years: preset.designLifeYears,
          },
          payload: {
            architecture: 'regenerative',
            links: links.map((l) => ({
              id: l.id,
              role: l.role,
              band_hz: l.band_hz,
              tx_power_w: l.tx_power_w,
              g_over_t_db_k: l.g_over_t_db_k,
              required_margin_db: l.required_margin_db,
              antenna: { type: 'patch', gain_dbi: l.gain_dbi },
            })),
            onboard: { buffer_mb: 64, priority_policy: ['C_prime', 'B_prime', 'A_prime'] },
            ephemeris_beacon: {
              enabled: true,
              period_s: beaconPeriodS,
              format: beaconFormat,
            },
          },
        },
        mass_items: mass,
        conditions: { alt_km: altKm, planned_payload_duty: plannedDuty },
      })
      .then(setView)
      .catch((e) => setError(String(e)))
  }, [presets, presetId, links, mass, beaconPeriodS, beaconFormat, altKm, plannedDuty])

  return (
    <div className="split">
      <div className="pane" style={{ padding: 12 }}>
        <div className="tabs" style={{ marginBottom: 8 }}>
          {presets.map((p) => (
            <button
              key={p.id}
              className="tab"
              aria-selected={p.id === presetId}
              onClick={() => setPresetId(p.id)}
            >
              {p.name}
            </button>
          ))}
          <span className="secondary" style={{ marginLeft: 12 }}>
            высота, км
          </span>
          <input
            type="number"
            value={altKm}
            onChange={(e) => setAltKm(Number(e.target.value))}
            style={{ width: 80 }}
          />
        </div>

        {error && <div className="warn">Ошибка: {error}</div>}
        {!view ? (
          <div className="secondary">Загрузка…</div>
        ) : (
          <>
            <div className="card">
              <h3>Платформа · массовый бюджет</h3>
              <div>
                <table>
                  <thead>
                    <tr>
                      <th>Элемент</th>
                      <th style={{ width: 90, textAlign: 'right' }}>Масса, кг</th>
                      <th style={{ width: 130 }}>Зрелость</th>
                      <th style={{ width: 90, textAlign: 'right' }}>Резерв, %</th>
                      <th style={{ width: 110, textAlign: 'right' }}>С резервом</th>
                    </tr>
                  </thead>
                  <tbody>
                    {view.mass.items.map((item) => (
                      <tr key={item.name}>
                        <td>{item.name}</td>
                        <td className="num">{item.massKg}</td>
                        <td className="secondary">
                          {MATURITY_LABEL[item.maturity.toLowerCase()] ?? item.maturity}
                        </td>
                        <td className="num">{item.marginPct}</td>
                        <td className="num">{item.withMarginKg}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <div className="field">
                  <label>Системный резерв, %</label>
                  <span className="mono">{view.mass.systemMarginPct}</span>
                </div>
                <div className="field">
                  <label>Сухая масса, кг</label>
                  <span className={`mono${view.mass.withinPlatformRange ? '' : ' warn'}`}>
                    {view.mass.dryMassKg}
                  </span>
                </div>
                <div className="field">
                  <label>Заправленная масса, кг</label>
                  <span className="mono">{view.mass.wetMassKg}</span>
                  <span className="secondary"> при ΔV {view.mass.deltaVMs} м/с</span>
                </div>
              </div>
            </div>

            <div className="card">
              <h3>Энергетика худшего витка</h3>
              <div>
                <div className="field">
                  <label>Генерация / потребление, Вт·ч</label>
                  <span className="mono">
                    {view.power.generatedWh} / {view.power.consumedWh}
                  </span>
                </div>
                <div className="field">
                  <label>Баланс, Вт·ч</label>
                  <span className={`mono${view.power.balanceOk ? '' : ' warn'}`}>
                    {view.power.balanceWh}
                  </span>
                </div>
                <div className="field">
                  <label>Маяк, Вт·ч за виток</label>
                  <span className="mono">{view.power.beaconWh}</span>
                </div>
                <div className="field">
                  <label>Скважность ПН: заявленная / допустимая</label>
                  <span className={`mono${view.power.dutyOk ? '' : ' warn'}`}>
                    {view.power.plannedPayloadDuty} / {view.power.allowedPayloadDuty}
                  </span>
                  <div className="secondary">
                    Баланс считается при заявленной: при допустимой он ноль по построению.
                  </div>
                </div>
                <div className="field">
                  <label>Заявленная скважность ПН</label>
                  <input
                    type="number"
                    step="0.05"
                    value={plannedDuty}
                    onChange={(e) => setPlannedDuty(Number(e.target.value))}
                    style={{ width: 80 }}
                  />
                </div>
                <div className="field">
                  <label>Глубина разряда АБ</label>
                  <span className={`mono${view.power.dodOk ? '' : ' warn'}`}>
                    {view.power.batteryDod}
                  </span>
                  <span className="secondary"> предел {view.power.batteryMaxDod}</span>
                </div>
              </div>
            </div>

            <div className="card">
              <h3>Полезная нагрузка · радиолинии</h3>
              <div>
                <table>
                  <thead>
                    <tr>
                      <th style={{ width: 170 }}>Назначение</th>
                      <th style={{ width: 110, textAlign: 'right' }}>ЭИИМ, дБВт</th>
                      <th style={{ width: 110, textAlign: 'right' }}>Запас в надире</th>
                      <th style={{ width: 130, textAlign: 'right' }}>Запас на границе</th>
                      <th style={{ width: 110, textAlign: 'right' }}>Угол зоны</th>
                      <th>Что ограничивает</th>
                    </tr>
                  </thead>
                  <tbody>
                    {view.links.map((link) => (
                      <tr key={link.id}>
                        <td>
                          <span className="id">{link.id}</span>{' '}
                          <span className="secondary">{ROLE_LABEL[link.role] ?? link.role}</span>
                        </td>
                        <td className="num">{link.eirpDbw}</td>
                        <td className="num">{link.marginAtZenithDb}</td>
                        <td className={`num${link.closes ? '' : ' warn'}`}>
                          {link.marginAtMinElevDb}
                        </td>
                        <td className="num">{link.serviceElevationDeg ?? '—'}</td>
                        <td className="secondary">
                          {link.limitingFactor === 'geometry'
                            ? 'геометрия (маска)'
                            : link.limitingFactor === 'link_margin'
                              ? 'запас линии'
                              : link.limitingFactor}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <p className="secondary">
                  Скорость {view.links[0]?.bitrateBps ?? '—'} бит/с и требуемое Eb/N0 берутся из
                  адаптера протокола, а не задаются здесь.
                </p>
              </div>
            </div>

            <div className="card">
              <h3>TPM</h3>
              <div>
                <table>
                  <thead>
                    <tr>
                      <th>Параметр</th>
                      <th style={{ width: 120, textAlign: 'right' }}>Текущее</th>
                      <th style={{ width: 110, textAlign: 'right' }}>Цель</th>
                      <th style={{ width: 110, textAlign: 'right' }}>Резерв, %</th>
                      <th style={{ width: 130 }}>Состояние</th>
                    </tr>
                  </thead>
                  <tbody>
                    {view.tpm.map((tpm) => (
                      <tr key={tpm.name}>
                        <td>{tpm.name}</td>
                        <td className="num">
                          {tpm.current} <span className="secondary">{tpm.unit}</span>
                        </td>
                        <td className="num">{tpm.target}</td>
                        <td className={`num${tpm.breached ? ' warn' : ''}`}>{tpm.marginPct}</td>
                        <td>
                          {tpm.breached ? (
                            <span className="warn">△ вне требуемого резерва</span>
                          ) : (
                            <span className="secondary">в допуске</span>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          </>
        )}
      </div>

      <aside className="pane pane--side">
        {view && view.issues.length > 0 && (
          <div className="card">
            <h3>Замечания модели</h3>
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
          <h3>Маяк эфемерид</h3>
          <div>
            <p className="secondary">
              Р5/ADR-005: маяк обязателен. Его энергия входит в циклограмму слагаемым, а не
              опцией — иначе скважность ПН получилась бы завышенной.
            </p>
            <div className="field">
              <label>Период, с</label>
              <input
                type="number"
                value={beaconPeriodS}
                onChange={(e) => setBeaconPeriodS(Number(e.target.value))}
              />
            </div>
            <div className="field">
              <label>Формат</label>
              <div className="tabs">
                {['pass_schedule', 'full_almanac', 'orbit_model'].map((f) => (
                  <button
                    key={f}
                    className="tab"
                    aria-selected={beaconFormat === f}
                    onClick={() => setBeaconFormat(f)}
                  >
                    {f === 'pass_schedule'
                      ? 'расписание'
                      : f === 'full_almanac'
                        ? 'альманах'
                        : 'модель орбиты'}
                  </button>
                ))}
              </div>
            </div>
            {view?.beacon && (
              <>
                <div className="field">
                  <label>Объём кадра, байт</label>
                  <span className="mono">{view.beacon.payloadBytes}</span>
                </div>
                <div className="field">
                  <label>Занятость нисходящей линии</label>
                  <span className="mono">{view.beacon.downlinkLoad}</span>
                </div>
              </>
            )}
          </div>
        </div>

        <div className="card">
          <h3>Ведомость масс</h3>
          <div>
            {mass.map((item, i) => (
              <div key={item.name} className="field">
                <label>{item.name}</label>
                <input
                  type="number"
                  value={item.mass_kg}
                  onChange={(e) =>
                    setMass((prev) =>
                      prev.map((m, j) => (i === j ? { ...m, mass_kg: Number(e.target.value) } : m)),
                    )
                  }
                  style={{ width: 70 }}
                />
                <div className="tabs">
                  {['new', 'modified', 'existing'].map((m) => (
                    <button
                      key={m}
                      className="tab"
                      aria-selected={item.maturity === m}
                      onClick={() =>
                        setMass((prev) =>
                          prev.map((x, j) => (i === j ? { ...x, maturity: m } : x)),
                        )
                      }
                    >
                      {MATURITY_LABEL[m]}
                    </button>
                  ))}
                </div>
              </div>
            ))}
          </div>
        </div>

        <div className="card">
          <h3>Радиолинии</h3>
          <div>
            {links.map((link, i) => (
              <div key={link.id} className="field">
                <label>{ROLE_LABEL[link.role] ?? link.role}</label>
                <span className="secondary">мощность передатчика, Вт</span>
                <input
                  type="number"
                  step="0.1"
                  value={link.tx_power_w}
                  onChange={(e) =>
                    setLinks((prev) =>
                      prev.map((l, j) =>
                        i === j ? { ...l, tx_power_w: Number(e.target.value) } : l,
                      ),
                    )
                  }
                  style={{ width: 80 }}
                />
                <span className="secondary">усиление антенны, дБи</span>
                <input
                  type="number"
                  value={link.gain_dbi}
                  onChange={(e) =>
                    setLinks((prev) =>
                      prev.map((l, j) => (i === j ? { ...l, gain_dbi: Number(e.target.value) } : l)),
                    )
                  }
                  style={{ width: 80 }}
                />
              </div>
            ))}
          </div>
        </div>
      </aside>
    </div>
  )
}
