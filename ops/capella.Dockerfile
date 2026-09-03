# Адаптер Capella (ADR-048): capellambse (Apache-2.0) в отдельном контейнере,
# только чтение модели. Включается профилем compose `capella` и переменной
# ORBITA_CAPELLA_MODEL; без модели ядро показывает fixture с баннером.
FROM python:3.12-slim
# Версия закреплена решением владельца (0.8.0); плавающая версия дала бы
# элементы, которых CI не видел.
RUN pip install --no-cache-dir capellambse==0.8.0
WORKDIR /app
COPY ops/capella/capella_service.py .
ENV ORBITA_CAPELLA_PORT=8092
EXPOSE 8092
HEALTHCHECK --interval=10s --timeout=3s --retries=6 \
  CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8092/health', timeout=2).status==200 else 1)"
CMD ["python", "capella_service.py"]
