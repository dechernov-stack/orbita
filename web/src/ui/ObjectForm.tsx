// Форма ввода объекта, построенная ПО СХЕМЕ вида (шаг 15 §2).
//
// Перечень полей, обязательность и допустимые значения приходят из схемы,
// а не переписываются сюда руками: список полей в клиенте разошёлся бы
// со схемой молча, а схема — нормативная структура данных, а не подсказка.
//
// Расчётов здесь нет и быть не может (STEP-6 §3.2): форма собирает документ
// и отдаёт его серверу, проверяет — сервер теми же правилами, что и импорт.
import { DateInput } from './DateInput'
import { useEffect, useMemo, useState , Suspense, lazy} from 'react'
import { OBJECT_ID, screenOfObject } from '../api/intent'
import { RefPicker } from './RefPicker'
import { type KindRow, edit, type JsonSchema, type StoredSummary } from '../api/edit'
import { useSession } from './session'

/** Значение любого поля документа: форма не типизирует модель за схему. */
type Value = unknown

interface FieldProps {
  name: string
  schema: JsonSchema
  value: Value
  required: boolean
  path: string
  errors: Array<{ path: string; message: string; rule?: string }>
  onChange: (next: Value) => void
}

/** Группа подписей для перечисления поля. Пары «поле → группа» задаёт сервер... */
const ENUM_GROUPS: Record<string, string> = {
  status: 'lifecycle',
  role: 'stakeholder_role',
  consumer_class: 'consumer_class',
  category: 'requirement_category',
  level: 'requirement_level',
  method: 'verification_method',
  kind: 'verification_kind',
  phase: 'phase',
  strategy: 'risk_strategy',
  segment: 'segment',
  subsystem: 'subsystem',
  operator: 'mop_operator',
  rollup: 'mop_rollup',
  source: 'provenance_source',
  maturity: 'maturity',
  placement: 'placement',
  limiting_factor: 'limiting_factor',
  name: 'moe_name',
  type: 'interface_type',
}

/**
 * Поля объекта, которые заполняет система, а не инженер (§1.1, TZ-COM-005).
 * Только ВЕРХНИЙ уровень документа: у вложенных записей `id` — содержание,
 * а не служебное поле. Показатель качества без своего MOE-0001 не сохранить,
 * и прятать его форма не вправе.
 */
const SYSTEM_FIELDS = new Set(['id', 'provenance', 'lifecycle', 'stale'])

/** Происхождение проставляется системой на любом уровне: и у величин тоже. */
const ALWAYS_SYSTEM = new Set(['provenance'])

const hidden = (name: string, top: boolean): boolean =>
  top ? SYSTEM_FIELDS.has(name) : ALWAYS_SYSTEM.has(name)

const isQuantity = (schema: JsonSchema): boolean =>
  schema.type === 'object' &&
  !!schema.properties?.value &&
  !!schema.properties?.unit &&
  schema.properties.value.type === 'number'

const isLongText = (name: string): boolean =>
  ['statement', 'rationale', 'approach', 'description', 'means', 'note', 'assumptions'].includes(name)

/**
 * Ввод и пустота. Функции работают со СТРОКОЙ ПОЛЯ ВВОДА, а не с величиной
 * модели: сравнивать величины в клиенте нельзя — вердикты по ним выносит
 * сервер (STEP-6 §3.2), и обход кода это стережёт.
 */
const asText = (raw: unknown): string => (raw === undefined || raw === null ? '' : String(raw))

const isBlank = (raw: string): boolean => raw.length === 0

/** Строка поля в число: пусто — «поля нет», а не ноль. */
const toNumber = (raw: string): number | undefined => (isBlank(raw) ? undefined : Number(raw))

const isChecked = (raw: unknown): boolean => raw === true

/**
 * Схемный отказ — по-русски (второй заход: инженер получил
 * «does not match the regex pattern ^[A-Z]{2,3}-[0-9]{4}$» на «CE-003»
 * и не обязан был догадываться, что цифр должно быть четыре). Перевод —
 * по правилу отказа; незнакомое правило показывается серверным текстом:
 * честный пробел лучше молчания.
 */
