"""Catalog-backed Android adapter for the upstream ID remapper.

The adapter uses a temporary copy of each selected Mod as the store. It never
loads game-original JSON and never initializes the upstream DataManager.
"""
from __future__ import annotations

import json
import shutil
from pathlib import Path
from typing import Any

from upstream_sultan.core.mod.id_remap import (
    ModIdInfo,
    RemapTable,
    _allocate_tag_name_remaps,
    _collect_all_used_ids,
    allocate_new_ids,
    apply_remap_to_store,
    build_remap_table,
    collect_mod_ids,
    detect_conflicts,
)
from upstream_sultan.core.data_manager import DataManager


def _load_catalog(path: Path) -> tuple[dict[str, set[str]], set[str]]:
    data = json.loads(path.read_text(encoding="utf-8"))
    base_ids = {
        "cards": set(data.get("cards", [])),
        "tag": set(data.get("tagCodes", [])),
        "tag_id": {str(value) for value in data.get("tagIds", [])},
        "rite": set(data.get("rite", [])),
        "event": set(data.get("event", [])),
        "over": set(data.get("over", [])),
        "loot": set(data.get("loot", [])),
        "rite_template": set(data.get("riteTemplate", [])),
        "rite_template_mappings": set(data.get("riteTemplateMappings", [])),
    }
    return base_ids, set(data.get("tagNames", []))


class DirectoryStore:
    """Minimal DataManager-shaped store for upstream json_ops operations."""

    def __init__(self, roots: list[Path]):
        self.roots = roots

    def has_mod(self, mod_id: str, rel_path: str) -> bool:
        return (self._root(mod_id) / rel_path).is_file()

    def get_mod(self, mod_id: str, rel_path: str):
        from sultan_core.json import JsonDoc

        return JsonDoc.parse_file(str(self._root(mod_id) / rel_path))

    def mod_files(self, mod_id: str) -> list[str]:
        root = self._root(mod_id)
        return [p.relative_to(root).as_posix() for p in root.rglob("*.json")]

    def remove_mod_file(self, mod_id: str, rel_path: str) -> None:
        path = self._root(mod_id) / rel_path
        if path.exists():
            path.unlink()

    def set_mod(self, mod_id: str, rel_path: str, doc: Any) -> None:
        path = self._root(mod_id) / rel_path
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(doc.to_string(), encoding="utf-8")

    def _root(self, mod_id: str) -> Path:
        return self.roots[int(mod_id)]


def _table_json(table: RemapTable) -> dict[str, Any]:
    return {
        "cards": table.cards,
        "tag_codes": table.tag_codes,
        "tag_ids": {str(k): v for k, v in table.tag_ids.items()},
        "tag_names": table.tag_names,
        "tag_name_mapping": table.tag_name_mapping,
        "rite": table.rite,
        "event": table.event,
        "over": table.over,
        "loot": table.loot,
        "rite_template": table.rite_template,
        "rite_template_mappings": table.rite_template_mappings,
    }


def run(input_roots: list[str], catalog_path: str, output_root: str) -> dict[str, Any]:
    source_roots = [Path(value).resolve() for value in input_roots]
    output = Path(output_root).resolve()
    output.mkdir(parents=True, exist_ok=True)
    roots: list[Path] = []
    for index, source in enumerate(source_roots):
        target = output / f"input-{index}"
        shutil.copytree(source, target, dirs_exist_ok=True)
        roots.append(target)

    base_ids, base_tag_names = _load_catalog(Path(catalog_path))
    store = DirectoryStore(roots)
    DataManager._instance = store
    mod_configs = [(str(i), source_roots[i].name, roots[i]) for i in range(len(roots))]
    mod_ids = [collect_mod_ids(str(i)) for i in range(len(roots))]
    conflicts, tag_name_conflicts = detect_conflicts(base_ids, base_tag_names, mod_ids)
    tag_name_remap = _allocate_tag_name_remaps(tag_name_conflicts, base_tag_names, mod_configs)
    remap = allocate_new_ids(conflicts, _collect_all_used_ids(base_ids, mod_ids), len(roots))

    tables: dict[str, dict[str, Any]] = {}
    for index in range(len(roots)):
        table = build_remap_table(remap, index, mod_ids[index])
        for (mod_index, code), name in tag_name_remap.items():
            if mod_index == index:
                table.tag_names[code] = name
                old_name = mod_ids[index].tag_names.get(code, "")
                if old_name:
                    table.tag_name_mapping[old_name] = name
        if not table.is_empty():
            apply_remap_to_store(str(index), table)
            tables[str(index)] = _table_json(table)

    return {
        "conflicts": {
            entity: {key: value for key, value in values.items()}
            for entity, values in conflicts.items()
        },
        "tag_name_conflicts": {
            name: [[index, code] for index, code in entries]
            for name, entries in tag_name_conflicts.items()
        },
        "remap_tables": tables,
        "roots": [str(root) for root in roots],
    }


def run_json(request: str) -> str:
    value = json.loads(request)
    return json.dumps(
        run(value["input_roots"], value["catalog_path"], value["output_root"]),
        ensure_ascii=False,
        sort_keys=True,
    )
