// Шаблон «Реестр» (блок D, дизайн §3.2): одна таблица объектов вида с
// отбором, счётчиком и главным действием; справа инспектор — панель работы
// с выбранным объектом (§3.3). На него сажаются все виды без собственного
// расчётного экрана. Правила формы — серверные (ObjectEditor, шаг 15).
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api, asBatchReport, type BatchReport } from '../api/client'
import { requestObject, takeObject } from '../api/intent'
import { STATUS_MEANING } from '../ui/maturity'
import { edit, type KindRow, type StoredSummary } from '../api/edit'
import { withProject } from '../api/project'
import { ObjectEditor } from '../ui/ObjectEditor'
import { SortTh, useSort } from '../ui/sort'
import { basedOnTemplate } from '../ui/ObjectForm'
import { useSession } from '../ui/session'

/** Подписи видов на экране; сервер отдаёт состав видов, имена — словарь экрана. */
const KIND_TITLES: Record<string, string> = {
  mission_goal: 'Цели миссии',
  need: 'Нужды',
  service: 'Сервисы',
  requirement: 'Требования',
  conops: 'Сценарии ConOps',
  function: 'Функции',
  alternative: 'Альтернативы',
  decision: 'Решения',
  wbs_element: 'Элементы WBS',
  cost_estimate: 'Оценки стоимости',
  oda: 'Оценки засорения',
  review_item: 'Замечания обзора',
  risk: 'Риски',
  technology: 'Технологии',
  evidence: 'Свидетельства',
  validation: 'Валидации',
  document_issue: 'Выпуски документов',
  component: 'Элементы',
  interface: 'Интерфейсы',
  project: 'Проекты',
  ai_profile: 'Профили службы ИИ',
  source_document: 'Исходные документы',
  scenario: 'Сценарии',
  constellation: 'Группировка',
  spacecraft: 'Модель КА',
  demand_map: 'Карта спроса',
  terminal_profile: 'Профили терминалов',
  ground_stations: 'Наземные станции',
  protocol_adapter: 'Адаптер протокола',
}

const kindTitle = (k: string) => KIND_TITLES[k] ?? k

