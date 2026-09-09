// Кто работает и как называются коды (шаг 15).
//
// Автор нужен КАЖДОМУ изменению: сессия параллельного проектирования — это
// несколько человек одновременно, и правка без автора это правка, о которой
// некого спросить. Учётных записей в системе нет, поэтому имя вводит сам
// инженер и оно живёт в браузере; подмена автора здесь не пресекается —
// это журнал работы, а не средство разграничения доступа.
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { api } from '../api/client'
import { edit } from '../api/edit'

const AUTHOR_KEY = 'orbita.author'

interface Session {
  author: string
  setAuthor: (name: string) => void
  /** В3: режим учёток. null — ещё выясняется; false — прежний режим. */
  authEnabled: boolean | null
  /** Вошедший пользователь; null при выключенном режиме или до входа. */
  user: { login: string; display_name: string; roles: Record<string, string> } | null
  /** Режим приёмочного стенда: учётки заведены, пароль не спрашивается. */
  standUsers: Array<{ login: string; display_name: string; roles: Record<string, string> }> | null
  /** Вход через Telegram включён на сервере (ADR-065). */
  telegram: boolean
  refreshWho: () => void
  /** Подпись кода перечисления; неизвестный код возвращается как есть. */
  label: (group: string, code: string | null | undefined) => string
  /** Подпись имени поля формы; неизвестное поле возвращается кодом. */
  fieldLabel: (name: string) => string
}

const SessionContext = createContext<Session>({
  author: '',
  setAuthor: () => {},
  authEnabled: null,
  user: null,
  standUsers: null,
  telegram: false,
  refreshWho: () => {},
  label: (_group, code) => code ?? '',
  fieldLabel: (name) => name,
})

export function SessionProvider({ children }: { children: ReactNode }) {
  const [author, setAuthorState] = useState<string>(() => {
    try {
      return localStorage.getItem(AUTHOR_KEY) ?? ''
    } catch {
      // приватное окно или запрет хранилища: имя спросим заново, работа не встанет
      return ''
    }
  })
  const [labels, setLabels] = useState<Record<string, Record<string, string>>>({})
  const [fieldLabels, setFieldLabels] = useState<Record<string, string>>({})
  const [authEnabled, setAuthEnabled] = useState<boolean | null>(null)
  const [user, setUser] = useState<Session['user']>(null)
  const [standUsers, setStandUsers] = useState<Session['standUsers']>(null)
  const [telegram, setTelegram] = useState(false)

  const refreshWho = useCallback(() => {
    api.whoami()
      .then((w) => {
        setAuthEnabled(w.enabled)
        setUser(w.user ?? null)
        setStandUsers(w.mode === 'stand' ? (w.stand_users ?? []) : null)
        setTelegram(w.mode === 'telegram')
        // стенд без паролей: без сессии входим первой учёткой (руководитель)
        if (w.mode === 'stand' && w.enabled && !w.user && (w.stand_users ?? []).length > 0) {
          api.standLogin(w.stand_users![0].login).then(() => api.whoami()).then((w2) => {
            setUser(w2.user ?? null)
            if (w2.user) setAuthorState(w2.user.display_name)
          }).catch(() => undefined)
        }
        // автор из учётки — поле автора больше не источник правды
        if (w.enabled && w.user) setAuthorState(w.user.display_name)
      })
      .catch(() => setAuthEnabled(false))
  }, [])

  useEffect(refreshWho, [refreshWho])

  useEffect(() => {
    // Подписи приходят с сервера одной таблицей: словари, рассыпанные
    // по экранам, уже приводили к тому, что на одном экране класс подписан,
    // а на соседнем выходит кодом.
    edit.enumLabels().then(setLabels).catch(() => setLabels({}))
    edit.fieldLabels().then(setFieldLabels).catch(() => setFieldLabels({}))
  }, [])

  const setAuthor = useCallback((name: string) => {
    setAuthorState(name)
    try {
      localStorage.setItem(AUTHOR_KEY, name)
    } catch {
      // не сохранилось — имя действует до перезагрузки, и это лучше отказа
    }
  }, [])

  const label = useCallback(
    (group: string, code: string | null | undefined) => {
      if (!code) return '—'
      return labels[group]?.[code] ?? code
    },
    [labels],
  )

  const fieldLabel = useCallback(
    (name: string) => fieldLabels[name] ?? name,
    [fieldLabels],
  )

  const value = useMemo(
    () => ({ author, setAuthor, label, fieldLabel, authEnabled, user, standUsers, telegram, refreshWho }),
    [author, setAuthor, label, fieldLabel, authEnabled, user, standUsers, telegram, refreshWho],
  )
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>
}

export function useSession(): Session {
  return useContext(SessionContext)
}

