// Панель работы с объектом (шаг 15 §1): создание, правка, отмена, откат,
// базирование — и то, что мешает каждому из них.
//
// Композиция взята из макета `docs/ui/stitch/stakeholder-map-needs`: справа
// от списка — панель определения объекта с полями и действиями. В прежнем
// клиенте эта панель повторяла поля строки таблицы; повторять то, что уже
// видно, ей незачем — она для работы, а не для второго показа.
import { useCallback, useEffect, useState } from 'react'
import {
  edit,
  EditRejected,
  type BaselineIssues,
  type HistoryEntry,
  type JsonSchema,
  type StoredSummary,
} from '../api/edit'
import { ObjectForm, editableFields, emptyDoc, useSystemFieldsNote } from './ObjectForm'
import { useSession } from './session'

import { STATUS_ACTION, STATUS_MEANING, STATUS_ORDER } from './maturity'

interface Props {
  /** Вид объекта в модели: `need`, `service`, `requirement`… */
  kind: string
  /** Имя схемы вида — приходит из /api/kinds, а не пишется в экране. */
  schemaName: string
  /** Открытый объект; null — создание нового. */
  id: string | null
  /** Заголовок панели: чем этот вид называется на экране. */
  title: string
  onSaved: (id: string) => void
  onCancelled?: () => void
}

interface Conflict {
  yourBase: string
  currentVersion: string
  changedBy: string
  theirValues: Record<string, unknown>
}

