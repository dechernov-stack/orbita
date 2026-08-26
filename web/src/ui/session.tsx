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

  const refreshWho = useCallback(() => {
    api.whoami()
      .then((w) => {
        setAuthEnabled(w.enabled)
        setUser(w.user ?? null)
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
    () => ({ author, setAuthor, label, fieldLabel, authEnabled, user, refreshWho }),
    [author, setAuthor, label, fieldLabel, authEnabled, user, refreshWho],
  )
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>
}

export function useSession(): Session {
  return useContext(SessionContext)
}

/** Поле имени в шапке: без него правка не отправляется, и это видно сразу. */
export function AuthorField() {
  const { author, setAuthor, authEnabled, user, refreshWho } = useSession()
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
  const { authEnabled, user, refreshWho } = useSession()
  const [login, setLogin] = useState('')
  const [password, setPassword] = useState('')
  const [failure, setFailure] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  if (authEnabled !== true || user) return <>{children}</>
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
          <button className="np-btn np-pri" disabled={busy || !login || !password} onClick={enter}>
            Войти
          </button>
        </div>
      </div>
    </div>
  )
}
