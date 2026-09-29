#!/usr/bin/env python3
"""Check French resource coverage, duplicates, plurals, and format arguments."""
from collections import Counter
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

RES = Path(__file__).resolve().parents[1] / "app/src/main/res"
FORMAT = re.compile(r"%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z])")


def resources(directory):
    result = {}
    for path in sorted(directory.glob("*.xml")):
        for element in ET.parse(path).getroot():
            if element.tag not in ("string", "plurals", "string-array"):
                continue
            key = element.tag, element.attrib["name"]
            if key in result:
                raise ValueError(f"Duplicate {key} in {path}")
            result[key] = element
    return result


def arguments(element):
    text = "".join(element.itertext()).replace("%%", "")
    return Counter((int(index or position), kind) for position, (index, kind) in
                   enumerate(FORMAT.findall(text), start=1) if kind != "n")


def check():
    english, french = resources(RES / "values"), resources(RES / "values-fr")
    errors = []
    for key, source in english.items():
        if source.get("translatable") == "false":
            continue
        if key not in french:
            errors.append(f"Missing French {key}")
            continue
        target = french[key]
        if source.tag == "plurals":
            forms = {item.get("quantity"): item for item in target}
            for required in ("one", "other"):
                if required not in forms:
                    errors.append(f"Missing French {required} plural: {key}")
            source_forms = {item.get("quantity"): item for item in source}
            for form, item in forms.items():
                if arguments(item) != arguments(source_forms.get(form, source_forms["other"])):
                    errors.append(f"Format mismatch: {key}, {form}")
        elif source.tag == "string-array":
            if len(source) != len(target):
                errors.append(f"Array length mismatch: {key}")
            for a, b in zip(source, target):
                if arguments(a) != arguments(b):
                    errors.append(f"Array format mismatch: {key}")
        elif arguments(source) != arguments(target):
            errors.append(f"Format mismatch: {key}")
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print(f"English/French coverage and format arguments verified for {len(english)} resources.")
    return 0


if __name__ == "__main__":
    sys.exit(check())
