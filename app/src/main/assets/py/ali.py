# -*- coding: utf-8 -*-
"""
阿里云盘分享解析规则（v1.1.24 新增）

只做一件事：把阿里分享链接变成可播地址，游客可用，不登录。
链路（报告 2026-11-10 调研原文，§4.4）：
1. 取分享 token：POST https://api.alipan.com/v2/share_link/get_share_token，
   body {"share_id", "share_pwd"}，无提取码时 share_pwd 为空，
   官方文档明写无需鉴权，返回 share_token 与 expires_in；
2. 列文件：POST https://api.aliyundrive.com/adrive/v3/file/list，
   body {"share_id", "parent_file_id", "limit": 200}，
   请求头带 x-share-token；
3. 播视频先走转码：POST /v2/file/get_share_link_video_preview_play_info，
   body {"share_id", "file_id", "category": "live_transcoding"}，
   取 video_preview_play_info.live_transcoding_task_list 里的地址；
4. 原画直链作备选：POST /v2/file/get_share_link_download_url，
   body {"share_id", "file_id", "expire_sec": 600}，头带 x-share-token。
share_token 剩余有效期不足 600 秒时刷新。

待真机用真实分享链接验证（未验证，不可当已验证）：
- 匿名原画直链是否真能出（报告保留：py_ali 的播放分支仍先调 login，
  匿名原画直链未被该实现证实；转码 m3u8 路线相对稳）。
- require_login 的分享（分享者设成须登录查看）游客打不开，属预期失败。
- 转码清晰度与速度上限、直链实际有效期，待真链验证。
"""
import re
import sys
import time
import urllib.parse

sys.path.append('..')
from base.spider import Spider


