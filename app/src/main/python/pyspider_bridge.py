"""movieshell PY 规则桥：Kotlin(ChaquopyEngine) <-> hipy 风格 Spider 规则。

规则文件是用户 .py 文件（位于 filesDir/py/），桥按绝对路径动态加载，
实例化其中定义的 Spider 子类并调用其方法，结果一律转 JSON 字符串回传。

约定：
- call(rule_path, method, args_json) -> JSON 字符串：
  {"ok": true, "data": <方法返回值>} 或 {"ok": false, "error": "...", "trace": "..."}
- args_json 是传给 Spider 方法的关键字参数，如 '{"filter": true}'。
- 规则里的 `from base.spider import Spider` 能解析到本包内 bundled 的 base/spider.py。
"""

import base64
import importlib.util
import inspect
import json
import re
import sys
import traceback
import types
import zipfile
from urllib.parse import urlparse

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

# v0.294.5：调用方未指定 timeout 时的默认值（连接, 读取）秒
_DEFAULT_TIMEOUT = (15, 15)

# v1.3.30：PY 代理按源开关——Kotlin 侧（ChaquopyEngine.setPyProxy）把当前
# PY 源的代理状态推过来：开着就给 clash 混合端口地址，否则空串。
# 所有 PY 请求都过 _install_resp_sniffer 改写后的 HTTPAdapter.send，
# 在那里按请求补 proxies。规则自己显式传了 proxies 的不动。
_CLASH_PROXY_URL = ""


def set_clash_proxy(url):
    """Kotlin 侧调：url 为 "http://127.0.0.1:<混合端口>"，空串/None 即关代理。"""
    global _CLASH_PROXY_URL
    try:
        _CLASH_PROXY_URL = url or ""
    except Exception:
        _CLASH_PROXY_URL = ""


def _install_resp_sniffer():
    try:
        import requests.adapters
    except Exception:
        return
    orig_send = requests.adapters.HTTPAdapter.send

    def send(self, request, **kwargs):
        global _last_resp_info
        # v0.294.5：规则没给 timeout（requests 默认 None＝无限等）时补默认 15s 连接/读取，
        # 防止一个挂死的请求把 PY 调用线程永久占住、后续调用排队堆积。
        if kwargs.get("timeout") is None:
            kwargs["timeout"] = _DEFAULT_TIMEOUT
        # v1.3.30：当前 PY 源开了代理开关时补 proxies（规则显式传了的不动，
        # 本地地址不走代理）。_CLASH_PROXY_URL 由 Kotlin 侧推，见 set_clash_proxy。
        # 注意不能只判 proxies 字典非空：requests 合并环境变量后字典里可能
        # 有与本次请求无关的键（如沙箱的 node_use_env），要按 scheme 逐项看；
        # 用 setdefault 合并，规则显式给过的 scheme 绝不覆盖。
        if _CLASH_PROXY_URL:
            try:
                _pu = urlparse(str(getattr(request, "url", "")))
                _h = (_pu.hostname or "").lower()
                _scheme = (_pu.scheme or "http").lower()
            except Exception:
                _h, _scheme = "", "http"
            if _h and _h not in ("127.0.0.1", "localhost", "::1"):
                _p = dict(kwargs.get("proxies") or {})
                _p.setdefault("http", _CLASH_PROXY_URL)
                _p.setdefault("https", _CLASH_PROXY_URL)
                kwargs["proxies"] = _p
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


def _find_spider_class(module):
    """找模块内定义的 Spider 子类（排除 import 进来的基类本身）。"""
    for name in dir(module):
        obj = getattr(module, name, None)
        if (
            isinstance(obj, type)
            and getattr(obj, "__module__", None) == module.__name__
            and hasattr(obj, "homeContent")
        ):
            return obj
    return None


def _instantiate_spider(module, rule_path):
    """按找到的 Spider 子类实例化（init 失败吞掉，与原路同款）。"""
    spider_cls = _find_spider_class(module)
    if spider_cls is None:
        raise ImportError("规则文件里没有找到 Spider 子类: %s" % rule_path)
    inst = spider_cls()
    try:
        inst.init("")
    except Exception:
        pass
    return inst


