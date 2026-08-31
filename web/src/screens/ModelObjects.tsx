// Объекты модели, у которых нет собственного экрана (шаг 15 §1.1):
// элементы архитектуры, интерфейсы, входы моделирования, сценарии, риски.
//
// Экран один, а видов много: перечень видов приходит с сервера (`/api/kinds`),
// форма строится по схеме вида. Писать по экрану на каждый вид значило бы
// пятнадцать раз повторить одно и то же и пятнадцать раз разойтись со схемой.
//
// Именно этот экран закрывает критерий шага: пустой проект наполняется
// целиком через интерфейс, без консоли и seedDemo.
import { useCallback, useEffect, useMemo, useState } from 'react'
import { edit, type KindRow, type StoredSummary } from '../api/edit'
import { ObjectEditor } from '../ui/ObjectEditor'
import { StatusDot } from '../ui/parts'
import { useSession } from '../ui/session'
import { SortTh, useSort } from '../ui/sort'

/** Подписи видов: код вида — тоже код, и на экран он выходить не должен. */
const KIND_TITLE: Record<string, string> = {
  need: 'нужды',
  service: 'сервисы',
  requirement: 'требования',
  component: 'элементы архитектуры',
  interface: 'интерфейсы',
  evidence: 'свидетельства',
  validation: 'валидации',
  risk: 'риски',
  scenario: 'сценарии моделирования',
  constellation: 'группировки',
  spacecraft: 'аппараты',
  demand_map: 'карты спроса',
  terminal_profile: 'профили терминалов',
  ground_stations: 'наземные станции',
  protocol_adapter: 'адаптеры протокола',
}

export function ModelObjects({ kinds }: { kinds: string[] }) {
  const { label } = useSession()
  const [available, setAvailable] = useState<KindRow[] | null>(null)
  const [kind, setKind] = useState<string>(kinds[0])
  const [rows, setRows] = useState<StoredSummary[] | null>(null)
  const [selected, setSelected] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    edit.kinds().then(setAvailable).catch((e) => setError(String(e)))
  }, [])

  const row = useMemo(() => available?.find((k) => k.type === kind), [available, kind])

  /** Объекты вида приходят готовым списком: подпись выбирает сервер. */
  const reload = useCallback(() => {
    edit
      .list(kind)
      .then((next) => {
        setRows(next)
        setError(null)
      })
      .catch((e) => setError(String(e)))
  }, [kind])

  useEffect(() => {
    setRows(null)
    setSelected(null)
    setCreating(false)
    reload()
  }, [reload])

  if (error) return <div className="empty">Ошибка обращения к API: {error}</div>
  if (!available) return <div className="empty">Загрузка видов…</div>
  if (!row) return <div className="empty">Вид «{kind}» ядру неизвестен</div>

  // Сортировка заголовком (§2.4): реестр объектов модели
  const { sorted, sort, toggle } = useSort(rows ?? [], {
    id: (o) => o.id,
    version: (o) => o.version,
    status: (o) => o.status,
    title: (o) => o.title ?? '',
  })

  return (
    <div className="split">
      <div className="pane">
        <div className="pane__tools">
          <label className="secondary">
            Вид объекта{' '}
            <select value={kind} aria-label="Вид объекта" onChange={(e) => setKind(e.target.value)}>
              {kinds.map((k) => (
                <option key={k} value={k}>
                  {KIND_TITLE[k] ?? k}
                </option>
              ))}
            </select>
          </label>
          <button
            type="button"
            className="tab tab--primary"
            onClick={() => {
              setCreating(true)
              setSelected(null)
            }}
          >
            + Добавить
          </button>
        </div>

        {rows && rows.length === 0 && (
          <div className="empty">
            Объектов вида «{KIND_TITLE[kind] ?? kind}» пока нет. Форма построена по схеме вида
            и проверяется теми же правилами, что импорт и предложения ИИ.
          </div>
        )}

        {rows && rows.length > 0 && (
          <table>
            <thead>
              <tr>
                <SortTh label="ID" sortKey="id" sort={sort} onToggle={toggle} width={90} />
                <SortTh label="Версия" sortKey="version" sort={sort} onToggle={toggle} width={70} />
                <SortTh label="Статус" sortKey="status" sort={sort} onToggle={toggle} width={130} />
                <SortTh label="Содержание" sortKey="title" sort={sort} onToggle={toggle} />
              </tr>
            </thead>
            <tbody>
              {sorted.map((o) => (
                <tr
                  key={o.id}
                  aria-selected={o.id === selected}
                  onClick={() => {
                    setSelected(o.id)
                    setCreating(false)
                  }}
                >
                  <td>
                    <span className="id">{o.id}</span>
                  </td>
                  <td className="mono">{o.version}</td>
                  <td>
                    <StatusDot status={o.status} />
                    <span className="secondary">{label('lifecycle', o.status)}</span>
                  </td>
                  <td className="wrap secondary">{o.title}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <aside className="pane pane--side">
        {creating || selected ? (
          <ObjectEditor
            kind={kind}
            schemaName={row.schema}
            title={KIND_TITLE[kind] ?? kind}
            id={creating ? null : selected}
            onSaved={(id) => {
              setCreating(false)
              setSelected(id)
              reload()
            }}
            onCancelled={() => {
              setSelected(null)
              reload()
            }}
          />
        ) : (
          <div className="secondary">Выберите объект для правки или добавьте новый.</div>
        )}
      </aside>
    </div>
  )
}