export function ObjectEditor({ kind, schemaName, id, title, onSaved, onCancelled }: Props) {
  const { author, label } = useSession()
  const [schema, setSchema] = useState<JsonSchema | null>(null)
  const [doc, setDoc] = useState<Record<string, unknown>>({})
  const [version, setVersion] = useState<string | null>(null)
  const [status, setStatus] = useState<string | null>(null)
  const [errors, setErrors] = useState<Array<{ path: string; message: string }>>([])
  const [conflict, setConflict] = useState<Conflict | null>(null)
  const [blocked, setBlocked] = useState<string | null>(null)
  /** Основание изменения базированного объекта (TZ-COM-003). */
  const [changeRef, setChangeRef] = useState('')
  const [failure, setFailure] = useState<string | null>(null)
  const [issues, setIssues] = useState<BaselineIssues | null>(null)
  const [history, setHistory] = useState<HistoryEntry[] | null>(null)
  const [busy, setBusy] = useState(false)
  const systemNote = useSystemFieldsNote()

  useEffect(() => {
    edit.schema(schemaName).then(setSchema).catch((e) => setFailure(String(e)))
  }, [schemaName])

  const clearNotices = () => {
    setErrors([])
    setConflict(null)
    setBlocked(null)
    setFailure(null)
  }

  /** Правка начинается с ХРАНИМОГО документа, а не с копии строки таблицы. */
  const load = useCallback(
    (objectId: string) => {
      clearNotices()
      edit
        .object(objectId)
        .then((stored) => {
          setDoc((stored.doc ?? {}) as Record<string, unknown>)
          setVersion(stored.version)
          setStatus(stored.status)
        })
        .catch((e) => setFailure(String(e)))
      edit.issues(objectId).then(setIssues).catch(() => setIssues(null))
      setHistory(null)
    },
    [],
  )

  useEffect(() => {
    if (id) {
      load(id)
    } else if (schema) {
      clearNotices()
      setDoc(emptyDoc(schema))
      setVersion(null)
      setStatus(null)
      setIssues(null)
      setHistory(null)
    }
  }, [id, schema, load])

  const handleRejection = (e: unknown) => {
    if (e instanceof EditRejected) {
      const c = e.conflict
      if (c) {
        setConflict({
          yourBase: c.your_base,
          currentVersion: c.current_version,
          changedBy: c.changed_by,
          theirValues: c.their_values,
        })
        return
      }
      const b = e.blocked
      if (b) {
        setBlocked(b.reason)
        return
      }
      const fields = e.fieldErrors
      if (fields.length > 0) {
        setErrors(fields)
        return
      }
      setFailure(e.message)
      return
    }
    setFailure(String(e))
  }

  /**
     * Успешное действие обновляет панель СОСТОЯНИЕМ ИЗ ОТВЕТА. Без этого форма
     * оставалась на прежней версии: следующая правка уходила с устаревшим
     * base_version и получала отказ по конфликту — с самим собой.
     */
  const applySaved = (saved: StoredSummary) => {
    if (saved.doc) {
      setDoc(saved.doc as Record<string, unknown>)
      setVersion(saved.version)
      setStatus(saved.status)
      edit.issues(saved.id).then(setIssues).catch(() => setIssues(null))
      setHistory(null)
    } else {
      load(saved.id)
    }
  }

  const run = (action: () => Promise<StoredSummary>) => {
    if (!author.trim()) {
      setFailure('Представьтесь в шапке: изменение записывается с автором')
      return
    }
    clearNotices()
    setBusy(true)
    action()
      .then((saved) => {
        applySaved(saved)
        onSaved(saved.id)
      })
      .catch(handleRejection)
      .finally(() => setBusy(false))
  }

  const save = () =>
    run(() =>
      id && version
        ? edit.update(id, editableFields(doc), version, author)
        : edit.create(kind, editableFields(doc), author),
    )

  if (failure && !schema) return <div className="empty">Схема вида недоступна: {failure}</div>
  if (!schema) return <div className="empty">Загрузка формы…</div>

  return (
    <div className="editor">
      <h2 className="editor__title">
        {id ? (
          <>
            <span className="id">{id}</span>{' '}
            <span className="secondary">
              в. {version} · {label('lifecycle', status)}
            </span>
          </>
        ) : (
          <>Новый объект: {title}</>
        )}
      </h2>

      {/* Лестница зрелости целиком (TZ-COM-003): видно, где объект стоит и
          что пройдено, — прежде у базированного объекта кнопки исчезали
          молча, а разница «утверждён» и «базирован» оставалась догадкой
          (находка живой сессии). Следующая ступень — кнопка; подсказка на
          каждой ступени объясняет, что ступень означает. */}
      {id && status && STATUS_ORDER.includes(status) && (
        <div
          role="group"
          aria-label="зрелость объекта"
          style={{ display: 'flex', alignItems: 'center', gap: 4, flexWrap: 'wrap', margin: '6px 0 10px' }}
        >
          {STATUS_ORDER.map((s, i) => {
            const cur = STATUS_ORDER.indexOf(status)
            const isNext = i === cur + 1
            const blockedBaseline = s === 'Baseline' && issues != null && !issues.can_baseline
            return (
              <span key={s} style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                {i > 0 && <span className="secondary">→</span>}
                {isNext ? (
                  <button
                    type="button"
                    className="tab"
                    disabled={busy || blockedBaseline}
                    onClick={() => run(() => edit.promote(id, s))}
                    title={
                      blockedBaseline
                        ? `Базирование заблокировано: ${issues.issues.join('; ')}`
                        : STATUS_MEANING[s]
                    }
                  >
                    {STATUS_ACTION[s] ?? label('lifecycle', s)}
                  </button>
                ) : (
                  <span
                    className={i <= cur ? 'chip' : 'chip secondary'}
                    title={STATUS_MEANING[s]}
                    style={i === cur ? { fontWeight: 600 } : i > cur ? { opacity: 0.55 } : undefined}
                  >
                    {i < cur ? '✓ ' : ''}
                    {label('lifecycle', s)}
                  </span>
                )}
              </span>
            )
          })}
        </div>
      )}

      {conflict && (
        <div className="notice notice--conflict" role="alert">
          <b>Объект изменён другим инженером.</b>
          <div>
            Ваша правка основана на версии {conflict.yourBase}, текущая — {conflict.currentVersion},
            изменил: {conflict.changedBy}.
          </div>
          <div className="conflict__values">
            {Object.entries(conflict.theirValues).map(([field, value]) => (
              <div key={field} className="field">
                <label>{field} — сейчас в модели</label>
                <span className="mono">{JSON.stringify(value)}</span>
              </div>
            ))}
          </div>
          <button type="button" className="tab" onClick={() => id && load(id)}>
            Перечитать и править заново
          </button>
        </div>
      )}

      {blocked && (
        <div className="notice notice--blocked" role="alert">
          <b>Правка не принята.</b> {blocked}
          {/* Единственный вход изменения базированного объекта — процедура
              с основанием (TZ-COM-003): рабочая правка сюда и отсылает */}
          {id && (
            <div className="field" style={{ marginTop: 6 }}>
              <input
                placeholder="основание (напр. CR-9)"
                value={changeRef}
                onChange={(e) => setChangeRef(e.target.value)}
                style={{ width: 160 }}
              />
              <button
                type="button"
                className="tab"
                disabled={busy || !changeRef.trim()}
                onClick={() =>
                  run(() =>
                    edit.changeWithRef(id, doc as Record<string, unknown>, changeRef.trim()).then((stored) => {
                      setChangeRef('')
                      setBlocked(null)
                      return stored
                    }),
                  )
                }
              >
                Изменить с основанием
              </button>
            </div>
          )}
        </div>
      )}

      {failure && <div className="warn" role="alert">{failure}</div>}

      <ObjectForm schema={schema} value={doc} errors={errors} onChange={setDoc} />
      <p className="secondary hint">{systemNote}</p>

      <div className="editor__actions">
        <button type="button" className="tab tab--primary" disabled={busy} onClick={save}>
          {id ? 'Сохранить правку' : 'Создать'}
        </button>
        {id && (
          <>
            <button
              type="button"
              className="tab"
              disabled={busy}
              onClick={() => run(() => edit.undo(id, author))}
              title="Содержание предыдущей версии станет текущим; история сохраняется"
            >
              Отменить действие
            </button>
            <button
              type="button"
              className="tab"
              disabled={busy}
              onClick={() =>
                run(() =>
                  edit.cancel(id, author, version ?? undefined).then((s) => {
                    onCancelled?.()
                    return s
                  }),
                )
              }
              title="Статус Cancelled: объект остаётся, на него могут ссылаться"
            >
              Отменить объект
            </button>
            {/* Переходы зрелости — лестницей под заголовком: там видно и
                пройденное, и следующий шаг (TZ-COM-003). */}
          </>
        )}
      </div>

      {id && issues && !issues.can_baseline && (
        <div className="card">
          <h3>Что мешает базированию</h3>
          <div>
            {issues.issues.map((issue) => (
              <div key={issue} className="amber">
                △ {issue}
              </div>
            ))}
          </div>
        </div>
      )}

      {id && (
        <div className="card">
          <h3>История версий</h3>
          <div>
            {history === null ? (
              <button type="button" className="tab" onClick={() => edit.history(id).then(setHistory)}>
                Показать историю
              </button>
            ) : (
              <table>
                <thead>
                  <tr>
                    <th style={{ width: 60 }}>Версия</th>
                    <th style={{ width: 110 }}>Статус</th>
                    <th>Автор</th>
                  </tr>
                </thead>
                <tbody>
                  {history.map((h) => (
                    <tr key={`${h.version}-${h.valid_from}`}>
                      <td className="mono">{h.version}</td>
                      <td className="secondary">{label('lifecycle', h.status)}</td>
                      <td>
                        {h.author}
                        {h.current && <span className="chip">текущая</span>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