def _load_spider_zip(rule_path):
    """从压缩包条目加载 Spider（v1.1.14：压缩包源永远不解压）。

    桥态身份：zip:<zip 绝对路径>!<entry 路径>，缓存键就是该身份串。
    """
    if rule_path in _spiders:
        return _spiders[rule_path]
    rest = rule_path[4:]
    bang = rest.find("!")
    if bang <= 0:
        raise ImportError("压缩包规则身份不对：%s" % rule_path)
    zip_path = rest[:bang]
    entry_path = rest[bang + 1:]
    if not entry_path:
        raise ImportError("压缩包规则身份不对：%s" % rule_path)
    try:
        with zipfile.ZipFile(zip_path) as zf:
            code_bytes = zf.read(entry_path)
    except Exception as e:
        raise ImportError("压缩包规则读不出来：%s（%s）" % (rule_path, e))
    code = None
    for enc in ("utf-8", "gbk"):
        try:
            code = code_bytes.decode(enc)
            break
        except Exception:
            continue
    if code is None:
        raise ImportError("压缩包规则解码失败：%s" % rule_path)
    mod_name = "ms_zip_%d" % (abs(hash(rule_path)) % (10 ** 8))
    module = types.ModuleType(mod_name)
    module.__file__ = rule_path
    sys.modules[mod_name] = module
    exec(compile(code, rule_path, "exec"), module.__dict__)
    inst = _instantiate_spider(module, rule_path)
    _spiders[rule_path] = inst
    return inst


