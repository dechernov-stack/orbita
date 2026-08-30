// Ф-07: замысел миссии собирается ИЗ ДОКУМЕНТОВ. Пустая табличка из четырёх
// полей рядом с разобранной запиской — работа, которую система обязана
// сделать сама. Форма рукой остаётся запасным путём; здесь — второй путь:
// собрать по урожаю разбора и блокам канона, показать с якорями, отдать
// инженеру на правку и акцепт.
import { useEffect, useState } from 'react'
import { api } from '../api/client'
import { useSession } from '../ui/session'
import type { MissionIntentDraftView } from '../api/types'

const FIELD_LABEL: Record<string, string> = {
  for_whom: 'Для кого',
  what: 'Что делает',
  where: 'Где',
  horizon: 'Горизонт',
}

export function MissionIntent(
  { onAccepted, onNeedMaterials, onNeedParse }: {
    onAccepted?: () => void
    onNeedMaterials?: () => void
    onNeedParse?: () => void
  },
) {
  const { author } = useSession()
  const [readiness, setReadiness] = useState<Awaited<ReturnType<typeof api.missionIntentReadiness>> | null>(null)
  const [draft, setDraft] = useState<MissionIntentDraftView | null>(null)
  const [prompt, setPrompt] = useState<string | null>(null)
  const [raw, setRaw] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [note, setNote] = useState<string | null>(null)

  useEffect(() => {
    api.missionIntentReadiness().then(setReadiness).catch((e) => setError(String(e)))
  }, [])

  /**
   * «Собрать из документов» — это СБОРКА, а не показ промпта. Система сама
   * спрашивает службу и приносит четыре поля с якорями. Канал не настроен
   * либо служба молчит — честно говорим причину и открываем прежний путь:
   * промпт наружу, ответ пакетом (внешний контур, Ф-10).
   */
  const composeDraft = () => {
    setBusy(true)
    setError(null)
    setNote(null)
    api.missionIntentCompose()
      .then((r) => {
        setDraft(r.draft)
        setNote(`замысел собран службой${r.model ? ` (${r.model})` : ''} — правьте и принимайте`)
      })
      .catch((e) => {
        setError(`служба не собрала замысел: ${String(e)}. Промпт ниже — его можно отдать внешнему контуру и вернуть ответ пакетом`)
        return api.missionIntentPrompt().then((p) => setPrompt(p.text)).catch(() => undefined)
      })
      .finally(() => setBusy(false))
  }

  const composePrompt = () => {
    setBusy(true)
    setError(null)
    api.missionIntentPrompt()
      .then((p) => setPrompt(p.text))
      .catch((e) => setError(String(e)))
      .finally(() => setBusy(false))
  }

  const takeDraft = () => {
    if (!raw.trim()) return
    setBusy(true)
    setError(null)
    api.missionIntentDraft(raw)
      .then((d) => { setDraft(d); setRaw('') })
      .catch((e) => setError(String(e)))
      .finally(() => setBusy(false))
  }

  const editField = (field: keyof MissionIntentDraftView['intent'], text: string) => {
    if (!draft) return
    setDraft({ ...draft, intent: { ...draft.intent, [field]: { ...draft.intent[field], text } } })
  }

  const accept = () => {
    if (!draft || !author) return
    setBusy(true)
    setError(null)
    api.missionIntentAccept(draft, author)
      .then(() => {
        setNote('замысел принят — генерация постановки разблокирована')
        setDraft(null)
        onAccepted?.()
      })
      .catch((e) => setError(String(e)))
      .finally(() => setBusy(false))
  }

  if (!readiness) return null

  return (
    <div className="card">
      <h3>Замысел из документов</h3>
      <div>
        <p className="secondary" style={{ marginTop: 0 }}>
          {readiness.why}
          {readiness.can_compose && ` · разобрано документов: ${readiness.parsed}`}
        </p>
        {error && <div className="warn" style={{ padding: 6 }}>{error}</div>}
        {note && <div className="secondary">{note}</div>}

        <div className="toolbar" style={{ padding: '4px 0', gap: 6 }}>
          {/* Ф-11: неактивный контрол обязан нести причину И ПУТЬ оживления.
              Нечего разбирать — кнопка не сереет, а ведёт туда, где материал
              прикладывается, и возвращает обратно. */}
          {readiness.can_compose ? (
            <>
              <button className="rr-assign" disabled={busy} onClick={composeDraft}
                title="система спросит службу по урожаю разбора и блокам канона и принесёт четыре поля с якорями">
                {busy ? 'Собираю…' : 'Собрать из документов'}
              </button>
              <button className="np-linkish" disabled={busy} onClick={composePrompt}
                title="показать промпт целиком — для внешнего контура: отдать наружу и вернуть ответ пакетом">
                промпт для внешнего контура
              </button>
            </>
          ) : readiness.documents === 0 && onNeedMaterials ? (
            <button className="rr-assign" onClick={onNeedMaterials}
              title="собирать замысел не из чего: материалов в проекте нет — шаг назад приложит документ, оттуда вернётесь сюда">
              ← Приложить материалы
            </button>
          ) : readiness.documents > 0 && onNeedParse ? (
            <button className="rr-assign" onClick={onNeedParse}
              title="материалы есть, но не разобраны: разбор даёт канон с якорями, по нему и собирается замысел">
              Разобрать документы →
            </button>
          ) : (
            <button className="rr-assign" disabled title={readiness.why}>
              Собрать из документов
            </button>
          )}
          {readiness.sources.map((s) => (
            <span key={s.document} className="chip"
              title={s.harvest ? 'есть смысловой разбор — урожай усилит сборку' : 'есть канон разбора'}>
              {s.document}{s.harvest ? ' ✓' : ''}
            </span>
          ))}
        </div>

        {prompt && (
          <textarea readOnly rows={6} value={prompt}
            style={{ width: '100%', fontFamily: 'var(--font-mono)', fontSize: 12 }} />
        )}

        {readiness.can_compose && (
          <div className="field">
            <label>Предложение замысла пакетом (ответ модели либо заготовка)</label>
            <textarea rows={3} value={raw} onChange={(e) => setRaw(e.target.value)}
              style={{ width: '100%', fontFamily: 'var(--font-mono)', fontSize: 12 }}
              placeholder='{"kind": "mission_intent_from_docs", "intent": {…}}' />
            <div className="toolbar" style={{ padding: '4px 0', gap: 6 }}>
              <label className="rr-assign" title="выбрать файл предложения">
                Выбрать файл…
                <input type="file" accept="application/json,.json" style={{ display: 'none' }}
                  onChange={(e) => {
                    const file = e.target.files?.[0]
                    e.target.value = ''
                    if (file) file.text().then(setRaw).catch((err) => setError(String(err)))
                  }} />
              </label>
              <button className="rr-assign" onClick={takeDraft} disabled={!raw.trim() || busy}
                title="ворота — нормативная схема замысла: чужая форма не пройдёт">
                Показать предложение
              </button>
            </div>
          </div>
        )}

        {draft && (
          <div style={{ marginTop: 6 }}>
            <p className="secondary" style={{ margin: '0 0 4px' }}>
              Предложение, не истина: правьте текст и принимайте. Якоря показывают,
              из каких блоков документа поле выведено.
            </p>
            {(['for_whom', 'what', 'where', 'horizon'] as const).map((field) => (
              <div key={field} className="field">
                <label>
                  {FIELD_LABEL[field]}{' '}
                  <span className="secondary mono" title="блоки канона — основание поля">
                    {(draft.intent[field].anchors ?? []).join(', ') || '—'}
                  </span>
                </label>
                <textarea rows={2} style={{ width: '100%' }} value={draft.intent[field].text}
                  onChange={(e) => editField(field, e.target.value)} />
              </div>
            ))}
            <div className="toolbar" style={{ padding: '6px 0', gap: 6 }}>
              <button className="tab tab--primary" onClick={accept} disabled={busy || !author}
                title={author ? 'принять замысел в паспорт проекта' : 'представьтесь в шапке'}>
                {busy ? 'Принимаю…' : 'Принять замысел'}
              </button>
              <button className="rr-assign" onClick={() => setDraft(null)}
                title="отказаться от предложения — замысел останется прежним">
                отклонить
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
