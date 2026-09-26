// Реестр целей (шип 5 §2): вкладка «Цели» Постановки и тело сцены 4 — один
// компонент. Колонки: код · цель · показатель (MOE) · год · нужд закрывает ·
// источник. Чипы: без нужд · без показателя. Дерево «цель → задачи» ждёт
// поля истины (у цели нет родителя) — вопрос владельцу в NEXT.md; до решения
// цели — плоским реестром по порядку истины.
import { useContext, useState } from 'react'
import { ConfirmBox, useConfirm } from '../../ui/Confirm'
import { api, type EntityRow } from '../api'
import type { ЧипОтбора } from '../ui/chips'
import { ПлотностьКонтекст } from '../ui/density'
import type { МассовоеДействие } from '../ui/mass'
import { Карточка, пусто as пустоЗначение, словами } from '../ui/objectcard'
import { закрываемые, имяЗаписи } from './data'
import { Реестр, type Колонка } from './registry'
import type { ПропсыРеестра } from './stakeholders'

/** Откуда запись словами: документ и якорь, иначе — кто завёл. */
export function откуда(р: EntityRow): string {
  if (р.source) return [р.source, р.anchor].filter(Boolean).join(' · ')
  const кто = (р.author ?? '').replace(/^правка инженера:\s*/, '')
  return кто || '—'
}

export function РеестрЦелей({ project, данные, onChanged, onGoScene }: ПропсыРеестра) {
  const { цели, нужды } = данные
  const вид = данные.виды.goal
  const плотность = useContext(ПлотностьКонтекст)
  const автор = плотность.кто || 'инженер'
  const [отказ, setОтказ] = useState<string | null>(null)
  const [итог, setИтог] = useState<string | null>(null)
  const [ask, спросить, закрыть] = useConfirm()

  const год = (коды: string[]) => спросить({
    question: `Год (горизонт) ${коды.length === 1 ? `цели ${коды[0]}` : `целей (${коды.length})`}.`,
    ok: 'Записать',
    input: { label: 'год', placeholder: '2030', required: true },
    onOk: (текст) => {
      const число = Number(текст)
      if (!Number.isInteger(число)) { setОтказ(`«${текст}» — не год`); return }
      setОтказ(null)
      Promise.allSettled(коды.map((к) => api.patchEntity(project, к, { year: число }, автор, 'горизонт цели')))
        .then((итоги) => {
          const отказы = итоги.filter((и) => и.status === 'rejected').length
          setИтог(отказы === 0 ? `год записан: ${коды.length}` : `записано ${коды.length - отказы}, отказов ${отказы}`)
          onChanged()
        })
    },
  })

  const колонки: Колонка<EntityRow>[] = [
    { key: 'код', title: 'Код', cell: (ц) => ц.code, className: 'v2-mono' },
    { key: 'цель', title: 'Цель', cell: (ц) => имяЗаписи(ц) },
    {
      key: 'показатель', title: 'Показатель', hint: 'показатель результата (MOE) — величина с единицей',
      cell: (ц) => вид && !пустоЗначение(ц.doc.measure) ? словами(вид, 'measure', ц.doc.measure) : <span className="v2-warn">не задан</span>,
    },
    { key: 'год', title: 'Год', cell: (ц) => String(ц.doc.year ?? '—') },
    {
      key: 'нужд', title: 'Нужд закрывает',
      cell: (ц) => {
        const n = закрываемые(ц, нужды).length
        return n === 0 ? <span className="v2-warn" title="цель, не закрывающая ни одной нужды, ни к чему не ведёт">нет</span> : n
      },
    },
    { key: 'источник', title: 'Источник', cell: (ц) => <span className="v2-dim">{откуда(ц)}</span> },
  ]

  const чипы: ЧипОтбора<EntityRow>[] = [
    { key: 'без-нужд', word: 'без нужд', group: 'нужды', test: (ц) => закрываемые(ц, нужды).length === 0, hint: 'цели, не закрывающие ни одной нужды' },
    { key: 'без-показателя', word: 'без показателя', group: 'показатель', test: (ц) => пустоЗначение(ц.doc.measure), hint: 'цели без показателя результата: показатель обязателен' },
  ]
  const массово: МассовоеДействие[] = [{ key: 'год', икон: 'править', слово: 'год', run: год }]

  return (
    <>
      {отказ && <div className="v2-locked">{отказ}</div>}
      {итог && <div className="v2-note-line">{итог}</div>}
      <Реестр label="реестр целей" строки={цели} ключ={(ц) => ц.code} колонки={колонки} чипы={чипы}
        поиск={{ placeholder: 'найти цель', text: (ц) => имяЗаписи(ц) }}
        массово={массово}
        пусто={<>Целей пока нет.<span className="v2-empty__why">Цели заводятся на сцене 4 — из устава, с показателем и годом.</span></>}
        карточка={(ц, закрытьКарточку) => вид
          ? (
            <Карточка project={project} row={ц} spec={вид} скрыть={['needs']} onSaved={onChanged} onClose={закрытьКарточку}
              кМесту={onGoScene ? { слово: 'к месту: сцена 4', go: () => onGoScene('4', `цель ${ц.code}`) } : undefined}
              extra={<ЗакрываетНужды нужды={закрываемые(ц, нужды)} />} />
          )
          : <div className="v2-empty">Читаю истину вида…</div>} />
      <ConfirmBox request={ask} onClose={закрыть} />
    </>
  )
}

/** Грань «закрывает нужды»: связи covers, а не текст поля. */
export function ЗакрываетНужды({ нужды }: { нужды: EntityRow[] }) {
  return (
    <div className="v2-facet v2-facet--wide">
      <label>Закрывает нужды</label>
      {нужды.length === 0
        ? <div className="v2-warn">ни одной — такая запись ни к чему не ведёт</div>
        : <ul className="v2-why">{нужды.map((н) => <li key={н.id}><span className="v2-mono">{н.code}</span> {имяЗаписи(н)}</li>)}</ul>}
      <div className="v2-facet__hint">связи «закрывает» — раздача нужд в поле знаний или сцена этой записи</div>
    </div>
  )
}
