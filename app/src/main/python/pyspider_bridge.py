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
import re
import sys
import traceback

_spiders = {}

# v0.27.5 编码识别：HTTP header 声明优先（浏览器同策略），检测只做 fallback。
# 背景：黄果_v18.py 第 392 行 `_fetch_html` 用 `resp.apparent_encoding` 解码，
# chardet 会把该站页面误判成 Windows-1254 导致标题乱码；header 明确声明了 utf-8。
# 注意：这个 patch 修的是"乱码"，不是 v0.27.8 的 USC4 崩溃（见下）。
try:
    from requests.models import Response as _ReqResponse

    _orig_apparent_fget = _ReqResponse.apparent_encoding.fget

    def _safe_apparent_encoding(self):
        m = re.search(
            r"charset\s*=\s*([\w-]+)",
            self.headers.get("content-type", ""),
            re.I,
        )
        if m:
            return m.group(1)
        return _orig_apparent_fget(self)

    _ReqResponse.apparent_encoding = property(_safe_apparent_encoding)
    # v0.27.6 可观测：patch 成功打上就走 logcat，下次看日志能确认手机上是否生效
    print("[pybridge] apparent_encoding patch 已生效：header 优先")
except Exception:
    pass

# v0.27.8 根治详情 XMLSyntaxError（USC4 little endian）：
# 真根因（v0.27.5 定错了）：页面简介里有 emoji（U+1F60D/U+1F924 等 astral 字符）。
# 含 astral 字符的 Python str 在 CPython 里按 UCS-4 存储，lxml 的 _parseUnicodeDoc
# 会把 UCS-4 buffer 直接传给 libxml2 并声明 UTF-32LE；手机 Chaquopy 自带的 libxml2
# 是精简构建，缺这个编码 handler，第 1 行第 1 列直接 fatal。桌面 libxml2 自带该
# handler，所以桌面永远复现不了、真机必挂——之前所有"桌面自测全过"都不可信。
# 修法（不动用户规则文件）：包一层 lxml.etree.fromstring / lxml.html.fromstring，
# str 输入含 astral 字符时先转成数字字符引用（&#x1F60D;），解析器会解回原字符，
# 规则读到的内容逐字不变；bytes 输入和纯 BMP 字符串零改动直接透传。
try:
    from lxml import etree as _etree
    import lxml.html as _lxml_html

    _astral_re = re.compile(r"[\U00010000-\U0010FFFF]")
    _orig_etree_fromstring = _etree.fromstring
    _orig_lxml_html_fromstring = _lxml_html.fromstring

    def _astral_to_ncr(s):
        return _astral_re.sub(lambda m: "&#x%X;" % ord(m.group(0)), s)

    def _safe_etree_fromstring(text, *args, **kwargs):
        if isinstance(text, str) and _astral_re.search(text):
            text = _astral_to_ncr(text)
        return _orig_etree_fromstring(text, *args, **kwargs)

    def _safe_lxml_html_fromstring(text, *args, **kwargs):
        if isinstance(text, str) and _astral_re.search(text):
            text = _astral_to_ncr(text)
        return _orig_lxml_html_fromstring(text, *args, **kwargs)

    _etree.fromstring = _safe_etree_fromstring
    _lxml_html.fromstring = _safe_lxml_html_fromstring
    print("[pybridge] lxml fromstring patch 已生效：astral 字符转 NCR")
except Exception:
    pass

# v0.27.4 诊断：记录规则最近一次 HTTP 响应的元信息（URL/状态/编码/前 200 字节 hex）。
# 真机实锤：依赖包已装但详情仍报 XMLSyntaxError（USC4），先拿数据不猜——
# 若响应体是未解开的压缩二进制，前几个字节的 hex 能直接看出是哪种压缩。
_last_resp_info = None


def _install_resp_sniffer():
    try:
        import requests.adapters
    except Exception:
        return
    orig_send = requests.adapters.HTTPAdapter.send

    def send(self, request, **kwargs):
        global _last_resp_info
        resp = orig_send(self, request, **kwargs)
        try:
            raw = resp.content[:200] if resp is not None else b""
            ct = resp.headers.get("Content-Type", "")
            # v0.27.6 可观测：text 类响应记录 patch 后的 apparent_encoding。
            # 下次"PY 最近响应"里直接看到编码是谁定的："utf-8"（header）= patch 生效，
            # "Windows-1254"/"UTF-32"之类 = patch 没生效、还是检测值。
            apparent = ""
            if ct.lower().startswith("text/"):
                try:
                    apparent = resp.apparent_encoding or ""
                except Exception:
                    apparent = ""
            _last_resp_info = {
                "url": str(getattr(request, "url", ""))[:160],
                "status": getattr(resp, "status_code", -1),
                "content_encoding": resp.headers.get("Content-Encoding", ""),
                "content_type": ct[:80],
                "apparent_encoding": apparent,
                "head_hex": raw.hex(),
            }
        except Exception:
            pass
        return resp

    requests.adapters.HTTPAdapter.send = send


_install_resp_sniffer()


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
                "last_resp": _last_resp_info,
            }
        )


def reset(rule_path=""):
    """清掉缓存（规则文件更新后调用）。空串=清全部。"""
    if rule_path:
        _spiders.pop(rule_path, None)
    else:
        _spiders.clear()
    return _to_json({"ok": True})
