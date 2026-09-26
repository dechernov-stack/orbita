// Ответ владельца по вопросам шипа 5 (26.09): что изменилось на экранах.
//
// §5 — колонка «выпусков» (печать — не выпуск) стала «базовых линий N»;
// секунды связного текста — из журнала ИИ. §7 — подсказка поля показывается
// словами: коды полей и значений заменены метками истины (`note_words`).
import { describe, expect, it } from 'vitest'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import type { EntityRow, KindSpec } from './api'
import { секундыСвязного } from './documents/section'
import { Карточка } from './ui/objectcard'

describe('секунды связного текста', () => {
  it('из журнала ИИ; нет в журнале — секундами окна; нигде — не пишутся', () => {
    expect(секундыСвязного(12.4, null)).toBe('12.4 с')
    expect(секундыСвязного(null, 7)).toBe('7 с')
    expect(секундыСвязного(undefined, null)).toBeNull()
    expect(секундыСвязного(0, 5)).toBe('0 с')
  })
})

describe('подсказка поля словами', () => {
  const вид: KindSpec = {
    code: 'component', title: 'узел состава', status_model: 'draft', fields: ['nature'], required: [], measures: [],
    labels: { nature: 'Род' }, required_at: {}, enums: { nature: ['node', 'behaviour'] },
    enum_labels: { nature: { node: 'узел (железо)', behaviour: 'поведение (ПО)' } },
    notes: { nature: 'behaviour обязан быть развёрнут на node' },
    note_words: { nature: '«поведение (ПО)» обязан быть развёрнут на «узел (железо)»' },
    measure_ops: {}, widgets: { nature: 'enum' }, ref_kinds: {},
  }
  const строка: EntityRow = { id: 'c1', code: 'SC', status: 'draft', version: 1, doc: { nature: 'node' } }

  it('карточка показывает note_words, а не код истины', () => {
    const html = renderToStaticMarkup(createElement(Карточка, { project: 'PJ', row: строка, spec: вид }))
    expect(html).toContain('«поведение (ПО)» обязан быть развёрнут на «узел (железо)»')
    expect(html).not.toContain('behaviour обязан')
  })

  it('без note_words — примечание истины как есть', () => {
    const html = renderToStaticMarkup(createElement(Карточка, { project: 'PJ', row: строка, spec: { ...вид, note_words: undefined } }))
    expect(html).toContain('behaviour обязан быть развёрнут на node')
  })
})
