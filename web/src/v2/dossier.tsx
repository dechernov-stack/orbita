// Документ как источник — целиком (шип 4 §3, РЕШЕНИЕ-ДОКУМЕНТ-ПЕРВИЧЕН §3–6).
//
// Досье документа: резюме разбора, прогоны, вклад по видам и решениям,
// сущности, стоящие на документе (и только на нём), полезность — всё
// посчитано сервером. Два взгляда: «документ → что из него вышло» здесь и
// «сущность → на чём стоит» щелчком по сущности. Откат вклада — одним
// действием с причиной; приём — целиком · по разделам · только в контекст ·
// отклонить. Партия каталога — очередь и одна сводка; библиотека проекта —
// документ другого проекта берётся с фактами без вызова модели.
import { useCallback, useEffect, useState } from 'react'
import { ConfirmBox, useConfirm } from '../ui/Confirm'
import {
  api, РЕЖИМЫ_ПРИЁМА, РОЛИ_ДОКУМЕНТА,
  type AcceptMode, type Authority, type BasisView, type BatchItem, type BatchSummary,
  type DocumentRole, type Dossier, type GapMap, type MaterialRow,
} from './api'
import { портфель, type КарточкаПроекта } from './projects.api'
import { СЛОВО_ВИДА_ФАКТА, СЛОВО_ЗАПИСИ, СЛОВО_ПРОГОНА, СЛОВО_РАНГА, СЛОВО_РЕШЕНИЯ } from './words'

/** Раздел канона для приёма «по разделам»: якорь, заголовок, сколько блоков. */
export interface РазделКанона { anchor: string; title: string; blocks: number }

/**
 * Разделы из блоков канона: якорь раздела — до «#», заголовок — блок вида
 * heading, иначе первые слова первого блока. Вёрстка, не расчёт.
 */
export function разделыКанона(блоки: { anchor: string; kind: string; text: string }[]): РазделКанона[] {
  const порядок: string[] = []
  const заголовки: Record<string, string> = {}
  const счёт: Record<string, number> = {}
  for (const б of блоки) {
    const раздел = б.anchor.split('#')[0]
    if (!(раздел in счёт)) { порядок.push(раздел); счёт[раздел] = 0 }
    if (б.kind === 'heading') заголовки[раздел] = б.text
    else {
      счёт[раздел] += 1
      if (!заголовки[раздел]) заголовки[раздел] = б.text.slice(0, 60)
    }
  }
  return порядок.map((р) => ({ anchor: р, title: заголовки[р] ?? р, blocks: счёт[р] }))
}

function словоРоли(роль: DocumentRole | null | undefined): string {
  return роль ? (РОЛИ_ДОКУМЕНТА.find((р) => р.code === роль)?.word ?? роль) : 'роль не названа'
}

function отказСловами(e: unknown): string {
  const о = e as { message?: string }
  return String(о?.message ?? e)
}

