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
import { ObjectForm, editableFields, emptyDoc, humanizeError, invalidateRefOptions, useSystemFieldsNote } from './ObjectForm'
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
  /**
   * Применима ли зрелость (лестница статусов). Реестр передаёт признак из
   * /kinds: он учитывает и схему, и планки ворот (риск: lifecycle в схеме
   * нет, но Д6 зреет к MCR). Не задан — по наличию lifecycle в схеме.
   */
  maturity?: boolean
  /** Заготовка создания «на основе»: форма нового объекта открывается с
   *  этим содержимым вместо пустого (вариантность: клон + правка поля). */
  template?: Record<string, unknown> | null
  onSaved: (id: string) => void
  onCancelled?: () => void
}

interface Conflict {
  yourBase: string
  currentVersion: string
  changedBy: string
  theirValues: Record<string, unknown>
}

export function ObjectEditor({ kind, schemaName, id, title, maturity, template, onSaved, onCancelled }: Props) {
  useEffect(() => { invalidateRefOptions() }, [id])
  const { author, label } = useSession()
  const [schema, setSchema] = useState<JsonSchema | null>(null)
  const [doc, setDoc] = useState<Record<string, unknown>>({})
  const [version, setVersion] = useState<string | null>(null)
  const [status, setStatus] = useState<string | null>(null)
  const [errors, setErrors] = useState<Array<{ path: string; message: string; rule?: string }>>([])
  /** Снимок сохранённого содержимого: кнопка сохранения активна только при
   *  расхождении с ним — синяя кнопка без изменений звала сохранять ничего
   *  (находка второго захода). */
  const [savedSnapshot, setSavedSnapshot] = useState<string | null>(null)
  const [conflict, setConflict] = useState<Conflict | null>(null)
  const [blocked, setBlocked] = useState<string | null>(null)
  /** Основание изменения базированного объекта (TZ-COM-003). */
  const [changeRef, setChangeRef] = useState('')
  const [failure, setFailure] = useState<string | null>(null)
  const [issues, setIssues] = useState<BaselineIssues | null>(null)
  const [history, setHistory] = useState<HistoryEntry[] | null>(null)
  const [busy, setBusy] = useState(false)
  /** Вкладки карточки (второй заход): «что мешает» жило в подвале прокрутки —
   *  теперь готовность и история на своих вкладках, препятствия видны сверху. */
  const [tab, setTab] = useState<'form' | 'ready' | 'history'>('form')
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
          setSavedSnapshot(JSON.stringify(editableFields((stored.doc ?? {}) as Record<string, unknown>)))
        })
        .catch((e) => setFailure(String(e)))
      edit.issues(objectId).then(setIssues).catch(() => setIssues(null))
      setHistory(null)
    },
    [],
  )

  useEffect(() => {
    setTab('form')
    if (id) {
      load(id)
    } else if (schema) {
      clearNotices()
      setDoc(template ? { ...template } : emptyDoc(schema))
      setVersion(null)
      setStatus(null)
      setSavedSnapshot(null)
      setIssues(null)
      setHistory(null)
    }
  }, [id, schema, load, template])

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
  /** Правка вернула объект в черновик — сказать, а не промолчать. */
  const [demoted, setDemoted] = useState<string | null>(null)

  const applySaved = (saved: StoredSummary) => {
    // Сброс статуса правкой — закон (TZ-COM-003: изменённое содержание
    // проходит утверждение заново), но МОЛЧАЛИВЫЙ сброс — ловушка: инженер
    // базировал, поправил и не понимал, почему объект снова в незакрытых
    // «Готовности» (находка второго захода).
    if (status && status !== 'Draft' && saved.status === 'Draft') {
      setDemoted(status)
    }
    if (saved.doc) {
      setDoc(saved.doc as Record<string, unknown>)
      setVersion(saved.version)
      setStatus(saved.status)
      setSavedSnapshot(JSON.stringify(editableFields(saved.doc as Record<string, unknown>)))
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
    setDemoted(null)
    clearNotices()
    setBusy(true)
    action()
      .then((saved) => {
        // Списки-ссылки других форм обязаны увидеть записанное: без сброса
        // новый объект не появлялся в выпадающих, пока жила страница.
        invalidateRefOptions()
        applySaved(saved)
        onSaved(saved.id)
      })
      .catch(handleRejection)
      .finally(() => setBusy(false))
  }

  useEffect(() => {
    if (tab === 'history' && id && history === null) {
      edit.history(id).then(setHistory).catch(() => setHistory([]))
    }
  }, [tab, id, history])

  const save = () =>
    run(() =>
      id && version
        ? edit.update(id, editableFields(doc), version, author)
        : edit.create(kind, editableFields(doc), author),
    )

  if (failure && !schema) return <div className="empty">Схема вида недоступна: {failure}</div>
  if (!schema) return <div className="empty">Загрузка формы…</div>

  const hasMaturity = maturity ?? Boolean(schema.properties?.lifecycle)
  // Есть ли что сохранять: для существующего объекта — расхождение формы со
  // снимком сохранённого; «изменил и вернул как было» честно гасит кнопку.
  const dirty = !id || savedSnapshot == null ||
    JSON.stringify(editableFields(doc)) !== savedSnapshot

  return (
    <div className="editor">
      <h2 className="editor__title">
        {id ? (
          <>
            <span className="id">{id}</span>{' '}
            <span className="secondary">
              в. {version}
              {hasMaturity && status ? <> · {label('lifecycle', status)}</> : null}
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
      {id && status && STATUS_ORDER.includes(status) && hasMaturity && (
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

      {/* Вкладки карточки: определение · готовность (с числом препятствий) ·
          история. Прежде «Что мешает базированию» лежало в подвале прокрутки
          (находка второго захода: «подсказка внизу — очень неудобно»). */}
      {id && (
        <div className="tabs" style={{ margin: '2px 0 8px' }}>
          <button type="button" className="tab" aria-selected={tab === 'form'}
            onClick={() => setTab('form')}>
            Определение
          </button>
          <button type="button" className="tab" aria-selected={tab === 'ready'}
            onClick={() => setTab('ready')}>
            Готовность{issues && !issues.can_baseline ? ` · △${issues.issues.length}` : ''}
          </button>
          <button type="button" className="tab" aria-selected={tab === 'history'}
            onClick={() => setTab('history')}>
            История
          </button>
        </div>
      )}

      {/* Препятствия — на виду и на вкладке «Определение»: одной строкой */}
      {id && tab === 'form' && issues && !issues.can_baseline && (
        <button type="button" className="notice notice--blocked"
          style={{ display: 'block', width: '100%', textAlign: 'left', cursor: 'pointer', border: 0, marginBottom: 8 }}
          title="открыть вкладку «Готовность»"
          onClick={() => setTab('ready')}>
          △ Базированию мешает: {issues.issues.length} — открыть «Готовность» →
        </button>
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
      {/* Сводка ВСЕХ замечаний сервера — страховка от молчаливого отказа:
          ошибка с путём, не совпавшим ни с одним полем (элемент массива,
          переименованное поле), терялась, и «Сохранить правку» выглядела
          сломанной без объяснений (находка второго захода). Полевые
          подсветки остаются точечной наводкой, эта сводка — гарантией. */}
      {errors.length > 0 && (
        <div className="notice notice--blocked" role="alert">
          <b>Правка не принята — замечаний: {errors.length}.</b>
          <ul style={{ margin: '4px 0 0 16px' }}>
            {errors.map((e, i) => (
              <li key={i}>
                {e.path && <span className="mono">{e.path} — </span>}
                {humanizeError(e)}
              </li>
            ))}
          </ul>
        </div>
      )}

      {(!id || tab === 'form') && (
      <>
      {demoted && (
        <div className="notice notice--blocked" role="alert">
          Правка вернула объект в <b>черновик</b> (был «{label('lifecycle', demoted)}»):
          изменённое содержание проходит утверждение заново — лестница сверху
          (TZ-COM-003). Если объект держал контрольную точку, он снова в
          незакрытых «Готовности».
        </div>
      )}
      {/* Действия — СВЕРХУ формы (находка второго захода: «сохранить — тоже
          вверх»): длинная форма прокручивается, кнопка — нет */}
      <div className="editor__actions" style={{ marginBottom: 8 }}>
        <button type="button" className="tab tab--primary" disabled={busy || !dirty} onClick={save}
          title={dirty ? '' : 'изменений нет — сохранять нечего'}>
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

      <ObjectForm schema={schema} value={doc} errors={errors} onChange={setDoc} />
      <p className="secondary hint">{systemNote}</p>
      </>
      )}

      {id && tab === 'ready' && (
        <div className="card">
          <h3>Что мешает базированию</h3>
          <div>
            {issues == null || issues.can_baseline ? (
              <div className="secondary">Препятствий базированию нет.</div>
            ) : (
              issues.issues.map((issue) => {
                const waivable = issues.waivable?.includes(issue) ?? false
                return (
                  <div key={issue} className="amber"
                    style={{ display: 'flex', gap: 8, alignItems: 'baseline' }}>
                    <span style={{ flex: 1 }}>△ {issue}</span>
                    {/* Правила качества — эвристики, и они ошибаются
                        (находка прогона: «в пределах» по смыслу ровно).
                        Инженер вправе отвести правило С ОБОСНОВАНИЕМ:
                        след остаётся в объекте, решение — за человеком.
                        TBD и план верификации не отводимы. */}
                    {waivable && (
                      <button type="button" className="tab"
                        title="отвести правило с обоснованием: замечание перестанет блокировать этот объект"
                        onClick={() => {
                          const rationale = window.prompt(
                            `Отвести правило:\n«${issue}»\n\nОбоснование (почему здесь оно неприменимо, не короче 10 символов):`,
                          )?.trim()
                          if (!rationale) return
                          if (rationale.length < 10) {
                            setFailure('Обоснование отвода короче 10 символов — так не принимается.')
                            return
                          }
                          const cur = Array.isArray(doc.quality_waivers)
                            ? (doc.quality_waivers as Array<{ rule: string; rationale: string }>)
                            : []
                          setDoc({ ...doc, quality_waivers: [...cur, { rule: issue, rationale }] })
                          setTab('form')
                        }}>
                        Отвести…
                      </button>
                    )}
                  </div>
                )
              })
            )}
            {issues && (issues.waived?.length ?? 0) > 0 && (
              <div style={{ marginTop: 8 }}>
                {issues.waived!.map((w) => (
                  <div key={w.rule} className="secondary">
                    ✓ отведено: «{w.rule}» — {w.rationale}
                  </div>
                ))}
              </div>
            )}
            {/* Помощник распределения (находка второго захода): интерфейсы
                появились позже требований, и искать «какой интерфейс связан
                с этим элементом» инженер вынужден был руками по реестрам —
                хотя стороны интерфейсов системе известны. Кнопка подставляет
                распределение в форму; решает и сохраняет инженер. */}
            {kind === 'requirement' && issues && !issues.can_baseline &&
              issues.issues.some((i) => i.includes('интерфейс')) && (
                <InterfaceAllocationHelper doc={doc}
                  onApply={(next) => { setDoc(next); setTab('form') }} />
              )}
          </div>
        </div>
      )}

      {id && tab === 'history' && (
        <div className="card">
          <h3>История версий</h3>
          <div>
            {history === null ? (
              <div className="secondary">Загрузка…</div>
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

/** Запись распределения требования (allocated_to). */
interface AllocationEntry {
  component?: string
  interface?: string
  kind?: string
  rationale?: string
}

/**
 * Помощник распределения интерфейсного требования: показывает интерфейсы
 * проекта со сторонами и подставляет выбранный в allocated_to. Кандидаты,
 * чьи стороны пересекаются с текущими элементами распределения, идут
 * первыми — «какой интерфейс связан с этим элементом» знает система, а не
 * инженер по памяти. Подстановка НЕ сохраняет: инженер видит результат в
 * форме и сохраняет сам.
 */
function InterfaceAllocationHelper({ doc, onApply }: {
  doc: Record<string, unknown>
  onApply: (next: Record<string, unknown>) => void
}) {
  const [ifaces, setIfaces] = useState<Array<{ id: string; name: string; owners: string[] }> | null>(null)
  const [applied, setApplied] = useState<string | null>(null)

  useEffect(() => {
    let alive = true
    edit.list('interface')
      .then((rows) => Promise.all(rows.map((r) =>
        edit.object(r.id).then((o) => {
          const d = o.doc as Record<string, unknown>
          return {
            id: r.id,
            name: String(d.name ?? r.title ?? ''),
            owners: Array.isArray(d.owners) ? (d.owners as string[]) : [],
          }
        }),
      )))
      .then((full) => { if (alive) setIfaces(full) })
      .catch(() => { if (alive) setIfaces([]) })
    return () => { alive = false }
  }, [])

  if (ifaces == null) return <div className="secondary">Ищу интерфейсы проекта…</div>
  if (ifaces.length === 0) {
    return (
      <div className="secondary" style={{ marginTop: 6 }}>
        Интерфейсов в проекте нет — заведите стык в реестре «Элементы и
        интерфейсы» (у интерфейса две стороны-элемента), затем распределите
        требование на него.
      </div>
    )
  }

  const current = Array.isArray(doc.allocated_to) ? (doc.allocated_to as AllocationEntry[]) : []
  const components = new Set(current.map((a) => a.component).filter(Boolean) as string[])
  // связанные с текущими элементами — первыми
  const ranked = [...ifaces].sort((a, b) => {
    const ra = a.owners.some((o) => components.has(o)) ? 0 : 1
    const rb = b.owners.some((o) => components.has(o)) ? 0 : 1
    return ra - rb || a.id.localeCompare(b.id)
  })

  const apply = (iface: { id: string; owners: string[] }) => {
    const covered = new Set(iface.owners)
    const next: AllocationEntry[] = []
    let replaced = false
    for (const a of current) {
      if (a.component && covered.has(a.component)) {
        // сторона выбранного интерфейса: запись становится интерфейсной
        if (!replaced) {
          const entry: AllocationEntry = { interface: iface.id, kind: a.kind ?? 'full' }
          if (a.rationale) entry.rationale = a.rationale
          next.push(entry)
          replaced = true
        }
      } else if (a.interface === iface.id) {
        if (!replaced) { next.push(a); replaced = true }
      } else {
        next.push(a)
      }
    }
    if (!replaced) next.push({ interface: iface.id, kind: 'full' })
    onApply({ ...doc, allocated_to: next })
    setApplied(iface.id)
  }

  return (
    <div style={{ marginTop: 8 }}>
      <div className="secondary" style={{ marginBottom: 4 }}>
        Распределить на интерфейс{components.size > 0 ? ' (связанные с текущими элементами — первыми)' : ''}:
      </div>
      {ranked.map((f) => {
        const related = f.owners.some((o) => components.has(o))
        return (
          <div key={f.id} style={{ margin: '3px 0' }}>
            <button type="button" className="tab" onClick={() => apply(f)}
              title="подставить в форму — сохранение остаётся за вами">
              → <span className="mono">{f.id}</span> «{f.name}»
              <span className="secondary"> ({f.owners.join(' ↔ ')})</span>
              {related && <b> · связан</b>}
            </button>
          </div>
        )
      })}
      {applied && (
        <div className="notice" style={{ marginTop: 4 }}>
          Подставлено распределение на <span className="mono">{applied}</span> —
          проверьте форму и нажмите «Сохранить правку».
        </div>
      )}
    </div>
  )
}
