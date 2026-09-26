// Требования (шип 5 §4): реестр не переделывается — виды вкладками (§1.1),
// группировка по носителю и уровню — полосами (§1.2), карточка — по §1.3
// (EARS чипом по-русски, линт маркером — никогда не запирает, строка долга по
// ступени), действия строки — пиктограммой, массово — общей панелью (§1.4):
// «распределить носитель», «базировать выбранные», «снять линт-помету с
// причиной». Формулировка — целиком у СИ и РП, двумя строками у инженера.
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import type { RequirementRow } from './api'
import экран from './requirements.tsx?raw'
import { активныеПометы, Формулировка } from './requirements'

const строка = (notes: { rule: string }[], принятые: { rule: string }[] = []): RequirementRow => ({
  id: 'r', code: 'RQ-1', level: 'system', title: 'доставка', statement: 'Система должна доставлять', category: 'functional',
  ears: 'ubiquitous', carrier: null, carrier_kind: null, measure: null, verification_method: null, priority: null,
  acceptance_criteria: null, status: 'draft', version: 1, template_ref: null, applicability: null, after_baseline_changed: false,
  sources: [], notes: notes.map((н) => ({ ...н, what: 'что', why: 'почему' })), lint_acknowledged: принятые,
})

describe('реестр требований', () => {
  it('группы — полосами над своими таблицами, а не строкой colSpan в tbody', () => {
    expect(экран).toContain('<Полоса key={имяГруппы ?? \'*\'}')
    expect(экран).toContain('{таблица(дети)}')
    expect(экран).not.toContain('<tr><td colSpan={7} className="v2-dim">{имяГруппы}')
  })

  it('линт рекомендует, не запирает: принятая как есть помета маркером не горит', () => {
    expect(активныеПометы(строка([{ rule: 'R1' }, { rule: 'R2' }], [{ rule: 'R1' }])).map((п) => п.rule)).toEqual(['R2'])
    expect(активныеПометы(строка([{ rule: 'R1' }]))).toHaveLength(1)
    expect(экран).toContain('title="линт рекомендует, не запирает"')
  })

  it('карточка: EARS чипом словом истины, строка долга по ступени, действия пиктограммой', () => {
    expect(экран).toContain("схема.метка('ears_pattern', т.ears) || т.ears")
    expect(экран).toContain('<div className="v2-object__debt">')
    expect(экран).toContain('<ИконКнопка икон="карточка"')
  })

  it('формулировка у инженера — двумя строками с раскрытием, у СИ и РП — целиком', () => {
    const длинная = 'Система должна доставлять сообщение класса B′ с P95 не более 180 минут на этапе 1 в зонах без наземного покрытия, включая Арктику, Северный морской путь, Сибирь и Дальний Восток, а также удалённые объекты КИИ.'
    const инженер = renderToStaticMarkup(createElement(Формулировка, { текст: длинная, свернуть: true }))
    expect(инженер).toContain('class="v2-clamp2"')
    expect(инженер).toContain('>целиком</button>')
    const си = renderToStaticMarkup(createElement(Формулировка, { текст: длинная, свернуть: false }))
    expect(си).toBe(длинная)
  })
})
