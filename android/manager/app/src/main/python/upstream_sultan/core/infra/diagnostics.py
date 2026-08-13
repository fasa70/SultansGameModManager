"""
线程安全的全局诊断信息收集器

替代原来散落在各模块中的 parse_warnings / scan_errors / merge_warnings 列表。
使用 category 区分消息来源："parse" / "scan" / "merge" / "schema"。
每条消息附带级别：INFO / WARNING / ERROR。
"""
import threading

# 日志级别常量
INFO = "info"
WARNING = "warning"
ERROR = "error"


class Diagnostics:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._messages: dict[str, list[tuple[str, str]]] = {}  # category → [(level, msg)]

    def info(self, category: str, msg: str) -> None:
        """线程安全地追加一条信息级别的诊断消息"""
        with self._lock:
            self._messages.setdefault(category, []).append((INFO, msg))

    def warn(self, category: str, msg: str) -> None:
        """线程安全地追加一条警告级别的诊断消息"""
        with self._lock:
            self._messages.setdefault(category, []).append((WARNING, msg))

    def error(self, category: str, msg: str) -> None:
        """线程安全地追加一条错误级别的诊断消息"""
        with self._lock:
            self._messages.setdefault(category, []).append((ERROR, msg))

    def snapshot(self, *categories: str) -> list[tuple[str, str]]:
        """返回指定类别的消息并清空。不传参则返回全部类别。
        返回 [(level, msg), ...] 列表。
        """
        with self._lock:
            if not categories:
                categories = tuple(self._messages.keys())
            result = []
            for cat in categories:
                result.extend(self._messages.get(cat, []))
                self._messages.pop(cat, None)
            return result


diag = Diagnostics()


class MergeContext(threading.local):
    """线程本地的合并上下文，由 merge_file 设置，供 deep_merge 中的警告生成读取"""
    def __init__(self) -> None:
        super().__init__()
        self.mod_name: str = ""
        self.mod_id: str = ""
        self.rel_path: str = ""
        self.source_file: str = ""  # mod 源文件绝对路径

merge_ctx = MergeContext()
