"""
ID 重分配模块 - 检测并解决多个 Mod 之间的 ID 冲突

在合并前扫描所有 mod，找出被多个 mod 定义的相同 ID，
为冲突的 ID 分配新值，并在 store 中原地替换所有引用。
"""
from dataclasses import dataclass, field
from pathlib import Path

from sultan_core.json import JsonDoc
from sultan_core.json_ops import (
    extract_root_field_ints,
    extract_root_field_strs,
    extract_root_keys,
    remap_all_ints,
    remap_all_str_ids,
    replace_field_ints,
    replace_field_strs,
    replace_root_keys,
)

from ..infra.diagnostics import diag
from ..infra.types import CancelCheck

# 各类型 ID 的分配范围（从起始值向上递增）
ID_ALLOC_START: dict[str, int | None] = {
    "cards": 2900000,
    "tag_id": 3900000,
    "tag_code": None,         # tag code 通过加后缀生成
    "rite": 5090000,
    "event": 5390000,
    "over": 900,
    "loot": 6900000,
    "rite_template": 8090000,
    "rite_template_mappings": 8091000,
}

# 以文件名为 ID 的实体类型及其对应的目录名
FILE_BASED_TYPES: dict[str, str] = {
    "rite": "rite",
    "event": "event",
    "loot": "loot",
    "rite_template": "rite_template",
}

_REMAP_LOG_FIELDS: tuple[tuple[str, str], ...] = (
    ("rite", "rite"), ("event", "event"), ("loot", "loot"),
    ("over", "over"), ("rite_template", "rite_template"),
    ("rite_template_mappings", "rite_template_mappings"),
    ("tag_codes", "tag code"), ("tag_ids", "tag id"),
)

# dictionary 类型实体及其对应的文件名
DICT_BASED_TYPES: dict[str, str] = {
    "cards": "cards.json",
    "tag": "tag.json",
    "over": "over.json",
    "rite_template_mappings": "rite_template_mappings.json",
}


@dataclass
class RemapTable:
    """单个 mod 的 ID 替换映射表"""
    cards: dict[str, str] = field(default_factory=dict)
    tag_codes: dict[str, str] = field(default_factory=dict)
    tag_ids: dict[int, int] = field(default_factory=dict)
    tag_names: dict[str, str] = field(default_factory=dict)   # code → new_name
    tag_name_mapping: dict[str, str] = field(default_factory=dict)  # old_name → new_name
    rite: dict[str, str] = field(default_factory=dict)
    event: dict[str, str] = field(default_factory=dict)
    over: dict[str, str] = field(default_factory=dict)
    loot: dict[str, str] = field(default_factory=dict)
    rite_template: dict[str, str] = field(default_factory=dict)
    rite_template_mappings: dict[str, str] = field(default_factory=dict)

    def is_empty(self) -> bool:
        return not any([
            self.cards, self.tag_codes, self.tag_ids, self.tag_names,
            self.rite, self.event, self.over,
            self.loot, self.rite_template, self.rite_template_mappings,
        ])

    def build_int_lookup(self) -> dict[int, int]:
        """构建整数 ID 的查找表（旧→新），用于快速替换 JSON 中的数字值"""
        lookup: dict[int, int] = {}
        for mapping in [
            self.cards, self.rite, self.event, self.over,
            self.loot, self.rite_template, self.rite_template_mappings,
        ]:
            for old_str, new_str in mapping.items():
                lookup[int(old_str)] = int(new_str)
        for old_int, new_int in self.tag_ids.items():
            lookup[old_int] = new_int
        return lookup

    def build_str_lookup(self) -> dict[str, str]:
        """构建字符串 ID 的查找表，用于替换 DSL 表达式和字符串中的 ID"""
        lookup: dict[str, str] = {}
        for mapping in [
            self.cards, self.rite, self.event, self.over,
            self.loot, self.rite_template, self.rite_template_mappings,
        ]:
            for old_str, new_str in mapping.items():
                lookup[old_str] = new_str
        for old_int, new_int in self.tag_ids.items():
            lookup[str(old_int)] = str(new_int)
        return lookup


