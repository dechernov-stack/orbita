// Карточка требования (шип 5 §4): правка на месте по истине вида, величина, источники, пометы линта.
import { useEffect, useMemo, useState } from 'react'
import { api, type LintNote, type RequirementCard, type RequirementRow, type UnitRow } from '../api'
import { ВИД_ИСТОЧНИКА, НОСИТЕЛЬ, useВидТребования } from './common'

/** Строка таблицы плюс карточка ВНИЗ: контекст строки не теряется. */
/** З-03: правка требования на месте — заголовок и формулировка новой версией; связи после базирования станут подозрительными и потребуют подтверждения. */
/**
 * Правка требования в карточке: заголовок, формулировка и поля СВОЕЙ стадии.
 *
 * Журнал ПМИ-7, З-10: «в карточке нет пикера носителя — а распределение по
 * природе и есть работа сцены 8». Поля собираются не списком в коде, а по
 * истине: всё, что она требует к базированию или к точке (`required_at`:
 * baseline · SRR), показывается здесь — категория и метод селектами русских
 * значений (`enum_labels`), показатель тройкой оператор · значение · единица,
 * носитель — пикером по природе уровня (`field_rules.widgets`).
 */
export function ПравкаТребования({ project, т, onSaved, схема }: {
  project: string; т: RequirementRow; onSaved: () => void; схема: ReturnType<typeof useВидТребования>
}) {
  const [заголовок, setЗаголовок] = useState(т.title ?? '')
  const [формулировка, setФормулировка] = useState(т.statement ?? '')
  const [правки, setПравки] = useState<Record<string, unknown>>({})
  const [носители, setНосители] = useState<{ id: string; code: string; kind: string; подпись: string }[]>([])
  const [занято, setЗанято] = useState(false)
  const [отказ, setОтказ] = useState<string | null>(null)
  const [итог, setИтог] = useState<string | null>(null)
  /**
   * Величина набрана наполовину: число есть, единицы нет.
   *
   * Прежде такая величина уходила ПУСТОЙ, сервер отвечал «изменено 0», и на
   * экране не менялось ничего — владелец 19.09: «не могу ввести показатель ни
   * в одно поле требований — он не сохраняется». Теперь это названо словами.
   */
  const [неполна, setНеполна] = useState<string | null>(null)
  /** Источники трогали — значит их и отправляем (иначе правка их не касается). */
  const [источникиПравлены, setИсточникиПравлены] = useState(false)

  // Поля стадии базирования и точки: истина называет их сама.
  const поляСтадии = useMemo(
    () => (схема.вид?.fields ?? []).filter((поле) => {
      const стадия = схема.стадия(поле)
      return стадия.startsWith('baseline') || /^[A-Z]{3}/.test(стадия)
    }),
    [схема],
  )

  /**
   * Источники требования — ПРАВИМЫЕ (проход владельца 19.09).
   *
   * Условие сцены 8 из шаблона фазы — «каждая цель покрыта требованием»: оно
   * смотрит на `source[].ref`. Двенадцать требований записки пришли с
   * источником-материалом (`accept:from_basis`), и связать их с целью было
   * НЕЧЕМ — карточка показывала источники только для чтения, а сцена стояла:
   * «целей без требования: 8». Теперь источник добавляется и снимается здесь
   * же, видами истины: цель · нужда · ограничение · материал.
   */
  const [источникиСписком, setИсточникиСписком] = useState<{ kind: string; ref: string; подпись: string }[]>([])
  const [кандидатыИсточников, setКандидаты] = useState<{ kind: string; id: string; code: string; подпись: string }[]>([])

  useEffect(() => {
    const виды = ['goal', 'need', 'constraint', 'material'] as const
    Promise.all(виды.map((вид) => api.entities(project, вид).catch(() => ({ items: [] }))))
      .then((ответы) => {
        const все = ответы.flatMap((о, i) => о.items.map((с) => ({
          kind: виды[i],
          id: с.id,
          code: с.code,
          подпись: `${с.code} · ${String(с.doc.statement ?? с.doc.title ?? с.doc.name ?? с.doc.text ?? '')}`.slice(0, 70),
        })))
        setКандидаты(все)
        // В карточку источники приходят КОДАМИ: разворачиваем обратно в ссылки,
        // чтобы правка не потеряла уже стоящие основания.
        setИсточникиСписком(т.sources.map((код) => {
          const найден = все.find((к) => к.code === код)
          return найден
            ? { kind: найден.kind, ref: найден.id, подпись: найден.подпись }
            : { kind: 'material', ref: код, подпись: `${код} · материал` }
        }))
      })
      .catch(() => undefined)
  }, [project, т.code, т.sources])

  // Носители по природе уровня: узел состава · стык · сценарий.
  useEffect(() => {
    const виды = НОСИТЕЛЬ[т.level]?.виды ?? ['component']
    Promise.all(виды.map((вид) => api.entities(project, вид).catch(() => ({ items: [] }))))
      .then((ответы) => setНосители(ответы.flatMap((о, i) => о.items.map((с) => ({
        id: с.id,
        code: с.code,
        kind: виды[i],
        подпись: `${с.code} · ${String(с.doc.name ?? с.doc.title ?? с.doc.statement ?? '')}`.slice(0, 60),
      })))))
      .catch(() => undefined)
  }, [project, т.level])

  const сохранить = () => {
    // Половина величины НЕ держит карточку: показатель по истине необязателен
    // (`required: false`), а держал он всю работу — час прохода владельца ушёл
    // на запертую кнопку (19.09). Неполное поле просто не уезжает, и это
    // сказано словами рядом.
    const кПравке = Object.fromEntries(Object.entries(правки).filter(([, з]) => з !== null))
    setЗанято(true); setОтказ(null); setИтог(null)
    const сИсточниками = источникиПравлены
      ? { ...кПравке, source: источникиСписком.map((и) => ({ kind: и.kind, ref: и.ref })) }
      : кПравке
    api.patchEntity(project, т.code, { title: заголовок, statement: формулировка, ...сИсточниками }, 'инженер')
      .then((р) => {
        setЗанято(false); setПравки({})
        // «Изменено 0» говорится словами: молчание человек читает как «сохранилось».
        const наполовину = неполна ? ` · ${неполна} — это поле не сохранено` : ''
        setИтог((р.note?.trim()
          ? р.note
          : (р.changed > 0 ? `сохранено, версия ${р.version}` : 'ничего не изменилось — поля те же')) + наполовину)
        onSaved()
      })
      .catch((e) => { setЗанято(false); setОтказ(String(e.message ?? e)) })
  }

  const значение = (поле: string): string => {
    const правка = правки[поле]
    if (typeof правка === 'string') return правка
    if (поле === 'carrier') return ''
    return String((т as unknown as Record<string, unknown>)[поле] ?? '')
  }

  return (
    <Группа title="Правка на месте">
      <div className="v2-form" data-why="работа">
        <label>{схема.имя('title')}
          <input name={`${т.code}.title`} autoComplete="off" value={заголовок}
            onChange={(e) => setЗаголовок(e.target.value)} />
        </label>
        <label>{схема.имя('statement')}
          <textarea name={`${т.code}.statement`} value={формулировка} autoComplete="off"
            onChange={(e) => setФормулировка(e.target.value)} rows={3} />
        </label>
        {поляСтадии.map((поле) => {
          // Подпись несёт стадию и ПРИМЕЧАНИЕ истины: «обязателен для
          // performance» у показателя — иначе необязательное поле выглядит
          // долгом (владелец 19.09: «какой показатель тут можно поставить?»).
          const примечание = схема.примечание(поле)
          const подпись = `${схема.имя(поле)} · ${схема.стадия(поле)}${примечание ? ` · ${примечание}` : ''}`
          if (поле === 'carrier') {
            return (
              <label key={поле}>{подпись}
                <select name={`${т.code}.carrier`} value={String(правки.carrier ?? '')}
                  onChange={(e) => setПравки({ ...правки, carrier: e.target.value })}>
                  <option value="">
                    {т.carrier ? `оставить ${т.carrier}` : `— выберите ${НОСИТЕЛЬ[т.level]?.слова ?? 'носителя'} —`}
                  </option>
                  {носители.map((н) => <option key={н.id} value={н.id}>{н.подпись}</option>)}
                </select>
              </label>
            )
          }
          if (схема.вид?.measures.includes(поле)) {
            // Показатель нужен НЕ ВСЕМ: истина говорит «обязателен для
            // performance», и у функционального требования его ставить неоткуда
            // (владелец 19.09: «что ставить в показатели неведомо»). Поэтому
            // поле стоит там, где оно значит, а не висит вопросом всегда.
            const категория = String(правки.category ?? т.category ?? '')
            const ждёт = примечание.includes('performance')
              ? категория === 'performance'
              : true
            if (!ждёт && !т.measure && правки[поле] === undefined) {
              return (
                <span key={поле} className="v2-empty__why">
                  {схема.имя(поле)}: у категории «{схема.метка('category', категория) || 'не задана'}»
                  не требуется — {примечание || схема.стадия(поле)}.{' '}
                  <button type="button" className="v2-chip"
                    title="показатель можно поставить и здесь — истина этого не запрещает"
                    onClick={() => setПравки({ ...правки, [поле]: null })}>всё равно задать</button>
                </span>
              )
            }
            return (
              <Величина key={поле} подпись={подпись} код={т.code} было={т.measure}
                единицы={схема.единицы} почемуБезЕдиниц={схема.почемуБезЕдиниц}
                операторы={схема.вид.measure_ops}
                onChange={(в) => setПравки({ ...правки, [поле]: в })}
                onНеполна={setНеполна} />
            )
          }
          const значения = схема.значения(поле)
          if (значения.length > 0) {
            return (
              <label key={поле}>{подпись}
                <select name={`${т.code}.${поле}`} value={значение(поле)}
                  onChange={(e) => setПравки({ ...правки, [поле]: e.target.value })}>
                  <option value="">— не задано —</option>
                  {значения.map((з) => <option key={з.код} value={з.код}>{з.имя}</option>)}
                </select>
              </label>
            )
          }
          return (
            <label key={поле}>{подпись}
              <input name={`${т.code}.${поле}`} autoComplete="off" value={значение(поле)}
                onChange={(e) => setПравки({ ...правки, [поле]: e.target.value })} />
            </label>
          )
        })}
        {/*
          Источники правятся здесь: условие сцены 8 «каждая цель покрыта
          требованием» закрывается именно этим (проход владельца 19.09).
        */}
        <span className="v2-field">
          <span className="v2-field__cap">
            {схема.имя('source')} · {схема.стадия('source')} — откуда выведено
          </span>
          {источникиСписком.length === 0
            ? <span className="v2-locked">источника нет: требование ниоткуда не выводится</span>
            : источникиСписком.map((и) => (
              <span key={и.ref} className="v2-note">
                <span className="v2-note__rule">{схема.метка('source_kind', и.kind) || ВИД_ИСТОЧНИКА[и.kind] || и.kind}</span>
                <span>{и.подпись}</span>
                <button type="button" className="v2-chip" title="снять основание"
                  onClick={() => {
                    setИсточникиСписком(источникиСписком.filter((д) => д.ref !== и.ref))
                    setИсточникиПравлены(true)
                  }}>убрать</button>
              </span>
            ))}
          <select name={`${т.code}.source.add`} value=""
            aria-label="добавить источник требования"
            onChange={(e) => {
              const к = кандидатыИсточников.find((x) => x.id === e.target.value)
              if (!к || источникиСписком.some((и) => и.ref === к.id)) return
              setИсточникиСписком([...источникиСписком, { kind: к.kind, ref: к.id, подпись: к.подпись }])
              setИсточникиПравлены(true)
            }}>
            <option value="">— добавить цель · нужду · ограничение · материал —</option>
            {кандидатыИсточников.map((к) => (
              <option key={к.id} value={к.id}>{ВИД_ИСТОЧНИКА[к.kind] ?? к.kind}: {к.подпись}</option>
            ))}
          </select>
        </span>
        <button type="button" className="v2-primary" onClick={сохранить}
          disabled={занято || !формулировка.trim()}
          title={!формулировка.trim()
            ? 'формулировка пустой быть не может'
            : 'сохранить новой версией — провенанс «правка инженера»'}>Сохранить</button>
        {неполна && <span className="v2-empty__why">{неполна} — остальное сохранится</span>}
        {итог && <span className="v2-empty__why">{итог}</span>}
        {отказ && <span className="v2-locked">{отказ}</span>}
      </div>
    </Группа>
  )
}

