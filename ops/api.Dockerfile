# Образы ядра: API изделия и заполнение демо-проекта.
#
# ДВА ОБРАЗА, а не один. Фикстуры демонстрационного проекта живут в тестовом
# наборе исходников, и в образ изделия они не попадают (STEP-7-9 §7.2). Общий
# образ был бы удобнее ровно до того дня, когда демонстрационные данные
# оказались бы в рабочем проекте — и это заметили бы на обзоре, а не при сборке.
#
# Собирать целями: --target api и --target seed.

# --------------------------------------------------------------------------
FROM gradle:8.14.3-jdk21 AS build
# Образ gradle работает под пользователем gradle, а COPY кладёт файлы от root:
# без этого сборка падает на записи в /src. Слой сборки в итог не входит.
USER root
WORKDIR /src

# Сначала описание сборки — слой с загрузкой зависимостей переиспользуется,
# пока не менялись build-файлы.
COPY settings.gradle.kts build.gradle.kts ./
COPY core/mod/build.gradle.kts core/mod/
COPY core/com/build.gradle.kts core/com/
COPY core/req/build.gradle.kts core/req/
COPY core/usr/build.gradle.kts core/usr/
COPY core/out/build.gradle.kts core/out/
COPY core/bal/build.gradle.kts core/bal/
COPY core/ka/build.gradle.kts  core/ka/
COPY core/net/build.gradle.kts core/net/
COPY core/flw/build.gradle.kts core/flw/
COPY core/ai/build.gradle.kts  core/ai/
RUN gradle --no-daemon :core:com:dependencies --configuration runtimeClasspath > /dev/null 2>&1 || true

COPY core core
COPY schemas schemas
COPY db db
COPY spec spec
# Датасет населённых пунктов нужен заполнителю: демо-проект строит карту
# спроса из него же, что и импорт (второй копии чисел нет).
COPY data data
# apiJar — только main; seedJar — main + test. Собираются одной командой,
# раскладываются по разным целевым образам ниже.
RUN gradle --no-daemon :core:com:apiJar :core:com:seedJar

# --------------------------------------------------------------------------
# Изделие: API ядра. Фикстур нет, Python не нужен.
FROM eclipse-temurin:21-jre AS api
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /opt/orbita

# Нормативные каталоги нужны в рантайме: схемы проверяются при приёме,
# миграции применяются при старте (Main.kt).
COPY --from=build /src/schemas schemas
COPY --from=build /src/db/migrations db/migrations
COPY --from=build /src/core/com/build/libs/orbita-api.jar app.jar

ENV ORBITA_REPO_ROOT=/opt/orbita \
    ORBITA_HTTP_PORT=8090 \
    JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8"
EXPOSE 8090

# Схема сверяется с моделями при старте; расхождение роняет запуск — это
# и есть проверка живости, врать ей нечем.
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=6 \
  CMD curl -fsS http://127.0.0.1:8090/api/kinds > /dev/null || exit 1

CMD ["java", "-jar", "app.jar"]

# --------------------------------------------------------------------------
# Средство демонстрации: заполнение базы демо-проектом. Разовый запуск.
# Python нужен потому, что проект берётся из эталона spec/demo_project.py —
# второй копии демо-данных в проекте нет и быть не должно (ловушка 1).
FROM eclipse-temurin:21-jre AS seed
RUN apt-get update \
 && apt-get install -y --no-install-recommends python3 \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /opt/orbita

COPY --from=build /src/schemas schemas
COPY --from=build /src/db/migrations db/migrations
COPY --from=build /src/spec spec
COPY --from=build /src/data data
COPY --from=build /src/core/com/build/libs/orbita-seed.jar app.jar
COPY ops/seed.sh seed.sh
RUN chmod +x seed.sh

ENV ORBITA_REPO_ROOT=/opt/orbita \
    JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8"

CMD ["./seed.sh"]