/** Поле имени в шапке: без него правка не отправляется, и это видно сразу. */
export function AuthorField() {
  const { author, setAuthor, authEnabled, user, standUsers, refreshWho } = useSession()
  // Режим приёмочного стенда (ПМИ-4): учётка переключается селектором
  // «имя · роль», пароль не спрашивается. Права при этом настоящие — их
  // проверяет сервер по роли выбранной учётки.
  if (authEnabled && user && standUsers) {
    // роль — словами, как в реестре учёток, а не кодом
    const ROLE_TITLE: Record<string, string> = {
      lead: 'руководитель', lead_se: 'ведущий СИ', specialist: 'инженер',
      sma: 'SMA', da_review: 'DA', reader: 'читатель',
    }
    const roleOf = (u: { roles: Record<string, string> }) => {
      const roles = Object.values(u.roles)
      return roles.length ? (ROLE_TITLE[roles[0]] ?? roles[0]) : 'без роли'
    }
    return (
      <label className="author" title="учётка приёмочного стенда: переключение без пароля, права — по роли">
        <span className="secondary">учётка</span>
        <select value={user.login}
          onChange={(e) => { api.standLogin(e.target.value).then(refreshWho).catch(() => undefined) }}>
          {standUsers.map((u) => (
            <option key={u.login} value={u.login}>{u.display_name} · {roleOf(u)}</option>
          ))}
        </select>
      </label>
    )
  }
  // В3: при включённых учётках автор — из сессии; поле ввода уходит,
  // остаётся индикатор входа и выход
  if (authEnabled && user) {
    return (
      <span className="author" title={`вы вошли как ${user.login}; автор изменений — из учётки`}>
        <span className="secondary">Инженер</span>{' '}
        <b>{user.display_name}</b>{' '}
        <button className="btn" onClick={() => { api.logout().finally(refreshWho) }}>выйти</button>
      </span>
    )
  }
  if (authEnabled) return null // оверлей входа рисует LoginGate
  return (
    <label className="author" title="Автор изменений: записывается в каждую версию объекта">
      <span className="secondary">Инженер</span>
      <input
        value={author}
        placeholder="представьтесь"
        aria-label="Автор изменений"
        onChange={(e) => setAuthor(e.target.value)}
      />
    </label>
  )
}

/** В3: ворота входа — при включённых учётках без сессии работа не идёт. */
export function LoginGate({ children }: { children: ReactNode }) {
  const { authEnabled, user, standUsers, telegram, refreshWho } = useSession()
  const [login, setLogin] = useState('')
  const [password, setPassword] = useState('')
  const [failure, setFailure] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  if (authEnabled !== true || user) return <>{children}</>
  if (standUsers) {
    // приёмочный стенд: сессия ставится сама первой учёткой — пароля нет
    return <div className="empty">вход учёткой стенда…</div>
  }
  if (telegram) {
    return (
      <div className="login-gate">
        <div className="login-card">
          <h2>Вход в «Орбиту»</h2>
          <p className="secondary">Вход через Telegram: членство в группе проекта и есть пропуск.</p>
          <TelegramLogin onDone={refreshWho} />
        </div>
      </div>
    )
  }
  const enter = () => {
    if (busy || !login || !password) return
    setBusy(true)
    setFailure(null)
    api.login(login, password)
      .then(refreshWho)
      .catch(() => setFailure('неверный логин или пароль'))
      .finally(() => setBusy(false))
  }
  return (
    <div className="login-gate">
      <div className="login-card">
        <h2>Вход в «Орбиту»</h2>
        <p className="secondary">Учётные записи включены: автор каждой правки — из учётки.</p>
        <label className="np-label" htmlFor="lg-login">Логин</label>
        <input className="np-name" id="lg-login" value={login} autoFocus
          onChange={(e) => setLogin(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') enter() }} />
        <label className="np-label" htmlFor="lg-pass" style={{ marginTop: 10 }}>Пароль</label>
        <input className="np-name" id="lg-pass" type="password" value={password}
          onChange={(e) => setPassword(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') enter() }} />
        {failure && <div className="np-err">{failure}</div>}
        <div className="np-actions">
          <button title="введите логин и пароль" className="np-btn np-pri" disabled={busy || !login || !password} onClick={enter}>
            Войти
          </button>
        </div>
      </div>
    </div>
  )
}

/**
 * Кнопка входа через Telegram (ADR-065): сервер выдаёт ссылку на общего бота,
 * человек жмёт Start, клиент опрашивает статус раз в две секунды до approved —
 * сессия ставится сервером в cookie, дальше обычная работа.
 */
export function TelegramLogin({ onDone }: { onDone: () => void }) {
  const [link, setLink] = useState<string | null>(null)
  const [state, setState] = useState<'idle' | 'waiting' | 'denied' | 'expired' | 'error'>('idle')
  const [why, setWhy] = useState<string | null>(null)
  const begin = () => {
    setWhy(null)
    api.authStart()
      .then((s) => {
        setLink(s.deep_link)
        setState('waiting')
        window.open(s.deep_link, '_blank', 'noopener')
        const t0 = Date.now()
        const tick = () => {
          api.authStatus(s.token)
            .then((r) => {
              if (r.status === 'approved') { onDone(); return }
              if (r.status === 'denied' || r.status === 'expired') { setState(r.status); return }
              if (Date.now() - t0 > 5 * 60 * 1000) { setState('expired'); return }
              window.setTimeout(tick, 2000)
            })
            .catch((e) => { setState('error'); setWhy(String(e)) })
        }
        window.setTimeout(tick, 2000)
      })
      .catch((e) => { setState('error'); setWhy(String(e)) })
  }
  return (
    <div className="np-actions" style={{ flexDirection: 'column', alignItems: 'flex-start', gap: 8 }}>
      <button type="button" className="np-btn np-pri" onClick={begin} disabled={state === 'waiting'}
        title="откроется общий бот входа; нажмите в нём Start">
        {state === 'waiting' ? 'ждём подтверждения в Telegram…' : 'Войти через Telegram'}
      </button>
      {link && state === 'waiting' && (
        <span className="secondary">Бот не открылся? <a href={link} target="_blank" rel="noreferrer">открыть ссылку</a></span>
      )}
      {state === 'denied' && <div className="np-err">вас нет в группе проекта — попросите руководителя добавить</div>}
      {state === 'expired' && <div className="np-err">время входа вышло — нажмите ещё раз</div>}
      {state === 'error' && <div className="np-err">вход не удался: {why}</div>}
    </div>
  )
}