class Spider(Spider):
    def getName(self):
        return "阿里云盘"

    def init(self, extend=""):
        self.ua = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                   "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        self.token_api = "https://api.alipan.com"
        self.api = "https://api.aliyundrive.com"
        self.referer = "https://www.alipan.com/"
        self._video_exts = (".mp4", ".mkv", ".mov", ".avi", ".flv", ".wmv",
                            ".m4v", ".ts", ".rmvb", ".webm")
        self._token = ""
        self._token_expires = 0

    # ---------------- 会话与分享解析 ----------------

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
        m = re.search(r"alipan\.com/s/([A-Za-z0-9_-]+)", url)
        if not m:
            m = re.search(r"aliyundrive\.com/s/([A-Za-z0-9_-]+)", url)
        if not m:
            return None, "", ""
        share_id = m.group(1)
        qs = urllib.parse.parse_qs(urllib.parse.urlparse(url).query)
        pwd = (qs.get("pwd") or [""])[0]
        if not pwd:
            m2 = re.search(r"密码[:：]?\s*([A-Za-z0-9]+)", url)
            pwd = m2.group(1) if m2 else ""
        folder_id = ""
        m3 = re.search(r"/folder/([A-Za-z0-9_-]+)", url)
        if m3:
            folder_id = m3.group(1)
        return share_id, pwd, folder_id

    # ---------------- 分享 token ----------------

    def _get_token(self, s, share_id, pwd):
        if self._token and self._token_expires - time.time() > 600:
            return self._token
        try:
            r = s.post(self.token_api + "/v2/share_link/get_share_token",
                       json={"share_id": share_id, "share_pwd": pwd or ""},
                       timeout=15)
            d = r.json()
            token = d.get("share_token") or ""
            if token:
                expires_in = d.get("expires_in") or 3600
                self._token = token
                self._token_expires = time.time() + int(expires_in)
                return token
        except Exception:
            pass
        return ""

    # ---------------- 列文件 ----------------

    def _list_files(self, s, token, share_id, parent_file_id):
        try:
            r = s.post(self.api + "/adrive/v3/file/list",
                       json={"share_id": share_id,
                             "parent_file_id": parent_file_id or "root",
                             "limit": 200},
                       headers={"x-share-token": token}, timeout=15)
            d = r.json()
            return (d.get("items") or [])
        except Exception:
            return []

    def _list_share(self, share_url):
        """返回 (标题, 视频文件列表)。多层目录自动收集：根目录起，最多进 24 个
        子目录、最深 4 层、最多 200 个视频；集名带上子目录前缀以便区分。"""
        share_id, pwd, folder_id = self._parse_share(share_url)
        if not share_id:
            return "", []
        s = self._session()
        token = self._get_token(s, share_id, pwd)
        if not token:
            return "", []
        all_files = []
        queue = [(folder_id or "root", "", 0)]
        visited_dirs = 0
        while queue and len(all_files) < 200:
            parent_id, prefix, depth = queue.pop(0)
            items = self._list_files(s, token, share_id, parent_id)
            for f in items:
                name = str(f.get("name") or "")
                if f.get("type") == "folder":
                    if depth < 4 and visited_dirs < 24:
                        queue.append((f.get("file_id") or "",
                                      prefix + name + "/",
                                      depth + 1))
                        visited_dirs += 1
                else:
                    if prefix:
                        f = dict(f)
                        f["name"] = prefix + name
                    all_files.append(f)
        videos = [f for f in all_files
                  if str(f.get("name") or "").lower().endswith(self._video_exts)]
        if not videos:
            videos = all_files
        title = ""
        if videos:
            title = str(videos[0].get("name") or "")
        return title, videos

    # ---------------- 取播放地址 ----------------

    def _transcode_url(self, s, token, share_id, file_id):
        """取转码 m3u8，优先 FHD，再 HD，再 SD。失败返回空串。"""
        try:
            r = s.post(self.api + "/v2/file/get_share_link_video_preview_play_info",
                       json={"share_id": share_id, "file_id": file_id,
                             "category": "live_transcoding"},
                       headers={"x-share-token": token}, timeout=15)
            d = r.json()
            info = (d.get("video_preview_play_info") or {})
            tasks = info.get("live_transcoding_task_list") or []
            best = ""
            for want in ("FHD", "HD", "SD", ""):
                for t in tasks:
                    tid = str(t.get("template_id") or "")
                    url = t.get("url") or t.get("preview_url") or ""
                    if url and (not want or want in tid):
                        return url
                    if url and not best:
                        best = url
            return best
        except Exception:
            return ""

    def _download_url(self, s, token, share_id, file_id):
        """取原画直链。匿名是否放行待真链验证，本规则只作备选。"""
        try:
            r = s.post(self.api + "/v2/file/get_share_link_download_url",
                       json={"share_id": share_id, "file_id": file_id,
                             "expire_sec": 600},
                       headers={"x-share-token": token}, timeout=15)
            d = r.json()
            return d.get("url") or ""
        except Exception:
            return ""

    def _resolve_file(self, share_url, want_fid=""):
        title, videos = self._list_share(share_url)
        if not videos:
            return None, "", ""
        pick = None
        if want_fid:
            for f in videos:
                if str(f.get("file_id")) == want_fid:
                    pick = f
                    break
        if pick is None:
            pick = max(videos, key=lambda f: int(f.get("size") or 0))
        return pick, title, videos

    # ---------------- TVBox 五方法 ----------------

    def homeContent(self, filter=True):
        return {"class": [{
            "type_id": "share",
            "type_name": "阿里云盘分享：粘贴分享链接后搜索",
        }], "filters": {}}

    def categoryContent(self, tid, pg=1, filter=True, extend=None):
        return {"page": 1, "pagecount": 1, "limit": 30, "total": 0, "list": []}

    def detailContent(self, ids):
        out = []
        for share_url in ids:
            share_url = str(share_url or "").strip()
            if not share_url:
                continue
            title, videos = self._list_share(share_url)
            if not videos:
                continue
            eps = []
            for f in videos:
                eps.append("%s$%s@@%s" % (f.get("name") or "视频",
                                          share_url, f.get("file_id") or ""))
            out.append({
                "vod_id": share_url,
                "vod_name": title or "阿里云盘分享",
                "vod_pic": "",
                "vod_remarks": "阿里云盘",
                "vod_play_from": "阿里云盘",
                "vod_play_url": "#".join(eps),
            })
        return {"list": out}

    def searchContent(self, key, quick=False, pg="1"):
        key = str(key or "").strip()
        share_id, _pwd, _folder = self._parse_share(key)
        if not share_id:
            return {"list": []}
        s = self._session()
        token = self._get_token(s, share_id, _pwd)
        if not token:
            return {"list": []}
        return {"list": [{
            "vod_id": key,
            "vod_name": "阿里云盘分享",
            "vod_pic": "",
            "vod_remarks": "阿里云盘",
        }]}

    def playerContent(self, flag, id, vipFlags):
        raw = str(id or "").strip()
        if "@@" in raw:
            share_url, want_fid = raw.split("@@", 1)
        else:
            share_url, want_fid = raw, ""
        share_id, pwd, _folder = self._parse_share(share_url)
        if not share_id:
            return {"parse": 0, "url": ""}
        pick, _title, _videos = self._resolve_file(share_url, want_fid)
        if pick is None:
            return {"parse": 0, "url": ""}
        file_id = str(pick.get("file_id") or "")
        if not file_id:
            return {"parse": 0, "url": ""}
        s = self._session()
        token = self._get_token(s, share_id, pwd)
        if not token:
            return {"parse": 0, "url": ""}
        # 先走转码路线，匿名原画直链未被实锤，只作备选。
        url = self._transcode_url(s, token, share_id, file_id)
        if not url:
            url = self._download_url(s, token, share_id, file_id)
        if not url:
            return {"parse": 0, "url": ""}
        return {"parse": 0, "url": url, "header": {
            "Referer": self.referer,
            "User-Agent": self.ua,
        }}
