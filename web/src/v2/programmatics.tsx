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

const ТОЧКИ = ['MCR', 'SRR', 'SDR', 'PDR']

/** Сцена 10 — технологии: TRL, разрыв, план созревания. */
export function Technologies({ project }: { project: string }) {
  const [строки, setСтроки] = useState<TechnologyRow[]>([])
  const [созревание, setСозревание] = useState<MaturationRow[]>([])
  const [узлы, setУзлы] = useState<ComponentRow[]>([])
  const [отказ, setОтказ] = useState<string | null>(null)
  const [занято, setЗанято] = useState(false)
  const [поля, setПоля] = useState({
    name: '', component: '', trl_current: 5, trl_required: 6, required_by: 'PDR', fallback: '',
  })

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
              {ТОЧКИ.map((т) => <option key={т} value={т}>{т}</option>)}
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
    strategy: 'mitigate', measures: '', owner: 'Ведущий СИ', due_point: 'MCR',
  })
  const [оценка, setОценка] = useState({ variant: '', lifetime_years: 18, dv_deorbit: '', compliant: true })

  const перечитать = useCallback(() => {
    api.risks(project).then((r) => setРиски(r.items)).catch((e) => setОтказ(String(e.message ?? e)))
    api.oda(project).then((r) => setОсз(r.items)).catch(() => undefined)
  }, [project])

  useEffect(перечитать, [перечитать])

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
                  <td title="вероятность × влияние">{р.probability}×{р.impact} = {р.level}</td>
                  <td>{р.strategy}</td>
                  <td>{р.owner}</td>
                  <td className={р.due_point === '—' ? 'v2-warn' : ''}>
                    {р.due_point === '—' ? 'нет точки' : р.due_point}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
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
              {ТОЧКИ.map((т) => <option key={т} value={т}>{т}</option>)}
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
                вариант {о.variant || '—'} · время существования {о.lifetime_years} лет ·
                норматив {о.norm} · {о.compliant ? 'соответствует' : 'НЕ соответствует'}
              </span>
            </div>
          ))
        )}
        <div className="v2-form">
          <label>Вариант
            <input value={оценка.variant} placeholder="V1"
              onChange={(e) => setОценка({ ...оценка, variant: e.target.value })} />
          </label>
          <label>Время существования, лет
            <input type="number" value={оценка.lifetime_years}
              onChange={(e) => setОценка({ ...оценка, lifetime_years: Number(e.target.value) })} />
          </label>
          <label>Δv увода
            <input value={оценка.dv_deorbit} placeholder="12 м/с"
              onChange={(e) => setОценка({ ...оценка, dv_deorbit: e.target.value })} />
          </label>
          <div className="v2-form__actions">
            <button type="button" className="v2-primary"
              title="записать начальную оценку засорения от базового варианта"
              onClick={() => api.addOda(project, {
                ...оценка,
                compliant: оценка.lifetime_years <= 25,
                norm: '25 лет',
              })
                .then(перечитать)
                .catch((e) => setОтказ(String(e.message ?? e)))}>
              Записать ОСЗ
            </button>
            <span className="v2-empty__why">
              Соответствие нормативу считает система: 25 лет — предел.
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
