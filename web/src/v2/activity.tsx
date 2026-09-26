// Экран мероприятия глазами инженера (`эталоны/reference-activity-engineer.html`,
// шип 2, экран 7).
//
// Четыре элемента и ни одного лишнего — состав приходит из `density.ts`,
// где у каждого записано, на какой из трёх вопросов он отвечает:
//   · контекст — одна строка: предыдущее → вы здесь → следующее, заголовок,
//     сцена и роль (следующий клик; сцена названа здесь и только здесь);
//   · поверхность — то, с чем работают, во всю ширину (работа);
//   · строка выходов — счётчики словами и «не даёт завершить» (почему нельзя);
//   · рейка 128 px — условия, входы, «откроет», схема фазы: слово и счётчик;
//     раскрывается ВНУТРИ рейки, третьей колонки не бывает (следующий клик).
//
// В плотности «сцена» (ведущий СИ) контекст и рейка не рисуются: сцену с её
// потоком мероприятий показывает экран работы, а вместо рейки — строка
// выходов, как велит задание экрана 4 («правой рейки в режиме сцены нет»).
// Проект и фаза живут в ШАПКЕ оболочки и здесь не повторяются (§4).
import { useEffect, useRef, useState, type ReactNode } from 'react'
import { api, type Activity, type DocHint, type Phase, type Scene, type SceneSuggestions } from './api'
import { DocumentBody } from './documents'
import { ResearchPanel } from './research'
import { пунктыРейки, составЭкрана, type Блок, type ПунктРейки, type Режим } from './density'
import { Маркер as МаркерЗдоровья } from './ui/tabs'

/**
 * Сцены, у которых есть внешний контур полноты: 3 стороны · 4 повестка ·
 * 5 нормы · 7 аналоги · 12 бенчмарки. Перечень тот же, что у сервера, —
 * на прочих сценах адрес исследования отказывает, и звать его незачем.
 */
const СЦЕНЫ_ИССЛЕДОВАНИЯ = ['3', '4', '5', '7', '12']

/** Роль мероприятия и сцены — по-русски: служебное имя роли инженеру не говорит. */
export const РОЛЬ: Record<string, string> = {
  lead: 'руководитель',
  lead_se: 'ведущий СИ',
  specialist: 'инженер',
  sma: 'обеспечение качества',
  da_review: 'решающий орган',
  reader: 'наблюдатель',
}

/** Рейка говорит словами (эталон): слово и счётчик рядом, под ними — что за пунктом. */
const СЛОВО_РЕЙКИ: Record<ПунктРейки['key'], string> = {
  условия: 'Условия', входы: 'Входы', откроет: 'Откроет', схема: 'Схема фазы',
}

