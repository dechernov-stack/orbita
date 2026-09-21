// Рабочие поверхности сцен 10–12: технологии, риски и засорение,
// стоимость и сроки.
//
// Правило этих трёх экранов одно: система не считает работу сделанной по
// нажатию. Технология с разрывом TRL САМА рождает пакет созревания и веху;
// риск без срока-точки держит сцену; оценка принимается только диапазоном
// с допущениями.
import { useCallback, useEffect, useState } from 'react'
import {
  api, type ComponentRow, type MaturationRow, type OdaRow,
  type RiskRow, type TechnologyRow, type WbsOffer, type WbsRow,
} from './api'

/**
 * Вехи проекта для срока риска.
 *
 * Истина: `risk.due_point: ref gate` — «срок — любая веха (gate.kind
 * phase|technology)». Перечень в коде («MCR · SRR · SDR · PDR») был вторым
 * списком мимо данных: у проекта свои точки и свои вехи технологий, и риск
 * держится ими. Пусто — значит вех в проекте ещё нет, и это сказано словами.
 */
function useВехи(project: string): { код: string; подпись: string }[] {
  const [вехи, setВехи] = useState<{ код: string; подпись: string }[]>([])
  useEffect(() => {
    api.entities(project, 'gate')
      .then((р) => setВехи(р.items
        .filter((в) => в.status !== 'cancelled')
        .map((в) => ({
          код: в.code,
          подпись: `${в.code}${в.doc.name ? ` · ${String(в.doc.name)}` : ''}`
            + (в.doc.planned_date ? ` · ${String(в.doc.planned_date)}` : ''),
        }))))
      .catch(() => undefined)
  }, [project])
  return вехи
}

