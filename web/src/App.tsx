// Оболочка клиента: мастер Ш1–Ш7 слева, экраны шага — вкладками сверху
// (STEP-7-9 §9.1). Данные только из API ядра: расчётов в клиенте нет.
//
// Состояние шага — сколько объектов и сколько замечаний — приходит с сервера
// (/views/wizard). Клиент не решает, закрыт шаг или нет: «пусто» и «всё
// хорошо» различает WizardViews, потому что различать их надо одинаково
// и на экране, и в отчёте зрелости.
import { useEffect, useState, type ReactElement } from 'react'
import { api } from './api/client'
import type { WizardStep } from './api/types'
import { AiProposal } from './screens/AiProposal'
import { Comparison } from './screens/Comparison'
import { ComponentSpec } from './screens/ComponentSpec'
import { Demand } from './screens/Demand'
import { Globe } from './screens/Globe'
import { Needs } from './screens/Needs'
import { Readiness } from './screens/Readiness'
import { Requirements } from './screens/Requirements'
import { Risks } from './screens/Risks'
import { Services } from './screens/Services'
import { Spacecraft } from './screens/Spacecraft'
import { SystemOverview } from './screens/SystemOverview'

interface Screen {
  id: string
  title: string
  render: () => ReactElement
}

/** Экраны шага мастера. Номер шага совпадает с номером в /views/wizard. */
const STEPS: Array<{ number: number; screens: Screen[] }> = [
  {
    number: 1,
    screens: [{ id: 'needs', title: 'Нужды стейкхолдеров', render: () => <Needs /> }],
  },
  {
    number: 2,
    screens: [
      { id: 'services', title: 'Сервисы и профили QoS', render: () => <Services /> },
      { id: 'demand', title: 'Карта спроса', render: () => <Demand /> },
    ],
  },
  {
    number: 3,
    screens: [
      { id: 'requirements', title: 'Дерево требований', render: () => <Requirements /> },
      { id: 'ai', title: 'Предложение ИИ', render: () => <AiProposal /> },
    ],
  },
  {
    number: 4,
    screens: [
      {
        id: 'component',
        title: 'Спецификация элемента',
        render: () => <ComponentSpec componentId="CM-0011" />,
      },
      { id: 'spacecraft', title: 'Модель КА', render: () => <Spacecraft /> },
    ],
  },
  {
    number: 5,
    screens: [
      { id: 'comparison', title: 'Сравнение вариантов', render: () => <Comparison /> },
      { id: 'globe', title: 'Баллистика', render: () => <Globe /> },
    ],
  },
  {
    number: 6,
    screens: [{ id: 'system', title: 'Система в целом', render: () => <SystemOverview /> }],
  },
  {
    number: 7,
    screens: [
      { id: 'readiness', title: 'Готовность к точке', render: () => <Readiness /> },
      { id: 'risks', title: 'Реестр рисков', render: () => <Risks /> },
    ],
  },
]

export function App() {
  const [steps, setSteps] = useState<WizardStep[]>([])
  const [step, setStep] = useState(1)
  const [screen, setScreen] = useState(STEPS[0].screens[0].id)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api.wizard().then(setSteps).catch((e) => setError(String(e)))
  }, [])

  const openStep = (number: number) => {
    setStep(number)
    const first = STEPS.find((s) => s.number === number)?.screens[0]
    if (first) setScreen(first.id)
  }

  const current = STEPS.find((s) => s.number === step) ?? STEPS[0]
  const active = current.screens.find((s) => s.id === screen) ?? current.screens[0]

  return (
    <div className="app">
      <header className="topbar">
        <h1>Орбита</h1>
        <nav className="tabs">
          {current.screens.map((s) => (
            <button
              key={s.id}
              className="tab"
              aria-selected={s.id === active.id}
              onClick={() => setScreen(s.id)}
            >
              {s.title}
            </button>
          ))}
        </nav>
      </header>

      <nav className="wizard">
        <div className="wizard__group">Мастер</div>
        {STEPS.map(({ number }) => {
          const state = steps.find((s) => s.number === number)
          return (
            <button
              key={number}
              className="wizard__step"
              aria-selected={number === step}
              onClick={() => openStep(number)}
            >
              Ш{number}. {state?.title ?? '—'}
              <small className={state?.complete ? '' : 'amber'}>
                {stepState(state)}
              </small>
            </button>
          )
        })}
        {error && <div className="warn" style={{ padding: '8px 12px' }}>API: {error}</div>}
      </nav>

      {active.render()}
    </div>
  )
}

/**
 * Подпись состояния шага. Сервер уже различил «пусто» и «замечаний нет»;
 * здесь только выбираются слова, признак не выводится заново.
 */
function stepState(state: WizardStep | undefined): string {
  if (!state) return 'нет данных'
  if (state.complete) return `${state.objects} · без замечаний`
  if (state.objects === 0) return 'пусто'
  return `${state.objects} · замечаний ${state.issues.length}`
}
