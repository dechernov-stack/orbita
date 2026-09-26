// Раздача нужд по целям и сервисам из поля знаний.
import { useCallback, useEffect, useState } from 'react'
import { ConfirmBox, useConfirm } from '../../ui/Confirm'
import { api, type DistributionLink, type DistributionRun } from '../api'
import { отказПодробно } from './common'

/**
 * Постановка из поля: диф четырьмя группами — новое · дополнить ·
 * противоречит · подтверждено.
 *
 * Синтез идёт по ВСЕМУ полю, а не по последнему источнику, и ничего не
 * заводит: он предлагает. Отмеченное принимает клик человека, и то ЧЕРЕЗ
 * сверку — теми же четырьмя вопросами, что и ручной ввод. Счётчики групп,
 * размер среза и вердикты приходят с сервера готовыми; заранее не отмечено
 * ничего — в том числе в группе «противоречит», где значение выбирает
 * человек, а не служба.
 *
 * Отметка «все» (просьба владельца на прогоне 14.09: щёлкать каждую строку
 * поодиночке — работа, а не решение) ставит галочки РАЗОМ, но только на
 * предложения с пустым «не закрыто». Помеченное — незакрытая обязательная
 * связь или правило образования — пакетом не идёт: сервер отказывает всему
 * пакету целиком (422) ещё до первого заведения, и одна такая строка увела
 * бы в отказ и все здоровые. Сколько их и почему они мимо пакета, сказано
 * рядом словами; решаются они по одной, в своей строке.
 */
/**
 * Раздача нужд по целям и сервисам — один вызов (решение владельца 17.09).
 * Чтение документа даёт цели и сервисы со ссылками не на все нужды, и сцены
 * 4 и 6 не закрываются содержанием. Здесь модель получает ПОЛНЫЕ перечни и
 * отдаёт карту связей; экран показывает её строками с причиной, а заводит
 * связи только клик «Принять связи» — и он обратим.
 */
