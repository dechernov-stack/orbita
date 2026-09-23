// Экран «Постановка» (шип 2, экран 11): таблица сторон с силой кликом, сетка
// «влияние × сила» чипами-инициалами (предложенная сила серым, перенос
// кликом) и карточка стороны вниз. Пустой сетки не бывает.
import { describe, expect, it } from 'vitest'
import экран from './coverage.tsx?raw'

describe('стороны таблицей', () => {
  it('роль, число нужд и влияние — словами истины, кода поля на экране нет', () => {
    expect(экран).toContain("api.kind('stakeholder')")
    expect(экран).toContain("вид?.enum_labels?.[поле]?.[код]")
    expect(экран).toContain('<th>Сторона</th><th>Роль</th><th>Нужд</th><th>Влияние</th><th>Сила</th>')
  })

  it('сила ставится кликом по баллу, а не селектом', () => {
    expect(экран).toContain('aria-label={`сила стороны ${с.code}`}')
    expect(экран).toContain("поправить(с, { power: String(н) }, 'сила стороны')")
    expect(экран).not.toContain('— не задана —')
  })

  it('карточка стороны раскрывается вниз: кто · влияние · нужды · основания и документы', () => {
    expect(экран).toContain('function КарточкаСтороны')
    expect(экран).toContain('<div className="v2-facet__title">Кто</div>')
    expect(экран).toContain('<div className="v2-facet__title">Влияние</div>')
    expect(экран).toContain('<div className="v2-facet__title">Нужды</div>')
    expect(экран).toContain('<div className="v2-facet__title">Основания и документы</div>')
    // Оснований может не быть — это говорится словами, а не пустотой.
    expect(экран).toContain('фактов об этой стороне в поле знаний нет')
  })
})

describe('сетка влияние × сила', () => {
  it('сторона — чип с инициалами, предложенная сила серым с «?»', () => {
    expect(экран).toContain("import { инициалы } from './people'")
    expect(экран).toContain('{инициалы(имя(с))}{предложена(с) ? \' ?\' : \'\'}')
    expect(экран).toContain("'v2-stake-chip--proposed'")
  })

  it('перенос — клик по чипу и клик по клетке; клетка называет, куда переедет', () => {
    expect(экран).toContain('aria-label={выбрана ? `перенести ${выбрана}: сила ${балл}, ${к}` : undefined}')
    expect(экран).toContain("поправить(с, { power: String(балл), influence: влияние }, 'перенос стороны на сетке влияния')")
    expect(экран).toContain('сила предложена по роли — подтвердите баллом в таблице или перенесите чип кликом')
  })

  it('сторона без силы названа под сеткой, а не пропадает', () => {
    expect(экран).toContain('вне сетки:')
  })
})
