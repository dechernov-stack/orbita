# StrictDoc-канал (ADR-049): strictdoc (Apache-2.0) в отдельном контейнере —
# .sdoc по грамматике Орбиты, штатные ReqIF/HTML/PDF/XLSX и импорт. Ядро
# форматов не знает. Версия закреплена решением владельца.
FROM python:3.12-slim
RUN pip install --no-cache-dir strictdoc==0.29.0
WORKDIR /app
COPY ops/strictdoc/strictdoc_service.py .
ENV ORBITA_STRICTDOC_PORT=8093
EXPOSE 8093
HEALTHCHECK --interval=10s --timeout=3s --retries=6 \
  CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8093/health', timeout=2).status==200 else 1)"
CMD ["python", "strictdoc_service.py"]
