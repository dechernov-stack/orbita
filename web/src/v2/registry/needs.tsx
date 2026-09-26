// Реестр нужд (шип 5 §2): вкладка «Нужды» Постановки и тело сцен 3 и 6 — один
// компонент. «Покрытие k из n» — число вкладки и колонка «состояние», а не
// раздел. Колонки: код · нужда · носители · класс · ждёт (чем закрывается
// по истине) · закрыта чем (цели и сервисы чипами) · состояние. Вердикт
// «покрыта» и «чего не хватает» — серверные (матрица покрытия); клиент их не
// считает, он называет следующий клик.
import { useContext, useState } from 'react'
import { ConfirmBox, useConfirm } from '../../ui/Confirm'
import { api, type CoverageNeed, type EntityRow } from '../api'
import type { ЧипОтбора } from '../ui/chips'
import { ПлотностьКонтекст } from '../ui/density'
import { ИконКнопка } from '../ui/iconbutton'
import type { МассовоеДействие } from '../ui/mass'
import { Карточка } from '../ui/objectcard'
import {
  КЛАССЫ, закрывают, имяЗаписи, классНазначен, классСловами, носителиНужды, словом, строкаПокрытия,
} from './data'
import { Реестр, type Колонка } from './registry'
import type { ПропсыРеестра } from './stakeholders'

/**
 * Куда идти, чтобы разрыв закрылся. Строка называет не только «чего нет», но
 * и место работы: носитель — сцена 3, цель — 4, сервис — 6, а разом по всем
 * нуждам — «Раздача нужд» в поле знаний (владелец 18.09: «нельзя ничего
 * отредактировать»).
 */
export function кудаИдти(н: CoverageNeed): string {
  if (!н.owner) return 'закрывается на сцене 3: назначьте носителя нужде'
  if (н.goals.length === 0 && н.services.length === 0) {
    return 'закрывается на сценах 4 и 6 — или разом: «Поле знаний» → «Постановка из поля» → «Раздать нужды по целям и сервисам»'
  }
  if (н.goals.length === 0) return 'закрывается на сцене 4: свяжите нужду с целью — или раздачей нужд в поле знаний'
  return 'закрывается на сцене 6: свяжите нужду с сервисом — или раздачей нужд в поле знаний'
}