def _safe_get_mod(store, mod_id: str, rel_path: str) -> JsonDoc | None:
    try:
        return store.get_mod(mod_id, rel_path)
    except Exception as error:
        recorder = getattr(store, "record_invalid_json", None)
        if recorder is not None:
            recorder(mod_id, rel_path, error)
        return None


# ==================== ID 收集 ====================

def collect_base_ids() -> tuple[dict[str, set[str]], set[str]]:
    """收集游戏本体的所有 ID 和 tag name（从 DataManager 读取）。

    返回 (base_ids, base_tag_names)。
    """
    from ..data_manager import DataManager
    store = DataManager.instance()

    base_ids: dict[str, set[str]] = {
        "cards": set(), "tag": set(), "tag_id": set(),
        "rite": set(), "event": set(), "over": set(),
        "loot": set(), "rite_template": set(),
        "rite_template_mappings": set(),
    }
    base_tag_names: set[str] = set()

    # dictionary 类型
    for entity_type, filename in DICT_BASED_TYPES.items():
        if not store.has_base(filename):
            continue
        doc = store.get_base(filename)
        base_ids[entity_type] = set(extract_root_keys(doc))
        if entity_type == "tag":
            base_ids["tag_id"] = {str(v) for v in extract_root_field_ints(doc, "id").values()}
            base_tag_names = set(extract_root_field_strs(doc, "name").values())

    # 文件名即 ID 的类型（从 store 的 base rel_paths 中过滤）
    for entity_type, dirname in FILE_BASED_TYPES.items():
        prefix = dirname + "/"
        for rel_path in store.base_rel_paths():
            if rel_path.startswith(prefix) and rel_path.endswith(".json"):
                stem = rel_path[len(prefix):-5]
                if "/" not in stem:  # 只取直接子目录下的文件
                    base_ids[entity_type].add(stem)

    return base_ids, base_tag_names


@dataclass
class ModIdInfo:
    """单个 mod 的 ID 信息"""
    cards_keys: list[str] = field(default_factory=list)
    cards_names: dict[str, str] = field(default_factory=dict)

    tag_keys: list[str] = field(default_factory=list)
    tag_ids: dict[str, int] = field(default_factory=dict)
    tag_names: dict[str, str] = field(default_factory=dict)

    over_keys: list[str] = field(default_factory=list)
    rite_template_mappings_keys: list[str] = field(default_factory=list)

    # 文件类型：{id_str: rel_path}
    rite: dict[str, str] = field(default_factory=dict)
    event: dict[str, str] = field(default_factory=dict)
    loot: dict[str, str] = field(default_factory=dict)
    rite_template: dict[str, str] = field(default_factory=dict)


def collect_mod_ids(mod_id: str) -> ModIdInfo:
    """收集单个 mod 的所有 ID 定义（从 DataManager 读取）"""
    from ..data_manager import DataManager
    store = DataManager.instance()

    info = ModIdInfo()

    # dictionary 类型
    if store.has_mod(mod_id, "cards.json"):
        doc = _safe_get_mod(store, mod_id, "cards.json")
        if doc is None:
            doc = None
        else:
            info.cards_keys = extract_root_keys(doc)
            info.cards_names = extract_root_field_strs(doc, "name")

    if store.has_mod(mod_id, "tag.json"):
        doc = _safe_get_mod(store, mod_id, "tag.json")
        if doc is not None:
            info.tag_keys = extract_root_keys(doc)
            info.tag_ids = extract_root_field_ints(doc, "id")
            info.tag_names = extract_root_field_strs(doc, "name")

    if store.has_mod(mod_id, "over.json"):
        doc = _safe_get_mod(store, mod_id, "over.json")
        if doc is not None:
            info.over_keys = extract_root_keys(doc)

    if store.has_mod(mod_id, "rite_template_mappings.json"):
        doc = _safe_get_mod(store, mod_id, "rite_template_mappings.json")
        if doc is not None:
            info.rite_template_mappings_keys = extract_root_keys(doc)

    # 文件类型：从 store 的文件列表中过滤
    for entity_type, dirname in FILE_BASED_TYPES.items():
        mapping: dict[str, str] = {}
        prefix = dirname + "/"
        for rel_path in store.mod_files(mod_id):
            if rel_path.startswith(prefix) and rel_path.endswith(".json"):
                stem = rel_path[len(prefix):-5]
                if "/" not in stem:
                    mapping[stem] = rel_path
        setattr(info, entity_type, mapping)

    return info


