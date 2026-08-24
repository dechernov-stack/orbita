// Рабочий слой клиента (шаг 15 §1): создание, правка, отмена, история, откат.
//
// Автор идёт ТЕЛОМ запроса, а не заголовком: значение заголовка HTTP обязано
// быть ASCII, а имена инженеров русские — на «инженер А» запрос не собирается
// вовсе. Автор и по смыслу часть изменения, а не сведения о транспорте.
import { ApiError } from './client'
import { withProject } from './project'

const BASE = '/api'

/** Отказ по расхождению версий: то, чем инженер конфликт и разрешает. */
export interface EditConflict {
  conflict: true
  your_base: string
  current_version: string
  changed_by: string
  their_values: Record<string, unknown>
  error: string
}

/** Отказ правки базированного объекта: причина адресована инженеру. */
export interface EditBlocked {
  blocked: true
  reason: string
}

export interface StoredSummary {
  id: string
  type: string
  version: string
  status: string
  /** Содержательная подпись объекта; выбирает сервер, а не клиент по именам полей. */
  title?: string
  doc?: Record<string, unknown>
}

export interface HistoryEntry {
  version: string
  status: string
  author: string
  valid_from: string
  valid_to: string | null
  current: boolean
}

export interface BaselineIssues {
  can_baseline: boolean
  issues: string[]
}

export interface KindRow {
  type: string
  prefix: string
  schema: string
  /** false — вид живёт собственным циклом (open/closed): зрелость неприменима. */
  lifecycle: boolean
}

/** Схема вида с раскрытыми ссылками: по ней строится форма. */
export type JsonSchema = {
  type?: string
  /** Константа схемы: значение принадлежит схеме, не инженеру. */
  const?: unknown
  title?: string
  description?: string
  required?: string[]
  properties?: Record<string, JsonSchema>
  items?: JsonSchema
  enum?: string[]
  minimum?: number
  maximum?: number
  minLength?: number
  minItems?: number
  pattern?: string
  /** format: date — форма даёт календарь вместо свободного текста. */
  format?: string
  /** Примеры значений — форма подставляет их подсказкой (например, единицы СИ). */
  examples?: string[]
  default?: unknown
  additionalProperties?: unknown
}

/**
 * Отказ разбирается на понятный инженеру исход. Конфликт и блокировка — не
 * «ошибка сети», а рабочие состояния: их показывают в форме, а не в консоли.
 */
export class EditRejected extends Error {
  constructor(
    readonly status: number,
    readonly payload: Record<string, unknown>,
  ) {
    super(String(payload.error ?? payload.reason ?? `отказ ${status}`))
  }

  get conflict(): EditConflict | null {
    return this.payload.conflict ? (this.payload as unknown as EditConflict) : null
  }

  get blocked(): EditBlocked | null {
    return this.payload.blocked ? (this.payload as unknown as EditBlocked) : null
  }

  /** Ошибки схемы: путь до поля и правило — форма показывает их у полей. */
  get fieldErrors(): Array<{ path: string; message: string; rule?: string }> {
    const errors = this.payload.errors
    if (!Array.isArray(errors)) return []
    return errors.map((e) => ({
      path: String((e as Record<string, unknown>).path ?? ''),
      message: String((e as Record<string, unknown>).message ?? ''),
      rule: String((e as Record<string, unknown>).rule ?? '') || undefined,
    }))
  }
}

async function send<T>(method: string, path: string, body?: unknown): Promise<T> {
  const response = await fetch(`${BASE}${withProject(path)}`, {
    method,
    headers: { 'Content-Type': 'application/json; charset=utf-8', Accept: 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await response.text()
  if (!response.ok) {
    let payload: Record<string, unknown> | null = null
    try {
      payload = JSON.parse(text) as Record<string, unknown>
    } catch {
      payload = null
    }
    if (payload) throw new EditRejected(response.status, payload)
    throw new ApiError(response.status, path, text)
  }
  return (text ? JSON.parse(text) : null) as T
}

export const edit = {
  kinds: () => send<KindRow[]>('GET', '/kinds'),

  /** Объекты одного вида с подписями — список экрана. */
  list: (type: string) => send<StoredSummary[]>('GET', `/objects?type=${encodeURIComponent(type)}`),
  schema: (name: string) => send<JsonSchema>('GET', `/schemas/${name}`),

  create: (type: string, doc: Record<string, unknown>, author: string) =>
    send<StoredSummary>('POST', `/edit/${type}`, { author, doc }),

  update: (id: string, changes: Record<string, unknown>, baseVersion: string, author: string) =>
    send<StoredSummary>('PATCH', `/edit/${id}`, { author, base_version: baseVersion, changes }),

  cancel: (id: string, author: string, baseVersion?: string) =>
    send<StoredSummary>('POST', `/edit/${id}/cancel`, { author, base_version: baseVersion }),

  undo: (id: string, author: string) => send<StoredSummary>('POST', `/edit/${id}/undo`, { author }),

  history: (id: string) => send<HistoryEntry[]>('GET', `/edit/${id}/history`),

  issues: (id: string) => send<BaselineIssues>('GET', `/edit/${id}/issues`),

  /** Текущий документ объекта — правка начинается с него, а не с копии строки. */
  object: (id: string) => send<StoredSummary>('GET', `/objects/${id}`),
  /**
   * Процедура изменения базированного объекта (TZ-COM-003): рабочая правка
   * его не трогает — принимается только изменение с основанием (change_ref).
   */
  changeWithRef: (id: string, doc: Record<string, unknown>, changeRef: string) =>
    send<StoredSummary>('POST', `/objects/${id}/change`, { doc, change_ref: changeRef }),
  /** Обходы трассировки за один запрос (TZ-REQ-003). */
  ancestors: (id: string) => send<Array<{ id: string; depth: number }>>('GET', `/objects/${id}/ancestors`),
  descendants: (id: string) =>
    send<Array<{ id: string; depth: number }>>('GET', `/objects/${id}/descendants`),
  /**
   * Привязка события верификации к требованию (единственный оставшийся вид
   * ручной связи): trace/allocation/derive выводятся из документа (ADR-027).
   */
  addVerificationLink: (from: string, to: string) =>
    send<{ status: string }>('POST', '/links', { from, to, kind: 'verification' }),
  /** Действующие параметры объекта — неакцептованное ИИ отфильтровано хранилищем. */
  listParams: (id: string) =>
    send<Array<{ name: string; unit: string; value?: number; formula?: string; source: string; is_tpm: boolean }>>(
      'GET',
      `/objects/${id}/params`,
    ),
  /** Параметр объекта: значение, единица, происхождение, формула (TZ-MOD-005). */
  putParam: (id: string, name: string, value: number | null, unit: string, formula?: string) =>
    send<void>('POST', `/objects/${id}/params/${encodeURIComponent(name)}`, {
      value,
      unit,
      provenance: { source: 'manual' },
      formula: formula || undefined,
    }),
  /** Зависимость параметра от параметра — вход каскада stale (TZ-MOD-005). */
  addParamDependency: (id: string, name: string, depId: string, depName: string) =>
    send<{ status: string }>('POST', '/param-deps', {
      object_id: id,
      name,
      dep_object_id: depId,
      dep_name: depName,
    }),

  promote: (id: string, status: string) =>
    send<StoredSummary>('POST', `/objects/${id}/promote`, { status }),

  enumLabels: () => send<Record<string, Record<string, string>>>('GET', '/enum-labels'),

  /** Подписи имён полей форм (блок D, §3.6) — одной таблицей с сервера. */
  fieldLabels: () => send<Record<string, string>>('GET', '/field-labels'),
}
