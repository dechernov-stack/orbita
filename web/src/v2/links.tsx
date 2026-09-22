// Одна раздача связей на экране (шип 1, «+ одна раздача связей»).
//
// Порядок один на все виды связи: «Предложить связи» — один вызов по полным
// перечням, карта с причиной на связь, отметки, «Принять связи», «Отменить
// раздачу». Вид связи — параметр; слова колонок — от вида. Четвёртая раздача,
// функции → узлы на сцене 7, получается этим же компонентом.
import { useCallback, useEffect, useState } from 'react'
import { api, type DistributionRun } from './api'
import { useАвтор } from './research'

/** Слова раздачи по виду связи: чем названы источник и цель. */
const СЛОВА: Record<string, { источник: string; цель: string; принять: string }> = {
  covers: { источник: 'Нужда', цель: 'Цель · сервис', принять: 'заведёт связи «покрывает»' },
  derives_from: { источник: 'Требование', цель: 'Цель', принять: 'запишет цель источником требования' },
  allocated_to: { источник: 'Функция', цель: 'Узел', принять: 'запишет узел носителем функции' },
}

export function РаздачаСвязей({ project, type, onChanged }: {
  project: string
  /** Вид связи из реестра: covers · derives_from · allocated_to. */
  type: string
  onChanged?: () => void
}) {
  const [раздача, setРаздача] = useState<DistributionRun | null>(null)
  const [идёт, setИдёт] = useState(false)
  const [секунды, setСекунды] = useState(0)
  const [отмечены, setОтмечены] = useState<string[]>([])
  const [отказ, setОтказ] = useState<string | null>(null)
  const [итог, setИтог] = useState<string | null>(null)
  const [открыта, setОткрыта] = useState(false)
  const [автор] = useАвтор()
  const слова = СЛОВА[type] ?? { источник: 'Откуда', цель: 'Куда', принять: 'запишет связи' }

  const перечитать = useCallback(() => {
    api.linkProposal(project, type)
      .then((р) => {
        if ('links' in р) {
          setРаздача(р)
          setОтмечены(р.links.filter((с) => !с.accepted && !с.exists).map((с) => с.id))
          setОткрыта(р.links.length > 0)
        } else { setРаздача(null) }
      })
      .catch(() => undefined)
  }, [project, type])
  useEffect(перечитать, [перечитать])

  const раздать = () => {
    setИдёт(true); setОтказ(null); setИтог(null); setСекунды(1)
    const часы = window.setInterval(() => setСекунды((с) => с + 1), 1000)
    const кончить = () => { window.clearInterval(часы); setСекунды(0); setИдёт(false) }
    api.proposeLinks(project, type, автор || 'инженер')
      .then((р) => {
        кончить(); setРаздача(р); setОткрыта(true)
        setОтмечены(р.links.filter((с) => !с.accepted && !с.exists).map((с) => с.id))
        setИтог(р.note)
      })
      .catch((e) => { кончить(); setОтказ(String(e.message ?? e)) })
  }

  const кПриёму = раздача ? раздача.links.filter((с) => !с.accepted && !с.exists) : []
  const принятые = раздача ? раздача.links.filter((с) => с.accepted) : []
  const выбрано = отмечены.filter((id) => кПриёму.some((с) => с.id === id))

  const принять = () => {
    if (!раздача || выбрано.length === 0) return
    api.acceptLinks(project, type, раздача.run, выбрано, автор || 'инженер', 'раздача принята инженером')
      .then((и) => { setИтог(и.note); перечитать(); onChanged?.() })
      .catch((e) => setОтказ(String(e.message ?? e)))
  }
  const отменить = () => {
    if (!раздача) return
    api.undoLinks(project, type, раздача.run, автор || 'инженер')
      .then((о) => { setИтог(о.note); перечитать(); onChanged?.() })
      .catch((e) => setОтказ(String(e.message ?? e)))
  }

  return (
    <>
      <button type="button" className="v2-chip" disabled={идёт}
        title={идёт
          ? 'раздача идёт: тот же перечень даст тот же ответ'
          : 'один вызов модели по полным перечням проекта; связи заводит только приём'}
        onClick={раздать}>
        {идёт ? `Предлагаю… ${секунды} с` : 'Предложить связи'}
      </button>
      {раздача && раздача.links.length > 0 && (
        <button type="button" className="v2-chip" aria-pressed={открыта} onClick={() => setОткрыта(!открыта)}
          title="показать предложения раздачи">
          предложений {раздача.links.length}
        </button>
      )}
      {отказ && <div className="v2-locked">{отказ}</div>}
      {открыта && раздача && (
        <div className="v2-card" data-why="работа">
          <div className="v2-note-line"><span className="v2-mono">{раздача.run}</span> {раздача.note}</div>
          {итог && <div className="v2-empty__why">{итог}</div>}
          {раздача.unassigned.length > 0 && (
            <div className="v2-empty__why">без связи осталось: {раздача.unassigned.join(', ')} — модели не к чему было отнести</div>
          )}
          {раздача.refused.length > 0 && <ul className="v2-dim">{раздача.refused.map((р) => <li key={р}>{р}</li>)}</ul>}
          <table className="v2-tab2">
            <thead><tr><th /><th>{слова.источник}</th><th>{слова.цель}</th><th>Почему</th></tr></thead>
            <tbody>
              {раздача.links.map((с) => (
                <tr key={с.id} className={с.accepted || с.exists ? 'v2-dim' : undefined}>
                  <td>
                    {с.accepted || с.exists ? (
                      <span title={с.accepted ? 'принято этой раздачей' : 'связь уже есть'}>{с.accepted ? 'принято' : 'уже есть'}</span>
                    ) : (
                      <input type="checkbox" checked={отмечены.includes(с.id)} aria-label={`отметить связь ${с.id}`}
                        onChange={(e) => setОтмечены(e.target.checked ? [...отмечены, с.id] : отмечены.filter((к) => к !== с.id))} />
                    )}
                  </td>
                  <td><span className="v2-mono">{с.need}</span><div>{с.need_text}</div></td>
                  <td><span className="v2-mono">{с.target}</span><div>{с.target_text}</div></td>
                  <td>{с.reason}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="v2-form__actions">
            <button type="button" className="v2-primary" disabled={выбрано.length === 0}
              title={выбрано.length === 0 ? 'отметьте связи — раздача сама ничего не заводит' : `приём ${слова.принять}: ${выбрано.length}`}
              onClick={принять}>
              Принять связи ({выбрано.length})
            </button>
            {принятые.length > 0 && (
              <button type="button" className="v2-link" onClick={отменить} title="снять принятое этой раздачей; прежнее цело">
                Отменить раздачу
              </button>
            )}
          </div>
        </div>
      )}
    </>
  )
}

/** Функции системы и их узлы — рабочее место четвёртой раздачи на сцене 7. */
export function ФункцииКУзлам({ project }: { project: string }) {
  const [функции, setФункции] = useState<{ code: string; name?: string; layer?: string; allocated_to?: string[] }[]>([])
  const перечитать = useCallback(() => {
    api.functions(project).then((р) => setФункции(р.items)).catch(() => setФункции([]))
  }, [project])
  useEffect(перечитать, [перечитать])
  const безУзла = функции.filter((ф) => (ф.allocated_to ?? []).length === 0)
  return (
    <div className="v2-card" data-why="работа">
      <div className="v2-card__head">
        <span className="v2-card__title">Функции → узлы</span>
        <span className="v2-card__count">{функции.length} · без узла {безУзла.length}</span>
      </div>
      <span className="v2-empty__why">
        Функция без носителя — не архитектура: условие A5 считает функции SA без узла. Узлы предлагает раздача, записывает приём.
      </span>
      {функции.length === 0 ? (
        <div className="v2-empty">Функций нет. <span className="v2-empty__why">Возьмите полку архитектуры или заведите функции.</span></div>
      ) : (
        <ul className="v2-dim">
          {функции.map((ф) => (
            <li key={ф.code}>
              <span className="v2-mono">{ф.code}</span> {ф.name ?? ''}
              {ф.layer ? ` · ${ф.layer}` : ''}
              {' → '}{(ф.allocated_to ?? []).length > 0 ? ф.allocated_to!.join(', ') : <span className="v2-warn">узла нет</span>}
            </li>
          ))}
        </ul>
      )}
      <РаздачаСвязей project={project} type="allocated_to" onChanged={перечитать} />
    </div>
  )
}
