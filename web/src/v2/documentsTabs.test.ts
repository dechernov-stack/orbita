// Документы (шип 5 §5): вкладки «Комплект» и по вкладке на открытый документ;
// документ — список разделов слева 220 px, тело раздела справа: элементы →
// связный текст с маркером статуса и «кто · когда · движок · секунды» →
// правки изложения историей; панель раздела — пиктограмма и слово; проверка
// против поля — только в эксперт-режиме.
import { describe, expect, it } from 'vitest'
import экран from './documents.tsx?raw'
import тело from './documents/body.tsx?raw'
import раздел from './documents/section.tsx?raw'
import комплект from './documents/kit.tsx?raw'
import type { DocSection } from './api'
import { закрытТезисомНет, здоровьеТекста } from './documents/section'

describe('документы — вкладками', () => {
  it('«Комплект» и вкладка на каждый открытый документ; проверка против поля — эксперту', () => {
    expect(экран).toContain("{ key: 'комплект', word: 'Комплект'")
    expect(экран).toContain('...открытые.map((код) => {')
    expect(экран).toContain('<Вкладки label="документы" current={текущая}')
    expect(тело).toContain('{expert && (\n        <FieldCheck')
  })

  it('комплект — таблицей: документ · шаблон · полнота маркером · базовых линий · действия', () => {
    expect(комплект).toContain('<th>Документ</th><th>Шаблон</th>')
    // Ответ владельца 26.09: печать — не выпуск; колонка — «базовых линий N», последняя — именем и датой.
    expect(комплект).toContain('>Базовых линий</th>')
    expect(комплект).not.toContain('Выпусков')
    for (const слово of ['открыть документ', 'собрать PDF', 'базировать']) expect(комплект).toContain(`слово="${слово}"`)
  })

  it('документ: список разделов 220 px слева, тело справа', () => {
    expect(тело).toContain('<nav className="v2-doc__nav" aria-label="разделы документа">')
    expect(тело).toContain('<div className="v2-doc__grid">')
  })

  it('раздел: элементы → связный текст → правки историей; панель — пиктограмма и слово', () => {
    const элементы = раздел.indexOf('{раздел.elements.map((э) => (')
    const текст = раздел.indexOf('aria-label="связный текст раздела"')
    const правки = раздел.indexOf('правки изложения: ${текст.patches.length}')
    expect(элементы).toBeGreaterThan(0)
    expect(текст).toBeGreaterThan(элементы)
    expect(правки).toBeGreaterThan(текст)
    for (const слово of ['править изложение', 'принять как есть', 'их нет — закрыть тезисом']) expect(раздел).toContain(`слово="${слово}"`)
    expect(раздел).toContain("`пишу… ${секунд} с`")
  })

  it('маркер статуса текста и «их нет» словом в списке разделов', () => {
    expect(здоровьеТекста('accepted')).toBe('ok')
    expect(здоровьеТекста('draft')).toBe('debt')
    const р = (text: string) => ({ elements: [{ kind: 'statement', text }] }) as unknown as DocSection
    expect(закрытТезисомНет(р('Открытые вопросы: нет.'))).toBe(true)
    expect(закрытТезисомНет(р('Открытые вопросы — два.'))).toBe(false)
  })
})
