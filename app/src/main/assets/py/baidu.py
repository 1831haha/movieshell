# -*- coding: utf-8 -*-
"""
百度网盘分享解析规则（v1.1.23 内置）

只做一件事：把百度分享链接变成可播直链。
链路：wxlist 列文件 → tplconfig 取 sign → sharedownload 取 dlink。
取 dlink 分两档，按顺序走，拿到就停：
1. nolimit（登录态，clienttype=12 + type=nolimit）——SVIP 直链；
2. 普通（clienttype=0）——游客/普通登录大概率只给一段加密串，播不了。

已实测（2026-11-10 沙箱出口直连 pan.baidu.com）：
- wxlist 通：POST /share/wxlist，shorturl 必须带 1 前缀全码
  （用不带 1 的码报 errno:2）；返回 uk/shareid/seckey/list，
  测试分享 1 个文件 Possible.Love.2026.mp4，3.7GB。
- tplconfig 通：sign 40 位 hex、timestamp 5 分钟有效。
- sharedownload 游客卡住：clienttype=0 只给 3992 字符 base64 密文，
  不是直链；clienttype=12 + type=nolimit 报 errno 9019 need verify。
  公开实现证实 nolimit 路线要带已登录 SVIP 的 BDUSS 才能出明文 dlink。
- verify 接口不可靠（同一链接两次结果不同，errno 0 与 2 都出现过），
  本规则不用它做检测；wxlist 不依赖它也能通。

登录态取法：App 里登录百度后存在私有目录，规则经 Java 桥读
BaiduAuthStore.getCookie() 拿到；拿不到按游客跑。
回源头：大于约 20MB 的文件回源须带 User-Agent: pan.baidu.com，
本规则回给播放器的头固定带它。
dlink 短时效（sign 5 分钟），现取现用，绝不缓存。

注意：百度登录后全程沙箱验不了（无百度 SVIP 账号），
登录态下 dlink 能不能出、非 SVIP 能不能出，真机待验。
"""
import json
import re
import sys
import urllib.parse

sys.path.append('..')
from base.spider import Spider


