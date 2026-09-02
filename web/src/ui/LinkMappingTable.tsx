// Г-01 (ПМИ-3): ссылка пакета на чужой проект — не отказ строки, а диалог
// сопоставления «нужда исходного проекта (с её формулировкой) → нужда этого
// проекта» с автопредложением по совпадению формулировки и пикером. Одна таблица на оба
// входа — службу ИИ и загрузку пачкой: изоляция проектов не ослабляется,
// кросс-проектная ссылка не записывается никогда; несопоставленное остаётся
// без связи и честно даёт разрыв трассировки.
import type { LinkMappingView } from '../api/types'
import { Tooltip } from './Tooltip'

export function LinkMappingTable({ mapping, choice, onChoice }: {
  mapping: LinkMappingView
  choice: Record<string, string>
  onChoice: (ref: string, id: string) => void
}) {
  return (
    <div className="rr-expand" style={{ display: 'block', padding: '8px 10px', marginBottom: 8 }}>
      <div className="secondary" style={{ marginBottom: 6 }}>{mapping.summary}</div>
      <table className="grid">
        <thead>
          <tr>
            <th style={{ width: 110 }}>Ссылка пакета</th>
            <th>Что это в исходном проекте</th>
            <th style={{ width: 260 }}>Чем заменить здесь</th>
          </tr>
        </thead>
        <tbody>
          {mapping.links.map((l) => (
            <tr key={l.ref}>
              <td className="mono">
                {l.ref}
                {l.from_project && <div className="secondary">{l.from_project}</div>}
              </td>
              <td className="wrap">
                {l.text || (
                  <Tooltip text="объект исходного проекта недоступен — формулировку показать неоткуда">
                    <span className="secondary">—</span>
                  </Tooltip>
                )}
              </td>
              <td>
                <select value={choice[l.ref] ?? ''}
                  title="объект этого проекта, которым заменится чужая ссылка; «без связи» — строка ляжет с разрывом трассировки"
                  onChange={(e) => onChoice(l.ref, e.target.value)}>
                  <option value="">— без связи (разрыв трассировки) —</option>
                  {l.candidates.map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.id} · {c.text.slice(0, 46)}{c.percent > 0 ? ` (${c.percent}%)` : ''}
                    </option>
                  ))}
                </select>
                {l.suggested && choice[l.ref] === l.suggested.id && (
                  <div className="secondary">предложено по совпадению формулировки</div>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className="secondary" style={{ marginTop: 6 }}>
        Связь запишется только на объект этого проекта. Оставленное «без связи»
        уйдёт в разрыв трассировки, а строка, которой связь обязательна по схеме,
        честно не пройдёт ворота.
      </div>
    </div>
  )
}

/** Выбор → карта «чужая ссылка → объект этого проекта» без пустых пар. */
export function chosenMapping(choice: Record<string, string>): Record<string, string> | undefined {
  const карта = Object.fromEntries(Object.entries(choice).filter(([, v]) => v))
  return Object.keys(карта).length > 0 ? карта : undefined
}