# ==================== 冲突检测 ====================

def _get_id_keys(info: ModIdInfo, entity_type: str) -> list[str]:
    """从 ModIdInfo 中获取指定实体类型的 ID 键列表（保持插入顺序）"""
    if entity_type == "cards":
        return info.cards_keys
    if entity_type == "over":
        return info.over_keys
    if entity_type == "rite_template_mappings":
        return info.rite_template_mappings_keys
    return list(getattr(info, entity_type).keys())


def _detect_dict_conflicts(
    base_ids: set[str],
    mod_key_sets: list[list[str]],
) -> dict[str, list[int]]:
    """检测 dictionary 类型实体的 ID 冲突"""
    id_to_mods: dict[str, list[int]] = {}
    for mod_idx, keys in enumerate(mod_key_sets):
        for id_str in keys:
            if id_str not in base_ids:
                id_to_mods.setdefault(id_str, []).append(mod_idx)
    return {k: v for k, v in id_to_mods.items() if len(v) > 1}


def _detect_tag_conflicts(
    base_ids: set[str],
    base_tag_ids: set[str],
    base_tag_names: set[str],
    mod_ids_list: list[ModIdInfo],
) -> tuple[dict[str, list[int]], dict[str, list[int]], dict[str, list[tuple[int, str]]]]:
    """
    检测 tag 三类冲突，返回 (code_conflicts, id_conflicts, name_conflicts)。
    code 冲突：相同 code 但不同 name（同 code 同 name 视为同一 tag）。
    id 冲突：不同 code 但相同数字 id。
    name 冲突：不同 code 但相同 name（与本体或跨 mod 重名）。
    """
    # code 冲突检测
    code_to_mods: dict[str, list[int]] = {}
    code_to_names: dict[str, set[str]] = {}
    for mod_idx, info in enumerate(mod_ids_list):
        for code in info.tag_keys:
            if code not in base_ids:
                code_to_mods.setdefault(code, []).append(mod_idx)
                name = info.tag_names.get(code, "")
                code_to_names.setdefault(code, set()).add(name)

    # 相同 code 但 name 完全一致 → 不冲突
    code_conflicts: dict[str, list[int]] = {}
    for code, mods in code_to_mods.items():
        if len(mods) > 1 and len(code_to_names.get(code, set())) > 1:
            code_conflicts[code] = mods

    # id 数字冲突检测
    id_to_mods: dict[str, list[tuple[int, str]]] = {}  # id_str -> [(mod_idx, code)]
    for mod_idx, info in enumerate(mod_ids_list):
        for code, tag_id in info.tag_ids.items():
            id_str = str(tag_id)
            if id_str not in base_tag_ids:
                id_to_mods.setdefault(id_str, []).append((mod_idx, code))

    id_conflicts: dict[str, list[int]] = {}
    for id_str, entries in id_to_mods.items():
        mod_indices = list({mod_idx for mod_idx, _ in entries})
        if len(mod_indices) > 1:
            id_conflicts[id_str] = mod_indices

    # name 冲突检测（独立于 code 和 id）
    name_to_entries: dict[str, list[tuple[int, str]]] = {}  # name → [(mod_idx, code)]
    for mod_idx, info in enumerate(mod_ids_list):
        for code in info.tag_keys:
            if code in base_ids:
                continue
            name = info.tag_names.get(code)
            if name:
                name_to_entries.setdefault(name, []).append((mod_idx, code))

    name_conflicts: dict[str, list[tuple[int, str]]] = {}
    for name, entries in name_to_entries.items():
        if name in base_tag_names:
            name_conflicts[name] = entries
        elif len(entries) > 1:
            name_conflicts[name] = entries

    return code_conflicts, id_conflicts, name_conflicts


