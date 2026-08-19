-- Шаг 2 (TZ-REQ-003, Р9/ADR-009): ссылка «сервис → требование» несёт класс
-- потребителя. Обязательность класса зависит от типа источника связи и
-- обеспечивается прикладным правилом (core/req): CHECK уровня таблицы не может
-- заглянуть в objects. Здесь — только допустимые значения.
ALTER TABLE links ADD COLUMN consumer_class text
    CONSTRAINT links_consumer_class_valid
    CHECK (consumer_class IS NULL OR consumer_class IN ('A_prime', 'B_prime', 'C_prime'));
