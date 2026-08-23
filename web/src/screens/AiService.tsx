// Служба ИИ (П5): профиль → промпт службы → вызов → фильтр → журнал.
//
// Промпт инженер не пишет: его собирает служба из профиля (ограничения
// объектом AP-NNNN), состояния модели и вида пакета. Транспорта два, формат
// один: прямой вызов провайдера — основной, закрытый контур (пакет владельцу,
// ответ файлом) — режим того же формата. Журнал отвечает «сколько и почём».
import { useCallback, useEffect, useState } from 'react'
import { api, type AiJournal, type AiRunReport, type BatchReport } from '../api/client'
import { edit, type StoredSummary } from '../api/edit'
import { useSession } from '../ui/session'

const KINDS: Array<{ id: string; title: string; generative: boolean }> = [
  { id: 'mission_to_goals', title: 'Постановка → цели миссии', generative: true },
  { id: 'mission_to_needs', title: 'Постановка → нужды', generative: true },
  { id: 'needs_to_services', title: 'Нужды → сервисы', generative: true },
  { id: 'services_to_requirements', title: 'Сервисы → требования', generative: true },
  { id: 'risk_register', title: 'Сценарий → риски', generative: true },
  { id: 'requirement_quality', title: 'Рецензия формулировок', generative: false },
]

export function AiService() {
  const { author } = useSession()
  const [profiles, setProfiles] = useState<StoredSummary[]>([])
  const [profile, setProfile] = useState('')
  const [kind, setKind] = useState(KINDS[0].id)
  const [statement, setStatement] = useState('')
  const [prompt, setPrompt] = useState<string | null>(null)
  const [transport, setTransport] = useState<string>('')
  const [raw, setRaw] = useState('')
  const [report, setReport] = useState<AiRunReport | null>(null)
  const [batch, setBatch] = useState<BatchReport | null>(null)
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
    api.aiAsk(kind, profile, statement, author)
      .then(setReport)
      .catch((e) => setError(String(e)))
      .finally(() => { setBusy(false); reloadJournal() })
  }

  const submit = () => {
    setBusy(true)
    setError(null)
    setBatch(null)
    api.aiSubmit(kind, profile, statement, raw, author)
      .then(setReport)
      .catch((e) => setError(String(e)))
      .finally(() => { setBusy(false); reloadJournal() })
  }

  const acceptAll = () => {
    if (!report) return
    setBusy(true)
    api.acceptBatchOfCall(report.call ?? null, 'служба', author, report.shown.map((s) => s.item))
      .then(setBatch)
      .catch((e) => setError(String(e)))
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
            Профилей службы в проекте нет. Профиль — ограничения инженера объектом
            (виды, транспорт, правила формулировок, глоссарий, запреты); заведите
            его в реестре «Профили службы ИИ», иначе служба работать не вправе.
          </div>
        )}
        {error && <div className="notice notice--blocked">{error}</div>}

        <div className="field">
          <label>Вход операции (постановка миссии либо иной материал инженера)</label>
          <textarea rows={3} style={{ width: '100%' }} value={statement}
            onChange={(e) => setStatement(e.target.value)}
            placeholder="Национальная спутниковая платформа IoT: сбор телеметрии…" />
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
            <button className="btn" onClick={submit} disabled={!raw.trim() || !author || busy}>
              Внести ответ контура
            </button>
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
              {generative && report.shown.length > 0 && (
                <button className="btn btn--primary" onClick={acceptAll} disabled={!author || busy}>
                  Принять пачкой ({report.shown.length})
                </button>
              )}
              {batch && (
                <div className={batch.problems.length ? 'notice notice--blocked' : 'notice'}>
                  {batch.problems.length === 0
                    ? <>Принято: <b className="mono">{batch.written}</b> — акцепт записан в журнал вызова</>
                    : <>Пачка отклонена: {batch.problems.slice(0, 4).map((p) => `${p.id ?? p.index}: ${p.message}`).join('; ')}</>}
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
