// Сцена 8 работает у себя: предложения требований принимаются на экране
// требований, а не в поле знаний (проход владельца 18.09: «ничего в
// требованиях верхнего уровня не изменилось»).
import { describe, expect, it } from 'vitest'
import экран from './requirements.tsx?raw'

describe('сцена 8 — предложения требований', () => {
  it('предложения берутся из той же постановки и принимаются тем же приёмом', () => {
    const кусок = экран.slice(экран.indexOf('function ПредложенияТребований'), экран.indexOf('export function Requirements'))
    expect(кусок).toContain('api.synthesisDiff(project)')
    expect(кусок).toContain("п.concept === 'requirement'")
    expect(кусок).toContain('api.acceptSynthesis(project, запуск, отмечены')
    // Сами ничего не заводят: заводит клик человека.
    expect(кусок).toContain('отмечены.length === 0')
  })

  it('блок стоит на экране требований выше реестра', () => {
    const блок = экран.indexOf('<ПредложенияТребований')
    const реестр = экран.indexOf('<span className="v2-card__title">Требования · уровень проекта</span>')
    expect(блок).toBeGreaterThan(0)
    expect(блок).toBeLessThan(реестр)
  })

  it('строка таблицы не помечена классом сетки: иначе клетки разваливаются', () => {
    // `.v2-row` — сетка из трёх колонок для списков. На строке таблицы она
    // превращала клетки в блоки по 16 px (замерено на стенде 19.09).
    const кусок = экран.slice(экран.indexOf('function TableRow'), экран.indexOf('function TableRow') + 1200)
    expect(кусок).not.toContain("'v2-row v2-row--open'")
    expect(кусок).toContain("открыта ? 'v2-row--open' : undefined")
  })

  it('реестр рисуется своим классом — ширины колонок у него в стиле', () => {
    // Сами ширины живут в tokens.css (.v2-table--req: table-layout fixed и
    // ширины шести колонок); тест держит то, что от разметки зависит.
    expect(экран).toContain('className="v2-table v2-table--req"')
    // Шип 2: заголовки шести колонок стали сортируемыми и рисуются перечнем
    // КОЛОНКИ — ширины по-прежнему в tokens.css, первой идёт отметка строки.
    expect(экран).toContain("['code', 'Код'], ['title', 'Заголовок'], ['statement', 'Формулировка'],")
    expect(экран).toContain("['measure', 'Показатель'], ['carrier', 'Носитель'], ['status', 'Статус'],")
  })
})
