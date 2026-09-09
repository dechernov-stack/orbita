-- ADR-065: вход через Telegram заводит учётку «tg:<id>». Проверка логина
-- из В3 (латиница/цифры без двоеточия) её отвергала — первый вход на стенде
-- падал бы на INSERT. Второй допустимый вид логина назван явно, старый не
-- расширен вслепую.
ALTER TABLE users DROP CONSTRAINT users_login_check;
ALTER TABLE users ADD CONSTRAINT users_login_check
    CHECK (login ~ '^([a-z0-9_.-]{2,32}|tg:[0-9]{1,20})$');
