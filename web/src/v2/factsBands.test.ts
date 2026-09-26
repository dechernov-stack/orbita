// Факты полосами (шип 5 §3.2). Владелец КТ2: «объединение фактов по группам —
// стало только хуже: колонка «Утверждение», а написано там всё подряд».
// Группа — полоса над своей таблицей; предмет — один раз в полосе, в
// колонках его нет; действия строки — пиктограммой со словом; чипы
// отбора — значениями из данных; ручной ввод — полосой, а не карточкой.
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import type { FactRow } from './api'
import поле from './knowledgefield.tsx?raw'
import вкладка from './knowledge/facts.tsx?raw'
import { ВкладкаФакты, бесспорный, группыФактов, нуженПовод } from './knowledge/facts'

const факт = (code: string, subject: string, predicate: string, extra: Partial<FactRow> = {}): FactRow => ({
  id: code, code, subject, predicate, value: '180', unit: 'мин', anchor: 's12', mark: 'И', material: 'SD-0001',
  disposition: 'free', rank: 'mandatory', conflicts: [], ...extra,
})

const латентность = [
  факт('F-0164', 'Латентность B′', 'P95 доставки на этапе 1 — не более', { conflicts: ['F-0168'], quote: 'не более 180 мин на этапе 1' }),
  факт('F-0165', 'Латентность B′', 'класс B′ — трекинг вагонов', { disposition: 'adopted' }),
  факт('F-0166', 'Латентность B′', 'порог отсева по P95', { mark: 'П' }),
]
const вРендер = (факты: FactRow[], ручнойОткрыт = false) => renderToStaticMarkup(createElement(ВкладкаФакты, {
  project: 'PJ-1', факты, темы: [], знанияV2: true, документы: { 'SD-0001': 'записка миссии' }, автор: 'Иванов И.',
  ручнойВвод: createElement('div', { className: 'форма-ручного-ввода' }, 'форма'), ручнойОткрыт, onChanged: () => undefined,
}))

describe('группы фактов — полосами', () => {
  it('счётчики полосы: фактов, нерешённых, противоречий', () => {
    const [г] = группыФактов(латентность)
    expect(г).toMatchObject({ предмет: 'Латентность B′', нерешено: 2, противоречий: 1 })
  })

  it('единственная группа раскрыта; предмет — один раз в полосе, в колонках его нет', () => {
    const html = вРендер(латентность)
    expect(html).toContain('<b class="v2-band__subject">Латентность B′</b>')
    expect(html).toContain('фактов 3 · нерешённых 2 · противоречий 1')
    expect(html.split('Латентность B′').length - 1).toBe(1)
    expect(html).toContain('<th>Утверждение</th><th>Значение</th>')
    expect(html).not.toContain('<th>Предмет</th>')
    expect(html).toContain('<td>P95 доставки на этапе 1 — не более</td>')
  })

  it('откуда — документ словом и якорь ссылкой; противоречие — ссылкой на факт', () => {
    const html = вРендер(латентность)
    expect(html).toContain('записка миссии · s12')
    expect(html).toContain('title="раскрыть цитату блока документа"')
    expect(html).toContain('title="перейти к факту F-0168: его полоса раскроется, строка подсветится">F-0168</button>')
  })

  it('действия строки — пиктограммой со словом; полоса — «принять бесспорные»', () => {
    const html = вРендер(латентность)
    expect(html).toContain('aria-label="принять факт"')
    expect(html).toContain('aria-label="отклонить с причиной"')
    expect(html).toContain('aria-label="в допущения"')
    expect(html).toContain('aria-label="принять бесспорные: 1"')
  })

  it('чипы — значениями из данных; ручной ввод — полосой с формой', () => {
    const html = вРендер([...латентность, факт('F-0200', '', 'ручной факт', { manual: true, material: 'эксперт' })], true)
    expect(html).toContain('нерешённые <span class="v2-chip__n">3</span>')
    expect(html).toContain('противоречия <span class="v2-chip__n">1</span>')
    expect(html).toContain('ручной ввод <span class="v2-chip__n">1</span>')
    expect(html).toContain('записка миссии <span class="v2-chip__n">3</span>')
    expect(html).toContain('<b class="v2-band__subject">Ручной ввод</b>')
    expect(html).toContain('форма-ручного-ввода')
  })

  it('две группы и больше — «раскрыть все» над первой полосой, полосы свёрнуты', () => {
    const html = вРендер([...латентность, факт('F-0300', 'Минтранс России', 'участвует в проекте')])
    expect(html).toContain('раскрыть все (2)')
    expect(html).toContain('v2-band v2-band--closed')
    expect(html).not.toContain('<th>Утверждение</th>')
  })
})

describe('правила решения по факту', () => {
  it('бесспорный — не решён, без противоречий, не сомнительного ранга', () => {
    expect(бесспорный(латентность[0])).toBe(false)
    expect(бесспорный(латентность[1])).toBe(false)
    expect(бесспорный(латентность[2])).toBe(true)
    expect(бесспорный({ ...латентность[2], rank: 'doubtful' })).toBe(false)
  })
  it('повод нужен у отклонения, у оспоренного и при смене решения', () => {
    expect(нуженПовод('free', 'adopted')).toBe(false)
    expect(нуженПовод('free', 'rejected')).toBe(true)
    expect(нуженПовод('contested', 'adopted')).toBe(true)
    expect(нуженПовод('noted', 'adopted')).toBe(true)
  })
})

describe('экран поля знаний', () => {
  it('таблицы фактов в knowledgefield.tsx нет — вкладка фактов своим файлом', () => {
    expect(поле).toContain('<ВкладкаФакты project={project}')
    expect(поле).not.toContain('className="v2-kf__group"')
    expect(поле).not.toContain('показать:')
    expect(вкладка).not.toContain('colSpan={знанияV2 ? 7 : 6}')
  })
})