def detect_conflicts(
    base_ids: dict[str, set[str]],
    base_tag_names: set[str],
    mod_ids_list: list[ModIdInfo],
) -> tuple[dict[str, dict[str, list[int]]], dict[str, list[tuple[int, str]]]]:
    """
    检测所有类型的 ID 冲突。
    返回 (conflicts, tag_name_conflicts)：
    - conflicts: {entity_type: {id_str: [mod_indices]}}
    - tag_name_conflicts: {name: [(mod_idx, code)]}
    """
    conflicts: dict[str, dict[str, list[int]]] = {}

    for entity_type in (*("cards", "over", "rite_template_mappings"), *FILE_BASED_TYPES):
        mod_key_sets = [_get_id_keys(m, entity_type) for m in mod_ids_list]
        result = _detect_dict_conflicts(base_ids[entity_type], mod_key_sets)
        if result:
            conflicts[entity_type] = result

    # tag 特殊处理（code、id、name 三类独立检测）
    code_conflicts, id_conflicts, name_conflicts = _detect_tag_conflicts(
        base_ids["tag"], base_ids.get("tag_id", set()), base_tag_names, mod_ids_list
    )
    if code_conflicts:
        conflicts["tag_code"] = code_conflicts
    if id_conflicts:
        conflicts["tag_id"] = id_conflicts

    return conflicts, name_conflicts


# ==================== ID 分配 ====================

def _collect_all_used_ids(
    base_ids: dict[str, set[str]],
    mod_ids_list: list[ModIdInfo],
) -> dict[str, set[str]]:
    """收集所有已使用的 ID（本体 + 所有 mod）"""
    used: dict[str, set[str]] = {k: set(v) for k, v in base_ids.items()}

    for mod_info in mod_ids_list:
        for k in used:
            if k == "tag_id":
                for _code, tag_id in mod_info.tag_ids.items():
                    used["tag_id"].add(str(tag_id))
            elif k == "tag":
                used["tag"].update(mod_info.tag_keys)
            else:
                used[k].update(_get_id_keys(mod_info, k))

    return used


def _next_available_id(start: int, used: set[str]) -> int:
    """从 start 开始找到下一个未使用的 ID"""
    candidate = start
    candidate_str = str(candidate)
    while candidate_str in used:
        candidate += 1
        candidate_str = str(candidate)
    return candidate


def allocate_new_ids(
    conflicts: dict[str, dict[str, list[int]]],
    all_used: dict[str, set[str]],
    mod_count: int,
) -> dict[tuple[int, str, str], str]:
    """
    为冲突 ID 分配新值。
    冲突中优先级最高的 mod（索引最大）保留原 ID，其余重分配。
    返回 {(mod_index, entity_type, old_id): new_id}
    """
    remap: dict[tuple[int, str, str], str] = {}
    effective_used: dict[str, set[str]] = {k: set(v) for k, v in all_used.items()}

    for entity_type, id_conflicts in conflicts.items():
        alloc_key = entity_type
        if alloc_key == "tag_code":
            continue
        start_val = ID_ALLOC_START.get(alloc_key, 9000000)
        start = start_val if start_val is not None else 9000000
        used = effective_used.setdefault(alloc_key, set())

        for old_id, mod_indices in id_conflicts.items():
            keeper = max(mod_indices)
            for mod_idx in sorted(mod_indices):
                if mod_idx == keeper:
                    continue
                new_id_int = _next_available_id(start, used)
                new_id = str(new_id_int)
                remap[(mod_idx, entity_type, old_id)] = new_id
                used.add(new_id)
                start = new_id_int + 1

    if "tag_code" in conflicts:
        tag_used = effective_used.setdefault("tag", set())
        for old_code, mod_indices in conflicts["tag_code"].items():
            keeper = max(mod_indices)
            suffix_counter = 1
            for mod_idx in sorted(mod_indices):
                if mod_idx == keeper:
                    continue
                new_code = f"{old_code}_{suffix_counter}"
                while new_code in tag_used:
                    suffix_counter += 1
                    new_code = f"{old_code}_{suffix_counter}"
                remap[(mod_idx, "tag_code", old_code)] = new_code
                tag_used.add(new_code)
                suffix_counter += 1

    return remap


