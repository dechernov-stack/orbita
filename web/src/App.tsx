// Оболочка (блок D, дизайн docs/ui/reference): шапка 48 px с проектом, фазой
// и ближайшей точкой; рейка разделов 52 px; панель операций 236 px — экраны
// раздела и операции фазы с состоянием от сервера (ADR-029); рабочая область.
// Мастер как меню упразднён: навигация показывает состояние операций и
// незакрытое до точки, а не список экранов.
import { Accounts } from './ui/Accounts'
import { useCallback, useEffect, useState, type ReactElement } from 'react'
import { api, type OperationRow } from './api/client'
import { edit } from './api/edit'
import { currentProject, selectProject } from './api/project'
import { SECTIONS, sectionOf } from './nav'
import { AiProfiles } from './screens/AiProfiles'
import { AiProposal } from './screens/AiProposal'
import { AiService } from './screens/AiService'
import { BatchLoad } from './screens/BatchLoad'
import { NewProject } from './screens/NewProject'
import { SeedDemand } from './screens/SeedDemand'
import { Shelves } from './screens/Shelves'
import { References } from './screens/References'
import { StatementGuide } from './ui/StatementGuide'
import { ReviewInspection } from './screens/ReviewInspection'
import { TaskFrameBar } from './ui/TaskFrame'
import { Results } from './screens/Results'
import type { PhaseWorkView } from './api/types'
import { PhaseWork, NextStepBadge } from './screens/PhaseWork'
import { StakeholderCoverage } from './screens/StakeholderCoverage'
import { LibraryKnowledge } from './screens/LibraryKnowledge'
import { DocParse } from './screens/DocParse'
import { MyTasks, MyTasksBadge } from './screens/MyTasks'
import { StartPath } from './screens/StartPath'
import { Comparison } from './screens/Comparison'
import { ConstellationCompare } from './screens/ConstellationCompare'
import { Coverage } from './screens/Coverage'
import { Demand } from './screens/Demand'
import { Documents } from './screens/Documents'
import { GateReadiness } from './screens/GateReadiness'
import { Globe } from './screens/Globe'
import { GroundSegment } from './screens/GroundSegment'
import { ImportCatalog } from './screens/ImportCatalog'
import { KindRegistry } from './screens/KindRegistry'
import { Lifecycle } from './screens/Lifecycle'
import { Needs } from './screens/Needs'
import { Operations } from './screens/Operations'
import { Portfolio } from './screens/Portfolio'
import { RequirementMatrices, type MatrixKind } from './screens/RequirementMatrices'
import { Requirements } from './screens/Requirements'
import { Risks } from './screens/Risks'
import { Spacecraft } from './screens/Spacecraft'
import { SystemComposition } from './screens/SystemComposition'
import { TraceGraph } from './screens/TraceGraph'
import { ExternalModel } from './screens/ExternalModel'
import { SystemModels } from './screens/SystemModels'
import { Architecture } from './screens/Architecture'
import { SystemOverview } from './screens/SystemOverview'
import { AuthorField, LoginGate, useSession } from './ui/session'

interface HeaderInfo {
  name: string
  phase: string
  nextGate: string | null
  unclosed: number | null
  operations: OperationRow[]
}

/** Иконки рейки — контуры эталона reference2 (штрих 1.6, 18 px);
    «Концепция» и «Инструменты» дорисованы в той же стилистике. */
