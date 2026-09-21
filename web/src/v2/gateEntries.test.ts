// Ворота называют условие — экран обязан давать, чем его выполнить.
//
// Проход владельца 21.09, KDP-A: «Д1 · FAD … не базирован: нет ни одной линии
// базирования … при этом как базировать FAD — непонятно, в интерфейсе нет
// кнопок» и «Д2 · MCReport … полны 8 из 11 разделов». Маршруты стояли с шипа C
// и волны 4, звать их было нечем: базирование документа, отказы от объёма
// (§9) и варианты построения (§2) не имели ни одной формы.
import { describe, expect, it } from 'vitest'
import документы from './documents.tsx?raw'
import концепция from './concept.tsx?raw'
import клиент from './api.ts?raw'
import реестр from './programmatics.tsx?raw'
import клиентИсследования from './research.tsx?raw'

describe('базирование документа', () => {
  it('вход есть на экране документа, а не только в маршруте', () => {
    expect(клиент).toContain('baselineDocument:')
    expect(клиент).toContain('docBaselines:')
    expect(документы).toContain('<Baselines project={project}')
    expect(документы).toContain('api.baselineDocument(project, code, { name: выбрано, author: автор })')
  })

  it('имя линии — имя точки, и берётся из точек проекта', () => {
    expect(документы).toContain('api.points(project)')
    expect(документы).toContain('точки.find((т) => !т.passed)?.title')
    // Список точек — подсказкой, а не жёстким выбором: имя линии свободно.
    expect(документы).toContain('<datalist id={`v2-точки-${code}`}>')
  })

  it('занятое имя не даёт нажать и говорит почему: линия неизменяема', () => {
    expect(документы).toContain('const занятоИмя = (линии ?? []).some((л) => л.name === выбрано)')
    expect(документы).toContain('линия неизменяема, назовите другое имя')
    expect(документы).toContain('перебазирование заводит новое имя')
  })
})

describe('отказы от объёма и отклонённые варианты', () => {
  it('дописываются к принятому решению, а не называются разом при выборе', () => {
    expect(концепция).toContain("дописать('descopes'")
    expect(концепция).toContain("дописать('rejected'")
    expect(концепция).toContain('api.patchEntity(project, концепция.code, { [поле]: [...было, строка] }')
  })

  it('отказ без цены не записывается', () => {
    expect(концепция).toContain('отказ без цены — не решение')
    expect(концепция).toContain('отклонение без причины не записывается')
  })
})

describe('варианты построения', () => {
  it('заводятся с экрана сцены 7, а не только читаются', () => {
    expect(клиент).toContain('addVariant:')
    expect(концепция).toContain('<ВариантыПостроения project={project} />')
    expect(концепция).toContain('api.addVariant(project, тело)')
  })

  it('вариант описан строем, а показатель необязателен', () => {
    expect(концепция).toContain("pattern: форма.pattern")
    expect(концепция).toContain('per_plane: Number(форма.per_plane)')
    expect(концепция).toContain('if (форма.metric.trim() && форма.value.trim())')
    expect(концепция).toContain('показателей нет — сравнивать нечем, но в документ вариант идёт')
  })

  it('ССО задаётся временем прохождения узла, наклонённая орбита — наклонением', () => {
    expect(концепция).toContain("const поВремени = форма.pattern === 'sso'")
    expect(концепция).toContain("подгруппа[поВремени ? 'ltan' : 'inclination']")
  })
})

describe('оценка риска', () => {
  it('вероятность, влияние, стратегия и владелец правятся в строке реестра', () => {
    expect(реестр).toContain('aria-label={`вероятность риска ${р.code}`}')
    expect(реестр).toContain('aria-label={`влияние риска ${р.code}`}')
    expect(реестр).toContain('aria-label={`стратегия риска ${р.code}`}')
    expect(реестр).toContain('aria-label={`владелец риска ${р.code}`}')
    expect(реестр).toContain('const правитьРиск = (код: string, поля: Record<string, unknown>)')
  })

  it('уровень считает сервер, а не экран', () => {
    expect(реестр).toContain('пересчёт уровня (вероятность × влияние) делает сервер')
    expect(реестр).not.toContain('probability * impact')
  })
})

describe('полнота документа и запертая кнопка', () => {
  it('полнота названа ступенью, а не голым числом', () => {
    // Владелец 21.09: «и 3 из 5 — непонятно». Пять — это разделы, которых
    // ждёт ТЕКУЩАЯ ступень, и без её имени число не значит ничего.
    expect(документы).toContain('полнота {вид.complete} из {вид.total} к {вид.gate}')
    expect(документы).toContain('вид.not_due_yet > 0')
    expect(документы).toContain('const неполные = вид.sections.filter((р) => р.due_now && !р.complete)')
  })

  it('имя автора берётся из учётки, а не спрашивается заново', () => {
    expect(документы).toContain('const [автор, setАвтор] = useАвтор()')
    expect(клиентИсследования).toContain('export function useАвтор()')
    expect(клиентИсследования).toContain("fetch('/api/auth/whoami')")
  })

  it('запертая кнопка говорит причину строкой, а не подсказкой под курсором', () => {
    expect(документы).toContain('{помеха && <div className="v2-locked">Нажать нельзя: {помеха}</div>}')
    expect(документы).toContain('не названо, кто базирует')
  })
})
