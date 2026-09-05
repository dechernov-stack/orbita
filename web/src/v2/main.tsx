// Точка входа интерфейса v2. Живёт рядом со старым (strangler): старый
// интерфейс работает по /, новый — по /v2.html, пока волны не заменят его.
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { Shell } from './shell'
import './tokens.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <Shell />
  </StrictMode>,
)