export function РаздачаНужд({ project, onChanged, вПолосе = false }: {
  project: string
  onChanged: () => void
  /** Во вкладке «Предложения» раздача живёт своей полосой: заголовок и число — у полосы. */
  вПолосе?: boolean
}) {
  const [раздача, setРаздача] = useState<DistributionRun | null>(null)
  const [идёт, setИдёт] = useState(false)
  const [секунды, setСекунды] = useState(0)
  const [отказ, setОтказ] = useState<string | null>(null)
  const [итог, setИтог] = useState<string | null>(null)
  const [отмечены, setОтмечены] = useState<string[]>([])
  const [ask, спросить, закрытьВопрос] = useConfirm()

  const перечитать = useCallback(() => {
    api.distribution(project)
      .then((р) => {
        if ('links' in р) {
          setРаздача(р)
          setОтмечены(р.links.filter((с) => кПриёмуЛи(с)).map((с) => с.id))
        } else {
          setРаздача(null)
        }
      })
      .catch((e) => setОтказ(отказПодробно(e)))
  }, [project])
  useEffect(перечитать, [перечитать])

  const раздать = () => {
    setИдёт(true); setОтказ(null); setИтог(null); setСекунды(1)
    const часы = window.setInterval(() => setСекунды((с) => с + 1), 1000)
    const кончить = () => { window.clearInterval(часы); setСекунды(0); setИдёт(false) }
    api.distributeNeeds(project)
      .then((р) => {
        кончить()
        setРаздача(р)
        setОтмечены(р.links.filter((с) => кПриёмуЛи(с)).map((с) => с.id))
        setИтог(р.note)
      })
      .catch((e) => { кончить(); setОтказ(отказПодробно(e)) })
  }

  const кПриёму = раздача ? раздача.links.filter(кПриёмуЛи) : []
  const принятые = раздача ? раздача.links.filter((с) => с.accepted) : []
  const выбрано = отмечены.filter((id) => кПриёму.some((с) => с.id === id))

  const принять = () => {
    if (!раздача || выбрано.length === 0) return
    const целей = выбрано.filter((id) => раздача.links.find((с) => с.id === id)?.kind === 'goal').length
    const сервисов = выбрано.filter((id) => раздача.links.find((с) => с.id === id)?.kind === 'service').length
    спросить({
      question: `Принять связей: ${выбрано.length} (к целям ${целей}, к сервисам ${сервисов}). `
        + 'Нужда без класса обслуживания получит класс покрывшего сервиса. '
        + 'Приём обратим: «Отменить раздачу» снимет связи и вернёт классы.',
      ok: 'Принять связи',
      input: { label: 'почему берём', placeholder: 'основание решения' },
      onOk: (повод) => api.acceptDistribution(project, раздача.run, выбрано, 'инженер', повод || undefined)
        .then((и) => { setИтог(и.note); onChanged(); перечитать() })
        .catch((e) => setОтказ(отказПодробно(e))),
    })
  }

  const отменить = () => {
    if (!раздача) return
    спросить({
      question: `Отменить раздачу ${раздача.run}: принятые связи (${принятые.length}) снимутся, классы вернутся как были.`,
      ok: 'Отменить раздачу',
      onOk: () => api.undoDistribution(project, раздача.run, 'инженер')
        .then((о) => { setИтог(о.note); onChanged(); перечитать() })
        .catch((e) => setОтказ(отказПодробно(e))),
    })
  }

  const строка = (с: DistributionLink) => (
    <tr key={с.id} className={!кПриёмуЛи(с) ? 'v2-dim' : undefined}>
      <td>
        {!кПриёмуЛи(с) ? (
          <span title={с.accepted ? 'связь принята этой раздачей' : 'связь уже есть в модели — второй раз не заводится'}
            data-why="почему-нельзя">{с.accepted ? 'принята' : 'уже есть'}</span>
        ) : (
          <input type="checkbox" checked={отмечены.includes(с.id)}
            aria-label={`отметить связь ${с.id}`}
            onChange={(e) => setОтмечены(e.target.checked
              ? [...отмечены, с.id]
              : отмечены.filter((к) => к !== с.id))} />
        )}
      </td>
      <td><span className="v2-mono">{с.need}</span><div>{с.need_text}</div></td>
      <td>
        <span className="v2-mono">{с.target}</span>
        <div>{с.kind === 'goal' ? 'цель' : 'сервис'}: {с.target_text}</div>
      </td>
      <td>
        {с.kind === 'service' ? (с.qos_class ?? '—') : ''}
        {с.exists && с.class_pending && !с.accepted && (
          <div className="v2-dim" title="связь уже есть, а класса у нужды нет: приём даст ей класс сервиса">
            связь есть · класс ждёт
          </div>
        )}
      </td>
      <td>{с.reason}</td>
    </tr>
  )

  return (
    <div className={вПолосе ? undefined : 'v2-card'} data-why="работа">
      {!вПолосе && (
        <div className="v2-card__head">
          <span className="v2-card__title">Раздача нужд по целям и сервисам</span>
          <span className="v2-card__count" title="связей в последней раздаче">{раздача ? раздача.links.length : '—'}</span>
        </div>
      )}
      <div className="v2-empty__why">
        Сцена 4 закрывается, когда каждая нужда ведёт к цели; сцена 6 — когда каждая
        покрыта сервисом и несёт класс. Один вызов по полным перечням проекта; связи
        заводит только приём, и он обратим.
      </div>
      <div className="v2-form__actions">
        <button type="button" disabled={идёт}
          title={идёт ? 'раздача уже идёт: тот же перечень даст тот же ответ' : 'один вызов модели по всем нуждам, целям и сервисам проекта'}
          onClick={раздать}>
          {идёт ? `Раздаю… ${секунды} с` : 'Раздать нужды по целям и сервисам'}
        </button>
        {кПриёму.length > 0 && (
          <button type="button" className="v2-primary" disabled={выбрано.length === 0}
            title={выбрано.length === 0 ? 'отметьте связи — раздача сама ничего не заводит' : `принять ${выбрано.length} связей`}
            onClick={принять}>
            Принять связи ({выбрано.length})
          </button>
        )}
        {принятые.length > 0 && раздача && (
          <button type="button" className="v2-link" onClick={отменить}
            title="снять связи этой раздачи и вернуть классы как были">
            Отменить раздачу
          </button>
        )}
      </div>
      {отказ && <div className="v2-error">{отказ}</div>}
      {итог && <div className="v2-note-line">{итог}</div>}
      {раздача && (
        <>
          <div className="v2-note-line">
            <span className="v2-mono">{раздача.run}</span>
            {раздачаСловами(раздача)}
          </div>
          {раздача.unassigned.length > 0 && (
            <div className="v2-error">
              не раздано: {раздача.unassigned.join(', ')} — модели не к чему было отнести; свяжите на сценах 4 и 6 сами
            </div>
          )}
          {раздача.refused.length > 0 && (
            <ul className="v2-dim">{раздача.refused.map((р) => <li key={р}>{р}</li>)}</ul>
          )}
          {раздача.links.length > 0 && (
            <table className="v2-tab2">
              <thead>
                <tr><th /><th>Нужда</th><th>Ведёт к · покрыта</th><th>Класс</th><th>Почему</th></tr>
              </thead>
              <tbody>{раздача.links.map(строка)}</tbody>
            </table>
          )}
        </>
      )}
      <ConfirmBox request={ask} onClose={закрытьВопрос} />
    </div>
  )
}

/** К приёму — новая связь либо лежащая связь сервиса у нужды без класса. */
function кПриёмуЛи(с: DistributionLink): boolean {
  return !с.accepted && (!с.exists || с.class_pending)
}

function раздачаСловами(р: DistributionRun): string {
  const части = [р.note]
  if (р.cached) части.push('ответ из журнала — живого вызова не было')
  return ' · ' + части.filter(Boolean).join(' · ')
}

