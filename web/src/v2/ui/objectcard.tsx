// Карточка объекта (шип 5 §1.3) — одна на все виды.
//
// Открывается вниз от строки и закрывается той же строкой и Esc. Строение —
// из истины, руками ничего не размечается: грани — поля вида по `label`,
// контрол — по виджету поля (`field_rules.widgets`: enum → селект русскими
// метками; ref → пикер с поиском по коду и имени; measure → одна строка
// «оператор · число · единица справочника»; text → поле с автовысотой;
// вложенный массив → малая таблица; дата — ввод даты). Подсказка поля —
// `note` истины под полем, не в плейсхолдере. Строка долга — из
// `required_at`: поле, которого истина не требует, долгом не выглядит.
// Правка на месте: «сохранено, версия N» или «ничего не изменилось»
// словами; отказ сервера — его словами у поля.
import { useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { api, type EntityRow, type EntityVersion, type KindSpec, type UnitRow } from '../api'
import { СЛОВО_СТАТУСА } from '../words'
import { ПлотностьКонтекст, колонокГраней } from './density'
import { ИконКнопка } from './iconbutton'
import { Маркер } from './tabs'

/** Поля ядра: колонки записи и служебные пометы — гранями карточки не бывают. */
export const ПОЛЯ_ЯДРА = new Set(['id', 'code', 'kind', 'area', 'born_in', 'status', 'version', 'provenance', 'created_at', 'updated_at', 'notes', 'tags'])

/** Сколько граней видно сразу; остальные — «ещё N полей» (эксперт-режим — все). */
export const ГРАНЕЙ_СРАЗУ = 7

/** Стадия долга словами: accept — к приёму, baseline — к базированию, точка — к точке. */
export function стадияСловами(правило: string): string | null {
  const голова = правило.split(':')[0].trim()
  if (!голова) return null
  if (голова === 'accept') return 'к приёму'
  if (голова === 'baseline') return 'к базированию'
  return `к ${голова}`
}

/** Пусто ли значение поля: пустая строка, пустой массив, объект без значения. */
export function пусто(значение: unknown): boolean {
  if (значение === null || значение === undefined) return true
  if (typeof значение === 'string') return значение.trim() === ''
  if (Array.isArray(значение)) return значение.length === 0
  if (typeof значение === 'object') return Object.values(значение as object).every((в) => пусто(в))
  return false
}

/**
 * Грани вида: поля истины по порядку, без полей ядра и без скрытых экраном.
 * Поле ядра `kind` — вид самой записи, в документе его нет; поле `kind` с
 * перечнем у вида — своё поле (вид узла, точки, бюджета), и оно — грань.
 */
export function грани(spec: KindSpec, скрыть: string[] = []): string[] {
  const своё = (п: string) => п === 'kind' && Boolean(spec.enums?.kind?.length)
  return spec.fields.filter((п) => (!ПОЛЯ_ЯДРА.has(п) || своё(п)) && !скрыть.includes(п))
}

/**
 * Строка долга: незаполненные поля, которых истина требует к стадии, —
 * по стадиям в порядке появления: «к базированию: Категория · Приоритет».
 * Подставляемое системой (`system`, `auto`, `from_basis`, `default`) долгом
 * человека не бывает.
 */
export function долг(spec: KindSpec, doc: Record<string, unknown>): { стадия: string; поля: string[] }[] {
  const по = new Map<string, string[]>()
  for (const поле of spec.fields) {
    const правило = spec.required_at?.[поле]
    if (!правило) continue
    if (/\b(system|auto|from_basis|default)\b/.test(правило)) continue
    if (!пусто(doc[поле])) continue
    const стадия = стадияСловами(правило)
    if (!стадия) continue
    по.set(стадия, [...(по.get(стадия) ?? []), spec.labels?.[поле] ?? поле])
  }
  return [...по.entries()].map(([стадия, поля]) => ({ стадия, поля }))
}

/** Виджет поля — из истины (`widgets` вида); без него — строка. */
export function виджет(spec: KindSpec, поле: string): string {
  return spec.widgets?.[поле] ?? (spec.measures?.includes(поле) ? 'measure' : spec.enums?.[поле] ? 'enum' : 'str')
}

type Сохранение = { поле: string; итог: string; ошибка?: boolean }

/** Кто правил — человеком: «правка инженера: Иванов И. — причина» → «Иванов И.». */
export function ктоКратко(автор: string | undefined): string {
  if (!автор) return ''
  const без = автор.replace(/^правка инженера:\s*/, '')
  const i = без.indexOf(' — ')
  return (i < 0 ? без : без.slice(0, i)).trim()
}

/** Дата записи коротко: 24.09 (год виден в истории). */
export function датаКратко(когда: string | undefined): string {
  if (!когда) return ''
  const [г, м, д] = когда.slice(0, 10).split('-')
  return д && м && г ? `${д}.${м}` : когда
}

/** «версия N · кто · когда» из строки перечня; пусто — если сервер версию не прислал. */
export function метаЗаписи(row: EntityRow): string {
  if (row.version == null) return ''
  return [`версия ${row.version}`, ктоКратко(row.author), датаКратко(row.updated_at)].filter(Boolean).join(' · ')
}

/** Значение ссылки словами: код и имя записи, если она известна пикеру. */
function имяЗаписи(р: EntityRow): string {
  const д = р.doc
  return String(д.name ?? д.title ?? д.statement ?? д.designation ?? д.label ?? р.code)
}

/** Свой контрол поля: экран знает источник значений (полка, учётки), истина — только вид ссылки. */
export type СвойКонтрол = (п: { id: string; значение: unknown; занято: boolean; onSave: (значение: unknown) => void }) => ReactNode

export function Карточка({
  project, row, spec, заголовок, состояние, мета, actions, extra, скрыть = [], толькоЧтение = [], кМесту, onSaved, onClose,
  свои, всеСразу = false, сохранитьПоле,
}: {
  project: string
  row: EntityRow
  spec: KindSpec
  /** Заголовок карточки; по умолчанию — имя записи. */
  заголовок?: string
  /** Маркер состояния и слово: «черновик», «принято». */
  состояние?: ReactNode
  /** «версия N · кто · когда». */
  мета?: string
  /** Действия карточки пиктограммами (история, к месту, снять). */
  actions?: ReactNode
  /** Грани, которых у вида нет полем, но экран их показывает (основания, документы). */
  extra?: ReactNode
  /** Поля, которые экран показывает иначе (в таблице, своим редактором). */
  скрыть?: string[]
  /** Поля только для чтения: их считает система. */
  толькоЧтение?: string[]
  /** «К месту»: сцена, где запись рождается и правится в потоке работы. */
  кМесту?: { слово: string; go: () => void }
  onSaved?: () => void
  onClose?: () => void
  /** Свои контролы полей, чьи значения берутся не из записей проекта (класс миссии с полки, учётки). */
  свои?: Record<string, СвойКонтрол>
  /** Все грани сразу — у маленькой карточки (паспорт) нечего прятать за «ещё N полей». */
  всеСразу?: boolean
  /** Своя запись поля — у вида с собственным маршрутом правки (паспорт проекта). */
  сохранитьПоле?: (поле: string, значение: unknown) => Promise<{ version: number; changed: number }>
}) {
  const плотность = useContext(ПлотностьКонтекст)
  const колонок = колонокГраней(плотность)
  const [всеПоля, setВсеПоля] = useState(false)
  const [сохранение, setСохранение] = useState<Сохранение | null>(null)
  const [занято, setЗанято] = useState<string | null>(null)
  const корень = useRef<HTMLDivElement>(null)
  const список = useMemo(() => грани(spec, скрыть), [spec, скрыть])
  const видны = плотность.эксперт || всеПоля || всеСразу ? список : список.slice(0, ГРАНЕЙ_СРАЗУ)
  const скрыто = список.length - видны.length
  const долги = долг(spec, row.doc)
  const [история, setИстория] = useState<EntityVersion[] | null>(null)
  const [историяОтказ, setИсторияОтказ] = useState<string | null>(null)
  const показатьИсторию = () => {
    if (история) { setИстория(null); return }
    setИсторияОтказ(null)
    api.entityHistory(project, row.code)
      .then((r) => setИстория(r.items))
      .catch((e) => setИсторияОтказ(String((e as Error).message ?? e)))
  }
  const строкаМеты = мета ?? метаЗаписи(row)

  useEffect(() => {
    const esc = (e: KeyboardEvent) => { if (e.key === 'Escape' && onClose) onClose() }
    window.addEventListener('keydown', esc)
    return () => window.removeEventListener('keydown', esc)
  }, [onClose])

  const сохранить = (поле: string, значение: unknown) => {
    const было = row.doc[поле]
    if (JSON.stringify(было ?? null) === JSON.stringify(значение ?? null)) {
      setСохранение({ поле, итог: 'ничего не изменилось' })
      return
    }
    setЗанято(поле); setСохранение(null)
    const запрос = сохранитьПоле
      ? сохранитьПоле(поле, значение)
      : api.patchEntity(project, row.code, { [поле]: значение }, плотность.кто || 'инженер', `правка поля «${spec.labels?.[поле] ?? поле}» в карточке`)
    запрос
      .then((о) => {
        setСохранение({ поле, итог: о.changed === 0 ? 'ничего не изменилось' : `сохранено, версия ${о.version}` })
        onSaved?.()
      })
      .catch((e) => setСохранение({ поле, итог: String((e as Error).message ?? e), ошибка: true }))
      .finally(() => setЗанято(null))
  }

  return (
    <div className="v2-object" ref={корень} data-why="работа" aria-label={`карточка ${row.code}`}>
      <div className="v2-object__head">
        <span className="v2-mono">{row.code}</span>
        <h3>{заголовок ?? имяЗаписи(row)}</h3>
        {состояние ?? (
          <span className="v2-object__state">
            <Маркер health={долги.length > 0 ? 'debt' : 'ok'} title={долги.length > 0 ? 'есть долг: строка внизу карточки' : 'долгов нет'} />
            {СЛОВО_СТАТУСА[row.status] ?? row.status}
          </span>
        )}
        {строкаМеты && <span className="v2-object__meta">{строкаМеты}</span>}
        <span className="v2-object__acts">
          {actions}
          <ИконКнопка икон="история" слово={история ? 'скрыть историю' : 'история правок'} aria-pressed={история !== null} onClick={показатьИсторию} />
          {кМесту && <ИконКнопка икон="к-месту" слово={кМесту.слово} onClick={кМесту.go} />}
        </span>
      </div>
      {историяОтказ && <div className="v2-facet__err">{историяОтказ}</div>}
      {история && (
        <ol className="v2-object__history" aria-label={`история ${row.code}`}>
          {история.map((в) => (
            <li key={в.version}>
              <b>версия {в.version}</b> · {ктоКратко(в.author) || 'без автора'} · {датаКратко(в.at)}
              {в.changed.length > 0 && <span className="v2-object__meta"> · {в.changed.map((п) => spec.labels?.[п] ?? п).join(', ')}</span>}
            </li>
          ))}
        </ol>
      )}
      <div className={`v2-object__facets v2-object__facets--${колонок}`}>
        {видны.map((поле) => (
          <Грань key={поле} project={project} spec={spec} поле={поле} значение={row.doc[поле]} свой={свои?.[поле]}
            толькоЧтение={толькоЧтение.includes(поле) || виджет(spec, поле) === 'computed'}
            занято={занято === поле}
            итог={сохранение?.поле === поле ? сохранение : null}
            onSave={(з) => сохранить(поле, з)} />
        ))}
        {скрыто > 0 && (
          <div className="v2-facet">
            <button type="button" className="v2-link" onClick={() => setВсеПоля(true)}
              title={список.slice(ГРАНЕЙ_СРАЗУ).map((п) => spec.labels?.[п] ?? п).join(' · ').slice(0, 150)}>
              ещё {скрыто} {скрыто === 1 ? 'поле' : скрыто < 5 ? 'поля' : 'полей'}
            </button>
          </div>
        )}
        {extra}
      </div>
      {долги.length > 0 && (
        <div className="v2-object__debt">
          {долги.map((д, i) => (
            <span key={д.стадия}>{i > 0 ? ' · ' : ''}<b>{д.стадия}:</b> {д.поля.join(' · ')}</span>
          ))}
        </div>
      )}
    </div>
  )
}

/** Одна грань: подпись истины, контрол по виджету, подсказка `note` под полем, итог правки словами. */
function Грань({ project, spec, поле, значение, свой, толькоЧтение, занято, итог, onSave }: {
  project: string
  spec: KindSpec
  поле: string
  значение: unknown
  свой?: СвойКонтрол
  толькоЧтение: boolean
  занято: boolean
  итог: Сохранение | null
  onSave: (значение: unknown) => void
}) {
  const подпись = spec.labels?.[поле] ?? поле
  const w = виджет(spec, поле)
  const широкая = w === 'text' || w === 'table' || w === 'object'
  const id = `v2-f-${поле}`
  return (
    <div className={широкая ? 'v2-facet v2-facet--wide' : 'v2-facet'}>
      <label htmlFor={id}>{подпись}</label>
      {толькоЧтение
        ? <div className="v2-facet__ro" id={id}>{словами(spec, поле, значение)}</div>
        : свой
          ? свой({ id, значение, занято, onSave })
          : <Контрол project={project} spec={spec} поле={поле} id={id} значение={значение} занято={занято} onSave={onSave} />}
      {(spec.note_words?.[поле] ?? spec.notes?.[поле]) && <div className="v2-facet__hint">{spec.note_words?.[поле] ?? spec.notes[поле]}</div>}
      {итог && <div className={итог.ошибка ? 'v2-facet__err' : 'v2-facet__ok'}>{итог.итог}</div>}
    </div>
  )
}

/** Значение словами для чтения: перечень — меткой истины, ссылки — кодами, объект — парами. */
export function словами(spec: KindSpec, поле: string, значение: unknown): string {
  if (пусто(значение)) return '—'
  const метки = spec.enum_labels?.[поле]
  if (typeof значение === 'string') return метки?.[значение] ?? значение
  if (typeof значение === 'number' || typeof значение === 'boolean') return String(значение === true ? 'да' : значение === false ? 'нет' : значение)
  if (Array.isArray(значение)) return значение.map((э) => (typeof э === 'object' && э !== null ? Object.values(э as object).filter((в) => typeof в !== 'object').join(' · ') : (метки?.[String(э)] ?? String(э)))).join('; ')
  const о = значение as Record<string, unknown>
  if ('op' in о || 'unit' in о) {
    const знак = spec.measure_ops?.[String(о.op ?? '')] ?? о.op
    const границы = [о.min, о.max].filter((x) => x != null).join('…')
    return [знак, о.value ?? границы, о.unit].filter((x) => x != null && String(x) !== '').join(' ')
  }
  return Object.entries(о).filter(([, в]) => !пусто(в) && typeof в !== 'object').map(([к, в]) => `${к}: ${в}`).join(' · ')
}

function Контрол({ project, spec, поле, id, значение, занято, onSave }: {
  project: string
  spec: KindSpec
  поле: string
  id: string
  значение: unknown
  занято: boolean
  onSave: (значение: unknown) => void
}) {
  const w = виджет(spec, поле)
  if (w === 'enum') {
    const коды = spec.enums?.[поле] ?? []
    return (
      <select id={id} value={String(значение ?? '')} disabled={занято} onChange={(e) => onSave(e.target.value || null)}>
        <option value="">— не задано</option>
        {коды.map((к) => <option key={к} value={к}>{spec.enum_labels?.[поле]?.[к] ?? к}</option>)}
      </select>
    )
  }
  if (w === 'measure') return <ВводВеличины id={id} spec={spec} значение={значение} занято={занято} onSave={onSave} />
  if (w === 'ref' || w === 'refs') return <Пикер id={id} project={project} виды={spec.ref_kinds?.[поле] ?? []} многие={w === 'refs'} значение={значение} занято={занято} onSave={onSave} />
  if (w === 'text') return <Текст id={id} значение={String(значение ?? '')} занято={занято} onSave={(т) => onSave(т)} />
  if (w === 'number') {
    return <input id={id} type="number" defaultValue={String(значение ?? '')} disabled={занято}
      onBlur={(e) => onSave(числоИлиПусто(e.target.value))} />
  }
  if (w === 'bool') return <input id={id} type="checkbox" checked={значение === true} disabled={занято} onChange={(e) => onSave(e.target.checked)} />
  if (w === 'date') {
    return <input id={id} type="date" defaultValue={String(значение ?? '').slice(0, 10)} disabled={занято}
      onBlur={(e) => onSave(e.target.value || null)} />
  }
  if (w === 'list') {
    const список = Array.isArray(значение) ? значение.map(String) : []
    return <input id={id} defaultValue={список.join('; ')} disabled={занято}
      onBlur={(e) => onSave(e.target.value.split(';').map((с) => с.trim()).filter(Boolean))} />
  }
  if (w === 'table') return <МалаяТаблица значение={значение} />
  if (w === 'object') return <div className="v2-facet__ro" id={id}>{словами(spec, поле, значение)}</div>
  return <input id={id} defaultValue={String(значение ?? '')} disabled={занято} onBlur={(e) => onSave(e.target.value.trim() || null)} />
}

/** Число поля ввода либо пусто: пустой ввод снимает значение. */
function числоИлиПусто(текст: string): number | null {
  const чисто = текст.trim()
  return чисто ? Number(чисто) : null
}

/** Текст на всю ширину: высота растёт по тексту. */
function Текст({ id, значение, занято, onSave }: { id: string; значение: string; занято: boolean; onSave: (т: string | null) => void }) {
  const поле = useRef<HTMLTextAreaElement>(null)
  const подогнать = () => { const у = поле.current; if (у) { у.style.height = 'auto'; у.style.height = `${у.scrollHeight + 2}px` } }
  useEffect(подогнать, [значение])
  return <textarea id={id} ref={поле} rows={2} defaultValue={значение} disabled={занято} onInput={подогнать}
    onBlur={(e) => onSave(e.target.value.trim() || null)} />
}

/**
 * Величина одной строкой: оператор · число · единица справочника. Общая:
 * ею же правятся величины анкеты узла в карточке узла (шип 5 §7).
 */
export function ВводВеличины({ id, spec, значение, занято, onSave }: { id: string; spec: KindSpec; значение: unknown; занято: boolean; onSave: (з: unknown) => void }) {
  const о = (значение && typeof значение === 'object' ? значение : {}) as Record<string, unknown>
  const [единицы, setЕдиницы] = useState<UnitRow[]>([])
  useEffect(() => { api.units().then((r) => setЕдиницы(r.items)).catch(() => setЕдиницы([])) }, [])
  const операторы = Object.entries(spec.measure_ops ?? {})
  const [оп, setОп] = useState(String(о.op ?? ''))
  const [число, setЧисло] = useState(String(о.value ?? ''))
  const [ед, setЕд] = useState(String(о.unit ?? ''))
  const записать = (п: { op?: string; value?: string; unit?: string }) => {
    const новое = { op: п.op ?? оп, value: п.value ?? число, unit: п.unit ?? ед }
    if (новое.value.trim() === '') return
    const n = Number(новое.value.replace(',', '.'))
    onSave({ ...(новое.op ? { op: новое.op } : {}), value: Number.isNaN(n) ? новое.value : n, ...(новое.unit ? { unit: новое.unit } : {}) })
  }
  return (
    <div className="v2-facet__row">
      <select aria-label="оператор" value={оп} disabled={занято} onChange={(e) => { setОп(e.target.value); записать({ op: e.target.value }) }}>
        <option value="">—</option>
        {операторы.map(([код, знак]) => <option key={код} value={код}>{знак}</option>)}
      </select>
      <input id={id} aria-label="число" value={число} disabled={занято} onChange={(e) => setЧисло(e.target.value)} onBlur={() => записать({})} />
      <select aria-label="единица" value={ед} disabled={занято} onChange={(e) => { setЕд(e.target.value); записать({ unit: e.target.value }) }}>
        <option value="">— единица</option>
        {единицы.map((е) => <option key={е.code} value={е.code}>{е.label}</option>)}
      </select>
    </div>
  )
}

/** Пикер ссылки: поиск по коду и имени записей названных видов. */
function Пикер({ id, project, виды, многие, значение, занято, onSave }: {
  id: string; project: string; виды: string[]; многие: boolean; значение: unknown; занято: boolean; onSave: (з: unknown) => void
}) {
  const [записи, setЗаписи] = useState<EntityRow[]>([])
  useEffect(() => {
    Promise.all(виды.map((в) => api.entities(project, в).then((r) => r.items).catch(() => [] as EntityRow[])))
      .then((вс) => setЗаписи(вс.flat()))
  }, [project, виды.join(',')])
  const выбраны = многие ? (Array.isArray(значение) ? значение.map(String) : []) : []
  const ид = `${id}-list`
  const найти = (т: string) => {
    const код = т.split(' · ')[0].trim()
    return записи.find((р) => р.code === код || р.id === код)?.code ?? код
  }
  const подпись = (к: string) => { const р = записи.find((х) => х.code === к || х.id === к); return р ? `${р.code} · ${имяЗаписи(р)}` : к }
  return (
    <>
      <datalist id={ид}>{записи.map((р) => <option key={р.id} value={`${р.code} · ${имяЗаписи(р)}`} />)}</datalist>
      {многие ? (
        <div className="v2-facet__chips">
          {выбраны.map((к) => (
            <span key={к} className="v2-chip">{подпись(к)}
              <button type="button" className="v2-link" aria-label={`убрать ${к}`} title={`убрать ${к}`} disabled={занято}
                onClick={() => onSave(выбраны.filter((х) => х !== к))}> ×</button>
            </span>
          ))}
          <input id={id} list={ид} placeholder="код или имя" disabled={занято}
            onKeyDown={(e) => {
              if (e.key !== 'Enter') return
              const т = (e.target as HTMLInputElement).value.trim()
              if (!т) return
              onSave([...выбраны, найти(т)]); (e.target as HTMLInputElement).value = ''
            }} />
        </div>
      ) : (
        <input id={id} list={ид} defaultValue={typeof значение === 'string' ? подпись(значение) : ''} disabled={занято}
          onBlur={(e) => onSave(e.target.value.trim() ? найти(e.target.value) : null)} />
      )}
    </>
  )
}

/** Вложенный массив — малая таблица: колонки — ключи элементов. */
function МалаяТаблица({ значение }: { значение: unknown }) {
  const строки = Array.isArray(значение) ? значение.filter((э) => э && typeof э === 'object') as Record<string, unknown>[] : []
  if (строки.length === 0) return <div className="v2-facet__ro">—</div>
  const колонки = [...new Set(строки.flatMap((с) => Object.keys(с)))].filter((к) => строки.some((с) => typeof с[к] !== 'object'))
  return (
    <table className="v2-table v2-table--mini">
      <thead><tr>{колонки.map((к) => <th key={к}>{к}</th>)}</tr></thead>
      <tbody>{строки.map((с, i) => <tr key={i}>{колонки.map((к) => <td key={к}>{typeof с[к] === 'object' ? '' : String(с[к] ?? '')}</td>)}</tr>)}</tbody>
    </table>
  )
}
