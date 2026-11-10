# -*- coding: utf-8 -*-
"""
光鸭网盘分享解析规则（v1.1.24 新增）

只做一件事：把光鸭分享链接变成可播直链，游客可用，不登录。
链路（报告 2026-11-10 调研原文，§3）：
1. 取分享 token：POST https://api.guangyapan.com/userres/v1/get_share_access_token，
   body {"shareId", "code"}，返回 data.accessToken（分享级 token，无需登录；
   返回码 209 表示待验证）；
2. 列文件：POST /userres/v1/get_share_page_files_list，
   body {"accessToken", "folderPath", "orderBy", "sortType"}；
3. 取直链：POST /userres/v1/get_share_download_url，
   body {"fileId", "accessToken", "orderId"}，返回 signedURL，现取现用。

直链带 need_pay 或 pay_reason_code 时如实失败，不假装成功：
playerContent 此时返回空地址并带 msg 说明需付费，绝不返回假直链。
orderBy 与 sortType 的取值、orderId 的含义，报告未给出具体值，
本规则按常规值发送，须拿有效分享验证后修正。

待真机用真实分享链接验证（未验证，不可当已验证）：
- 有效分享端到端 1 到 3 步全程（报告只实测到过期分享的 201 与错参的 112）。
- 大文件是否触发付费分支（need_pay 的阈值未核实）。
- folderPath 根目录取值、子目录路径写法、文件列表字段名是否与本规则一致。
"""
import re
import sys
import urllib.parse

sys.path.append('..')
from base.spider import Spider


