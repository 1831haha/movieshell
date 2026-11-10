# -*- coding: utf-8 -*-
"""
迅雷云盘分享解析规则（v1.1.24 新增）

只做一件事：把迅雷分享链接变成可播地址，游客可用，不登录。
链路（报告 2026-11-10 调研原文，§4.2）：
1. 先拿验证码 token：POST https://xluser-ssl.xunlei.com/v1/shield/captcha/init，
   body 含 client_id、action "get:/drive/v1/share"、device_id、
   meta{package_name "pan.xunlei.com", client_version "1.92.89",
   captcha_sign, timestamp, user_id "0"}；
   captcha_sign 是本地 MD5 链式签名（hashlib.md5 纯本地算，不依赖登录）。
2. 列目录：根目录 GET https://api-pan.xunlei.com/drive/v1/share，
   参数 share_id、pass_code、pass_code_token、limit=100、space 为空、
   thumbnail_size=SIZE_MEDIUM、scene=NORMAL、page_token；
   子目录改调 /drive/v1/share/detail，加 parent_id；
   pass_code_token 从首次响应里取，分页靠 next_page_token。
   请求头必须带 X-Captcha-Token、X-Device-Id（本地随机 32 位 hex）、
   X-Client-Id: Xqp0kJBXWhwaTpB6、X-Client-Version: 1.92.89、
   Referer: https://pan.xunlei.com/。
3. 取直链：GET /drive/v1/share/file_info，
   参数 share_id、file_id、pass_code、pass_code_token、folder_id、
   space 为空、scene=NORMAL，
   返回 web_content_link、download_url、play_url，视频优先取 play_url。

边界（写死在规则里，不越界）：
- 普通文件无直链时走转存分支，转存须登录 Bearer token；
  PY 规则内不硬编码任何 token，无直链文件在 playerContent 里
  明确返回“需登录后转存”状态，走 App 侧 v1.1.23 已有的
  PanSaveApi 模式（save、轮询 task、自己盘取直链），由 App 侧实现，
  本规则只报状态、不替 App 登录转存。

待真机用真实分享链接验证（未验证，不可当已验证）：
- captcha_sign 的盐值与链式拼接顺序：报告只写明是本地 MD5 链式签名，
  未给出盐值与拼接顺序，本规则按 client_id、device_id、时间戳、
  action、版本号的链式 MD5 实现，须拿真分享验证，失败时服务端
  会报 captcha_invalid。
- 游客拿到的 play_url 是否真不限速、能直接播。
- 直链时效：短时效、用时现取，本规则不缓存直链。
"""
import hashlib
import random
import re
import sys
import time
import urllib.parse

sys.path.append('..')
from base.spider import Spider


