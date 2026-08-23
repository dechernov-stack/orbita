// Шаблон «Реестр» (блок D, дизайн §3.2): одна таблица объектов вида с
// отбором, счётчиком и главным действием; справа инспектор — панель работы
// с выбранным объектом (§3.3). На него сажаются все виды без собственного
// расчётного экрана. Правила формы — серверные (ObjectEditor, шаг 15).
import { useCallback, useEffect, useMemo, useState } from 'react'
import { api } from '../api/client'
import { edit, type KindRow, type StoredSummary } from '../api/edit'
import { ObjectEditor } from '../ui/ObjectEditor'
import { useSession } from '../ui/session'

/** Подписи видов на экране; сервер отдаёт состав видов, имена — словарь экрана. */
const KIND_TITLES: Record<string, string> = {
  mission_goal: 'Цели миссии',
  need: 'Нужды',
  service: 'Сервисы',
  requirement: 'Требования',
  conops: 'Сценарии ConOps',
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
}

const kindTitle = (k: string) => KIND_TITLES[k] ?? k

export function KindRegistry({ kinds, title }: { kinds: string[]; title: string }) {
  const { label, author } = useSession()
  const [massStatus, setMassStatus] = useState('Preliminary')
  const [massReport, setMassReport] = useState<string | null>(null)
  const [available, setAvailable] = useState<KindRow[] | null>(null)
  const [kind, setKind] = useState<string>(kinds[0])
  const [rows, setRows] = useState<StoredSummary[] | null>(null)
  const [selected, setSelected] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [filter, setFilter] = useState('')
  const [error, setError] = useState<string | null>(null)

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
    reload()
  }, [reload])

  if (error) return <div className="empty">Ошибка обращения к API: {error}</div>
  if (!available || !meta) return <div className="empty">Загрузка…</div>

  const visible = (rows ?? []).filter(
    (r) => !filter || r.id.toLowerCase().includes(filter.toLowerCase()) ||
      (r.title ?? '').toLowerCase().includes(filter.toLowerCase()),
  )
  const open = creating || selected != null

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
        {/* Массовое действие реестра (§3.2): перевод статуса пачкой */}
        {(rows?.length ?? 0) > 0 && (
          <>
            <select value={massStatus} onChange={(e) => setMassStatus(e.target.value)}>
              {['Preliminary', 'Approved', 'Baseline'].map((st) => (
                <option key={st} value={st}>{label('lifecycle', st)}</option>
              ))}
            </select>
            <button className="btn" disabled={!author}
              title={author ? 'перевести все видимые ниже выбранного статуса' : 'представьтесь в шапке'}
              onClick={() => {
                const ids = visible.filter((r) => r.status !== massStatus && r.status !== 'Cancelled').map((r) => r.id)
                if (ids.length === 0) { setMassReport('переводить нечего'); return }
                api.promoteBatch(ids, massStatus, author)
                  .then((r) => {
                    setMassReport(`переведено ${r.promoted.length}; отказов ${r.failed.length}` +
                      (r.failed.length ? ` — ${r.failed.slice(0, 3).map((f) => `${f.id}: ${f.reason}`).join('; ')}` : ''))
                    reload()
                  })
                  .catch((e) => setMassReport(String(e)))
              }}>
              Все видимые →
            </button>
            {massReport && <span className="secondary">{massReport}</span>}
          </>
        )}
        <button className="btn btn--primary" onClick={() => { setSelected(null); setCreating(true) }}>
          Создать
        </button>
      </div>
      <div className="registry">
        <div className="pane">
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
                  <th style={{ width: 90 }}>ID</th>
                  <th>Содержание</th>
                  <th style={{ width: 110 }}>Статус</th>
                  <th style={{ width: 60 }}>Версия</th>
                </tr>
              </thead>
              <tbody>
                {visible.map((r) => (
                  <tr
                    key={r.id}
                    aria-selected={r.id === selected}
                    onClick={() => { setCreating(false); setSelected(r.id) }}
                  >
                    <td className="id">{r.id}</td>
                    <td title={r.title}>{r.title}</td>
                    <td>
                      <span className={`dot status-${r.status}`} />
                      {label('lifecycle', r.status)}
                    </td>
                    <td className="num">{r.version}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
        {open && (
          <aside className="inspector">
            <ObjectEditor
              kind={kind}
              schemaName={meta.schema}
              id={creating ? null : selected}
              title={kindTitle(kind)}
              onSaved={(id) => { setCreating(false); setSelected(id); reload() }}
              onCancelled={() => { setCreating(false); setSelected(null); reload() }}
            />
          </aside>
        )}
      </div>
    </>
  )
}