const RAIL_ICONS: Record<string, ReactElement> = {
  portfolio: (
    <>
      <rect x="4" y="4" width="7" height="7" rx="1.5" />
      <rect x="13" y="4" width="7" height="7" rx="1.5" />
      <rect x="4" y="13" width="7" height="7" rx="1.5" />
      <rect x="13" y="13" width="7" height="7" rx="1.5" />
    </>
  ),
  library: (
    <>
      <path d="M12 6c-2-1.4-5-1.4-7 0v12c2-1.4 5-1.4 7 0c2-1.4 5-1.4 7 0V6c-2-1.4-5-1.4-7 0z" />
      <path d="M12 6v12" />
    </>
  ),
  documents: (
    <>
      <path d="M6 3h8l4 4v14H6z" />
      <path d="M14 3v4h4" />
    </>
  ),
  goals: (
    <>
      <circle cx="12" cy="12" r="8" />
      <circle cx="12" cy="12" r="2.6" />
    </>
  ),
  req: <path d="M5 5h14M5 10h14M5 15h9M5 20h6" />,
  aoa: (
    <>
      <circle cx="12" cy="6" r="2.5" />
      <circle cx="6" cy="18" r="2.5" />
      <circle cx="18" cy="18" r="2.5" />
      <path d="M10.8 8.2 7.2 15.8M13.2 8.2l3.6 7.6" />
    </>
  ),
  ballistics: (
    <>
      <circle cx="12" cy="12" r="8" />
      <path d="M2.5 12c3-4 16-4 19 0" />
    </>
  ),
  readiness: <path d="M4 19V5m0 14h16M8 15l4-6 4 3 4-7" />,
  ai: (
    <>
      <path d="M9 4h6l1 3 3 1v6l-3 1-1 3H9l-1-3-3-1V8l3-1z" />
      <circle cx="12" cy="11.5" r="2.4" />
    </>
  ),
}

const PHASE_LABEL: Record<string, string> = { pre_phase_a: 'Pre-Phase A', phase_a: 'Phase A' }

/** Расшифровки состояния операции — подсказками к маркерам (§2.3). */
const STATE_TITLES: Record<string, string> = {
  Done: 'выполнена — выход достиг требуемого статуса',
  InProgress: 'в работе — выход есть, статус ниже требуемого',
  NotStarted: 'не начата — выхода ещё нет',
  NotMeasurable: 'нечем измерить — вид выхода в системе отсутствует',
}

/** Спецэкран на шапке экрана (§3.1): заголовок 40 px + рабочая область. */
function ScreenFrame({ title, children }: { title: string; children: ReactElement }) {
  return (
    <>
      <div className="toolbar"><h2>{title}</h2></div>
      <div className="workarea" style={{ display: 'flex', flexDirection: 'column' }}>{children}</div>
    </>
  )
}

/** Шаблон «Матрица» (§3.4): трассировка, верификация, валидация вкладками. */
function MatrixScreen() {
  const [kind, setKind] = useState<MatrixKind>('trace')
  const KINDS: Array<[MatrixKind, string]> = [
    ['trace', 'Трассировка'],
    ['verification', 'Верификация'],
    ['validation', 'Валидация'],
    ['functions', 'Функции × узлы'],
    ['coverage', 'Покрытие требований'],
  ]
  return (
    <>
      <div className="toolbar">
        <h2>Матрицы</h2>
        <div className="tabs">
          {KINDS.map(([k, t]) => (
            <button key={k} className="tab" aria-selected={kind === k} onClick={() => setKind(k)}>
              {t}
            </button>
          ))}
        </div>
      </div>
      <div className="workarea">
        <RequirementMatrices kind={kind} />
      </div>
    </>
  )
}

