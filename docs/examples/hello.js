// hello.js —— 侠影 V8 引擎示例规则
// 复制到手机 xiaying/js/ 目录，App 内换源选它即可看到演示数据。
// 这是一个规则文件（var rule），不是裸脚本：章节函数必须是 async，
// 网络请求（request）和解析（pdfh/pdfa）返回 Promise，必须 await。
// md5、base64Decode、setResult 是同步调用，不用 await。
// 演示数据说明：首页抓 httpbin.org 的公开测试接口；播放地址是公开测试片源，
// 写真实源时把详情里的 vod_play_url 换成解析出的直链。

var rule = {
    title: 'Hello V8',
    host: 'https://httpbin.org',
    homeUrl: '/get',
    url: '/get',
    class_name: '演示',
    class_url: 'demo',

    // 推荐：演示 await request + md5 + base64Decode
    推荐: async function () {
        var body = await request('https://httpbin.org/get');
        if (!body) {
            // request 失败时 resolve 空字符串，不 reject，这里直接说明原因
            setResult([{ vod_id: 'hello-err', vod_name: '请求失败：网络不通时 request 返回空字符串',
                         vod_pic: '', vod_remarks: '' }]);
            return;
        }
        var data = JSON.parse(body || '{}');
        setResult([
            { vod_id: 'hello-1', vod_name: 'Hello V8：' + (data.url || 'ok'),
              vod_pic: '', vod_remarks: 'md5(xying)=' + md5('xying') },
            { vod_id: 'hello-2', vod_name: 'base64Decode(eHlpbmc=)=' + base64Decode('eHlpbmc='),
              vod_pic: '', vod_remarks: '同步调用不用 await' },
        ]);
    },

    // 一级：和推荐同一套演示逻辑（for 循环，不要用 forEach + await）
    一级: async function () {
        var body = await request('https://httpbin.org/get');
        var d = [];
        if (body) {
            var data = JSON.parse(body || '{}');
            d.push({ vod_id: 'hello-1', vod_name: 'Hello V8：' + (data.url || 'ok'),
                     vod_pic: '', vod_remarks: 'md5(xying)=' + md5('xying') });
        }
        setResult(d);
    },

    // 二级：详情。vod_play_url 是公开测试片源，仅用于验证播放链路能通。
    二级: async function () {
        setResult({
            vod_id: this.ID, vod_name: 'Hello V8 演示片', vod_pic: '',
            vod_play_from: '演示',
            vod_play_url: '演示$https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4',
        });
    },

    // 搜索：把关键词原样回显成一条结果，演示搜索链路
    搜索: async function () {
        setResult([{ vod_id: 'hello-search', vod_name: '你搜了：' + this.KEY,
                     vod_pic: '', vod_remarks: 'md5=' + md5(this.KEY || '') }]);
    },

    // 播放：input 是播放条目 id/URL 原值，不要对它做 detailUrl 渲染
    播放: async function () {
        setResult({ url: this.input, parse: 0 });
    },
};
