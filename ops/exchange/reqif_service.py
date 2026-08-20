#!/usr/bin/env python3
"""Служба обмена ReqIF (TZ-OUT-005, шаг 11.2, ADR-023).

Разделение труда с ядром: ОТОБРАЖЕНИЕ модели в атрибуты — в ядре
(core/out/ReqifMapping.kt, эталон spec/reqif_semantics.py), а здесь — только
XML стандарта: сборка через библиотеку `reqif` (strictdoc-project) и разбор ею
же. Разбирать чужой кривой ReqIF самостоятельно — задача, которую не стоит
брать: парсер библиотеки намеренно устойчив к неполным схемам чужих
инструментов (ADR-023).

Дата выгрузки приходит от вызывающего, а не берётся здесь из часов: экспорт
фиксирует дату (TZ-OUT-005), и одинаковый вход обязан давать одинаковый файл —
иначе воспроизводимость выгрузки нельзя проверить сравнением.

HTTP-интерфейс (для контейнера): POST /reqif/export, POST /reqif/parse,
GET /health. Модуль импортируется и напрямую — так работает проверка
кругового обмена в CI (tools/check_reqif_roundtrip.py).
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from xml.sax.saxutils import escape

from reqif.models.reqif_core_content import ReqIFCoreContent
from reqif.models.reqif_data_type import (
    ReqIFDataTypeDefinitionBoolean,
    ReqIFDataTypeDefinitionDateIdentifier,
    ReqIFDataTypeDefinitionEnumeration,
    ReqIFDataTypeDefinitionInteger,
    ReqIFDataTypeDefinitionReal,
    ReqIFDataTypeDefinitionString,
    ReqIFDataTypeDefinitionXHTML,
    ReqIFEnumValue,
)
from reqif.models.reqif_namespace_info import ReqIFNamespaceInfo
from reqif.models.reqif_req_if_content import ReqIFReqIFContent
from reqif.models.reqif_reqif_header import ReqIFReqIFHeader
from reqif.models.reqif_core_content import ReqIFCoreContent  # noqa: F811
from reqif.models.reqif_spec_hierarchy import ReqIFSpecHierarchy
from reqif.models.reqif_spec_object import ReqIFSpecObject, SpecObjectAttribute
from reqif.models.reqif_spec_object_type import ReqIFSpecObjectType, SpecAttributeDefinition
from reqif.models.reqif_spec_relation import ReqIFSpecRelation
from reqif.models.reqif_spec_relation_type import ReqIFSpecRelationType
from reqif.models.reqif_specification import ReqIFSpecification
from reqif.models.reqif_specification_type import ReqIFSpecificationType
from reqif.models.reqif_types import SpecObjectAttributeType
from reqif.object_lookup import ReqIFObjectLookup
from reqif.parser import ReqIFParser
from reqif.reqif_bundle import ReqIFBundle
from reqif.unparser import ReqIFUnparser

ATTR_TYPES = {
    'string': SpecObjectAttributeType.STRING,
    'integer': SpecObjectAttributeType.INTEGER,
    'real': SpecObjectAttributeType.REAL,
    'date': SpecObjectAttributeType.DATE,
    'boolean': SpecObjectAttributeType.BOOLEAN,
    'enum': SpecObjectAttributeType.ENUMERATION,
    'xhtml': SpecObjectAttributeType.XHTML,
}


def _datatype_id(name, kind):
    # Идентификаторы детерминированы: тот же вход — тот же файл.
    return f'DT-ENUM-{name}' if kind == 'enum' else f'DT-{kind.upper()}'


def _enum_value_id(name, value):
    return f'EV-{name}-{value}'


def build_reqif(payload):
    """Полезная нагрузка ядра → текст ReqIF.

    Ожидаемая форма payload:
      title, exported_at           — заголовок; дата приходит снаружи
      datatypes                    — имя атрибута → {kind, values?}
      object_types                 — id типа → {long_name, attributes: {имя: kind}}
      relation_types               — id типа связи → long_name
      objects                      — [{identifier, type, values: {имя: значение}}]
      relations                    — [{identifier, type, source, target}]
    """
    exported_at = payload['exported_at']
    datatypes = payload['datatypes']

    dt_defs = {}
    for name, spec in sorted(datatypes.items()):
        kind = spec['kind']
        dt_id = _datatype_id(name, kind)
        if dt_id in dt_defs:
            continue
        common = {'identifier': dt_id, 'last_change': exported_at}
        if kind == 'string':
            # MAX-LENGTH обязателен по XSD; предел щедрый, но конечный
            dt_defs[dt_id] = ReqIFDataTypeDefinitionString(max_length='32000', **common)
        elif kind == 'integer':
            dt_defs[dt_id] = ReqIFDataTypeDefinitionInteger(
                min_value='-2147483648', max_value='2147483647', **common)
        elif kind == 'real':
            dt_defs[dt_id] = ReqIFDataTypeDefinitionReal(
                accuracy=15, min_value='-1E12', max_value='1E12', **common)
        elif kind == 'date':
            dt_defs[dt_id] = ReqIFDataTypeDefinitionDateIdentifier(**common)
        elif kind == 'boolean':
            dt_defs[dt_id] = ReqIFDataTypeDefinitionBoolean(**common)
        elif kind == 'xhtml':
            dt_defs[dt_id] = ReqIFDataTypeDefinitionXHTML(**common)
        elif kind == 'enum':
            dt_defs[dt_id] = ReqIFDataTypeDefinitionEnumeration(
                # OTHER-CONTENT обязателен по XSD; содержимым служит само имя
                values=[ReqIFEnumValue(identifier=_enum_value_id(name, v), key=str(i),
                                       long_name=v, other_content=v, last_change=exported_at)
                        for i, v in enumerate(spec['values'])],
                **common)
        else:
            raise ValueError(f'неизвестный тип данных: {kind}')

    def attr_defs_for(type_id, attributes):
        return [
            SpecAttributeDefinition(
                ATTR_TYPES[kind], f'AD-{type_id}-{name}', _datatype_id(name, kind),
                long_name=name, last_change=exported_at,
                # MULTI-VALUED обязателен по XSD для перечислений; значения одиночные
                multi_valued=False if kind == 'enum' else None)
            for name, kind in sorted(attributes.items())
        ]

    object_types = {
        type_id: ReqIFSpecObjectType(
            identifier=type_id, long_name=spec.get('long_name', type_id),
            last_change=exported_at,
            attribute_definitions=attr_defs_for(type_id, spec['attributes']))
        for type_id, spec in sorted(payload['object_types'].items())
    }
    relation_types = [
        ReqIFSpecRelationType(identifier=type_id, long_name=long_name, last_change=exported_at)
        for type_id, long_name in sorted(payload.get('relation_types', {}).items())
    ]
    spec_type = ReqIFSpecificationType(
        identifier='SPT-DOCUMENT', long_name='Document', last_change=exported_at,
        spec_attributes=None, spec_attribute_map={}, is_self_closed=True)

    def attribute(type_id, attributes, name, value):
        kind = attributes[name]
        if kind == 'enum':
            return SpecObjectAttribute(
                ATTR_TYPES['enum'], f'AD-{type_id}-{name}', [_enum_value_id(name, value)])
        if kind == 'xhtml':
            # Значение оборачивается в xhtml:div; текст экранируется здесь,
            # у ядра нет причин знать про XML
            return SpecObjectAttribute(
                ATTR_TYPES['xhtml'], f'AD-{type_id}-{name}',
                f'<xhtml:div>{escape(str(value))}</xhtml:div>')
        if kind == 'boolean':
            return SpecObjectAttribute(
                ATTR_TYPES['boolean'], f'AD-{type_id}-{name}', 'true' if value else 'false')
        return SpecObjectAttribute(ATTR_TYPES[kind], f'AD-{type_id}-{name}', str(value))

    spec_objects = []
    for obj in payload['objects']:
        type_id = obj['type']
        attributes = payload['object_types'][type_id]['attributes']
        unknown = [n for n in obj['values'] if n not in attributes]
        if unknown:
            raise ValueError(f'{obj["identifier"]}: атрибуты вне отображения: {unknown}')
        spec_objects.append(ReqIFSpecObject(
            identifier=obj['identifier'], spec_object_type=type_id,
            last_change=exported_at,
            attributes=[attribute(type_id, attributes, n, v)
                        for n, v in obj['values'].items() if v is not None]))

    spec_relations = [
        ReqIFSpecRelation(
            identifier=rel['identifier'], relation_type_ref=rel['type'],
            source=rel['source'], target=rel['target'], last_change=exported_at)
        for rel in payload.get('relations', [])
    ]

    # Иерархия плоская: структура глав — забота документов (TZ-OUT-001),
    # обмен передаёт реестр
    specification = ReqIFSpecification(
        identifier='SPEC-0001', long_name=payload.get('title', 'Requirements'),
        specification_type='SPT-DOCUMENT', last_change=exported_at,
        children=[ReqIFSpecHierarchy(identifier=f'SH-{i:04d}', spec_object=o.identifier,
                                     level=1, last_change=exported_at)
                  for i, o in enumerate(spec_objects, start=1)])

    content = ReqIFReqIFContent(
        data_types=list(dt_defs.values()),
        spec_types=list(object_types.values()) + relation_types + [spec_type],
        spec_objects=spec_objects,
        spec_relations=spec_relations,
        specifications=[specification])
    bundle = ReqIFBundle(
        namespace_info=ReqIFNamespaceInfo.create_default(),
        req_if_header=ReqIFReqIFHeader(
            identifier='RH-0001', title=payload.get('title', 'Requirements'),
            creation_time=exported_at, req_if_version='1.0',
            req_if_tool_id='orbita', source_tool_id='orbita-core'),
        core_content=ReqIFCoreContent(content),
        tool_extensions_tag_exists=False,
        lookup=ReqIFObjectLookup(
            data_types_lookup={}, spec_types_lookup={},
            spec_objects_lookup={}, spec_relations_parent_lookup={}),
        exceptions=[])
    return ReqIFUnparser.unparse(bundle)


def parse_reqif(path):
    """Файл ReqIF → та же форма, что на входе build_reqif (насколько файл её несёт).

    Читает и ЧУЖИЕ файлы: имена атрибутов берутся из определений типов файла,
    а не из нашего отображения. Значения перечислений возвращаются их именами.
    """
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

    objects = [
        {'identifier': so.identifier, 'type': so.spec_object_type,
         'values': {attr_names.get(a.definition_ref, a.definition_ref): value_of(a)
                    for a in so.attributes}}
        for so in content.spec_objects or []
    ]
    relations = [
        {'identifier': r.identifier, 'type': r.relation_type_ref,
         'source': r.source, 'target': r.target}
        for r in content.spec_relations or []
    ]
    return {
        'title': bundle.req_if_header.title if bundle.req_if_header else None,
        'exported_at': bundle.req_if_header.creation_time if bundle.req_if_header else None,
        'object_types': object_types,
        'objects': objects,
        'relations': relations,
    }


def _strip_xhtml_div(text):
    """Обратная сторона обёртки `<xhtml:div>…</xhtml:div>` из build_reqif."""
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
                xml = build_reqif(json.loads(raw))
                self._reply(200, xml, 'application/xml; charset=utf-8')
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
