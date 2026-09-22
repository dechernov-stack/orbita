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
import реестр from './risks.tsx?raw'
import клиентИсследования from './research.tsx?raw'
import оболочка from './shell.tsx?raw'
import модели from './models.tsx?raw'
import работа from './work.tsx?raw'
import поле from './knowledgefield.tsx?raw'
import точки from './points.tsx?raw'
import паспорт from './passport.tsx?raw'

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
  it('заводятся там же, где сравниваются: одна карточка, не две', () => {
    expect(клиент).toContain('addVariant:')
    expect(модели).toContain('function ФормаВарианта')
    expect(модели).toContain('api.addVariant(project, тело)')
    // Карточка «Варианты построения» одна на экран: дубль на сцене 7 и был
    // половиной ответа «куда и что писать непонятно» (владелец, 21.09).
    expect(концепция).not.toContain('ВариантыПостроения')
  })

  it('вариант описан строем, а показатель необязателен', () => {
    expect(модели).toContain("pattern: форма.pattern")
    expect(модели).toContain('per_plane: Number(форма.per_plane)')
    expect(модели).toContain('if (форма.metric.trim() && форма.value.trim())')
    expect(модели).toContain('Им наполняется §2 отчёта о концепции миссии')
  })

  it('ССО задаётся временем прохождения узла, наклонённая орбита — наклонением', () => {
    expect(модели).toContain("const поВремени = форма.pattern === 'sso'")
    expect(модели).toContain("подгруппа[поВремени ? 'ltan' : 'inclination']")
  })
})

