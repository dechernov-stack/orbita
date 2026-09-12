// Точка входа интерфейса v2. Живёт рядом со старым (strangler): старый
// интерфейс v1 остался по /v1.html с баннером, v2 — корень стенда (З-01, 12.09); прежде было наоборот, пока волны не заменили его.
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { Shell } from './shell'
import './tokens.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <Shell />
  </StrictMode>,
)
