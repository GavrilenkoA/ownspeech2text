"""Minimal JSON-Schema validator (the subset our schemas use): type, enum,
const, properties, required, additionalProperties, items, maxItems, minItems,
maxLength, minimum, maximum. Stdlib only."""
from __future__ import annotations

_PY = {"object": dict, "array": list, "string": str, "integer": int, "number": (int, float), "boolean": bool,
       "null": type(None)}


def validate(val, sch: dict, path: str = "$", enforce_required: bool = True) -> list[str]:
    errs: list[str] = []
    _v(val, sch, path, errs, enforce_required)
    return errs


def _v(val, sch, path, errs, req):
    if "const" in sch and val != sch["const"]:
        errs.append(f"{path}: expected {sch['const']!r}")
        return
    if "enum" in sch and val not in sch["enum"]:
        errs.append(f"{path}: {val!r} not in enum")
        return
    t = sch.get("type")
    types = t if isinstance(t, list) else [t] if t else []
    if types and not any(isinstance(val, _PY[x]) and not (x in ("integer", "number") and isinstance(val, bool))
                         for x in types):
        errs.append(f"{path}: type {type(val).__name__} not in {types}")
        return
    if isinstance(val, dict):
        props = sch.get("properties", {})
        if req:
            for k in sch.get("required", []):
                if k not in val:
                    errs.append(f"{path}: missing {k!r}")
        for k, v in val.items():
            if k in props:
                _v(v, props[k], f"{path}.{k}", errs, req)
            elif sch.get("additionalProperties") is False:
                errs.append(f"{path}: unknown key {k!r}")
    elif isinstance(val, list):
        if "maxItems" in sch and len(val) > sch["maxItems"]:
            errs.append(f"{path}: {len(val)} items > {sch['maxItems']}")
        if "minItems" in sch and len(val) < sch["minItems"]:
            errs.append(f"{path}: {len(val)} items < {sch['minItems']}")
        if "items" in sch:
            for i, x in enumerate(val):
                _v(x, sch["items"], f"{path}[{i}]", errs, req)
    elif isinstance(val, str):
        if "maxLength" in sch and len(val) > sch["maxLength"]:
            errs.append(f"{path}: length {len(val)} > {sch['maxLength']}")
    elif isinstance(val, (int, float)) and not isinstance(val, bool):
        if "minimum" in sch and val < sch["minimum"]:
            errs.append(f"{path}: {val} < {sch['minimum']}")
        if "maximum" in sch and val > sch["maximum"]:
            errs.append(f"{path}: {val} > {sch['maximum']}")