export function РеестрНужд({ project, данные, onChanged, onGoScene, отбор }: ПропсыРеестра & {
  /** Сцена 6 открывает реестр на TBR: класс назначается там. */
  отбор?: 'tbr'
}) {
  const { нужды, стороны, цели, сервисы, покрытие } = данные
  const вид = данные.виды.need
  const плотность = useContext(ПлотностьКонтекст)
  const автор = плотность.кто || 'инженер'
  const [отказ, setОтказ] = useState<string | null>(null)
  const [итог, setИтог] = useState<string | null>(null)
  const [ask, спросить, закрыть] = useConfirm()

  const пачкой = (коды: string[], шаг: (код: string) => Promise<unknown>, что: string) => {
    setОтказ(null)
    Promise.allSettled(коды.map(шаг)).then((итоги) => {
      const отказы = итоги.filter((и) => и.status === 'rejected') as PromiseRejectedResult[]
      setИтог(отказы.length === 0 ? `${что}: ${коды.length}` : `${что}: ${коды.length - отказы.length}, отказов ${отказы.length}`)
      if (отказы.length > 0) setОтказ(String(отказы[0].reason?.message ?? отказы[0].reason))
      onChanged()
    })
  }

  const назначитьКласс = (коды: string[]) => спросить({
    question: `Класс обслуживания ${коды.length === 1 ? `нужды ${коды[0]}` : `нужд (${коды.length})`}: TBR закрывается здесь.`,
    ok: 'Назначить',
    choice: { label: 'класс обслуживания', options: КЛАССЫ },
    onOk: (класс) => пачкой(коды, (к) => api.patchEntity(project, к, { qos_class: класс }, автор, 'класс назначен в реестре нужд'), 'класс назначен'),
  })

  const чемЗакрывается = (коды: string[]) => спросить({
    question: `Чем закрывается ${коды.length === 1 ? `нужда ${коды[0]}` : `нужд (${коды.length})`}: сервисом, ограничением, программой или требованием.`,
    ok: 'Записать',
    choice: { label: 'чем закрывается', options: (вид?.enums?.coverage_expected ?? []).map((к) => [к, словом(вид, 'coverage_expected', к)] as [string, string]) },
    onOk: (чем) => пачкой(коды, (к) => api.patchEntity(project, к, { coverage_expected: чем }, автор, 'чем закрывается нужда'), 'записано'),
  })

  const добавитьНосителя = (коды: string[]) => спросить({
    question: `Носитель ${коды.length === 1 ? `нужды ${коды[0]}` : `нужд (${коды.length})`}: нужда — много носителей, прежние останутся.`,
    ok: 'Добавить',
    choice: { label: 'сторона', options: стороны.map((с) => [с.code, `${с.code} · ${имяЗаписи(с)}`]) },
    onOk: (сторона) => пачкой(коды, (к) => api.needOwner(project, к, сторона, { author: автор }), 'носитель добавлен'),
  })

  const колонки: Колонка<EntityRow>[] = [
    { key: 'код', title: 'Код', cell: (н) => н.code, className: 'v2-mono' },
    { key: 'нужда', title: 'Нужда', cell: (н) => имяЗаписи(н) },
    {
      key: 'носители', title: 'Носители',
      cell: (н) => {
        const кто = носителиНужды(н, стороны)
        return кто.length === 0 ? <span className="v2-warn">ничья</span> : кто.map(имяЗаписи).join(' · ')
      },
    },
    {
      key: 'класс', title: 'Класс', hint: 'класс обслуживания; TBR допустим до сцены 6',
      cell: (н) => классНазначен(н.doc.qos_class)
        ? классСловами(н.doc.qos_class)
        : <span className="v2-warn" title="класс назначается на сцене сервисов; здесь он ещё TBR">{классСловами(н.doc.qos_class) || 'TBR'}</span>,
    },
    {
      key: 'ждёт', title: 'Ждёт', hint: 'чем нужда закрывается по истине: сервисом, ограничением, программой, требованием',
      cell: (н) => {
        const чем = строкаПокрытия(покрытие, н.code)?.expected ?? н.doc.coverage_expected
        return чем ? словом(вид, 'coverage_expected', чем) : <span className="v2-dim">не названо</span>
      },
    },
    {
      key: 'чем', title: 'Закрыта чем', className: 'v2-reg__chips',
      cell: (н) => {
        const закрыта = [...закрывают(н, цели), ...закрывают(н, сервисы)]
        return закрыта.length === 0
          ? <span className="v2-dim">ничем</span>
          : <span className="v2-chips v2-chips--cell">{закрыта.map((з) => <span key={з.id} className="v2-chip" title={имяЗаписи(з)}>{з.code}</span>)}</span>
      },
    },
    {
      key: 'состояние', title: 'Состояние', hint: 'вердикт покрытия считает сервер',
      cell: (н) => {
        const с = строкаПокрытия(покрытие, н.code)
        if (!с) return <span className="v2-dim">—</span>
        return с.covered
          ? <span className="v2-ok">покрыта</span>
          : (
            <>
              <span className="v2-warn">{с.gap}</span>
              {с.note && <div className="v2-dim">{с.note}</div>}
              <div className="v2-dim" data-why="следующий-клик">{кудаИдти(с)}</div>
            </>
          )
      },
    },
  ]

  const чипы: ЧипОтбора<EntityRow>[] = [
    { key: 'не-покрыты', word: 'не покрыты', group: 'покрытие', test: (н) => строкаПокрытия(покрытие, н.code)?.covered === false, hint: 'нужды, которые ещё ничем не закрыты — строка скажет, чего не хватает' },
    { key: 'без-цели', word: 'без цели', group: 'цель', test: (н) => закрывают(н, цели).length === 0, hint: 'нужды без цели: пока они есть, сцена 4 не закроется' },
    { key: 'без-носителя', word: 'без носителя', group: 'носитель', test: (н) => носителиНужды(н, стороны).length === 0, hint: 'нужды без носителя: за них никто не отвечает' },
    { key: 'tbr', word: 'TBR', group: 'класс', test: (н) => !классНазначен(н.doc.qos_class), hint: 'класс обслуживания ещё TBR — назначается на сцене 6' },
  ]

  const массово: МассовоеДействие[] = [
    { key: 'класс', икон: 'принять', слово: 'назначить класс', клавиша: 'A', run: назначитьКласс },
    { key: 'чем', икон: 'править', слово: 'чем закрывается', run: чемЗакрывается },
    { key: 'носитель', икон: 'связать', слово: 'добавить носителя', run: добавитьНосителя },
  ]

  return (
    <>
      {отказ && <div className="v2-locked">{отказ}</div>}
      {итог && <div className="v2-note-line">{итог}</div>}
      {отбор === 'tbr' && <div className="v2-empty__why">Класс обслуживания у покрытых нужд назначается здесь: чип «TBR» отбирает те, где его ещё нет.</div>}
      <Реестр label="реестр нужд" строки={нужды} ключ={(н) => н.code} колонки={колонки} чипы={чипы}
        начальныеЧипы={отбор === 'tbr' ? ['tbr'] : undefined}
        поиск={{ placeholder: 'найти нужду', text: (н) => имяЗаписи(н) }}
        массово={массово}
        наборы={(видимые) => [
          { key: 'tbr', word: 'все TBR', keys: видимые.filter((н) => !классНазначен(н.doc.qos_class)).map((н) => н.code) },
          { key: 'ничьи', word: 'без носителя', keys: видимые.filter((н) => носителиНужды(н, стороны).length === 0).map((н) => н.code) },
        ]}
        действия={(н) => onGoScene
          ? <ИконКнопка икон="к-месту" слово={`к месту: сцена ${закрывают(н, цели).length === 0 ? 4 : 6}`}
              onClick={() => onGoScene(закрывают(н, цели).length === 0 ? '4' : '6', `нужда ${н.code}`)} />
          : null}
        пусто={<>Нужд пока нет.<span className="v2-empty__why">Нужда заводится на сцене 3 — у неё обязан быть носитель.</span></>}
        карточка={(н, закрытьКарточку) => вид
          ? (
            <Карточка project={project} row={н} spec={вид} скрыть={['stakeholders', 'qos_class']} onSaved={onChanged} onClose={закрытьКарточку}
              кМесту={onGoScene ? { слово: 'к месту: сцена 3', go: () => onGoScene('3', `нужда ${н.code}`) } : undefined}
              extra={<ГраниНужды project={project} н={н} данные={данные} автор={автор} onChanged={onChanged} />} />
          )
          : <div className="v2-empty">Читаю истину вида…</div>} />
      <ConfirmBox request={ask} onClose={закрыть} />
    </>
  )
}