class Spider(Spider):
    def getName(self):
        return "迅雷云盘"

    def init(self, extend=""):
        self.ua = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                   "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        self.api = "https://api-pan.xunlei.com"
        self.captcha_api = "https://xluser-ssl.xunlei.com"
        self.client_id = "Xqp0kJBXWhwaTpB6"
        self.client_version = "1.92.89"
        self.referer = "https://pan.xunlei.com/"
        self._video_exts = (".mp4", ".mkv", ".mov", ".avi", ".flv", ".wmv",
                            ".m4v", ".ts", ".rmvb", ".webm")
        self._device_id = ""
        self._captcha_token = ""

    # ---------------- 会话与分享解析 ----------------

    def _device(self):
        if not self._device_id:
            self._device_id = "".join(
                random.choice("0123456789abcdef") for _ in range(32))
        return self._device_id

    def _headers(self):
        return {
            "User-Agent": self.ua,
            "Referer": self.referer,
            "Accept": "application/json",
            "X-Captcha-Token": self._captcha_token,
            "X-Device-Id": self._device(),
            "X-Client-Id": self.client_id,
            "X-Client-Version": self.client_version,
        }

    def _session(self):
        import requests
        s = requests.Session()
        s.headers.update({
            "User-Agent": self.ua,
            "Referer": self.referer,
            "Accept": "application/json",
        })
        return s

    def _parse_share(self, url):
        url = url or ""
        m = re.search(r"pan\.xunlei\.com/s/([A-Za-z0-9_-]+)", url)
        if not m:
            return None, ""
        share_id = m.group(1)
        qs = urllib.parse.parse_qs(urllib.parse.urlparse(url).query)
        code = (qs.get("pwd") or [""])[0]
        if not code:
            m2 = re.search(r"密码[:：]?\s*([A-Za-z0-9]+)", url)
            code = m2.group(1) if m2 else ""
        return share_id, code

    # ---------------- 验证码 token ----------------

    def _captcha_sign(self, device_id, timestamp):
        """本地 MD5 链式签名。盐值与拼接顺序报告未给出，按常规链式
        拼接实现，须拿真分享验证，失败时服务端报 captcha_invalid。"""
        first = hashlib.md5(
            ("%s%s%s" % (self.client_id, device_id, timestamp)).encode()
        ).hexdigest()
        return hashlib.md5(
            ("%s%s%s" % (first, "get:/drive/v1/share",
                         self.client_version)).encode()
        ).hexdigest()

    def _get_captcha_token(self, s):
        if self._captcha_token:
            return self._captcha_token
        try:
            device_id = self._device()
            timestamp = str(int(time.time() * 1000))
            r = s.post(self.captcha_api + "/v1/shield/captcha/init",
                       json={
                           "client_id": self.client_id,
                           "action": "get:/drive/v1/share",
                           "device_id": device_id,
                           "meta": {
                               "package_name": "pan.xunlei.com",
                               "client_version": self.client_version,
                               "captcha_sign": self._captcha_sign(
                                   device_id, timestamp),
                               "timestamp": timestamp,
                               "user_id": "0",
                           },
                       }, timeout=15)
            d = r.json()
            data = d.get("data") or d
            token = (data.get("captcha_token") or d.get("captcha_token")
                     or data.get("token") or "")
            if token:
                self._captcha_token = token
            return self._captcha_token
        except Exception:
            return ""

    # ---------------- 列目录 ----------------

    def _list_page(self, s, share_id, code, pass_code_token,
                   parent_id="", page_token=""):
        """返回 (文件列表, 下一页 token, 新 pass_code_token)。"""
        params = {
            "share_id": share_id,
            "pass_code": code or "",
            "pass_code_token": pass_code_token or "",
            "limit": "100",
            "space": "",
            "thumbnail_size": "SIZE_MEDIUM",
            "scene": "NORMAL",
            "page_token": page_token or "",
        }
        if parent_id:
            path = "/drive/v1/share/detail"
            params["parent_id"] = parent_id
        else:
            path = "/drive/v1/share"
        try:
            r = s.get(self.api + path, params=params,
                      headers=self._headers(), timeout=15)
            d = r.json()
            files = d.get("files") or []
            nxt = d.get("next_page_token") or ""
            token = (d.get("pass_code_token") or pass_code_token or "")
            return files, nxt, token
        except Exception:
            return [], "", pass_code_token

    def _is_dir(self, f):
        kind = str(f.get("kind") or "")
        if kind:
            return "folder" in kind
        if f.get("is_dir") is not None:
            return bool(f.get("is_dir"))
        return str(f.get("type") or "") == "folder"

    def _list_share(self, share_url):
        """返回 (标题, 视频文件列表, 提取码, pass_code_token)。
        多层目录自动收集：根目录起，最多进 24 个子目录、最深 4 层、
        最多 200 个视频；集名带上子目录前缀以便区分。"""
        share_id, code = self._parse_share(share_url)
        if not share_id:
            return "", [], "", ""
        s = self._session()
        if not self._get_captcha_token(s):
            return "", [], "", ""
        all_files = []
        queue = [("", "", 0)]
        visited_dirs = 0
        pass_code_token = ""
        while queue and len(all_files) < 200:
            parent_id, prefix, depth = queue.pop(0)
            page_token = ""
            while True:
                items, page_token, pass_code_token = self._list_page(
                    s, share_id, code, pass_code_token, parent_id, page_token)
                for f in items:
                    name = str(f.get("name") or "")
                    if self._is_dir(f):
                        if depth < 4 and visited_dirs < 24:
                            queue.append((f.get("id") or "",
                                          prefix + name + "/",
                                          depth + 1))
                            visited_dirs += 1
                    else:
                        if prefix:
                            f = dict(f)
                            f["name"] = prefix + name
                            f["_folder_id"] = parent_id
                        else:
                            f["_folder_id"] = ""
                        all_files.append(f)
                if not page_token or len(all_files) >= 200:
                    break
        videos = [f for f in all_files
                  if str(f.get("name") or "").lower().endswith(self._video_exts)]
        if not videos:
            videos = all_files
        title = ""
        if videos:
            title = str(videos[0].get("name") or "")
        return title, videos, code, pass_code_token

    # ---------------- 取直链 ----------------

    def _file_info(self, s, share_id, code, pass_code_token,
                   file_id, folder_id):
        """返回 (play_url, download_url, web_content_link)，全空表示无直链。"""
        try:
            r = s.get(self.api + "/drive/v1/share/file_info", params={
                "share_id": share_id,
                "file_id": file_id,
                "pass_code": code or "",
                "pass_code_token": pass_code_token or "",
                "folder_id": folder_id or "",
                "space": "",
                "scene": "NORMAL",
            }, headers=self._headers(), timeout=15)
            d = r.json()
            return (d.get("play_url") or "",
                    d.get("download_url") or "",
                    d.get("web_content_link") or "")
        except Exception:
            return "", "", ""

    def _resolve_file(self, share_url, want_fid=""):
        title, videos, code, token = self._list_share(share_url)
        if not videos:
            return None, "", "", ""
        pick = None
        if want_fid:
            for f in videos:
                if str(f.get("id")) == want_fid:
                    pick = f
                    break
        if pick is None:
            pick = max(videos, key=lambda f: int(f.get("size") or 0))
        return pick, title, code, token

    # ---------------- TVBox 五方法 ----------------

    def homeContent(self, filter=True):
        return {"class": [{
            "type_id": "share",
            "type_name": "迅雷云盘分享：粘贴分享链接后搜索",
        }], "filters": {}}

    def categoryContent(self, tid, pg=1, filter=True, extend=None):
        return {"page": 1, "pagecount": 1, "limit": 30, "total": 0, "list": []}

    def detailContent(self, ids):
        out = []
        for share_url in ids:
            share_url = str(share_url or "").strip()
            if not share_url:
                continue
            title, videos, _code, _token = self._list_share(share_url)
            if not videos:
                continue
            eps = []
            for f in videos:
                eps.append("%s$%s@@%s" % (f.get("name") or "视频",
                                          share_url, f.get("id") or ""))
            out.append({
                "vod_id": share_url,
                "vod_name": title or "迅雷云盘分享",
                "vod_pic": "",
                "vod_remarks": "迅雷云盘",
                "vod_play_from": "迅雷云盘",
                "vod_play_url": "#".join(eps),
            })
        return {"list": out}

    def searchContent(self, key, quick=False, pg="1"):
        key = str(key or "").strip()
        share_id, _code = self._parse_share(key)
        if not share_id:
            return {"list": []}
        s = self._session()
        if not self._get_captcha_token(s):
            return {"list": []}
        return {"list": [{
            "vod_id": key,
            "vod_name": "迅雷云盘分享",
            "vod_pic": "",
            "vod_remarks": "迅雷云盘",
        }]}

    def playerContent(self, flag, id, vipFlags):
        raw = str(id or "").strip()
        if "@@" in raw:
            share_url, want_fid = raw.split("@@", 1)
        else:
            share_url, want_fid = raw, ""
        share_id, code = self._parse_share(share_url)
        if not share_id:
            return {"parse": 0, "url": ""}
        pick, _title, _code, pass_code_token = self._resolve_file(
            share_url, want_fid)
        if pick is None:
            return {"parse": 0, "url": ""}
        file_id = str(pick.get("id") or "")
        if not file_id:
            return {"parse": 0, "url": ""}
        s = self._session()
        if not self._get_captcha_token(s):
            return {"parse": 0, "url": ""}
        play_url, download_url, web_link = self._file_info(
            s, share_id, code, pass_code_token, file_id,
            pick.get("_folder_id") or "")
        url = play_url or download_url or web_link
        if not url:
            # 无游客直链：转存须登录，本规则不硬编码 token，
            # 只报状态，走 App 侧 v1.1.23 已有的 PanSaveApi 模式。
            return {"parse": 0, "url": "",
                    "msg": "需登录后转存：该文件无游客直链，"
                           "请在 App 内登录迅雷后转存再播"}
        return {"parse": 0, "url": url, "header": {
            "Referer": self.referer,
            "User-Agent": self.ua,
        }}