export function App() {
  const [screen, setScreen] = useState<string>(currentProject() ? 'lifecycle' : 'portfolio')
  /** Первая установка: в портфеле нет проектов — форма с вводной строкой. */
  const [firstRun, setFirstRun] = useState(false)
  const [info, setInfo] = useState<HeaderInfo | null>(null)
  /** Запомненный проект исчез со стенда — банер в портфеле объясняет, почему вы здесь. */
  const [lostProject, setLostProject] = useState<string | null>(null)
  const project = currentProject()
  const section = sectionOf(screen)

  const loadHeader = useCallback(() => {
    if (!project) { setInfo(null); return }
    api.operations()
      .then(async (ops) => {
        let name = project
        try {
          const p = await edit.object(project)
          name = ((p.doc as { name?: string } | undefined)?.name) ?? project
        } catch { /* имя не критично */ }
        let unclosed: number | null = null
        if (ops.next_gate) {
          try {
            unclosed = (await api.gateIssues(ops.next_gate)).issues.length
          } catch { unclosed = null }
        }
        setInfo({ name, phase: ops.phase, nextGate: ops.next_gate, unclosed, operations: ops.operations })
      })
      .catch((e) => {
        setInfo(null)
        // Проект, запомненный браузером, удалён со стенда (перезапуск прогона):
        // не показывать ошибку API поверх пустого экрана, а честно вернуть в
        // портфель и сказать, что случилось (находка сброса стенда 2026-09-02)
        if (/не найден/.test(String(e))) {
          setLostProject(project)
          selectProject(null)
          setScreen('portfolio')
        }
      })
  }, [project])

  useEffect(loadHeader, [loadHeader, screen])

  /** Ф-12: переход к месту действия — с преднастроенным видом операции. */
  const [pendingKind, setPendingKind] = useState<string | undefined>(undefined)
  const [pendingDoc, setPendingDoc] = useState<string | undefined>(undefined)
  /**
   * Переход к месту действия. Третий параметр — конкретный объект либо
   * шаблон документа: переход обязан открывать ТО, ради чего он сделан, а
   * не общий список, где инженер ищет заново (наблюдение живого прохода).
   */
  const go = (next: string, kind?: string, target?: string) => {
    setPendingKind(kind)
    setPendingDoc(target)
    setScreen(next)
  }

  /**
   * Круг 3: рамка ведения — РЕЖИМ, а не экран. Сверху степпер задачи, ниже
   * тот же самый экран системы, преднастроенный шагом. Собственных форм у
   * рамки нет по построению: она добавляет одну строку, всё рабочее рисует
   * встроенный экран.
   */
  const session = useSession()
  const [frame, setFrame] = useState<{ task: string; step: number } | null>(null)
  const [frameTask, setFrameTask] = useState<PhaseWorkView['items'][number] | null>(null)
  const [frameBusy, setFrameBusy] = useState(false)

  const открытьШаг = (task: PhaseWorkView['items'][number], index: number) => {
    const шаг = task.steps[index]
    setFrame({ task: task.id, step: index })
    setFrameTask(task)
    if (шаг?.screen) go(шаг.screen, шаг.kind, шаг.document_code)
  }

  /**
   * Ведение задачи: по умолчанию — с первого незакрытого шага, а если пришли
   * с конкретного шага (круг 6: клик подзадачи на Ганте) — прямо с него.
   */
  const вестиЗадачу = (taskId: string, шаг?: number) => {
    api.phaseWork()
      .then((v) => {
        const задача = v.items.find((t) => t.id === taskId)
        if (!задача) return
        const первый = задача.steps.findIndex((s) => !s.done)
        открытьШаг(задача, шаг ?? (первый >= 0 ? первый : 0))
      })
      .catch(() => undefined)
  }

  /** Перечитать задачу: шаг мог закрыться работой на встроенном экране. */
  const обновитьРамку = (следующий?: boolean) => {
    if (!frame) return
    setFrameBusy(true)
    api.phaseWork()
      .then((v) => {
        const задача = v.items.find((t) => t.id === frame.task)
        if (!задача) return
        setFrameTask(задача)
        if (следующий) {
          const дальше = frame.step + 1
          if (дальше >= задача.steps.length) {
            setFrame(null)
            setFrameTask(null)
            go('phasework')
          } else открытьШаг(задача, дальше)
        }
      })
      .finally(() => setFrameBusy(false))
  }

  const render = (): ReactElement => {
    switch (screen) {
      case 'portfolio': return (
        <Portfolio onOpen={() => { setScreen('lifecycle'); loadHeader() }}
          onNew={() => { setFirstRun(false); setScreen('newproject') }}
          onLoadFile={() => { setFirstRun(false); setScreen('importb') }}
          onStart={() => { setScreen('startpath'); loadHeader() }} />
      )
      case 'newproject': return (
        <NewProject firstRun={firstRun}
          onDone={(id) => {
            selectProject(id)
            setFirstRun(false)
            setScreen('startpath')
            loadHeader()
          }}
          onCancel={() => { setFirstRun(false); setScreen('portfolio') }}
          onLoadFile={() => { setFirstRun(false); setScreen('importb') }} />
      )
      case 'startpath':
        return project
          ? <StartPath project={project} onGo={go} onDone={() => { setScreen('lifecycle'); loadHeader() }} />
          : <Portfolio onOpen={() => setScreen('lifecycle')}
              onNew={() => { setFirstRun(false); setScreen('newproject') }}
              onLoadFile={() => { setFirstRun(false); setScreen('importb') }}
              onStart={() => { setScreen('startpath'); loadHeader() }} />
      case 'lifecycle':
        return project
          ? <Lifecycle project={project} onGo={go} />
          : <Portfolio onOpen={() => setScreen('lifecycle')}
              onNew={() => { setFirstRun(false); setScreen('newproject') }}
              onLoadFile={() => { setFirstRun(false); setScreen('importb') }}
              onStart={() => { setScreen('startpath'); loadHeader() }} />
      case 'mytasks':
        return project
          ? <MyTasks onGo={go} />
          : <Portfolio onOpen={() => setScreen('lifecycle')}
              onNew={() => { setFirstRun(false); setScreen('newproject') }}
              onLoadFile={() => { setFirstRun(false); setScreen('importb') }}
              onStart={() => { setScreen('startpath'); loadHeader() }} />
      case 'operations':
        return project
          ? <Operations project={project} onGo={go} />
          : <Portfolio onOpen={() => setScreen('lifecycle')}
              onNew={() => { setFirstRun(false); setScreen('newproject') }}
              onLoadFile={() => { setFirstRun(false); setScreen('importb') }}
              onStart={() => { setScreen('startpath'); loadHeader() }} />
      case 'phasework':
        return <ScreenFrame title="Работа фазы"><PhaseWork onGo={go} onLead={вестиЗадачу} here={frame?.task} /></ScreenFrame>
      case 'projreg': return <KindRegistry key={screen} kinds={['project']} title="Паспорт проекта" />
      case 'shelves': return <Shelves key={screen} />
      case 'inspection':
        return <ScreenFrame title="Инспекция обзора"><ReviewInspection onGo={go} /></ScreenFrame>
      case 'results': return <ScreenFrame title="Результаты"><Results onGo={go} /></ScreenFrame>
      case 'references':
        return <ScreenFrame title="Справочники"><References /></ScreenFrame>
      case 'libknowledge':
        return <ScreenFrame title="Знание полки"><LibraryKnowledge /></ScreenFrame>
      case 'docparse':
        return <ScreenFrame title="Разбор документов"><DocParse onGo={go} /></ScreenFrame>
      case 'sourcedocs':
        return <KindRegistry key={screen} kinds={['source_document']} title="Исходные документы (библиотека)" />
      {/* формы постановки: редактор раскрывается вниз, как в требованиях
          (замечание МВП-прохода) */}
      case 'goals': return (
        <>
          <StatementGuide onGo={go} />
          <KindRegistry key={screen} kinds={['mission_goal', 'need']} title="Цели и нужды" expandDown />
        </>
      )
      case 'stakeholders': return (
        <>
          <StatementGuide onGo={go} />
          <StakeholderCoverage onGo={go} />
          <KindRegistry key={screen} kinds={['stakeholder']} title="Стейкхолдеры проекта" expandDown />
        </>
      )
      case 'needs': return (
        <ScreenFrame title="Нужды и их сервисы">
          <div>
            <StatementGuide onGo={go} />
            <Needs initialId={pendingDoc} />
          </div>
        </ScreenFrame>
      )
      case 'services': return (
        <>
          <StatementGuide onGo={go} />
          <KindRegistry key={screen} kinds={['service']} title="Сервисы и QoS" expandDown />
        </>
      )
      case 'conops': return <KindRegistry key={screen} kinds={['conops']} title="Сценарии ConOps" />
      case 'req': return <Requirements onGo={go} />
      case 'matrix': return <MatrixScreen />
      case 'trace': return <ScreenFrame title="Трассировка и impact"><TraceGraph onGo={go} /></ScreenFrame>
      case 'aoa': return <KindRegistry key={screen} kinds={['alternative', 'decision']} title="Альтернативы и решения" />
      case 'architecture': return <ScreenFrame title="Архитектура"><Architecture onGo={go} /></ScreenFrame>
      case 'functions': return <KindRegistry key={screen} kinds={['function', 'function_chain', 'capability', 'logical_component']} title="Функции и цепочки" />
      case 'external': return <ScreenFrame title="Внешняя модель"><ExternalModel onGo={go} /></ScreenFrame>
      case 'sysmodels': return <ScreenFrame title="Модели системы"><SystemModels onGo={go} /></ScreenFrame>
      case 'wbs': return <KindRegistry key={screen} kinds={['component', 'interface']} title="Элементы и интерфейсы" />
      case 'composition': return <ScreenFrame title="Дерево состава"><SystemComposition onGo={go} /></ScreenFrame>
      case 'wbstree': return <KindRegistry key={screen} kinds={['wbs_element']} title="Структура работ (WBS)" />
      case 'cost': return <KindRegistry key={screen} kinds={['cost_estimate']} title="Оценки стоимости и сроков" />
      case 'siminputs':
        return (
          <KindRegistry key={screen} title="Входы моделирования"
            kinds={['scenario', 'constellation', 'demand_map', 'terminal_profile', 'ground_stations', 'protocol_adapter']}
          />
        )
      case 'spacecraft':
        // ADR-044: модель аппарата — вид узла КА дерева состава; переход с
        // дерева несёт идентификатор узла
        return <ScreenFrame title="Модель аппарата"><Spacecraft spacecraftId={pendingKind === 'component' ? pendingDoc : undefined} onGo={go} /></ScreenFrame>
      case 'seeddemand': return <ScreenFrame title="Затравка спроса"><SeedDemand /></ScreenFrame>
      case 'demand': return <ScreenFrame title="Карта спроса"><Demand /></ScreenFrame>
      case 'ground': return <ScreenFrame title="Наземный сегмент"><GroundSegment /></ScreenFrame>
      case 'ballistics': return <ScreenFrame title="Глобус"><Globe /></ScreenFrame>
      case 'constcompare': return <ScreenFrame title="Сравнение построений"><ConstellationCompare /></ScreenFrame>
      case 'coverage': return <ScreenFrame title="Карта покрытия"><Coverage /></ScreenFrame>
      case 'compare': return <ScreenFrame title="Сравнение вариантов"><Comparison /></ScreenFrame>
      case 'readiness':
        return project
          ? <GateReadiness key={pendingDoc ?? 'next'} project={project} gate={pendingDoc} onGo={go} />
          : <Portfolio onOpen={() => setScreen('lifecycle')}
              onNew={() => { setFirstRun(false); setScreen('newproject') }}
              onLoadFile={() => { setFirstRun(false); setScreen('importb') }}
              onStart={() => { setScreen('startpath'); loadHeader() }} />
      case 'rfa': return <KindRegistry key={screen} kinds={['review_item']} title="Замечания обзора (RFA/RID)" />
      case 'risks': return <KindRegistry key={screen} kinds={['risk']} title="Риски" />
      case 'riskmatrix': return <ScreenFrame title="Матрица рисков"><Risks /></ScreenFrame>
      case 'trl': return <KindRegistry key={screen} kinds={['technology']} title="Технологии и TRL" />
      case 'oda': return <KindRegistry key={screen} kinds={['oda']} title="Оценка орбитального засорения" />
      case 'vv': return <KindRegistry key={screen} kinds={['evidence', 'validation']} title="Верификация и валидация" />
      case 'docs': return (
        <ScreenFrame title="Документы"><Documents onGo={go} initialCode={pendingDoc} /></ScreenFrame>
      )
      case 'system': return <ScreenFrame title="Система в целом"><SystemOverview /></ScreenFrame>
      case 'aiservice': return <AiService onGo={go} initialKind={pendingKind} initialContext={pendingDoc} />
      case 'aiprofiles': return <AiProfiles key={screen} onGo={go} />
      case 'ai': return <ScreenFrame title="Предложения ИИ"><AiProposal /></ScreenFrame>
      case 'importb': return <BatchLoad />
      case 'terminals': return <ScreenFrame title="Импорт терминалов"><ImportCatalog /></ScreenFrame>
      default: return <div className="empty">Экран «{screen}» не найден.</div>
    }
  }

  const nav = SECTIONS.find((s) => s.key === section)!
  const sectionOps = info?.operations.filter((o) => o.screen && sectionOf(o.screen) === section) ?? []

  return (
    <LoginGate>
    <div className="shell">
      <header className="header">
        <div className="header__logo">Орбита</div>
        {screen === 'newproject' && (
          <span className="np-crumb">
            {firstRun ? <b>Первый проект</b> : <>Портфель / <b>Новый проект</b></>}
          </span>
        )}
        {screen !== 'newproject' && project && info && (
          <div className="header__project">
            <b>{info.name}</b>
            <span className="header__phase">{PHASE_LABEL[info.phase] ?? info.phase}</span>
          </div>
        )}
        {screen !== 'newproject' && project && info?.nextGate && (
          <button className="header__gate" onClick={() => setScreen('readiness')}
            title="Готовность к точке — что мешает пройти">
            <span className="secondary">ближайшая точка</span>
            <b className="mono">{info.nextGate}</b>
            <span className={`count ${info.unclosed === 0 ? 'count--ready' : ''}`}>
              {info.unclosed == null ? '' : info.unclosed === 0 ? 'готово' : `не закрыто: ${info.unclosed}`}
            </span>
          </button>
        )}
        {screen !== 'newproject' && project && (
          <button className="btn" onClick={() => { selectProject(null); setScreen('portfolio') }}>
            Сменить проект
          </button>
        )}
        <div className="grow" style={{ flex: 1 }} />
        {screen !== 'newproject' && project && <NextStepBadge tick={screen} onGo={go} />}
        {screen !== 'newproject' && project && <MyTasksBadge tick={screen} onGo={go} />}
        <Accounts />
          <AuthorField />
      </header>
      <div className="shell__body">
        <nav className="rail">
          {SECTIONS.map((s) => (
            <button key={s.key} className="rail__item" aria-selected={s.key === section}
              title={`раздел «${s.label}»`}
              onClick={() => setScreen(s.screens[0].key)}>
              <svg viewBox="0 0 24 24" aria-hidden="true">{RAIL_ICONS[s.key]}</svg>
              {s.label}
            </button>
          ))}
        </nav>
        {/* Круг 2 (правило общесистемное): панель раздела с единственным
            пунктом и без операций фазы схлопывается — пустая белая колонка
            занимала треть окна и ничего не давала. Раздел открывается сразу
            экраном; прямые маршруты при этом живут. */}
        {!['portfolio', 'newproject', 'startpath'].includes(screen) &&
          !(nav.screens.length <= 1 && sectionOps.length === 0) && (
          <aside className="ops">
            <div className="ops__title">{nav.label}</div>
            {nav.screens.map((sc) => (
              <button key={sc.key} className="ops__item" aria-selected={sc.key === screen}
                onClick={() => setScreen(sc.key)}>
                <span className="name">{sc.title}</span>
              </button>
            ))}
            {sectionOps.length > 0 && (
              <>
                <div className="ops__group">Операции фазы</div>
                {sectionOps.map((o) => (
                  <button key={o.code} className="ops__item" title={o.name}
                    onClick={() => o.screen && setScreen(o.screen)}>
                    <span className={`ops__state ops__state--${o.state} ${o.returned_to ? 'ops__state--returned' : ''}`}
                      title={o.returned_to ? 'цель действующего возврата' : STATE_TITLES[o.state] ?? o.state} />
                    <span className="mono" style={{ minWidth: 28 }}>{o.code}</span>
                    <span className="name">{o.name}</span>
                  </button>
                ))}
              </>
            )}
          </aside>
        )}
        <main className="shell__work">
          {lostProject && (
            <div className="notice" role="status" style={{ margin: '8px 12px 0' }}>
              проект {lostProject} больше не существует на стенде — выберите проект в портфеле
            </div>
          )}

          {frame && frameTask && (
            <TaskFrameBar task={frameTask} step={frame.step} busy={frameBusy} author={session.author}
              onStep={(i) => открытьШаг(frameTask, i)}
              onExit={() => { setFrame(null); setFrameTask(null); go('phasework') }}
              onAdvance={() => обновитьРамку(true)}
              onManualDone={() => обновитьРамку(false)}
              onOpenTask={вестиЗадачу} onGo={go} />
          )}
          {render()}
        </main>
      </div>
    </div>
    </LoginGate>
  )
}