/**
 * Величина: оператор · значение · ЕДИНИЦА СПРАВОЧНИКА (`field_rules.widgets`).
 *
 * Плоским полем величину брать нельзя — «объект без виджета на экране —
 * сторож». Единица ВЫБИРАЕТСЯ из справочника (полка LIB), а не набирается
 * руками: владелец 19.09 — «единиц измерения нет — блок», и число без единицы
 * уходило пустой величиной, отчего правка «не сохранялась» молча. Половина
 * величины теперь названа словами и держит кнопку.
 */
export function Величина({ подпись, код, было, единицы, почемуБезЕдиниц, операторы, onChange, onНеполна }: {
  подпись: string
  код: string
  было: string | null
  единицы: UnitRow[]
  почемуБезЕдиниц: string
  операторы: Record<string, string>
  onChange: (значение: Record<string, unknown> | null) => void
  onНеполна: (словами: string | null) => void
}) {
  const прежнее = useMemo(() => {
    try { return было ? (JSON.parse(было) as Record<string, unknown>) : null } catch { return null }
  }, [было])
  const [оператор, setОператор] = useState(String(прежнее?.op ?? ''))
  const [число, setЧисло] = useState(String(прежнее?.value ?? ''))
  const [единица, setЕдиница] = useState(String(прежнее?.unit ?? ''))

  const собрать = (оп: string, зн: string, ед: string) => {
    const естьЧисло = зн.trim() !== ''
    const естьЕдиница = ед.trim() !== ''
    if (!естьЧисло && !естьЕдиница) { onНеполна(null); onChange(null); return }
    if (!естьЧисло || !естьЕдиница) {
      onНеполна(естьЕдиница
        ? `${подпись}: единица есть, а числа нет — величины без числа не бывает`
        : `${подпись}: «${зн}» без единицы — выберите единицу справочника, иначе число ничего не значит`)
      onChange(null)
      return
    }
    if (Number.isNaN(Number(зн.replace(',', '.')))) {
      onНеполна(`${подпись}: «${зн}» — не число`); onChange(null); return
    }
    onНеполна(null)
    const величина: Record<string, unknown> = { value: Number(зн.replace(',', '.')), unit: ед.trim() }
    if (оп) величина.op = оп
    onChange(величина)
  }

  // Единица записи может быть вне справочника (её дало чтение документа): её не
  // выбрасываем — показываем как есть и говорим, что она вне справочника.
  const своя = единица !== '' && !единицы.some((е) => е.code === единица)

  // Три контрола НЕ заворачиваются в один <label>: клик по второму и третьему
  // браузер переадресует первому (label связан с первым контролом), и список
  // единиц закрывался сразу — «единицу измерения выбрать нельзя» (проход
  // владельца 19.09). Подпись — своей строкой, каждому контролу — своё имя.
  return (
    <span className="v2-field">
      <span className="v2-field__cap">{подпись}</span>
      <span className="v2-measure">
        <select name={`${код}.measure.op`} value={оператор} aria-label={`${подпись}: оператор`}
          onChange={(e) => { setОператор(e.target.value); собрать(e.target.value, число, единица) }}>
          {Object.entries(операторы).map(([кодОп, знак]) => (
            <option key={кодОп} value={кодОп === '=' ? '' : кодОп}>{знак}</option>
          ))}
        </select>
        <input name={`${код}.measure.value`} autoComplete="off" value={число} placeholder="значение"
          aria-label={`${подпись}: значение`}
          onChange={(e) => { setЧисло(e.target.value); собрать(оператор, e.target.value, единица) }} />
        <select name={`${код}.measure.unit`} value={единица} aria-label={`${подпись}: единица справочника`}
          onChange={(e) => { setЕдиница(e.target.value); собрать(оператор, число, e.target.value) }}>
          <option value="">— единица —</option>
          {своя && <option value={единица}>{единица} · вне справочника</option>}
          {единицы.map((е) => <option key={е.code} value={е.code}>{е.label}</option>)}
        </select>
      </span>
      {единицы.length === 0 && (
        <span className="v2-locked">{почемуБезЕдиниц || 'справочник единиц пуст — выбрать единицу нечем'}</span>
      )}
    </span>
  )
}

