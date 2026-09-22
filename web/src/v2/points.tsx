// Экран «Точки» (шип D, сцены 15–18): готовность по экспертизе, замечания
// с возвратом в сцену, решение точки, матрица зрелости комплекта.
//
// Минимум по `ДИЗАЙН-ПРИНЦИПЫ-V2`: список точек, карточка вниз. Ни одной
// спрятанной кнопки: роль и блокирующие проверяет сервер и отвечает
// словами — они и показываются, как есть.
import { useEffect, useState } from 'react'
import { api, type Condition, type Finding, type Gate, type Phase, type Position, type PointsView } from './api'
import { ResearchPanel } from './research'

/**
 * Точки, перед которыми спрашивают полноту у внешнего контура: внутренний
 * обзор и MCR. Перед воротами вопрос ставится по всем пяти классам сразу —
 * дальше комплект уходит на решение, и добирать будет поздно.
 */
const ТОЧКИ_ИССЛЕДОВАНИЯ = ['internal_review', 'MCR']

const ИСХОД: Record<string, string> = {
  approve: 'зафиксирована',
  return: 'возвращена с замечаниями',
  defer: 'отложена',
}
const ВИД_ЗАМЕЧАНИЯ: Record<string, string> = { finding: 'замечание', rfa: 'RFA', rid: 'RID' }
const РОЛЬ: Record<string, string> = {
  lead: 'руководитель проекта',
  lead_se: 'ведущий системный инженер',
  specialist: 'инженер',
  da_review: 'DA',
}

/** Учётка, как её отдаёт /api/auth/whoami: роль «от имени» сильнее ролей проекта. */
export type Учётка = {
  login: string
  display_name: string
  acting_role?: string | null
  roles?: Record<string, string>
}

/**
 * Мои роли в проекте — ровно то, что скажет сервер: роль «от имени», иначе
 * роль в проекте, иначе роль «*». Никаких добавок вроде «РП носит и DA»:
 * право решает сервер, а экран не обещает того, в чём ему откажут (1.1).
 */
export function моиРоли(учётка: Учётка | null | undefined, project: string): string[] {
  if (!учётка) return []
  if (учётка.acting_role) return [учётка.acting_role]
  const роль = учётка.roles?.[project] ?? учётка.roles?.['*']
  return роль ? [роль] : []
}

export function Points({ project, phase, onChanged, onGoScene, учётка }: {
  project: string | null
  phase: Phase | null
  onChanged: () => void
  /** Переход «к месту»: открыть работу на сцене, где чинится условие. */
  onGoScene?: (сцена: string) => void
  /** Моя учётка: кнопка решения видна только роли, которая решает. */
  учётка?: Учётка | null
}) {
  const [вид, setВид] = useState<PointsView | null>(null)
  const [открыта, setОткрыта] = useState<string | null>(null)
  const [отказ, setОтказ] = useState<string | null>(null)
  const [ролиПроекта, setРолиПроекта] = useState<Record<string, string>>({})

  const перечитать = () => {
    if (!project) return
    api.points(project).then(setВид).catch((e) => setОтказ(String(e.message ?? e)))
    api.projectRoles(project).then(setРолиПроекта).catch(() => setРолиПроекта({}))
  }
  useEffect(перечитать, [project])

  if (!project) return <div className="v2-panel" data-why="следующий-клик"><div className="v2-empty">Проект не выбран.</div></div>
  if (отказ) return <div className="v2-panel" data-why="почему-нельзя"><div className="v2-locked">{отказ}</div></div>
  if (!вид) return <div className="v2-panel" data-why="следующий-клик"><div className="v2-empty">Точки читаются…</div></div>

  const пройдено = вид.items.filter((т) => т.passed).length
  const точка = вид.items.find((т) => т.key === открыта) ?? null
  return (
    <>
      <div className="v2-panel" data-why="следующий-клик">
        <h3>
          Точки фазы
          <span className="v2-cnt">{пройдено} из {вид.items.length} пройдены · {вид.phase}</span>
        </h3>
        <div className="v2-list">
          {вид.items.map((т) => (
            <button key={т.key} type="button"
              className={т.key === открыта ? 'v2-row v2-row--cur' : 'v2-row'}
              title={т.passed ? 'пройдена — решение записано' : т.blocking.length > 0 ? `держится: ${т.blocking.join('; ')}` : 'условия выполнены — можно решать'}
              onClick={() => setОткрыта(т.key === открыта ? null : т.key)}>
              <span className="v2-row__n">◆</span>
              <span className="v2-row__t">{т.title}</span>
              <span className="v2-st">
                {т.planned_date && <>{датаКратко(т.planned_date)} · </>}
                {т.passed
                  ? <span className="v2-ok">пройдена</span>
                  : т.blocking.length > 0
                    ? <span className="v2-bad">{т.blocking.length} блокирующих</span>
                    : <span className="v2-ok">условия выполнены</span>}
                {' · решает '}{РОЛЬ[т.role] ?? т.role}
              </span>
            </button>
          ))}
        </div>
      </div>
      {точка && phase && (
        <PointCard project={project} точка={точка} все={вид.items} phase={phase}
          onChanged={() => { перечитать(); onChanged() }} onGoScene={onGoScene}
          мои={моиРоли(учётка, project)} ролиПроекта={ролиПроекта} я={учётка?.display_name ?? ''} />
      )}
    </>
  )
}

