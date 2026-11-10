# -*- coding: utf-8 -*-
"""
UC 网盘分享解析规则（v1.1.23 内置）

只做一件事：把 UC 分享链接变成可播直链。
链路与夸克完全同名同形，只差 host 与 pr：
base https://pc-api.uc.cn/1/clouddrive/，pr=UCBrowser&fr=pc。
取直链分三档，按顺序走，拿到就停：
1. 游客/登录直取（share 侧 file/download）——小文件直接通；
2. 转存原画（登录态）：save→轮询 task→自己盘 file/download，
   走 Kotlin 的 PanSaveApi（可单测）；UC 的 fids 传单元素数组；
3. 转码预览（登录态，转存成功但直取失败时）：file/v2/play 取 m3u8。

已实测（2026-11-10 沙箱出口直连 pc-api.uc.cn）：
- token、detail、download 全通（测试链接 drive.uc.cn/s/84110e68cae54，
  短剧合集，逐层钻目录取到 62.mp4，4MB）。
- 直链回源必须带 file/download 响应下发的 __pugs Cookie，裸 URL 回 403，
  带上立即 206 取回真实 MP4 头；UA 无关。
- 登录态取法：App 里登录 UC 后存在私有目录，规则经 Java 桥读
  UcAuthStore.getCookie() 拿到；拿不到就按游客跑。

转存与转码形态（CatVod UCApi.java 同款，curl 只验到 401 形态）：
- 建/找 TV 目录 → sharepage/save 取 task_id → GET task 轮询最多 3 次、
  间隔 1 秒，save_as_top_fids[0] 是转存后新 fid；
- 原画直链用转存后 fid 调 file/download（fids 传单元素数组）；
- 转码用转存后 fid 调 file/v2/play，m3u8 在 video_info.url。
- 登录后全程沙箱验不了（无 UC 登录 Cookie），单测见 PanSaveApiTest，
  真机登录后待验。

注意：detail 列表里文件 token 的字段名是 share_fid_token，不是 fid_token。
另注意：HDmoli 当前 60 页详情里没有 UC 链接，UC 货源要另找；
分发只认 drive.uc.cn/s/ 链接。
"""
import re
import sys
import urllib.parse

sys.path.append('..')
from base.spider import Spider


