// Строка предложения вкладки «Предложения» (шип 5 §3.3; §8 d — экран не
// длиннее 600 строк): отметка, что предлагается — с полями приёма из истины
// схем, чем отличается, основания, «не закрыто». Поля приёма и строка «что
// доделается позже» — чистые функции от онтологии формирования.
import type { FormationOntology, FormationProposal } from '../api'
import { ПОНЯТИЕ, ПОЛЕ, рангСловами } from './common'

/**
 * Поля строки правки: что предложение уже несёт плюс обязательные поля
 * вида, которых у него нет (их и спрашивает своя сцена). Величины и
 * поля-ссылки правятся не здесь — у них своя форма на сцене.
 *
 * Что спрашивается НА ПРИЁМЕ. Истина схем 18.09 (`required_at`) называет
 * стадию и того, кто заполняет: `accept` — человек здесь, `accept:system ·
 * from_basis · auto · default(x)` — подставит система, `baseline` и точки —
 * своя сцена. Форма приёма из всей схемы (журнал ПМИ-7, З-09: «все поля
 * обязательны… работать нельзя») кончилась: спрашиваем ровно своё.
 */
export function поляПравки(онтология: FormationOntology | null, п: FormationProposal): string[] {
  const понятие = онтология?.concepts.find((к) => к.code === п.concept)
  const вид = понятие?.kind
  const системные = понятие?.system_fields ?? []
  const стадия = вид?.required_at ?? {}
  const наПриёме = (поле: string) => {
    const правило = стадия[поле]
    if (!правило) return true
    const [когда, чем] = [правило.split(':')[0].trim(), (правило.split(':')[1] ?? '').trim()]
    return когда === 'accept' && чем === ''
  }
  const свои = Object.keys(п.payload).filter((поле) => !системные.includes(поле) && наПриёме(поле))
  const надо = (вид?.required ?? []).filter((поле) => !свои.includes(поле)
    && !системные.includes(поле) && наПриёме(поле)
    && !(вид?.measures ?? []).includes(поле) && !(вид?.fact_refs ?? []).includes(поле))
  return [...свои, ...надо]
}

/** Что доделается позже и где — строкой под полями приёма, именами истины. */
export function позже(онтология: FormationOntology | null, п: FormationProposal): string {
  const понятие = онтология?.concepts.find((к) => к.code === п.concept)
  const вид = понятие?.kind
  if (!вид) return ''
  const поСтадиям = new Map<string, string[]>()
  Object.entries(вид.required_at ?? {}).forEach(([поле, правило]) => {
    const когда = правило.split(':')[0].trim()
    const чем = (правило.split(':')[1] ?? '').trim()
    if (когда === 'accept' && чем === '') return
    const где = когда === 'accept'
      ? 'подставит система'
      : когда === 'baseline' ? 'к базированию' : `к точке ${когда}`
    поСтадиям.set(где, [...(поСтадиям.get(где) ?? []), вид.labels?.[поле] ?? поле])
  })
  return [...поСтадиям.entries()].map(([где, поля]) => `${где}: ${поля.join(' · ')}`).join(' · ')
}

