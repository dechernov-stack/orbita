// Срок-точка риска правится в реестре, а вехи берутся из проекта.
//
// Проход владельца 20.09: «не даёт завершить: без срока-точки: RI-0025,
// RI-0026, RI-0027». Реестр показывал «нет точки», а поправить срок принятого
// риска было негде — он задавался только при заведении. Плюс перечень точек
// («MCR · SRR · SDR · PDR») жил в коде экрана мимо данных проекта, хотя истина
// говорит `risk.due_point: ref gate` — «срок — любая веха».
import { describe, expect, it } from 'vitest'
import экран from './programmatics.tsx?raw'

describe('срок-точка риска', () => {
  it('правится прямо в строке реестра', () => {
    expect(экран).toContain('aria-label={`срок-точка риска ${р.code}`}')
    expect(экран).toContain("api.patchEntity(project, р.code, { due_point: e.target.value }")
  })

  it('проставить разом можно, но только без точки и только выбранной вехой', () => {
    expect(экран).toContain('Проставить всем без точки')
    expect(экран).toContain("риски.filter((р) => р.due_point === '—')")
    expect(экран).toContain('disabled={!всемВеха || занятоВсем}')
    expect(экран).toContain('названные сроки не трогаются')
  })

  it('вехи берутся из проекта, а не из списка в коде', () => {
    expect(экран).toContain('function useВехи')
    expect(экран).toContain("api.entities(project, 'gate')")
    expect(экран).not.toContain("['MCR', 'SRR', 'SDR', 'PDR']")
    // Ни одна форма не подставляет точку по умолчанию: её выбирает человек.
    expect(экран).not.toContain("due_point: 'MCR'")
    expect(экран).not.toContain("required_by: 'PDR'")
  })
})
