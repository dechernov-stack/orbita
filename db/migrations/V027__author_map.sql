-- В3 §2.2: история неприкосновенна — строки author не переписываются.
-- Карта «строка автора → учётка» связывает прошлое с настоящим для отчётов.
CREATE TABLE author_map (
    author_string text PRIMARY KEY,
    login         text NOT NULL REFERENCES users(login) ON DELETE CASCADE
);