export function СтрокаПредложения({ п, онтология, отмечено, вФокусе, правка, onОтметить, onДиапазон, onПравка }: {
  п: FormationProposal
  онтология: FormationOntology | null
  отмечено: boolean
  /** Строка под фокусом клавиатуры (↑ ↓). */
  вФокусе: boolean
  /** Правки полей этой строки до приёма. */
  правка?: Record<string, string>
  onОтметить: (да: boolean) => void
  /** Shift-клик: диапазон от последней отметки до этой строки. */
  onДиапазон: () => void
  onПравка: (поле: string, значение: string) => void
}) {
  const понятие = онтология?.concepts.find((к) => к.code === п.concept)
  const позжеСтроки = позже(онтология, п)
  return (
    <tr id={`предложение-${п.proposal}`} className={вФокусе ? 'v2-row--open' : undefined}>
      <td>
        <input type="checkbox" checked={отмечено}
          aria-label={`отметить предложение ${п.proposal}`}
          title={п.decision === 'rejected'
            ? 'строка отклонена: отметьте и верните в работу, если решение изменилось'
            : п.decision === 'deferred'
              ? 'строка отложена: воротам не мешает, вернуть в работу можно отметкой'
              : п.missing.length === 0
                ? 'отметить это предложение: заводит его клик по «Принять отмеченные»; Shift — диапазон'
                : 'у предложения стоит «не закрыто»: отметка «все» его не берёт, '
                  + 'а в пакете оно уведёт в отказ и здоровые строки — закройте связь '
                  + 'или поправьте основание'}
          onChange={(e) => onОтметить(e.target.checked)}
          onClick={(e) => { if (e.shiftKey) onДиапазон() }} />
        {п.decision === 'deferred' && <div className="v2-dim">отложено</div>}
        {п.decision === 'rejected' && <div className="v2-dim">отклонено</div>}
      </td>
      <td>
        <span title={понятие?.note ?? ''}>{ПОНЯТИЕ[п.concept] ?? п.concept}</span>
        {/*
          Поля предложения — правимые до приёма (владелец
          18.09). Перечень значений и обязательность берутся
          из истины схем, а не из догадки экрана; правка
          уезжает вместе с приёмом и проверяется сервером.
        */}
        <div className="v2-form">
          {поляПравки(онтология, п).map((поле) => {
            const вид = понятие?.kind
            const перечень = вид?.enums?.[поле] ?? []
            const текущее = правка?.[поле] ?? п.payload[поле] ?? ''
            const обязательно = (вид?.required ?? []).includes(поле)
            return (
              <label key={поле} className="v2-dim">
                {вид?.labels?.[поле] ?? ПОЛЕ[поле] ?? поле}{обязательно && !текущее ? ' · обязательно' : ''}
                {перечень.length > 0 ? (
                  /*
                    Значения перечисления — по-русски из истины 19.09
                    (`enum_labels`): «enum → селект русскими значениями».
                    Своего перевода у экрана нет; неназванное значение
                    показывается кодом — это видно и правится в истине.
                  */
                  <select value={текущее} name={`${п.proposal}.${поле}`}
                    onChange={(e) => onПравка(поле, e.target.value)}>
                    <option value="">— не задано —</option>
                    {перечень.map((з) => (
                      <option key={з} value={з}>{вид?.enum_labels?.[поле]?.[з] ?? з}</option>
                    ))}
                  </select>
                ) : текущее.length > 90 ? (
                  /*
                    Длинное значение — в поле на несколько строк: замысел
                    несёт четыре абзаца, и одной строкой они слипались в
                    стену (владелец 18.09). autoComplete выключен у всех:
                    браузер подставлял в безымянные поля телефон из своей
                    памяти — «система подставила номер куда только смогла».
                  */
                  <textarea rows={3} value={текущее} name={`${п.proposal}.${поле}`}
                    autoComplete="off" spellCheck={false}
                    onChange={(e) => onПравка(поле, e.target.value)} />
                ) : (
                  <input value={текущее} name={`${п.proposal}.${поле}`}
                    autoComplete="off" spellCheck={false}
                    onChange={(e) => onПравка(поле, e.target.value)} />
                )}
              </label>
            )
          })}
          {позжеСтроки && <div className="v2-dim">{позжеСтроки}</div>}
        </div>
        {п.target_ref && (
          <div className="v2-dim">
            о принятом <span className="v2-mono">{п.target_ref}</span>
          </div>
        )}
      </td>
      <td>
        {п.diff_field ? ПОЛЕ[п.diff_field] ?? п.diff_field : '—'}
        {п.rank_hint && <div className="v2-dim">{п.rank_hint}</div>}
      </td>
      <td>
        {п.basis.length === 0 ? (
          <span className="v2-warn">без документального основания</span>
        ) : п.basis.map((о, номер) => (
          <div key={`${п.proposal}-${номер}`} className="v2-dim">
            <span className="v2-mono">{о.fact}</span>
            {о.anchor && <span className="v2-mono">{` · ${о.anchor}`}</span>}
            {о.account && ` · эксперт: ${о.account}${о.role ? `, ${о.role}` : ''}`
              + `${о.at ? `, ${о.at}` : ''}`}
            {` · ранг: ${о.rank_word ?? рангСловами(о.rank)}`}
          </div>
        ))}
      </td>
      <td>
        {п.missing.length === 0 ? '—' : п.missing.map((строка) => (
          <div key={строка} className="v2-warn">
            {/* В «не закрыто» лежат ДВЕ разные вещи: имя незакрытой
                связи понятия (токен без пробелов) и целая фраза
                правила образования. Одевать фразу в слова про связь
                значило бы соврать о причине. */}
            {строка.includes(' ')
              ? строка
              : <>связь <span className="v2-mono">{строка}</span> не закрыта: сущности не будет</>}
          </div>
        ))}
      </td>
    </tr>
  )
}