/** Сцена 10 — технологии: TRL, разрыв, план созревания. */
export function Technologies({ project }: { project: string }) {
  const [строки, setСтроки] = useState<TechnologyRow[]>([])
  const [созревание, setСозревание] = useState<MaturationRow[]>([])
  const [узлы, setУзлы] = useState<ComponentRow[]>([])
  const [отказ, setОтказ] = useState<string | null>(null)
  const [занято, setЗанято] = useState(false)
  const [поля, setПоля] = useState({
    name: '', component: '', trl_current: 5, trl_required: 6, required_by: '', fallback: '',
  })
  const вехи = useВехи(project)

  const перечитать = useCallback(() => {
    api.technologies(project).then((r) => setСтроки(r.items)).catch((e) => setОтказ(String(e.message ?? e)))
    api.maturation(project).then((r) => setСозревание(r.items)).catch(() => undefined)
    api.components(project).then((r) => setУзлы(r.items)).catch(() => undefined)
  }, [project])

  useEffect(перечитать, [перечитать])

  const завести = () => {
    setЗанято(true); setОтказ(null)
    api.addTechnology(project, поля)
      .then(() => { setПоля({ ...поля, name: '', fallback: '' }); перечитать() })
      .catch((e) => setОтказ(String(e.message ?? e)))
      .finally(() => setЗанято(false))
  }

  return (
    <>
      {отказ && <div className="v2-locked">{отказ}</div>}

      <div className="v2-panel" data-why="работа">
        <h3>Критические технологии<span className="v2-cnt">{строки.length}</span></h3>
        {строки.length === 0 ? (
          <div className="v2-empty">
            Критических технологий не названо.
            <span className="v2-empty__why">
              Критическая — та, без которой миссия не состоится, а её зрелость ниже требуемой.
              Регламент: TRL 6 к PDR.
            </span>
          </div>
        ) : (
          <table className="v2-table">
            <thead>
              <tr>
                <th>Код</th><th>Технология</th><th>Узел</th><th>TRL тек. → треб.</th>
                <th>К точке</th><th>Пакет · веха</th><th>Резерв</th>
              </tr>
            </thead>
            <tbody>
              {строки.map((т) => {
                const разрыв = т.trl_current < т.trl_required
                // План созревания читается В СТРОКЕ технологии, а не в
                // отдельной таблице ниже: разрыв TRL и то, чем он закрыт, —
                // один вопрос, и разводить их по двум таблицам значит
                // заставлять сверять глазами (эталон сцен 10–12).
                const план = созревание.find((с) => с.technology === т.code)
                return (
                  <tr key={т.code}>
                    <td className="v2-mono">{т.code}</td>
                    <td>{т.name}</td>
                    <td>{т.component ?? '—'}</td>
                    <td className={разрыв ? 'v2-warn' : 'v2-ok'}>
                      {т.trl_current} → {т.trl_required}{разрыв ? ' · разрыв' : ' · закрыт'}
                    </td>
                    <td>{т.required_by}</td>
                    <td>
                      {!разрыв ? (
                        <span className="v2-dim" title="TRL достаточен — созревать нечего">не требуется</span>
                      ) : план?.package ? (
                        <>
                          <span className="v2-mono">{план.package}</span>
                          {план.milestone && <span className="v2-dim"> · веха {план.milestone}</span>}
                        </>
                      ) : (
                        <span className="v2-warn"
                          title="разрыв TRL сам рождает пакет созревания и веху при заведении технологии">
                          нет пакета — сцена 12 не закроется
                        </span>
                      )}
                    </td>
                    <td>{т.fallback ?? <span className="v2-dim">не назначен</span>}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        )}
      </div>

      <div className="v2-panel" data-why="следующий-клик">
        <h3>План созревания<span className="v2-cnt">{созревание.filter((с) => с.package).length}</span></h3>
        <div className="v2-empty__why">
          Разрыв TRL рождает пакет работ и веху сам — их остаётся принять, а не заводить руками.
        </div>
        {созревание.map((с) => (
          <div key={с.technology} className="v2-note">
            <span className="v2-note__rule">{с.technology}</span>
            <span>{с.words}</span>
          </div>
        ))}
      </div>

      <div className="v2-panel" data-why="работа">
        <h3>Новая технология</h3>
        <div className="v2-form">
          <label>Название
            <input value={поля.name} placeholder="алгоритмы FDIR"
              onChange={(e) => setПоля({ ...поля, name: e.target.value })} />
          </label>
          <label>Узел, на котором стоит
            <select value={поля.component} onChange={(e) => setПоля({ ...поля, component: e.target.value })}>
              <option value="">— выберите узел состава —</option>
              {узлы.map((у) => <option key={у.code} value={у.code}>{у.code} · {у.name}</option>)}
            </select>
          </label>
          <label>TRL сейчас
            <input type="number" min={1} max={9} value={поля.trl_current}
              onChange={(e) => setПоля({ ...поля, trl_current: Number(e.target.value) })} />
          </label>
          <label>TRL требуемый
            <input type="number" min={1} max={9} value={поля.trl_required}
              onChange={(e) => setПоля({ ...поля, trl_required: Number(e.target.value) })} />
          </label>
          <label>К точке
            <select value={поля.required_by} onChange={(e) => setПоля({ ...поля, required_by: e.target.value })}>
              <option value="">— веха проекта —</option>
              {вехи.map((в) => <option key={в.код} value={в.код}>{в.подпись}</option>)}
            </select>
          </label>
          <label>Резервное решение
            <input value={поля.fallback} placeholder="чем закроем, если не дозреет"
              onChange={(e) => setПоля({ ...поля, fallback: e.target.value })} />
          </label>
          <div className="v2-form__actions">
            <button type="button" className="v2-primary"
              disabled={занято || !поля.name.trim() || !поля.component}
              title={!поля.component
                ? 'технология критична для КОНКРЕТНОГО узла — иначе её нечем закрывать'
                : 'завести технологию; разрыв TRL сразу заведёт пакет созревания и веху'}
              onClick={завести}>
              {занято ? 'Завожу…' : 'Завести технологию'}
            </button>
          </div>
        </div>
      </div>
    </>
  )
}

/** Сцена 11 — риски со сроком-точкой и оценка засорения. */
export function Risks({ project }: { project: string }) {
  const [риски, setРиски] = useState<RiskRow[]>([])
  const [осз, setОсз] = useState<OdaRow[]>([])
  const [отказ, setОтказ] = useState<string | null>(null)
  const [поля, setПоля] = useState({
    statement: '', category: 'technical', probability: 3, impact: 3,
    strategy: 'mitigate', measures: '', owner: 'Ведущий СИ', due_point: '',
  })
  /**
   * Поля ОСЗ — те, что спрашивает маршрут (и истина `debris_assessment`):
   * два срока (штатный увод и пассивный сход при отказе ДУ), модель атмосферы,
   * баллистический коэффициент и нормативы с ПОЛКИ. Прежняя форма посылала
   * «lifetime_years · compliant · norm» — таких полей маршрут не знает, и
   * запись молча не заводилась: владелец 20.09 видел «оценки засорения нет».
   */
  const [оценка, setОценка] = useState({
    variant: '', active_lifetime_years: 18, passive_lifetime_years: 25,
    deorbit_dv: '', atmosphere_model: 'NRLMSISE-00', ballistic_coefficient: '',
    normative_active: '', normative_passive: '',
  })
  const [варианты, setВарианты] = useState<{ код: string; подпись: string }[]>([])
  const [нормативы, setНормативы] = useState<{ код: string; подпись: string }[]>([])
  const [занятоОсз, setЗанятоОсз] = useState(false)
  const вехи = useВехи(project)
  const [всемВеха, setВсемВеха] = useState('')
  const [занятоВсем, setЗанятоВсем] = useState(false)

  const перечитать = useCallback(() => {
    api.risks(project).then((r) => setРиски(r.items)).catch((e) => setОтказ(String(e.message ?? e)))
    api.oda(project).then((r) => setОсз(r.items)).catch(() => undefined)
    // Вариант — из принятой базовой концепции (сцена 7): ОСЗ считается ОТ НЕЁ.
    api.concept(project)
      .then((р) => setВарианты(р.items.map((к) => ({
        код: к.variant || к.code,
        подпись: `${к.variant || к.code}${к.rationale ? ` · ${к.rationale.slice(0, 40)}` : ''}`,
      }))))
      .catch(() => undefined)
    // Нормативы — с ПОЛКИ: порог живёт полем `limit` их пункта, и сервер берёт
    // его оттуда; список показывает только те, у кого нужный порог есть.
    api.shelves('normative_document')
      .then((р) => setНормативы(р.items
        .filter((н) => JSON.stringify(н.doc).includes('"limit"'))
        .map((н) => ({
          код: String((н.doc as { designation?: string }).designation ?? н.code),
          подпись: String((н.doc as { designation?: string }).designation ?? н.code).slice(0, 60),
        }))))
      .catch(() => undefined)
  }, [project])

  useEffect(перечитать, [перечитать])

  /**
   * Правка оценки риска в строке реестра. Вид «риск» правится на месте, а
   * пересчёт уровня (вероятность × влияние) делает сервер — экран лишь
   * перечитывает реестр, чтобы «В×П» не разошлись с тем, что видят ворота.
   */
  const правитьРиск = (код: string, поля: Record<string, unknown>) => {
    api.patchEntity(project, код, поля, 'инженер', 'оценка риска')
      .then(перечитать)
      .catch((ошибка) => setОтказ(String(ошибка.message ?? ошибка)))
  }

  return (
    <>
      {отказ && <div className="v2-locked">{отказ}</div>}

      <div className="v2-panel" data-why="работа">
        <h3>Реестр рисков<span className="v2-cnt">{риски.length}</span></h3>
        {риски.length === 0 ? (
          <div className="v2-empty">
            Рисков не заведено.
            <span className="v2-empty__why">
              Пустой реестр означает, что риски не искали: сцена 11 держится тремя записями.
            </span>
          </div>
        ) : (
          <table className="v2-table">
            <thead>
              <tr><th>Код</th><th>Риск</th><th>В×П</th><th>Стратегия</th><th>Владелец</th><th>Срок</th></tr>
            </thead>
            <tbody>
              {риски.map((р) => (
                <tr key={р.code}>
                  <td className="v2-mono">{р.code}</td>
                  <td>{р.statement}</td>
                  {/*
                    Вероятность, влияние, стратегия и владелец правятся В СТРОКЕ.
                    Принятый из записки риск приходил с «0×0 = 0», и поправить
                    оценку было нечем ни на одном экране: §6 отчёта о концепции
                    миссии печатал четыре пустые колонки на все двенадцать
                    рисков, а «ключевые риски» FA (критичность ≥ 12) не могли
                    набраться никогда (владелец 20–21.09).
                  */}
                  <td title="вероятность × влияние: шкала 1–5">
                    <select name={`${р.code}.probability`} value={р.probability || ''}
                      aria-label={`вероятность риска ${р.code}`}
                      onChange={(e) => правитьРиск(р.code, { probability: Number(e.target.value) })}>
                      <option value="">—</option>
                      {[1, 2, 3, 4, 5].map((з) => <option key={з} value={з}>{з}</option>)}
                    </select>
                    ×
                    <select name={`${р.code}.impact`} value={р.impact || ''}
                      aria-label={`влияние риска ${р.code}`}
                      onChange={(e) => правитьРиск(р.code, { impact: Number(e.target.value) })}>
                      <option value="">—</option>
                      {[1, 2, 3, 4, 5].map((з) => <option key={з} value={з}>{з}</option>)}
                    </select>
                    {р.level > 0 ? ` = ${р.level}` : ''}
                  </td>
                  <td>
                    <select name={`${р.code}.strategy`} value={р.strategy === '—' ? '' : р.strategy}
                      aria-label={`стратегия риска ${р.code}`}
                      onChange={(e) => правитьРиск(р.code, { strategy: e.target.value })}>
                      <option value="">— стратегия —</option>
                      <option value="mitigate">снижать</option>
                      <option value="accept">принять</option>
                      <option value="transfer">передать</option>
                      <option value="avoid">избежать</option>
                    </select>
                  </td>
                  <td>
                    <input name={`${р.code}.owner`} defaultValue={р.owner === '—' ? '' : р.owner}
                      placeholder="кто ведёт" aria-label={`владелец риска ${р.code}`}
                      onBlur={(e) => {
                        const имя = e.target.value.trim()
                        if (имя && имя !== р.owner) правитьРиск(р.code, { owner: имя })
                      }} />
                  </td>
                  {/*
                    Срок правится ЗДЕСЬ: условие сцены 11 «у каждого риска
                    срок-точка» держало проход на RI-0025…0027, а поправить
                    срок принятого риска было негде — только при заведении
                    (проход владельца 20.09).
                  */}
                  <td className={р.due_point === '—' ? 'v2-warn' : undefined}>
                    <select name={`${р.code}.due_point`} value={р.due_point === '—' ? '' : р.due_point}
                      aria-label={`срок-точка риска ${р.code}`}
                      onChange={(e) => api.patchEntity(project, р.code, { due_point: e.target.value }, 'инженер',
                        'срок-точка риска')
                        .then(перечитать)
                        .catch((ошибка) => setОтказ(String(ошибка.message ?? ошибка)))}>
                      <option value="">— нет точки —</option>
                      {вехи.map((в) => <option key={в.код} value={в.код}>{в.подпись}</option>)}
                    </select>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {/*
          Простановка разом: на проходе 20.09 без точки стояли ВСЕ двенадцать
          рисков — по одному это двенадцать кликов. Веху выбирает человек,
          проставляется она только тем, у кого точки нет: уже названный срок
          массовое действие не трогает.
        */}
        {риски.some((р) => р.due_point === '—') && (
          <div className="v2-form__actions" data-why="работа">
            <span className="v2-empty__why">
              без срока-точки: {риски.filter((р) => р.due_point === '—').length} из {риски.length}
            </span>
            <select name="всем.due_point" value={всемВеха} aria-label="веха для рисков без точки"
              onChange={(e) => setВсемВеха(e.target.value)}>
              <option value="">— веха проекта —</option>
              {вехи.map((в) => <option key={в.код} value={в.код}>{в.подпись}</option>)}
            </select>
            <button type="button" disabled={!всемВеха || занятоВсем}
              title={всемВеха
                ? 'проставить выбранную веху всем рискам без точки; названные сроки не трогаются'
                : 'сначала выберите веху'}
              onClick={() => {
                setЗанятоВсем(true); setОтказ(null)
                const без = риски.filter((р) => р.due_point === '—')
                Promise.all(без.map((р) => api.patchEntity(project, р.code, { due_point: всемВеха }, 'инженер',
                  'срок-точка риска: проставлено разом')))
                  .then(() => { setЗанятоВсем(false); перечитать() })
                  .catch((ошибка) => { setЗанятоВсем(false); setОтказ(String(ошибка.message ?? ошибка)) })
              }}>
              {занятоВсем ? 'Ставлю…' : 'Проставить всем без точки'}
            </button>
          </div>
        )}

        <div className="v2-form">
          <label>Формулировка
            <input value={поля.statement} placeholder="SEU в памяти без ECC → зависание борта"
              onChange={(e) => setПоля({ ...поля, statement: e.target.value })} />
          </label>
          <label>Меры
            <input value={поля.measures} placeholder="ECC и watchdog"
              onChange={(e) => setПоля({ ...поля, measures: e.target.value })} />
          </label>
          <label>Вероятность 1–5
            <input type="number" min={1} max={5} value={поля.probability}
              onChange={(e) => setПоля({ ...поля, probability: Number(e.target.value) })} />
          </label>
          <label>Влияние 1–5
            <input type="number" min={1} max={5} value={поля.impact}
              onChange={(e) => setПоля({ ...поля, impact: Number(e.target.value) })} />
          </label>
          <label>Срок — точка
            <select value={поля.due_point} onChange={(e) => setПоля({ ...поля, due_point: e.target.value })}>
              <option value="">— веха проекта —</option>
              {вехи.map((в) => <option key={в.код} value={в.код}>{в.подпись}</option>)}
            </select>
          </label>
          <div className="v2-form__actions">
            <button type="button" className="v2-primary" disabled={!поля.statement.trim()}
              title="срок без точки не наступает — точка обязательна"
              onClick={() => api.addRisk(project, поля)
                .then(() => { setПоля({ ...поля, statement: '', measures: '' }); перечитать() })
                .catch((e) => setОтказ(String(e.message ?? e)))}>
              Завести риск
            </button>
          </div>
        </div>
      </div>

      <div className="v2-panel" data-why="работа">
        <h3>Оценка засорения (ОСЗ)<span className="v2-cnt">{осз.length}</span></h3>
        {осз.length === 0 ? (
          <div className="v2-empty">
            Оценки засорения нет.
            <span className="v2-empty__why">
              ОСЗ к MCR считается от базового варианта: время существования против норматива
              и Δv увода.
            </span>
          </div>
        ) : (
          осз.map((о) => (
            <div key={о.code} className="v2-note">
              <span className="v2-note__rule">{о.code}</span>
              <span>
                вариант {о.variant || '—'} · штатный увод {о.active_lifetime_years} лет
                {' '}({о.compliant_active ? 'в норме' : 'НЕ в норме'}, {о.normative_active}) ·
                {' '}пассивный сход {о.passive_lifetime_years} лет
                {' '}({о.compliant_passive ? 'в норме' : 'НЕ в норме'}, {о.normative_passive})
                {о.deorbit_dv && ` · Δv увода ${о.deorbit_dv}`}
                {о.atmosphere_model && ` · атмосфера ${о.atmosphere_model}`}
              </span>
            </div>
          ))
        )}
        <div className="v2-form">
          <label>Вариант базовой концепции
            <select name="осз.variant" value={оценка.variant}
              onChange={(e) => setОценка({ ...оценка, variant: e.target.value })}>
              <option value="">— вариант, от которого считаем —</option>
              {варианты.map((в) => <option key={в.код} value={в.код}>{в.подпись}</option>)}
            </select>
          </label>
          <label>Время существования после увода ДУ, лет
            <input type="number" name="осз.active" value={оценка.active_lifetime_years}
              onChange={(e) => setОценка({ ...оценка, active_lifetime_years: Number(e.target.value) })} />
          </label>
          <label>Пассивный сход при отказе ДУ, лет
            <input type="number" name="осз.passive" value={оценка.passive_lifetime_years}
              onChange={(e) => setОценка({ ...оценка, passive_lifetime_years: Number(e.target.value) })} />
          </label>
          <label>Δv увода
            <input name="осз.dv" autoComplete="off" value={оценка.deorbit_dv} placeholder="12 м/с"
              onChange={(e) => setОценка({ ...оценка, deorbit_dv: e.target.value })} />
          </label>
          <label>Модель атмосферы
            <input name="осз.atm" autoComplete="off" value={оценка.atmosphere_model} placeholder="NRLMSISE-00"
              onChange={(e) => setОценка({ ...оценка, atmosphere_model: e.target.value })} />
          </label>
          <label>Баллистический коэффициент m/(Cd·A)
            <input name="осз.bc" autoComplete="off" value={оценка.ballistic_coefficient} placeholder="120 кг/м²"
              onChange={(e) => setОценка({ ...оценка, ballistic_coefficient: e.target.value })} />
          </label>
          <label>Норматив штатного увода
            <select name="осз.norm_active" value={оценка.normative_active}
              onChange={(e) => setОценка({ ...оценка, normative_active: e.target.value })}>
              <option value="">— норматив с полки —</option>
              {нормативы.map((н) => <option key={н.код} value={н.код}>{н.подпись}</option>)}
            </select>
          </label>
          <label>Норматив пассивного схода
            <select name="осз.norm_passive" value={оценка.normative_passive}
              onChange={(e) => setОценка({ ...оценка, normative_passive: e.target.value })}>
              <option value="">— норматив с полки —</option>
              {нормативы.map((н) => <option key={н.код} value={н.код}>{н.подпись}</option>)}
            </select>
          </label>
          <div className="v2-form__actions">
            <button type="button" className="v2-primary"
              disabled={занятоОсз || !оценка.normative_active || !оценка.normative_passive
                || !оценка.atmosphere_model.trim() || !оценка.ballistic_coefficient.trim()}
              title={!оценка.normative_active || !оценка.normative_passive
                ? 'норматив не назван: сверять срок не с чем'
                : !оценка.atmosphere_model.trim()
                  ? 'модель атмосферы не названа: срок схода без неё не проверить'
                  : !оценка.ballistic_coefficient.trim()
                    ? 'баллистический коэффициент не задан: сход считается по нему'
                    : 'записать оценку засорения от базового варианта'}
              onClick={() => {
                setЗанятоОсз(true); setОтказ(null)
                api.addOda(project, { ...оценка, author: 'инженер' })
                  .then(() => { setЗанятоОсз(false); перечитать() })
                  .catch((e) => { setЗанятоОсз(false); setОтказ(String(e.message ?? e)) })
              }}>
              {занятоОсз ? 'Записываю…' : 'Записать ОСЗ'}
            </button>
            <span className="v2-empty__why">
              Вердикты считает система: пороги берутся полем `limit` пункта норматива с полки,
              а не числом в тексте. Случая два — штатный увод и отказ ДУ.
            </span>
          </div>
        </div>
      </div>
    </>
  )
}

/** Сцена 12 — WBS с парами к узлам и оценка диапазоном. */
export function Costs({ project }: { project: string }) {
  const [пакеты, setПакеты] = useState<WbsRow[]>([])
  const [окно, setОкно] = useState<WbsOffer[]>([])
  const [показатьОкно, setПоказатьОкно] = useState(false)
  const [отказ, setОтказ] = useState<string | null>(null)
  const [занято, setЗанято] = useState(false)
  const [оценка, setОценка] = useState({
    pkg: '', min: 0, max: 0, unit: 'млн ₽', method: 'rom', assumptions: '',
  })

  const перечитать = useCallback(() => {
    api.wbs(project).then((r) => setПакеты(r.items)).catch((e) => setОтказ(String(e.message ?? e)))
    api.wbsOffer(project).then((r) => setОкно(r.items)).catch(() => setОкно([]))
  }, [project])

  useEffect(перечитать, [перечитать])

  const безОценки = пакеты.filter((п) => !п.estimate)
  const безПары = пакеты.filter((п) => !п.cross_cutting && п.pbs_refs.length === 0)
  const невзятые = окно.filter((п) => !п.taken)

  const взять = (коды: string[]) => {
    setЗанято(true)
    api.takeWbs(project, коды).then(перечитать)
      .catch((e) => setОтказ(String(e.message ?? e)))
      .finally(() => setЗанято(false))
  }

  return (
    <>
      {отказ && <div className="v2-locked">{отказ}</div>}

      <div className="v2-panel" data-why="работа">
        <h3>
          Пакеты работ (WBS)
          <span className="v2-cnt">
            {пакеты.length}
            {пакеты.length > 0 && ` · без пары ${безПары.length} · без оценки ${безОценки.length}`}
            {невзятые.length > 0 && ` · на полке ещё ${невзятые.length}`}
          </span>
          <span className="v2-head__spacer" />
          <button type="button" className="v2-chip" onClick={() => setПоказатьОкно(!показатьОкно)}
            title="окно взятия: что предлагает полка и почему">
            {показатьОкно ? 'скрыть окно' : 'окно взятия'}
          </button>
        </h3>
        {пакеты.length === 0 ? (
          <div className="v2-empty">
            WBS не взят.
            <span className="v2-empty__why">
              Типовой WBS лежит на полке: пакеты берутся оттуда и сопоставляются узлам состава.
            </span>
            <div className="v2-form__actions">
              <button type="button" className="v2-primary" disabled={занято}
                title="взять пакеты, которым есть с чем быть парными, плюс сквозные"
                onClick={() => взять([])}>
                {занято ? 'Беру…' : `Взять рекомендованные (${окно.filter((п) => п.recommended).length})`}
              </button>
              <button type="button" className="v2-link" onClick={() => setПоказатьОкно(!показатьОкно)}>
                {показатьОкно ? 'скрыть окно взятия' : `показать всё с полки (${окно.length})`}
              </button>
            </div>
          </div>
        ) : (
          <table className="v2-table">
            <thead>
              <tr><th>Код</th><th>Пакет</th><th>Узлы</th><th>Оценка</th><th>Разрывы</th></tr>
            </thead>
            <tbody>
              {пакеты.slice(0, 30).map((п) => (
                <tr key={п.code}>
                  <td className="v2-mono">{п.code}</td>
                  <td>{п.name}{п.cross_cutting && <span className="v2-dim"> · сквозной</span>}</td>
                  <td>{п.pbs_refs.join(', ') || <span className="v2-dim">—</span>}</td>
                  <td>
                    {п.estimate
                      ? `${п.estimate.min}–${п.estimate.max} ${п.estimate.unit} · ${п.estimate.method}`
                      : <span className="v2-dim">нет</span>}
                  </td>
                  <td className="v2-warn">{п.gaps.length > 0 ? п.gaps.length : ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {показатьОкно && (
        <div className="v2-panel" data-why="следующий-клик">
          <h3>
            Окно взятия WBS
            <span className="v2-cnt">
              на полке {окно.length} · рекомендовано {окно.filter((п) => п.recommended).length}
              {невзятые.length > 0 && ` · не взято ${невзятые.length}`}
            </span>
          </h3>
          <div className="v2-empty__why">
            Рекомендованы пакеты, которым есть с чем быть парными в составе, и сквозные —
            у них пары нет по определению. Остальное берётся руками: пакет без пары
            держит выход сцены 12 и ничего не даёт взамен.
          </div>
          <table className="v2-table">
            <thead>
              <tr><th>Код</th><th>Пакет</th><th>Почему</th><th>Состояние</th><th /></tr>
            </thead>
            <tbody>
              {окно.map((п) => (
                <tr key={п.code} className={п.recommended ? '' : 'v2-dim'}>
                  <td className="v2-mono">{п.code}</td>
                  <td>{п.name}</td>
                  <td>{п.why}</td>
                  <td>
                    {п.taken ? <span className="v2-ok">взят</span>
                      : п.recommended ? 'рекомендован' : <span className="v2-dim">не взят</span>}
                  </td>
                  <td>
                    {!п.taken && (
                      <button type="button" className="v2-link" disabled={занято}
                        title={п.recommended ? 'взять пакет в проект'
                          : 'взять пакет, у которого нет пары к узлу: выход сцены 12 его спросит'}
                        onClick={() => взять([п.code])}>
                        взять
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {пакеты.length > 0 && (
        <div className="v2-panel" data-why="работа">
          <h3>Оценка пакета</h3>
          <div className="v2-empty__why">
            Одно число на Pre-A читают как обязательство — принимается только диапазон с допущениями.
          </div>
          <div className="v2-form">
            <label>Пакет
              <select value={оценка.pkg} onChange={(e) => setОценка({ ...оценка, pkg: e.target.value })}>
                <option value="">— выберите пакет —</option>
                {пакеты.map((п) => (
                  <option key={п.code} value={п.code}>{п.code} · {п.name}</option>
                ))}
              </select>
            </label>
            <label>От
              <input type="number" value={оценка.min}
                onChange={(e) => setОценка({ ...оценка, min: Number(e.target.value) })} />
            </label>
            <label>До
              <input type="number" value={оценка.max}
                onChange={(e) => setОценка({ ...оценка, max: Number(e.target.value) })} />
            </label>
            <label>Метод
              <select value={оценка.method} onChange={(e) => setОценка({ ...оценка, method: e.target.value })}>
                <option value="rom">ROM (порядок величины)</option>
                <option value="parametric">параметрика</option>
                <option value="bottom_up">снизу вверх</option>
              </select>
            </label>
            <label>Допущения
              <input value={оценка.assumptions} placeholder="серийная платформа 12U, пуск попутный, курс на дату"
                onChange={(e) => setОценка({ ...оценка, assumptions: e.target.value })} />
            </label>
            <div className="v2-form__actions">
              <button type="button" className="v2-primary"
                disabled={!оценка.pkg || оценка.max <= оценка.min || !оценка.assumptions.trim()}
                title={оценка.max <= оценка.min
                  ? 'дайте диапазон: одно число на Pre-A — ложная точность'
                  : !оценка.assumptions.trim()
                    ? 'оценка без допущений непроверяема: на чём она держится?'
                    : 'записать оценку пакета'}
                onClick={() => api.estimate(project, {
                  package: оценка.pkg,
                  min: оценка.min,
                  max: оценка.max,
                  unit: оценка.unit,
                  method: оценка.method,
                  assumptions: оценка.assumptions,
                })
                  .then(() => { setОценка({ ...оценка, assumptions: '' }); перечитать() })
                  .catch((e) => setОтказ(String(e.message ?? e)))}>
                Записать оценку
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  )
}
