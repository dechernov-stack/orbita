// Новая запись той же карточкой объекта (27.09, «блокеры Phase A»).
//
// «+ завести» из строки долга карточки открывает вниз карточку ВИДА: грани по
// истине, контрол по виджету поля, подсказка — примечанием истины. Правка
// ложится в черновик; запись заводится одним общим вызовом, когда названы
// обязательные поля вида — названо словами, чего не хватает. Отдельной формы
// под вид нет: это та же карточка, что правит запись на месте.
import { useEffect, useMemo, useState } from 'react'
import { api, type EntityRow, type KindSpec } from '../api'
import { Карточка, пусто, type СвойКонтрол } from './objectcard'

export function НоваяЗапись({ project, kind, заготовка = {}, заголовок, свои, скрыть = [], автор, onCreated, onClose }: {
  project: string
  /** Вид истины: функция · обмен · элемент обмена · КЕ · логический компонент. */
  kind: string
  /** Что уже известно из места, откуда заводят: узел, стык, слой. */
  заготовка?: Record<string, unknown>
  заголовок?: string
  свои?: Record<string, СвойКонтрол>
  скрыть?: string[]
  автор: string
  onCreated: (код: string) => void
  onClose: () => void
}) {
  const [spec, setSpec] = useState<KindSpec | null>(null)
  const [doc, setDoc] = useState<Record<string, unknown>>(заготовка)
  const [отказ, setОтказ] = useState<string | null>(null)
  const [занято, setЗанято] = useState(false)
  useEffect(() => { api.kind(kind).then(setSpec).catch((e) => setОтказ(String((e as Error).message ?? e))) }, [kind])
  const row = useMemo<EntityRow>(() => ({ id: '', code: 'новая', status: 'draft', doc }), [doc])
  const скрытые = useMemo(() => ['code', ...скрыть], [скрыть])

  if (!spec) return <div className="v2-empty" data-why="работа">{отказ ?? 'Читаю вид…'}</div>
  const нет = spec.required.filter((п) => п !== 'code' && пусто(doc[п])).map((п) => spec.labels?.[п] ?? п)

  const завести = () => {
    setЗанято(true); setОтказ(null)
    api.createEntity(project, kind, doc, автор || 'инженер')
      .then((о) => onCreated(о.code))
      .catch((e) => setОтказ(String((e as Error).message ?? e)))
      .finally(() => setЗанято(false))
  }

  return (
    <div className="v2-object__new" aria-label={`новая запись: ${spec.title}`}>
      <Карточка project={project} row={row} spec={spec} заголовок={заголовок ?? `Новая запись: ${spec.title}`}
        мета="ещё не заведена" черновик всеСразу скрыть={скрытые} свои={свои} onClose={onClose}
        сохранитьПоле={(поле, значение) => {
          setDoc((д) => ({ ...д, [поле]: значение }))
          return Promise.resolve({ version: 0, changed: 1 })
        }} />
      {отказ && <div className="v2-locked">{отказ}</div>}
      <div className="v2-form__actions">
        <button type="button" className="v2-primary" disabled={занято || нет.length > 0}
          title={нет.length > 0 ? `не названо: ${нет.join(' · ')}` : `завести: ${spec.title} — грани правятся потом на месте`}
          onClick={завести}>
          {занято ? 'Завожу…' : 'Завести'}
        </button>
        <button type="button" className="v2-link" title="закрыть черновик, ничего не заводя" onClick={onClose}>отменить</button>
        {нет.length > 0 && <span className="v2-empty__why">Нажать нельзя: не названо — {нет.join(' · ')}.</span>}
      </div>
    </div>
  )
}