/** Досье документа с приёмом, оценкой и откатом. */
export function Досье({ project, code, onChanged, onError }: {
  project: string
  code: string
  /** Поле изменилось (приём, откат): экрану пора перечитать факты и постановку. */
  onChanged: () => void
  onError: (e: string) => void
}) {
  const [досье, setДосье] = useState<Dossier | null>(null)
  const [основания, setОснования] = useState<BasisView | null>(null)
  const [оценка, setОценка] = useState('')
  const [разделы, setРазделы] = useState<РазделКанона[] | null>(null)
  const [отмеченные, setОтмеченные] = useState<string[]>([])
  const [итог, setИтог] = useState<string | null>(null)
  const [занято, setЗанято] = useState(false)
  const [ask, спросить, закрытьВопрос] = useConfirm()

  const перечитать = useCallback(() => {
    api.dossier(project, code)
      .then((д) => { setДосье(д); setОценка(д.usefulness_note ?? '') })
      .catch((e) => onError(отказСловами(e)))
  }, [project, code, onError])
  useEffect(перечитать, [перечитать])

  const после = (заметка: string) => { setИтог(заметка); setЗанято(false); перечитать(); onChanged() }
  const беда = (e: unknown) => { setЗанято(false); onError(отказСловами(e)) }

  const принять = (mode: AcceptMode, reason = '', sections: string[] = []) => {
    setЗанято(true); setИтог(null)
    api.acceptMaterial(project, code, { mode, reason: reason || undefined, sections: sections.length ? sections : undefined })
      .then((п) => после(п.note + (п.notes.length ? ` · ${п.notes.slice(0, 3).join('; ')}` : '')))
      .catch(беда)
  }

  const режим = (mode: AcceptMode) => {
    const слово = РЕЖИМЫ_ПРИЁМА.find((р) => р.code === mode)?.word ?? mode
    if (mode === 'rejected') {
      спросить({
        question: `Отклонить документ «${досье?.name ?? code}»? Факты останутся в поле отклонёнными и в промпты не пойдут.`,
        ok: 'Отклонить',
        input: { label: 'почему документ не берётся', required: true },
        onOk: (повод) => принять('rejected', повод),
      })
      return
    }
    if (mode === 'sections') {
      if (разделы) { setРазделы(null); return }
      api.intakeCanon(project, code)
        .then((к) => { setРазделы(разделыКанона(к.blocks)); setОтмеченные([]) })
        .catch(беда)
      return
    }
    спросить({
      question: mode === 'context_only'
        ? `Оставить документ «${досье?.name ?? code}» только в контексте? Факты будут учтены, сущностей не появится.`
        : `Принять весь документ «${досье?.name ?? code}»: исполнить все действия плана разбора, прошедшие ворота?`,
      ok: слово,
      onOk: () => принять(mode),
    })
  }

  const откатить = () => спросить({
    question: `Откатить вклад документа «${досье?.name ?? code}»? Его факты и всё, что образовано только из них, будут сняты; `
      + 'сущности с другими основаниями останутся с пометой «основание снято».',
    ok: 'Откатить',
    input: { label: 'почему откатываем', required: true },
    onOk: (повод) => {
      setЗанято(true); setИтог(null)
      api.rollbackMaterial(project, code, повод).then((о) => после(о.note)).catch(беда)
    },
  })

  const сохранитьОценку = () => {
    setЗанято(true)
    api.usefulnessNote(project, code, оценка.trim())
      .then((д) => { setДосье(д); setЗанято(false); setИтог('оценка сохранена') })
      .catch(беда)
  }

  const показатьОснования = (сущность: string) => {
    if (основания?.entity === сущность) { setОснования(null); return }
    api.basis(project, сущность).then(setОснования).catch(беда)
  }

  if (!досье) return <div className="v2-muted">досье читается…</div>
  const виды = Object.entries(досье.facts_by_kind)
  const решения = Object.entries(досье.by_disposition)
  return (
    <div className="v2-card" data-why="работа" aria-label="досье документа">
      <div className="v2-muted">
        {словоРоли(досье.role)} · ранг {досье.rank ? (СЛОВО_РАНГА[досье.rank] ?? досье.rank) : 'не назван'}
        {' '}· версия {досье.version} · знаков {досье.chars} · блоков канона {досье.blocks}
        {' '}· загружен {досье.created_at.slice(0, 10)}
        {досье.supersedes && <> · заменяет {досье.supersedes}</>}
        {досье.superseded_by && <> · заменён на {досье.superseded_by}</>}
        {досье.accept_mode && <> · приём: {РЕЖИМЫ_ПРИЁМА.find((р) => р.code === досье.accept_mode)?.word ?? досье.accept_mode}</>}
      </div>
      <p title="резюме порождает разбор: о чём документ, что в нём полезного проекту, чего в нём нет">
        {досье.summary ?? <span className="v2-muted">резюме появится при разборе документа</span>}
      </p>
      <div className="v2-facets">
        <div className="v2-facet">
          <div className="v2-facet__title">Прогоны разбора</div>
          <div className="v2-facet__body">
            {досье.runs.length === 0 && <span className="v2-muted">документ ещё не разбирался</span>}
            {досье.runs.map((п) => (
              <div key={п.code} className="v2-mono" title={`версия промпта ${п.prompt_version}`}>
                {п.code} · {п.at.slice(0, 16).replace('T', ' ')} · фактов {п.facts} · {СЛОВО_ПРОГОНА[п.status] ?? п.status}
                {п.rolled_back_by && <> ({п.rolled_back_by})</>}
              </div>
            ))}
          </div>
        </div>
        <div className="v2-facet">
          <div className="v2-facet__title">Вклад</div>
          <div className="v2-facet__body">
            <div>{виды.length === 0 ? <span className="v2-muted">фактов нет</span>
              : виды.map(([в, n]) => <span key={в} className="v2-chip">{СЛОВО_ВИДА_ФАКТА[в] ?? в} · {n}</span>)}</div>
            <div>{решения.map(([р, n]) => <span key={р} className="v2-chip">{СЛОВО_РЕШЕНИЯ[р] ?? р} · {n}</span>)}</div>
            <div title="уникальных — таких утверждений нет в других документах; подтверждений и противоречий — по связям фактов между документами">
              уникальных {досье.unique} · подтверждений {досье.confirms} · противоречий {досье.contradicts}
            </div>
            <div title="считается сервером: сущностей + уникальных фактов + подтверждений + противоречий">
              полезность <b>{досье.usefulness}</b>
            </div>
          </div>
        </div>
        <div className="v2-facet">
          <div className="v2-facet__title">Сущности на документе · {досье.entities.length}</div>
          <div className="v2-facet__body">
            {досье.entities.length === 0 && <span className="v2-muted">ни одна запись проекта на этот документ не опирается</span>}
            {досье.entities.map((с) => (
              <div key={с.code}>
                <button type="button" className="v2-link" onClick={() => показатьОснования(с.code)}
                  title="на чём стоит: основания с якорями и ролями документов">
                  {с.code} · {СЛОВО_ЗАПИСИ[с.kind] ?? с.kind} · {с.title}
                </button>
                {с.only_basis && <span className="v2-chip v2-chip--warn" title="других оснований нет: откат документа снимет её"> только на нём</span>}
              </div>
            ))}
            {досье.entities_only_basis > 0 && <div className="v2-muted">только на этом документе: {досье.entities_only_basis}</div>}
          </div>
        </div>
      </div>

      {основания && (
        <div data-why="работа" aria-label="на чём стоит">
          <div className="v2-facet__title">{основания.entity} · {СЛОВО_ЗАПИСИ[основания.kind] ?? основания.kind} · {основания.title}: на чём стоит</div>
          {основания.notes.map((з) => <div key={з} className="v2-muted">{з}</div>)}
          {основания.facts.length > 0 && (
            <table className="v2-table">
              <thead><tr><th>Факт</th><th>Документ</th><th>Якорь</th><th>Цитата</th><th>Метка</th><th>Ранг</th><th>Решение</th></tr></thead>
              <tbody>
                {основания.facts.map((ф) => (
                  <tr key={ф.code} className={ф.withdrawn ? 'v2-muted' : undefined}>
                    <td className="v2-mono">{ф.code}{ф.withdrawn && <span title="основание снято откатом документа"> (снято)</span>}</td>
                    <td>{ф.material_name ?? 'рука эксперта'}{ф.role && <span className="v2-muted"> · {словоРоли(ф.role)}</span>}</td>
                    <td className="v2-mono">{ф.anchor ?? '—'}</td>
                    <td>{ф.quote ?? `${ф.subject} — ${ф.predicate}: ${ф.value}`}</td>
                    <td>{ф.mark}</td>
                    <td>{ф.rank ? (СЛОВО_РАНГА[ф.rank] ?? ф.rank) : '—'}</td>
                    <td>{СЛОВО_РЕШЕНИЯ[ф.disposition] ?? ф.disposition}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}

      <div className="v2-form__actions" aria-label="приём документа">
        {РЕЖИМЫ_ПРИЁМА.map((р) => (
          <button key={р.code} type="button" disabled={занято} onClick={() => режим(р.code)}
            title={занято ? 'подождите: предыдущее действие ещё идёт' : р.hint}>
            {р.code === 'sections' && разделы ? 'скрыть разделы' : р.word}
          </button>
        ))}
        <button type="button" disabled={занято || досье.runs.length === 0} onClick={откатить}
          title={досье.runs.length === 0 ? 'откатывать нечего: документ ещё не разбирался'
            : занято ? 'подождите: предыдущее действие ещё идёт'
              : 'снять факты документа и всё, что образовано только из них; остальное — с пометой «основание снято»'}>
          Откатить вклад
        </button>
      </div>
      {разделы && (
        <div className="v2-form" data-why="работа" aria-label="разделы для приёма">
          {разделы.map((р) => (
            <label key={р.anchor} className="v2-field">
              <span>
                <input type="checkbox" checked={отмеченные.includes(р.anchor)}
                  onChange={(e) => setОтмеченные(e.target.checked ? [...отмеченные, р.anchor] : отмеченные.filter((а) => а !== р.anchor))} />
                {' '}<span className="v2-mono">{р.anchor}</span> · {р.title} <span className="v2-muted">· блоков {р.blocks}</span>
              </span>
            </label>
          ))}
          <div className="v2-form__actions">
            <button type="button" className="v2-primary" disabled={занято || отмеченные.length === 0}
              title={отмеченные.length === 0 ? 'отметьте хотя бы один раздел' : 'исполнить действия плана только из отмеченных разделов'}
              onClick={() => принять('sections', '', отмеченные)}>
              Принять отмеченные разделы · {отмеченные.length}
            </button>
          </div>
        </div>
      )}
      <div className="v2-form v2-form--row" aria-label="оценка полезности">
        <input value={оценка} onChange={(e) => setОценка(e.target.value)} placeholder="оценка полезности документа одной строкой"
          aria-label="оценка полезности" />
        <button type="button" disabled={занято} onClick={сохранитьОценку}
          title={занято ? 'подождите: предыдущее действие ещё идёт' : 'сохранить оценку человека рядом с посчитанной полезностью'}>
          Сохранить оценку
        </button>
      </div>
      {итог && <div className="v2-note-line">{итог}</div>}
      <ConfirmBox request={ask} onClose={закрытьВопрос} />
    </div>
  )
}

/** Карта пробелов устава: двенадцать пунктов записки миссии — что есть, чего нет, какую сцену закрывает. */
export function КартаПробелов({ project, code, onError }: { project: string; code: string; onError: (e: string) => void }) {
  const [карта, setКарта] = useState<GapMap | null>(null)
  useEffect(() => {
    api.charterGaps(project, code).then(setКарта).catch((e) => onError(отказСловами(e)))
  }, [project, code, onError])
  if (!карта) return <div className="v2-muted">карта пробелов читается…</div>
  return (
    <div className="v2-card" data-why="почему-нельзя" aria-label="карта пробелов устава">
      <div className="v2-note-line">{карта.note}</div>
      <table className="v2-table">
        <thead><tr><th>№</th><th>Что должно быть</th><th>Есть</th><th>Где</th><th>Сцена</th><th>Мера полноты</th></tr></thead>
        <tbody>
          {карта.items.map((п) => (
            <tr key={п.n} className={п.present ? undefined : 'v2-warn'}>
              <td>{п.n}</td>
              <td>{п.what}</td>
              <td>{п.present ? `да · блоков ${п.blocks}` : 'нет'}</td>
              <td className="v2-mono">{п.anchor ?? '—'}</td>
              <td>{п.scene}</td>
              <td className="v2-muted">{п.measure}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {карта.blocked_scenes.length > 0 && (
        <div className="v2-locked">без недостающих пунктов не закрываются сцены {карта.blocked_scenes.join(', ')}</div>
      )}
    </div>
  )
}

/** Партия каталога: несколько файлов сразу, у каждого своя роль; разбор — очередью; сводка — одна. */
export function Партия({ project, ранг, роль, onDone, onError }: {
  project: string
  ранг: Authority | ''
  роль: DocumentRole | ''
  onDone: () => void
  onError: (e: string) => void
}) {
  const [файлы, setФайлы] = useState<{ name: string; text?: string; base64?: string; role: DocumentRole | '' }[]>([])
  const [коды, setКоды] = useState<string[]>([])
  const [сводка, setСводка] = useState<BatchSummary | null>(null)
  const [занято, setЗанято] = useState(false)
  const [итог, setИтог] = useState<string | null>(null)

  const выбрать = (список: FileList | null) => {
    if (!список) return
    const прочитанные: Promise<{ name: string; text?: string; base64?: string; role: DocumentRole | '' }>[] = []
    for (const f of Array.from(список)) {
      const текстовый = /\.(txt|md|csv|markdown)$/i.test(f.name) || f.type.startsWith('text/')
      прочитанные.push(текстовый
        ? f.text().then((text) => ({ name: f.name, text, role: роль }))
        : new Promise((resolve, reject) => {
          const reader = new FileReader()
          reader.onload = () => { const url = String(reader.result ?? ''); resolve({ name: f.name, base64: url.substring(url.indexOf(',') + 1), role: роль }) }
          reader.onerror = () => reject(new Error(`файл ${f.name} не прочитался`))
          reader.readAsDataURL(f)
        }))
    }
    Promise.all(прочитанные).then(setФайлы).catch((e) => onError(отказСловами(e)))
  }

  const опрос = (список: string[], попытка: number) => {
    api.batchSummary(project, список).then((с) => {
      setСводка(с)
      if (с.running > 0 && попытка < 200) window.setTimeout(() => опрос(список, попытка + 1), 3000)
      else { setЗанято(false); setИтог(с.note); onDone() }
    }).catch((e) => { setЗанято(false); onError(отказСловами(e)) })
  }

  const загрузить = () => {
    setЗанято(true); setИтог(null); setСводка(null)
    const items: BatchItem[] = файлы.map((ф) => ({
      name: ф.name.replace(/\.[^.]+$/, ''),
      ...(ф.text !== undefined ? { text: ф.text } : { filename: ф.name, file_base64: ф.base64 }),
      rank: ранг || undefined,
      role: ф.role || undefined,
    }))
    api.materialsBatch(project, items)
      .then((п) => {
        setКоды(п.codes)
        if (п.refusals.length) onError(`не прочитано: ${п.refusals.join('; ')}`)
        if (п.codes.length === 0) { setЗанято(false); return }
        setИтог(п.note)
        опрос(п.codes, 0)
      })
      .catch((e) => { setЗанято(false); onError(отказСловами(e)) })
  }

  return (
    <div className="v2-form" data-why="работа" aria-label="партия каталога">
      <label>несколько файлов сразу
        <input type="file" multiple accept=".txt,.md,.csv,.docx,.pdf,.xlsx,.pptx"
          title="каталог документов: у каждого своя карточка и роль, разбор идёт очередью, сводка партии — одна"
          onChange={(e) => выбрать(e.target.files)} />
      </label>
      {файлы.length > 0 && (
        <table className="v2-table">
          <thead><tr><th>Файл</th><th>Роль</th></tr></thead>
          <tbody>
            {файлы.map((ф, i) => (
              <tr key={ф.name}>
                <td>{ф.name}</td>
                <td>
                  <select value={ф.role} aria-label={`роль документа ${ф.name}`}
                    onChange={(e) => setФайлы(файлы.map((х, j) => (j === i ? { ...х, role: e.target.value as DocumentRole | '' } : х)))}>
                    <option value="">— роль не названа —</option>
                    {РОЛИ_ДОКУМЕНТА.map((р) => <option key={р.code} value={р.code}>{р.word}</option>)}
                  </select>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <div className="v2-form__actions">
        <button type="button" className="v2-primary" disabled={занято || файлы.length === 0 || !ранг} onClick={загрузить}
          title={файлы.length === 0 ? 'выберите файлы каталога'
            : !ранг ? 'назовите ранг доверия: он общий для партии'
              : занято ? 'партия идёт: разбор очередью, сводка обновляется'
                : 'положить документы и разобрать очередью'}>
          {занято ? 'Партия идёт…' : `Загрузить партией · ${файлы.length}`}
        </button>
        {итог && <span className="v2-dim">{итог}</span>}
      </div>
      {сводка && (
        <div data-why="работа" aria-label="сводка партии">
          <div className="v2-note-line">{сводка.note}{сводка.running > 0 && ' · разбор идёт'}</div>
          <table className="v2-table">
            <thead><tr><th>Код</th><th>Документ</th><th>Роль</th><th>Фактов</th><th>Противоречий</th><th>Дубль</th><th>Разбор</th></tr></thead>
            <tbody>
              {сводка.items.map((с) => (
                <tr key={с.code}>
                  <td className="v2-mono">{с.code}</td>
                  <td>{с.name}{с.summary && <div className="v2-muted">{с.summary}</div>}</td>
                  <td>{словоРоли(с.role)}</td>
                  <td>{с.facts}</td>
                  <td>{с.contradicts}</td>
                  <td className="v2-mono">{с.duplicate_of ?? '—'}</td>
                  <td>{с.job_error ? <span className="v2-warn">{с.job_error}</span> : (СЛОВО_ПРОГОНА[с.job_status] ?? с.job_status)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {коды.length > 0 && !сводка && <div className="v2-muted">коды партии: {коды.join(' · ')}</div>}
    </div>
  )
}

/** Библиотека проекта: взять разобранный документ другого проекта — с фактами, без вызова модели. */
export function ВзятьИзПроекта({ project, onDone, onError }: { project: string; onDone: () => void; onError: (e: string) => void }) {
  const [проекты, setПроекты] = useState<КарточкаПроекта[]>([])
  const [откуда, setОткуда] = useState('')
  const [документы, setДокументы] = useState<MaterialRow[]>([])
  const [код, setКод] = useState('')
  const [занято, setЗанято] = useState(false)
  const [итог, setИтог] = useState<string | null>(null)

  useEffect(() => {
    портфель().then((п) => setПроекты(п.items.filter((к) => к.code !== project))).catch(() => setПроекты([]))
  }, [project])
  useEffect(() => {
    if (!откуда) { setДокументы([]); return }
    api.materials(откуда).then((r) => setДокументы(r.items.filter((м) => м.role !== 'charter'))).catch(() => setДокументы([]))
  }, [откуда])

  const взять = () => {
    setЗанято(true); setИтог(null)
    api.takeMaterial(project, откуда, код)
      .then((в) => { setЗанято(false); setИтог(в.note); setКод(''); onDone() })
      .catch((e) => { setЗанято(false); onError(отказСловами(e)) })
  }

  if (проекты.length === 0) return null
  return (
    <div className="v2-form v2-form--row" data-why="работа" aria-label="взять документ из другого проекта">
      <select value={откуда} onChange={(e) => { setОткуда(e.target.value); setКод('') }} aria-label="проект-источник"
        title="библиотека другого проекта: нормативы, обстановка, наследие берутся вместе с разобранными фактами; устав — нет">
        <option value="">— взять документ из проекта… —</option>
        {проекты.map((п) => <option key={п.code} value={п.code}>{п.code} · {п.name}</option>)}
      </select>
      <select value={код} onChange={(e) => setКод(e.target.value)} aria-label="документ другого проекта" disabled={!откуда}
        title={откуда ? 'документ берётся с фактами; устав в перечень не входит' : 'сначала выберите проект'}>
        <option value="">{откуда && документы.length === 0 ? '— в проекте нет документов, кроме устава —' : '— документ —'}</option>
        {документы.map((м) => <option key={м.code} value={м.code}>{м.code} · {м.name} · {словоРоли(м.role)}</option>)}
      </select>
      <button type="button" disabled={занято || !код} onClick={взять}
        title={!код ? 'выберите документ' : занято ? 'идёт взятие' : 'взять в этот проект вместе с фактами — модель не вызывается'}>
        Взять в проект
      </button>
      {итог && <span className="v2-dim">{итог}</span>}
    </div>
  )
}
