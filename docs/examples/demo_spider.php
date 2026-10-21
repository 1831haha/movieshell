<?php
// demo_spider.php —— 侠影 PHP 演示源（继承 BaseSpider 的最小可跑源）
// 复制到手机 xiaying/php/t4_demo/ 目录。
// 依赖：同目录必须有 spider.php（BaseSpider 框架）和 HtmlParser.php，
// 本文件只 require spider.php，spider.php 自己会 require HtmlParser.php。
// 零端口方向：PHP 不再走常驻服务（原 29578 服务访问方式作废），v0.151.0 起
// 走 CLI 引擎执行：有请求才跑 php，跑完退出；本文件可直接放到 t4_demo 目录真跑。
// 查询约定（CLI 引擎到来后同样按此约定传参）：
//   首页：  demo_spider.php（不带参数）
//   分类：  demo_spider.php?ac=videolist&t=1&pg=1
//   详情：  demo_spider.php?ac=detail&ids=demo-1
//   搜索：  demo_spider.php?wd=演示
//   播放：  demo_spider.php?ac=play&flag=演示&id=demo-1
// 注意：详情和分类要求 ac 参数非空；不带任何参数时走首页。
// 演示数据说明：两条影片是仿 PyT4-demo 的演示数据；播放地址是公开测试片源，
// 仅用于验证播放链路能通，写真实源时把这里换成解析出的直链。

require_once __DIR__ . '/spider.php';

class DemoSpider extends BaseSpider {

    // 演示影片表（真实源里这里应换成抓站或调接口的结果）
    private $demos = [
        [
            'vod_id' => 'demo-1',
            'vod_name' => '演示影片一',
            'vod_pic' => '',
            'vod_remarks' => 'HD·演示',
            'type_id' => '1',
            'type_name' => '电影',
        ],
        [
            'vod_id' => 'demo-2',
            'vod_name' => '演示影片二',
            'vod_pic' => '',
            'vod_remarks' => '更新至2集',
            'type_id' => '2',
            'type_name' => '剧集',
        ],
    ];

    public function homeContent($filter) {
        return ['class' => [
            ['type_id' => '1', 'type_name' => '电影'],
            ['type_id' => '2', 'type_name' => '剧集'],
        ]];
    }

    public function homeVideoContent() {
        return ['list' => $this->demos];
    }

    public function categoryContent($tid, $pg = 1, $filter = [], $extend = []) {
        $list = [];
        foreach ($this->demos as $item) {
            if ((string)$item['type_id'] === (string)$tid) {
                $list[] = $item;
            }
        }
        return $this->pageResult($list, $pg);
    }

    public function detailContent($ids) {
        $list = [];
        foreach ($this->demos as $item) {
            if (in_array($item['vod_id'], $ids, true)) {
                $item['vod_play_from'] = '演示';
                // 公开测试片源，仅用于验证播放链路能通
                $item['vod_play_url'] = '演示$https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4';
                $list[] = $item;
            }
        }
        return ['list' => $list];
    }

    public function searchContent($key, $quick = false, $pg = 1) {
        $list = [];
        foreach ($this->demos as $item) {
            if (stripos($item['vod_name'], $key) !== false) {
                $list[] = $item;
            }
        }
        return ['list' => $list];
    }

    public function playerContent($flag, $id, $vipFlags = []) {
        // $id 就是详情里给的播放地址原值，直接透传，parse 0 表示直链不解析
        return ['parse' => 0, 'url' => $id, 'header' => []];
    }
}

$spider = new DemoSpider();
$spider->run();
