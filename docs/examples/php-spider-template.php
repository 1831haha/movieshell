<?php
// php-spider-template.php —— 侠影 PHP 站点源填空模板（v0.163.0 修正版）
// ============================================================================
// 放哪里：改名后拷到手机 xiaying/php/t4_demo/ 目录，例如 mysite.php。
// 同目录必须已有框架文件 spider.php（BaseSpider）和 HtmlParser.php，
// 这两个是框架、不算源；你自己的文件千万别再叫 spider.php，叫了会被排除。
// 站源配置：key 写 php:<文件名>，例如文件是 mysite.php，key 就是 php:mysite.php。
//
// 路由是怎么跑的（写源的人不用管，但别写错）：
//   侠影不走常驻服务、没有 19978 网关。PhpCliEngine 每次有请求才跑一次 php，
//   引导文件先把查询串 parse 成 $_GET，再 include 你这个文件；
//   文件结尾的 $spider->run() 按 $_GET 分发：
//     不带参数            → homeContent + homeVideoContent（首页）
//     ac=videolist&t=&pg= → categoryContent（分类，ac 不能为空）
//     ac=detail&ids=     → detailContent（详情，ac 不能为空）
//     wd=                → searchContent（搜索）
//     ac=play&flag=&id=  → playerContent（播放）
//   所以：不要自己读 $argv 分 action，不要自己 echo JSON，run() 会 echo。
//   stdout 只准有 JSON，调试别 echo/var_dump，会把返回的 JSON 打坏。
//
// 162 补缺包那个 spider模板.php 为什么不能用：
//   ①它按 $argv[1] 分 home/category/detail/search/play，引擎根本不这么传参；
//   ②它没继承 BaseSpider、结尾没调 run()，也就不走上面这套 $_GET 路由；
//   ③它让文件放在 xiaying/php/<源名>/spider.php，真实规则目录是
//     xiaying/php/t4_demo/，且 spider.php 这个名字本身就是框架文件、会被排除。
// ============================================================================

require_once __DIR__ . '/spider.php';

class MySiteSpider extends BaseSpider
{
    // 改这里：站点域名（末尾不带斜杠）
    const HOST = 'https://example.com';

    public function homeContent($filter)
    {
        // 改这里：分类 id 和名字，按站点真实分类填
        return ['class' => [
            ['type_id' => '1', 'type_name' => '电影'],
            ['type_id' => '2', 'type_name' => '电视剧'],
        ]];
    }

    public function homeVideoContent()
    {
        // 改这里：首页推荐列表；没有推荐就返回空 list，别删这个方法
        $html = $this->fetch(self::HOST . '/');
        $items = $this->pdfa($html, '改成站点列表规则');
        $list = [];
        foreach ($items as $it) {
            $list[] = [
                'vod_id' => $this->pdfh($it, '改成 id 规则'),
                'vod_name' => $this->pdfh($it, '改成标题规则'),
                'vod_pic' => $this->pdfh($it, '改成海报规则'),
                'vod_remarks' => $this->pdfh($it, '改成备注规则'),
            ];
        }
        return ['list' => $list];
    }

    public function categoryContent($tid, $pg = 1, $filter = [], $extend = [])
    {
        // 改这里：分类地址模板按站点真实分页地址换
        $html = $this->fetch(self::HOST . "/vodshow/{$tid}--------{$pg}---.html");
        $items = $this->pdfa($html, '改成站点列表规则');
        $list = [];
        foreach ($items as $it) {
            $list[] = [
                'vod_id' => $this->pdfh($it, '改成 id 规则'),
                'vod_name' => $this->pdfh($it, '改成标题规则'),
                'vod_pic' => $this->pdfh($it, '改成海报规则'),
                'vod_remarks' => $this->pdfh($it, '改成备注规则'),
            ];
        }
        return $this->pageResult($list, $pg);
    }

    public function detailContent($ids)
    {
        $list = [];
        foreach ($ids as $id) {
            // 改这里：详情地址和选集规则按站点换
            $html = $this->fetch(self::HOST . "/voddetail/{$id}.html");
            $eps = $this->pdfa($html, '改成选集规则');
            $parts = [];
            foreach ($eps as $ep) {
                $parts[] = $this->pdfh($ep, '改成集名规则') . '$' . $this->pdfh($ep, '改成播放 id 规则');
            }
            $list[] = [
                'vod_id' => $id,
                'vod_name' => $this->pdfh($html, '改成标题规则'),
                'vod_pic' => $this->pdfh($html, '改成海报规则'),
                'vod_content' => $this->pdfh($html, '改成简介规则'),
                'vod_play_from' => '线路一',
                'vod_play_url' => implode('#', $parts),
            ];
        }
        return ['list' => $list];
    }

    public function searchContent($key, $quick = false, $pg = 1)
    {
        // 改这里：搜索地址按站点换，列表解析与 categoryContent 同一套规则
        $html = $this->fetch(self::HOST . '/vodsearch/' . urlencode($key) . "----------{$pg}---.html");
        $items = $this->pdfa($html, '改成站点列表规则');
        $list = [];
        foreach ($items as $it) {
            $list[] = [
                'vod_id' => $this->pdfh($it, '改成 id 规则'),
                'vod_name' => $this->pdfh($it, '改成标题规则'),
                'vod_pic' => $this->pdfh($it, '改成海报规则'),
                'vod_remarks' => $this->pdfh($it, '改成备注规则'),
            ];
        }
        return ['list' => $list];
    }

    public function playerContent($flag, $id, $vipFlags = [])
    {
        // 直链：parse 0，直接把 $id 当地址返回（$id 是详情里给的播放 id）。
        // 需要走解析线路时改成 ['parse' => 1, 'url' => $id, 'header' => []]。
        return ['parse' => 0, 'url' => $id, 'header' => []];
    }
}

$spider = new MySiteSpider();
$spider->run();
