// Реестр одного вида (шип 5 §2): чипы отбора значениями из данных, таблица с
// колонкой отметок, карточка объекта вниз от строки, липкая панель «выбрано N».
//
// Одна поверхность — один компонент: Постановка и сцены 3–6 показывают ОДНИ И
// ТЕ ЖЕ реестры (registry/*.tsx), второй копии таблицы нет. Колонки,
// чипы, действия строки и массовые действия задаёт вид; строение — здесь.
import { Fragment, useMemo, useState, type ReactNode } from 'react'
import { Чипы, найдено, отобрать, type ЧипОтбора } from '../ui/chips'
import { ИконКнопка } from '../ui/iconbutton'
import { Отметка, ПанельМассово, useМассово, type МассовоеДействие, type Набор } from '../ui/mass'

export interface Колонка<R> {
  key: string
  /** Заголовок колонки словом; пустой — колонка без подписи (маркер). */
  title: string
  cell: (r: R) => ReactNode
  /** Подсказка заголовка: откуда значение, как его правят. */
  hint?: string
  className?: string
}

export function Реестр<R>({
  label, строки, ключ, колонки, чипы = [], поиск, хвост, действия, карточка, массово, наборы, пусто, рядом, начальныеЧипы,
}: {
  /** Имя реестра для чтения экрана: «реестр сторон». */
  label: string
  строки: R[]
  /** Ключ строки — код записи: по нему отметки, курсор и открытая карточка. */
  ключ: (r: R) => string
  колонки: Колонка<R>[]
  чипы?: ЧипОтбора<R>[]
  поиск?: { placeholder: string; text: (r: R) => string }
  /** Правый край ряда чипов: переключатель вида, «добавить». */
  хвост?: ReactNode
  /** Действия строки пиктограммами — после «карточки». */
  действия?: (r: R) => ReactNode
  /** Карточка объекта вниз от строки; закрывается той же строкой и Esc. */
  карточка?: (r: R, закрыть: () => void) => ReactNode
  /** Массовые действия: колонка отметок и липкая панель «выбрано N». */
  массово?: МассовоеДействие[]
  /** Умные наборы по видимым строкам: «все без нужд», «по роли». */
  наборы?: (видимые: R[]) => Набор[]
  /** Пустой реестр: что это значит и где записи рождаются. */
  пусто: ReactNode
  /** Панель рядом (сетка влияния): правее таблицы при ширине ≥ 1280, иначе под ней. */
  рядом?: ReactNode
  /** Чипы, включённые сразу: сцена 6 открывает нужды на «TBR». */
  начальныеЧипы?: string[]
}) {
  const [включены, setВключены] = useState<Set<string>>(() => new Set(начальныеЧипы ?? []))
  const [игла, setИгла] = useState('')
  const [открыта, setОткрыта] = useState<string | null>(null)

  const видимые = useMemo(() => {
    const поЧипам = отобрать(строки, чипы, включены)
    return поиск && игла.trim() ? поЧипам.filter((р) => найдено(поиск.text(р), игла)) : поЧипам
  }, [строки, чипы, включены, поиск, игла])
  const ключи = useMemo(() => видимые.map(ключ), [видимые, ключ])
  const м = useМассово(ключи, {
    onOpen: (к) => setОткрыта((было) => (было === к ? null : к)),
    действия: массово,
  })

  const переключитьЧип = (к: string) => setВключены((было) => {
    const стало = new Set(было)
    if (стало.has(к)) стало.delete(к); else стало.add(к)
    return стало
  })
  const колонок = колонки.length + (массово ? 1 : 0) + 1

  return (
    <div className="v2-reg" aria-label={label}>
      {(чипы.length > 0 || поиск || хвост) && (
        <Чипы строки={строки} чипы={чипы} включены={включены} onToggle={переключитьЧип} label={`отбор: ${label}`}>
          {поиск && (
            <input type="search" value={игла} placeholder={поиск.placeholder} aria-label={поиск.placeholder}
              onChange={(e) => setИгла(e.target.value)} />
          )}
          {хвост}
        </Чипы>
      )}
      <div className={рядом ? 'v2-reg__body v2-reg__body--side' : 'v2-reg__body'}>
        <div className="v2-reg__table" tabIndex={0} onKeyDown={м.клавиша}
          aria-label={`${label}: ↑ ↓ строка, Space отметить, Enter карточка`}>
          <table className="v2-table">
            <thead>
              <tr>
                {массово && (
                  <th className="v2-reg__mark">
                    <input type="checkbox" className="v2-mark" checked={м.всеВыбраны} onChange={м.всеРазом}
                      aria-label="выбрать все видимые" disabled={видимые.length === 0} />
                  </th>
                )}
                {колонки.map((к) => <th key={к.key} title={к.hint} className={к.className}>{к.title}</th>)}
                <th className="v2-acts" aria-label="действия строки" />
              </tr>
            </thead>
            <tbody>
              {видимые.length === 0 && (
                <tr>
                  <td colSpan={колонок} className="v2-empty">
                    {строки.length === 0 ? пусто : 'под отбор ничего не попало — снимите чип или поиск'}
                  </td>
                </tr>
              )}
              {видимые.map((р, i) => {
                const к = ключ(р)
                const откр = к === открыта
                const класс = [i === м.курсор ? 'v2-row--cursor' : '', откр ? 'v2-row--open' : ''].filter(Boolean).join(' ')
                return (
                  <Fragment key={к}>
                    <tr className={класс || undefined} onClick={() => м.setКурсор(i)}>
                      {массово && (
                        <td className="v2-reg__mark">
                          <Отметка ключ={к} выбрана={м.выбраны.has(к)} onToggle={м.отметить} />
                        </td>
                      )}
                      {колонки.map((кол) => <td key={кол.key} className={кол.className}>{кол.cell(р)}</td>)}
                      <td className="v2-acts">
                        {карточка && (
                          <ИконКнопка икон="карточка" слово={откр ? 'свернуть карточку' : 'карточка'} aria-expanded={откр}
                            onClick={() => setОткрыта(откр ? null : к)} />
                        )}
                        {действия?.(р)}
                      </td>
                    </tr>
                    {откр && карточка && (
                      <tr className="v2-card-row">
                        <td colSpan={колонок}>{карточка(р, () => setОткрыта(null))}</td>
                      </tr>
                    )}
                  </Fragment>
                )
              })}
            </tbody>
          </table>
        </div>
        {рядом && <aside className="v2-reg__side">{рядом}</aside>}
      </div>
      {массово && строки.length > 0 && (
        <ПанельМассово выбранные={м.выбранные} действия={массово} наборы={наборы?.(видимые)}
          onНабор={(н) => м.выбрать(н.keys)} onСнять={м.снять} />
      )}
    </div>
  )
}
