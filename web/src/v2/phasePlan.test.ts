// План работ фазы задаётся экраном, а не только маршрутом.
//
// Проход владельца 20.09: все двенадцать сцен прожиты, и внутренний обзор
// держится на «план работ фазы не задан: ленте нечего показывать». Маршрут
// `/v2/plan` есть с волны 3, вызывать его было некому: `api.setPlan` в клиенте
// не звал ни один экран.
import { describe, expect, it } from 'vitest'
import план from './plan.tsx?raw'
import работа from './work.tsx?raw'

describe('план работ фазы', () => {
  it('стоит на карте фазы', () => {
    expect(работа).toContain('<PhasePlan phase={фаза} project={project}')
    expect(работа).toContain("import { PhasePlan } from './plan'")
  })

  it('даты точек и окна сцен — из самой фазы, не списком в коде', () => {
    expect(план).toContain('phase.gates.map')
    expect(план).toContain('phase.scenes.map')
    expect(план).toContain('api.setPlan(project')
  })

  it('пустая дата не записывается как ноль', () => {
    expect(план).toContain('.filter(([, д]) => д)')
    expect(план).toContain('.filter(([, о]) => о.start && о.end)')
    expect(план).toContain('«не запланировано» — это не ноль')
  })

  it('видно, у скольких сцен окна нет', () => {
    expect(план).toContain('без окна ${безОкна.length} из ${phase.scenes.length}')
  })
})
