// Оболочка клиента. Экраны первой и второй очереди (STEP-6 §3.1, STEP-7-9 §8).
// Данные только из API ядра: расчётов в клиенте нет.
import { useState } from 'react'
import { Comparison } from './screens/Comparison'
import { ComponentSpec } from './screens/ComponentSpec'
import { Globe } from './screens/Globe'
import { Requirements } from './screens/Requirements'
import { SystemOverview } from './screens/SystemOverview'

const SCREENS = [
  { id: 'requirements', title: 'Требования', render: () => <Requirements /> },
  { id: 'component', title: 'Спецификация элемента', render: () => <ComponentSpec componentId="CM-0011" /> },
  { id: 'comparison', title: 'Сравнение вариантов', render: () => <Comparison /> },
  { id: 'system', title: 'Система в целом', render: () => <SystemOverview /> },
  { id: 'globe', title: 'Баллистика', render: () => <Globe /> },
] as const

export function App() {
  const [screen, setScreen] = useState<string>(SCREENS[0].id)
  const active = SCREENS.find((s) => s.id === screen) ?? SCREENS[0]

  return (
    <div className="app">
      <header className="topbar">
        <h1>Орбита</h1>
        <nav className="tabs">
          {SCREENS.map((s) => (
            <button
              key={s.id}
              className="tab"
              aria-selected={s.id === screen}
              onClick={() => setScreen(s.id)}
            >
              {s.title}
            </button>
          ))}
        </nav>
      </header>
      {active.render()}
    </div>
  )
}
