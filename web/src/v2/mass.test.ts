// Массовый приём (шип 5 §1.4): отметки, диапазон Shift-кликом, липкая панель
// «выбрано N — действия» с пиктограммой и словом, подсказка клавиш. Мера —
// сто строк не больше чем за пять действий, без мыши.
import { describe, expect, it } from 'vitest'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { ПанельМассово, отметитьСтроку } from './ui/mass'

const сто = Array.from({ length: 100 }, (_, i) => `RE-${String(i + 1).padStart(4, '0')}`)

describe('массовый приём', () => {
  it('Space отмечает, Shift — диапазон от якоря', () => {
    let с = отметитьСтроку(сто, new Set(), -1, 'RE-0003', false)
    expect([...с.выбраны]).toEqual(['RE-0003'])
    с = отметитьСтроку(сто, с.выбраны, с.якорь, 'RE-0007', true)
    expect([...с.выбраны].sort()).toEqual(['RE-0003', 'RE-0004', 'RE-0005', 'RE-0006', 'RE-0007'])
    с = отметитьСтроку(сто, с.выбраны, с.якорь, 'RE-0005', false)
    expect(с.выбраны.has('RE-0005')).toBe(false)
  })
  it('сто строк за два действия: первая отметка и Shift по последней', () => {
    let с = отметитьСтроку(сто, new Set(), -1, сто[0], false)
    с = отметитьСтроку(сто, с.выбраны, с.якорь, сто[99], true)
    expect(с.выбраны.size).toBe(100)
  })
  it('панель: «выбрано N», действия пиктограммой и словом, клавиши подсказкой; без выбора — умные наборы', () => {
    const действия = [
      { key: 'a', икон: 'принять' as const, слово: 'принять', клавиша: 'A', run: () => undefined },
      { key: 'r', икон: 'отклонить' as const, слово: 'отклонить', клавиша: 'R', run: () => undefined },
    ]
    const html = renderToStaticMarkup(createElement(ПанельМассово, { выбранные: ['RE-0001', 'RE-0002'], действия }))
    expect(html).toContain('<b>выбрано 2</b>')
    expect(html).toContain('aria-label="принять"')
    expect(html).toContain('<span>отклонить</span>')
    expect(html).toContain('A принять · R отклонить')
    const пусто = renderToStaticMarkup(createElement(ПанельМассово, {
      выбранные: [], действия, наборы: [{ key: 'решённые', word: 'все решённые', keys: ['RE-0001'] }], onНабор: () => undefined,
    }))
    expect(пусто).toContain('все решённые 1')
    expect(пусто).not.toContain('aria-label="принять"')
  })
})
