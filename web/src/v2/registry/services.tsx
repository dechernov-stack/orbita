// Реестр сервисов (шип 5 §2): вкладка «Сервисы» Постановки и тело сцены 6 —
// один компонент. Колонки: код · сервис · класс обслуживания · целевой
// показатель · нужды. Чипы: без нужды · без показателя.
import { useContext, useState } from 'react'
import { ConfirmBox, useConfirm } from '../../ui/Confirm'
import { api, type EntityRow } from '../api'
import type { ЧипОтбора } from '../ui/chips'
import { ПлотностьКонтекст } from '../ui/density'
import type { МассовоеДействие } from '../ui/mass'
import { Карточка, пусто as пустоЗначение, словами } from '../ui/objectcard'
import { КЛАССЫ, закрываемые, имяЗаписи, классСловами } from './data'
import { ЗакрываетНужды } from './goals'
import { Реестр, type Колонка } from './registry'
import type { ПропсыРеестра } from './stakeholders'

export function РеестрСервисов({ project, данные, onChanged, onGoScene }: ПропсыРеестра) {
  const { сервисы, нужды } = данные
  const вид = данные.виды.service
  const плотность = useContext(ПлотностьКонтекст)
  const автор = плотность.кто || 'инженер'
  const [итог, setИтог] = useState<string | null>(null)
  const [ask, спросить, закрыть] = useConfirm()

  const класс = (коды: string[]) => спросить({
    question: `Класс обслуживания ${коды.length === 1 ? `сервиса ${коды[0]}` : `сервисов (${коды.length})`}.`,
    ok: 'Назначить',
    choice: { label: 'класс обслуживания', options: КЛАССЫ },
    onOk: (к) => Promise.allSettled(коды.map((код) => api.patchEntity(project, код, { qos_class: к }, автор, 'класс сервиса')))
      .then((итоги) => {
        const отказы = итоги.filter((и) => и.status === 'rejected').length
        setИтог(отказы === 0 ? `класс назначен: ${коды.length}` : `назначено ${коды.length - отказы}, отказов ${отказы}`)
        onChanged()
      }),
  })

  const колонки: Колонка<EntityRow>[] = [
    { key: 'код', title: 'Код', cell: (с) => с.code, className: 'v2-mono' },
    { key: 'сервис', title: 'Сервис', cell: (с) => имяЗаписи(с) },
    { key: 'класс', title: 'Класс', cell: (с) => классСловами(с.doc.qos_class) || <span className="v2-warn">не задан</span> },
    {
      key: 'показатель', title: 'Целевой показатель',
      cell: (с) => вид && !пустоЗначение(с.doc.target_measure) ? словами(вид, 'target_measure', с.doc.target_measure) : <span className="v2-warn">не задан</span>,
    },
    {
      key: 'нужды', title: 'Нужды',
      cell: (с) => {
        const n = закрываемые(с, нужды).length
        return n === 0 ? <span className="v2-warn" title="сервис, не покрывающий ни одной нужды, никому не нужен">нет</span> : n
      },
    },
  ]
  const чипы: ЧипОтбора<EntityRow>[] = [
    { key: 'без-нужды', word: 'без нужды', group: 'нужды', test: (с) => закрываемые(с, нужды).length === 0, hint: 'сервисы, не покрывающие ни одной нужды' },
    { key: 'без-показателя', word: 'без показателя', group: 'показатель', test: (с) => пустоЗначение(с.doc.target_measure), hint: 'сервисы без целевого показателя: он обязателен' },
  ]
  const массово: МассовоеДействие[] = [{ key: 'класс', икон: 'принять', слово: 'класс обслуживания', клавиша: 'A', run: класс }]

  return (
    <>
      {итог && <div className="v2-note-line">{итог}</div>}
      <Реестр label="реестр сервисов" строки={сервисы} ключ={(с) => с.code} колонки={колонки} чипы={чипы}
        поиск={{ placeholder: 'найти сервис', text: (с) => имяЗаписи(с) }}
        массово={массово}
        пусто={<>Сервисов пока нет.<span className="v2-empty__why">Сервис заводится на сцене 6: что система даёт кому и с каким качеством.</span></>}
        карточка={(с, закрытьКарточку) => вид
          ? (
            <Карточка project={project} row={с} spec={вид} скрыть={['needs']} onSaved={onChanged} onClose={закрытьКарточку}
              кМесту={onGoScene ? { слово: 'к месту: сцена 6', go: () => onGoScene('6', `сервис ${с.code}`) } : undefined}
              extra={<ЗакрываетНужды нужды={закрываемые(с, нужды)} />} />
          )
          : <div className="v2-empty">Читаю истину вида…</div>} />
      <ConfirmBox request={ask} onClose={закрыть} />
    </>
  )
}
