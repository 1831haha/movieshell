"""movieshell PY 规则桥：Kotlin(ChaquopyEngine) <-> hipy 风格 Spider 规则。

规则文件是用户 .py 文件（位于 filesDir/py/），桥按绝对路径动态加载，
实例化其中定义的 Spider 子类并调用其方法，结果一律转 JSON 字符串回传。

约定：
- call(rule_path, method, args_json) -> JSON 字符串：
  {"ok": true, "data": <方法返回值>} 或 {"ok": false, "error": "...", "trace": "..."}
- args_json 是传给 Spider 方法的关键字参数，如 '{"filter": true}'。
- 规则里的 `from base.spider import Spider` 能解析到本包内 bundled 的 base/spider.py。
"""

import importlib.util
import json
import sys
import traceback

_spiders = {}


def _load_spider(rule_path):
    """加载规则文件并实例化 Spider（带缓存）。"""
    if rule_path in _spiders:
        return _spiders[rule_path]
    mod_name = "ms_rule_%d" % (abs(hash(rule_path)) % (10 ** 8))
    spec = importlib.util.spec_from_file_location(mod_name, rule_path)
    if spec is None or spec.loader is None:
        raise ImportError("无法加载规则文件: %s" % rule_path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[mod_name] = module
    spec.loader.exec_module(module)

    spider_cls = None
    # 优先找模块内定义的 Spider 子类（排除 import 进来的基类本身）
    for name in dir(module):
        obj = getattr(module, name, None)
        if (
            isinstance(obj, type)
            and getattr(obj, "__module__", None) == module.__name__
            and hasattr(obj, "homeContent")
        ):
            spider_cls = obj
            break
    if spider_cls is None:
        raise ImportError("规则文件里没有找到 Spider 子类: %s" % rule_path)

    inst = spider_cls()
    try:
        inst.init("")
    except Exception:
        pass
    _spiders[rule_path] = inst
    return inst


def _to_json(obj):
    return json.dumps(obj, ensure_ascii=False, default=str)


def call(rule_path, method, args_json):
    """统一入口，供 Kotlin 经 Chaquopy 调用。"""
    try:
        spider = _load_spider(rule_path)
        args = json.loads(args_json) if args_json else {}
        if not isinstance(args, dict):
            raise ValueError("args_json 必须是 JSON 对象")
        fn = getattr(spider, method, None)
        if not callable(fn):
            raise AttributeError("Spider 没有方法: %s" % method)
        result = fn(**args)
        return _to_json({"ok": True, "data": result})
    except Exception as e:
        return _to_json(
            {
                "ok": False,
                "error": "%s: %s" % (type(e).__name__, e),
                "trace": traceback.format_exc(limit=8),
            }
        )


def reset(rule_path=""):
    """清掉缓存（规则文件更新后调用）。空串=清全部。"""
    if rule_path:
        _spiders.pop(rule_path, None)
    else:
        _spiders.clear()
    return _to_json({"ok": True})
