// Мелкие элементы отображения. Ни один из них не считает — все получают
// готовые значения с сервера и только выбирают, как их нарисовать.
import type { BudgetBar, ConditionView } from '../api/types'

export function StatusDot({ status }: { status: string }) {
  return <span className={`dot status-${status}`} title={status} />
}

/**
 * Условие как СТРУКТУРНОЕ значение, а не текст (STEP-6 §3.2). Строку собрал
 * сервер: оператор, число и подпись единицы — из renderConstraint, поэтому
 * «≤ 60 кг» здесь и в отчёте выглядят одинаково по построению.
 */
export function Condition({ condition }: { condition: ConditionView | null }) {
  if (!condition?.rendered) return <span className="secondary">—</span>
  return (
    <span className="mono" title={condition.name ?? undefined}>
      {condition.rendered}
    </span>
  )
}

/**
 * Полоса бюджета. Превышение краснеет и показывает величину — обрезать полосу
 * до «100%» значило бы спрятать ровно то, ради чего она рисуется (ловушка 4).
 * Доля заполнения приходит с сервера готовым процентом: делить used на limit
 * в клиенте — уже заготовка второй реализации правила (ловушка 5).
 */
export function BudgetGauge({ bar }: { bar: BudgetBar | null }) {
  if (!bar) return <span className="secondary">—</span>
  return (
    <span>
      <span className="bar">
        <span
          className={`bar__fill${bar.overrun ? ' bar__fill--overrun' : ''}`}
          style={{ width: `${bar.fillPercent}%` }}
        />
      </span>
      <span className={`mono${bar.overrun ? ' warn' : ''}`}>
        {bar.used} / {bar.limit} ·{' '}
        {bar.overrun ? `превышение ${bar.overrunValue}` : `остаток ${bar.remaining}`}
      </span>
    </span>
  )
}

/** Метод проверки и начало описания подхода; отсутствие подхода — замечание. */
export function Verification({
  method,
  approach,
  issues,
}: {
  method: string | null
  approach: string | null
  issues: string[]
}) {
  if (!method) return <span className="secondary">—</span>
  return (
    <span>
      <span className="chip">{method}</span>{' '}
      {approach ? (
        <span className="secondary truncate">{approach}</span>
      ) : (
        <span className="amber">△ не описано, как выполняется проверка</span>
      )}
      {issues.length > 0 && (
        <span className="amber" title={issues.join('\n')}>
          {' '}
          △ {issues.length}
        </span>
      )}
    </span>
  )
}
