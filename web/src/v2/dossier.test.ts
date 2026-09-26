// Документ как источник — целиком (шип 4 §3): досье, откат, приём режимом,
// партия каталога, библиотека проекта, карта пробелов — на экране.
import { describe, expect, it } from 'vitest'
import досье from './dossier.tsx?raw'
import документыПоля from './knowledge/documents.tsx?raw'
import источник from './knowledge/source.tsx?raw'
import { разделыКанона } from './dossier'

describe('досье документа', () => {
  it('разделы канона собираются из блоков: заголовок разделу, счёт блоков', () => {
    const р = разделыКанона([
      { anchor: 's1', kind: 'heading', text: 'Замысел' },
      { anchor: 's1#1', kind: 'paragraph', text: 'Мы строим систему.' },
      { anchor: 's1#2', kind: 'paragraph', text: 'Заказчик — Минтранс.' },
      { anchor: 's2#1', kind: 'paragraph', text: 'Раздел без заголовка, но с длинным первым абзацем, который режется до шестидесяти знаков.' },
    ])
    expect(р.map((x) => [x.anchor, x.title, x.blocks])).toEqual([
      ['s1', 'Замысел', 2],
      ['s2', 'Раздел без заголовка, но с длинным первым абзацем, который режется до шестидесяти знаков.'.slice(0, 60), 1],
    ])
  })
  it('экран: два взгляда, откат с причиной своим окном, приём режимом, оценка', () => {
    expect(досье).toContain('aria-label="досье документа"')
    expect(досье).toContain('aria-label="на чём стоит"')
    expect(досье).toContain("input: { label: 'почему откатываем', required: true }")
    expect(досье).toContain('api.rollbackMaterial(project, code, повод)')
    expect(досье).toContain('РЕЖИМЫ_ПРИЁМА.map((р) =>')
    expect(досье).toContain("принять('sections', '', отмеченные)")
    expect(досье).toContain('api.usefulnessNote(project, code, оценка.trim())')
    expect(досье).not.toContain('window.confirm')
  })
  it('партия каталога — очередь и одна сводка; библиотека проекта — без устава', () => {
    expect(досье).toContain('aria-label="партия каталога"')
    expect(досье).toContain('api.materialsBatch(project, items)')
    expect(досье).toContain('aria-label="сводка партии"')
    expect(досье).toContain("r.items.filter((м) => м.role !== 'charter')")
    expect(досье).toContain('api.takeMaterial(project, откуда, код)')
  })
  it('поле знаний: досье под строкой документа, карта пробелов у устава и после загрузки', () => {
    // Шип 5 §3: вкладки поля знаний — своими файлами (knowledge/*.tsx).
    expect(документыПоля).toContain("from '../dossier'")
    expect(документыПоля).toContain('<Досье project={project} code={м.code} onChanged={изменилось} onError={onError} />')
    expect(документыПоля).toContain("м.role === 'charter' && (")
    expect(источник).toContain('setПробелы(м.gaps ? { code: м.code, карта: м.gaps } : null)')
    expect(источник).toContain('<Партия project={project} ранг={ранг} роль={рольДок}')
  })
})
