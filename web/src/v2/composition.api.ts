// Данные экрана «Состав системы» (шип 2, экран 8): одно чтение на экран.
//
// Экран не решает ничего сам: ступень зрелости и разрывы считает СЕРВЕР
// (карточка узла гранями), какие величины спрашивать у узла — говорит ПОЛКА
// каркаса (`params` узла: ключ, имя, единица, точка), кратность живёт
// отдельным видом «вхождение ×N». Здесь — сбор прочитанного и приведение к
// строкам дерева-таблицы; ни одного вердикта из сравнения величин.
//
// Читаются только существующие маршруты v2: перечень сущностей (узлы,
// величины, вхождения), виды истины, полка каркаса, концепция, точки.
import { api, type EntityRow, type KindSpec, type UnitRow } from './api'

/** Узел состава со своим местом в дереве; поля — из истины вида «узел состава». */
export interface УзелСостава {
  code: string
  name: string
  level: number
  nature: string
  kind: string
  parent: string | null
  external: boolean
  /** Ссылка на узел полки, откуда узел взят каркасом («ПОЛКА:УЗЕЛ»). */
  template: string
  /** Применимость: код перечня истины; пусто — применяется. */
  applicability: string
  /** Почему снят с применения — обоснование отклонения. */
  deviation: string
  дети: УзелСостава[]
}

/** Поле анкеты узла по полке: что за величина, в чём и к какой точке нужна. */
export interface ПолеАнкеты {
  key: string
  name: string
  unit: string
  required_to: string
}

/** Заведённая величина узла: код записи нужен для правки на месте. */
export interface ВеличинаУзла {
  code: string
  узел: string
  key: string
  measure: Record<string, unknown>
  required_to: string
}

/** Кратность узла: число и запись вхождения, если она есть (иначе — с полки). */
export interface Кратность {
  сколько: number
  запись: string | null
}

/** Строка дерева на экране: узел, его отступ и есть ли что раскрывать. */
export interface СтрокаДерева {
  узел: УзелСостава
  глубина: number
  естьДети: boolean
}

export interface СоставЭкрана {
  корни: УзелСостава[]
  всего: number
  /** Базовый вариант построения — в заголовок экрана. */
  вариант: string
  /** Ближайшая непройденная точка проекта: к ней считается ступень. */
  точка: { key: string; title: string }
  величины: ВеличинаУзла[]
  /** Код узла → поля анкеты, которые спрашивает полка. */
  анкета: Record<string, ПолеАнкеты[]>
  кратность: Record<string, Кратность>
  /** Код вида узла → слово полки («subsystem» → «подсистема»). */
  словоВида: Record<string, string>
  /** Русские имена полей вида «узел состава» (истина схем). */
  метки: Record<string, string>
  /** Русские значения перечней вида «узел состава». */
  значения: Record<string, Record<string, string>>
  /** Допустимые значения перечней вида: виды узла берутся отсюда, не из кода. */
  перечни: Record<string, string[]>
  /** Имя поля кратности по истине вида «вхождение ×N» и его русское имя. */
  кратностьПоле: { поле: string; метка: string; вид: string } | null
  /** Знаки операторов величины: код записи → знак для экрана. */
  знаки: Record<string, string>
  /** Единицы справочника: код → как читать человеку. */
  единицы: Record<string, string>
  /** Каркас с полки: что даст взятие (существующий поток). */
  каркас: { shelf: string; rule?: string; nodes?: number; already?: number; note: string } | null
  /** Записи узлов как есть — карточке объекта (шип 5 §7): грани по истине. */
  записи: Record<string, EntityRow>
  /** Вид «узел состава» по истине — строение карточки узла. */
  вид: KindSpec
  /** Вид «значение параметра узла» — знаки операторов величин анкеты. */
  видВеличины: KindSpec | null
}

const строка = (з: unknown): string => (typeof з === 'string' ? з : '')

const число = (з: unknown): number => {
  if (typeof з === 'number') return з
  const разобрано = Number.parseInt(строка(з), 10)
  return Number.isNaN(разобрано) ? 0 : разобрано
}

const запись = (з: unknown): Record<string, unknown> =>
  (з && typeof з === 'object' ? з : {}) as Record<string, unknown>

const список = (з: unknown): unknown[] => (Array.isArray(з) ? з : [])

/**
 * Имя поля кратности НЕ выдумывается: оно берётся из истины вида «вхождение
 * ×N». Код сверяет своё ожидание с перечнем полей вида — если истина такого
 * поля не знает, колонка скажет это словами, а не покажет выдуманное число.
 */
const ОЖИДАЕМОЕ_ПОЛЕ_КРАТНОСТИ = 'quantity'
const ВИД_ВХОЖДЕНИЯ = 'component_usage'