class Spider(Spider):
    def getName(self):
        return "光鸭网盘"

    def init(self, extend=""):
        self.ua = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                   "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        self.api = "https://api.guangyapan.com"
        self.referer = "https://www.guangyapan.com/"
        self._video_exts = (".mp4", ".mkv", ".mov", ".avi", ".flv", ".wmv",
                            ".m4v", ".ts", ".rmvb", ".webm")

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
        m = re.search(r"guangyapan\.com/s/([A-Za-z0-9_-]+)", url)
        if not m:
            return None, ""
        share_id = m.group(1)
        qs = urllib.parse.parse_qs(urllib.parse.urlparse(url).query)
        code = (qs.get("pwd") or qs.get("code") or [""])[0]
        if not code:
            m2 = re.search(r"密码[:：]?\s*([A-Za-z0-9]+)", url)
            code = m2.group(1) if m2 else ""
        return share_id, code

    # ---------------- 三步 POST ----------------

    def _get_access_token(self, s, share_id, code):
        """返回分享级 accessToken；209 待验证或其它失败返回空串。"""
        try:
            r = s.post(self.api + "/userres/v1/get_share_access_token",
                       json={"shareId": share_id, "code": code or ""},
                       timeout=15)
            d = r.json()
            if d.get("code") != 200:
                return ""
            data = d.get("data") or {}
            return data.get("accessToken") or ""
        except Exception:
            return ""

    def _list_files(self, s, token, folder_path):
        try:
            r = s.post(self.api + "/userres/v1/get_share_page_files_list",
                       json={"accessToken": token,
                             "folderPath": folder_path or "",
                             "orderBy": "name", "sortType": "asc"},
                       timeout=15)
            d = r.json()
            if d.get("code") != 200:
                return []
            data = d.get("data") or {}
            if isinstance(data, list):
                return data
            return (data.get("list") or data.get("files")
                    or data.get("records") or [])
        except Exception:
            return []

    def _download_url(self, s, token, file_id):
        """返回 (直链, 是否需付费, 付费原因码)。直链是签名直链，现取现用。"""
        try:
            r = s.post(self.api + "/userres/v1/get_share_download_url",
                       json={"fileId": file_id, "accessToken": token,
                             "orderId": ""},
                       timeout=15)
            d = r.json()
            data = d.get("data") or {}
            need_pay = bool(data.get("need_pay"))
            reason = str(data.get("pay_reason_code") or "")
            if need_pay:
                return "", True, reason
            url = (data.get("signedURL") or data.get("signedUrl")
                   or data.get("downloadUrl") or "")
            if d.get("code") not in (None, 200) and not url:
                return "", False, ""
            return url, False, reason
        except Exception:
            return "", False, ""

    def _is_dir(self, f):
        if f.get("isDir") is not None:
            return bool(f.get("isDir"))
        if f.get("is_dir") is not None:
            return bool(f.get("is_dir"))
        return str(f.get("type") or "") in ("folder", "dir")

    def _fname(self, f):
        return str(f.get("fileName") or f.get("file_name")
                   or f.get("name") or "")

    def _fid(self, f):
        return str(f.get("fileId") or f.get("file_id")
                   or f.get("id") or "")

    def _list_share(self, share_url):
        """返回 (标题, 视频文件列表)。多层目录自动收集：根目录起，最多进 24 个
        子目录、最深 4 层、最多 200 个视频；集名带上子目录前缀以便区分。"""
        share_id, code = self._parse_share(share_url)
        if not share_id:
            return "", []
        s = self._session()
        token = self._get_access_token(s, share_id, code)
        if not token:
            return "", []
        all_files = []
        queue = [("", "", 0)]
        visited_dirs = 0
        while queue and len(all_files) < 200:
            folder_path, prefix, depth = queue.pop(0)
            items = self._list_files(s, token, folder_path)
            for f in items:
                name = self._fname(f)
                if self._is_dir(f):
                    if depth < 4 and visited_dirs < 24:
                        sub = (folder_path.rstrip("/") + "/" + name
                               if folder_path else "/" + name)
                        queue.append((sub, prefix + name + "/",
                                      depth + 1))
                        visited_dirs += 1
                else:
                    if prefix:
                        f = dict(f)
                        f["fileName"] = prefix + name
                    all_files.append(f)
        videos = [f for f in all_files
                  if self._fname(f).lower().endswith(self._video_exts)]
        if not videos:
            videos = all_files
        title = ""
        if videos:
            title = self._fname(videos[0])
        return title, videos

    def _resolve_file(self, share_url, want_fid=""):
        title, videos = self._list_share(share_url)
        if not videos:
            return None, ""
        pick = None
        if want_fid:
            for f in videos:
                if self._fid(f) == want_fid:
                    pick = f
                    break
        if pick is None:
            pick = max(videos, key=lambda f: int(f.get("fileSize")
                                                 or f.get("size") or 0))
        return pick, title

    # ---------------- TVBox 五方法 ----------------

    def homeContent(self, filter=True):
        return {"class": [{
            "type_id": "share",
            "type_name": "光鸭网盘分享：粘贴分享链接后搜索",
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
                eps.append("%s$%s@@%s" % (self._fname(f) or "视频",
                                          share_url, self._fid(f)))
            out.append({
                "vod_id": share_url,
                "vod_name": title or "光鸭网盘分享",
                "vod_pic": "",
                "vod_remarks": "光鸭网盘",
                "vod_play_from": "光鸭网盘",
                "vod_play_url": "#".join(eps),
            })
        return {"list": out}

    def searchContent(self, key, quick=False, pg="1"):
        key = str(key or "").strip()
        share_id, code = self._parse_share(key)
        if not share_id:
            return {"list": []}
        s = self._session()
        token = self._get_access_token(s, share_id, code)
        if not token:
            return {"list": []}
        return {"list": [{
            "vod_id": key,
            "vod_name": "光鸭网盘分享",
            "vod_pic": "",
            "vod_remarks": "光鸭网盘",
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
        pick, _title = self._resolve_file(share_url, want_fid)
        if pick is None:
            return {"parse": 0, "url": ""}
        file_id = self._fid(pick)
        if not file_id:
            return {"parse": 0, "url": ""}
        s = self._session()
        token = self._get_access_token(s, share_id, code)
        if not token:
            return {"parse": 0, "url": ""}
        url, need_pay, reason = self._download_url(s, token, file_id)
        if need_pay:
            # 如实返回付费状态，不假装成功、不返回假直链。
            msg = "光鸭分享下载需付费"
            if reason:
                msg += "（" + reason + "）"
            return {"parse": 0, "url": "", "msg": msg}
        if not url:
            return {"parse": 0, "url": ""}
        return {"parse": 0, "url": url, "header": {
            "Referer": self.referer,
            "User-Agent": self.ua,
        }}
