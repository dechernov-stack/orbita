// Оболочка клиента: экраны первой очереди (STEP-6 §3.1) — требования с карточкой
// и спецификация элемента. Данные только из API ядра.
import { useState } from 'react'
import { ComponentSpec } from './screens/ComponentSpec'
import { Requirements } from './screens/Requirements'

type Screen = 'requirements' | 'component'

export function App() {
  const [screen, setScreen] = useState<Screen>('requirements')

  return (
    <div className="app">
      <header className="topbar">
        <h1>Орбита</h1>
        <nav className="tabs">
          <button
            className="tab"
            aria-selected={screen === 'requirements'}
            onClick={() => setScreen('requirements')}
          >
            Требования
          </button>
          <button
            className="tab"
            aria-selected={screen === 'component'}
            onClick={() => setScreen('component')}
          >
            Спецификация элемента
          </button>
        </nav>
      </header>
      {screen === 'requirements' ? <Requirements /> : <ComponentSpec componentId="CM-0002" />}
    </div>
  )
}