/** Одно чтение экрана: узлы, величины, вхождения, полка, виды, точки. */
export async function читатьСостав(project: string): Promise<СоставЭкрана> {
  const пусто = { items: [] as EntityRow[] }
  const [
    узлы, величины, вхождения, видУзла, видВхождения, видВеличины,
    единицы, концепции, точки, полки, каркас,
  ] = await Promise.all([
    api.entities(project, 'component'),
    api.entities(project, 'parameter').catch(() => пусто),
    api.entities(project, ВИД_ВХОЖДЕНИЯ).catch(() => пусто),
    api.kind('component'),
    api.kind(ВИД_ВХОЖДЕНИЯ).catch(() => null as KindSpec | null),
    api.kind('parameter').catch(() => null as KindSpec | null),
    api.units().then((о) => о.items).catch(() => [] as UnitRow[]),
    api.concept(project).then((о) => о.items).catch(() => []),
    api.points(project).then((о) => о.items).catch(() => []),
    api.shelves('pbs_template').then((о) => о.items).catch(() => []),
    api.frame(project).catch(() => null),
  ])

  const поId = new Map<string, string>()
  узлы.items.forEach((з) => поId.set(з.id, з.code))

  const плоско: УзелСостава[] = узлы.items.map((з) => ({
    code: з.code,
    name: строка(з.doc.name) || з.code,
    level: число(з.doc.level),
    nature: строка(з.doc.nature) || 'node',
    kind: строка(з.doc.kind),
    parent: поId.get(строка(з.doc.parent)) ?? null,
    external: з.doc.external === true,
    template: строка(з.doc.template_ref),
    applicability: строка(з.doc.applicability),
    deviation: строка(з.doc.deviation_rationale),
    дети: [],
  }))
  плоско.sort((а, б) => а.level - б.level || а.code.localeCompare(б.code))

  const поКоду = new Map(плоско.map((у) => [у.code, у]))
  const корни: УзелСостава[] = []
  плоско.forEach((у) => {
    const родитель = у.parent ? поКоду.get(у.parent) : undefined
    if (родитель && родитель !== у) родитель.дети.push(у)
    else корни.push(у)
  })

  // Полка каркаса — та, из которой узлы взяты: её называет сам узел ссылкой
  // на шаблон. Догадки о «единственной полке» здесь нет: если ссылки нет,
  // анкеты с полки просто не будет, и экран не соврёт.
  const ссылка = плоско.map((у) => у.template).find((т) => т.includes(':'))
  const полка = полки.find((п) => ссылка?.startsWith(`${п.code}:`)) ?? null
  const узлыПолки = список(запись(полка?.doc).nodes).map(запись)

  const анкета: Record<string, ПолеАнкеты[]> = {}
  const сПолки: Record<string, number> = {}
  узлыПолки.forEach((у) => {
    const код = строка(у.code)
    if (!код) return
    const поля = список(у.params).map(запись).map((п) => ({
      key: строка(п.key),
      name: строка(п.name) || строка(п.key),
      unit: строка(п.unit),
      required_to: строка(п.required_to),
    })).filter((п) => п.key)
    if (поля.length > 0) анкета[код] = поля
    const кратно = число(у.default_quantity)
    if (кратно) сПолки[код] = кратно
  })

  // Имена видов узла (система · сегмент · элемент …) истина схем по-русски не
  // называет: `enum_labels` у поля пуст. Слова даёт та же полка каркаса
  // (`levels` — шесть уровней членения) в порядке перечня истины; кода вида
  // на экране не будет, а если полка слова не дала — не будет и выбора.
  const словаУровней = список(запись(полка?.doc).levels).map(строка)
  const словоВида: Record<string, string> = {}
  ;(видУзла.enums?.kind ?? []).forEach((код, и) => {
    if (словаУровней[и]) словоВида[код] = словаУровней[и]
  })

  const поле = видВхождения?.fields?.includes(ОЖИДАЕМОЕ_ПОЛЕ_КРАТНОСТИ)
    ? ОЖИДАЕМОЕ_ПОЛЕ_КРАТНОСТИ
    : null
  const кратностьПоле = поле && видВхождения
    ? { поле, метка: видВхождения.labels?.[поле] ?? поле, вид: видВхождения.title }
    : null

  const кратность: Record<string, Кратность> = {}
  if (кратностьПоле) {
    вхождения.items.forEach((з) => {
      const определение = строка(з.doc.definition) || строка(з.doc.definition_ref)
      const код = поId.get(определение) ?? определение
      const сколько = число(з.doc[кратностьПоле.поле])
      if (код && сколько) кратность[код] = { сколько, запись: з.code }
    })
  }
  Object.entries(сПолки).forEach(([код, сколько]) => {
    if (!кратность[код]) кратность[код] = { сколько, запись: null }
  })

  const величиныУзлов: ВеличинаУзла[] = величины.items.map((з) => ({
    code: з.code,
    узел: поId.get(строка(з.doc.target)) ?? '',
    key: строка(з.doc.key),
    measure: запись(з.doc.measure),
    required_to: строка(з.doc.required_to),
  })).filter((в) => в.узел && в.key)

  const ближайшая = точки.find((т) => !т.passed) ?? точки[точки.length - 1]

  return {
    корни,
    всего: плоско.length,
    вариант: концепции[0]?.variant ?? '',
    точка: { key: ближайшая?.key ?? '', title: ближайшая?.title ?? '' },
    величины: величиныУзлов,
    анкета,
    кратность,
    словоВида,
    метки: видУзла.labels ?? {},
    значения: видУзла.enum_labels ?? {},
    перечни: видУзла.enums ?? {},
    кратностьПоле,
    знаки: видВеличины?.measure_ops ?? {},
    единицы: Object.fromEntries(единицы.map((е) => [е.code, е.label])),
    каркас,
    записи: Object.fromEntries(узлы.items.map((з) => [з.code, з])),
    вид: видУзла,
    видВеличины,
  }
}