def _allocate_tag_name_remaps(
    name_conflicts: dict[str, list[tuple[int, str]]],
    base_tag_names: set[str],
    mod_configs: list[tuple[str, str, Path]],
) -> dict[tuple[int, str], str]:
    """为 tag name 冲突分配新名称。

    与本体重名 → 所有 mod 条目重命名。
    仅 mod 间重名 → 最高优先级 mod 保留，其余重命名。
    新名称格式：「原名（【mod 名称首字符】）」，首字符冲突时扩展更多字符。

    返回 {(mod_idx, code): new_name}
    """
    result: dict[tuple[int, str], str] = {}

    for name, entries in name_conflicts.items():
        if name in base_tag_names:
            to_rename = entries
        else:
            max_idx = max(e[0] for e in entries)
            keeper_found = False
            to_rename = []
            for entry in entries:
                if entry[0] == max_idx and not keeper_found:
                    keeper_found = True
                    continue
                to_rename.append(entry)

        if not to_rename:
            continue

        used_suffixes: set[str] = set()
        for mod_idx, code in to_rename:
            mod_name = mod_configs[mod_idx][1]
            first_char = mod_name[0] if mod_name else "?"
            suffix = first_char
            if suffix in used_suffixes:
                for n in range(2, len(mod_name) + 1):
                    candidate = mod_name[:n]
                    if candidate not in used_suffixes:
                        suffix = candidate
                        break
                else:
                    counter = 2
                    while f"{first_char}{counter}" in used_suffixes:
                        counter += 1
                    suffix = f"{first_char}{counter}"
            used_suffixes.add(suffix)
            result[(mod_idx, code)] = f"{name}（【{suffix}】）"

    return result


def build_remap_table(
    remap: dict[tuple[int, str, str], str],
    mod_index: int,
    mod_ids: ModIdInfo,
) -> RemapTable:
    """为单个 mod 构建 RemapTable"""
    table = RemapTable()
    for (idx, entity_type, old_id), new_id in remap.items():
        if idx != mod_index:
            continue
        if entity_type == "cards":
            table.cards[old_id] = new_id
        elif entity_type == "tag_code":
            table.tag_codes[old_id] = new_id
        elif entity_type == "tag_id":
            table.tag_ids[int(old_id)] = int(new_id)
        elif entity_type == "rite":
            table.rite[old_id] = new_id
        elif entity_type == "event":
            table.event[old_id] = new_id
        elif entity_type == "over":
            table.over[old_id] = new_id
        elif entity_type == "loot":
            table.loot[old_id] = new_id
        elif entity_type == "rite_template":
            table.rite_template[old_id] = new_id
        elif entity_type == "rite_template_mappings":
            table.rite_template_mappings[old_id] = new_id
    return table


# ==================== ID 替换 ====================


def _remap_dict_keys(rel_path: str, doc: JsonDoc, remap: RemapTable) -> JsonDoc:
    """替换 dictionary 文件的顶层 key 及关联内部字段（纯 C++ json_ops 操作）"""
    if rel_path == "cards.json" and remap.cards:
        doc = replace_root_keys(doc, remap.cards)
        id_map = {int(old): int(new) for old, new in remap.cards.items()}
        doc = replace_field_ints(doc, "id", id_map)

    elif rel_path == "tag.json" and (remap.tag_codes or remap.tag_ids or remap.tag_names):
        if remap.tag_codes:
            doc = replace_root_keys(doc, remap.tag_codes)
            doc = replace_field_strs(doc, "code", remap.tag_codes)
        if remap.tag_ids:
            doc = replace_field_ints(doc, "id", remap.tag_ids)
        if remap.tag_name_mapping:
            doc = replace_field_strs(doc, "name", remap.tag_name_mapping)

    elif rel_path == "over.json" and remap.over:
        doc = replace_root_keys(doc, remap.over)

    elif rel_path == "rite_template_mappings.json" and remap.rite_template_mappings:
        doc = replace_root_keys(doc, remap.rite_template_mappings)
        id_map = {int(old): int(new) for old, new in remap.rite_template_mappings.items()}
        doc = replace_field_ints(doc, "id", id_map)

    return doc


