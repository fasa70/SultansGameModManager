"""Catalog-backed Android adapter for the upstream ID remapper."""
from __future__ import annotations

import json
import os
import shutil
import tempfile
from pathlib import Path
from typing import Any

from upstream_sultan.core.data_manager import DataManager
from upstream_sultan.core.mod.id_remap import (
    RemapTable,
    _allocate_tag_name_remaps,
    _collect_all_used_ids,
    allocate_new_ids,
    apply_remap_to_store,
    build_remap_table,
    collect_mod_ids,
    compute_resource_rename,
    detect_conflicts,
)


class DirectoryStore:
    """DataManager-shaped store with one explicit config layout per Mod."""

    def __init__(self, roots: list[Path]):
        self.roots = [root.resolve() for root in roots]
        self._config_layout: list[bool] = []
        for root in self.roots:
            config = root / "config"
            config_json = _json_files(config) if config.is_dir() else []
            legacy_json = [
                path for path in _json_files(root)
                if "config" not in path.relative_to(root).parts
            ]
            # Info.json is metadata, not a config file.  A second JSON tree is
            # never silently ignored when a Mod contains both layouts.
            legacy_config = [
                path for path in legacy_json
                if path.name.lower() != "info.json"
            ]
            if config_json and legacy_config:
                raise ValueError(
                    f"Mod 同时包含 config/ 和 legacy 配置布局：{root.name}"
                )
            self._config_layout.append(bool(config_json))

    def _root(self, mod_id: str) -> Path:
        index = int(mod_id)
        if index < 0 or index >= len(self.roots):
            raise ValueError(f"invalid mod id: {mod_id}")
        return self.roots[index]

    def _uses_config(self, mod_id: str) -> bool:
        index = int(mod_id)
        if index < 0 or index >= len(self._config_layout):
            raise ValueError(f"invalid mod id: {mod_id}")
        return self._config_layout[index]

    @staticmethod
    def _logical(rel_path: str) -> str:
        path = Path(rel_path.replace("\\", "/"))
        if path.is_absolute() or not path.parts or ".." in path.parts or "." in path.parts:
            raise ValueError(f"unsafe relative path: {rel_path}")
        return "/".join(path.parts)

    def _physical(self, mod_id: str, logical: str, *, for_write: bool = False) -> Path:
        logical = self._logical(logical)
        root = self._root(mod_id)
        candidate = root / "config" / logical if self._uses_config(mod_id) else root / logical
        resolved_parent = candidate.parent.resolve()
        if root not in (resolved_parent, *resolved_parent.parents):
            raise ValueError(f"path escapes mod root: {logical}")
        return candidate

    def has_mod(self, mod_id: str, rel_path: str) -> bool:
        path = self._physical(mod_id, rel_path)
        return path.is_file() and not path.is_symlink()

    def get_mod(self, mod_id: str, rel_path: str):
        from sultan_core.json import JsonDoc
        path = self._physical(mod_id, rel_path)
        if not path.is_file() or path.is_symlink():
            raise FileNotFoundError(rel_path)
        return JsonDoc.parse_file(str(path))

    def mod_files(self, mod_id: str) -> list[str]:
        root = self._root(mod_id)
        scan = root / "config" if self._uses_config(mod_id) else root
        result: list[str] = []
        for path in _json_files(scan):
            if path.is_symlink() or not path.is_file():
                raise ValueError(f"unsafe mod file: {path}")
            relative = path.relative_to(scan).as_posix()
            if relative.lower() != "info.json":
                result.append(relative)
        return sorted(result)

    def remove_mod_file(self, mod_id: str, rel_path: str) -> None:
        path = self._physical(mod_id, rel_path)
        if path.exists() and path.is_symlink():
            raise ValueError(f"unsafe mod file: {rel_path}")
        path.unlink(missing_ok=True)

    def set_mod(self, mod_id: str, rel_path: str, doc: Any) -> None:
        path = self._physical(mod_id, rel_path, for_write=True)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(doc.to_string(), encoding="utf-8")

    def rename_resource(self, mod_id: str, old_rel: str, new_rel: str) -> None:
        old = self._root(mod_id) / self._logical(old_rel)
        new = self._root(mod_id) / self._logical(new_rel)
        if old.is_symlink() or (new.exists() and new.is_symlink()):
            raise ValueError("resource symlink is not allowed")
        if not old.is_file():
            raise FileNotFoundError(old_rel)
        if new.exists():
            if new.read_bytes() != old.read_bytes():
                raise ValueError(f"resource rename collision: {new_rel}")
            old.unlink()
            return
        new.parent.mkdir(parents=True, exist_ok=True)
        old.rename(new)


def _json_files(root: Path) -> list[Path]:
    if not root.is_dir():
        return []
    return [path for path in root.rglob("*.json") if path.is_file()]


def _validate_tree(root: Path) -> None:
    for path in [root, *root.rglob("*")]:
        if path.is_symlink():
            raise ValueError(f"Mod 包含不安全符号链接：{path.name}")
        if not path.is_file() and not path.is_dir():
            raise ValueError(f"Mod 包含非普通文件：{path.name}")


