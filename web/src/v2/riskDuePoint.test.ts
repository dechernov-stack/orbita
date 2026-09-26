// Срок-точка риска правится в реестре, а вехи берутся из проекта.
//
// Проход владельца 20.09: «не даёт завершить: без срока-точки: RI-0025,
// RI-0026, RI-0027». Реестр показывал «нет точки», а поправить срок принятого
// риска было негде — он задавался только при заведении. Плюс перечень точек
// («MCR · SRR · SDR · PDR») жил в коде экрана мимо данных проекта, хотя истина
// говорит `risk.due_point: ref gate` — «срок — любая веха».
import { describe, expect, it } from 'vitest'
import экран from './programmatics.tsx?raw'
import реестр from './risks.tsx?raw'

describe('срок-точка риска', () => {
  // Шип 5 §7: срок правится в карточке риска — грань истины «Срок-точка»
  // (ref gate, пикер вех проекта); строка показывает его и «нет точки».
  it('правится в карточке одним кликом от строки', () => {
    expect(реестр).not.toContain("скрыть={['due_point'")
    expect(реестр).toContain("скрыть={['cec', 'probability', 'impact', 'owner', 'refs']}")
    expect(реестр).toContain("<span className={р.status === 'open' ? 'v2-warn' : 'v2-dim'}>нет точки</span>")
  })

  it('проставить разом можно, но только без точки и только выбранной вехой', () => {
    expect(реестр).toContain("{ key: 'без-срока', word: 'без срока-точки', keys: видимые.filter((р) => р.status === 'open' && р.due_point === '—').map((р) => р.code) }")
    expect(реестр).toContain("choice: { label: 'веха проекта', options: [['', '— выберите веху —'], ...вехи.map(")
    expect(реестр).toContain("api.patchEntity(project, к, { due_point: веха }")
    expect(реестр).toContain('названные сроки не трогаются')
  })

  it('вехи берутся из проекта, а не из списка в коде', () => {
    expect(реестр).toContain('function useВехи')
    expect(реестр).toContain("api.entities(project, 'gate')")
    expect(реестр).not.toContain("['MCR', 'SRR', 'SDR', 'PDR']")
    // Ни одна форма не подставляет точку по умолчанию: её выбирает человек.
    expect(реестр).not.toContain("due_point: 'MCR'")
    expect(реестр).not.toContain("required_by: 'PDR'")
  })
})

describe('оценка засорения', () => {
  it('форма спрашивает то, что требует маршрут, а не прежние поля', () => {
    // Проход владельца 20.09: «оценки засорения нет» — форма слала
    // «lifetime_years · compliant · norm», а маршрут ждёт два срока, модель
    // атмосферы, баллистический коэффициент и нормативы с полки.
    expect(экран).toContain('active_lifetime_years')
    expect(экран).toContain('passive_lifetime_years')
    expect(экран).toContain('atmosphere_model')
    expect(экран).toContain('ballistic_coefficient')
    expect(экран).toContain('normative_active')
    expect(экран).toContain('normative_passive')
    expect(экран).not.toContain('lifetime_years: 18, dv_deorbit')
  })

  it('вариант — из принятой концепции, норматив — с полки, вердикт считает сервер', () => {
    expect(экран).toContain('api.concept(project)')
    expect(экран).toContain("api.shelves('normative_document')")
    expect(экран).toContain('Вердикты считает система')
    // Кнопка держится, пока не названо то, без чего маршрут откажет.
    expect(экран).toContain('норматив не назван: сверять срок не с чем')
    expect(экран).toContain('модель атмосферы не названа')
  })
})
