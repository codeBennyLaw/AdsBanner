# 3.1.6 开屏窗口身份事件遗漏修复

## 对照与原因

本轮兼容回归中，哔哩哔哩数字控件和迅雷纯触摸、描述或主页控件出现间歇性漏跳。失败时活动窗口属于正确应用，扫描仍在启动预算内，但 `foregroundActivity` 为空，该窗口也没有已验证的 Activity 身份。依赖专用开屏页面的匹配因此未授权动作。

3.1.5 的迅雷整组对照也复现了同类失败。UiAutomation 的即时事件记录中，系统发送了两条对应新窗口的 `LaunchActivity` 状态事件，随后发送旧窗口的 `android.widget.FrameLayout` 状态事件；AdsBanner 仅记住此前两个窗口，新窗口的 Activity 身份缺失。

服务配置的 `notificationTimeout=25` 会合并短期内同类型的无障碍事件。新 Activity 与离场视图的状态事件可在同一批次中出现，最后的旧视图事件覆盖页面身份事件，后续仅靠窗口根节点无法恢复 Activity 类名。单场景多次复测可以正常，但不能因此将首次失败视为已修复。

## 调整

将服务配置改为 `notificationTimeout=0`，即时接收事件。引擎已经分别限制快速节点查询、整树遍历与前台复核的频率，事件送达不等于重复执行完整查询。已声明的 Activity 仍绑定到自己的窗口，离场窗口事件不能借用或覆盖新窗口的身份。

此项调整不放宽目标授权条件；修复的是处理授权所需证据在进入匹配之前丢失的问题。

## 证据位置

原始记录保存在忽略目录 `app/build/ui-review/cainiao-316`：

- `fixed-bili-chaoxing-first-failure.txt`：数字控件没有窗口身份。
- `fixed-xunlei-first-failure.txt`、`fixed-xunlei-second-failure.txt`：触摸或主页形态漏跳。
- `315-xunlei-full-identity-trace.txt`：3.1.5 对照中，系统发送了新窗口 Activity 事件，但服务未记录该窗口的身份。
- `315-xunlei-identity-1.txt` 至 `315-xunlei-identity-3.txt`：较短对照正常，说明仅靠重跑短场景不足以排除问题。

本轮模拟与公开规则复核都不等同于读取用户实体设备的实际节点。


## 修复后回归

取消事件合并后，迅雷 23 个场景通过 78 项断言，哔哩哔哩与学习通 25 个场景通过 101 项断言。通用、结果通知、连续切换、设置滚动、跳转及慢关闭确认也完成回归；稳定行为字段与 3.1.5 对齐。总表与分阶段安装包信息见[菜鸟兼容回归记录](splash-cainiao-316.md)。
