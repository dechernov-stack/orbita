-- В3: локальные учётки, сессии, роли на проект. Проверка прав — на сервере
-- (ловушка 5: кнопку спрятать можно, маршрут обязан отказать сам).
CREATE TABLE users (
    login         text PRIMARY KEY CHECK (login ~ '^[a-z0-9_.-]{2,32}$'),
    password_hash text NOT NULL,
    salt          text NOT NULL,
    display_name  text NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE sessions (
    token      text PRIMARY KEY,
    login      text NOT NULL REFERENCES users(login) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL
);
CREATE INDEX sessions_login ON sessions(login);

-- Роли из регламентов: руководитель · ведущий СИ · специалист · SMA ·
-- DA/обзор · читатель
CREATE TABLE project_roles (
    project_id text NOT NULL,
    login      text NOT NULL REFERENCES users(login) ON DELETE CASCADE,
    role       text NOT NULL CHECK (role IN
        ('lead', 'lead_se', 'specialist', 'sma', 'da_review', 'reader')),
    PRIMARY KEY (project_id, login)
);