export function ActivityScreen({
  project, phase, scene, activity, режим, роль, onPickActivity, onPhaseMap, onChanged, children,
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
  /** Принятое из знаний меняет поверхность — её надо перечитать. */
  onChanged?: () => void
  children: ReactNode
}) {
  const [открыта, setОткрыта] = useState<string | null>(null)
  /** Поверхность — куда ведёт «к месту» из условия. */
  const поверхностьRef = useRef<HTMLDivElement>(null)
  /** Куда попадает работа сцены: строка «в документ» (ЗАДАНИЕ-ДОКУМЕНТЫ §3). */
  const [вДокумент, setВДокумент] = useState<DocHint[]>([])
  const [разделОткрыт, setРазделОткрыт] = useState<DocHint | null>(null)
  const [показать, setПоказать] = useState(false)

  /** Предложения из записки: сцена начинается не с пустой формы. */
  const [предложения, setПредложения] = useState<SceneSuggestions | null>(null)
  const [берём, setБерём] = useState(false)

  const перечитатьПредложения = () => {
    api.suggestions(project, scene.key)
      .then((r) => setПредложения(r.actions.length > 0 ? r : null))
      .catch(() => setПредложения(null))
  }

  useEffect(() => {
    setРазделОткрыт(null)
    api.docHints(project, scene.key)
      .then((r) => setВДокумент(r.items))
      .catch(() => setВДокумент([]))
    перечитатьПредложения()
  }, [project, scene.key])

  const вСцене = режим === 'сцена'
  const состав = составЭкрана(режим)
  const дела = scene.activities
  const индекс = activity ? дела.findIndex((д) => д.code === activity.code) : -1
  const прошлое = индекс > 0 ? дела[индекс - 1] : undefined
  const следующее = индекс >= 0 && индекс + 1 < дела.length ? дела[индекс + 1] : undefined

  const условия = scene.exit
  const закрыто = условия.filter((у) => у.passed).length
  const незакрытые = условия.filter((у) => !у.passed)
  const держит = activity?.blocked_by[0] ?? незакрытые[0]?.why ?? незакрытые[0]?.title
  /** Красная строка одна, а причин может быть больше: остальные — подсказкой. */
  const держатВсе = [...(activity?.blocked_by ?? []), ...незакрытые.map((у) => у.why ?? у.title)]
  const свои = activity?.outputs.filter((в) => !в.produced_in) ?? []
  const чужие = activity?.outputs.filter((в) => в.produced_in) ?? []
  const рейка = пунктыРейки({
    условийВсего: условия.length,
    условийЗакрыто: закрыто,
    входов: activity?.inputs.length ?? 0,
    откроет: activity?.opens.length ?? 0,
  })
  const почему = (ключ: string) => состав.find((б: Блок) => б.key === ключ)

  /** Что стоит за пунктом рейки — словами, как в эталоне («замысел · записка»). */
  const заПунктом = (ключ: ПунктРейки['key']): string => {
    if (ключ === 'условия') return 'что не даёт завершить'
    if (ключ === 'входы') return (activity?.inputs ?? []).slice(0, 2).join(' · ')
    if (ключ === 'откроет') return (activity?.opens ?? []).slice(0, 3).join(' · ')
    return 'где я в фазе'
  }

  /**
   * «К месту» из условия ведёт В ПОВЕРХНОСТЬ, а не в другой раздел: карточка,
   * чьё имя названо в условии, подводится к глазам; не нашлась — поверхность
   * целиком. Соответствие сверяется двумя именами из данных, а не таблицей
   * в коде (тот же приём, что у перехода из документа в work.tsx).
   */
  const кМесту = (текст: string) => {
    setОткрыта(null)
    const корень = поверхностьRef.current
    if (!корень) return
    const карточки = [...корень.querySelectorAll<HTMLElement>('.v2-card__title, .v2-panel > h3')]
    const своя = карточки.find((э) => {
      const имя = (э.childNodes[0]?.textContent ?? '').trim()
      return имя.length > 3 && текст.toLowerCase().includes(имя.toLowerCase())
    })
    ;(своя ?? корень).scrollIntoView({ block: своя ? 'center' : 'start' })
  }

  return (
    <div className={вСцене ? 'v2-act2 v2-act2--scene' : открыта ? 'v2-act2 v2-act2--open' : 'v2-act2'}>
      <div className="v2-act2__col">
        {!вСцене && (
          <div className="v2-act2__ctx" data-why={почему('контекст')?.зачем}
            title={почему('контекст')?.почему}>
            {activity && (
              <span className="v2-act2__thread">
                {прошлое && (
                  <>
                    <button type="button" className="v2-link" title={`к мероприятию ${прошлое.code} · ${прошлое.name}`}
                      onClick={() => onPickActivity(прошлое.code)}>{прошлое.code}</button>
                    {' → '}
                  </>
                )}
                <b>{activity.code} · вы здесь</b>
                {следующее && (
                  <>
                    {' → '}
                    <button type="button" className="v2-link" title={`к мероприятию ${следующее.code} · ${следующее.name}`}
                      onClick={() => onPickActivity(следующее.code)}>{следующее.code}</button>
                  </>
                )}
              </span>
            )}
            <h1 className="v2-act2__h">
              {activity ? `${activity.code} · ${activity.name}` : `${scene.key} · ${scene.title}`}
            </h1>
            {activity && (
              <span className="v2-dim" title={scene.question || `сцена ${scene.key}`}>
                сцена {scene.key} «{scene.title}» · {РОЛЬ[activity.role] ?? activity.role}
                {роль === activity.role ? ' — вы исполняете' : ''}
              </span>
            )}
          </div>
        )}

        <div ref={поверхностьRef} className="v2-act2__surface" data-why={почему('поверхность')?.зачем}
          title={почему('поверхность')?.почему}>
          {предложения && предложения.task && (
            <div className="v2-prop" data-why="работа"
              title="предложения поля знаний: принятое встанет в таблицу с провенансом">
              <span>{предложения.summary}</span>
              <button type="button" className="v2-prop__go" disabled={берём}
                title={предложения.actions.map((д) => д.preview).join(' · ')}
                onClick={() => {
                  setБерём(true)
                  api.acceptPlan(project, предложения.task!, предложения.indices, 'инженер')
                    .then(() => { setБерём(false); перечитатьПредложения(); onChanged?.() })
                    .catch(() => setБерём(false))
                }}>
                {берём ? 'принимаю…' : 'Принять'}
              </button>
              <button type="button" className="v2-link"
                title="что именно появится — до нажатия"
                onClick={() => setПоказать(!показать)}>
                {показать ? 'свернуть' : 'посмотреть'}
              </button>
            </div>
          )}
          {показать && предложения && (
            <ul className="v2-prop__list">
              {предложения.actions.map((д) => (
                <li key={д.index}>{д.preview}
                  {д.facts.length > 0 && <span className="v2-dim"> · факты: {д.facts.join(', ')}</span>}
                </li>
              ))}
            </ul>
          )}
          {/* Полнота: чего в поле нет — спрашивается у внешнего контура, рядом
              с предложениями поля. Панель одна на сцену и на точку. */}
          {СЦЕНЫ_ИССЛЕДОВАНИЯ.includes(scene.key) && (
            <ResearchPanel project={project} trigger={{ scene: scene.key }}
              onChanged={() => { перечитатьПредложения(); onChanged?.() }} />
          )}
          <div id="v2-act-surface">{children}</div>
        </div>

        {/*
          Строка выходов (шип 5 §6): «не даёт завершить» — первым и с дорогой к
          месту (поверхность сцены выше); выходы — маркерами со словами.
        */}
        <div className="v2-act2__foot" data-why={почему('выходы')?.зачем}>
          {держит && (
            <span className="v2-bad" title={держатВсе.join('; ')}>
              <МаркерЗдоровья health="block" title="держит завершение сцены" />
              не даёт завершить: {держит}
              {держатВсе.length > 1 && ` (и ещё ${держатВсе.length - 1})`}
              {' '}
              <button type="button" className="v2-link" title="к месту: поверхность сцены, где это закрывается"
                onClick={() => document.getElementById('v2-act-surface')?.scrollIntoView({ block: 'start', behavior: 'smooth' })}>
                к месту
              </button>
            </span>
          )}
          <span>
            <span className="v2-dim">выходы: </span>
            {свои.length === 0 && чужие.length === 0 && <span className="v2-dim">не заданы</span>}
            {/*
              Счётчик СЛОВАМИ (журнал ПМИ-7, З-08): «требования к системе 12/3»
              не говорит ничего — владелец 19.09: «опять встали, непонятно на
              чём». Теперь видно и сколько есть, и сколько ждут, и чего не
              хватает.
            */}
            {свои.map((в, i) => (
              <span key={в.kind + в.what} className={в.satisfied ? 'v2-ok' : 'v2-warn'}
                title={в.satisfied ? `${в.what}: есть` : `${в.what}: не хватает ${в.min - в.count}`}>
                {i > 0 && ' · '}
                <МаркерЗдоровья health={в.satisfied ? 'ok' : 'debt'} title={в.satisfied ? 'выход есть' : 'выхода не хватает'} />
                {в.what} {в.count}{в.min > 0 && ` из ${в.min}`}
                {!в.satisfied && в.min > 0 && ` — не хватает ${в.min - в.count}`}
              </span>
            ))}
            {чужие.map((в) => (
              <span key={в.kind + в.what} className="v2-dim"> · {в.what} → сцена {в.produced_in}</span>
            ))}
          </span>
          <span className="v2-act2__sp" />
          {!держит && <span className="v2-ok"><МаркерЗдоровья health="ok" title="условия выхода выполнены" />условия выполнены</span>}
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

      {!вСцене && (
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
              <b>{СЛОВО_РЕЙКИ[п.key]}{п.счёт && ` ${п.счёт}`}</b>
              {заПунктом(п.key)}
            </button>
          ))}
          {/* Панель раскрывается ВНУТРИ рейки: колонка становится шире (280),
              третьей не появляется — запрет §4 стережёт сторож двух дорожек. */}
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
                      {/* Действие там же, где назван разрыв: в поверхность, не в другой раздел. */}
                      {!у.passed && (
                        <button type="button" className="v2-link" title="к месту: поверхность, где это закрывается"
                          onClick={() => кМесту(у.why ?? у.title)}>к месту</button>
                      )}
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
        </aside>
      )}
    </div>
  )
}