class Spider(Spider):
    def getName(self):
        return "UC网盘"

    def init(self, extend=""):
        self.ua = ("Mozilla/5.0 (Linux; Android 8.0.0; SM-G955U Build/R16NW) "
                   "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 "
                   "Mobile Safari/537.36")
        self.api = "https://pc-api.uc.cn/1/clouddrive"
        self.pr = "pr=UCBrowser&fr=pc"
        self.referer = "https://drive.uc.cn/"
        self._cookie = None
        self._video_exts = (".mp4", ".mkv", ".mov", ".avi", ".flv", ".wmv",
                            ".m4v", ".ts", ".rmvb", ".webm")
        # 游客直取约 50MB 封顶（与夸克同机制）；大于它且已登录时优先走转存。
        self._large_size = 50 * 1024 * 1024

    # ---------------- 登录态 ----------------

    def _load_cookie(self):
        if self._cookie is not None:
            return self._cookie
        self._cookie = ""
        try:
            from com.movieshell.data.source_mgr import UcAuthStore
            self._cookie = UcAuthStore.getCookie() or ""
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
        m = re.search(r"drive\.uc\.cn/s/([A-Za-z0-9]+)", url or "")
        if not m:
            return None, ""
        pwd_id = m.group(1)
        qs = urllib.parse.parse_qs(urllib.parse.urlparse(url).query)
        code = (qs.get("pwd") or [""])[0]
        if not code:
            m2 = re.search(r"密码[:：]?\s*([A-Za-z0-9]+)", url or "")
            code = m2.group(1) if m2 else ""
        return pwd_id, code

    def _token(self, s, pwd_id, code):
        try:
            r = s.post(self.api + "/share/sharepage/token?" + self.pr,
                       json={"pwd_id": pwd_id, "passcode": code or ""}, timeout=15)
            d = r.json()
            if d.get("code") == 0:
                data = d.get("data") or {}
                return data.get("stoken") or "", data.get("title") or ""
        except Exception:
            pass
        return "", ""

    def _detail(self, s, pwd_id, stoken, pdir_fid):
        try:
            r = s.get(self.api + "/share/sharepage/detail", params={
                "pr": "UCBrowser", "fr": "pc", "pwd_id": pwd_id, "stoken": stoken,
                "pdir_fid": pdir_fid, "_page": "1", "_size": "50",
                "_sort": "file_type:asc,updated_at:desc",
            }, timeout=15)
            d = r.json()
            if d.get("code") == 0:
                return (d.get("data") or {}).get("list") or []
        except Exception:
            pass
        return []

    def _list_share(self, share_url):
        """返回 (标题, 视频文件列表)。多层目录自动收集：根目录起，最多进 24 个
        子目录、最深 4 层、最多 200 个视频；集名带上子目录前缀以便区分。"""
        pwd_id, code = self._parse_share(share_url)
        if not pwd_id:
            return "", [], "", ""
        s = self._session()
        stoken, title = self._token(s, pwd_id, code)
        if not stoken:
            return "", [], "", ""
        all_files = []
        queue = [("0", "", 0)]
        visited_dirs = 0
        while queue and len(all_files) < 200:
            pdir_fid, prefix, depth = queue.pop(0)
            items = self._detail(s, pwd_id, stoken, pdir_fid)
            for f in items:
                if f.get("dir"):
                    if depth < 4 and visited_dirs < 24:
                        queue.append((f.get("fid") or "",
                                      prefix + str(f.get("file_name") or "") + "/",
                                      depth + 1))
                        visited_dirs += 1
                else:
                    if prefix:
                        f = dict(f)
                        f["file_name"] = prefix + str(f.get("file_name") or "")
                    all_files.append(f)
        videos = [f for f in all_files
                  if f.get("file_type") == 1 or
                  str(f.get("file_name") or "").lower().endswith(self._video_exts)]
        if not videos:
            videos = all_files
        if not title and all_files:
            title = str(all_files[0].get("file_name") or "")
        return title, videos, pwd_id, stoken

    # ---------------- 取直链 ----------------

    def _download(self, s, pwd_id, stoken, fid, fid_token):
        """返回 (直链, 游客 pugs, 错误码, 错误信息)。直链为空表示失败。"""
        try:
            r = s.post(self.api + "/file/download?" + self.pr, json={
                "fids": [fid], "fids_token": [fid_token or ""],
                "pwd_id": pwd_id, "stoken": stoken,
                "speedup_session": "", "token": "",
            }, timeout=15)
            d = r.json()
            pugs = ""
            try:
                pugs = r.cookies.get("__pugs") or ""
            except Exception:
                pugs = ""
            if d.get("code") == 0:
                data = d.get("data") or []
                if data:
                    return data[0].get("download_url") or "", pugs, 0, ""
            return "", pugs, d.get("code") or -1, str(d.get("message") or "")
        except Exception as e:
            return "", "", -1, str(e)

    def _save_flow(self, pwd_id, stoken, fid, fid_token):
        """转存原画再取直链；转存成功但直取失败时再试转码。返回 (直链, 回源 Cookie)。"""
        try:
            from com.movieshell.data.source_mgr import PanSaveApi
            saved = PanSaveApi.saveAndDownload(
                self._load_cookie(), self.api, self.pr, self.ua, self.referer,
                pwd_id, stoken, fid, fid_token, True)
            url = saved.optString("url") or ""
            cookie = saved.optString("cookie") or ""
            if url:
                return url, cookie
            saved_fid = saved.optString("saved_fid") or ""
            if not saved_fid:
                return "", ""
            play = PanSaveApi.transcode(
                self._load_cookie(), self.api, self.pr, self.ua, self.referer, saved_fid)
            return play.optString("url") or "", ""
        except Exception:
            return "", ""

    def _resolve_file(self, share_url, want_fid=""):
        title, videos, pwd_id, stoken = self._list_share(share_url)
        if not videos:
            return None, "", "", ""
        pick = None
        if want_fid:
            for f in videos:
                if str(f.get("fid")) == want_fid:
                    pick = f
                    break
        if pick is None:
            pick = max(videos, key=lambda f: int(f.get("size") or 0))
        return pick, pwd_id, stoken, title

    def _ok(self, url, pugs):
        headers = {"Referer": self.referer, "User-Agent": self.ua}
        if pugs:
            headers["Cookie"] = "__pugs=" + pugs
        return {"parse": 0, "url": url, "header": headers}

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
            title, videos, _pwd_id, _stoken = got
            if not videos:
                continue
            eps = []
            for f in videos:
                eps.append("%s$%s@@%s" % (f.get("file_name") or "视频",
                                          share_url, f.get("fid") or ""))
            out.append({
                "vod_id": share_url,
                "vod_name": title or "UC 分享",
                "vod_pic": "",
                "vod_remarks": "UC网盘",
                "vod_play_from": "UC网盘",
                "vod_play_url": "#".join(eps),
            })
        return {"list": out}

    def searchContent(self, key, quick=False, pg="1"):
        key = str(key or "").strip()
        pwd_id, _code = self._parse_share(key)
        if not pwd_id:
            return {"list": []}
        s = self._session()
        _stoken, title = self._token(s, pwd_id, _code)
        return {"list": [{
            "vod_id": key,
            "vod_name": title or "UC 分享",
            "vod_pic": "",
            "vod_remarks": "UC网盘",
        }]}

    def playerContent(self, flag, id, vipFlags):
        raw = str(id or "").strip()
        if "@@" in raw:
            share_url, want_fid = raw.split("@@", 1)
        else:
            share_url, want_fid = raw, ""
        pick, pwd_id, stoken, _title = self._resolve_file(share_url, want_fid)
        if pick is None:
            return {"parse": 0, "url": ""}
        fid = str(pick.get("fid") or "")
        fid_token = pick.get("share_fid_token") or pick.get("fid_token") or ""
        size = int(pick.get("size") or 0)
        cookie = self._load_cookie()
        # 大文件且已登录：优先转存原画（游客直取约 50MB 封顶，直试必回 23018）。
        if cookie and size > self._large_size:
            url, pugs = self._save_flow(pwd_id, stoken, fid, fid_token)
            if url:
                return self._ok(url, pugs)
        # 先游客直取；大文件回 23018、未登录回 31001 时，有登录态再重试一次。
        s = self._session()
        url, pugs, code, _msg = self._download(s, pwd_id, stoken, fid, fid_token)
        if not url and code in (23018, 31001) and cookie:
            s2 = self._session(with_cookie=True)
            url, pugs, code, _msg = self._download(s2, pwd_id, stoken, fid, fid_token)
        # 直取全败且已登录：转存原画，不成再试转码。
        if not url and cookie:
            url, pugs = self._save_flow(pwd_id, stoken, fid, fid_token)
        if not url:
            return {"parse": 0, "url": ""}
        return self._ok(url, pugs)
