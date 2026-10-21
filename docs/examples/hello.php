<?php
// hello.php —— 侠影 PHP 运行时探针
// 复制到手机 xiaying/php/t4_demo/ 目录。
// 它不是站点源（没有继承 BaseSpider），只是验证本机 PHP 能跑起来。
// 零端口方向：PHP 不再走常驻服务，执行等 Phase 3 的 CLI 引擎（有请求才跑 php，
// 跑完退出）；当前版本不执行 PHP，放在这里仅作占位与写法参考。
// 不要把它当源挂进配置；真正的源写法见同目录的 demo_spider.php。
echo "Hello from 侠影 PHP!\n";
echo "PHP 版本：" . PHP_VERSION . "\n";
for ($i = 1; $i <= 3; $i++) {
    echo "tick $i\n";
}
