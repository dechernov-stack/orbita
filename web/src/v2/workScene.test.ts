// Экраны 4 и 7 шипа 2 (ЗАДАНИЕ-ШИП-2-ДИЗАЙН): «Работа» — лента сцен 320 px и
// сцена в теле; «Мероприятие инженера» — поверхность во всю ширину и рейка
// 128 px словами. Проверяется по исходному тексту, как остальные сторожа
// экранов (образец gateEntries.test.ts): каждое требование задания — строкой
// в коде, которую нельзя убрать молча.
import { describe, expect, it } from 'vitest'
import работа from './work.tsx?raw'
import лента from './phaseband.tsx?raw'
import мероприятие from './activity.tsx?raw'
// CSS в vitest импортом (и с ?raw) приходит пустым — читаем файл как текст.
// Типов node в проекте нет (@types/node не ставится), сам модуль в vitest есть.
// @ts-ignore
import { readFileSync } from 'node:fs'

const стили: string = readFileSync(new URL('./work.css', import.meta.url), 'utf-8')

/** Тело правила CSS по селектору: селектор пишется в файле ровно так, с « {». */
function правило(селектор: string): string {
  const от = стили.indexOf(`\n${селектор} {`)
  expect(от, `в work.css нет правила «${селектор}»`).toBeGreaterThanOrEqual(0)
  return стили.slice(от, стили.indexOf('}', от))
}

describe('экран 4 «Работа»: лента сцен 320 px, сцена в теле', () => {
  it('лента — колонка 320 px слева, тело — остальное; степпера нет ни у кого', () => {
    expect(правило('.v2-work2--scene')).toContain('grid-template-columns: 320px minmax(0, 1fr)')
    expect(работа).toContain("'v2-work2 v2-work2--scene'")
    expect(работа).not.toContain('v2-stepper')
    expect(лента).not.toContain('v2-stepper')
    expect(мероприятие).not.toContain('v2-stepper')
    expect(стили).not.toContain('v2-stepper')
  })

  it('у сцены — маркер-кружок с состоянием и состояние словами', () => {
    expect(лента).toContain('<Маркер род="сцена" состояние={маркерСцены(с)}')
    expect(лента).toContain("if (с.state === 'done') return 'выполнена'")
    expect(лента).toContain('`в работе${прогресс(с)}`')
    expect(лента).toContain('`закрыта${ждёт(с)}`')
    // цвет маркера — состояние; выбранная сцена отмечена и атрибутом
    expect(лента).toContain("aria-current={с.key === current ? 'true' : undefined}")
  })

  it('точки — строками-ромбами между сценами; точка без сцены — в конце ленты', () => {
    expect(лента).toContain('<Маркер род="точка" состояние={маркерТочки(т)}')
    expect(лента).toContain('{точкиПосле(с.key).map(точка)}')
    expect(лента).toContain('{вКонце.map(точка)}')
    expect(лента).toContain('блокирует {т.blocking.length}')
    expect(лента).toContain('датаКратко(т.planned_date)')
  })

  it('клик по сцене открывает её; панели с заголовком ради панели нет', () => {
    expect(лента).toContain('onClick={() => onPick(с.key)}')
    expect(лента).not.toContain('v2-panel')
    expect(лента).not.toContain('<h3>')
  })

  it('сцена в теле: заголовок, ОДНА строка контекста из роли и входов, поток мероприятий', () => {
    expect(работа).toContain('<h1 className="v2-scene__h">{текущая.key} · {текущая.title}</h1>')
    expect(работа).toContain('className="v2-scene__ctx"')
    expect(работа).toContain('{текущая.entry.map((у, i) => (')
    expect(работа).toContain('{РОЛЬ[текущая.role] ?? текущая.role}')
    // контекст и рейка мероприятия в режиме сцены не рисуются — сцена названа один раз
    expect(мероприятие).toContain("const вСцене = режим === 'сцена'")
    expect(мероприятие).toContain('{!вСцене && (')
    expect(мероприятие).toContain("className={вСцене ? 'v2-act2 v2-act2--scene' : открыта ? 'v2-act2 v2-act2--open' : 'v2-act2'}")
    expect(правило('.v2-work2 .v2-act2--scene')).toContain('display: block')
  })

  it('поток мероприятий: скошенные карточки с маркером, стрелка подписана артефактом', () => {
    expect(работа).toContain('function ПотокМероприятий')
    expect(работа).toContain('<Маркер род="мероприятие" состояние={маркерМероприятия(дело, текущее)}')
    expect(работа).toContain('onClick={() => onPick(дело.code)}')
    expect(работа).toContain('className="v2-flow2__arrow"')
    expect(работа).toContain('<i>{подписьСтрелки(прежнее, дело)}</i>')
    // выход первого = вход второго; иначе подписью идут выходы первого
    expect(работа).toContain('к.inputs.some((вх) => вх.toLowerCase() === в.toLowerCase())')
    // цвет карточки — состояние: текущее кобальт, выполненное зелёное, закрытое серое
    expect(работа).toContain("if (текущее) return 'текущее'")
    expect(работа).toContain("if (дело.state === 'done') return 'выполнено'")
    expect(работа).toContain("if (дело.state === 'blocked') return 'закрыто'")
    expect(правило('.v2-flow2__card')).toContain('clip-path: polygon(')
  })

  it('схема сцены библиотекой больше не рисуется; карта фазы взята как есть', () => {
    expect(работа).not.toContain('SceneMap')
    expect(работа).not.toContain('v2-scenemap')
    expect(работа).toContain("import { PhaseMap } from './processmap'")
    expect(работа).toContain('<PhaseMap phase={фаза} onScene={')
  })
})