export function humanizeError(e: { path: string; message: string; rule?: string }): string {
  const field = e.path.replace(/^\//, '').replace(/\/[0-9]+$/, '')
  switch (e.rule) {
    case 'pattern':
      if (e.message.includes('^[A-Z]{2,3}-[0-9]{4}$')) {
        return 'не по формату идентификатора: нужно <ВИД>-НННН с четырьмя цифрами — CE-0003, а не CE-003'
      }
      return `не по формату: образец ${e.message.substring(e.message.indexOf('pattern') + 8) || '—'}`
    case 'required':
      return 'обязательное поле не заполнено'
    case 'minLength':
      return 'значение короче допустимого'
    case 'maxLength':
      return 'значение длиннее допустимого'
    case 'minItems':
      return 'записей меньше, чем требует схема'
    case 'enum':
      return 'значение вне допустимого перечня — выберите из списка'
    case 'type':
      return 'не тот тип значения'
    case 'additionalProperties':
      return `поле «${field || '—'}» схемой не предусмотрено`
    default:
      return e.message
  }
}

function Errors({ path, errors, deep }: { path: string; errors: Array<{ path: string; message: string; rule?: string }>; deep?: boolean }) {
  // deep — для листовых коллекций: серверная ошибка элемента приходит путём
  // «/resolution_refs/0», а поле слушало ровно «/resolution_refs» — отказ
  // не показывался нигде, и сохранение выглядело молчаливо сломанным
  // (находка второго захода).
  const mine = errors.filter((e) => e.path === path || (deep && e.path.startsWith(`${path}/`)))
  if (mine.length === 0) return null
  return (
    <div className="warn" role="alert">
      {mine.map((e) => humanizeError(e)).join('; ')}
    </div>
  )
}

/**
 * Подпись поля (блок D, §3.6): русская подпись из таблицы сервера, код поля —
 * в подсказке. Неизвестное поле показывается кодом — видимый пробел.
 */
function FieldName({ name, required }: { name: string; required?: boolean }) {
  const { fieldLabel } = useSession()
  return (
    <>
      <span title={name}>{fieldLabel(name)}</span>
      {required && <span className="req"> *</span>}
    </>
  )
}

/** Величина: число и единица рядом. Происхождение проставит сервер. */
function QuantityField({ name, schema, value, required, path, errors, onChange }: FieldProps) {
  const current = (value ?? {}) as Record<string, unknown>
  const units = schema.properties?.unit?.examples
  return (
    <div className="field">
      <label>
        <FieldName name={name} required={required} />
      </label>
      <span className="quantity">
        <input
          type="number"
          step="any"
          aria-label={`${name}: значение`}
          value={asText(current.value)}
          onChange={(e) => onChange({ ...current, value: toNumber(e.target.value) })}
        />
        <input
          className="unit"
          aria-label={`${name}: единица`}
          placeholder="ед."
          list={units ? `units-${name}` : undefined}
          value={asText(current.unit)}
          onChange={(e) => onChange({ ...current, unit: e.target.value })}
        />
        {units && (
          <datalist id={`units-${name}`}>
            {units.map((u) => (
              <option key={u} value={u} />
            ))}
          </datalist>
        )}
      </span>
      <Errors path={path} errors={errors} />
    </div>
  )
}

/**
 * МВП-М1 §3 (ЗАДАЧА-CODE-ПОСТРОЕНИЕ): подгруппы построения — таблицей.
 * Строка = подгруппа; поля по типу (ССО скрывает наклонение, показывает
 * LTAN и вычисленное серым); свёртка «итого КА: сумма» под таблицей.
 * Арифметику и наклонение ССО считает СЕРВЕР (/calc/constellation-summary,
 * ловушка 2) — клиент показывает ответ.
 */
function SubgroupsField({ value, path, errors, onChange }: FieldProps) {
  type Row = {
    name?: string; kind?: string; planes?: number; per_plane?: number
    altitude_km?: number; inclination_deg?: number; phasing?: number; ltan_h?: number
  }
  const rows = (Array.isArray(value) ? value : []) as Row[]
  const [summary, setSummary] = useState<{
    total_sats: number; formula: string; warnings: string[]
    subgroups: Array<{ index: number; sats: number; computed_inclination_deg?: number }>
  } | null>(null)
  const { fieldLabel } = useSession()

  // сводка — сервером, с лёгкой задержкой на ввод
  useEffect(() => {
    if (rows.length === 0) { setSummary(null); return }
    const t = setTimeout(() => {
      import('../api/client').then(({ api }) =>
        api.calcConstellationSummary(rows).then(setSummary).catch(() => setSummary(null)),
      )
    }, 350)
    return () => clearTimeout(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [JSON.stringify(rows)])

  const patch = (i: number, changes: Partial<Row>) => {
    const next = rows.map((r, j) => (j === i ? { ...r, ...changes } : r))
    onChange(next)
  }
  const numArg = (raw: string): number | undefined => (raw === '' ? undefined : Number(raw))

  const KINDS: Array<{ key: string; title: string }> = [
    { key: 'walker_delta', title: 'Walker Δ' },
    { key: 'walker_star', title: 'Walker ★' },
    { key: 'sso', title: 'ССО' },
  ]

  return (
    <div className="field">
      <label><FieldName name="subgroups" /></label>
      <table style={{ width: '100%' }}>
        <thead>
          <tr>
            <th>Имя</th>
            <th style={{ width: 150 }}>Тип</th>
            <th style={{ width: 70 }} title="число орбитальных плоскостей">Плоск.</th>
            <th style={{ width: 70 }} title="КА в каждой плоскости">КА/пл.</th>
            <th style={{ width: 84 }}>Высота, км</th>
            <th style={{ width: 110 }} title="walker: вводится; ССО: вычисляется из высоты">Наклонение, °</th>
            <th style={{ width: 60 }} title="фазовый параметр F (Walker)">F</th>
            <th style={{ width: 80 }} title="ССО: местное время восходящего узла, ч">LTAN, ч</th>
            <th style={{ width: 60 }} title="КА подгруппы — произведение, считает сервер">КА</th>
            <th style={{ width: 30 }} />
          </tr>
        </thead>
        <tbody>
          {rows.map((r, i) => {
            const sso = r.kind === 'sso'
            const srv = summary?.subgroups.find((g) => g.index === i)
            return (
              <tr key={i}>
                <td>
                  <input aria-label="имя подгруппы" value={r.name ?? ''} style={{ width: '100%' }}
                    onChange={(e) => patch(i, { name: e.target.value || undefined })} />
                </td>
                <td>
                  <span style={{ display: 'flex', gap: 2 }}>
                    {KINDS.map((k) => (
                      <button key={k.key} type="button" className="tab" aria-selected={r.kind === k.key}
                        title={k.key === 'sso'
                          ? 'солнечно-синхронная: наклонение вычисляется из высоты, вводится LTAN'
                          : k.key === 'walker_star' ? 'плоскости веером 180°/P' : 'плоскости по кругу 360°/P'}
                        onClick={() => patch(i, { kind: k.key })}>
                        {k.title}
                      </button>
                    ))}
                  </span>
                </td>
                <td><input aria-label="плоскости" type="number" min={1} value={r.planes ?? ''} style={{ width: 56 }}
                  onChange={(e) => patch(i, { planes: numArg(e.target.value) })} /></td>
                <td><input aria-label="КА в плоскости" type="number" min={1} value={r.per_plane ?? ''} style={{ width: 56 }}
                  onChange={(e) => patch(i, { per_plane: numArg(e.target.value) })} /></td>
                <td><input aria-label="высота" type="number" value={r.altitude_km ?? ''} style={{ width: 70 }}
                  onChange={(e) => patch(i, { altitude_km: numArg(e.target.value) })} /></td>
                <td>
                  {sso ? (
                    <span className="secondary"
                      title={`вычислено: ССО для h=${r.altitude_km ?? '—'} км — руками не вводится`}>
                      {srv?.computed_inclination_deg !== undefined
                        ? `${srv.computed_inclination_deg.toFixed(2)} (выч.)`
                        : '— (выч.)'}
                    </span>
                  ) : (
                    <input aria-label="наклонение" type="number" value={r.inclination_deg ?? ''} style={{ width: 80 }}
                      onChange={(e) => patch(i, { inclination_deg: numArg(e.target.value) })} />
                  )}
                </td>
                <td><input aria-label="фазовый параметр" type="number" min={0} value={r.phasing ?? ''} style={{ width: 44 }}
                  disabled={sso} title={sso ? 'фазировка ССО — нулевая' : 'фазовый параметр F'}
                  onChange={(e) => patch(i, { phasing: numArg(e.target.value) })} /></td>
                <td>
                  <input aria-label="LTAN" type="number" min={0} max={23.99} step={0.5}
                    value={r.ltan_h ?? ''} style={{ width: 64 }} disabled={!sso}
                    title={sso ? 'местное время восходящего узла опорной плоскости' : 'только для ССО'}
                    onChange={(e) => patch(i, { ltan_h: numArg(e.target.value) })} />
                </td>
                <td className="num" title="произведение плоскостей на КА в плоскости — считает сервер">
                  {srv?.sats ?? '…'}
                </td>
                <td>
                  <button type="button" className="tab" title="убрать подгруппу"
                    onClick={() => onChange(rows.filter((_, j) => j !== i))}>✕</button>
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
      <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
        <button type="button" className="tab"
          onClick={() => onChange([...rows, { kind: 'walker_delta', planes: 1, per_plane: 1, altitude_km: 600, phasing: 0 }])}>
          + подгруппа
        </button>
        {summary && (
          <span title="итог построения — сумма по подгруппам, вычисляется, не вводится">
            итого КА: <b className="mono">{summary.formula || summary.total_sats}</b>
          </span>
        )}
        {summary?.warnings.map((w) => <span key={w} className="warn">{w}</span>)}
      </div>
      <div className="secondary hint">{fieldLabel('subgroups') === 'subgroups' ? '' : ''}
        Составное построение: подгруппы считаются объединением — покрытие по всем КА,
        свёртки и бюджеты суммой. Прежний одиночный Walker живёт как одна подгруппа.
      </div>
      <Errors path={path} errors={errors} deep />
    </div>
  )
}

/** Массив объектов: подформа на каждый элемент, тоже по схеме. */
function ObjectArrayField({ name, schema, value, required, path, errors, onChange }: FieldProps) {
  const items = Array.isArray(value) ? (value as Record<string, unknown>[]) : []
  const itemSchema = schema.items ?? {}
  return (
    <div className="field">
      <label>
        <FieldName name={name} required={required} />
        <span className="secondary"> · {items.length}</span>
      </label>
      {items.map((item, i) => (
        <div key={i} className="subform">
          <div className="subform__head">
            <span className="secondary">
              <FieldName name={name} /> [{i + 1}]
            </span>
            <button
              type="button"
              className="tab"
              onClick={() => onChange(items.filter((_, j) => j !== i))}
            >
              убрать
            </button>
          </div>
          <Fields
            schema={itemSchema}
            value={item}
            path={`${path}/${i}`}
            errors={errors}
            onChange={(next) => onChange(items.map((it, j) => (j === i ? next : it)))}
          />
        </div>
      ))}
      <button type="button" className="tab" onClick={() => onChange([...items, {}])}>
        + <FieldName name={name} />
      </button>
      <Errors path={path} errors={errors} />
    </div>
  )
}

/** Массив строк: значения через запятую — ссылки на объекты вводятся так же. */
function StringArrayField({ name, schema, value, required, path, errors, onChange }: FieldProps) {
  const items = Array.isArray(value) ? (value as string[]) : []
  return (
    <div className="field">
      <label>
        <FieldName name={name} required={required} />
      </label>
      <input
        aria-label={name}
        value={items.join(', ')}
        placeholder="идентификаторы через запятую: CE-0001, DI-0005"
        onChange={(e) =>
          onChange(
            e.target.value
              .split(',')
              .map((s) => s.trim())
              .filter(Boolean),
          )
        }
      />
      {schema.description && <div className="secondary hint">{schema.description}</div>}
      <Errors path={path} errors={errors} deep />
    </div>
  )
}

/**
 * Поле-ссылка на объект модели (второй заход: «allocated_to — это ссылка на
 * элемент? непонятно что делать»). Паттерн схемы вида ^(CM|IF)-[0-9]{4}$
 * называет допустимые ВИДЫ — форма подгружает их реестры и даёт выпадающий
 * список «идентификатор — название» вместо голого текста. Ввод руками
 * остаётся: список подсказывает, а не запирает.
 */
const REF_PATTERN = /^\^\(?([A-Z|]{2,24})\)?-\[0-9\]\{4\}\$$/

const refCache = new Map<string, Promise<StoredSummary[]>>()

/**
 * Сброс кэша списков-ссылок. Кэш дедуплицирует запросы полей ОДНОЙ формы,
 * а не переживает её: находка прогона — инженер создал вариант группировки,
 * а выпадающий список сценария так и предлагал старый перечень, и внести
 * новое построение было невозможно.
 */
export function invalidateRefOptions() {
  refCache.clear()
}

/**
 * Заготовка «на основе»: содержимое источника без служебных полей. Версии
 * входов не наследуются намеренно: сервер явно заданные не перетирает
 * (V008), и унаследованный штамп молча пришпилил бы вариант к старым
 * версиям входов.
 */
export function basedOnTemplate(source: Record<string, unknown>): Record<string, unknown> {
  const doc = { ...source }
  delete doc.id
  delete doc.provenance
  delete doc.lifecycle
  delete doc.input_versions
  if (typeof doc.name === 'string' && doc.name) doc.name = `${doc.name} (вариант)`
  return doc
}

// Редактор загружается отложенно: RefField открывает форму варианта, а
// ObjectEditor сам строится на ObjectForm — прямой импорт закольцевал бы
// модули на инициализации.
const LazyObjectEditor = lazy(() =>
  import('./ObjectEditor').then((m) => ({ default: m.ObjectEditor })),
)

/**
 * Створка «вариант по ссылке»: форма нового объекта того же вида поверх
 * текущей, с содержимым объекта, на который ссылается поле. Находка
 * прогона: вариант сценария — это прежде всего другое орбитальное
 * построение, а из формы сценария построение было не внести — только
 * выбрать из существующих.
 */
function RefVariantSheet({ sourceId, onDone, onClose }: {
  sourceId: string
  onDone: (newId: string) => void
  onClose: () => void
}) {
  const [row, setRow] = useState<KindRow | null>(null)
  const [tpl, setTpl] = useState<Record<string, unknown> | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  useEffect(() => {
    let alive = true
    Promise.all([edit.kinds(), edit.object(sourceId)])
      .then(([kinds, o]) => {
        if (!alive) return
        setRow(kinds.find((k) => k.prefix === sourceId.split('-')[0]) ?? null)
        setTpl(basedOnTemplate(o.doc as Record<string, unknown>))
      })
      .catch((e) => { if (alive) setFailure(String(e)) })
    return () => { alive = false }
  }, [sourceId])
  return (
    <div className="overlay" onClick={onClose}>
      <div className="sheet" onClick={(e) => e.stopPropagation()}>
        {failure && <div className="empty">Не удалось открыть источник: {failure}</div>}
        {!failure && (!row || !tpl) && <div className="empty">Загрузка…</div>}
        {row && tpl && (
          <Suspense fallback={<div className="empty">Загрузка…</div>}>
            <LazyObjectEditor
              kind={row.type}
              schemaName={row.schema}
              id={null}
              title={`вариант на основе ${sourceId}`}
              maturity={row.lifecycle}
              template={tpl}
              onSaved={onDone}
              onCancelled={onClose}
            />
          </Suspense>
        )}
      </div>
    </div>
  )
}

function loadRefOptions(key: string, prefixes: string[]): Promise<StoredSummary[]> {
  if (!refCache.has(key)) {
    refCache.set(
      key,
      edit.kinds().then((kinds) => {
        const types = prefixes
          .map((pf) => kinds.find((k) => k.prefix === pf)?.type)
          .filter((t): t is string => Boolean(t))
        // Отменённый объект остаётся в реестре (на него могли ссылаться
        // раньше), но НОВУЮ ссылку на него предлагать нечестно.
        return Promise.all(types.map((t) => edit.list(t)))
          .then((lists) => lists.flat().filter((o) => o.status !== 'Cancelled'))
      }),
    )
  }
  return refCache.get(key)!
}

function RefField(props: FieldProps & { prefixes: string[] }) {
  const { name, schema, value, required, path, errors, onChange, prefixes } = props
  const key = prefixes.join('|')
  const [options, setOptions] = useState<StoredSummary[] | null>(null)
  const [refresh, setRefresh] = useState(0)
  const [variantOf, setVariantOf] = useState<string | null>(null)
  useEffect(() => {
    let alive = true
    loadRefOptions(key, prefixes).then((o) => { if (alive) setOptions(o) }).catch(() => { if (alive) setOptions([]) })
    return () => { alive = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, refresh])
  // Вариантность уместна для входов моделирования (trade studies): другие
  // ссылки — трассировка и распределение, там клон по ссылке — подмена
  // содержательного решения.
  const current = asText(value)
  const cloneable = OBJECT_ID.test(current) &&
    prefixes.includes(current.split('-')[0]) &&
    screenOfObject(current) === 'siminputs'
  return (
    <div className="field">
      <label>
        <FieldName name={name} required={required} />
      </label>
      {/* МВП-П1 §2.2: ввод ссылки — справочником; текстовый ввод ID умер */}
      <RefPicker
        value={current}
        options={(options ?? []).map((o) => ({ id: o.id, title: o.title ?? undefined }))}
        placeholder={options && options.length > 0
          ? 'выбрать из справочника…'
          : `объектов ${prefixes.join('/')} пока нет`}
        clearable={!required}
        onChange={(id) => onChange(id || undefined)}
      />
      {cloneable && (
        <button type="button" className="tab" style={{ alignSelf: 'flex-start' }}
          title={`создать вариант на основе ${current} и сослаться на него`}
          onClick={() => setVariantOf(current)}>
          вариант…
        </button>
      )}
      {variantOf && (
        <RefVariantSheet
          sourceId={variantOf}
          onDone={(newId) => {
            onChange(newId)
            setVariantOf(null)
            setRefresh((n) => n + 1)
          }}
          onClose={() => setVariantOf(null)}
        />
      )}
      {options != null && options.length === 0 && (
        <div className="secondary hint">
          Объектов вида {prefixes.join('/')} в проекте пока нет — ссылаться не на что.
        </div>
      )}
      {schema.description && <div className="secondary hint">{schema.description}</div>}
      <Errors path={path} errors={errors} />
    </div>
  )
}

function Field(props: FieldProps) {
  const { name, schema, value, required, path, errors, onChange } = props
  const { label } = useSession()

  if (schema.enum) {
    const group = ENUM_GROUPS[name]
    return (
      <div className="field">
        <label>
          <FieldName name={name} required={required} />
        </label>
        <select aria-label={name} value={asText(value)} onChange={(e) => onChange(e.target.value || undefined)}>
          <option value="">—</option>
          {schema.enum.map((code) => (
            <option key={code} value={code}>
              {group ? label(group, code) : code}
            </option>
          ))}
        </select>
        <Errors path={path} errors={errors} />
      </div>
    )
  }

  // Константа схемы (например, ephemeris_beacon.enabled: const true — Р5):
  // у неё нет type, и текстовое поле предлагало бы ввести то, что вводу
  // не подлежит. Значение показывается и подставляется в документ как есть.
  if (schema.const !== undefined) {
    // подстановка после рендера: значение принадлежит схеме, не инженеру.
    // Сравнение здесь — со значением СХЕМЫ, а не между величинами модели:
    // без него подстановка зациклила бы рендер.
    const constPending = value !== schema.const // eslint-disable-line eqeqeq
    if (constPending) {
      setTimeout(() => onChange(schema.const), 0)
    }
    return (
      <div className="field">
        <label>
          <FieldName name={name} required={required} />
        </label>
        <span className="mono">{String(schema.const)}</span>
        <Errors path={path} errors={errors} />
      </div>
    )
  }

  if (isQuantity(schema)) return <QuantityField {...props} />

  // МВП-М1 §3: подгруппы построения — таблицей со сводкой (не подформами)
  if (path === '/subgroups') return <SubgroupsField {...props} />

  if (schema.type === 'array') {
    return schema.items?.type === 'object' ? <ObjectArrayField {...props} /> : <StringArrayField {...props} />
  }

  if (schema.type === 'object') {
    return (
      <div className="field">
        <label>
          <FieldName name={name} required={required} />
        </label>
        <div className="subform">
          <Fields
            schema={schema}
            value={(value ?? {}) as Record<string, unknown>}
            path={path}
            errors={errors}
            onChange={onChange}
          />
        </div>
      </div>
    )
  }

  if (schema.type === 'boolean') {
    return (
      <div className="field">
        <label>
          <input
            type="checkbox"
            aria-label={name}
            checked={isChecked(value)}
            onChange={(e) => onChange(e.target.checked)}
          />{' '}
          <FieldName name={name} />
        </label>
        <Errors path={path} errors={errors} />
      </div>
    )
  }

  if (schema.type === 'number' || schema.type === 'integer') {
    return (
      <div className="field">
        <label>
          <FieldName name={name} required={required} />
        </label>
        <input
          type="number"
          step={schema.type === 'integer' ? 1 : 'any'}
          aria-label={name}
          value={asText(value)}
          onChange={(e) => onChange(toNumber(e.target.value))}
        />
        <Errors path={path} errors={errors} />
      </div>
    )
  }

  const refMatch = schema.type === 'string' && schema.pattern ? REF_PATTERN.exec(schema.pattern) : null
  if (refMatch) return <RefField {...props} prefixes={refMatch[1].split('|')} />

  return (
    <div className="field">
      <label>
        <FieldName name={name} required={required} />
      </label>
      {isLongText(name) ? (
        <textarea
          aria-label={name}
          rows={3}
          value={asText(value)}
          onChange={(e) => onChange(e.target.value || undefined)}
        />
      ) : schema.format === 'date' ? (
        /* Дата — компонентом оболочки (reference-date-input): нативные
           date input в продуктовых экранах запрещены (круг 3 §2). */
        <DateInput
          iso={asText(value).slice(0, 10)}
          onChange={(v) => onChange(v || undefined)}
        />
      ) : (
        <input
          aria-label={name}
          value={asText(value)}
          placeholder={schema.pattern ?? undefined}
          onChange={(e) => onChange(e.target.value || undefined)}
        />
      )}
      {schema.description && <div className="secondary hint">{schema.description}</div>}
      <Errors path={path} errors={errors} />
    </div>
  )
}

/** Поля одного уровня документа. Порядок — как в схеме: он осмыслен. */
function Fields({
  schema,
  value,
  path,
  errors,
  top = false,
  onChange,
}: {
  schema: JsonSchema
  value: Record<string, unknown>
  path: string
  errors: Array<{ path: string; message: string }>
  /** Верхний уровень документа: только на нём прячутся служебные поля объекта. */
  top?: boolean
  onChange: (next: Record<string, unknown>) => void
}) {
  const required = new Set(schema.required ?? [])
  const entries = Object.entries(schema.properties ?? {}).filter(([name]) => !hidden(name, top))
  return (
    <>
      {entries.map(([name, sub]) => (
        <Field
          key={name}
          name={name}
          schema={sub}
          required={required.has(name)}
          value={value[name]}
          path={`${path}/${name}`}
          errors={errors}
          onChange={(next) => {
            const copy = { ...value }
            if (next === undefined || (Array.isArray(next) && next.length === 0)) delete copy[name]
            else copy[name] = next
            onChange(copy)
          }}
        />
      ))}
    </>
  )
}

/**
 * Форма объекта. Возвращает документ наружу целиком: что с ним делать —
 * создать или сохранить правкой — решает вызывающий экран.
 */
export function ObjectForm({
  schema,
  value,
  errors,
  onChange,
}: {
  schema: JsonSchema
  value: Record<string, unknown>
  errors: Array<{ path: string; message: string }>
  onChange: (next: Record<string, unknown>) => void
}) {
  return (
    <div className="form">
      <Fields schema={schema} value={value} path="" errors={errors} top onChange={onChange} />
    </div>
  )
}

/** Пустой документ по схеме: обязательные коллекции сразу списками. */
export function emptyDoc(schema: JsonSchema): Record<string, unknown> {
  const doc: Record<string, unknown> = {}
  Object.entries(schema.properties ?? {}).forEach(([name, sub]) => {
    if (hidden(name, true)) return
    if (sub.type === 'array' && (schema.required ?? []).includes(name) && !sub.minItems) doc[name] = []
  })
  return doc
}

/** Поля документа, которые инженер мог изменить: системные не отправляются. */
export function editableFields(doc: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  Object.entries(doc).forEach(([k, v]) => {
    if (!hidden(k, true)) out[k] = v
  })
  return out
}

/** Подсказка о полях, которые заполняет система, — чтобы их не искали в форме. */
export function useSystemFieldsNote(): string {
  return useMemo(
    () => `Идентификатор, статус, версию и происхождение проставляет система: ${[...SYSTEM_FIELDS].join(', ')}`,
    [],
  )
}

/** Состояние формы: документ и ошибки полей от сервера. */
export function useFormState(initial: Record<string, unknown>) {
  const [doc, setDoc] = useState<Record<string, unknown>>(initial)
  const [errors, setErrors] = useState<Array<{ path: string; message: string; rule?: string }>>([])
  return { doc, setDoc, errors, setErrors }
}