/**
 * Карточка по эталону reference-scenes-5-8-req-arch (журнал ПМИ-7, З-14):
 * пары «ярлык → значение» — категория · приоритет, EARS, источник с якорем и
 * цитатой, нормативное основание, метод с TBD, критерий приёмки, обоснование,
 * связи с основанием, «что заденет», история. Слова полей — из истины схем.
 */
export function КарточкаТребования({ project, т, схема }: {
  project: string; т: RequirementRow; схема: ReturnType<typeof useВидТребования>
}) {
  const [карточка, setКарточка] = useState<RequirementCard | null>(null)
  const [отказ, setОтказ] = useState<string | null>(null)
  useEffect(() => {
    let живо = true
    api.requirementCard(project, т.code)
      .then((к) => { if (живо) setКарточка(к) })
      .catch((e) => { if (живо) setОтказ(String(e.message ?? e)) })
    return () => { живо = false }
  }, [project, т.code, т.version])
  if (отказ) return <Группа title="Карточка"><div className="v2-locked">{отказ}</div></Группа>
  if (!карточка) return <Группа title="Карточка"><div className="v2-dim">читаю…</div></Группа>
  const ВИД_ИСТОЧНИКА: Record<string, string> = { goal: 'цель', need: 'нужда', constraint: 'ограничение', requirement: 'требование', material: 'материал', fact: 'факт' }
  return (
    <>
      <Группа title="Происхождение">
        <div>{схема.имя('category')} · {схема.имя('priority')}: {схема.метка('category', т.category) || <span className="v2-dim">не задана — к базированию</span>}
          {' · '}{схема.метка('priority', карточка.priority) || карточка.priority || <span className="v2-dim">к базированию</span>}</div>
        <div>{схема.имя('level')}: {схема.метка('level', т.level) || '—'}</div>
        <div>{схема.имя('ears_pattern')}: {схема.метка('ears_pattern', т.ears) || т.ears || '—'}</div>
        <div>
          источник:{' '}
          {карточка.sources.length === 0 ? <span className="v2-flag v2-flag--warn">нет — требование ниоткуда не выводится</span> : карточка.sources.map((и) => (
            <div key={`${и.kind}:${и.ref}`} className="v2-dim" title={и.quote || и.text}>
              {ВИД_ИСТОЧНИКА[и.kind] ?? и.kind} <span className="v2-mono">{и.ref}</span>
              {и.anchor && <span className="v2-mono"> · {и.anchor}</span>}
              {(и.quote || и.text) && <> · «{(и.quote || и.text).slice(0, 140)}»</>}
            </div>
          ))}
        </div>
        <div>
          {схема.имя('normative_basis')}: {карточка.normative_basis?.normative_document
            ? `${карточка.normative_basis.normative_document}${карточка.normative_basis.clause ? `, ${карточка.normative_basis.clause}` : ''}`
            : <span className="v2-dim">не задано</span>}
        </div>
        {т.template_ref && <div>типовое: {т.template_ref} · применимость {схема.метка('applicability', т.applicability) || '—'}</div>}
      </Группа>
      <Группа title="Проверка и связи">
        <div>
          {схема.имя('verification_method')}: {схема.метка('verification_method', карточка.verification_method)
            || (карточка.verification_tbd ? <span className="v2-dim">TBD — допустим до SRR</span> : карточка.verification_method)}
        </div>
        <div>
          {схема.имя('acceptance_criteria')}: {карточка.acceptance_criteria
            || <span className="v2-flag v2-flag--warn">не задан — к базированию</span>}
        </div>
        <div>обоснование: {карточка.rationale || <span className="v2-dim">—</span>}</div>
        <div>
          связи: {карточка.sources.length === 0 ? <span className="v2-dim">нет</span> : карточка.sources.map((и) => (
            <div key={`l:${и.kind}:${и.ref}`} className="v2-dim">
              derives_from → {ВИД_ИСТОЧНИКА[и.kind] ?? и.kind} <span className="v2-mono">{и.ref}</span>
              {и.text && <> «{и.text.slice(0, 80)}»</>} · обоснование {карточка.rationale ? '✓' : '—'}
            </div>
          ))}
        </div>
        <div>
          что заденет: {карточка.carrier ? '1 узел' : '0 узлов'} · {карточка.documents.length > 0 ? карточка.documents.join(', ') : 'документов нет'}
        </div>
        <div>
          история: {карточка.history.length === 0 ? `v${карточка.version}` : карточка.history.map((в) => `v${в.version} · ${в.author} · ${в.at}`).join(' → ')}
        </div>
      </Группа>
    </>
  )
}

export function Группа({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="v2-facet">
      <div className="v2-facet__title">{title}</div>
      <div className="v2-facet__body">{children}</div>
    </div>
  )
}

export function Помета({ note }: { note: LintNote }) {
  return (
    <div className="v2-note">
      <span className="v2-note__rule">{note.rule}</span>
      <span>{note.what}</span>
      <span className="v2-empty__why">{note.why}</span>
    </div>
  )
}
