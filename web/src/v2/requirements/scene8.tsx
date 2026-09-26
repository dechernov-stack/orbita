// Сцена 8 над реестром требований: раздача требований по целям, цели без требования, предложения требований.
import { useCallback, useEffect, useState } from 'react'
import { Полоса } from '../ui/band'
import { api, type DistributionRun, type FormationProposal } from '../api'

/**
 * Раздача требований по целям — один вызов модели (просьба владельца 20.09).
 *
 * Тот же порядок, что у раздачи нужд: предложения с причиной, отметки, приём,
 * «Отменить раздачу». Записывается ИСТОЧНИК требования — условие сцены 8
 * смотрит на него, а не на связь. Ничего не заводится само.
 */
export function РаздачаЦелей({ project, onChanged }: { project: string; onChanged: () => void }) {
  const [раздача, setРаздача] = useState<DistributionRun | null>(null)
  const [идёт, setИдёт] = useState(false)
  const [секунды, setСекунды] = useState(0)
  const [отмечены, setОтмечены] = useState<string[]>([])
  const [отказ, setОтказ] = useState<string | null>(null)
  const [итог, setИтог] = useState<string | null>(null)
  const [открыта, setОткрыта] = useState(false)

  const перечитать = useCallback(() => {
    api.goalCoverage(project)
      .then((р) => {
        if ('links' in р) {
          setРаздача(р)
          setОтмечены(р.links.filter((с) => !с.accepted && !с.exists).map((с) => с.id))
          setОткрыта(р.links.length > 0)
        } else { setРаздача(null) }
      })
      .catch(() => undefined)
  }, [project])
  useEffect(перечитать, [перечитать])

  const раздать = () => {
    setИдёт(true); setОтказ(null); setИтог(null); setСекунды(1)
    const часы = window.setInterval(() => setСекунды((с) => с + 1), 1000)
    const кончить = () => { window.clearInterval(часы); setСекунды(0); setИдёт(false) }
    api.distributeGoals(project)
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
    api.acceptGoalCoverage(project, раздача.run, выбрано, 'инженер', 'раздача целей принята инженером')
      .then((и) => { setИтог(и.note); перечитать(); onChanged() })
      .catch((e) => setОтказ(String(e.message ?? e)))
  }

  const отменить = () => {
    if (!раздача) return
    api.undoGoalCoverage(project, раздача.run, 'инженер')
      .then((о) => { setИтог(о.note); перечитать(); onChanged() })
      .catch((e) => setОтказ(String(e.message ?? e)))
  }

  return (
    <>
      <button type="button" className="v2-chip" disabled={идёт}
        title={идёт
          ? 'раздача идёт: тот же перечень даст тот же ответ'
          : 'один вызов модели по всем целям и требованиям проекта; связи заводит только приём'}
        onClick={раздать}>
        {идёт ? `Предлагаю… ${секунды} с` : 'Предложить моделью'}
      </button>
      {раздача && раздача.links.length > 0 && (
        <button type="button" className="v2-chip" aria-pressed={открыта}
          onClick={() => setОткрыта(!открыта)}
          title="показать предложения раздачи">
          предложений {раздача.links.length}
        </button>
      )}
      {открыта && раздача && (
        <div className="v2-card" data-why="работа">
          <div className="v2-note-line">
            <span className="v2-mono">{раздача.run}</span> {раздача.note}
          </div>
          {отказ && <div className="v2-locked">{отказ}</div>}
          {итог && <div className="v2-empty__why">{итог}</div>}
          {раздача.unassigned.length > 0 && (
            <div className="v2-empty__why">
              без требования осталось: {раздача.unassigned.join(', ')} — модели не к чему было отнести;
              свяжите вручную строкой выше
            </div>
          )}
          {раздача.refused.length > 0 && (
            <ul className="v2-dim">{раздача.refused.map((р) => <li key={р}>{р}</li>)}</ul>
          )}
          <table className="v2-tab2">
            <thead>
              <tr><th /><th>Требование</th><th>Цель</th><th>Почему</th></tr>
            </thead>
            <tbody>
              {раздача.links.map((с) => (
                <tr key={с.id} className={с.accepted || с.exists ? 'v2-dim' : undefined}>
                  <td>
                    {с.accepted || с.exists ? (
                      <span title={с.accepted ? 'принято этой раздачей' : 'цель уже стоит источником'}>
                        {с.accepted ? 'принято' : 'уже есть'}
                      </span>
                    ) : (
                      <input type="checkbox" checked={отмечены.includes(с.id)}
                        aria-label={`отметить связь ${с.id}`}
                        onChange={(e) => setОтмечены(e.target.checked
                          ? [...отмечены, с.id]
                          : отмечены.filter((к) => к !== с.id))} />
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
              title={выбрано.length === 0
                ? 'отметьте связи — раздача сама ничего не заводит'
                : `записать цель источником у ${выбрано.length} требований`}
              onClick={принять}>
              Принять связи ({выбрано.length})
            </button>
            {принятые.length > 0 && (
              <button type="button" className="v2-link" onClick={отменить}
                title="снять источники, дописанные этой раздачей; прежние основания целы">
                Отменить раздачу
              </button>
            )}
          </div>
        </div>
      )}
    </>
  )
}

/**
 * Цели без требования — рабочее место под условие сцены 8.
 *
 * Условие шаблона фазы — «каждая цель покрыта требованием»; оно смотрит на
 * `source[].ref` требования. Владелец 19.09 упирался в него третий раз:
 * «не даёт завершить: целей без требования: 8» — при том что все двенадцать
 * требований заполнены. Карточка позволяет добавить источник по одному, но
 * работать надо с ПРОБЕЛОМ: вот цели, которых никто не несёт, вот требования,
 * которыми их закрывают. Одна цель может закрываться несколькими требованиями,
 * одно требование — служить нескольким целям.
 */
export function ЦелиБезТребования({ project, onChanged }: { project: string; onChanged: () => void }) {
  const [открыта, setОткрыта] = useState(false)
  const [цели, setЦели] = useState<{ id: string; code: string; текст: string }[]>([])
  const [требования, setТребования] = useState<{ id: string; code: string; текст: string; источники: { kind: string; ref: string }[] }[]>([])
  const [выбор, setВыбор] = useState<Record<string, string>>({})
  const [занято, setЗанято] = useState<string | null>(null)
  const [отказ, setОтказ] = useState<string | null>(null)
  const [итог, setИтог] = useState<string | null>(null)

  const перечитать = useCallback(() => {
    Promise.all([api.entities(project, 'goal'), api.entities(project, 'requirement')])
      .then(([ц, т]) => {
        setЦели(ц.items.filter((с) => с.status !== 'cancelled').map((с) => ({
          id: с.id, code: с.code, текст: String(с.doc.statement ?? с.doc.title ?? '').slice(0, 90),
        })))
        setТребования(т.items.filter((с) => с.status !== 'cancelled').map((с) => ({
          id: с.id,
          code: с.code,
          текст: String(с.doc.title ?? с.doc.statement ?? '').slice(0, 70),
          источники: Array.isArray(с.doc.source)
            ? (с.doc.source as { kind?: string; ref?: string }[])
              .filter((и) => и && typeof и.ref === 'string')
              .map((и) => ({ kind: String(и.kind ?? 'material'), ref: String(и.ref) }))
            : [],
        })))
      })
      .catch((e) => setОтказ(String(e.message ?? e)))
  }, [project])

  useEffect(перечитать, [перечитать])

  const покрытые = new Set(требования.flatMap((т) => т.источники.map((и) => и.ref)))
  const без = цели.filter((ц) => !покрытые.has(ц.id))

  const связать = (цель: { id: string; code: string }) => {
    const код = выбор[цель.id]
    const т = требования.find((x) => x.code === код)
    if (!т) { setОтказ(`выберите требование для ${цель.code}`); return }
    setЗанято(цель.id); setОтказ(null); setИтог(null)
    api.patchEntity(project, т.code, {
      source: [...т.источники, { kind: 'goal', ref: цель.id }],
    }, 'инженер', `цель ${цель.code} закрывается требованием ${т.code}`)
      .then(() => {
        setИтог(`${цель.code} → ${т.code}: источник записан`)
        перечитать(); onChanged()
      })
      .catch((e) => setОтказ(String(e.message ?? e)))
      .finally(() => setЗанято(null))
  }

  if (цели.length === 0) return null

  return (
    // Шип 5 §4: работа сцены 8 над реестром — полосой, свёрнутой: реестр — главное на экране.
    <Полоса open={открыта} onToggle={() => setОткрыта(!открыта)} subject="Цели без требования"
      counts={`целей ${без.length} · требование выводится из цели`}>
      <РаздачаЦелей project={project} onChanged={() => { перечитать(); onChanged() }} />
      {отказ && <div className="v2-locked">{отказ}</div>}
      {итог && <div className="v2-empty__why">{итог}</div>}
      {без.length === 0 ? (
        <div className="v2-empty">
          Каждая цель закрыта требованием.
          <span className="v2-empty__why">Условие сцены 8 «каждая цель покрыта требованием» выполнено.</span>
        </div>
      ) : (
        <>
          <span className="v2-empty__why">
            Условие сцены 8 смотрит на источник требования: цель закрыта, когда хотя бы одно
            требование выведено ИЗ НЕЁ. Выберите требование — источник запишется ему.
          </span>
          {без.map((ц) => (
            <div key={ц.id} className="v2-note">
              <span className="v2-note__rule">{ц.code}</span>
              <span>{ц.текст}</span>
              <select name={`${ц.code}.покрыть`} value={выбор[ц.id] ?? ''}
                aria-label={`требование, закрывающее цель ${ц.code}`}
                onChange={(e) => setВыбор({ ...выбор, [ц.id]: e.target.value })}>
                <option value="">— каким требованием закрыта —</option>
                {требования.map((т) => (
                  <option key={т.code} value={т.code}>{т.code} · {т.текст}</option>
                ))}
              </select>
              <button type="button" className="v2-chip" disabled={занято === ц.id || !выбор[ц.id]}
                title={выбор[ц.id]
                  ? `записать цель ${ц.code} источником требования ${выбор[ц.id]}`
                  : 'сначала выберите требование'}
                onClick={() => связать(ц)}>
                {занято === ц.id ? 'Связываю…' : 'Связать'}
              </button>
            </div>
          ))}
        </>
      )}
    </Полоса>
  )
}

/**
 * Предложения требований из постановки — прямо на сцене 8.
 *
 * Маршрут владельца: «Сцена 8: принять 12 требований, распределить по
 * природе». До 18.09 предложения лежали на другом экране (поле знаний), и на
 * сцене 8 было пусто: «ничего в требованиях верхнего уровня не изменилось».
 * Приём тот же самый — те же ворота сверки и та же обратимость пакета.
 */
export function ПредложенияТребований({ project, onAccepted }: { project: string; onAccepted: () => void }) {
  const [открыта, setОткрыта] = useState(false)
  const [запуск, setЗапуск] = useState<string>('')
  const [строки, setСтроки] = useState<FormationProposal[]>([])
  const [отмечены, setОтмечены] = useState<string[]>([])
  const [занято, setЗанято] = useState(false)
  const [итог, setИтог] = useState<string | null>(null)
  const [отказ, setОтказ] = useState<string | null>(null)

  const перечитать = useCallback(() => {
    api.synthesisDiff(project)
      .then((д) => {
        if (!('id' in д) || !д.diff) { setСтроки([]); return }
        const свои = (['new', 'augment', 'contradict', 'confirm'] as const)
          .flatMap((к) => д.diff?.[к] ?? [])
          .filter((п) => п.concept === 'requirement')
        setЗапуск(д.id)
        setСтроки(свои)
        setОтмечены(свои.filter((п) => п.missing.length === 0 && п.verdict === 'new').map((п) => п.proposal))
      })
      .catch(() => setСтроки([]))
  }, [project])
  useEffect(перечитать, [перечитать])

  if (строки.length === 0) return null

  const принять = () => {
    if (отмечены.length === 0) return
    setЗанято(true); setОтказ(null)
    api.acceptSynthesis(project, запуск, отмечены, 'инженер', 'сцена 8: требования из постановки')
      .then((и) => {
        setИтог(`заведено ${и.created.length}${и.pending.length > 0 ? `, ждёт решения ${и.pending.length}` : ''}`)
        onAccepted(); перечитать()
      })
      .catch((e) => setОтказ(String(e.message ?? e)))
      .finally(() => setЗанято(false))
  }

  return (
    // Шип 5 §4: приём предложений в реестр не встраивается (§3.3) — здесь он
    // полосой над реестром (решение владельца 18.09), свёрнутой по умолчанию.
    <Полоса open={открыта} onToggle={() => setОткрыта(!открыта)} subject="Предложения требований из постановки"
      counts={`предложений ${строки.length} · приём заводит черновиком, обратим пакетом`}>
      <div className="v2-empty__why">
        Требования уровня проекта образуются здесь, решением инженера: приём заводит их
        черновиком, а природу и носителя вы назначаете в карточке. Приём обратим пакетом.
      </div>
      {отказ && <div className="v2-locked">{отказ}</div>}
      {итог && <div className="v2-note-line">{итог}</div>}
      <table className="v2-table">
        <thead><tr><th /><th>Заголовок</th><th>Формулировка</th><th>Основания</th></tr></thead>
        <tbody>
          {строки.map((п) => (
            <tr key={п.proposal}>
              <td>
                <input type="checkbox" checked={отмечены.includes(п.proposal)}
                  aria-label={`отметить ${п.proposal}`} autoComplete="off"
                  onChange={(e) => setОтмечены(e.target.checked
                    ? [...отмечены, п.proposal]
                    : отмечены.filter((к) => к !== п.proposal))} />
              </td>
              <td>{п.payload.title ?? '—'}</td>
              <td>{п.payload.statement ?? ''}</td>
              <td className="v2-dim">{п.basis.map((о) => `${о.material ?? о.fact}${о.anchor ? ` · ${о.anchor}` : ''}`).join(' · ')}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className="v2-form__actions">
        <button type="button" className="v2-primary" disabled={занято || отмечены.length === 0}
          title={отмечены.length === 0 ? 'отметьте предложения — сами они ничего не заводят' : `принять ${отмечены.length} черновиками`}
          onClick={принять}>
          {занято ? 'Принимаю…' : `Принять в черновик (${отмечены.length})`}
        </button>
      </div>
    </Полоса>
  )
}
