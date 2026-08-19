-- Исправление ограничения ai_needs_accept из V001 (STEP-4 §0.2).
--
-- Третий случай той же NULL-семантики (после V003 → V004): при
-- source = 'ai_proposed' и ОТСУТСТВУЮЩЕМ блоке `ai` левый операнд оператора `?`
-- равен NULL, оператор возвращает NULL, и `FALSE OR NULL` даёт NULL —
-- CHECK считается пройденным. Практическое следствие: предложение ИИ без
-- признака акцепта сохранялось и попадало в расчётные выборки, что нарушает
-- TZ-COM-005 (происхождение) и TZ-AI-004 (акцепт до влияния на расчёты).
--
-- Исправление: отсутствие блока или ключа приводится к FALSE явно.

ALTER TABLE params DROP CONSTRAINT IF EXISTS ai_needs_accept;
ALTER TABLE params ADD CONSTRAINT ai_needs_accept CHECK (
    COALESCE(provenance->>'source', '') <> 'ai_proposed'
    OR COALESCE(provenance->'ai' ? 'accepted', false)
);