/** Грани нужды вне полей вида: носители связями, класс, чем закрыта. */
function ГраниНужды({ project, н, данные, автор, onChanged }: {
  project: string
  н: EntityRow
  данные: ПропсыРеестра['данные']
  автор: string
  onChanged: () => void
}) {
  const [отказ, setОтказ] = useState<string | null>(null)
  const носители = носителиНужды(н, данные.стороны)
  const закрыта = [...закрывают(н, данные.цели), ...закрывают(н, данные.сервисы)]
  const выполнить = (шаг: Promise<unknown>) => {
    setОтказ(null)
    шаг.then(onChanged).catch((e) => setОтказ(String((e as Error).message ?? e)))
  }
  return (
    <>
      <div className="v2-facet">
        <label htmlFor={`v2-носитель-${н.code}`}>Носители</label>
        <div className="v2-facet__chips">
          {носители.map((с) => (
            <span key={с.id} className="v2-chip">{имяЗаписи(с)}
              {носители.length > 1 && (
                <ИконКнопка икон="снять" слово={`снять носителя ${с.code}`}
                  onClick={() => выполнить(api.needOwner(project, н.code, с.code, { remove: true, author: автор }))} />
              )}
            </span>
          ))}
          <select id={`v2-носитель-${н.code}`} value="" aria-label="добавить носителя"
            onChange={(e) => e.target.value && выполнить(api.needOwner(project, н.code, e.target.value, { author: автор }))}>
            <option value="">— добавить носителя</option>
            {данные.стороны.filter((с) => !носители.some((х) => х.id === с.id)).map((с) => <option key={с.id} value={с.code}>{имяЗаписи(с)}</option>)}
          </select>
        </div>
        <div className="v2-facet__hint">носители — связью; последнего носителя снять нельзя</div>
      </div>
      <div className="v2-facet">
        <label htmlFor={`v2-класс-${н.code}`}>Класс обслуживания</label>
        <select id={`v2-класс-${н.code}`} value={классНазначен(н.doc.qos_class) ? String(н.doc.qos_class) : ''}
          onChange={(e) => e.target.value && выполнить(api.patchEntity(project, н.code, { qos_class: e.target.value }, автор, 'класс нужды в карточке'))}>
          <option value="">{классСловами(н.doc.qos_class) || 'TBR'}</option>
          {КЛАССЫ.map(([к, слово]) => <option key={к} value={к}>{слово}</option>)}
        </select>
        <div className="v2-facet__hint">{данные.виды.need?.notes?.qos_class ?? 'TBR допустим до сцены 6'}</div>
      </div>
      <div className="v2-facet v2-facet--wide">
        <label>Закрыта чем</label>
        {закрыта.length === 0
          ? <div className="v2-warn">ничем — ни цели, ни сервиса</div>
          : <ul className="v2-why">{закрыта.map((з) => <li key={з.id}><span className="v2-mono">{з.code}</span> {имяЗаписи(з)}</li>)}</ul>}
        {отказ && <div className="v2-facet__err">{отказ}</div>}
      </div>
    </>
  )
}
