// Служба ИИ (П5): профиль → промпт службы → вызов → фильтр → журнал.
//
// Промпт инженер не пишет: его собирает служба из профиля (ограничения
// объектом AP-NNNN), состояния модели и вида пакета. Транспорта два, формат
// один: прямой вызов провайдера — основной, закрытый контур (пакет владельцу,
// ответ файлом) — режим того же формата. Журнал отвечает «сколько и почём».
import { useCallback, useEffect, useState } from 'react'
import { api, asBatchReport, type AiJournal, type AiRunReport, type BatchReport } from '../api/client'
import { edit, type StoredSummary } from '../api/edit'
import { useSession } from '../ui/session'

const KINDS: Array<{ id: string; title: string; generative: boolean }> = [
  { id: 'mission_to_goals', title: 'Постановка → цели миссии', generative: true },
  { id: 'mission_to_needs', title: 'Постановка → нужды', generative: true },
  { id: 'needs_to_services', title: 'Нужды → сервисы', generative: true },
  { id: 'services_to_requirements', title: 'Сервисы → требования', generative: true },
  { id: 'risk_register', title: 'Сценарий → риски', generative: true },
  { id: 'requirement_quality', title: 'Рецензия формулировок', generative: false },
  // Дозаполнение (находка прогона: 140 требований без обоснования и
  // показателя): вход собирает СЛУЖБА — дырявые требования пачкой; ответ —
  // частичные правки, применяются к существующим объектам
  { id: 'requirement_enrichment', title: 'Дозаполнение требований (обоснование, MOP)', generative: false },
]