describe('оценка риска', () => {
  it('вероятность, влияние, стратегия и владелец правятся в строке реестра', () => {
    expect(реестр).toContain('aria-label={`вероятность риска ${р.code}`}')
    expect(реестр).toContain('aria-label={`влияние риска ${р.code}`}')
    expect(реестр).toContain('aria-label={`стратегия риска ${р.code}`}')
    expect(реестр).toContain('aria-label={`владелец риска ${р.code}`}')
    expect(реестр).toContain('const правитьРиск = (код: string, поля: Record<string, unknown>, зачем = ')
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

describe('где мы находимся', () => {
  it('комплект точки виден на экране документов, а не только в матрице точек', () => {
    // Владелец 21.09: «базированные документы не видно, что они уже готовы.
    // Где мы находимся — вообще непонятно… То ли можно ехать дальше — то ли нет».
    expect(документы).toContain('function КомплектТочки')
    expect(документы).toContain("(с.our_ref ?? '').startsWith('document_template:')")
    expect(документы).toContain('Ехать дальше нельзя')
    expect(документы).toContain('Комплект документов собран')
  })

  it('строка списка говорит, базирован документ или нет', () => {
    expect(документы).toContain('базирован «${д.baseline_name}» · ${д.baseline_at}')
    expect(документы).toContain("'не базирован'")
    expect(документы).toContain('{д.complete} из {д.total} разделов полны к {д.gate}')
  })

  it('экран документа называет, чего ждёт от него точка', () => {
    expect(документы).toContain("?.find((с) => с.our_ref === `document_template:${code}`)")
    expect(документы).toContain('ждёт зрелость')
    expect(документы).toContain('Пока так — точка не пустит.')
  })
})

describe('дорога от раздела к сцене', () => {
  it('раздел, которого ждут, ведёт на свою сцену', () => {
    // «§2 Анализ альтернатив … ждёт сцен 7» — это адрес, а не жалоба:
    // без перехода человек читает его и не знает, куда идти (21.09).
    expect(документы).toContain('к месту: сцена {с}')
    expect(документы).toContain('onGoScene={onGoScene}')
    expect(оболочка).toContain('setWantReason(зачем ?? null)')
    expect(работа).toContain('Сюда вас послал документ')
    // Экран подводит к карточке, чьё имя названо в причине: соответствие
    // сверяется двумя именами из данных, а не таблицей в коде.
    expect(работа).toContain('зачем.toLowerCase().includes(имя.toLowerCase())')
  })

  it('десять сцен разом — не адрес: дорога предлагается, когда она одна-три', () => {
    expect(документы).toContain('const адресУзнан = сцены.length > 0 && сцены.length <= 3')
  })
})

describe('экран сцены 7 читается', () => {
  it('варианты стоят перед выбором базового, а не последними', () => {
    const вар = концепция.indexOf('<Variants project={project} />')
    const баз = концепция.indexOf('<span className="v2-card__title">Базовый вариант</span>')
    expect(вар).toBeGreaterThan(0)
    expect(вар).toBeLessThan(баз)
  })

  it('длинный состав свёрнут: сорок девять узлов — справка, а не экран работы', () => {
    expect(концепция).toContain('const ДЛИННЫЙ = 12')
    expect(концепция).toContain('(развёрнут ? узлы : узлы.slice(0, ДЛИННЫЙ))')
    expect(концепция).toContain('показать все ${узлы.length}')
  })

  it('карточка базового варианта не повторяет списками то, что уже сказала', () => {
    // «решил стенд · отклонены: … · отложено: 1» и следом те же строки
    // перечнем — владелец назвал это «кучей мусора» (21.09).
    expect(концепция).not.toContain('` · отклонены: ${к.rejected.map')
    expect(концепция).not.toContain('списки решения')
  })
})

describe('тема — не выход сцены', () => {
  it('§11 ведёт в поле знаний по ВИДУ записи, а не по списку сцен', () => {
    expect(документы).toContain("э.select === 'topic' && onGoField")
    expect(документы).toContain('к месту: поле знаний → «Завести тему»')
    // Дорога ведёт К ФОРМЕ: поле знаний открывается сразу ручным вводом.
    expect(оболочка).toContain("onGoField={() => { setРучнойВвод(true); setSection('knowledge') }}")
    expect(оболочка).toContain('ручной={ручнойВвод}')
    expect(поле).toContain('const [рукой, setРукой] = useState(ручной)')
  })
})

describe('«их нет» у одного перечня (шип 1, п. 1.8)', () => {
  it('кнопка закрытия словами есть и у элемента, не только у раздела', () => {
    expect(документы).toContain('(раздел.empty_ok_with_statement || э.empty_ok_with_statement) && onЗакрытьСловами')
    expect(документы).toContain('их нет — закрыть перечень тезисом')
    expect(клиент).toContain('empty_ok_with_statement?: boolean')
  })
})

describe('паспорт проекта (З-25, шип 1, п. 1.5)', () => {
  it('правится на месте: поля истины, версия, даты точек; вход из рейки и из шапки', () => {
    expect(клиент).toContain('passport: (project: string) => вызов<Passport>')
    expect(клиент).toContain('patchPassport:')
    expect(паспорт).toContain("const поля = ['name', 'mission_class', 'manager', 'standard'] as const")
    expect(паспорт).toContain('v{паспорт.version} · {паспорт.updated_at} · {паспорт.updated_by}')
    expect(паспорт).toContain('aria-label={`дата точки ${т.key}`}')
    // Метки полей — из истины, не из кода экрана.
    expect(паспорт).toContain('const метка = паспорт.labels[поле] ?? поле')
    expect(оболочка).toContain("{ key: 'passport', title: 'Паспорт'")
    expect(оболочка).toContain("onClick={() => setSection('passport')}")
  })

  it('DA — роль проекта, назначает руководитель; остальным сказано, кто назначает', () => {
    expect(паспорт).toContain("fetch('/api/auth/roles', {")
    expect(паспорт).toContain("role: 'da_review'")
    expect(паспорт).toContain('Роли назначает руководитель проекта.')
  })
})

describe('риски пачкой (шип 1, п. 1.2–1.4)', () => {
  it('реестр живёт в рейке с сцены 11, до неё — в эксперт-режиме', () => {
    expect(оболочка).toContain("{ key: 'risks', title: 'Риски', wave: 4, fromScene: '11'")
    expect(оболочка).toContain('(expert || сценаДостигнута(s.fromScene))')
    expect(оболочка).toContain("return !сцена || сцена.state !== 'locked'")
  })

  it('отбор, порядок, «держат точку», по владельцу', () => {
    expect(реестр).toContain("{ код: 'open', слово: 'открытые' }, { код: 'closed', слово: 'закрытые' }, { код: 'all', слово: 'все' }")
    expect(реестр).toContain('aria-label="порядок рисков"')
    expect(реестр).toContain('aria-label="держат точку"')
    expect(реестр).toContain('const группы: { владелец: string | null; строки: RiskRow[] }[] = поВладельцу')
  })

  it('закрытие с экрана — решением словами; возврат — причиной; кнопка без слов заперта и говорит почему', () => {
    expect(клиент).toContain('closeRisk:')
    expect(клиент).toContain('reopenRisk:')
    expect(реестр).toContain("api.closeRisk(project, р.code, решение.trim(), автор || 'инженер')")
    expect(реестр).toContain("api.reopenRisk(project, р.code, причина.trim(), автор || 'инженер')")
    expect(реестр).toContain('Нажать нельзя: решение не названо.')
    expect(реестр).toContain('Нажать нельзя: причина не названа.')
  })

  it('карточка: условие · событие · последствие, балл кликом, стратегия и категория — словами истины', () => {
    expect(реестр).toContain("правитьРиск(р.code, { cec }, 'условие · событие · последствие')")
    expect(реестр).toContain('aria-label={`вероятность риска ${р.code} кликом`}')
    expect(реестр).toContain('aria-label={`влияние риска ${р.code} кликом`}')
    // Перечисления — из enum_labels вида, копии в коде нет.
    expect(реестр).toContain("Object.entries(метки('strategy'))")
    expect(реестр).toContain("Object.entries(метки('category'))")
    expect(реестр).not.toContain('<option value="mitigate">снижать</option>')
    expect(реестр).toContain('aria-label={`связать риск ${р.code} с узлом`}')
  })

  it('точка называет риски, которые её держат, каждый — ссылкой в карточку', () => {
    expect(точки).toContain("api.risks(project).then((r) => setРиски(r.items))")
    expect(точки).toContain('риски={риски.filter((р) => р.holds.includes(точка.key))}')
    expect(точки).toContain('Риски, которые держат точку')
    expect(точки).toContain('onClick={() => onGoRisk(р.code)}')
    expect(оболочка).toContain("onGoRisk={(код) => { setWantRisk(код); setSection('risks') }}")
    expect(реестр).toContain("useEffect(() => { if (wantRisk) { setОткрыт(wantRisk); setОтбор('all') } }, [wantRisk])")
  })
})

describe('решение точки', () => {
  it('стоит сразу за готовностью, а не за матрицей и замечаниями', () => {
    // Владелец 21.09 базировал последний документ: «явного перехода нет —
    // всё просто стоит». Кнопка была, но ниже двух экранов таблицы.
    const готовность = точки.indexOf('<h4 className="v2-h4">Готовность</h4>')
    const решение = точки.indexOf('Решение: переход в')
    const матрица = точки.indexOf('Матрица зрелости комплекта')
    expect(готовность).toBeGreaterThan(0)
    expect(решение).toBeGreaterThan(готовность)
    expect(решение).toBeLessThan(матрица)
  })

  it('перед решением сказано, что держит точку', () => {
    expect(точки).toContain('Ничто не держит: решение можно фиксировать.')
    expect(точки).toContain('Сервер откажет, пока это не закрыто.')
  })

  it('вопрос экспертизы без замечания стоит точкой: «да» система не хранит', () => {
    expect(точки).toContain("{нет ? '☐' : '·'}")
    expect(точки).toContain('ответить «нет» → замечание')
    expect(точки).toContain('Ответ «да» нигде не хранится')
  })
})

describe('права на точке (шип 1, п. 1.1)', () => {
  it('кнопка решения видна только роли, которая решает; остальным — кто фиксирует', () => {
    expect(точки).toContain("!точка.passed && точка.role && !мои.includes(точка.role)")
    expect(точки).toContain('Фиксирует {РОЛЬ[точка.role] ?? точка.role}')
    expect(точки).toContain('роль в проекте не назначена')
    expect(точки).toContain("!точка.passed && (!точка.role || мои.includes(точка.role))")
  })

  it('мои роли — ровно как у сервера: от имени, иначе роль проекта; без добавок «РП носит и DA»', () => {
    expect(точки).toContain('export function моиРоли')
    expect(точки).toContain('if (учётка.acting_role) return [учётка.acting_role]')
    expect(точки).not.toContain("'da_review')")
    expect(оболочка).toContain('учётка={я}')
    expect(клиент).toContain('projectRoles:')
  })
})
