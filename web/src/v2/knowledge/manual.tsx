// Ручной ввод эксперта в поле знаний — через сверку (шип 5 §3.2: полосой «Ручной ввод»).
import { useState } from 'react'
import { ConfirmBox, useConfirm } from '../../ui/Confirm'
import { api, type ReconcileAction, type ReconcileFinding, type ReconcileItem, type ReconcileRun } from '../api'
import { ВИДЫ_ФАКТА, ПОНЯТИЕ, ПОЛЕ, ДЕЙСТВИЕ, ПОЛЯ_ПОНЯТИЯ, формулировкаКандидата, содержимоеКандидата, рангСловами, отказПодробно, источникСловами, type Тема } from './common'

/**
 * Тема или факт руками: полноправно, но видно как ручное.
 *
 * На проекте поля знаний v2 ввод эксперта не попадает в модель напрямую: он
 * идёт СВЕРКОЙ. Она заводит кандидат-факт (учётка · роль · дата вместо
 * якоря) и показывает находки по четырём вопросам; слить, уточнить, оспорить
 * или завести новым — решение человека, одно на карточку.
 */
export function Manual({ project, темы, знанияV2, onDone, onError }: {
  project: string
  темы: Тема[]
  знанияV2: boolean
  onDone: () => void
  onError: (e: string) => void
}) {
  const [метка, setМетка] = useState('')
  const [предмет, setПредмет] = useState('')
  const [утверждение, setУтверждение] = useState('')
  const [значение, setЗначение] = useState('')
  const [единица, setЕдиница] = useState('')
  const [вид, setВид] = useState('framing')
  const [темаФакта, setТемаФакта] = useState('')
  const [понятие, setПонятие] = useState('need')
  const [род, setРод] = useState('')
  const [роль, setРоль] = useState('')
  const [сверка, setСверка] = useState<ReconcileRun | null>(null)
  const [смысл, setСмысл] = useState(false)
  const [занято, setЗанято] = useState(false)

  const завестиТему = () => {
    api.addTopic(project, метка.trim(), 'инженер')
      .then(() => { setМетка(''); onDone() }).catch((e) => onError(String(e.message ?? e)))
  }
  const завестиФакт = () => {
    api.addFact(project, {
      subject: предмет, predicate: утверждение, value: значение, unit: единица,
      kind: вид, topic: темаФакта, author: 'инженер',
    })
      .then(() => { setУтверждение(''); setЗначение(''); onDone() })
      .catch((e) => onError(String(e.message ?? e)))
  }
  const величина = вид === 'quantity'

  // Сверка НИЧЕГО не меняет в модели: она заводит кандидат-факт и запись
  // запуска, а дальше ждёт человека. Батч здесь один — строка ввода одна.
  const сверить = () => {
    setЗанято(true)
    api.reconcile(
      project,
      [{
        local_id: 'c1',
        concept: понятие,
        payload: содержимоеКандидата(понятие, предмет, утверждение, значение, единица, род),
        origin: 'manual',
      }],
      'инженер',
      роль.trim(),
      смысл,
    )
      .then((з) => { setСверка(з); onDone() })
      .catch((e) => onError(отказПодробно(e)))
      .finally(() => setЗанято(false))
  }

  /** Решение человека применено — перечитываем тот же запуск, без нового вызова. */
  const перечитатьСверку = () => {
    onDone()
    if (!сверка) return
    api.reconcileRun(project, сверка.run).then(setСверка).catch((e) => onError(отказПодробно(e)))
  }

  const формулировка = формулировкаКандидата(понятие, предмет, утверждение)

  return (
    <div className="v2-kf__src" data-why="работа">
      <div className="v2-kf__row" style={{ gridTemplateColumns: '2fr auto' }}>
        <label>новая тема
          <input value={метка} onChange={(e) => setМетка(e.target.value)} placeholder="Опыт ЛИ Гонец-Д1М" />
        </label>
        <button type="button" disabled={!метка.trim()} onClick={завестиТему}
          title="тема руками: предмет, о котором знания копятся">Завести тему</button>
      </div>
      <div className="v2-kf__row" style={{ gridTemplateColumns: '1fr 2fr 1fr 80px 1fr' }}>
        <label>предмет
          <input value={предмет} onChange={(e) => setПредмет(e.target.value)} placeholder="Гонец-Д1М" />
        </label>
        <label>утверждение
          <input value={утверждение} onChange={(e) => setУтверждение(e.target.value)}
            placeholder="срок активного существования по ЛИ" />
        </label>
        <label>значение
          <input value={значение} onChange={(e) => setЗначение(e.target.value)} placeholder="7" />
        </label>
        <label>единица
          <input value={единица} onChange={(e) => setЕдиница(e.target.value)} placeholder={величина ? 'лет' : '—'}
            title={величина ? 'величина без единицы — не факт' : 'у текста единицы нет'} />
        </label>
        {знанияV2 ? (
          <label>о чём ввод
            <select value={понятие} onChange={(e) => setПонятие(e.target.value)}
              title="понятие онтологии формирования: по нему сверка знает, что считать дублем, что противоречием и какой связи не хватает">
              {Object.entries(ПОНЯТИЕ).map(([к, и]) => <option key={к} value={к}>{и}</option>)}
            </select>
          </label>
        ) : (
          <label>вид
            <select value={вид} onChange={(e) => setВид(e.target.value)}>
              {ВИДЫ_ФАКТА.map(([к, и]) => <option key={к} value={к}>{и}</option>)}
            </select>
          </label>
        )}
      </div>
      {знанияV2 && ПОЛЯ_ПОНЯТИЯ[понятие]?.род && (
        <div className="v2-kf__row" style={{ gridTemplateColumns: '2fr auto' }}>
          <label>{ПОЛЯ_ПОНЯТИЯ[понятие]!.род!.подпись}
            <select value={род} onChange={(e) => setРод(e.target.value)}
              title="обязательное поле вида: без него запись не отвечала бы собственной схеме, и сервер её не примет">
              <option value="">— выбрать —</option>
              {ПОЛЯ_ПОНЯТИЯ[понятие]!.род!.значения.map(([к, и]) => <option key={к} value={к}>{и}</option>)}
            </select>
          </label>
          <span className="v2-empty__why">Вехи технологий сюда не идут: они остаются точкой.</span>
        </div>
      )}
      {знанияV2 && (
        <div className="v2-empty__why">
          {ПОЛЯ_ПОНЯТИЯ[понятие]?.ссылка
            ? 'Предмет здесь — сторона, которая нуждается: связь обязательна, без неё сущности не будет.'
            : ПОЛЯ_ПОНЯТИЯ[понятие]?.изПредмета
              ? 'Предмет здесь — само имя: им понятие и узнаётся.'
              : 'Предмет и утверждение складываются в одну постановку.'}
          {ПОЛЯ_ПОНЯТИЯ[понятие]?.величина
            ? ' Значение с единицей ложится показателем: величина без единицы — не факт.'
            : ''}
        </div>
      )}
      <div className="v2-kf__row" style={{ gridTemplateColumns: '2fr auto' }}>
        {знанияV2 ? (
          <label>роль автора
            <input value={роль} onChange={(e) => setРоль(e.target.value)} placeholder="ведущий СИ"
              title="у руки эксперта якоря нет: источником становятся учётка, роль и дата — без роли сервер ввод не примет" />
          </label>
        ) : (
          <label>тема факта
            <select value={темаФакта} onChange={(e) => setТемаФакта(e.target.value)}>
              <option value="">— без темы —</option>
              {темы.map((т) => <option key={т.id} value={т.label}>{т.label}</option>)}
            </select>
          </label>
        )}
        {знанияV2 ? (
          <span className="v2-kf__why">
            <label title="ступень по ключу идёт всегда и токенов не стоит: дубль по ключу находится без вызова службы. Смысловую ступень зовёт человек — за неё платят токенами">
              <input type="checkbox" checked={смысл} onChange={(e) => setСмысл(e.target.checked)} />
              {' '}спросить смысл
            </label>
            <button type="button" className="v2-primary"
              disabled={занято || формулировка === '' || !роль.trim()}
              title={!роль.trim()
                ? 'назовите роль автора: у руки эксперта якоря нет — есть учётка, роль и дата'
                : формулировка === ''
                  ? 'сверять нечего: назовите предмет либо утверждение'
                  : 'показать находки по четырём вопросам — дубль · противоречие · соединение · нехватка; модель не изменится ни на строку'}
              onClick={сверить}>{занято ? 'Сверяю…' : 'Сверить ввод'}</button>
          </span>
        ) : (
          <button type="button" className="v2-primary"
            disabled={!утверждение.trim() || !значение.trim() || (величина && !единица.trim())}
            title="факт руками: метка [И], источник — инженер и дата; полноправный, но виден как ручной"
            onClick={завестиФакт}>Завести факт</button>
        )}
      </div>
      <div className="v2-empty__why">
        {знанияV2
          ? 'Ввод эксперта — источник, равный документу по механике и отличный рангом: экспертный. '
            + 'Сверка заводит кандидат-факт и показывает находки; завести новым, слить, уточнить '
            + 'или оспорить — решение человека, одно на карточку.'
          : 'Ручной факт полноправен — диспозиции, связи, промпт. Доля знаний из источников '
            + 'считается без него: ручное видно отдельно.'}
      </div>

      {знанияV2 && сверка && (
        <Находки project={project} сверка={сверка} onDone={перечитатьСверку} onError={onError} />
      )}
    </div>
  )
}

