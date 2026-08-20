# Служба обмена ReqIF (TZ-OUT-005, ADR-023).
#
# Отдельный контейнер, а не зависимость JVM-образа: развёртывание одной
# командой принято критерием приёмки шага 10, второй рантайм внутри образа
# ядра не нужен. Граница — обычный вызов службы по HTTP.
FROM python:3.12-slim

# Версия закреплена: та же, что в ADR-023 и в CI. Плавающая версия дала бы
# файлы, которые CI не видел.
RUN pip install --no-cache-dir reqif==0.0.47

WORKDIR /app
COPY ops/exchange/reqif_service.py .

ENV ORBITA_EXCHANGE_PORT=8091
EXPOSE 8091
HEALTHCHECK --interval=10s --timeout=3s --retries=6 \
  CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8091/health', timeout=2).status==200 else 1)"

CMD ["python", "reqif_service.py"]