function PointCard({ project, точка, все, phase, onChanged, onGoScene, мои, ролиПроекта, я }: {
  project: string
  точка: Gate
  все: Gate[]
  phase: Phase
  onChanged: () => void
  /** «К месту»: открыть сцену, где чинится незакрытое условие. */
  onGoScene?: (сцена: string) => void
  /** Мои роли в проекте — как их видит сервер. */
  мои: string[]
  /** Роли проекта: логин → роль; кто фиксирует, если не я. */
  ролиПроекта: Record<string, string>
  /** Моё имя — для строки «фиксирует … · вы». */
  я: string
}) {
  const [ответ, setОтвет] = useState<string | null>(null)
  const [форма, setФорма] = useState<{ text: string; scene: string; question?: string; kind: string } | null>(null)
  const [помета, setПомета] = useState('')
  const [толькоОткрытые, setТолькоОткрытые] = useState(true)
  const [занято, setЗанято] = useState(false)

  const чекЛист = точка.checklist_of ? все.find((т) => т.key === точка.checklist_of) ?? null : null
  const экспертиза = точка.expertise ?? null
  const вопросы = (экспертиза?.questions.length ? экспертиза.questions : чекЛист?.expertise?.questions) ?? []
  const открытые = точка.findings.filter((з) => з.status === 'open')

  const действие = async (что: () => Promise<unknown>) => {
    setЗанято(true); setОтвет(null)
    try { await что(); setФорма(null); onChanged() } catch (e) { setОтвет(String((e as Error).message ?? e)) } finally { setЗанято(false) }
  }
  const завести = () => форма && действие(() => api.addFinding(project, точка.key, {
    text: форма.text, returns_to_scene: форма.scene, question: форма.question, kind: форма.kind,
  }))
  const решить = (исход: string) => действие(() => api.decide(project, точка.key, исход, помета))
  const новоеЗамечание = (вопрос?: string) => setФорма({
    text: вопрос ? `Нет: ${вопрос}` : '', scene: phase.current_scene ?? phase.scenes[0]?.key ?? '1', question: вопрос,
    kind: точка.key === 'MCR' ? 'rfa' : 'finding',
  })

  return (
    <div className="v2-panel" data-why="работа">
      <h3>
        ◆ {точка.title}
        <span className="v2-cnt">
          {точка.planned_date ? датаКратко(точка.planned_date) + ' · ' : ''}
          {точка.decision
            ? `${ИСХОД[точка.decision.outcome] ?? точка.decision.outcome} · ${точка.decision.by} · ${датаКратко(точка.decision.at.slice(0, 10))}`
            : `решает ${РОЛЬ[точка.role] ?? точка.role}`}
        </span>
      </h3>
      {точка.decision?.note && <div className="v2-note-line">{точка.decision.note}</div>}
      <div className="v2-note-line">
        <a className="v2-link" href={api.pointPackageUrl(project, точка.key)} target="_blank" rel="noreferrer"
          title="один архив: точка JSON, печать документов фазы, .sdoc с грамматикой, пакет знаний с отпечатком, манифест">
          пакет точки (zip)
        </a>
      </div>
      {точка.legend_note && экспертиза?.positions.length ? <div className="v2-note-line">{точка.legend_note}</div> : null}

      {ТОЧКИ_ИССЛЕДОВАНИЯ.includes(точка.key) && (
        <ResearchPanel project={project} trigger={{ gate: точка.key }} onChanged={onChanged} />
      )}

      <h4 className="v2-h4">Готовность</h4>
      <ul className="v2-checks">
        {точка.criteria.map((у) => (
          <Условие key={у.check} у={у} сцена={где(phase, у.check)} onGoScene={onGoScene} />
        ))}
        {экспертиза?.positions.map((п) => <Позиция key={п.artifact} п={п} />)}
      </ul>

      {/*
        Решение стоит СРАЗУ ЗА ГОТОВНОСТЬЮ, а не в самом низу за матрицей
        зрелости и замечаниями: владелец 21.09 базировал последний документ и
        написал «явного перехода нет — всё просто стоит». Кнопка была, но
        ниже двух экранов таблицы.
      */}
      {/*
        Кнопка решения видна ТОЛЬКО той роли, которая решает (шип 1, п. 1.1):
        спрятанная кнопка правом не является — право проверяет сервер, — но и
        кнопка, отвечающая 403, ничего не решает. Остальным — кто фиксирует.
      */}
      {!точка.passed && точка.role && !мои.includes(точка.role) && (
        <div className="v2-empty__why" data-why="почему-нельзя">
          Фиксирует {РОЛЬ[точка.role] ?? точка.role}
          {(() => {
            const держатели = Object.entries(ролиПроекта).filter(([, р]) => р === точка.role).map(([л]) => л)
            return держатели.length > 0 ? ` · ${держатели.join(', ')}` : ' — роль в проекте не назначена'
          })()}
          . Ваша роль — {мои.map((р) => РОЛЬ[р] ?? р).join(', ') || 'нет'}{я ? ` (${я})` : ''}: решение здесь не ваше.
        </div>
      )}
      {!точка.passed && (!точка.role || мои.includes(точка.role)) && (
        <>
          <div className="v2-empty__why" data-why="почему-нельзя">
            {точка.blocking.length === 0
              ? 'Ничто не держит: решение можно фиксировать.'
              : `Держит ${точка.blocking.length}: ` + точка.blocking
                .map((б) => б.replace(/^комплект: /, '').replace(/ — .*/, ''))
                .join('; ') + '. Сервер откажет, пока это не закрыто.'}
          </div>
          <div className="v2-form v2-form--row" data-why="следующий-клик">
            <input value={помета} placeholder="помета к решению (не обязательно)" onChange={(e) => setПомета(e.target.value)} />
            <button type="button" className="v2-primary" disabled={занято}
              title={точка.opens_phase ? `решение открывает ${точка.opens_phase}` : 'зафиксировать точку: блокирующие и роль проверит сервер'}
              onClick={() => решить('approve')}>
              {точка.opens_phase ? `Решение: переход в ${точка.opens_phase}` : 'Зафиксировать'}
            </button>
            <button type="button" className="v2-link" disabled={занято}
              title="вернуть точку с открытыми замечаниями" onClick={() => решить('return')}>вернуть с замечаниями</button>
          </div>
        </>
      )}

      {чекЛист && (
        <>
          <h4 className="v2-h4">Чек-лист по критериям {чекЛист.title}</h4>
          <ul className="v2-checks">
            {чекЛист.criteria.map((у) => (
              <Условие key={у.check} у={у} сцена={где(phase, у.check)} onGoScene={onGoScene} />
            ))}
            {чекЛист.expertise?.positions.map((п) => <Позиция key={п.artifact} п={п} />)}
          </ul>
        </>
      )}

      {(экспертиза || вопросы.length > 0) && (
        <>
          <h4 className="v2-h4">Экспертиза{экспертиза?.source ? ` · ${экспертиза.source}` : ''}</h4>
          {экспертиза?.goal && <div className="v2-act__goal">{экспертиза.goal}</div>}
          <div className="v2-empty__why">
            Чек-лист эксперта: система записывает только ответ «нет» — он заводит замечание с
            возвратом в сцену. Ответ «да» нигде не хранится, поэтому вопрос без замечания стоит
            точкой, а не галочкой.
          </div>
          <ul className="v2-checks">
            {вопросы.map((в) => {
              const нет = точка.findings.find((з) => з.question === в && з.status === 'open')
              return (
                <li key={в} className={нет ? 'v2-check v2-check--no' : 'v2-check v2-check--note'}>
                  {/*
                    Галочка тут врала: «☑» значило лишь «замечания по вопросу
                    нет», а не «ответили да» — ответов система не хранит вовсе.
                    Точка (·) честнее: вопрос задан, ответа в системе нет.
                  */}
                  <span>{нет ? '☐' : '·'}</span>
                  <span className="v2-check__t">{в}</span>
                  {нет
                    ? <span className="v2-cnt"> — {нет.code} → сцена {нет.scene}</span>
                    : <button type="button" className="v2-link"
                        title="ответ «нет» заводит замечание с возвратом в сцену; ответ «да» система не хранит"
                        onClick={() => новоеЗамечание(в)}>ответить «нет» → замечание</button>}
                </li>
              )
            })}
          </ul>
          {экспертиза && экспертиза.results.length > 0 && (
            <div className="v2-act__io">
              <span>результаты: {экспертиза.results.join(' · ')}</span>
              {экспертиза.main_outcome.length > 0 && <span>главный итог: {экспертиза.main_outcome.join(' · ')}</span>}
            </div>
          )}
        </>
      )}

      {точка.matrix.length > 0 && (
        <>
          <h4 className="v2-h4">Матрица зрелости комплекта Д1–Д9</h4>
          <div className="v2-scroll">
            <table className="v2-table">
              <thead>
                <tr>
                  <th>артефакт</th>
                  {все.map((т) => <th key={т.key}>{т.key}</th>)}
                  <th>к этой точке</th>
                </tr>
              </thead>
              <tbody>
                {точка.matrix.map((р) => (
                  <tr key={р.artifact}>
                    <td>{р.artifact}</td>
                    {все.map((т) => <td key={т.key} className="v2-mono">{р.codes?.[т.key] ?? '—'}</td>)}
                    <td>
                      {р.passed === null
                        ? <span className="v2-cnt">{р.why}</span>
                        : р.passed
                          ? <span className="v2-ok">✓ {р.why}</span>
                          : <span className={р.blocking ? 'v2-bad' : 'v2-cnt'}>☐ {р.why}</span>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}

      <h4 className="v2-h4">
        Замечания
        <span className="v2-cnt"> {открытые.length} открытых из {точка.findings.length}</span>
        {' '}
        <button type="button" className={толькоОткрытые ? 'v2-chip v2-chip--on' : 'v2-chip'}
          onClick={() => setТолькоОткрытые(!толькоОткрытые)}>{толькоОткрытые ? 'открытые' : 'все'}</button>
        {' '}
        <button type="button" className="v2-chip" onClick={() => новоеЗамечание()}>+ замечание</button>
      </h4>
      {форма && (
        <div className="v2-form" data-why="работа">
          <label>текст замечания
            <textarea rows={2} value={форма.text} autoComplete="off" onChange={(e) => setФорма({ ...форма, text: e.target.value })} />
          </label>
          <label>возврат в сцену
            <select value={форма.scene} onChange={(e) => setФорма({ ...форма, scene: e.target.value })}>
              {phase.scenes.map((с) => <option key={с.key} value={с.key}>{с.key} · {с.title}</option>)}
            </select>
          </label>
          {точка.key === 'MCR' && (
            <label>вид
              <select value={форма.kind} onChange={(e) => setФорма({ ...форма, kind: e.target.value })}>
                <option value="rfa">RFA — запрос действия</option>
                <option value="rid">RID — расхождение обзора</option>
              </select>
            </label>
          )}
          <div className="v2-form__actions">
            <button type="button" className="v2-primary" disabled={занято || !форма.text.trim()}
              title={!форма.text.trim() ? 'замечание без текста не заводится' : 'завести замечание: сцена возврата снова в работе'}
              onClick={завести}>Завести</button>
            <button type="button" className="v2-link" onClick={() => setФорма(null)}>отмена</button>
          </div>
        </div>
      )}
      <ul className="v2-checks">
        {точка.findings.filter((з) => !толькоОткрытые || з.status === 'open').map((з) => (
          <Замечание key={з.code} з={з} занято={занято}
            onClose={() => действие(() => api.closeFinding(project, з.code, ''))} />
        ))}
        {точка.findings.length === 0 && <li className="v2-empty">замечаний нет</li>}
      </ul>

      {ответ && <div className="v2-locked" data-why="почему-нельзя">{ответ}</div>}
    </div>
  )
}

function Условие({ у, сцена, onGoScene }: {
  у: Condition
  сцена?: { key: string; title: string }
  onGoScene?: (сцена: string) => void
}) {
  return (
    <li className={у.passed ? 'v2-check' : у.blocking === false ? 'v2-check v2-check--note' : 'v2-check v2-check--no'}>
      <span>{у.passed ? '✓' : '☐'}</span>
      <span className="v2-check__t">{у.title}</span>
      {!у.passed && у.why && <span className="v2-cnt"> — {у.why}</span>}
      {/*
        «И где же прячутся риски?» (владелец 20.09): точка называет условие, а
        работать надо на СЦЕНЕ, где этот вид заводится. Сцена ищется по тому же
        ключу проверки, что стоит в условии, — не по догадке экрана.
      */}
      {!у.passed && сцена && onGoScene && (
        <button type="button" className="v2-link" onClick={() => onGoScene(сцена.key)}
          title={`открыть сцену ${сцена.key} «${сцена.title}» — там это и чинится`}>
          к месту: сцена {сцена.key}
        </button>
      )}
    </li>
  )
}

/**
 * Где чинится условие точки: сцена, у которой в выходах стоит проверка того же
 * корня. Корень — первое слово ключа («risks_due_closed» → «risks», и сцена 11
 * с `risks_min` находится сама). Догадок в коде нет: ключи берутся из фазы.
 */
function где(phase: Phase | null, check: string): { key: string; title: string } | undefined {
  if (!phase) return undefined
  const корень = check.split(':')[0].split('_')[0]
  if (корень.length < 3) return undefined
  const сцена = phase.scenes.find((с) =>
    с.exit.some((у) => у.check.split(':')[0].split('_')[0] === корень))
  return сцена ? { key: сцена.key, title: сцена.title } : undefined
}

function Позиция({ п }: { п: Position }) {
  const класс = п.passed === null ? 'v2-check v2-check--note' : п.passed ? 'v2-check' : п.blocking ? 'v2-check v2-check--no' : 'v2-check v2-check--note'
  return (
    <li className={класс}>
      <span>{п.passed === null ? '·' : п.passed ? '✓' : '☐'}</span>
      <span className="v2-check__t"><span className="v2-mono">{п.maturity}</span> {п.artifact}</span>
      {п.why && п.passed !== true && <span className="v2-cnt"> — {п.why}</span>}
      {п.passed === true && п.our_ref?.startsWith('kind:') && <span className="v2-cnt" title={п.why ?? ''}> — записи есть</span>}
    </li>
  )
}

function Замечание({ з, занято, onClose }: { з: Finding; занято: boolean; onClose: () => void }) {
  return (
    <li className={з.status === 'open' ? 'v2-check v2-check--no' : 'v2-check'}>
      <span>{з.status === 'open' ? '☐' : '✓'}</span>
      <span className="v2-check__t">
        <span className="v2-row__n">{з.code}</span> {з.text}
        <span className="v2-cnt"> — {ВИД_ЗАМЕЧАНИЯ[з.kind] ?? з.kind} · сцена {з.scene} · {з.author}{з.closed_by ? ` · закрыто: ${з.closed_by}` : ''}</span>
      </span>
      {з.status === 'open' && (
        <button type="button" className="v2-link" disabled={занято}
          title="закрыть замечание — сцена возврата отпускается" onClick={onClose}>закрыть</button>
      )}
    </li>
  )
}

function датаКратко(дата: string): string {
  const [г, м, д] = дата.split('-')
  return д && м ? `${д}.${м}${г ? '.' + г.slice(2) : ''}` : дата
}