describe('экран 7 «Мероприятие инженера» (reference-activity-engineer)', () => {
  it('строка контекста: предыдущее → вы здесь → следующее; заголовок 15 px; сцена — в этой же строке', () => {
    expect(мероприятие).toContain('<b>{activity.code} · вы здесь</b>')
    expect(мероприятие).toContain('onClick={() => onPickActivity(прошлое.code)}')
    expect(мероприятие).toContain('onClick={() => onPickActivity(следующее.code)}')
    expect(мероприятие).toContain('<h1 className="v2-act2__h">')
    expect(мероприятие).toContain('сцена {scene.key} «{scene.title}»')
  })

  it('рейка 128 px словами: Условия · Входы · Откроет · Схема фазы, счётчик рядом со словом', () => {
    expect(правило('.v2-work2 .v2-act2')).toContain('grid-template-columns: minmax(0, 1fr) 128px')
    expect(мероприятие).toContain("условия: 'Условия'")
    expect(мероприятие).toContain("входы: 'Входы'")
    expect(мероприятие).toContain("откроет: 'Откроет'")
    expect(мероприятие).toContain("схема: 'Схема фазы'")
    expect(мероприятие).toContain("'что не даёт завершить'")
    expect(мероприятие).toContain("'где я в фазе'")
    expect(мероприятие).toContain('<b>{СЛОВО_РЕЙКИ[п.key]}{п.счёт && ` ${п.счёт}`}</b>')
    // знаков-пиктограмм вместо слова на рейке нет
    expect(мероприятие).not.toContain('{п.знак}')
  })

  it('панель раскрывается внутри рейки: колонка расширяется, третьей колонки нет', () => {
    expect(правило('.v2-work2 .v2-act2--open')).toContain('grid-template-columns: minmax(0, 1fr) var(--rail-open)')
    expect(правило('.v2-work2 .v2-act2__panel')).toContain('position: static')
    // панель — внутри <aside> рейки, а не рядом с колонкой
    const рейка = мероприятие.slice(мероприятие.indexOf('<aside className="v2-act2__rail"'), мероприятие.indexOf('</aside>'))
    expect(рейка).toContain('className="v2-act2__panel"')
    // всякая сетка .v2-act2 в стилях экрана — не больше двух дорожек (сторож)
    for (const м of стили.matchAll(/([^{}\n]*\.v2-act2[^{}\n]*)\{([^}]*)\}/g)) {
      const колонки = м[2].match(/grid-template-columns:\s*([^;]+);/)
      if (!колонки) continue
      const дорожек = колонки[1].trim().match(/minmax\([^)]*\)|repeat\([^)]*\)|\S+/g) ?? []
      expect(дорожек.length, м[1].trim()).toBeLessThanOrEqual(2)
    }
  })

  it('строка выходов внизу: счётчики словами и одна красная «не даёт завершить»', () => {
    expect(мероприятие).toContain('className="v2-act2__foot"')
    expect(мероприятие).toContain('{в.what} {в.count}{в.min > 0 && ` из ${в.min}`}')
    expect(мероприятие).toContain('не даёт завершить: {держит}')
    // причин может быть больше одной — строка одна, остальные подсказкой
    expect(мероприятие).toContain("title={держатВсе.join('; ')}")
  })

  it('«к месту» из условия ведёт в поверхность, а не в другой раздел', () => {
    expect(мероприятие).toContain('const кМесту = (текст: string) => {')
    expect(мероприятие).toContain('(своя ?? корень).scrollIntoView(')
    expect(мероприятие).toContain('onClick={() => кМесту(у.why ?? у.title)}')
    expect(мероприятие).not.toContain('setSection(')
  })
})