class Spider(Spider):
    def getName(self):
        return "百度网盘"

    def init(self, extend=""):
        self.ua = ("Mozilla/5.0 (Linux; Android 14; Redmi K80) AppleWebKit/537.36 "
                   "(KHTML, like Gecko) Chrome/120.0.6099.144 Mobile Safari/537.36")
        self.base = "https://pan.baidu.com"
        self.referer = "https://pan.baidu.com/"
        self._cookie = None
        self._video_exts = (".mp4", ".mkv", ".mov", ".avi", ".flv", ".wmv",
                            ".m4v", ".ts", ".rmvb", ".webm")

    # ---------------- 登录态 ----------------

    def _load_cookie(self):
        if self._cookie is not None:
            return self._cookie
        self._cookie = ""
        try:
            from com.movieshell.data.source_mgr import BaiduAuthStore
            self._cookie = BaiduAuthStore.getCookie() or ""
        except Exception:
            self._cookie = ""
        return self._cookie

    def _session(self, with_cookie=False):
        import requests
        s = requests.Session()
        s.headers.update({
            "User-Agent": self.ua,
            "Referer": self.referer,
            "Accept": "application/json, text/plain, */*",
        })
        if with_cookie:
            c = self._load_cookie()
            if c:
                s.headers["Cookie"] = c
        return s

    # ---------------- 分享解析 ----------------

    def _parse_share(self, url):
        m = re.search(r"pan\.baidu\.com/s/([A-Za-z0-9_-]+)", url or "")
        if not m:
            return None, ""
        full = m.group(1)
        qs = urllib.parse.parse_qs(urllib.parse.urlparse(url).query)
        code = (qs.get("pwd") or [""])[0]
        if not code:
            m2 = re.search(r"密码[:：]?\s*([A-Za-z0-9]+)", url or "")
            code = m2.group(1) if m2 else ""
        return full, code

    def _wxlist(self, s, full, pwd, dir_path="", root="1"):
        """返回 (文件列表, seckey, uk, shareid, 标题)。失败返回空元组。"""
        try:
            r = s.post(self.base + "/share/wxlist", params={
                "channel": "weixin", "version": "2.2.2",
                "clienttype": "25", "web": "1",
            }, data={
                "shorturl": full, "dir": dir_path, "root": root,
                "pwd": pwd, "page": "1", "num": "1000", "order": "time",
            }, timeout=15)
            d = r.json()
            if d.get("errno") != 0:
                return [], "", "", "", ""
            data = d.get("data") or {}
            return (data.get("list") or [], data.get("seckey") or "",
                    str(data.get("uk") or ""), str(data.get("shareid") or ""),
                    data.get("title") or "")
        except Exception:
            return [], "", "", "", ""

    def _is_dir(self, f):
        # wxlist 的 isdir 是字符串 "0"/"1"，直接当布尔值会把 "0" 判成真。
        return str(f.get("isdir") or "0") == "1"

    def _list_share(self, share_url):
        """返回 (标题, 视频文件列表, uk, shareid, seckey, 全码, 提取码)。
        多层目录自动收集：根目录起，最多进 24 个子目录、最深 4 层、
        最多 200 个视频；集名带上子目录前缀以便区分。"""
        full, pwd = self._parse_share(share_url)
        if not full:
            return "", [], "", "", "", "", ""
        s = self._session()
        items, seckey, uk, shareid, title = self._wxlist(s, full, pwd)
        if not items and not seckey:
            return "", [], "", "", "", "", ""
        all_files = []
        queue = []  # (目录 path, 集名前缀, 深度)
        visited_dirs = 0

        def _collect(cur_items, prefix, depth):
            nonlocal visited_dirs
            for f in (cur_items or []):
                if self._is_dir(f):
                    if depth < 4 and visited_dirs < 24:
                        queue.append((f.get("path") or "",
                                      prefix + str(f.get("server_filename") or "") + "/",
                                      depth + 1))
                        visited_dirs += 1
                else:
                    if prefix:
                        f = dict(f)
                        f["server_filename"] = prefix + str(f.get("server_filename") or "")
                    all_files.append(f)

        _collect(items, "", 0)
        while queue and len(all_files) < 200:
            dir_path, prefix, depth = queue.pop(0)
            cur_items, _sk, _uk, _sid, _t = self._wxlist(
                s, full, pwd, dir_path=dir_path, root="0")
            _collect(cur_items, prefix, depth)
        videos = [f for f in all_files
                  if str(f.get("server_filename") or "").lower().endswith(self._video_exts)]
        if not videos:
            videos = all_files
        if len(videos) == 1:
            title = str(videos[0].get("server_filename") or title)
        elif not title and all_files:
            title = str(all_files[0].get("server_filename") or "")
        return title, videos, uk, shareid, seckey, full, pwd

    def _tplconfig(self, s, full):
        """返回 (sign, timestamp)。失败返回空串。"""
        try:
            r = s.get(self.base + "/share/tplconfig", params={
                "fields": "sign,timestamp", "channel": "chunlei", "web": "1",
                "app_id": "250528", "clienttype": "0", "surl": full,
            }, timeout=15)
            d = r.json()
            if d.get("errno") != 0:
                return "", ""
            data = d.get("data") or {}
            return data.get("sign") or "", str(data.get("timestamp") or "")
        except Exception:
            return "", ""

    def _sharedownload(self, s, uk, shareid, seckey, sign, timestamp, fs_id, clienttype, nolimit):
        """返回明文 dlink；拿到加密串或失败返回空串。"""
        try:
            params = {
                "channel": "chunlei", "clienttype": str(clienttype),
                "app_id": "250528", "sign": sign, "timestamp": timestamp, "web": "1",
            }
            if nolimit:
                params["type"] = "nolimit"
            r = s.post(self.base + "/api/sharedownload", params=params, data={
                "encrypt": "0", "product": "share",
                "primaryid": str(shareid), "shareid": str(shareid),
                "uk": str(uk), "fid_list": "[%s]" % fs_id,
                "extra": json.dumps({"sekey": seckey}),
            }, timeout=15)
            d = r.json()
            if d.get("errno") != 0:
                return ""
            lst = (d.get("data") or {}).get("list")
            if isinstance(lst, list) and lst and isinstance(lst[0], dict):
                return lst[0].get("dlink") or ""
            # list 是 base64 密文串：游客/非 SVIP 拿不到明文直链。
            return ""
        except Exception:
            return ""

    def _resolve_file(self, share_url, want_fs_id=""):
        title, videos, uk, shareid, seckey, full, pwd = self._list_share(share_url)
        if not videos:
            return None, "", "", "", "", "", ""
        pick = None
        if want_fs_id:
            for f in videos:
                if str(f.get("fs_id")) == want_fs_id:
                    pick = f
                    break
        if pick is None:
            pick = max(videos, key=lambda f: int(f.get("size") or 0))
        return pick, uk, shareid, seckey, full, pwd, title

    # ---------------- TVBox 五方法 ----------------

    def homeContent(self, filter=True):
        return {"class": [], "filters": {}}

    def categoryContent(self, tid, pg=1, filter=True, extend=None):
        return {"page": 1, "pagecount": 1, "limit": 30, "total": 0, "list": []}

    def detailContent(self, ids):
        out = []
        for share_url in ids:
            share_url = str(share_url or "").strip()
            if not share_url:
                continue
            got = self._list_share(share_url)
            title, videos, _uk, _sid, _sk, _full, _pwd = got
            if not videos:
                continue
            eps = []
            for f in videos:
                eps.append("%s$%s@@%s" % (f.get("server_filename") or "视频",
                                          share_url, f.get("fs_id") or ""))
            out.append({
                "vod_id": share_url,
                "vod_name": title or "百度分享",
                "vod_pic": "",
                "vod_remarks": "百度网盘",
                "vod_play_from": "百度网盘",
                "vod_play_url": "#".join(eps),
            })
        return {"list": out}

    def searchContent(self, key, quick=False, pg="1"):
        key = str(key or "").strip()
        full, _code = self._parse_share(key)
        if not full:
            return {"list": []}
        s = self._session()
        _items, _sk, _uk, _sid, title = self._wxlist(s, full, _code)
        return {"list": [{
            "vod_id": key,
            "vod_name": title or "百度分享",
            "vod_pic": "",
            "vod_remarks": "百度网盘",
        }]}

    def playerContent(self, flag, id, vipFlags):
        raw = str(id or "").strip()
        if "@@" in raw:
            share_url, want_fs_id = raw.split("@@", 1)
        else:
            share_url, want_fs_id = raw, ""
        got = self._resolve_file(share_url, want_fs_id)
        pick, uk, shareid, seckey, full, _pwd, _title = got
        if pick is None:
            return {"parse": 0, "url": ""}
        fs_id = str(pick.get("fs_id") or "")
        if not fs_id or not uk or not shareid or not seckey:
            return {"parse": 0, "url": ""}
        cookie = self._load_cookie()
        s = self._session(with_cookie=bool(cookie))
        sign, timestamp = self._tplconfig(s, full)
        if not sign:
            return {"parse": 0, "url": ""}
        url = ""
        # 有登录态先试 nolimit（SVIP 直链），不成再试普通档；无登录只试普通档。
        if cookie:
            url = self._sharedownload(s, uk, shareid, seckey, sign, timestamp, fs_id, 12, True)
        if not url:
            url = self._sharedownload(s, uk, shareid, seckey, sign, timestamp, fs_id, 0, False)
        if not url:
            return {"parse": 0, "url": ""}
        return {"parse": 0, "url": url, "header": {
            "User-Agent": "pan.baidu.com",
            "Referer": self.referer,
        }}
