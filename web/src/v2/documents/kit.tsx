// Вкладка «Комплект» документов (шип 5 §5): документы фазы — документ · шаблон ·
// полнота к ступени маркером · выпусков · базовая линия · действия пиктограммой
// (открыть · собрать PDF · базировать). Над таблицей — комплект точки: чего
// она ждёт от документов вместе и можно ли ехать дальше.
import { useContext, useEffect, useState } from 'react'
import { ConfirmBox, useConfirm } from '../../ui/Confirm'
import { api, type DocView, type DocumentJob, type Gate } from '../api'
import { ПлотностьКонтекст } from '../ui/density'
import { ИконКнопка } from '../ui/iconbutton'
import { Маркер } from '../ui/tabs'
import { отказСловами } from '../research'

/**
 * Комплект к точке: где мы и можно ли ехать дальше.
 *
 * Владелец 21.09: «базированные документы не видно, что они уже готовы. Где мы
 * находимся — вообще непонятно… То ли можно ехать дальше — то ли нет». Экран
 * документов показывал только полноту каждого документа по отдельности, а
 * КОМПЛЕКТ — то, чего точка ждёт от документов вместе, — жил на экране точек
 * матрицей зрелости, куда из документов дороги не было.
 *
 * Здесь та же матрица, но с её собственными словами: строки комплекта,
 * сведённые к нашим документам, с причиной у каждой незакрытой.
 */
export function КомплектТочки({ project, gate, onOpen }: {
  project: string
  /** Ступень, к которой считаются документы: её комплект и показываем. */
  gate: string
  onOpen: (код: string) => void
}) {
  const [точка, setТочка] = useState<Gate | null>(null)

  useEffect(() => {
    api.points(project)
      .then((р) => setТочка(
        // Та точка, к которой считаются документы. «Ближайшая непройденная»
        // здесь не годится: внутренний обзор идёт первым, а комплекта у него
        // нет вовсе — блок молчал бы ровно там, где вопрос и задан.
        р.items.find((т) => т.key === gate)
          ?? р.items.find((т) => !т.passed && (т.matrix ?? []).length > 0)
          ?? null,
      ))
      .catch(() => setТочка(null))
  }, [project, gate])

  if (!точка) return null
  /** Строки комплекта, сведённые к документу: остальные — реестры и записи. */
  const документы = (точка.matrix ?? []).filter((с) => (с.our_ref ?? '').startsWith('document_template:'))
  if (документы.length === 0) return null
  const держат = документы.filter((с) => с.blocking && с.passed === false)
  const готовы = документы.filter((с) => с.passed !== false)

  return (
    <div className="v2-prop" data-why="почему-нельзя"
      title="комплект документов точки: то, что она ждёт от них вместе">
      <span>
        {точка.title}
        <span className="v2-cnt">
          {' '}комплект {готовы.length} из {документы.length}
          {точка.planned_date ? ` · ${точка.planned_date}` : ''}
        </span>
        <span className="v2-empty__why">
          {держат.length === 0
            ? 'Комплект документов собран: точку держат её остальные условия, если держат.'
            : `Ехать дальше нельзя: ${держат.length} из ${документы.length} не готовы.`}
        </span>
        {держат.map((с) => (
          <span key={с.artifact} className="v2-dim">
            · {с.artifact}
            {с.why ? ` — ${с.why}` : ''}
            {(с.our_ref ?? '').startsWith('document_template:') && (
              <>
                {' '}
                <button type="button" className="v2-link"
                  title="открыть документ и закрыть то, чего не хватает"
                  onClick={() => onOpen((с.our_ref ?? '').replace('document_template:', ''))}>
                  открыть документ
                </button>
              </>
            )}
          </span>
        ))}
      </span>
    </div>
  )
}

type Печать = { job: string; sec: number; готово?: DocumentJob; ошибка?: string }

