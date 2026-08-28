// Ссылка-чип (МВП-П1 §2.1): любое ссылочное поле кликабельно — «ID · имя»,
// клик ведёт к объекту. Отменённый объект — стиль разрыва (зачёркнутый,
// с подсказкой), не 404: ссылка честно показывает, что цель умерла.
import { requestObject, screenOfObject } from '../api/intent'

export function RefChip({ id, title, cancelled, onGo, onOpen }: {
  id: string
  title?: string
  cancelled?: boolean
  onGo?: (screen: string) => void
  /** Родитель сам решает переход (например, карточка своего реестра). */
  onOpen?: (id: string) => void
}) {
  const screen = screenOfObject(id)
  const dead = !onOpen && (!screen || !onGo)
  return (
    <button
      type="button"
      className="refchip"
      disabled={dead}
      title={cancelled
        ? `${id} отменён — ссылка показывает разрыв`
        : dead ? `${id} — экрана вида пока нет` : `перейти: ${id}${title ? ` · ${title}` : ''}`}
      style={cancelled ? { textDecoration: 'line-through', color: 'var(--text-secondary)' } : undefined}
      onClick={() => {
        if (dead) return
        if (onOpen) { onOpen(id); return }
        requestObject(id)
        onGo!(screen!)
      }}
    >
      <span className="mono">{id}</span>
      {title && <span className="refchip__nm">{title}</span>}
    </button>
  )
}
