// Подтверждение опасного действия — СВОИМ окном, а не диалогом браузера.
//
// Находка прогона 04.09 (блокер З-01): `window.confirm` во встроенном
// контексте подавляется и возвращает «нет» — назначение молчало, и человек
// видел мёртвую кнопку. Нативные диалоги (confirm · alert · prompt) в
// продукте запрещены как класс: они не стилизуются, не читаются с экрана
// и, как выяснилось, могут быть выключены вовсе. Здесь — маленькое окно
// с вопросом, первичным действием и отменой; строка ввода — по надобности.
import { useEffect, useRef, useState } from 'react'

export type ConfirmRequest = {
  /** Вопрос человеку: что именно произойдёт и с чем. */
  question: string
  /** Подпись первичной кнопки; по умолчанию «Подтвердить». */
  ok?: string
  /** Поле ввода: подпись причины/обоснования; без него — просто вопрос. */
  input?: { label: string; placeholder?: string; required?: boolean }
  /** Действие: получает введённый текст (пустая строка, если поля нет). */
  onOk: (text: string) => void
}

export function ConfirmBox({ request, onClose }: { request: ConfirmRequest | null; onClose: () => void }) {
  const [text, setText] = useState('')
  const inputRef = useRef<HTMLInputElement>(null)
  const okRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    setText('')
    if (!request) return
    // фокус — на том, чем человек будет отвечать
    const t = setTimeout(() => (request.input ? inputRef.current?.focus() : okRef.current?.focus()), 0)
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => { clearTimeout(t); window.removeEventListener('keydown', onKey) }
  }, [request, onClose])

  if (!request) return null
  const мало = request.input?.required && !text.trim()
  const подтвердить = () => { if (мало) return; request.onOk(text.trim()); onClose() }
  return (
    <div className="cf-veil" role="presentation" onClick={onClose}>
      <div className="cf-box" role="dialog" aria-modal="true" onClick={(e) => e.stopPropagation()}>
        <div className="cf-q">{request.question}</div>
        {request.input && (
          <label className="cf-in">
            <span className="secondary">{request.input.label}</span>
            <input
              ref={inputRef}
              value={text}
              placeholder={request.input.placeholder}
              onChange={(e) => setText(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') подтвердить() }}
            />
          </label>
        )}
        <div className="cf-act">
          <button type="button" className="np-linkish" onClick={onClose}>Отмена</button>
          <button
            ref={okRef}
            type="button"
            className="btn btn--primary"
            disabled={мало}
            title={мало ? `сначала заполните: ${request.input?.label}` : undefined}
            onClick={подтвердить}
          >
            {request.ok ?? 'Подтвердить'}
          </button>
        </div>
      </div>
    </div>
  )
}

/** Состояние подтверждения для экрана: `const [ask, confirm] = useConfirm()`. */
export function useConfirm(): [ConfirmRequest | null, (r: ConfirmRequest) => void, () => void] {
  const [request, setRequest] = useState<ConfirmRequest | null>(null)
  return [request, setRequest, () => setRequest(null)]
}