export function ВкладкаКомплект({ project, список, onOpen, onChanged, onError }: {
  project: string
  список: DocView[]
  onOpen: (код: string) => void
  onChanged: () => void
  onError: (e: string) => void
}) {
  const { кто } = useContext(ПлотностьКонтекст)
  const [печать, setПечать] = useState<Record<string, Печать>>({})
  const [точки, setТочки] = useState<Gate[]>([])
  const [итог, setИтог] = useState<string | null>(null)
  const [ask, спросить, закрытьВопрос] = useConfirm()
  useEffect(() => { api.points(project).then((r) => setТочки(r.items)).catch(() => setТочки([])) }, [project])

  /** PDF — фоновым заданием: секунды вслух в строке, ссылка — когда готово. */
  const собратьPdf = (код: string) => {
    api.printJob(project, код, 'auto').then((з) => {
      setПечать((б) => ({ ...б, [код]: { job: з.job, sec: 0 } }))
      const опрос = () => {
        api.documentJob(project, код, з.job).then((т) => {
          if (т.status === 'done') setПечать((б) => ({ ...б, [код]: { job: з.job, sec: т.elapsed_seconds, готово: т } }))
          else if (т.status === 'failed') setПечать((б) => ({ ...б, [код]: { job: з.job, sec: т.elapsed_seconds, ошибка: т.error ?? 'печать не состоялась' } }))
          else { setПечать((б) => ({ ...б, [код]: { job: з.job, sec: т.elapsed_seconds } })); window.setTimeout(опрос, 2000) }
        }).catch((e) => setПечать((б) => ({ ...б, [код]: { job: з.job, sec: 0, ошибка: String(e.message ?? e) } })))
      }
      window.setTimeout(опрос, 1500)
    }).catch((e) => setПечать((б) => ({ ...б, [код]: { job: '', sec: 0, ошибка: String(e.message ?? e) } })))
  }
  /** Базировать строкой: имя линии — имя точки, к которой документ идёт; линия неизменяема. */
  const базировать = (д: DocView) => {
    const точка = точки.find((т) => т.key === д.gate)?.title ?? д.gate
    спросить({
      question: `Базировать «${д.title}»: состояние документа запишется линией — она неизменяема, перебазирование заводит новое имя.`,
      ok: 'Базировать',
      input: { label: 'имя линии', placeholder: точка },
      onOk: (имя) => api.baselineDocument(project, д.code, { name: имя.trim() || точка, author: кто || 'инженер' })
        .then((л) => { setИтог(`линия «${л.name}» записана: ${л.elements} элементов${л.note ? ` · ${л.note}` : ''}`); onChanged() })
        .catch((e) => onError(отказСловами(e))),
    })
  }

  return (
    <div data-why="следующий-клик" aria-label="комплект документов">
      <КомплектТочки project={project} gate={список[0]?.gate ?? ''} onOpen={onOpen} />
      {итог && <div className="v2-note-line">{итог}</div>}
      {список.length === 0 ? (
        <div className="v2-empty">
          Документов нет.
          <button type="button" className="v2-link" title="завести отчёт о концепции миссии по шаблону полки"
            onClick={() => api.ensureDocument(project, 'mcreport', 'инженер').then(onChanged).catch((e) => onError(String(e.message ?? e)))}>
            {' '}завести MCReport
          </button>
        </div>
      ) : (
        <table className="v2-table">
          <thead>
            <tr>
              <th>Документ</th><th>Шаблон</th><th title="полнота считается по разделам, которых ждёт ступень">Полнота к ступени</th>
              <th title="выпуски документа — записи истины «выпуск»">Выпусков</th><th>Базовая линия</th>
              <th className="v2-acts" aria-label="действия строки" />
            </tr>
          </thead>
          <tbody>
            {список.map((д) => {
              const п = печать[д.code]
              const идёт = п && !п.готово && !п.ошибка
              return (
                <tr key={д.code}>
                  <td>
                    <button type="button" className="v2-link" onClick={() => onOpen(д.code)} title="открыть документ разделами">{д.title}</button>
                  </td>
                  <td className="v2-dim">{д.standard}</td>
                  <td className="v2-nowrap">
                    <Маркер health={д.complete === д.total ? 'ok' : 'debt'} title={д.complete === д.total ? 'полон к ступени' : 'не полон к ступени'} />
                    {д.complete} из {д.total} к {д.gate}
                  </td>
                  <td>{д.releases ?? 0}</td>
                  <td>
                    {д.baselines > 0
                      ? <span className="v2-ok">«{д.baseline_name}» · {д.baseline_at}</span>
                      : <span className="v2-warn">не базирован</span>}
                  </td>
                  <td className="v2-acts">
                    {идёт && <span className="v2-dim">собираю PDF… {п.sec} с </span>}
                    {п?.готово && (
                      <a className="v2-link" href={api.printUrl(project, д.code, { job: п.job })} target="_blank" rel="noreferrer"
                        title={`движок ${п.готово.engine ?? '—'} · ${п.готово.size} байт`}>скачать PDF </a>
                    )}
                    {п?.ошибка && <span className="v2-warn" title={п.ошибка}>PDF не собран </span>}
                    <ИконКнопка икон="карточка" слово="открыть документ" onClick={() => onOpen(д.code)} />
                    <ИконКнопка икон="печать" слово="собрать PDF" disabled={Boolean(идёт)} onClick={() => собратьPdf(д.code)} />
                    <ИконКнопка икон="принять" слово="базировать" onClick={() => базировать(д)} />
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      )}
      <ConfirmBox request={ask} onClose={закрытьВопрос} />
    </div>
  )
}
