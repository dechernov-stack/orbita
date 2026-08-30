// Учётки и роли из интерфейса (спринт MVP, блокер Е3): регистрация и
// назначение ролей были только в API — «под DA» штатно не пройти.
// Первая учётка включает режим (В3) и логинит; дальше заводит лид;
// роль в текущем проекте назначает лид. Отказы — сервером.
import { useState } from 'react'
import { api } from '../api/client'
import { currentProject } from '../api/project'
import { useSession } from './session'

const ROLES: Array<{ key: string; title: string }> = [
  { key: 'lead', title: 'руководитель (lead)' },
  { key: 'lead_se', title: 'ведущий СИ (lead_se)' },
  { key: 'specialist', title: 'специалист' },
  { key: 'sma', title: 'SMA' },
  { key: 'da_review', title: 'DA (обзор и фиксация точек)' },
]

export function Accounts() {
  const { authEnabled, user, refreshWho } = useSession()
  const [open, setOpen] = useState(false)
  const [login, setLogin] = useState('')
  const [password, setPassword] = useState('')
  const [name, setName] = useState('')
  const [roleLogin, setRoleLogin] = useState('')
  const [role, setRole] = useState('da_review')
  const [note, setNote] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const isLead = Boolean(user && Object.values(user.roles).includes('lead'))
  if (authEnabled == null) return null
  if (authEnabled && !user) return null // оверлей входа рисует LoginGate

  const register = () => {
    if (busy || !login.trim() || password.length < 8) return
    setBusy(true)
    setNote(null)
    api.registerUser(login.trim(), password, name.trim() || login.trim())
      .then(() => (authEnabled ? Promise.resolve(undefined) : api.login(login.trim(), password).then(() => undefined)))
      .then(() => {
        setNote(authEnabled ? `учётка ${login.trim()} заведена` : 'учётки включены — вы вошли')
        setLogin(''); setPassword(''); setName('')
        refreshWho()
      })
      .catch((e) => setNote(String(e)))
      .finally(() => setBusy(false))
  }

  const assign = () => {
    const project = currentProject()
    if (busy || !project || !roleLogin.trim()) return
    setBusy(true)
    setNote(null)
    api.setRole(project, roleLogin.trim(), role)
      .then(() => setNote(`роль ${role} выдана ${roleLogin.trim()} в ${project}`))
      .catch((e) => setNote(String(e)))
      .finally(() => setBusy(false))
  }

  return (
    <span style={{ position: 'relative' }}>
      <button type="button" className="np-linkish" onClick={() => setOpen((v) => !v)}>
        {authEnabled ? 'учётки…' : 'включить учётки…'}
      </button>
      {open && (
        <span className="rr-cfg" style={{ right: 0, top: 28, width: 300 }}>
          <h4>
            {authEnabled ? 'Учётки и роли' : 'Включение учёток'}
            <button type="button" className="rr-assign" onClick={() => setOpen(false)}>закрыть</button>
          </h4>
          {!authEnabled && (
            <div className="secondary" style={{ fontSize: 11.5, marginBottom: 6 }}>
              Первая учётка включает режим: автор правок — из учётки,
              вы получите роль руководителя во всех проектах.
            </div>
          )}
          {(!authEnabled || isLead) ? (
            <>
              <div className="rr-sec">{authEnabled ? 'Новая учётка (заводит лид)' : 'Первая учётка'}</div>
              <input className="rr-search" style={{ width: '100%', marginBottom: 4 }} placeholder="логин"
                value={login} onChange={(e) => setLogin(e.target.value)} />
              <input className="rr-search" style={{ width: '100%', marginBottom: 4 }} type="password"
                placeholder="пароль (не короче 8)" value={password} onChange={(e) => setPassword(e.target.value)} />
              <input className="rr-search" style={{ width: '100%', marginBottom: 6 }} placeholder="имя (как показывать)"
                value={name} onChange={(e) => setName(e.target.value)} />
              <button title="нужны логин и пароль не короче восьми знаков" type="button" className="rr-btn rr-btn--pri" disabled={busy || !login.trim() || password.length < 8}
                onClick={register}>
                {authEnabled ? 'Завести учётку' : 'Включить и войти'}
              </button>
            </>
          ) : (
            <div className="secondary" style={{ fontSize: 11.5 }}>
              учётки заводит руководитель проекта
            </div>
          )}
          {authEnabled && isLead && (
            <>
              <div className="rr-sec">Роль в текущем проекте</div>
              <input className="rr-search" style={{ width: '100%', marginBottom: 4 }} placeholder="логин учётки"
                value={roleLogin} onChange={(e) => setRoleLogin(e.target.value)} />
              <span className="rr-col" style={{ display: 'block', marginBottom: 6 }}>
                {ROLES.map((r) => (
                  <label key={r.key} style={{ display: 'block', fontSize: 12 }}>
                    <input type="radio" checked={role === r.key} onChange={() => setRole(r.key)} /> {r.title}
                  </label>
                ))}
              </span>
              <button title="назовите учётку, которой назначается роль" type="button" className="rr-btn" disabled={busy || !roleLogin.trim()} onClick={assign}>
                Назначить роль
              </button>
            </>
          )}
          {note && <div className="secondary" style={{ fontSize: 11.5, marginTop: 6 }}>{note}</div>}
        </span>
      )}
    </span>
  )
}