def apply_remap_to_store(mod_id: str, remap: RemapTable) -> None:
    """将 remap 应用到 DataManager 中该 mod 的所有 JSON 数据（原地更新）。

    通过 C++ json_ops 直接操作 JsonDoc，保留重复键。
    """
    from ..data_manager import DataManager
    store = DataManager.instance()

    int_lookup = remap.build_int_lookup()
    str_lookup = remap.build_str_lookup()

    for rel_path in list(store.mod_files(mod_id)):
        doc = _safe_get_mod(store, mod_id, rel_path)
        if doc is None:
            continue

        new_rel = _compute_new_rel_path(rel_path, remap)

        doc = _remap_dict_keys(rel_path, doc, remap)

        if int_lookup:
            doc = remap_all_ints(doc, int_lookup)
        if str_lookup:
            doc = remap_all_str_ids(doc, str_lookup)

        if new_rel != rel_path:
            store.remove_mod_file(mod_id, rel_path)

        store.set_mod(mod_id, new_rel, doc)


def _compute_new_rel_path(rel_str: str, remap: RemapTable) -> str:
    """计算替换后的文件相对路径（处理文件名即 ID 的情况）"""
    parts = rel_str.split("/")

    # rite/XXXXX.json, event/XXXXX.json, loot/XXXXX.json, rite_template/XXXXX.json
    if len(parts) == 2 and parts[1].endswith(".json"):
        dirname = parts[0]
        stem = parts[1][:-5]  # 去掉 .json
        type_map: dict[str, dict[str, str]] = {
            "rite": remap.rite,
            "event": remap.event,
            "loot": remap.loot,
            "rite_template": remap.rite_template,
        }
        mapping = type_map.get(dirname)
        if mapping and stem in mapping:
            return f"{dirname}/{mapping[stem]}.json"

    # after_story/XXXXX.json（card ID）
    if len(parts) == 2 and parts[0] == "after_story" and parts[1].endswith(".json"):
        stem = parts[1][:-5]
        if stem in remap.cards:
            return f"after_story/{remap.cards[stem]}.json"

    return rel_str


def _split_stem_ext(filename: str) -> tuple[str, str]:
    """分离文件名和扩展名，如 '2020000.PNG' -> ('2020000', '.PNG')"""
    dot_idx = filename.rfind(".")
    if dot_idx < 0:
        return filename, ""
    return filename[:dot_idx], filename[dot_idx:]


def compute_resource_rename(rel_str: str, remap: RemapTable) -> str:
    """
    计算资源文件重映射后的目标路径。

    处理以下资源文件：
    - image/cards/{card_id}.ext 和 image/cards/{card_id}_{suffix}.ext
    - image/head/{card_id}.ext 和 image/head/{card_id}_{suffix}.ext
    - image/tag/tag_{tag_id}.ext

    不匹配时原样返回。
    """
    parts = rel_str.split("/")

    # image/cards/ 和 image/head/ 中的 card ID 图片
    if len(parts) == 3 and parts[0] == "image" and parts[1] in ("cards", "head"):
        stem, ext = _split_stem_ext(parts[2])
        for old_id, new_id in remap.cards.items():
            if stem == old_id:
                return f"image/{parts[1]}/{new_id}{ext}"
            if stem.startswith(old_id + "_"):
                new_stem = new_id + stem[len(old_id):]
                return f"image/{parts[1]}/{new_stem}{ext}"

    # image/tag/tag_{id}.ext 中的 tag ID 图片
    if len(parts) == 3 and parts[0] == "image" and parts[1] == "tag":
        stem, ext = _split_stem_ext(parts[2])
        if stem.startswith("tag_"):
            try:
                tag_id = int(stem[4:])
            except ValueError:
                return rel_str
            if tag_id in remap.tag_ids:
                return f"image/tag/tag_{remap.tag_ids[tag_id]}{ext}"

    return rel_str


# ==================== 主入口 ====================

