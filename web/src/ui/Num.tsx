// Форматирование чисел по смыслу — ОБЩИМ ПРАВИЛОМ (замечание 28.08, §2):
// разрядные пробелы, значащие цифры по величине, никаких float-хвостов.
// Спрос и веса — модельные оценки: восемь значащих цифр были бы ложью
// точности. Точное значение живёт в подсказке — честность не теряется.
// Форматирование — представление, не расчёт (Intl, не арифметика модели).

const RU_INT = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 0 })
const RU_1 = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 1 })
const RU_2 = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 2 })
const RU_SIG = new Intl.NumberFormat('ru-RU', { maximumSignificantDigits: 3 })

/** «356,6 млн» · «63 200» · «4 127» · «29,69 млн» · «1 550» · «0,42». */
export function fmtNum(v: number | undefined | null): string {
  if (v === undefined || v === null || Number.isNaN(v)) return '—'
  const abs = v < 0 ? -v : v
  if (abs >= 1e9) return `${RU_2.format(v / 1e9)} млрд`
  if (abs >= 1e6) return `${RU_2.format(v / 1e6)} млн`
  if (abs >= 1000) return RU_INT.format(v)
  if (abs >= 100) return RU_1.format(v)
  return RU_SIG.format(v)
}

/** Доля 0…1 → «12,4 %». */
export function fmtPercent(share: number | undefined | null): string {
  if (share === undefined || share === null || Number.isNaN(share)) return '—'
  return `${RU_1.format(share * 100)} %`
}

/** Число по правилу; точное значение — подсказкой. */
export function Num({ v, unit }: { v: number | undefined | null; unit?: string }) {
  const text = fmtNum(v)
  return (
    <span className="num" title={v === undefined || v === null ? undefined : `точно: ${v}${unit ? ` ${unit}` : ''}`}>
      {text}{unit ? ` ${unit}` : ''}
    </span>
  )
}

/** Доля — полоской с процентом: веса профиля не читаются долями e-6. */
export function ShareBar({ share, title }: { share: number; title?: string }) {
  const pct = share * 100
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}
      title={title ?? `доля: ${fmtPercent(share)}`}>
      <span style={{
        display: 'inline-block', width: 72, height: 7, borderRadius: 4,
        background: 'var(--container-2, #e8edf4)', overflow: 'hidden',
      }}>
        <span style={{
          display: 'block', height: '100%',
          width: `${pct < 1 && pct > 0 ? 1 : pct}%`,
          background: 'var(--accent, #2f6fd0)',
        }} />
      </span>
      <span className="secondary" style={{ fontSize: 11 }}>{fmtPercent(share)}</span>
    </span>
  )
}
