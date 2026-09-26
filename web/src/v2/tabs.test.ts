// Вкладки (шип 5 §1.1): единственное второе меню — строка вкладок с числом
// и маркером здоровья, не больше семи обычных, эксперт-вкладки серым за
// разделителем, клавиатура ← → Home End, role="tablist".
import { describe, expect, it } from 'vitest'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { Вкладки, разложить, следующаяВкладка, ВКЛАДОК_НЕ_БОЛЬШЕ, type Вкладка } from './ui/tabs'

const восемь: Вкладка[] = ['Стороны', 'Нужды', 'Цели', 'Сервисы', 'Ограничения', 'Допущения', 'Применимость', 'Восьмая']
  .map((w, i) => ({ key: `k${i}`, word: w, hint: w.toLowerCase(), count: i }))

describe('вкладки', () => {
  it('восьмая и дальше уходят в эксперт-режим', () => {
    const { обычные, эксперт } = разложить(восемь)
    expect(обычные).toHaveLength(ВКЛАДОК_НЕ_БОЛЬШЕ)
    expect(эксперт.map((в) => в.word)).toEqual(['Восьмая'])
  })
  it('клавиатура: ← → по кругу, Home и End', () => {
    const к = ['a', 'b', 'c']
    expect(следующаяВкладка(к, 'a', 'ArrowRight')).toBe('b')
    expect(следующаяВкладка(к, 'a', 'ArrowLeft')).toBe('c')
    expect(следующаяВкладка(к, 'b', 'End')).toBe('c')
    expect(следующаяВкладка(к, 'c', 'Home')).toBe('a')
    expect(следующаяВкладка(к, 'a', 'Enter')).toBeNull()
  })
  it('строка вкладок: tablist, текущая выбрана, число приглушённо, маркер здоровья со словом, эксперт серым', () => {
    const html = renderToStaticMarkup(createElement(Вкладки, {
      label: 'постановка', current: 'k1', onChange: () => undefined,
      items: [
        { key: 'k0', word: 'Стороны', count: 18, health: 'debt', hint: '3 стороны без нужд' },
        { key: 'k1', word: 'Нужды', count: '20 из 29', health: 'block', hint: '9 нужд без цели — держит сцену 4' },
        { key: 'k2', word: 'Индекс', expert: true, hint: 'эксперт-режим' },
      ],
    }))
    expect(html).toContain('role="tablist"')
    expect(html).toContain('aria-label="постановка"')
    expect(html).toMatch(/aria-selected="true"[^>]*>.*Нужды/)
    expect(html).toContain('<span class="v2-tab__n">20 из 29</span>')
    expect(html).toContain('v2-hm v2-hm--debt')
    expect(html).toContain('есть долг: 3 стороны без нужд')
    expect(html).toContain('v2-hm v2-hm--block')
    expect(html).toContain('v2-tabs__sep')
    expect(html).toMatch(/class="v2-tab v2-tab--expert"[^>]*disabled/)
    expect(html).toContain('откроется в эксперт-режиме')
  })
})