def remap_mod_configs(
    mod_configs: list[tuple[str, str, Path]],
    cancel_check: CancelCheck | None = None,
) -> tuple[list[str], dict[str, RemapTable]]:
    """
    检测 ID 冲突并重分配，直接更新 store 中的数据。

    参数:
        mod_configs: [(mod_id, mod_name, mod_config_path), ...] 按优先级排序
        cancel_check: 可选的取消检查回调

    返回:
        (remap_messages, remap_tables)
        - remap_messages: 日志消息列表
        - remap_tables: {mod_id: RemapTable} 各 mod 的重映射表
    """
    messages: list[str] = []

    if not mod_configs:
        return messages, {}

    # 1. 收集本体 ID 和 tag name
    base_ids, base_tag_names = collect_base_ids()
    if cancel_check:
        cancel_check()

    # 2. 收集各 mod 的 ID
    mod_ids_list: list[ModIdInfo] = []
    for mod_id, _, _ in mod_configs:
        mod_ids_list.append(collect_mod_ids(mod_id))
    if cancel_check:
        cancel_check()

    # 3. 检测冲突（code、id、name 三类独立）
    conflicts, tag_name_conflicts = detect_conflicts(base_ids, base_tag_names, mod_ids_list)

    # 4. tag name 重分配
    tag_name_remap = _allocate_tag_name_remaps(
        tag_name_conflicts, base_tag_names, mod_configs,
    )

    if not conflicts and not tag_name_remap:
        return messages, {}

    # 汇总冲突信息
    type_counts: dict[str, int] = {t: len(ids) for t, ids in conflicts.items()}
    if tag_name_conflicts:
        type_counts["tag_name"] = len(tag_name_conflicts)
    total = sum(type_counts.values())
    summary_parts = [f"{t}: {c}" for t, c in sorted(type_counts.items())]
    summary = f"ID 冲突检测: 发现 {total} 个冲突 ({', '.join(summary_parts)})"
    messages.append(summary)
    diag.info("remap", summary)

    # 5. 分配新 ID（code/id 冲突）
    all_used = _collect_all_used_ids(base_ids, mod_ids_list)
    remap = allocate_new_ids(conflicts, all_used, len(mod_configs))
    if cancel_check:
        cancel_check()

    # 6. 为每个有冲突的 mod 构建 remap table 并应用到 store
    remap_tables: dict[str, RemapTable] = {}
    mods_needing_remap: set[int] = set()
    for (mod_idx, _, _) in remap:
        mods_needing_remap.add(mod_idx)
    for (mod_idx, _code) in tag_name_remap:
        mods_needing_remap.add(mod_idx)

    for mod_idx in sorted(mods_needing_remap):
        mod_id, mod_name, _ = mod_configs[mod_idx]
        table = build_remap_table(remap, mod_idx, mod_ids_list[mod_idx])

        # 合并 tag name 重分配
        for (idx, code), new_name in tag_name_remap.items():
            if idx == mod_idx:
                table.tag_names[code] = new_name

        # 构建 tag_name_mapping（old_name → new_name）供 C++ replace_field_strs 使用
        if table.tag_names:
            for code, new_name in table.tag_names.items():
                old_name = mod_ids_list[mod_idx].tag_names.get(code, "")
                if old_name and old_name != new_name:
                    table.tag_name_mapping[str(old_name)] = new_name

        if table.is_empty():
            continue

        # 日志输出每个重分配
        for old_id, new_id in table.cards.items():
            name = mod_ids_list[mod_idx].cards_names.get(old_id, "")
            suffix = f" ({name})" if name else ""
            msg = f"ID 重分配: Mod [{mod_name}] card {old_id} → {new_id}{suffix}"
            messages.append(msg)
            diag.info("remap", msg)

        for field_name, label in _REMAP_LOG_FIELDS:
            for old_id, new_id in getattr(table, field_name).items():
                msg = f"ID 重分配: Mod [{mod_name}] {label} {old_id} → {new_id}"
                messages.append(msg)
                diag.info("remap", msg)

        for code, new_name in table.tag_names.items():
            old_name = mod_ids_list[mod_idx].tag_names.get(code, "")
            msg = f"ID 重分配: Mod [{mod_name}] tag name {old_name} → {new_name} (code={code})"
            messages.append(msg)
            diag.info("remap", msg)

        # 直接更新 store 中的数据
        apply_remap_to_store(mod_id, table)
        remap_tables[mod_id] = table

        if cancel_check:
            cancel_check()

    return messages, remap_tables
