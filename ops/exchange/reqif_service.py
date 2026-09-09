#!/usr/bin/env python3
"""Служба обмена ReqIF — сторона РАЗБОРА (TZ-OUT-005, ADR-023, ADR-064).

Читает ReqIF библиотекой `reqif` (strictdoc-project): имена атрибутов берутся
из определений типов файла, а не из нашего отображения, поэтому читаются и
ЧУЖИЕ файлы; значения перечислений возвращаются именами. Разбирать чужой
кривой ReqIF самостоятельно — задача, которую не стоит брать: парсер
библиотеки намеренно устойчив к неполным схемам чужих инструментов.

Собственной сборки ReqIF здесь больше нет: 09.09.2026 пять «да» сверки
каналов на данных стенда достигнуты, и выгрузку делает StrictDoc из .sdoc по
грамматике Орбиты (ops/strictdoc, ADR-049). Прежний путь /reqif/export
отвечает 410 с адресом нового канала — молчаливый 404 выглядел бы поломкой.

HTTP-интерфейс (для контейнера): POST /reqif/parse, GET /health.
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from reqif.models.reqif_spec_object_type import ReqIFSpecObjectType
from reqif.models.reqif_spec_relation_type import ReqIFSpecRelationType
from reqif.models.reqif_types import SpecObjectAttributeType
from reqif.parser import ReqIFParser

ATTR_TYPES = {
    'string': SpecObjectAttributeType.STRING,
    'integer': SpecObjectAttributeType.INTEGER,
    'real': SpecObjectAttributeType.REAL,
    'date': SpecObjectAttributeType.DATE,
    'boolean': SpecObjectAttributeType.BOOLEAN,
    'enum': SpecObjectAttributeType.ENUMERATION,
    'xhtml': SpecObjectAttributeType.XHTML,
}


def parse_reqif(path):
    """Файл ReqIF → заголовок, типы объектов, объекты с атрибутами по именам, связи."""
    bundle = ReqIFParser.parse(path)
    content = bundle.core_content.req_if_content

    enum_names = {}
    for dt in content.data_types or []:
        for value in getattr(dt, 'values', None) or []:
            enum_names[value.identifier] = value.long_name or value.key

    attr_names, attr_kinds, type_names = {}, {}, {}
    kind_by_attr_type = {v: k for k, v in ATTR_TYPES.items()}
    object_types = {}
    for st in content.spec_types or []:
        if isinstance(st, ReqIFSpecObjectType):
            type_names[st.identifier] = st.long_name or st.identifier
            attrs = {}
            for ad in st.attribute_definitions or []:
                name = ad.long_name or ad.identifier
                attr_names[ad.identifier] = name
                kind = kind_by_attr_type[ad.attribute_type]
                attr_kinds[ad.identifier] = kind
                attrs[name] = kind
            object_types[st.identifier] = {'long_name': type_names[st.identifier],
                                           'attributes': attrs}

    def value_of(attr):
        kind = attr_kinds.get(attr.definition_ref)
        if kind == 'enum':
            refs = attr.value if isinstance(attr.value, list) else [attr.value]
            names = [enum_names.get(r, r) for r in refs]
            return names[0] if len(names) == 1 else names
        if kind == 'xhtml':
            # Снимается обёртка div, добавленная при выгрузке: круговой обмен
            # обязан вернуть исходный текст, а не текст в упаковке
            return _strip_xhtml_div(attr.value_stripped_xhtml or attr.value)
        if kind == 'real':
            return float(attr.value)
        if kind == 'integer':
            return int(attr.value)
        if kind == 'boolean':
            return attr.value == 'true'
        return attr.value

    # Стандартные атрибуты OMG ReqIF (ForeignID · Text · Name · ChapterName)
    # опознаются ЗДЕСЬ, на границе библиотеки: ядро формата не знает и
    # получает объект с полями id/text/name, если файл их несёт, и со всеми
    # атрибутами как есть — чужой профиль атрибутов не теряется и не
    # переименовывается (ADR-064, остаток сноса).
    STD = {'ReqIF.ForeignID': 'id', 'ReqIF.Text': 'text', 'ReqIF.Name': 'name', 'ReqIF.ChapterName': 'chapter'}

    def std_of(values):
        return {STD[k]: v for k, v in values.items() if k in STD and v not in (None, '')}

    objects = []
    for so in content.spec_objects or []:
        values = {attr_names.get(a.definition_ref, a.definition_ref): value_of(a) for a in so.attributes}
        objects.append({'identifier': so.identifier, 'type': so.spec_object_type,
                        'type_name': type_names.get(so.spec_object_type, so.spec_object_type),
                        'values': values, 'std': std_of(values)})
    relations = [
        {'identifier': r.identifier, 'type': r.relation_type_ref,
         'source': r.source, 'target': r.target}
        for r in content.spec_relations or []
    ]
    # Типы связей — их именами: ядро показывает роль словами файла, не идентификатором
    relation_types = {st.identifier: (st.long_name or st.identifier)
                      for st in content.spec_types or [] if isinstance(st, ReqIFSpecRelationType)}
    return {
        'title': bundle.req_if_header.title if bundle.req_if_header else None,
        'exported_at': bundle.req_if_header.creation_time if bundle.req_if_header else None,
        'object_types': object_types,
        'relation_types': relation_types,
        'objects': objects,
        'relations': relations,
    }


def _strip_xhtml_div(text):
    """Снимается обёртка `<xhtml:div>…</xhtml:div>`: текст возвращается текстом, не упаковкой."""
    if text is None:
        return None
    from xml.sax.saxutils import unescape
    s = text.strip()
    for prefix, suffix in (('<xhtml:div>', '</xhtml:div>'), ('<div>', '</div>')):
        if s.startswith(prefix) and s.endswith(suffix):
            return unescape(s[len(prefix):-len(suffix)])
    return unescape(s)


class Handler(BaseHTTPRequestHandler):

    def _reply(self, status, body, content_type='application/json; charset=utf-8'):
        data = body if isinstance(body, bytes) else body.encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == '/health':
            self._reply(200, json.dumps({'status': 'ok', 'service': 'reqif'}))
        else:
            self._reply(404, json.dumps({'error': 'unknown path'}))

    def do_POST(self):
        raw = self.rfile.read(int(self.headers.get('Content-Length', '0')))
        try:
            if self.path == '/reqif/export':
                self._reply(410, json.dumps({'error': 'собственная сборка ReqIF снесена (ADR-064): '
                                                      'выгрузка — StrictDoc-каналом, GET /api/export/reqif'}, ensure_ascii=False))
            elif self.path == '/reqif/parse':
                import tempfile, os
                with tempfile.NamedTemporaryFile(suffix='.reqif', delete=False) as f:
                    f.write(raw)
                    path = f.name
                try:
                    self._reply(200, json.dumps(parse_reqif(path), ensure_ascii=False))
                finally:
                    os.unlink(path)
            else:
                self._reply(404, json.dumps({'error': 'unknown path'}))
        except Exception as e:  # noqa: BLE001 — границе службы положено отвечать, а не падать
            self._reply(422, json.dumps({'error': str(e)}, ensure_ascii=False))

    def log_message(self, fmt, *args):  # журнал — одна строка на запрос
        sys.stderr.write('reqif %s\n' % (fmt % args))


def main():
    import os
    port = int(os.environ.get('ORBITA_EXCHANGE_PORT', '8091'))
    server = ThreadingHTTPServer(('0.0.0.0', port), Handler)
    print(f'exchange_started service=reqif port={port}', flush=True)
    server.serve_forever()


if __name__ == '__main__':
    main()
