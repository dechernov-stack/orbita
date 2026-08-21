import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'
import { SessionProvider } from './ui/session'
import './ui/tokens.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    {/* Кто работает и как называются коды перечислений — на весь клиент (шаг 15) */}
    <SessionProvider>
      <App />
    </SessionProvider>
  </StrictMode>,
)
