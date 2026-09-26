// Полоса группы (шип 5 §1.2): группа — своя полоса над своей таблицей, а не
// строка colSpan в tbody; единственная группа раскрыта сразу.
import { describe, expect, it } from 'vitest'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { Полоса, РаскрытьВсе, раскрыта } from './ui/band'

describe('полоса группы', () => {
  it('единственная группа раскрыта сразу; прочие — по выбору', () => {
    expect(раскрыта(['a'], new Set(), 'a')).toBe(true)
    expect(раскрыта(['a', 'b'], new Set(), 'a')).toBe(false)
    expect(раскрыта(['a', 'b'], new Set(['b']), 'b')).toBe(true)
  })
  it('раскрытая полоса: раскрыватель со словом, предмет жирно, счётчики словами, таблица под полосой', () => {
    const html = renderToStaticMarkup(createElement(Полоса, {
      open: true, onToggle: () => undefined, subject: 'Минтранс России', counts: 'фактов 9 · нерешённых 0',
    }, createElement('table', null, createElement('tbody'))))
    expect(html).toContain('class="v2-band"')
    expect(html).toContain('aria-label="свернуть группу"')
    expect(html).toContain('aria-expanded="true"')
    expect(html).toContain('<b class="v2-band__subject">Минтранс России</b>')
    expect(html).toContain('фактов 9 · нерешённых 0')
    expect(html).toContain('<table>')
    expect(html).not.toContain('colSpan')
  })
  it('свёрнутая полоса таблицы не рисует', () => {
    const html = renderToStaticMarkup(createElement(Полоса, { open: false, onToggle: () => undefined, subject: 'Платформы' },
      createElement('table')))
    expect(html).toContain('v2-band--closed')
    expect(html).toContain('aria-label="раскрыть группу"')
    expect(html).not.toContain('<table')
  })
  it('«раскрыть все (N)» над первой полосой; при одной группе его нет', () => {
    expect(renderToStaticMarkup(createElement(РаскрытьВсе, { все: false, число: 58, onClick: () => undefined }))).toContain('раскрыть все (58)')
    expect(renderToStaticMarkup(createElement(РаскрытьВсе, { все: true, число: 3, onClick: () => undefined }))).toContain('свернуть все')
    expect(renderToStaticMarkup(createElement(РаскрытьВсе, { все: false, число: 1, onClick: () => undefined }))).toBe('')
  })
})
