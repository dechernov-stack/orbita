-- Служебный вход инструментов стенда (по слову владельца 27.09): учётка
-- «service:orbita-tools» — своя личность для загрузчика полок и прогона, вход
-- ключом ORBITA_SERVICE_KEY без Telegram. Проверка логина из В3 (V026, V047)
-- её отвергала (двоеточие — только у tg:<id>). Третий допустимый вид логина
-- назван явно, как tg: в V047, а не расширен вслепую.
ALTER TABLE users DROP CONSTRAINT users_login_check;
ALTER TABLE users ADD CONSTRAINT users_login_check
    CHECK (login ~ '^([a-z0-9_.-]{2,32}|tg:[0-9]{1,20}|service:[a-z0-9_-]{1,24})$');