def _load_catalog(path: Path) -> tuple[dict[str, set[str]], set[str]]:
    data = json.loads(path.read_text(encoding="utf-8"))
    required = {
        "profileId", "versionCode", "catalogVersion", "cards", "tagCodes",
        "tagIds", "tagNames", "rite", "event", "over", "loot",
        "riteTemplate", "riteTemplateMappings",
    }
    missing = sorted(required - data.keys())
    if missing:
        raise ValueError(f"catalog 缺少必需字段：{', '.join(missing)}")
    if not isinstance(data["versionCode"], int) or data["versionCode"] <= 0:
        raise ValueError("catalog versionCode 无效")
    if not isinstance(data["catalogVersion"], str) or not data["catalogVersion"]:
        raise ValueError("catalog catalogVersion 无效")
    collections = {
        key: data[key] for key in (
            "cards", "tagCodes", "tagIds", "tagNames", "rite", "event",
            "over", "loot", "riteTemplate", "riteTemplateMappings",
        )
    }
    if any(not isinstance(value, list) for value in collections.values()):
        raise ValueError("catalog 集合字段必须是数组")
    if not any(collections.values()):
        raise ValueError("catalog 为空，禁止合并")
    return ({
        "cards": set(data["cards"]),
        "tag": set(data["tagCodes"]),
        "tag_id": {str(value) for value in data["tagIds"]},
        "rite": set(data["rite"]),
        "event": set(data["event"]),
        "over": set(data["over"]),
        "loot": set(data["loot"]),
        "rite_template": set(data["riteTemplate"]),
        "rite_template_mappings": set(data["riteTemplateMappings"]),
    }, set(data["tagNames"]))


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


def _remap_resources(store: DirectoryStore, mod_id: str, table: RemapTable) -> None:
    root = store._root(mod_id)
    for path in sorted(root.rglob("*"), key=lambda p: len(p.parts), reverse=True):
        if not path.is_file() or path.is_symlink() or path.suffix.lower() == ".json":
            continue
        relative = path.relative_to(root).as_posix()
        mapped = compute_resource_rename(relative, table)
        if mapped != relative:
            store.rename_resource(mod_id, relative, mapped)


def _is_ancestor(parent: Path, child: Path) -> bool:
    try:
        child.relative_to(parent)
        return True
    except ValueError:
        return False


def _warnings(
    conflicts: dict[str, dict[str, list[int]]],
    tag_conflicts: dict[str, list[tuple[int, str]]],
) -> list[dict[str, Any]]:
    warnings: list[dict[str, Any]] = []
    for entity_type, values in sorted(conflicts.items()):
        warnings.append({
            "code": "id_conflict",
            "severity": "warning",
            "message": (
                f"检测到 {entity_type} ID 冲突，已继续尝试重映射；"
                "部分引用可能无法完全对应。"
            ),
            "entity_type": entity_type,
            "count": len(values),
        })
    if tag_conflicts:
        warnings.append({
            "code": "tag_name_conflict",
            "severity": "warning",
            "message": "检测到 tag name 冲突，已继续尝试重映射；部分引用可能无法完全对应。",
            "entity_type": "tag_name",
            "count": len(tag_conflicts),
        })
    return warnings


def run(input_roots: list[str], catalog_path: str, output_root: str) -> dict[str, Any]:
    if len(input_roots) < 2:
        raise ValueError("at least two Mod inputs are required")
    source_roots = [Path(value).resolve() for value in input_roots]
    if any(not root.is_dir() or root.is_symlink() for root in source_roots):
        raise ValueError("Mod input must be a real directory")
    for root in source_roots:
        _validate_tree(root)
    output = Path(output_root).resolve()
    if any(_is_ancestor(output, root) or _is_ancestor(root, output) for root in source_roots):
        raise ValueError("invalid worker output root: it overlaps an input directory")
    if output.exists():
        raise ValueError("worker output root already exists")
    output.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=f".{output.name}-", dir=str(output.parent)))
    roots: list[Path] = []
    previous = DataManager._instance
    try:
        for index, source in enumerate(source_roots):
            target = staging / f"input-{index}"
            shutil.copytree(source, target, symlinks=False)
            _validate_tree(target)
            roots.append(target)
        base_ids, base_tag_names = _load_catalog(Path(catalog_path).resolve())
        store = DirectoryStore(roots)
        DataManager._instance = store
        configs = [(str(i), source_roots[i].name, roots[i]) for i in range(len(roots))]
        infos = [collect_mod_ids(str(i)) for i in range(len(roots))]
        conflicts, tag_conflicts = detect_conflicts(base_ids, base_tag_names, infos)
        tag_remap = _allocate_tag_name_remaps(tag_conflicts, base_tag_names, configs)
        remap = allocate_new_ids(conflicts, _collect_all_used_ids(base_ids, infos), len(roots))
        tables: dict[str, dict[str, Any]] = {}
        for index in range(len(roots)):
            table = build_remap_table(remap, index, infos[index])
            for (mod_index, code), name in tag_remap.items():
                if mod_index == index:
                    table.tag_names[code] = name
                    old = infos[index].tag_names.get(code, "")
                    if old:
                        table.tag_name_mapping[old] = name
            if not table.is_empty():
                apply_remap_to_store(str(index), table)
                _remap_resources(store, str(index), table)
                tables[str(index)] = _table_json(table)
        result = {
            "status": "ok",
            "best_effort": bool(conflicts or tag_conflicts),
            "warnings": _warnings(conflicts, tag_conflicts),
            "conflicts": {kind: dict(values) for kind, values in conflicts.items()},
            "tag_name_conflicts": {name: [[i, code] for i, code in entries] for name, entries in tag_conflicts.items()},
            "remap_tables": tables,
            "roots": [f"input-{i}" for i in range(len(roots))],
        }
        DataManager._instance = previous
        os.replace(staging, output)
        return result
    except Exception:
        shutil.rmtree(staging, ignore_errors=True)
        raise
    finally:
        DataManager._instance = previous


def run_json(request: str) -> str:
    value = json.loads(request)
    return json.dumps(run(value["input_roots"], value["catalog_path"], value["output_root"]), ensure_ascii=False, sort_keys=True)
