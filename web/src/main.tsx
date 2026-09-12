import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'
import { SessionProvider } from './ui/session'
import './ui/tokens.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    {/* Кто работает и как называются коды перечислений — на весь клиент (шаг 15) */}
    <SessionProvider>
      {/* З-01 (ПМИ-5, 12.09): корень стенда — v2; старый интерфейс живёт по /v1.html с баннером */}
      <div className="v1-banner" role="note" style={{ padding: '6px 12px', background: '#fff7e0', borderBottom: '1px solid #e0c060', fontSize: 13 }}>
        Это старый интерфейс v1 — он остаётся для сравнения. Новый интерфейс — <a href="/">на главной</a>.
      </div>
      <App />
    </SessionProvider>
  </StrictMode>,
)
