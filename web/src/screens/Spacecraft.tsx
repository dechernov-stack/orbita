// Экран 5 — модель космического аппарата: ВИД УЗЛА КА дерева состава
// (ADR-044). Отдельной сущности «модель аппарата» больше нет: контракт
// собирается сервером из поддерева узла (платформа · ПН · подсистемы),
// величины — параметры узлов по анкетам Ф-06, и экран показывает, из каких
// узлов сложился аппарат и чего дереву не хватает.
//
// Ведомость масс и циклограмма — часть модели (CR-006, CR-007), а не поля
// экрана. Экран задаёт только УСЛОВИЯ ОЦЕНКИ: высоту, заявленную скважность
// полезной нагрузки. Раньше ведомость вводилась здесь и никуда не сохранялась,
// а виток делился поровну — правдоподобное число, которое ни о чём не говорит.
//
// Все бюджеты считает сервер вызовами core/ka: клиент не складывает массы
// и не сравнивает запас с нулём — это правила, а не отрисовка.
import { useEffect, useState } from 'react'
import { api, ApiError } from '../api/client'
import { DataRequests } from './DataRequests'
import type { CompositionCarrier, MaskScheduleView, ProtocolAdapterView, SpacecraftView } from '../api/types'

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

// Зашитого идентификатора аппарата нет (шаг 16 §3.2): по умолчанию берётся
// первый узел КА проекта, выбор — из узлов КА дерева состава.
export function Spacecraft({ spacecraftId, onGo }: {
  spacecraftId?: string
  onGo?: (screen: string, kind?: string, target?: string) => void
}) {
  const [altKm, setAltKm] = useState(550)
  const [plannedDuty, setPlannedDuty] = useState(0.5)
  const [view, setView] = useState<SpacecraftView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [masks, setMasks] = useState<MaskScheduleView | null>(null)
  const [masksNotice, setMasksNotice] = useState<string | null>(null)
  const [adapter, setAdapter] = useState<ProtocolAdapterView | null>(null)
  const [carriers, setCarriers] = useState<CompositionCarrier[]>([])
  const [spId, setSpId] = useState<string | undefined>(spacecraftId)
  const [assembly, setAssembly] = useState<{ problems: string[]; nodes: string[]; computed?: string[] } | null>(null)

  useEffect(() => {
    api
      .compositionTree()
      .then((t) => {
        setCarriers(t.carriers)
        if (!spacecraftId && t.carriers.length > 0) setSpId((cur) => cur ?? t.carriers[0].id)
      })
      .catch((e) => setError(String(e)))
  }, [spacecraftId])

  // из каких узлов собран контракт и чего не хватает — до расчёта: расчёт по
  // несобранному невозможен, и экран называет причину, а не молчит
  useEffect(() => {
    if (!spId) return
    api.spacecraftAssembly(spId).then(setAssembly).catch((e) => setError(String(e)))
  }, [spId])

  // Циклограмма из масок и параметры канала (шаг 16 §2.4): считает сервер,
  // подстановка сгенерированных долей в модель — решение инженера, не автоматика
  useEffect(() => {
    api
      .maskSchedule()
      .then(setMasks)
      .catch((e) => {
        if (e instanceof ApiError && e.status === 404) setMasksNotice(e.message.slice(0, 200))
        else setError(String(e))
      })
    api.protocolAdapter().then(setAdapter).catch((e) => setError(String(e)))
  }, [])

  useEffect(() => {
    if (!spId) return
    setError(null)
    api
      .spacecraftStored(spId, { alt_km: altKm, planned_payload_duty: plannedDuty })
      .then(setView)
      .catch((e) => setError(String(e)))
  }, [spId, altKm, plannedDuty])

  if (error) return <div className="empty">Ошибка обращения к API: {error}</div>
  if (carriers.length === 0)
    return (
      <div className="empty">
        Узлов КА в дереве состава нет: модель аппарата — вид узла с ролью «КА»; заведите его
        на «Дереве состава» с платформой и полезной нагрузкой как поддеревом.
      </div>
    )

  return (
    <div className="split">
      <div className="pane" style={{ padding: 12 }}>
        {/* Ф-06: библиотека сама называет потребные данные — до того, как
            инженер начнёт гадать, что вводить */}
        <DataRequests onGo={onGo} />
        <div className="tabs" style={{ marginBottom: 8, alignItems: 'center' }}>
          <span className="secondary">узел КА</span>
          <select value={spId ?? ''} onChange={(e) => setSpId(e.target.value)}>
            {carriers.map((c) => (
              <option key={c.id} value={c.id}>{c.id} · {c.name}</option>
            ))}
          </select>
          {onGo && spId && (
            <button type="button" className="tab" title="К узлу в дереве состава" onClick={() => onGo('composition', 'component', spId)}>
              в дереве состава
            </button>
          )}
          {view?.preset && <span className="chip">{view.preset}</span>}
          <span className="secondary" style={{ marginLeft: 12 }}>
            высота, км
          </span>
          <input
            type="number"
            value={altKm}
            onChange={(e) => setAltKm(Number(e.target.value))}
            style={{ width: 80 }}
          />
          <span className="secondary" style={{ marginLeft: 12 }}>
            заявленная скважность ПН
          </span>
          <input
            type="number"
            step="0.05"
            value={plannedDuty}
            onChange={(e) => setPlannedDuty(Number(e.target.value))}
            style={{ width: 80 }}
          />
        </div>

        {assembly && (
          <div className="card" style={{ marginBottom: 8 }}>
            <div className="secondary">
              Собрано из узлов: {assembly.nodes.map((n) => <span key={n} className="mono" style={{ marginRight: 6 }}>{n}</span>)}
            </div>
            {(assembly.computed?.length ?? 0) > 0 && (
              <div className="secondary" style={{ marginTop: 4 }}>
                Вычислено сборкой: {assembly.computed!.map((c) => <div key={c}>{c}</div>)}
              </div>
            )}
            {assembly.problems.length > 0 && (
              <ul style={{ margin: '4px 0 0 16px', padding: 0 }}>
                {assembly.problems.map((p) => <li key={p}>{p}</li>)}
              </ul>
            )}
          </div>
        )}
        {!view ? (
          <div className="secondary">
            {assembly && assembly.problems.length > 0 ? 'Расчёта нет: дерево не собирается в модель аппарата (см. претензии выше).' : 'Загрузка…'}
          </div>
        ) : (
          <>
            <div className="card">
              <h3>Платформа · ведомость масс</h3>
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
                        <td>
                          <span className="truncate">{item.name}</span>
                        </td>
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
                  <div className="secondary">
                    Считается при заявленной скважности: при допустимой он ноль по построению.
                    Доли витка режимов заданы моделью, а не делятся поровну.
                  </div>
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
            {view?.beacon ? (
              <>
                <div className="field">
                  <label>Формат</label>
                  {view.beacon.format}
                </div>
                <div className="field">
                  <label>Период, с</label>
                  <span className="mono">{view.beacon.periodS}</span>
                </div>
                <div className="field">
                  <label>Объём кадра, байт</label>
                  <span className="mono">{view.beacon.payloadBytes}</span>
                </div>
                <div className="field">
                  <label>Занятость нисходящей линии</label>
                  <span className="mono">{view.beacon.downlinkLoad}</span>
                </div>
              </>
            ) : (
              <span className="warn">△ маяк в модели не задан</span>
            )}
          </div>
        </div>

        <div className="card">
          <h3>Циклограмма из масок</h3>
          <div>
            <p className="secondary">
              TZ-KA-009 (Р4): маски — из хранимых карты спроса и станций, доли витка —
              по трассе. Подстановка в модель — решение инженера.
            </p>
            {masksNotice && <div className="secondary">{masksNotice}</div>}
            {masks && (
              <table>
                <thead>
                  <tr>
                    <th>Режим</th>
                    <th style={{ textAlign: 'right' }}>Из масок</th>
                    <th style={{ textAlign: 'right' }}>В модели</th>
                  </tr>
                </thead>
                <tbody>
                  {Object.entries(masks.generated_orbit_fractions).map(([mode, f]) => (
                    <tr key={mode}>
                      <td className="mono">{mode}</td>
                      <td className="num">{(f * 100).toFixed(1)}%</td>
                      <td className="num">
                        {masks.model_orbit_fractions?.[mode] !== undefined
                          ? `${(masks.model_orbit_fractions[mode] * 100).toFixed(1)}%`
                          : '—'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            {masks && (
              <p className="secondary" style={{ marginBottom: 0 }}>
                Маска <span className="mono">{masks.mask_version}</span>: приём{' '}
                {masks.rx_cells} яч., сброс {masks.downlink_cells} ст.
              </p>
            )}
          </div>
        </div>

        <div className="card">
          <h3>Канал (адаптер)</h3>
          <div>
            <p className="secondary">
              Параметры канала отдаёт только адаптер (TZ-NET-001): вторая копия чисел
              разошлась бы с расчётом потоков.
            </p>
            {adapter && (
              <>
                <div className="field">
                  <label>Адаптер</label>
                  <span className="mono">{adapter.id}</span> · {adapter.name} ·{' '}
                  {adapter.phy.modulation}
                </div>
                <table>
                  <thead>
                    <tr>
                      <th>Режим</th>
                      <th style={{ textAlign: 'right' }}>бит/с</th>
                      <th style={{ textAlign: 'right' }}>Eb/N0, дБ</th>
                    </tr>
                  </thead>
                  <tbody>
                    {adapter.phy.modes.map((m) => (
                      <tr key={m.mode_id}>
                        <td className="mono">{m.mode_id}</td>
                        <td className="num">{m.bitrate_bps}</td>
                        <td className="num">{m.required_ebn0_db.toFixed(2)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </>
            )}
          </div>
        </div>

        <div className="card">
          <h3>Что задаёт модель, а что экран</h3>
          <div className="secondary">
            Ведомость масс, циклограмма режимов с долями витка, радиолинии и маяк — часть
            хранимой модели аппарата: они переживают уход с экрана и попадают в пакет
            передачи. Экран задаёт условия оценки — высоту и заявленную скважность.
          </div>
        </div>
      </aside>
    </div>
  )
}
