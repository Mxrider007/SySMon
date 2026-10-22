#!/usr/bin/env python3
"""Audit the app-string translations against the base (English) resources.

For every values-<locale>/strings.xml (one consolidated file per locale),
checks:
  1. Coverage: every base string/plurals WITHOUT translatable="false" exists
     in the locale; nothing extra exists that the base doesn't define.
  2. Placeholders: the exact multiset of %n$s / %n$d survives translation,
     for strings and for every plurals quantity (against the base "other").
  3. formatted="false" is carried wherever the base declares it.

Run after ANY string change, before gradle — it catches in seconds what
lint reports late and MissingTranslation would let silently fall back.
Exit 0 only when every locale passes.
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

RES = Path(__file__).resolve().parent.parent / "app/src/main/res"
LOCALES = ["values-de", "values-fr", "values-es", "values-pt-rBR",
           "values-it", "values-pl", "values-ja", "values-ko"]


def collect(d):
    out = {}
    for f in sorted(Path(d).glob("strings*.xml")):
        root = ET.parse(f).getroot()
        for el in root:
            if el.tag == "string":
                out[el.attrib["name"]] = ("s", "".join(el.itertext()), el.attrib)
            elif el.tag == "plurals":
                items = {i.attrib["quantity"]: "".join(i.itertext()) for i in el}
                out[el.attrib["name"]] = ("p", items, el.attrib)
    return out


def placeholders(text):
    return sorted(re.findall(r"%\d+\$[sd]", text))


def main():
    base = collect(RES / "values")
    translatable = {k for k, v in base.items() if v[2].get("translatable") != "false"}
    fail = False
    for loc in LOCALES:
        tr = collect(RES / loc)
        for name in sorted(translatable - set(tr)):
            print(f"{loc}: MISSING {name}")
            fail = True
        for name in sorted(set(tr) - set(base)):
            print(f"{loc}: EXTRA {name}")
            fail = True
        for name, (kind, val, attrs) in tr.items():
            if name not in base:
                continue
            bkind, bval, battrs = base[name]
            if kind == "s":
                if placeholders(val) != placeholders(bval):
                    print(f"{loc}: {name} placeholders {placeholders(val)}"
                          f" != base {placeholders(bval)}")
                    fail = True
            else:
                bph = placeholders(bval.get("other", ""))
                for q, t in val.items():
                    if placeholders(t) != bph:
                        print(f"{loc}: {name}[{q}] placeholders"
                              f" {placeholders(t)} != base {bph}")
                        fail = True
            if battrs.get("formatted") == "false" and attrs.get("formatted") != "false":
                print(f"{loc}: {name} missing formatted=\"false\"")
                fail = True
        if not fail:
            print(f"{loc}: {len(tr)} entries ok")
    if fail:
        return 1
    print("\nAll translations pass.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