/** Строки дерева сверху вниз: дети показываются, пока открыт родитель. */
export function видимые(корни: УзелСостава[], открытые: Set<string>): СтрокаДерева[] {
  const строки: СтрокаДерева[] = []
  const обойти = (узел: УзелСостава, глубина: number) => {
    строки.push({ узел, глубина, естьДети: узел.дети.length > 0 })
    if (открытые.has(узел.code)) узел.дети.forEach((д) => обойти(д, глубина + 1))
  }
  корни.forEach((к) => обойти(к, 0))
  return строки
}

/** Все узлы дерева подряд — для умолчаний раскрытия и перечня уровней. */
export function всеУзлы(корни: УзелСостава[]): УзелСостава[] {
  const все: УзелСостава[] = []
  const обойти = (узел: УзелСостава) => { все.push(узел); узел.дети.forEach(обойти) }
  корни.forEach(обойти)
  return все
}

/** Ключи величин экрана: что спрашивает полка плюс что уже заведено. */
export function ключиВеличин(состав: СоставЭкрана): { key: string; name: string; unit: string; сколько: number }[] {
  const по = new Map<string, { key: string; name: string; unit: string; сколько: number }>()
  Object.values(состав.анкета).forEach((поля) => поля.forEach((п) => {
    if (!по.has(п.key)) по.set(п.key, { key: п.key, name: п.name, unit: п.unit, сколько: 0 })
  }))
  состав.величины.forEach((в) => {
    const было = по.get(в.key)
    if (было) { было.сколько += 1; return }
    по.set(в.key, { key: в.key, name: в.key, unit: строка(в.measure.unit), сколько: 1 })
  })
  // Порядок: сначала заполненные (их колонка полезнее), потом по имени.
  return [...по.values()].sort((а, б) => б.сколько - а.сколько || а.name.localeCompare(б.name))
}

/** Число или строка величины как есть: приведение, а не расчёт. */
const край = (з: unknown): string => (typeof з === 'number' ? String(з) : строка(з))

/**
 * Величина словами: знак оператора и подпись единицы приходят из истины
 * (`measure_ops` вида и справочник единиц), таблицы в коде нет. Срок вместо
 * значения (`tbr`) — тоже словами: кто уточняет и к какой точке.
 */
export function величинаСловами(
  measure: Record<string, unknown>,
  единица: (код: string) => string,
  знак: (код: string) => string,
): string {
  const одно = край(measure.value)
  const от = край(measure.min)
  const до = край(measure.max)
  const сама = одно || (от || до ? `${от}…${до}` : '')
  if (!сама) {
    const срок = Object.keys(запись(measure.tbr)).length > 0 ? запись(measure.tbr) : measure
    const кто = строка(срок.owner)
    const точка = строка(срок.gate)
    if (кто || точка) return `уточняется${кто ? ` · ${кто}` : ''}${точка ? ` к ${точка}` : ''}`
    return ''
  }
  const оп = строка(measure.op)
  return [оп ? знак(оп) : '', сама, единица(строка(measure.unit))].filter((ч) => ч).join(' ')
}

/** Число из правки: человек пишет с запятой, запись хранит точку. */
export function числоПравки(текст: string): number | null {
  const чистое = текст.trim().replace(',', '.')
  if (!чистое) return null
  const н = Number(чистое)
  return Number.isFinite(н) ? н : null
}
