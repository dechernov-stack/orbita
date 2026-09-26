// Экран 12 шипа 2 — массовый приём предложений: группа «требует внимания»
// сверху, умные наборы, диапазон Shift-кликом, клавиатура без мыши,
// предпросмотр последствий, решения по строкам и снятие принятия выборкой.
import { describe, expect, it } from 'vitest'
import экран from './knowledge/proposals.tsx?raw'
import клиент from './api.ts?raw'

describe('группы и наборы', () => {
  it('«требует внимания» стоит отдельной группой сверху и в пакет не идёт', () => {
    // Шип 5 §3.3: группы — полосами, «требует внимания» — первой полосой.
    expect(экран).toContain('subject="Требует внимания"')
    expect(экран).toContain('Эти строки в пакет не идут никогда')
    const внимание = экран.indexOf('subject="Требует внимания"')
    const группы = экран.indexOf('{ГРУППЫ.map(([ключ, слово, зачем]) => {')
    expect(внимание).toBeGreaterThan(0)
    expect(внимание).toBeLessThan(группы)
  })

  it('умные наборы отмечают разом и ничего не заводят', () => {
    expect(экран).toContain("имя: 'всё, кроме требующих внимания'")
    expect(экран).toContain("имя: 'только новое'")
    expect(экран).toContain("имя: 'только дополнения'")
    expect(экран).toContain('aria-label="умный набор по виду"')
    expect(экран).toContain('отметит ${н.коды.length} — ничего не заводя')
  })
})

describe('выбор и клавиатура', () => {
  it('диапазон отмечается Shift-кликом от последней отметки', () => {
    expect(экран).toContain('const отметить = (код: string, да: boolean, диапазоном = false)')
    expect(экран).toContain('if (e.shiftKey) отметить(п.proposal, !отмечены.includes(п.proposal), true)')
  })

  it('весь экран проходится клавишами: ↑ ↓ Space Enter A R L', () => {
    expect(экран).toContain('onKeyDown={клавиша}')
    expect(экран).toContain("case 'ArrowDown'")
    expect(экран).toContain("case 'ArrowUp'")
    expect(экран).toContain("case ' ':")
    expect(экран).toContain("case 'Enter':")
    expect(экран).toContain("case 'a': case 'A'")
    expect(экран).toContain("case 'r': case 'R'")
    expect(экран).toContain("case 'l': case 'L'")
    // Подсказка клавиш — на экране, а не в голове.
    expect(экран).toContain('клавиши: ↑ ↓ — строка, Space — отметить (с Shift — диапазон), Enter — принять отмеченные,')
  })

  it('предпросмотр последствий стоит до нажатия и считает отмеченное', () => {
    expect(экран).toContain('выбрано ${отмечены.length} из ${предложения.length} → появится ${появится(отмечены)}')
    expect(экран).toContain('требуют внимания ${внимание.length} — в пакет не идут')
  })
})

describe('решения по строкам и снятие принятия', () => {
  it('отклонить и отложить — по отмеченным, сервер решает и считает', () => {
    expect(клиент).toContain('declineSynthesis:')
    expect(экран).toContain("const решить = (что: 'rejected' | 'deferred' | 'pending')")
    expect(экран).toContain('Отклонить отмеченные')
    expect(экран).toContain('Отложить отмеченные')
    // Решение видно в строке словом, а не цветом.
    expect(экран).toContain("{п.decision === 'deferred' && <div className=\"v2-dim\">отложено</div>}")
    expect(экран).toContain("{п.decision === 'rejected' && <div className=\"v2-dim\">отклонено</div>}")
  })

  it('снятие принятия идёт выборкой: остальное в пакете остаётся', () => {
    expect(клиент).toContain('undoSynthesis: (project: string, run: string, author: string, chosen?: string[])')
    expect(экран).toContain('Снять принятие ({снятие.length})')
    expect(экран).toContain('aria-label={`снять принятие с ${код}`}')
    expect(экран).toContain("api.undoSynthesis(project, запуск.id, 'инженер', снятие)")
  })
})
