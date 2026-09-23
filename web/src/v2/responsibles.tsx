// Ответственные сцен (истина 23.09, scene_responsible_rule): ответственный
// живёт в ОКНЕ ПЛАНА работ фазы; пока не назначен — умолчание из ролей
// проекта по роли сцены в шаблоне (РП / ведущий СИ). Назначается здесь — на
// сцене A1, сцене 1 и в паспорте — и в самом плане; условие A1 читает то же
// окно, второго места хранения нет.
import { useCallback, useEffect, useState } from 'react'
import { api, type Phase } from './api'
import { РОЛЬ } from './activity'

type Окно = { scene: string; start: string; end: string; responsible?: string }
type План = { planned: boolean; gate_dates: { gate: string; date: string }[]; scene_windows: Окно[] }

/** Умолчание ответственного — учётка с ролью сцены; только РП и ведущий СИ (истина). */
export function умолчаниеПоРоли(роли: Record<string, string>, роль: string): string | null {
  if (роль !== 'lead' && роль !== 'lead_se') return null
  return Object.entries(роли).find(([, р]) => р === роль)?.[0] ?? null
}

export function ОтветственныеСцен({ project, onChanged }: { project: string; onChanged?: () => void }) {
  const [фаза, setФаза] = useState<Phase | null>(null)
  const [план, setПлан] = useState<План | null>(null)
  const [роли, setРоли] = useState<Record<string, string>>({})
  const [учётки, setУчётки] = useState<{ login: string; display_name: string }[]>([])
  const [отказ, setОтказ] = useState<string | null>(null)
  /** Сцена, чьё окно сейчас пишется: пока пишется — второй раз не нажать. */
  const [пишется, setПишется] = useState<string | null>(null)

  const перечитать = useCallback(() => {
    api.phase(project).then(setФаза).catch((e) => setОтказ(String(e.message ?? e)))
    api.plan(project)
      .then((п) => setПлан({ planned: п.planned, gate_dates: п.gate_dates ?? [], scene_windows: п.scene_windows ?? [] }))
      .catch((e) => setОтказ(String(e.message ?? e)))
    api.projectRoles(project).then(setРоли).catch(() => setРоли({}))
    fetch('/api/auth/users').then((r) => (r.ok ? r.json() : { users: [] }))
      .then((d) => setУчётки(d.users ?? [])).catch(() => setУчётки([]))
  }, [project])
  useEffect(перечитать, [перечитать])

  const имя = (логин: string) => учётки.find((у) => у.login === логин)?.display_name ?? логин
  const окно = (ключ: string) => план?.scene_windows.find((о) => о.scene === ключ)

  const назначить = (ключ: string, логин: string) => {
    if (!план || !фаза) return
    const окна = план.scene_windows.map((о) => (о.scene === ключ ? { ...о, responsible: логин || undefined } : о))
    setПишется(ключ); setОтказ(null)
    api.setPlan(project, { phase: фаза.phase, author: 'инженер', gate_dates: план.gate_dates, scene_windows: окна })
      .then(() => { перечитать(); onChanged?.() })
      .catch((e) => setОтказ(String(e.message ?? e)))
      .finally(() => setПишется(null))
  }

  if (отказ && !фаза) return <div className="v2-panel" data-why="почему-нельзя"><h3>Ответственные сцен</h3><div className="v2-locked">{отказ}</div></div>
  if (!фаза || !план) return <div className="v2-panel" data-why="работа"><h3>Ответственные сцен</h3><div className="v2-empty">Читаю план фазы…</div></div>

  // Экземпляры сцены (A4:EL-SC) живут окном сцены шаблона: строка — одна на сцену шаблона,
  // подпись — без имени узла, с числом экземпляров.
  const сцены = фаза.scenes
    .filter((с, i, все) => все.findIndex((д) => (д.instance_of ?? д.key) === (с.instance_of ?? с.key)) === i)
    .map((с) => {
      const экземпляров = с.instance_of ? фаза.scenes.filter((д) => д.instance_of === с.instance_of).length : 0
      const title = с.instance_of
        ? `${с.title.split(' элемента ')[0]} элемента · экземпляров ${экземпляров}`
        : с.title
      return { ключ: с.instance_of ?? с.key, title, role: с.role }
    })
  const назначенных = сцены.filter((с) => окно(с.ключ)?.responsible).length
  const поУмолчанию = сцены.filter((с) => !окно(с.ключ)?.responsible && умолчаниеПоРоли(роли, с.role)).length
  const безОтветственного = сцены.length - назначенных - поУмолчанию

  return (
    <div className="v2-panel" data-why="работа">
      <h3>
        Ответственные сцен
        <span className="v2-cnt">
          назначено {назначенных} · по умолчанию {поУмолчанию}
          {безОтветственного > 0 ? ` · без ответственного ${безОтветственного}` : ''}
        </span>
      </h3>
      <div className="v2-empty__why">
        Ответственный живёт в окне сцены плана работ фазы; пустое окно берёт умолчание из ролей проекта по роли сцены:
        руководитель — сценам руководителя, ведущий СИ — сценам ведущего. Роли назначаются в паспорте.
      </div>
      {отказ && <div className="v2-locked">{отказ}</div>}
      <table className="v2-table">
        <thead><tr><th>Сцена</th><th>Роль сцены</th><th>Ответственный</th></tr></thead>
        <tbody>
          {сцены.map((с) => {
            const о = окно(с.ключ)
            const умолчание = умолчаниеПоРоли(роли, с.role)
            const помеха = !о ? 'сначала окно сцены в плане работ фазы: ответственный живёт в окне'
              : пишется === с.ключ ? 'окно записывается' : null
            return (
              <tr key={с.ключ}>
                <td>{с.ключ} · {с.title}</td>
                <td>{РОЛЬ[с.role] ?? с.role}</td>
                <td>
                  <select aria-label={`ответственный сцены ${с.ключ}`} value={о?.responsible ?? ''} disabled={Boolean(помеха)}
                    title={помеха ?? (о?.responsible
                      ? `назначен в окне плана: ${имя(о.responsible)}`
                      : умолчание ? `не назначен — по умолчанию ${имя(умолчание)} (${РОЛЬ[с.role] ?? с.role})` : 'не назначен и умолчания по роли нет')}
                    onChange={(e) => назначить(с.ключ, e.target.value)}>
                    <option value="">{умолчание ? `по умолчанию: ${имя(умолчание)}` : '— не назначен —'}</option>
                    {учётки.map((у) => <option key={у.login} value={у.login}>{у.display_name} · {у.login}</option>)}
                  </select>
                  {!о && <span className="v2-dim"> · окна нет</span>}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}
