// Карточка объекта (шип 5 §1.3): собирается из истины — грани по `label`,
// контрол по виджету поля, подсказка `note` под полем, строка долга из
// `required_at`; первые семь граней, остальное — «ещё N полей».
import { describe, expect, it } from 'vitest'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { Карточка, грани, долг, стадияСловами, виджет, словами, ГРАНЕЙ_СРАЗУ } from './ui/objectcard'
import type { EntityRow, KindSpec } from './api'

const требование: KindSpec = {
  code: 'requirement', title: 'требование', status_model: 'Draft|Baseline',
  fields: ['id', 'code', 'level', 'title', 'statement', 'category', 'priority', 'measure', 'carrier', 'verification_method', 'acceptance_criteria', 'rationale', 'notes'],
  required: ['statement'], measures: ['measure'],
  labels: { level: 'Уровень', title: 'Заголовок', statement: 'Формулировка', category: 'Категория', priority: 'Приоритет', measure: 'Показатель', carrier: 'Носитель', verification_method: 'Метод верификации', acceptance_criteria: 'Критерий приёмки', rationale: 'Обоснование' },
  required_at: { category: 'baseline', priority: 'baseline', verification_method: 'SRR:tbd_allowed', acceptance_criteria: 'baseline', carrier: 'baseline', level: 'accept:default(project)' },
  enums: { level: ['project', 'scenario', 'system'], category: ['performance', 'functional'] },
  enum_labels: { level: { project: 'проектное', scenario: 'сценарное', system: 'системное' }, category: { performance: 'характеристика', functional: 'функциональное' } },
  notes: { measure: 'обязателен для performance', acceptance_criteria: 'обязателен к базированию' },
  measure_ops: { le: '≤', ge: '≥' },
  widgets: { level: 'enum', title: 'str', statement: 'text', category: 'enum', priority: 'ref', measure: 'measure', carrier: 'ref', verification_method: 'ref', acceptance_criteria: 'str', rationale: 'text' },
  ref_kinds: { carrier: ['component', 'interface', 'scenario'] },
}
const строка: EntityRow = {
  id: 'id-1', code: 'RE-0003', status: 'Draft', version: 3, author: 'Чернов Д.', updated_at: '2026-09-25T10:00:00Z',
  doc: { level: 'project', title: 'Латентность класса B′', statement: 'Система должна доставлять…', category: 'performance', measure: { op: 'le', value: 180, unit: 'min' } },
}

describe('карточка объекта из истины', () => {
  it('грани — поля вида по порядку без полей ядра', () => {
    expect(грани(требование)).toEqual(['level', 'title', 'statement', 'category', 'priority', 'measure', 'carrier', 'verification_method', 'acceptance_criteria', 'rationale'])
    expect(грани(требование, ['title'])).not.toContain('title')
  })
  it('строка долга из required_at: к базированию и к SRR; подставляемое системой — не долг', () => {
    expect(стадияСловами('baseline')).toBe('к базированию')
    expect(стадияСловами('SRR:tbd_allowed')).toBe('к SRR')
    expect(долг(требование, строка.doc)).toEqual([
      { стадия: 'к базированию', поля: ['Приоритет', 'Носитель', 'Критерий приёмки'] },
      { стадия: 'к SRR', поля: ['Метод верификации'] },
    ])
  })
  it('виджет — из истины; без неё — величина по measures, перечень по enums', () => {
    expect(виджет(требование, 'statement')).toBe('text')
    expect(виджет({ ...требование, widgets: {} }, 'measure')).toBe('measure')
    expect(виджет({ ...требование, widgets: {} }, 'level')).toBe('enum')
  })
  it('значение словами: перечень меткой истины, величина — знак, число, единица', () => {
    expect(словами(требование, 'level', 'project')).toBe('проектное')
    expect(словами(требование, 'measure', { op: 'le', value: 180, unit: 'min' })).toBe('≤ 180 min')
    expect(словами(требование, 'rationale', '')).toBe('—')
  })
  it('разметка: шапка, селект русскими метками, текст на всю ширину, величина строкой, подсказка под полем, долг, «ещё N полей»', () => {
    const html = renderToStaticMarkup(createElement(Карточка, { project: 'PJ-1', row: строка, spec: требование, мета: 'версия 3 · Чернов Д. · 25.09' }))
    expect(html).toContain('<span class="v2-mono">RE-0003</span>')
    expect(html).toContain('<h3>Латентность класса B′</h3>')
    expect(html).toContain('версия 3 · Чернов Д. · 25.09')
    expect(html).toContain('<label for="v2-f-level">Уровень</label>')
    expect(html).toContain('<option value="project" selected="">проектное</option>')
    expect(html).toContain('v2-facet v2-facet--wide')
    expect(html).toContain('aria-label="оператор"')
    expect(html).toContain('<div class="v2-facet__hint">обязателен для performance</div>')
    expect(html).toContain('<b>к базированию:</b>')
    const видно = (html.match(/<label for="v2-f-/g) ?? []).length
    expect(видно).toBe(ГРАНЕЙ_СРАЗУ)
    expect(html).toContain('ещё 3 поля')
  })
})
