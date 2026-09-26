// Поле знаний — три вкладки и эксперт-вкладки (шип 5 §3): Документы · Факты ·
// Предложения, за переключателем эксперт-режима — Исследование · Верификация ·
// Индекс. Простыня в 2675 строк разложена по файлам вкладок; ни один файл
// экрана поля знаний не длиннее 800 строк (сторож 8(d) — храповиком позже).
import { describe, expect, it } from 'vitest'
import экран from './knowledgefield.tsx?raw'
import документы from './knowledge/documents.tsx?raw'
import { ТЕКСТ_ПРЕДЛОЖЕНИЙ as предложения } from './test-support/proposalsSource'
import источник from './knowledge/source.tsx?raw'
import факты from './knowledge/facts.tsx?raw'
import ручной from './knowledge/manual.tsx?raw'
import раздача from './knowledge/distribution.tsx?raw'
import план from './knowledge/plan.tsx?raw'
import верификация from './knowledge/verification.tsx?raw'
import общее from './knowledge/common.ts?raw'
import { вкладСловами, последнийПрогон } from './knowledge/documents'
import type { Dossier } from './api'

describe('поле знаний — вкладками', () => {
  it('три вкладки для всех и три эксперт-вкладки; выбор помнится на раздел', () => {
    expect(экран).toContain("const ВКЛАДКИ_ПОЛЯ: readonly ВкладкаПоля[] = ['документы', 'факты', 'предложения', 'исследование', 'верификация', 'индекс']")
    expect(экран).toContain("useВкладка<ВкладкаПоля>('knowledge', ручной ? 'факты' : 'документы', ВКЛАДКИ_ПОЛЯ)")
    expect(экран).toContain("{ key: 'верификация', word: 'Верификация', expert: true")
    expect(экран).toContain("{ key: 'индекс', word: 'Индекс', expert: true")
    expect(экран).toContain('<Вкладки label="поле знаний" current={текущая} onChange={выбратьВкладку} expert={expert} tail={выгрузки}')
  })

  it('чтение документа ведёт в «Предложения» с отбором по прогону', () => {
    expect(экран).toContain("onRead={(run, note) => { setПрочитано({ run, note }); перечитать(); выбратьВкладку('предложения') }}")
    expect(экран).toContain('прогон={прочитано?.run}')
    expect(предложения).toContain('(прогон ? api.synthesisState(project, прогон) : api.synthesisDiff(project))')
  })

  it('файлы вкладок не длиннее 800 строк', () => {
    const файлы: [string, string][] = [
      ['knowledgefield', экран], ['documents', документы], ['proposals', предложения], ['source', источник],
      ['facts', факты], ['manual', ручной], ['distribution', раздача], ['plan', план], ['verification', верификация], ['common', общее],
    ]
    for (const [имя, текст] of файлы) expect([имя, текст.split('\n').length <= 800]).toEqual([имя, true])
  })
})

describe('вкладка «Документы»', () => {
  it('колонки: фактов, последний прогон, вклад; действия пиктограммами со словом', () => {
    expect(документы).toContain('<th>Код</th><th>Документ</th><th>Роль</th><th>Ранг</th><th>Фактов</th>')
    for (const слово of ['досье', 'прочитать в постановку', 'разобрать по заданию', 'загрузить версию', 'снять вклад документа', 'карта пробелов устава']) {
      expect(документы).toContain(слово)
    }
    expect(документы).toContain('api.rollbackMaterial(project, м.code, причина)')
    expect(документы).toContain("новаяВерсия={форма.версия}")
  })

  it('вклад словами — по видам, «сторон», не код вида', () => {
    expect(вкладСловами([{ kind: 'stakeholder' }, { kind: 'stakeholder' }, { kind: 'need' }, { kind: 'goal' }])).toBe('2 стороны · 1 нужда · 1 цель')
    expect(вкладСловами([{ kind: 'неведомое' }])).toBe('1 запись')
  })

  it('последний прогон — статус словом и дата', () => {
    const досье = { runs: [
      { code: 'RN-1', at: '2026-09-24T10:00:00Z', prompt_version: 'v1', facts: 3, status: 'superseded', rolled_back_by: null },
      { code: 'RN-2', at: '2026-09-25T22:10:00Z', prompt_version: 'v2', facts: 5, status: 'done', rolled_back_by: null },
    ] } as unknown as Dossier
    expect(последнийПрогон(досье)).toBe('готов · 25.09.2026')
    expect(последнийПрогон(null)).toBeNull()
  })
})

describe('вкладка «Предложения»', () => {
  it('шапка прогона: отпечаток, срез, урезание словами, «отменить пакет»; группы — полосами', () => {
    expect(предложения).toContain('aria-label="шапка прогона"')
    expect(предложения).toContain('запуск.slice_fingerprint.slice(0, 12)')
    expect(предложения).toContain('слово="отменить пакет" onClick={отменитьПакет}')
    expect(предложения).toContain('<Полоса key={ключ} open={полосы.открыта(ключ)}')
    expect(предложения).not.toContain('<div key={ключ} className="v2-card">')
  })
})
