# -*- coding: utf-8 -*-
"""
夸克网盘分享解析规则（v1.1.23 内置）

只做一件事：把夸克分享链接变成可播直链。
链路：分享链接取 stoken，再列文件，最后取直链（先直取，不转存原画）。

已实测（2026-11-10 沙箱出口直连 drive-pc.quark.cn）：
- token、detail 均通；file/download 用 share_fid_token 取链通。
- 游客直取只放行约 50MB 以内的小文件，大文件回 23018，必须登录。
- 直链域名 dl-guest-zb-u.drive.quark.cn，HEAD 200，支持 Range 拖动。
- 登录态取法：App 里登录夸克后存在私有目录，规则经 Java 桥读
  QuarkAuthStore.getCookie() 拿到；拿不到就按游客跑。

注意：detail 列表里文件 token 的字段名是 share_fid_token，不是 fid_token，
后者是空的；照旧写法会一直报 41020 token 校验异常。
"""
import re
import sys
import urllib.parse

sys.path.append('..')
from base.spider import Spider


class Spider(Spider):
    def getName(self):
        return "夸克网盘"

    def init(self, extend=""):
        self.ua = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                   "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        self.api = "https://drive-pc.quark.cn/1/clouddrive"
        self.referer = "https://pan.quark.cn/"
        self._cookie = None
        self._video_exts = (".mp4", ".mkv", ".mov", ".avi", ".flv", ".wmv",
                            ".m4v", ".ts", ".rmvb", ".webm")

    # ---------------- 登录态 ----------------

    def _load_cookie(self):
        if self._cookie is not None:
            return self._cookie
        self._cookie = ""
        try:
            from com.movieshell.data.source_mgr import QuarkAuthStore
            self._cookie = QuarkAuthStore.getCookie() or ""
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
        m = re.search(r"pan\.quark\.cn/s/([A-Za-z0-9]+)", url or "")
        if not m:
            m = re.search(r"drive\.quark\.cn/s/([A-Za-z0-9]+)", url or "")
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
            r = s.post(self.api + "/share/sharepage/token?pr=ucpro&fr=pc",
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
                "pr": "ucpro", "fr": "pc", "pwd_id": pwd_id, "stoken": stoken,
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
        """返回 (标题, 视频文件列表)。单文件夹分享自动钻一层。"""
        pwd_id, code = self._parse_share(share_url)
        if not pwd_id:
            return "", [], "", ""
        s = self._session()
        stoken, title = self._token(s, pwd_id, code)
        if not stoken:
            return "", [], "", ""
        items = self._detail(s, pwd_id, stoken, "0")
        files = [f for f in items if not f.get("dir")]
        dirs = [f for f in items if f.get("dir")]
        if not files and len(dirs) == 1:
            items = self._detail(s, pwd_id, stoken, dirs[0].get("fid") or "")
            files = [f for f in items if not f.get("dir")]
        videos = [f for f in files
                  if f.get("file_type") == 1 or
                  str(f.get("file_name") or "").lower().endswith(self._video_exts)]
        if not videos:
            videos = files
        if not title and items:
            title = str(items[0].get("file_name") or "")
        return title, videos, pwd_id, stoken

    # ---------------- 取直链 ----------------

    def _download(self, s, pwd_id, stoken, fid, fid_token):
        """返回 (直链, 游客 pugs, 错误码, 错误信息)。直链为空表示失败。"""
        try:
            r = s.post(self.api + "/file/download?pr=ucpro&fr=pc", json={
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
                "vod_name": title or "夸克分享",
                "vod_pic": "",
                "vod_remarks": "夸克网盘",
                "vod_play_from": "夸克网盘",
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
            "vod_name": title or "夸克分享",
            "vod_pic": "",
            "vod_remarks": "夸克网盘",
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
        # 先游客直取；大文件回 23018、未登录回 31001 时，有登录态再重试一次。
        s = self._session()
        url, pugs, code, _msg = self._download(s, pwd_id, stoken, fid, fid_token)
        if not url and code in (23018, 31001) and self._load_cookie():
            s2 = self._session(with_cookie=True)
            url, pugs, code, _msg = self._download(s2, pwd_id, stoken, fid, fid_token)
        if not url:
            return {"parse": 0, "url": ""}
        headers = {"Referer": self.referer, "User-Agent": self.ua}
        if pugs:
            headers["Cookie"] = "__pugs=" + pugs
        return {"parse": 0, "url": url, "header": headers}
