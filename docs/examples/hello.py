# -*- coding: utf-8 -*-
# hello.py —— 侠影 Chaquopy（Python）规则示例
# 复制到手机 xiaying/py/ 目录，App 内换源选它即可看到演示数据。
# 类名必须以 Spider 结尾（桥按“模块内定义的 Spider 子类”实例化）。
# App 已预装：requests、beautifulsoup4、pyquery、pycryptodome、lxml、Pillow、
# urllib3、chardet、idna、certifi；市场 py-deps 只补 brotli + charset_normalizer。
# 演示数据说明：首页抓 httpbin.org 的公开测试页面；播放地址是公开测试片源，
# 写真实源时把详情里的播放地址换成解析出的直链。
import sys
import json

sys.path.append('..')
from base.spider import Spider

import requests
from bs4 import BeautifulSoup
from Crypto.Hash import MD5


class HelloSpider(Spider):

    def getName(self):
        return "Hello"

    def init(self, extend=""):
        self.host = "https://httpbin.org"
        self.headers = {
            "User-Agent": ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                           "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"),
        }

    def homeContent(self, filter):
        return {"class": [{"type_id": "demo", "type_name": "演示"}]}

    def homeVideoContent(self):
        try:
            resp = requests.get(self.host + "/html", headers=self.headers, timeout=10)
            soup = BeautifulSoup(resp.text, "lxml")
            h1 = soup.h1.get_text(strip=True) if soup.h1 else "无标题"
        except Exception:
            h1 = "请求失败（网络不通时返回空列表）"
            return {"list": [{"vod_id": "hello-err", "vod_name": h1,
                              "vod_pic": "", "vod_remarks": ""}]}
        md5 = MD5.new(b"xying").hexdigest()
        return {"list": [
            {"vod_id": "hello-1", "vod_name": "Hello Python：" + h1,
             "vod_pic": "", "vod_remarks": "md5(xying)=" + md5},
        ]}

    def categoryContent(self, tid, pg, filter, extend):
        return {"list": [{"vod_id": "hello-1", "vod_name": "Hello Python 演示片",
                          "vod_pic": "", "vod_remarks": "分类：" + str(tid)}],
                "page": pg, "pagecount": 1, "limit": 20, "total": 1}

    def detailContent(self, ids):
        return {"list": [{
            "vod_id": ids[0] if ids else "hello-1",
            "vod_name": "Hello Python 演示片",
            "vod_pic": "",
            "vod_play_from": "演示",
            # 公开测试片源，仅用于验证播放链路能通
            "vod_play_url": ("演示$https://commondatastorage.googleapis.com/"
                             "gtv-videos-bucket/sample/BigBuckBunny.mp4"),
        }]}

    def searchContent(self, key, quick, pg):
        return {"list": [{"vod_id": "hello-search", "vod_name": "你搜了：" + key,
                          "vod_pic": "", "vod_remarks": ""}]}

    def playerContent(self, flag, id, vipFlags):
        # id 就是详情里给的播放地址原值，直接透传，parse 0 表示直链不解析
        return {"parse": 0, "url": id, "header": {}}
