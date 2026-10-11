# -*- coding: utf-8 -*-
"""
BTSOW 磁力搜索 PY 爬虫
站点: https://btsow.live (btsow.com 已跳转占位页)
接口(站内 JSON,不解析 HTML):
  搜索: POST /bts/data/api/search   body: [{"search": "关键词"}, 30, 页码]
  详情: POST /bts/data/api/magnet   body: ["40位HASH"]
POST 必须带: X-CSRF-Token(首页 <meta name="csrf-token">) +
             X-Requested-With: XMLHttpRequest + 浏览器 UA(无 UA 会被拦空返回)
播放: 磁力直链,playerContent 返回 parse 0(壳子需支持磁力)
"""
import re
import json
import sys
import urllib.parse

sys.path.append('..')
from base.spider import Spider


class Spider(Spider):
    def getName(self):
        return "BTSOW"

    def init(self, extend=""):
        self.host = "https://btsow.live"
        self.ua = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                   "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        self.headers = {
            "User-Agent": self.ua,
            "Referer": self.host + "/",
            "Origin": self.host,
        }
        self._csrf = ""
        # BTSOW 是纯磁力搜索站,没有真实分类,分类即关键词搜索
        self.classes = [
            {"type_id": "电影", "type_name": "电影"},
            {"type_id": "电视剧", "type_name": "电视剧"},
            {"type_id": "动漫", "type_name": "动漫"},
            {"type_id": "综艺", "type_name": "综艺"},
            {"type_id": "纪录片", "type_name": "纪录片"},
            {"type_id": "4K", "type_name": "4K"},
            {"type_id": "无损音乐", "type_name": "无损音乐"},
        ]

    # ---------------- 内部工具 ----------------
    def _csrf_token(self):
        if self._csrf:
            return self._csrf
        try:
            import requests
            r = requests.get(self.host + "/", headers={"User-Agent": self.ua}, timeout=10)
            m = re.search(r'<meta\s+name=["\']csrf-token["\']\s+content=["\']([^"\']+)["\']',
                          r.text, re.I)
            if m:
                self._csrf = m.group(1)
        except Exception:
            pass
        return self._csrf

    def _api_post(self, path, body):
        try:
            import requests
            h = dict(self.headers)
            h.update({
                "Content-Type": "application/json",
                "X-Requested-With": "XMLHttpRequest",
                "X-CSRF-Token": self._csrf_token(),
            })
            r = requests.post(self.host + path, data=json.dumps(body), headers=h, timeout=15)
            return r.json()
        except Exception:
            return {}

    @staticmethod
    def _clean(s):
        return re.sub(r"<[^>]+>", "", str(s or "")).strip()

    @staticmethod
    def _fmt_size(b):
        try:
            b = float(b)
        except Exception:
            return ""
        if b >= 1073741824:
            return "%.2fGB" % (b / 1073741824)
        if b >= 1048576:
            return "%.2fMB" % (b / 1048576)
        if b >= 1024:
            return "%.1fKB" % (b / 1024)
        return "%dB" % int(b)

    @staticmethod
    def _fmt_date(ts):
        try:
            import datetime
            ts = int(ts)
            if not ts:
                return ""
            return datetime.datetime.fromtimestamp(ts).strftime("%Y-%m-%d")
        except Exception:
            return ""

    def _search(self, keyword, page):
        try:
            page = int(page)
        except Exception:
            page = 1
        j = self._api_post("/bts/data/api/search", [{"search": keyword}, 30, page])
        if not isinstance(j, dict) or j.get("code") != 200 or not isinstance(j.get("data"), list):
            return []
        items = []
        for e in j["data"]:
            h = str(e.get("hash", "")).strip()
            if not h:
                continue
            name = self._clean(e.get("name"))
            remark = ("%s %s" % (self._fmt_size(e.get("size")),
                                 self._fmt_date(e.get("lastUpdateTime")))).strip()
            items.append({
                "vod_id": h,
                "vod_name": name,
                "vod_pic": "",
                "vod_remarks": remark,
            })
        return items

    def _detail(self, h):
        h = str(h or "").strip().upper()
        j = self._api_post("/bts/data/api/magnet", [h])
        d = j.get("data") if isinstance(j, dict) else None
        if not isinstance(d, dict):
            d = {}
        name = self._clean(d.get("name")) or h
        files = d.get("files") if isinstance(d.get("files"), list) else []
        total = d.get("size") or 0
        ts = d.get("lastUpdateTime") or 0
        flist = "\n".join("%s  [%s]" % (f.get("filename", ""),
                                        self._fmt_size(f.get("size"))) for f in files)
        magnet = "magnet:?xt=urn:btih:" + h
        if name and name != h:
            magnet += "&dn=" + urllib.parse.quote(name)
        play_name = re.sub(r"[$#]", "", name)
        content = "共 %d 个文件" % len(files)
        if flist:
            content += "\n" + flist
        return {
            "vod_id": h,
            "vod_name": name,
            "vod_pic": "",
            "vod_remarks": self._fmt_size(total),
            "vod_year": self._fmt_date(ts)[:4],
            "vod_content": content,
            "vod_play_from": "磁力",
            "vod_play_url": "%s$%s" % (play_name, magnet),
        }

    # ---------------- 标准接口 ----------------
    def homeContent(self, filter=True):
        return {"class": self.classes, "filters": {}}

    def homeVideoContent(self):
        return {"list": self._search("电影", 1)}

    def categoryContent(self, tid, pg=1, filter=True, extend=None):
        try:
            page = int(pg)
        except Exception:
            page = 1
        keyword = str(tid or "电影")
        lst = self._search(keyword, page)
        return {
            "page": page,
            "pagecount": 9999 if len(lst) >= 30 else page,
            "limit": 30,
            "total": 999999,
            "list": lst,
        }

    def detailContent(self, ids):
        return {"list": [self._detail(i) for i in ids if str(i or "").strip()]}

    def searchContent(self, key, quick=False, pg="1"):
        try:
            page = int(pg)
        except Exception:
            page = 1
        return {"list": self._search(key, page), "page": page}

    def playerContent(self, flag, id, vipFlags):
        url = str(id or "").strip()
        return {"parse": 0, "url": url}