def _load_spider(rule_path):
    """加载规则文件并实例化 Spider（带缓存）。"""
    if rule_path in _spiders:
        return _spiders[rule_path]
    if rule_path.startswith("zip:"):
        return _load_spider_zip(rule_path)
    mod_name = "ms_rule_%d" % (abs(hash(rule_path)) % (10 ** 8))
    # v0.294.22：可信副本偶发无 .py 后缀时，spec_from_file_location 在 Chaquopy 上返回 None；
    # 改用 SourceFileLoader 按路径硬加载，不依赖后缀。
    spec = importlib.util.spec_from_file_location(mod_name, rule_path)
    if spec is None or spec.loader is None:
        try:
            from importlib.machinery import SourceFileLoader
            loader = SourceFileLoader(mod_name, rule_path)
            spec = importlib.util.spec_from_loader(mod_name, loader)
        except Exception:
            spec = None
    if spec is None or spec.loader is None:
        raise ImportError("无法加载规则文件: %s" % rule_path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[mod_name] = module
    spec.loader.exec_module(module)

    inst = _instantiate_spider(module, rule_path)
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
        # v0.58：参数兼容——只传规则函数签名接受的参数。
        # 部分老规则 categoryContent(self, pg) 不接受 tid，硬塞 **args 会 TypeError。
        # 有 **kwargs 的函数则全量透传。
        try:
            params = inspect.signature(fn).parameters.values()
            if any(p.kind == inspect.Parameter.VAR_KEYWORD for p in params):
                filtered = args
            else:
                accepted = {p.name for p in params}
                filtered = {k: v for k, v in args.items() if k in accepted}
        except (ValueError, TypeError):
            filtered = args  # 签名拿不到时回退原行为
        result = fn(**filtered)
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


def proxy_image(rule_path, _method, args_json):
    """localProxy 取图：供 Kotlin 图片加载调规则自带的 localProxy。

    背景：部分 PY 规则把封面写成 proxy://do=py&url=..，只有规则自己的
    localProxy 知道取图要带什么 Referer；Kotlin 侧直接拿真实地址去要图
    会被图站防盗链拦下。桥在这里调一次 localProxy，把返回的字节转 base64
    经 JSON 回传（桥一律走 JSON 字符串，字节不能直传）。
    返回：{"ok": true, "data": {"status": .., "contentType": .., "base64": ..}}。
    规则没有 localProxy、或返回格式不对时返回 ok:false；规则取图失败时
    按约定返回 1x1 透明 png，Kotlin 侧按字节过小判失败、回落占位。
    """
    try:
        spider = _load_spider(rule_path)
        fn = getattr(spider, "localProxy", None)
        if not callable(fn):
            return _to_json({"ok": False, "error": "规则没有 localProxy 方法"})
        args = json.loads(args_json) if args_json else {}
        url = args.get("url") or ""
        if not url:
            return _to_json({"ok": False, "error": "proxy_image 缺少 url"})
        result = fn({"url": url})
        if not isinstance(result, (list, tuple)) or len(result) < 3:
            return _to_json({"ok": False, "error": "localProxy 返回格式不对"})
        status, ctype, data = result[0], result[1], result[2]
        if isinstance(data, str):
            data = data.encode("utf-8")
        elif isinstance(data, bytearray):
            data = bytes(data)
        if not isinstance(data, bytes):
            return _to_json({"ok": False, "error": "localProxy 返回的不是图片字节"})
        return _to_json({
            "ok": True,
            "data": {
                "status": status,
                "contentType": ctype,
                "base64": base64.b64encode(data).decode("ascii"),
            },
        })
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
        _book_spiders.pop(rule_path, None)
    else:
        _spiders.clear()
        _book_spiders.clear()
    return _to_json({"ok": True})


def reset_zip(zip_name):
    """清掉指定压缩包内全部条目的桥缓存（v1.1.14：zip 导入覆盖/删除后调用）。

    缓存键是桥态身份串，zip 部分在感叹号之前，取 basename 与 zip_name 比。
    """
    import os

    doomed = [
        k for k in list(_spiders)
        if k.startswith("zip:") and os.path.basename(k[4:].split("!", 1)[0]) == zip_name
    ]
    for k in doomed:
        _spiders.pop(k, None)
        _book_spiders.pop(k, None)
    return _to_json({"ok": True})


# ---------------- v0.64.0 书源（小说）PY 规则桥 ----------------
# 书源规则文件里的类需实现：
#   bookSearch(self, key)            -> {"list": [{"book_id","book_name","author","cover"}]}
#   bookDetail(self, book_id)        -> {"book_name","author","intro","cover","chapters":[{"title","url"}]}
#   bookChapter(self, url)           -> {"content": "正文"}
# 与视频 Spider 分开加载（判定条件是 bookSearch 方法），缓存也独立。

_book_spiders = {}


def _load_book_spider(rule_path):
    """加载书源规则文件并实例化书源类（带缓存）。"""
    if rule_path in _book_spiders:
        return _book_spiders[rule_path]
    mod_name = "ms_book_%d" % (abs(hash(rule_path)) % (10 ** 8))
    spec = importlib.util.spec_from_file_location(mod_name, rule_path)
    if spec is None or spec.loader is None:
        raise ImportError("无法加载书源文件: %s" % rule_path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[mod_name] = module
    spec.loader.exec_module(module)

    spider_cls = None
    for name in dir(module):
        obj = getattr(module, name, None)
        if (
            isinstance(obj, type)
            and getattr(obj, "__module__", None) == module.__name__
            and hasattr(obj, "bookSearch")
        ):
            spider_cls = obj
            break
    if spider_cls is None:
        raise ImportError("书源文件里没有找到书源类（需要 bookSearch 方法）: %s" % rule_path)

    inst = spider_cls()
    try:
        inst.init("")
    except Exception:
        pass
    _book_spiders[rule_path] = inst
    return inst


def book_call(rule_path, method, args_json):
    """书源统一入口，供 Kotlin 经 Chaquopy 调用。"""
    try:
        spider = _load_book_spider(rule_path)
        args = json.loads(args_json) if args_json else {}
        if not isinstance(args, dict):
            raise ValueError("args_json 必须是 JSON 对象")
        fn = getattr(spider, method, None)
        if not callable(fn):
            raise AttributeError("书源没有方法: %s" % method)
        try:
            params = inspect.signature(fn).parameters.values()
            if any(p.kind == inspect.Parameter.VAR_KEYWORD for p in params):
                filtered = args
            else:
                accepted = {p.name for p in params}
                filtered = {k: v for k, v in args.items() if k in accepted}
        except (ValueError, TypeError):
            filtered = args
        result = fn(**filtered)
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
