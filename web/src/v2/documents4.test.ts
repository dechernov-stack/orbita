// Документы живьём (шип 4 §4): связный текст и печать фоновыми заданиями,
// рецензия патчами с «принять как есть», ссылка на PDF по готовому заданию.
import { describe, expect, it } from 'vitest'
import экран from './documents.tsx?raw'
import { api, СЛОВО_РЕНДЕРИНГА } from './api'

describe('документы живьём', () => {
  it('связный текст — фоновым заданием с опросом и секундами; отказ сторожа показывается словами', () => {
    expect(экран).toContain("api.writeSectionJob(project, code, раздел.no, 'инженер')")
    expect(экран).toContain('api.documentJob(project, code, job)')
    expect(экран).toContain('`пишу… ${секунд} с`')
    expect(экран).toContain('текст отклонён сторожем:')
  })
  it('рецензия патчами: править изложение · сохранить правку · принять как есть', () => {
    expect(экран).toContain('aria-label="рецензия"')
    expect(экран).toContain("api.reviewSection(project, code, раздел.no, правка, 'инженер')")
    expect(экран).toContain("api.acceptRendering(project, code, 'инженер', раздел.no)")
    expect(экран).toContain('правка отклонена сторожем:')
    expect(экран).toContain("написанное.status !== 'accepted'")
    expect(СЛОВО_РЕНДЕРИНГА.accepted).toBe('принят')
  })
  it('печать — фоновым заданием, файл по job; движок назван в ссылке', () => {
    expect(экран).toContain("api.printJob(project, code, 'auto')")
    expect(экран).toContain('api.printUrl(project, code, { job: печать.job })')
    expect(api.printUrl('PJ-1', 'mcreport')).toBe('/api/v2/documents/mcreport/print?project=PJ-1')
    expect(api.printUrl('PJ-1', 'mcreport', { engine: 'typst', job: 'DJ-0002' })).toBe('/api/v2/documents/mcreport/print?project=PJ-1&engine=typst&job=DJ-0002')
  })
})