export function KindRegistry({ kinds, title, expandDown }: {
  kinds: string[]
  title: string
  /** Раскрытие редактора ВНИЗ под строкой — как в реестре требований
   * (замечание прохода МВП по формам постановки); без флага — прежний
   * боковой инспектор. */
  expandDown?: boolean
}) {
  const { label, author } = useSession()
  const [massStatus, setMassStatus] = useState('Preliminary')
  const [massReport, setMassReport] = useState<string | null>(null)
  const [massBusy, setMassBusy] = useState(false)
  /** Загрузка пачкой прямо в реестре (§3.2 дизайна): вид материала приходит
   *  файлом, и уходить за ним на отдельный экран инженеру незачем. */
  const [batch, setBatch] = useState<BatchReport | null>(null)
  const [available, setAvailable] = useState<KindRow[] | null>(null)
  const [kind, setKind] = useState<string>(kinds[0])
  const [rows, setRows] = useState<StoredSummary[] | null>(null)
  const [selected, setSelected] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  /** Заготовка «на основе» выбранного: вариантность без перепечатки полей. */
  const [template, setTemplate] = useState<Record<string, unknown> | null>(null)
  const [filter, setFilter] = useState('')
  /** Б-02 реестра блокеров: мягкая отмена по выбранному — с подтверждением
   * числом; жёсткого удаления нет, история неприкосновенна. */
  const [picked, setPicked] = useState<Set<string>>(new Set())
  const [error, setError] = useState<string | null>(null)
  /** Счётчик перебора табов при поиске объекта из намерения. */
  const seekRef = useRef(0)

  useEffect(() => {
    edit.kinds().then(setAvailable).catch((e) => setError(String(e)))
  }, [])

  const meta = useMemo(() => available?.find((k) => k.type === kind), [available, kind])

  const reload = useCallback(() => {
    edit.list(kind).then((next) => { setRows(next); setError(null) }).catch((e) => setError(String(e)))
  }, [kind])

  useEffect(() => {
    setRows(null)
    setSelected(null)
    setCreating(false)
    setPicked(new Set())
    reload()
  }, [reload])

  // Переход «к объекту» с другого экрана (разрыв документа, строка
  // готовности): как только строки вида загрузились и объект среди них —
  // он открывается в инспекторе. В реестре нескольких видов объект ищется
  // по табам (не больше одного круга — иначе чужое намерение зациклило бы
  // переключение), ненайденное намерение возвращается на место: его
  // заберёт правильный экран.
  useEffect(() => {
    if (!rows) return
    const wanted = takeObject()
    if (!wanted) return
    if (rows.some((r) => r.id === wanted)) {
      setSelected(wanted)
      seekRef.current = 0
      return
    }
    if (kinds.length > 1 && seekRef.current < kinds.length - 1) {
      seekRef.current += 1
      requestObject(wanted)
      setKind(kinds[(kinds.indexOf(kind) + 1) % kinds.length])
      return
    }
    seekRef.current = 0
    requestObject(wanted)
  }, [rows, kind, kinds])

  // до ранних return — хукам нужен стабильный порядок (React)
  const visible = (rows ?? []).filter(
    (r) => !filter || r.id.toLowerCase().includes(filter.toLowerCase()) ||
      (r.title ?? '').toLowerCase().includes(filter.toLowerCase()),
  )
  // П-Б: сортировка заголовком — клиентская по загруженному
  const { sorted, sort, toggle } = useSort(visible, {
    id: (r) => r.id,
    title: (r) => r.title ?? '',
    status: (r) => r.status ?? '',
    version: (r) => Number(r.version) || 0,
  })

  if (error) return <div className="empty">Ошибка обращения к API: {error}</div>
  if (!available || !meta) return <div className="empty">Загрузка…</div>

  const open = creating || selected != null
  const colCount = meta.lifecycle ? 5 : 3

  /** Редактор один — и для инспектора, и для раскрытия вниз. */
  const editor = (
    <ObjectEditor
      kind={kind}
      schemaName={meta.schema}
      id={creating ? null : selected}
      title={kindTitle(kind)}
      maturity={meta.lifecycle}
      template={creating ? template : null}
      onSaved={(id) => { setCreating(false); setSelected(id); reload() }}
      onCancelled={() => { setCreating(false); setSelected(null); reload() }}
    />
  )
  /** К отмене годно только живое: Cancelled второй раз не отменяется. */
  const pickedAlive = visible.filter((r) => picked.has(r.id) && r.status !== 'Cancelled')

  const cancelPicked = async () => {
    if (pickedAlive.length === 0 || !author || massBusy) return
    const ok = window.confirm(
      `Отменить объектов: ${pickedAlive.length} (${pickedAlive.slice(0, 5).map((r) => r.id).join(', ')}` +
      `${pickedAlive.length > 5 ? ', …' : ''})?\n\nОтмена мягкая: объект остаётся в истории со статусом ` +
      '«отменён», трассировки на него честно покажут разрыв. Жёсткого удаления нет.',
    )
    if (!ok) return
    setMassBusy(true)
    setMassReport(null)
    const failed: string[] = []
    let done = 0
    for (const r of pickedAlive) {
      try {
        await edit.cancel(r.id, author)
        done += 1
      } catch (e) {
        failed.push(`${r.id}: ${String((e as Error).message ?? e).slice(0, 80)}`)
      }
    }
    setMassReport(`отменено ${done}${failed.length ? `; отказов ${failed.length} — ${failed.slice(0, 3).join('; ')}` : ''}`)
    setPicked(new Set())
    setMassBusy(false)
    reload()
  }

  return (
    <>
      <div className="toolbar">
        <h2>{title}</h2>
        {kinds.length > 1 && (
          <div className="tabs">
            {kinds.map((k) => (
              <button key={k} className="tab" aria-selected={k === kind} onClick={() => setKind(k)}>
                {kindTitle(k)}
              </button>
            ))}
          </div>
        )}
        <span className="count">{rows ? visible.length : '…'}</span>
        <input
          placeholder="отбор по id и подписи"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          style={{ width: 200 }}
        />
        <div className="grow" />
        {/* Массовое действие реестра (§3.2): перевод статуса пачкой.
            Вид без статусной модели (замечание обзора, риск) живёт своим
            циклом — зрелость к нему неприменима, кнопки нет (находка
            второго захода: замечание обзора «базировалось»). */}
        {meta.lifecycle && (rows?.length ?? 0) > 0 && (
          <>
            <span className="secondary">статус:</span>
            <select value={massStatus} onChange={(e) => setMassStatus(e.target.value)}
              title={STATUS_MEANING[massStatus]}>
              {['Preliminary', 'Approved', 'Baseline'].map((st) => (
                <option key={st} value={st} title={STATUS_MEANING[st]}>{label('lifecycle', st)}</option>
              ))}
            </select>
            <button className="btn" disabled={!author || massBusy}
              title={author ? 'перевести все видимые ниже выбранного статуса' : 'представьтесь в шапке'}
              onClick={() => {
                const ids = visible.filter((r) => r.status !== massStatus && r.status !== 'Cancelled').map((r) => r.id)
                if (ids.length === 0) { setMassReport('переводить нечего'); return }
                setMassBusy(true)
                api.promoteBatch(ids, massStatus, author)
                  .then((r) => {
                    setMassReport(`переведено ${r.promoted.length}; отказов ${r.failed.length}` +
                      (r.failed.length ? ` — ${r.failed.slice(0, 3).map((f) => `${f.id}: ${f.reason}`).join('; ')}` : ''))
                    reload()
                  })
                  .catch((e) => setMassReport(String(e)))
                  .finally(() => setMassBusy(false))
              }}>
              {massBusy ? 'Перевод…' : 'Перевести все видимые'}
            </button>
            {pickedAlive.length > 0 && (
              <button className="btn" disabled={!author || massBusy}
                title="мягкая отмена выбранных: история сохраняется, ссылки покажут разрыв"
                onClick={cancelPicked}>
                Отменить выбранные · {pickedAlive.length}
              </button>
            )}
            {massReport && <span className="secondary">{massReport}</span>}
          </>
        )}
        <a className="btn" href={withProject(api.exportObjectsUrl())} download="orbita-export.json"
          title="выгрузить проект целиком тем же форматом, каким грузится пачка">
          Выгрузить
        </a>
        <label className="btn" title="загрузить пачкой: проверка по схемам до записи, всё или ничего"
          style={massBusy ? { opacity: 0.5, pointerEvents: 'none' } : undefined}>
          {massBusy ? 'Загрузка…' : 'Загрузить пачкой'}
          <input type="file" accept="application/json,.json" style={{ display: 'none' }} disabled={massBusy}
            onChange={(e) => {
              const file = e.target.files?.[0]
              e.target.value = ''
              if (!file) return
              setBatch(null)
              setMassReport(null)
              setMassBusy(true)
              file.text()
                .then((text) => {
                  const parsed = JSON.parse(text) as unknown
                  // Формат канала ИИ — МАССИВ предложений: он вносится службой,
                  // а не загрузкой пачкой. Иначе автор терялся (у массива
                  // свойства не сериализуются), и сервер отвечал «нет автора» —
                  // сообщение о последствии вместо причины.
                  if (Array.isArray(parsed)) {
                    throw new Error(
                      'Это ответ канала ИИ (массив предложений), а не пачка. ' +
                        'Вносите его на экране «Инструменты → Служба ИИ»: соберите промпт, ' +
                        'вставьте файл в поле ответа контура и примите пачкой — так материал ' +
                        'пройдёт фильтр и попадёт в журнал вызовов.',
                    )
                  }
                  const body = parsed as Record<string, unknown>
                  if (!Array.isArray(body.objects)) {
                    throw new Error('В файле нет поля objects: пачка — это {"objects": [ … ]}.')
                  }
                  if (!body.author && !author) {
                    throw new Error('Представьтесь в шапке: правка без автора не принимается (TZ-COM-005).')
                  }
                  if (!body.author) body.author = author
                  return api.importObjects(body)
                })
                .then((r) => { setBatch(r); reload() })
                .catch((err) => {
                  // 422 несёт тот же BatchReport построчно, что и успех — общий
                  // post() на отказе его теряет и бросает голую строку; достаём
                  // назад, чтобы инженер увидел замечания по строкам, а не текст.
                  const parsed = asBatchReport(err)
                  if (parsed) setBatch(parsed)
                  else setMassReport(err instanceof Error ? err.message : String(err))
                })
                .finally(() => setMassBusy(false))
            }} />
        </label>
        {/* «Создать на основе» (находка прогона: варианты сравнения — это
            клон сценария с другой группировкой, перепечатывать восемь ссылок
            руками — мучение). Копируется содержимое выбранного без id и
            служебных полей; наименование получает пометку «(вариант)». */}
        {selected && (
          <button className="btn" disabled={massBusy}
            title={`новый объект с содержимым ${selected}: поменяйте отличающееся и сохраните`}
            onClick={() => {
              edit.object(selected)
                .then((o) => {
                  setTemplate(basedOnTemplate((o.doc ?? {}) as Record<string, unknown>))
                  setSelected(null)
                  setCreating(true)
                })
                .catch((e) => setMassReport(String(e)))
            }}>
            Создать на основе
          </button>
        )}
        <button className="btn btn--primary" onClick={() => { setSelected(null); setTemplate(null); setCreating(true) }}>
          Создать
        </button>
      </div>
      {batch && (
        <div className={batch.problems.length ? 'notice notice--blocked' : 'notice'}
          style={{ margin: '8px 14px 0' }}>
          {batch.problems.length === 0 ? (
            <>Записано объектов: <b className="mono">{batch.written}</b></>
          ) : (
            <>
              Пачка отклонена целиком — {batch.problems.length} замечаний, ничего не записано:
              <ul style={{ margin: '4px 0 0 16px' }}>
                {batch.problems.slice(0, 5).map((p, i) => (
                  <li key={i}>
                    <span className="mono">{p.id ?? p.index}</span>
                    {p.path ? ` · ${p.path}` : ''} — {p.message}
                  </li>
                ))}
              </ul>
            </>
          )}
        </div>
      )}
      <div className="registry">
        <div className="pane">
          {/* создание при раскрытии вниз — формой над таблицей, на месте */}
          {expandDown && creating && (
            <div className="rr-expand" style={{ display: 'block', padding: '10px 14px 12px', maxWidth: 760 }}>
              {editor}
            </div>
          )}
          {rows == null ? (
            <div className="empty">Загрузка…</div>
          ) : visible.length === 0 ? (
            <div className="empty">
              {filter
                ? 'Ничего не найдено — ослабьте отбор.'
                : `Пока пусто. Создайте первую запись — «${kindTitle(kind)}».`}
            </div>
          ) : (
            <table>
              <thead>
                <tr>
                  {/* Б-02: выбор строк — для мягкой отмены по выбранному */}
                  {meta.lifecycle && (
                    <th style={{ width: 28 }}>
                      <input type="checkbox"
                        title="выбрать все видимые живые"
                        checked={visible.some((r) => r.status !== 'Cancelled') &&
                          visible.filter((r) => r.status !== 'Cancelled').every((r) => picked.has(r.id))}
                        onChange={(e) => setPicked(e.target.checked
                          ? new Set(visible.filter((r) => r.status !== 'Cancelled').map((r) => r.id))
                          : new Set())} />
                    </th>
                  )}
                  <SortTh label="ID" sortKey="id" sort={sort} onToggle={toggle} width={90} />
                  <SortTh label="Содержание" sortKey="title" sort={sort} onToggle={toggle} />
                  {/* зрелость — только у видов со статусной моделью; у прочих
                      свой цикл в собственном поле status объекта */}
                  {meta.lifecycle && <SortTh label="Статус" sortKey="status" sort={sort} onToggle={toggle} width={110} />}
                  <SortTh label="Версия" sortKey="version" sort={sort} onToggle={toggle} width={60} />
                </tr>
              </thead>
              <tbody>
                {sorted.map((r) => (
                  <React.Fragment key={r.id}>
                  <tr
                    aria-selected={r.id === selected}
                    onClick={() => {
                      setCreating(false)
                      // при раскрытии вниз повторный клик закрывает строку
                      setSelected(expandDown && selected === r.id ? null : r.id)
                    }}
                  >
                    {meta.lifecycle && (
                      <td onClick={(e) => e.stopPropagation()}>
                        <input type="checkbox"
                          disabled={r.status === 'Cancelled'}
                          title={r.status === 'Cancelled' ? 'уже отменён' : 'выбрать к отмене'}
                          checked={picked.has(r.id)}
                          onChange={(e) => setPicked((prev) => {
                            const next = new Set(prev)
                            if (e.target.checked) next.add(r.id)
                            else next.delete(r.id)
                            return next
                          })} />
                      </td>
                    )}
                    <td className="id">{r.id}</td>
                    <td title={r.title}>{r.title}</td>
                    {meta.lifecycle && (
                      <td>
                        <span className={`dot status-${r.status}`} title={label('lifecycle', r.status)} />
                        {label('lifecycle', r.status)}
                      </td>
                    )}
                    <td className="num">{r.version}</td>
                  </tr>
                  {/* раскрытие редактора вниз — как в реестре требований */}
                  {expandDown && selected === r.id && !creating && (
                    <tr className="rr-expand">
                      <td colSpan={colCount}>
                        <div style={{ maxWidth: 760 }}>{editor}</div>
                      </td>
                    </tr>
                  )}
                  </React.Fragment>
                ))}
              </tbody>
            </table>
          )}
        </div>
        {!expandDown && open && (
          <aside className="inspector">
            {editor}
          </aside>
        )}
      </div>
    </>
  )
}
