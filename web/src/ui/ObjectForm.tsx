// Форма ввода объекта, построенная ПО СХЕМЕ вида (шаг 15 §2).
//
// Перечень полей, обязательность и допустимые значения приходят из схемы,
// а не переписываются сюда руками: список полей в клиенте разошёлся бы
// со схемой молча, а схема — нормативная структура данных, а не подсказка.
//
// Расчётов здесь нет и быть не может (STEP-6 §3.2): форма собирает документ
// и отдаёт его серверу, проверяет — сервер теми же правилами, что и импорт.
import { useMemo, useState } from 'react'
import type { JsonSchema } from '../api/edit'
import { useSession } from './session'

/** Значение любого поля документа: форма не типизирует модель за схему. */
type Value = unknown

interface FieldProps {
  name: string
  schema: JsonSchema
  value: Value
  required: boolean
  path: string
  errors: Array<{ path: string; message: string }>
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

function Errors({ path, errors }: { path: string; errors: Array<{ path: string; message: string }> }) {
  const mine = errors.filter((e) => e.path === path)
  if (mine.length === 0) return null
  return (
    <div className="warn" role="alert">
      {mine.map((e) => e.message).join('; ')}
    </div>
  )
}

/** Величина: число и единица рядом. Происхождение проставит сервер. */
function QuantityField({ name, schema, value, required, path, errors, onChange }: FieldProps) {
  const current = (value ?? {}) as Record<string, unknown>
  const units = schema.properties?.unit?.examples
  return (
    <div className="field">
      <label>
        {name}
        {required && <span className="req"> *</span>}
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

/** Массив объектов: подформа на каждый элемент, тоже по схеме. */
function ObjectArrayField({ name, schema, value, required, path, errors, onChange }: FieldProps) {
  const items = Array.isArray(value) ? (value as Record<string, unknown>[]) : []
  const itemSchema = schema.items ?? {}
  return (
    <div className="field">
      <label>
        {name}
        {required && <span className="req"> *</span>}
        <span className="secondary"> · {items.length}</span>
      </label>
      {items.map((item, i) => (
        <div key={i} className="subform">
          <div className="subform__head">
            <span className="secondary">
              {name} [{i + 1}]
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
        + {name}
      </button>
      <Errors path={path} errors={errors} />
    </div>
  )
}

/** Массив строк: значения через запятую — ссылки на объекты вводятся так же. */
function StringArrayField({ name, value, required, path, errors, onChange }: FieldProps) {
  const items = Array.isArray(value) ? (value as string[]) : []
  return (
    <div className="field">
      <label>
        {name}
        {required && <span className="req"> *</span>}
      </label>
      <input
        aria-label={name}
        value={items.join(', ')}
        placeholder="через запятую"
        onChange={(e) =>
          onChange(
            e.target.value
              .split(',')
              .map((s) => s.trim())
              .filter(Boolean),
          )
        }
      />
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
          {name}
          {required && <span className="req"> *</span>}
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
          {name}
          {required && <span className="req"> *</span>}
        </label>
        <span className="mono">{String(schema.const)}</span>
        <Errors path={path} errors={errors} />
      </div>
    )
  }

  if (isQuantity(schema)) return <QuantityField {...props} />

  if (schema.type === 'array') {
    return schema.items?.type === 'object' ? <ObjectArrayField {...props} /> : <StringArrayField {...props} />
  }

  if (schema.type === 'object') {
    return (
      <div className="field">
        <label>
          {name}
          {required && <span className="req"> *</span>}
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
          {name}
        </label>
        <Errors path={path} errors={errors} />
      </div>
    )
  }

  if (schema.type === 'number' || schema.type === 'integer') {
    return (
      <div className="field">
        <label>
          {name}
          {required && <span className="req"> *</span>}
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

  return (
    <div className="field">
      <label>
        {name}
        {required && <span className="req"> *</span>}
      </label>
      {isLongText(name) ? (
        <textarea
          aria-label={name}
          rows={3}
          value={asText(value)}
          onChange={(e) => onChange(e.target.value || undefined)}
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
  const [errors, setErrors] = useState<Array<{ path: string; message: string }>>([])
  return { doc, setDoc, errors, setErrors }
}
