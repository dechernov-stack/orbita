// Библиотека (шип 2, экран 10): каталог полок СТРОКАМИ и окно взятия.
//
// Прежний экран был складом: список полок и «взять ничего нельзя» (владелец,
// журнал ПМИ-7 З-24). Теперь у каждой строки — имя, число записей, версия,
// «взято: N» и «Взять в проект…»; где взятие идёт своим механизмом или не
// идёт вовсе, кнопка заперта и говорит почему — молчания не бывает.
//
// Окно взятия: записи с чекбоксами (отмечено то, что рекомендует сама полка),
// уже взятые — строкой «есть в проекте» без чекбокса, запертые — серым, со
// словами «взять ‹родителя›»; предпросмотр «станут доступны» считает сервер.
import { useCallback, useEffect, useState } from 'react'
import './library.css'
import { библиотекаApi, type ОкноВзятия, type СтрокаКаталога } from './library.api'
import { Икон } from './icons'

export function Library({ project }: { project: string | null }) {
  const [полки, setПолки] = useState<СтрокаКаталога[]>([])
  const [пустые, setПустые] = useState<string[]>([])
  const [помета, setПомета] = useState('')
  const [окно, setОкно] = useState<ОкноВзятия | null>(null)
  const [отмечено, setОтмечено] = useState<string[]>([])
  const [итог, setИтог] = useState<string | null>(null)
  const [отказ, setОтказ] = useState<string | null>(null)
  const [занято, setЗанято] = useState(false)

  const перечитать = useCallback(() => {
    if (!project) return
    библиотекаApi.каталог(project)
      .then((к) => { setПолки(к.items); setПустые(к.empty); setПомета(к.empty_note) })
      .catch((e) => setОтказ(String(e.message ?? e)))
  }, [project])
  useEffect(перечитать, [перечитать])

  const открыть = (полка: СтрокаКаталога) => {
    if (!project) return
    setОтказ(null); setИтог(null)
    библиотекаApi.окно(project, полка.code)
      .then((о) => { setОкно(о); setОтмечено(о.items.filter((з) => з.recommended && !з.taken).map((з) => з.code)) })
      .catch((e) => setОтказ(String(e.message ?? e)))
  }

  const взять = () => {
    if (!project || !окно || отмечено.length === 0) return
    setЗанято(true); setОтказ(null)
    библиотекаApi.взять(project, окно.shelf, отмечено)
      .then((и) => { setИтог(и.note); setОкно(null); setОтмечено([]); перечитать() })
      .catch((e) => setОтказ(String(e.message ?? e)))
      .finally(() => setЗанято(false))
  }

  if (!project) {
    return (
      <div className="v2-panel" data-why="почему-нельзя">
        <h3>Библиотека</h3>
        <div className="v2-empty">
          Проект не выбран.
          <span className="v2-empty__why">Полки общие, а берут с них в проект — значит, проект нужен.</span>
        </div>
      </div>
    )
  }

  return (
    <div className="v2-panel" data-why="работа">
      <h3>
        Библиотека
        <span className="v2-cnt">полок {полки.length}</span>
      </h3>
      {отказ && <div className="v2-locked">{отказ}</div>}
      {итог && <div className="v2-empty__why">{итог}</div>}
      {полки.length === 0 ? (
        <div className="v2-empty">
          Полок нет.
          <span className="v2-empty__why">Библиотека наполняется поставкой внешнего контура, не руками.</span>
        </div>
      ) : (
        <table className="v2-table">
          <thead>
            <tr><th>Полка</th><th>Записей</th><th>Версия</th><th>Взято</th><th>Что это</th><th /></tr>
          </thead>
          <tbody>
            {полки.map((п) => {
              const помеха = п.why ?? (п.mechanism ? механизмом(п.mechanism) : null)
              return (
                <tr key={п.code}>
                  <td>{п.title}<div className="v2-dim">{п.code}</div></td>
                  <td>{п.count}</td>
                  <td>{п.version > 0 ? п.version : <span className="v2-dim">—</span>}</td>
                  <td>{п.taken > 0 ? п.taken : <span className="v2-dim">ничего</span>}</td>
                  <td className="v2-dim">{п.what}</td>
                  <td>
                    <button type="button" className={помеха ? undefined : 'v2-primary'}
                      disabled={Boolean(помеха)}
                      title={помеха ?? `открыть окно взятия: отметить записи и взять их в проект «${project}»`}
                      onClick={() => открыть(п)}>
                      Взять в проект…
                    </button>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      )}
      {пустые.length > 0 && (
        <div className="v2-empty__why">пустые полки: {пустые.join(', ')} — {помета}</div>
      )}

      {окно && (
        <div className="v2-take" data-why="работа">
          <div className="v2-card__head">
            <span className="v2-card__title">Взять с полки · {окно.title}</span>
            <span className="v2-card__count">
              возьмём {окно.take}
              {окно.already > 0 ? ` · уже есть ${окно.already}` : ''}
              {окно.blocked > 0 ? ` · заперто ${окно.blocked}` : ''}
            </span>
            <span className="v2-head__spacer" />
            <button type="button" className="v2-link" title="закрыть окно взятия: ничего не возьмётся"
              onClick={() => { setОкно(null); setОтмечено([]) }}>
              закрыть
            </button>
          </div>
          <div className="v2-empty__why">{окно.note}</div>
          <div className="v2-take__list">
            {окно.items.map((з) => {
              const заперта = з.needs.length > 0 && !з.taken
              return (
                <label key={з.code}
                  className={заперта ? 'v2-check v2-take__row v2-take__row--locked' : 'v2-check v2-take__row'}
                  style={{ paddingLeft: `${з.level * 16}px` }}
                  title={з.taken
                    ? 'эта запись уже в проекте: повтор ничего не удваивает'
                    : заперта
                      ? `сначала возьмите ${з.needs.map((н) => н.title).join(', ')} — без родителя узел был бы сиротой`
                      : `отметить «${з.title}» к взятию`}>
                  {з.taken ? (
                    <span className="v2-dim">есть в проекте</span>
                  ) : (
                    <input type="checkbox" checked={отмечено.includes(з.code)} disabled={заперта}
                      aria-label={`взять ${з.code}`}
                      onChange={(e) => setОтмечено(e.target.checked
                        ? [...отмечено, з.code]
                        : отмечено.filter((к) => к !== з.code))} />
                  )}
                  <span>
                    <span className="v2-mono">{з.code}</span> {з.title}
                    {заперта && (
                      <span className="v2-dim"> · взять {з.needs.map((н) => н.title).join(', ')}</span>
                    )}
                  </span>
                </label>
              )
            })}
          </div>
          {окно.unlocks.length > 0 && (
            <div className="v2-empty__why">
              станут доступны: {окно.unlocks.map((д) => `${д.count} ${д.kind}`).join(', ')}
            </div>
          )}
          <div className="v2-form__actions">
            <button type="button" className="v2-primary" disabled={отмечено.length === 0 || занято}
              title={отмечено.length === 0
                ? 'не отмечено ни одной записи — отметьте, что взять'
                : `взять ${отмечено.length} в проект; взятое помнит исток`}
              onClick={взять}>
              <Икон имя="полка" />
              {занято ? 'Беру…' : `Взять (${отмечено.length})`}
            </button>
            {отмечено.length === 0 && <span className="v2-empty__why">Нажать нельзя: ничего не отмечено.</span>}
          </div>
        </div>
      )}
    </div>
  )
}

/** Своим механизмом — словами, а не молчащей кнопкой. */
function механизмом(механизм: string): string {
  if (механизм === 'frame') return 'каркас состава берётся на экране концепции: «Взять из полки» — библиотека эту работу не повторяет'
  if (механизм === 'wbs') return 'каркас работ берётся окном пакетов на экране работ — библиотека эту работу не повторяет'
  return 'у этой полки свой механизм взятия — библиотека его не подменяет'
}