export function AiService({ onGo }: { onGo?: (screen: string) => void }) {
  const { author } = useSession()
  const [profiles, setProfiles] = useState<StoredSummary[]>([])
  const [profile, setProfile] = useState('')
  const [sourceDocs, setSourceDocs] = useState<StoredSummary[]>([])
  const [sourceDoc, setSourceDoc] = useState('')
  const [kind, setKind] = useState(KINDS[0].id)
  const [statement, setStatement] = useState('')
  const [prompt, setPrompt] = useState<string | null>(null)
  const [transport, setTransport] = useState<string>('')
  const [raw, setRaw] = useState('')
  const [report, setReport] = useState<AiRunReport | null>(null)
  const [batch, setBatch] = useState<BatchReport | null>(null)
  // Пачка на запись — всё или ничего (ADR-024): одно требование с изъяном
  // (например, интерфейсное распределено на элемент, а не на интерфейс —
  // CR-003) не должно держать заложником весь показанный улов. Инженер
  // снимает отметку у проблемных и принимает остальные тем же действием.
  const [excluded, setExcluded] = useState<Set<string>>(new Set())
  const [journal, setJournal] = useState<AiJournal | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const reloadJournal = useCallback(() => {
    api.aiJournal().then(setJournal).catch(() => setJournal(null))
  }, [])

  useEffect(() => {
    edit.list('ai_profile')
      .then((rows) => {
        setProfiles(rows)
        if (rows.length > 0) setProfile((cur) => cur || rows[0].id)
      })
      .catch((e) => setError(String(e)))
    // библиотека исходных документов (ADR-030): пустая — просто нет кнопки
    edit.list('source_document')
      .then((rows) => {
        setSourceDocs(rows)
        if (rows.length > 0) setSourceDoc((cur) => cur || rows[0].id)
      })
      .catch(() => setSourceDocs([]))
    reloadJournal()
  }, [reloadJournal])

  const compose = () => {
    setError(null)
    setReport(null)
    setBatch(null)
    api.aiCompose(kind, profile, statement)
      .then((r) => { setPrompt(r.prompt); setTransport(r.transport) })
      .catch((e) => setError(String(e)))
  }

  const ask = () => {
    setBusy(true)
    setError(null)
    setBatch(null)
    setExcluded(new Set())
    setEnriched(null)
    api.aiAsk(kind, profile, statement, author)
      .then(setReport)
      .catch((e) => setError(String(e)))
      .finally(() => { setBusy(false); reloadJournal() })
  }

  const submit = () => {
    setBusy(true)
    setError(null)
    setBatch(null)
    setExcluded(new Set())
    setEnriched(null)
    api.aiSubmit(kind, profile, statement, raw, author)
      .then(setReport)
      .catch((e) => setError(String(e)))
      .finally(() => { setBusy(false); reloadJournal() })
  }

  const [enriched, setEnriched] = useState<{ written: number; demoted: string[] } | null>(null)

  const enrichApply = () => {
    if (!report) return
    const items = report.shown.filter((s) => !excluded.has(String(s.item.id ?? '')))
    if (items.length === 0) return
    setBusy(true)
    setEnriched(null)
    const onRejected = (r: BatchReport & { demoted?: string[] }) => {
      setBatch(r)
      if (r.problems.length === 0) setEnriched({ written: r.written, demoted: r.demoted ?? [] })
      else setExcluded((prev) => {
        const next = new Set(prev)
        r.problems.forEach((p) => { if (p.id) next.add(p.id) })
        return next
      })
    }
    api.enrichApply(report.call ?? null, author, items.map((s) => s.item))
      .then(onRejected)
      .catch((e) => {
        const parsed = asBatchReport(e)
        if (parsed) onRejected(parsed)
        else setError(String(e))
      })
      .finally(() => { setBusy(false); reloadJournal() })
  }

  const acceptAll = () => {
    if (!report) return
    const items = report.shown.filter((s) => !excluded.has(String(s.item.id ?? '')))
    if (items.length === 0) return
    setBusy(true)
    const onRejected = (r: BatchReport) => {
      setBatch(r)
      // Пачка всё-или-ничего: при отказе снять отметку у названных причиной
      // объектов, чтобы повторное нажатие приняло уже без них.
      if (r.problems.length > 0) {
        setExcluded((prev) => {
          const next = new Set(prev)
          r.problems.forEach((p) => { if (p.id) next.add(p.id) })
          return next
        })
      }
    }
    api.acceptBatchOfCall(report.call ?? null, 'служба', author, items.map((s) => s.item))
      .then(onRejected)
      .catch((e) => {
        // Сервер отвечает 422 тем же BatchReport, что и 201 на успех (written: 0,
        // problems построчно) — общий post() на не-2xx бросает исключение и
        // разобранное тело теряет. Достаём его назад, а не пугаем инженера
        // сырой строкой ответа сервера.
        const parsed = asBatchReport(e)
        if (parsed) onRejected(parsed)
        else setError(String(e))
      })
      .finally(() => { setBusy(false); reloadJournal() })
  }

  const generative = KINDS.find((k) => k.id === kind)?.generative ?? false
  const noProfiles = profiles.length === 0

  return (
    <>
      <div className="toolbar">
        <h2>Служба ИИ</h2>
        <select value={profile} onChange={(e) => { setProfile(e.target.value); setPrompt(null) }}>
          {profiles.map((p) => (
            <option key={p.id} value={p.id}>{p.id} — {p.title}</option>
          ))}
        </select>
        <select value={kind} onChange={(e) => { setKind(e.target.value); setPrompt(null) }}>
          {KINDS.map((k) => <option key={k.id} value={k.id}>{k.title}</option>)}
        </select>
        <div className="grow" />
        <button className="btn" onClick={compose} disabled={!profile || noProfiles}>Собрать промпт</button>
        <button className="btn btn--primary" onClick={ask}
          disabled={!profile || !author || busy || transport === 'package'}
          title={transport === 'package' ? 'профиль работает режимом закрытого контура' : ''}>
          {busy ? 'Вызов…' : 'Спросить службу'}
        </button>
      </div>
      <div className="workarea" style={{ padding: 14 }}>
        {noProfiles && (
          <div className="notice notice--blocked">
            Профилей службы в проекте нет — служба работать не вправе. Профиль —
            ограничения инженера объектом (виды, транспорт, правила формулировок,
            глоссарий, запреты). Заведите его в реестре «Профили службы»: формой
            либо кнопкой «Загрузить пачкой» (файл материала —
            <span className="mono"> 00-профили-службы.json</span>).
            {/* Тупик второго захода: notice был табличкой без двери — инженер
                читал «не работает» и не знал, куда идти */}
            {onGo && (
              <div style={{ marginTop: 6 }}>
                <button type="button" className="btn" onClick={() => onGo('aiprofiles')}>
                  Открыть «Профили службы» →
                </button>
              </div>
            )}
          </div>
        )}
        {error && <div className="notice notice--blocked">{error}</div>}

        <div className="field">
          <label>Вход операции (постановка миссии либо иной материал инженера)</label>
          <textarea rows={3} style={{ width: '100%' }} value={statement}
            onChange={(e) => setStatement(e.target.value)}
            placeholder="Национальная спутниковая платформа IoT: сбор телеметрии…" />
          {/* Вход из библиотеки исходных документов (ADR-030): записка
              Минтранса лежит объектом SD-NNNN — её текст подставляется сюда
              вместе с реквизитами, и промпт понесёт ссылку на источник. */}
          {sourceDocs.length > 0 && (
            <div className="toolbar" style={{ padding: '4px 0', gap: 6 }}>
              <span className="secondary">из документа:</span>
              <select value={sourceDoc} onChange={(e) => setSourceDoc(e.target.value)}>
                {sourceDocs.map((d) => (
                  <option key={d.id} value={d.id}>{d.id} — {d.title}</option>
                ))}
              </select>
              <button className="btn" disabled={!sourceDoc}
                title="подставить текст документа с реквизитами во вход операции"
                onClick={() => {
                  setError(null)
                  edit.object(sourceDoc)
                    .then((o) => {
                      const doc = o.doc as Record<string, unknown>
                      const text = String(doc.text ?? '')
                      if (!text.trim()) {
                        setError(`У документа ${sourceDoc} нет текста: заполните поле «Текст» в реестре «Исходные документы».`)
                        return
                      }
                      const head = `Источник: ${sourceDoc} в. ${o.version} «${String(doc.name ?? '')}»` +
                        (doc.org ? `, ${String(doc.org)}` : '') +
                        (doc.doc_date ? `, ${String(doc.doc_date)}` : '') +
                        '. В rationale порождённых объектов ссылайтесь на этот документ.'
                      setStatement(`${head}\n\n${text}`)
                    })
                    .catch((e) => setError(String(e)))
                }}>
                Взять вход из документа
              </button>
            </div>
          )}
        </div>

        {prompt && (
          <div className="card">
            <h3>Промпт собран службой · транспорт {transport}</h3>
            <div>
              <p className="secondary">
                Инженер промпт не пишет: он выведен из профиля, состояния модели и вида
                пакета. Для закрытого контура — скопируйте текст, получите ответ файлом
                и внесите его ниже.
              </p>
              <textarea readOnly rows={10} value={prompt}
                style={{ width: '100%', fontFamily: 'var(--font-mono)', fontSize: 12 }} />
            </div>
          </div>
        )}

        {prompt && (
          <div className="field">
            <label>Ответ закрытого контура (файл от владельца)</label>
            <textarea rows={5} style={{ width: '100%', fontFamily: 'var(--font-mono)', fontSize: 12 }}
              value={raw} onChange={(e) => setRaw(e.target.value)}
              placeholder='[{"id": "ND-0001", …}]' />
            <div className="toolbar" style={{ padding: '6px 0' }}>
              <button className="btn" onClick={submit} disabled={!raw.trim() || !author || busy}>
                Внести ответ контура
              </button>
              <label className="btn" title="выбрать файл с ответом контура, не вставлять текст руками">
                Выбрать файл…
                <input type="file" accept="application/json,.json,.txt" style={{ display: 'none' }}
                  onChange={(e) => {
                    const file = e.target.files?.[0]
                    e.target.value = ''
                    if (!file) return
                    setError(null)
                    file.text().then(setRaw).catch((e) => setError(String(e)))
                  }} />
              </label>
            </div>
          </div>
        )}

        {report && (
          <div className="card">
            <h3>
              Отчёт фильтра: предложено {report.proposed} · показано {report.shown.length} ·
              снято {report.rework?.rejected ?? 0}
              {report.no_source > 0 && <span className="warn"> · без основания {report.no_source}</span>}
            </h3>
            <div>
              {report.no_source > 0 && (
                <p className="warn">
                  Значения без ссылки на источник не проходят молча (правило основания):
                  такие предложения сняты и требуют ручного решения инженера.
                </p>
              )}
              {report.by_rule && Object.keys(report.by_rule).length > 0 && (
                <table style={{ maxWidth: 460, marginBottom: 8 }}>
                  <thead><tr><th>Правило фильтра</th><th style={{ width: 90 }}>Снято</th></tr></thead>
                  <tbody>
                    {Object.entries(report.by_rule).map(([rule, count]) => (
                      <tr key={rule}><td>{rule}</td><td className="num">{count}</td></tr>
                    ))}
                  </tbody>
                </table>
              )}
              {kind === 'requirement_enrichment' && report.shown.length > 0 && (
                <>
                  <p className="secondary">
                    Правки применяются к СУЩЕСТВУЮЩИМ требованиям — с основанием
                    (акцепт предложений службы). Правка вернёт объект в черновик:
                    после применения ре-базируйте пачкой в реестре требований.
                  </p>
                  <div style={{ maxHeight: 260, overflowY: 'auto', marginBottom: 8 }}>
                    <table>
                      <thead><tr><th style={{ width: 30 }} /><th style={{ width: 90 }}>Id</th><th>Обоснование</th><th style={{ width: 220 }}>Показатель</th></tr></thead>
                      <tbody>
                        {report.shown.map((s) => {
                          const id = String(s.item.id ?? '')
                          const mop = s.item.mop as { name?: string; operator?: string; value?: { value?: number; unit?: string } } | undefined
                          const problem = batch?.problems.find((p) => p.id === id)
                          return (
                            <tr key={id}>
                              <td>
                                <input type="checkbox" checked={!excluded.has(id)}
                                  onChange={(e) => setExcluded((prev) => {
                                    const next = new Set(prev)
                                    if (e.target.checked) next.delete(id); else next.add(id)
                                    return next
                                  })} />
                              </td>
                              <td className="mono">{id}</td>
                              <td className="wrap">{String(s.item.rationale ?? '—')}
                                {problem && <div className="warn">{problem.message}</div>}
                              </td>
                              <td className="wrap">
                                {mop ? `${mop.name ?? ''} ${mop.operator ?? ''} ${mop.value?.value ?? ''} ${mop.value?.unit ?? ''}` : '—'}
                              </td>
                            </tr>
                          )
                        })}
                      </tbody>
                    </table>
                  </div>
                  <button className="btn btn--primary" onClick={enrichApply}
                    disabled={!author || busy || report.shown.length === excluded.size}>
                    Применить правками ({report.shown.length - excluded.size})
                  </button>
                  {enriched && (
                    <div className="notice">
                      Применено правок: <b className="mono">{enriched.written}</b>.
                      {enriched.demoted.length > 0 && (
                        <> Объекты вернулись в черновик ({enriched.demoted.length}) —
                        откройте реестр требований и ре-базируйте пачкой («Базировать все»).</>
                      )}
                    </div>
                  )}
                </>
              )}
              {generative && report.shown.length > 0 && (
                <>
                  <p className="secondary">
                    Пачка пишется всё или ничего: отказ по одному объекту не даёт причины
                    отказывать в остальных — снимите отметку у отклонённых (сервер называет их
                    поимённо) и примите оставшихся тем же действием.
                  </p>
                  <div style={{ maxHeight: 220, overflowY: 'auto', marginBottom: 8 }}>
                    <table>
                      <thead><tr><th style={{ width: 30 }} /><th style={{ width: 90 }}>Id</th><th>Формулировка</th></tr></thead>
                      <tbody>
                        {report.shown.map((s) => {
                          const id = String(s.item.id ?? '')
                          const label = String(s.item.statement ?? s.item.name ?? s.item.title ?? '')
                          const problem = batch?.problems.find((p) => p.id === id)
                          return (
                            <tr key={id}>
                              <td>
                                <input type="checkbox" checked={!excluded.has(id)}
                                  onChange={(e) => setExcluded((prev) => {
                                    const next = new Set(prev)
                                    if (e.target.checked) next.delete(id); else next.add(id)
                                    return next
                                  })} />
                              </td>
                              <td className="mono">{id}</td>
                              <td>
                                {label}
                                {problem && <div className="warn">{problem.rule ? `${problem.rule}: ` : ''}{problem.message}</div>}
                              </td>
                            </tr>
                          )
                        })}
                      </tbody>
                    </table>
                  </div>
                  <button className="btn btn--primary" onClick={acceptAll}
                    disabled={!author || busy || report.shown.length === excluded.size}>
                    Принять пачкой ({report.shown.length - excluded.size})
                  </button>
                </>
              )}
              {batch && (
                <div className={batch.problems.length ? 'notice notice--blocked' : 'notice'}>
                  {batch.problems.length === 0
                    ? <>Принято: <b className="mono">{batch.written}</b> — акцепт записан в журнал вызова</>
                    : <>Пачка отклонена, отклонённые сняты из выбора выше — примите оставшихся: {batch.problems.slice(0, 4).map((p) => `${p.id ?? p.index}: ${p.message}`).join('; ')}</>}
                </div>
              )}
            </div>
          </div>
        )}

        {journal && (
          <div className="card">
            <h3>
              Журнал службы: вызовов {journal.totals.calls} · предложено {journal.totals.proposed} ·
              снято {journal.totals.filtered} (без основания {journal.totals.no_source}) ·
              акцептовано {journal.totals.accepted} · токенов {journal.totals.tokens_in}/{journal.totals.tokens_out} ·
              стоимость {journal.totals.cost_usd} $
            </h3>
            <div>
              {journal.calls.length === 0 ? (
                <div className="empty">Служба ещё не вызывалась.</div>
              ) : (
                <table>
                  <thead>
                    <tr>
                      <th style={{ width: 140 }}>Когда</th>
                      <th style={{ width: 170 }}>Задание</th>
                      <th style={{ width: 110 }}>Профиль</th>
                      <th style={{ width: 80 }}>Канал</th>
                      <th style={{ width: 130 }}>Модель</th>
                      <th style={{ width: 70 }}>Предл.</th>
                      <th style={{ width: 70 }}>Снято</th>
                      <th style={{ width: 80 }}>Акцепт</th>
                      <th style={{ width: 110 }}>Токены</th>
                      <th>Стоимость / отказ</th>
                    </tr>
                  </thead>
                  <tbody>
                    {journal.calls.map((c) => (
                      <tr key={c.pk}>
                        <td className="mono">{c.at.slice(0, 16).replace('T', ' ')}</td>
                        <td>{c.kind}</td>
                        <td className="mono">{c.profile}@{c.profile_version}</td>
                        <td>{c.transport === 'direct' ? 'прямой' : 'контур'}</td>
                        <td className="mono">{c.model ?? '—'}</td>
                        <td className="num">{c.proposed}</td>
                        <td className="num">{c.filtered}{c.no_source > 0 ? ` (${c.no_source})` : ''}</td>
                        <td className="num">{c.accepted}</td>
                        <td className="num">{c.tokens_in ?? '—'}/{c.tokens_out ?? '—'}</td>
                        <td className={c.failure ? 'warn' : 'mono'}>{c.failure ?? (c.cost_usd ?? '—')}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          </div>
        )}
      </div>
    </>
  )
}
