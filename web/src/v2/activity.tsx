// Экран мероприятия глазами инженера (`эталоны/reference-activity-engineer.html`).
//
// Четыре элемента и ни одного лишнего — состав приходит из `density.ts`,
// где у каждого записано, на какой из трёх вопросов он отвечает:
//   · контекст — одна строка: где я и куда шаг (следующий клик);
//   · поверхность — то, с чем работают (работа);
//   · строка выходов — счётчики и что не даёт завершить (почему нельзя);
//   · рейка — условия, входы, «откроет», схема (следующий клик).
//
// Проект, фаза и сцена живут в ШАПКЕ оболочки и здесь не повторяются
// (запрет §4: дублирование контекста).
import { useEffect, useState, type ReactNode } from 'react'
import { api, type Activity, type DocHint, type Phase, type Scene } from './api'
import { DocumentBody } from './documents'
import { пунктыРейки, составЭкрана, type Блок, type Режим } from './density'

/** Роль мероприятия — по-русски: служебное имя роли инженеру не говорит. */
const РОЛЬ: Record<string, string> = {
  lead: 'руководитель',
  lead_se: 'ведущий СИ',
  specialist: 'инженер',
  sma: 'обеспечение качества',
  da_review: 'решающий орган',
  reader: 'наблюдатель',
}