/**
 * Находки сверки: что служба УВИДЕЛА и что предлагает сделать с вводом.
 *
 * Одно действие на карточку и только по нажатию человека: `apply` —
 * единственный путь изменения модели, и без причины сервер решение не ставит.
 * Служба не сливает и не переписывает принятое сама; «похоже» без названного
 * отличия сюда не доезжает — такую находку сервер отбрасывает раньше.
 */
function Находки({ project, сверка, onDone, onError }: {
  project: string
  сверка: ReconcileRun
  onDone: () => void
  onError: (e: string) => void
}) {
  const [ask, спросить, закрытьВопрос] = useConfirm()
  const [итог, setИтог] = useState<string | null>(null)

  const решить = (
    п: ReconcileItem, номер: number, н: ReconcileFinding, действие: ReconcileAction,
  ) => спросить({
    question: `${ДЕЙСТВИЕ[действие] ?? действие}: ${н.question_word}`
      + `${н.target ? ` · ${н.target}` : ''}. Модель меняется только этим решением.`,
    ok: ДЕЙСТВИЕ[действие] ?? действие,
    input: { label: 'почему так решили', required: true },
    onOk: (повод) => api.reconcileApply(project, сверка.run, {
      local_id: п.local_id,
      finding: номер,
      action: действие,
      target: н.target ?? undefined,
      reason: повод,
      author: 'инженер',
    })
      .then((и) => { setИтог(`${и.note} · доля знаний из источников: ${и.coverage}%`); onDone() })
      .catch((e) => onError(отказПодробно(e))),
  })

  return (
    <div className="v2-kf__src" data-why="работа">
      <div className="v2-note-line">
        Сверка <span className="v2-mono">{сверка.run}</span>
        {сверка.note ? ` · ${сверка.note}` : ''}
        {сверка.ai_called ? ' · со смысловой ступенью' : ' · по ключу, без живого вызова'}
      </div>
      {итог && <div className="v2-empty__why">{итог}</div>}
      {сверка.items.map((п) => (
        <div key={п.local_id} className="v2-card">
          <div className="v2-card__head">
            <span className="v2-card__title">{ПОНЯТИЕ[п.concept] ?? п.concept}</span>
            <span className="v2-card__count" title="вердикт сверки словами сервера">{п.verdict_word}</span>
            <span className="v2-dim">
              кандидат-факт <span className="v2-mono">{п.candidate_fact}</span>
              {` · ранг: ${рангСловами(п.rank)} · ${источникСловами(п.source)}`}
            </span>
          </div>
          {п.note && <div className="v2-empty__why">{п.note}</div>}
          {п.blocking.length > 0 && (
            <div className="v2-locked">
              Пока не закрыто, сущности не будет:{' '}
              <span className="v2-mono">{п.blocking.join(' · ')}</span>
            </div>
          )}
          {п.decided ? (
            <div className="v2-empty__why">
              решение человека: {ДЕЙСТВИЕ[п.decided] ?? п.decided} — одно на карточку
            </div>
          ) : п.findings.map((н, номер) => (
            <div key={`${п.local_id}-${номер}`} className="v2-check">
              <span>·</span>
              <span className="v2-check__t">
                <b>{н.question_word}</b>
                {н.target && <> · <span className="v2-mono">{н.target}</span></>}
                {` · нашли ${н.match}`}
                {н.difference && (
                  <div>
                    {`отличие по полю «${ПОЛЕ[н.difference.field] ?? н.difference.field}»: `}
                    {н.difference.comparison}
                    {` — у ввода «${н.difference.mine}», у принятого «${н.difference.theirs}»`}
                    {н.difference.reason ? ` · ${н.difference.reason}` : ''}
                  </div>
                )}
                {н.compared_fields.length > 0 && (
                  <div className="v2-dim">
                    {`сравнивали по полям: ${н.compared_fields.map((к) => ПОЛЕ[к] ?? к).join(' · ')}`}
                  </div>
                )}
                {н.basis.length > 0 && (
                  <div className="v2-dim">
                    основания: <span className="v2-mono">{н.basis.join(' · ')}</span>
                  </div>
                )}
                {н.missing && (
                  <div className="v2-warn">
                    не закрыта обязательная связь: <span className="v2-mono">{н.missing}</span>
                  </div>
                )}
                <span className="v2-form__actions">
                  {н.offers.map((действие) => (
                    <button key={действие} type="button" className="v2-link"
                      title={`${ДЕЙСТВИЕ[действие] ?? действие}: решение запишется с причиной — без неё сервер его не ставит`}
                      onClick={() => решить(п, номер, н, действие)}>
                      {ДЕЙСТВИЕ[действие] ?? действие}
                    </button>
                  ))}
                </span>
              </span>
            </div>
          ))}
        </div>
      ))}
      <ConfirmBox request={ask} onClose={закрытьВопрос} />
    </div>
  )
}

