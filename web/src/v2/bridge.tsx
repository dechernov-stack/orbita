// Мостик ведущего (шип 2, экран 6; РЕЖИМЫ-РАБОТЫ-ДВУХ-РОЛЕЙ).
//
// Вход ведущего — не раздел, а очередь: маршрут фазы одной строкой, что
// решить (≤ 7), что блокирует ближайшую точку (с «Поручить»), кто чем занят
// и три-четыре сигнала. Реестры отсюда открываются ИЗ СТРОКИ и уже под неё:
// ведущий не открывает таблицу ради обзора.
//
// Ни одного числа экран не считает: очередь, просрочку, «за рамкой» и счёт
// блокирующих считает сервер — здесь они только названы словами.
import { useCallback, useEffect, useState } from 'react'
import './bridge.css'
import { мостикApi, type Блокер, type Куда, type Мостик } from './bridge.api'
import { Маркер } from './markers'
import { useАвтор } from './research'

/** Учётки проекта — пикеру исполнителя у кнопки «Поручить». */
type Учётка = { login: string; display_name: string }

export function BridgeScreen({ project, onGo }: {
  project: string | null
  /** Уйти в место решения: раздел, сцена, код записи. */
  onGo: (куда: Куда) => void
}) {
  const [вид, setВид] = useState<Мостик | null>(null)
  const [учётки, setУчётки] = useState<Учётка[]>([])
  const [отказ, setОтказ] = useState<string | null>(null)
  const [поручаем, setПоручаем] = useState<Блокер | null>(null)
  const [автор] = useАвтор()

  const перечитать = useCallback(() => {
    if (!project) return
    мостикApi.мостик(project).then(setВид).catch((e) => setОтказ(String(e.message ?? e)))
  }, [project])
  useEffect(перечитать, [перечитать])
  useEffect(() => {
    fetch('/api/auth/users').then((r) => (r.ok ? r.json() : { users: [] }))
      .then((d) => setУчётки(d.users ?? [])).catch(() => setУчётки([]))
  }, [])

  if (!project) {
    return (
      <div className="v2-panel" data-why="почему-нельзя">
        <h3>Мостик</h3>
        <div className="v2-empty">Проект не выбран.<span className="v2-empty__why">Мостик ведёт по фазе проекта.</span></div>
      </div>
    )
  }
  if (отказ) return <div className="v2-panel" data-why="почему-нельзя"><h3>Мостик</h3><div className="v2-locked">{отказ}</div></div>
  if (!вид) return <div className="v2-panel" data-why="работа"><h3>Мостик</h3><div className="v2-empty">Читаю фазу…</div></div>

  const т = вид.route.gate
  return (
    <div className="v2-bridge">
      {/* 1 · Маршрут одной строкой: где фаза, где мы и что ближе всего. */}
      <div className="v2-panel" data-why="следующий-клик">
        <div className="v2-bridge__route">
          <b>{вид.route.phase}</b>
          {вид.route.scene.key && (
            <span>
              <Маркер род="сцена" состояние="текущее" подпись={`${вид.route.scene.key} · ${вид.route.scene.title}`} />
              {' '}сцена <b>{вид.route.scene.key} · {вид.route.scene.title}</b>
            </span>
          )}
          {т.key && (
            <span>
              <Маркер род="точка" состояние={т.blocking > 0 ? 'блок' : 'текущее'} подпись={т.title} />
              {' '}<b>{т.title}</b>
              {т.planned_date && ` · ${т.planned_date}`}
              {вид.route.days_to_gate !== null && ` · ${дней(вид.route.days_to_gate)}`}
              {' · '}
              {т.blocking > 0
                ? <span className="v2-bad">блокирует {т.blocking}</span>
                : <span className="v2-ok">условия выполнены</span>}
            </span>
          )}
          <button type="button" className="v2-link" title="карта фазы: дорожки, окна сцен и точки по датам"
            onClick={() => onGo({ section: 'work' })}>
            карта фазы
          </button>
        </div>
      </div>

      {/* 2 · Решить: очередь, каждая строка — один клик в место решения. */}
      <div className="v2-panel" data-why="работа">
        <h3>
          Решить
          <span className="v2-cnt">{вид.decide.length}{вид.decide_more > 0 ? ` · ещё ${вид.decide_more}` : ''}</span>
        </h3>
        {вид.decide.length === 0 ? (
          <div className="v2-empty">
            Решать нечего.
            <span className="v2-empty__why">Непринятых предложений, открытых замечаний и допущений к точке нет.</span>
          </div>
        ) : (
          <div className="v2-queue">
            {вид.decide.map((с, i) => (
              <div key={`${с.kind}-${i}`} className="v2-queue__row">
                <span className="v2-queue__kind">{с.kind}</span>
                <span>{с.text}</span>
                <button type="button" className="v2-link"
                  title={`перейти туда, где это решается: ${с.where.section}${с.where.scene ? `, сцена ${с.where.scene}` : ''}`}
                  onClick={() => onGo(с.where)}>
                  {с.action}
                </button>
              </div>
            ))}
            {вид.decide_more > 0 && (
              <div className="v2-empty__why">
                ещё {вид.decide_more} — экран держит семь строк: остальное придёт, как только эти решены
              </div>
            )}
          </div>
        )}
      </div>

      {/* 3 · Блокирует точку: причина словами и «Поручить». */}
      <div className="v2-panel" data-why="почему-нельзя">
        <h3>
          {т.key ? `Блокирует ${т.title}` : 'Блокирует ближайшую точку'}
          <span className="v2-cnt">{вид.blockers.length}</span>
        </h3>
        {вид.blockers.length === 0 ? (
          <div className="v2-empty">
            Точку ничто не держит.
            <span className="v2-empty__why">Все разрывы её сцен закрыты — решение можно фиксировать.</span>
          </div>
        ) : (
          <div className="v2-queue">
            {вид.blockers.map((б, i) => (
              <div key={`${б.scene}-${i}`} className="v2-queue__row">
                <span className="v2-queue__kind">
                  сцена {б.scene}
                  {б.waiting && <span className="v2-dim"> · ждёт входа</span>}
                </span>
                <span>
                  {б.why}
                  {б.assignment && (
                    <span className="v2-dim"> · поручено {б.assignment.assignee} к {б.assignment.due_point}</span>
                  )}
                </span>
                <span className="v2-inline">
                  <button type="button" className="v2-link" title={`открыть сцену «${б.scene_title}» — разрыв закрывается работой в ней`}
                    onClick={() => onGo({ section: 'work', scene: б.scene })}>
                    к месту
                  </button>
                  <button type="button" disabled={Boolean(б.assignment)}
                    title={б.assignment
                      ? `уже поручено ${б.assignment.assignee} к ${б.assignment.due_point} — закройте поручение в «Команде», чтобы поручить заново`
                      : 'поручить этот разрыв: исполнитель и срок-точка'}
                    onClick={() => setПоручаем(б)}>
                    Поручить
                  </button>
                </span>
              </div>
            ))}
          </div>
        )}
        {поручаем && (
          <ФормаПоручения project={project} блокер={поручаем} точки={вид.points} учётки={учётки} автор={автор}
            onОтмена={() => setПоручаем(null)}
            onГотово={() => { setПоручаем(null); перечитать() }}
            onОтказ={setОтказ} />
        )}
      </div>

      {/* 4 · Команда: кто чем занят и что просрочено. */}
      <div className="v2-panel" data-why="следующий-клик">
        <h3>
          Команда
          <span className="v2-cnt">
            {вид.team.filter((п) => !п.done).length} в работе
            {вид.team.some((п) => п.overdue) ? ` · просрочено ${вид.team.filter((п) => п.overdue).length}` : ''}
          </span>
        </h3>
        {вид.team.length === 0 ? (
          <div className="v2-empty">
            Поручений нет.
            <span className="v2-empty__why">Они ставятся отсюда: от разрыва, который держит точку.</span>
          </div>
        ) : (
          <table className="v2-table">
            <thead><tr><th>Кто</th><th>Что</th><th>Срок</th><th>Состояние</th><th /></tr></thead>
            <tbody>
              {вид.team.map((п) => (
                <tr key={п.code}>
                  <td>{п.assignee}</td>
                  <td>
                    {п.what || п.scene_title || п.target.ref}
                    <div className="v2-dim">сцена {п.scene}{п.assigned_by ? ` · поручил ${п.assigned_by}` : ''}</div>
                  </td>
                  <td>{п.due_point_title || п.due_point}{п.due_date ? ` · ${п.due_date}` : ''}</td>
                  <td>
                    {п.done
                      ? <span className="v2-ok">закрыто</span>
                      : п.overdue
                        ? <span className="v2-bad">просрочено</span>
                        : <span>в работе</span>}
                  </td>
                  <td>
                    {!п.done && (
                      <button type="button" className="v2-link" title="закрыть поручение: работа сделана"
                        onClick={() => мостикApi.завершить(project, п.code).then(перечитать).catch((e) => setОтказ(String(e.message ?? e)))}>
                        Закрыть
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {/* 5 · Сигналы: три-четыре числа, красное — только за рамкой. */}
      <div className="v2-panel" data-why="почему-нельзя">
        <h3>Сигналы<span className="v2-cnt">{вид.signals.length}</span></h3>
        {вид.signals.length === 0 ? (
          <div className="v2-empty">
            Сигналов нет.
            <span className="v2-empty__why">
              Их считают свёртки: масса с рамкой, риски, разрывы TRL, покрытие нужд. Нет свёртки — нет и числа.
            </span>
          </div>
        ) : (
          <div className="v2-signals">
            {вид.signals.map((с) => (
              <button key={с.name} type="button"
                className={с.over ? 'v2-signal v2-signal--over' : 'v2-signal'}
                title={`${с.note || с.name}${с.limit ? ` · предел ${с.limit}` : ''} — открыть, где это считается`}
                onClick={() => onGo(с.where)}>
                <span className="v2-signal__n">{с.name}</span>
                <div className="v2-signal__v">{с.value}</div>
                <span className="v2-signal__n">
                  {с.limit ? `предел ${с.limit}` : 'предела нет'}
                  {с.over ? ' · за рамкой' : ''}
                </span>
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}

/** Дней до точки словами: число без слова читается как код. */
function дней(сколько: number): string {
  if (сколько < 0) return `просрочена на ${-сколько} дн.`
  if (сколько === 0) return 'сегодня'
  return `через ${сколько} дн.`
}

/** Поручить разрыв: исполнитель и срок-точка — оба обязательны. */
function ФормаПоручения({ project, блокер, точки, учётки, автор, onОтмена, onГотово, onОтказ }: {
  project: string
  блокер: Блокер
  точки: { key: string; title: string; planned_date: string }[]
  учётки: Учётка[]
  автор: string
  onОтмена: () => void
  onГотово: () => void
  onОтказ: (т: string) => void
}) {
  const [исполнитель, setИсполнитель] = useState('')
  const [срок, setСрок] = useState(точки[0]?.key ?? '')
  const [занято, setЗанято] = useState(false)
  const помеха = !исполнитель ? 'исполнитель не выбран' : !срок ? 'срок-точка не выбран' : null
  return (
    <div className="v2-form v2-form--row" data-why="работа">
      <span className="v2-empty__why">поручить: {блокер.why}</span>
      <label className="v2-inline">
        исполнитель
        <select value={исполнитель} aria-label="исполнитель поручения" onChange={(e) => setИсполнитель(e.target.value)}>
          <option value="">— учётка —</option>
          {учётки.map((у) => <option key={у.login} value={у.login}>{у.display_name}</option>)}
        </select>
      </label>
      <label className="v2-inline">
        срок
        <select value={срок} aria-label="срок поручения — точка фазы" onChange={(e) => setСрок(e.target.value)}>
          {точки.map((т) => (
            <option key={т.key} value={т.key}>{т.title}{т.planned_date ? ` · ${т.planned_date}` : ''}</option>
          ))}
        </select>
      </label>
      <button type="button" className="v2-primary" disabled={Boolean(помеха) || занято}
        title={помеха ?? `поручить ${исполнитель} к ${срок}`}
        onClick={() => {
          setЗанято(true)
          мостикApi.поручить(project, {
            target: { kind: 'gap', ref: блокер.scene },
            assignee: исполнитель, due_point: срок, what: блокер.why, author: автор || 'ведущий',
          })
            .then(onГотово)
            .catch((e) => onОтказ(String(e.message ?? e)))
            .finally(() => setЗанято(false))
        }}>
        {занято ? 'Поручаю…' : 'Поручить'}
      </button>
      <button type="button" className="v2-link" title="закрыть форму поручения" onClick={onОтмена}>отмена</button>
      {помеха && <span className="v2-empty__why">Нажать нельзя: {помеха}.</span>}
    </div>
  )
}