export function ActivityScreen({
  project, phase, scene, activity, режим, роль, onPickActivity, onPhaseMap, children,
}: {
  project: string
  phase: Phase
  scene: Scene
  activity?: Activity
  режим: Режим
  /** Моя роль в проекте: подпись «вы исполняете» — факт, а не догадка. */
  роль: string | null
  onPickActivity: (code: string) => void
  onPhaseMap: () => void
  children: ReactNode
}) {
  const [открыта, setОткрыта] = useState<string | null>(null)
  /** Куда попадает работа сцены: строка «в документ» (ЗАДАНИЕ-ДОКУМЕНТЫ §3). */
  const [вДокумент, setВДокумент] = useState<DocHint[]>([])
  const [разделОткрыт, setРазделОткрыт] = useState<DocHint | null>(null)

  useEffect(() => {
    setРазделОткрыт(null)
    api.docHints(project, scene.key)
      .then((r) => setВДокумент(r.items))
      .catch(() => setВДокумент([]))
  }, [project, scene.key])
  const состав = составЭкрана(режим)
  const дела = scene.activities
  const индекс = activity ? дела.findIndex((д) => д.code === activity.code) : -1
  const прошлое = индекс > 0 ? дела[индекс - 1] : undefined
  const следующее = индекс >= 0 && индекс + 1 < дела.length ? дела[индекс + 1] : undefined

  const условия = scene.exit
  const закрыто = условия.filter((у) => у.passed).length
  const держит = activity?.blocked_by[0]
    ?? условия.find((у) => !у.passed)?.why
    ?? условия.find((у) => !у.passed)?.title
  const свои = activity?.outputs.filter((в) => !в.produced_in) ?? []
  const чужие = activity?.outputs.filter((в) => в.produced_in) ?? []
  const рейка = пунктыРейки({
    условийВсего: условия.length,
    условийЗакрыто: закрыто,
    входов: activity?.inputs.length ?? 0,
    откроет: activity?.opens.length ?? 0,
  })
  const почему = (ключ: string) => состав.find((б: Блок) => б.key === ключ)

  return (
    <div className={открыта ? 'v2-act2 v2-act2--open' : 'v2-act2'}>
      <div className="v2-act2__col">
        <div className="v2-act2__ctx" data-why={почему('контекст')?.зачем}
          title={почему('контекст')?.почему}>
          {прошлое && (
            <button type="button" className="v2-link" title={`к мероприятию ${прошлое.code} · ${прошлое.name}`}
              onClick={() => onPickActivity(прошлое.code)}>← {прошлое.code}</button>
          )}
          {следующее && (
            <button type="button" className="v2-link" title={`к мероприятию ${следующее.code} · ${следующее.name}`}
              onClick={() => onPickActivity(следующее.code)}>{следующее.code} →</button>
          )}
          <span className="v2-dim">вы здесь</span>
          <h1 className="v2-act2__h">
            {activity ? `${activity.code} · ${activity.name}` : `${scene.key} · ${scene.title}`}
          </h1>
          {activity && (
            <span className="v2-dim">
              {РОЛЬ[activity.role] ?? activity.role}
              {роль === activity.role ? ' · вы исполняете' : ''}
            </span>
          )}
        </div>

        <div className="v2-act2__surface" data-why={почему('поверхность')?.зачем}
          title={почему('поверхность')?.почему}>
          {children}
        </div>

        <div className="v2-act2__foot" data-why={почему('выходы')?.зачем}>
          <span>
            <span className="v2-dim">выходы: </span>
            {свои.length === 0 && чужие.length === 0 && <span className="v2-dim">не заданы</span>}
            {свои.map((в) => (
              <span key={в.kind + в.what} className={в.satisfied ? 'v2-ok' : 'v2-warn'}>
                {в.what} <b>{в.count}</b>{в.min > 0 && `/${в.min}`}{'  '}
              </span>
            ))}
            {чужие.map((в) => (
              <span key={в.kind + в.what} className="v2-dim">{в.what} → сцена {в.produced_in}{'  '}</span>
            ))}
          </span>
          <span className="v2-act2__sp" />
          {держит
            ? <span className="v2-bad">не даёт завершить: {держит}</span>
            : <span className="v2-ok">условия выполнены</span>}
        </div>
        {вДокумент.length > 0 && (
          <div className="v2-act2__doc" data-why={почему('документ')?.зачем}
            title={почему('документ')?.почему}>
            <span className="v2-dim">в документ: </span>
            {вДокумент.map((п) => (
              <button key={п.section} type="button" className="v2-link"
                title={`${п.document}, раздел ${п.section} «${п.section_title}» — открыть так, как он напечатается`}
                onClick={() => setРазделОткрыт(разделОткрыт?.section === п.section ? null : п)}>
                {п.document} {п.section} · {п.filled} из {п.elements} элементов · посмотреть
              </button>
            ))}
          </div>
        )}
        {разделОткрыт && (
          <DocumentBody project={project} code="mcreport" section={разделОткрыт.section} />
        )}
      </div>

      <aside className="v2-act2__rail" aria-label="контекст мероприятия">
        {рейка.map((п) => (
          <button key={п.key} type="button"
            className={открыта === п.key ? 'v2-act2__r v2-act2__r--on' : 'v2-act2__r'}
            aria-pressed={открыта === п.key}
            data-why={п.зачем}
            title={п.подсказка}
            onClick={() => (п.key === 'схема'
              ? onPhaseMap()
              : setОткрыта(открыта === п.key ? null : п.key))}>
            {п.знак}{п.счёт && <b>{п.счёт}</b>}
          </button>
        ))}
      </aside>

      {открыта && (
        <div className="v2-act2__panel" data-why="почему-нельзя">
          <div className="v2-act2__ph">
            {открыта === 'условия' && `Условия завершения · ${закрыто} из ${условия.length}`}
            {открыта === 'входы' && 'Входы мероприятия'}
            {открыта === 'откроет' && 'Что откроет эта работа'}
            <button type="button" className="v2-link" title="свернуть рейку"
              onClick={() => setОткрыта(null)}>свернуть</button>
          </div>
          {открыта === 'условия' && (
            <ul className="v2-act2__list">
              {условия.map((у) => (
                <li key={у.check} className={у.passed ? 'v2-c v2-c--ok' : 'v2-c v2-c--no'}>
                  {у.title}
                  {!у.passed && у.why && <span className="v2-empty__why">{у.why}</span>}
                </li>
              ))}
            </ul>
          )}
          {открыта === 'входы' && (
            <ul className="v2-act2__list">
              {(activity?.inputs ?? []).map((в) => <li key={в} className="v2-c">{в}</li>)}
            </ul>
          )}
          {открыта === 'откроет' && (
            <ul className="v2-act2__list">
              {(activity?.opens ?? []).map((о) => (
                <li key={о} className="v2-c">
                  {дела.some((д) => д.code === о)
                    ? (
                      <button type="button" className="v2-link" title={`к мероприятию ${о}`}
                        onClick={() => { setОткрыта(null); onPickActivity(о) }}>
                        {о} · {дела.find((д) => д.code === о)?.name}
                      </button>
                    )
                    : о}
                </li>
              ))}
              {phase.gates.filter((т) => !т.passed).slice(0, 1).map((т) => (
                <li key={т.key} className="v2-c v2-dim">ближайшая точка: {т.title}</li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  )
}
