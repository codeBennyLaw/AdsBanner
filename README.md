# AddBanner

安卓端的开屏广告跳过与误触跳转拦截工具。开启无障碍服务后，应用开屏时自动点掉「跳过」，在别的应用里误触广告被强行拉起淘宝、京东这类购物 App 时立刻退回原应用，并按应用记录拦截次数。

全程离线运行，不联网、不申请网络权限，统计数据只写在本机的 SharedPreferences 里。

## 功能在哪

Android 主代码在 [`app/src/main/java/com/addbanner/clean/`](app/src/main/java/com/addbanner/clean/)，想顺着某个功能往下看，从下表对应的文件进。

| 想找的功能 | 代码入口 | 具体做什么 |
| --- | --- | --- |
| 启动与配置初始化 | [`AddBannerApp.kt`](app/src/main/java/com/addbanner/clean/AddBannerApp.kt)、[`core/Prefs.kt`](app/src/main/java/com/addbanner/clean/core/Prefs.kt) | 进程启动时初始化配置与统计；配置用 SharedPreferences 同步读取，保证无障碍服务在任意时刻都能拿到开关状态。默认跳转名单也在这里写入。 |
| 开屏广告跳过 | [`service/AdSkipAccessibilityService.kt`](app/src/main/java/com/addbanner/clean/service/AdSkipAccessibilityService.kt) | 进入某应用后 10 秒窗口内遍历该应用的可访问性节点树，找到命中规则的「跳过」控件并点击；点击成功后记一次开屏拦截。 |
| 跳过按钮的文案与位置判定 | [`core/SkipRule.kt`](app/src/main/java/com/addbanner/clean/core/SkipRule.kt)、`AdSkipAccessibilityService.scoreOf()` | 文案分强/弱两级，弱匹配只在屏幕右上角才点；`scoreOf()` 再用控件尺寸和屏幕象限打分，过大的控件直接放弃，避免把整块广告卡片当成按钮。 |
| 误触跳转拦截 | `AdSkipAccessibilityService.onWindowChanged()`、`blockJump()` | 记录上一次前后台窗口切换的时间，若快速切到名单内的应用、且来源不是桌面或系统界面，判定为被强行拉起，延迟 250ms 后发送返回，并确认是否仍滞留再补一次。 |
| 按应用统计 | [`core/StatsStore.kt`](app/src/main/java/com/addbanner/clean/core/StatsStore.kt)、[`ui/StatsActivity.kt`](app/src/main/java/com/addbanner/clean/ui/StatsActivity.kt) | 一个 JSON 字符串存全部数据，按包名分别记录开屏、跳转的累计与今日次数；统计页支持全部/开屏/跳转三个视图、按次数排序和清空。 |
| 跳转拦截名单 | [`ui/TargetListActivity.kt`](app/src/main/java/com/addbanner/clean/ui/TargetListActivity.kt)、[`core/AppInfoHelper.kt`](app/src/main/java/com/addbanner/clean/core/AppInfoHelper.kt) | 后台线程枚举带桌面入口的应用，支持搜索和勾选；应用名、图标、桌面包名都在 `AppInfoHelper` 里做内存缓存，避免主线程反复查 PackageManager。 |
| 主界面与权限引导 | [`ui/MainActivity.kt`](app/src/main/java/com/addbanner/clean/ui/MainActivity.kt)、[`core/AccessibilityHelper.kt`](app/src/main/java/com/addbanner/clean/core/AccessibilityHelper.kt) | 服务状态、今日/累计数字、三个开关、电池优化白名单与各厂商保活指引；无障碍状态直接读 `Settings.Secure`，比 `AccessibilityManager` 在服务刚重启时更准。 |
| 后台存活 | [`service/KeepAliveService.kt`](app/src/main/java/com/addbanner/clean/service/KeepAliveService.kt)、[`receiver/BootReceiver.kt`](app/src/main/java/com/addbanner/clean/receiver/BootReceiver.kt) | 前台常驻通知只用来提进程优先级；开机和应用更新后由广播恢复通知，无障碍服务本身由系统重新绑定，不需要自己拉起。 |
| 无障碍服务配置 | [`res/xml/accessibility_service_config.xml`](app/src/main/res/xml/accessibility_service_config.xml) | 声明监听窗口切换与内容变化两类事件，并打开 `flagIncludeNotImportantViews`、`flagRetrieveInteractiveWindows`。 |

## 权限与系统限制

| 权限 | 用途 | 能否由代码申请 |
| --- | --- | --- |
| `BIND_ACCESSIBILITY_SERVICE` | 读取其他应用界面节点、模拟点击与返回 | 不能。只能跳转设置页引导，用户手动开启 |
| `POST_NOTIFICATIONS` | Android 13+ 显示常驻通知 | 可以弹窗申请 |
| `RECEIVE_BOOT_COMPLETED` | 开机后恢复守护 | manifest 声明即可 |
| `QUERY_ALL_PACKAGES` | 按包名统计、判断跳转目标 | manifest 声明即可 |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Android 14+ 前台服务必须声明类型 | manifest 声明即可 |
| 电池优化白名单 | 避免休眠时断开 | 不能，只能跳转申请页 |

几条绕不过去的系统限制，README 里写清楚比藏起来好：

- **没有 API 能在点击落地前拦下 Intent 跳转。** 无障碍只能观察和模拟操作，所以跳转拦截一定是「先跳过去、再立刻退回来」，会有一帧闪现。
- **摇一摇触发的跳转无法阻止**，只能等它跳完之后返回。
- 被用户手动「强行停止」后所有服务失效，需要重新打开应用。
- Android 14 起，非应用商店渠道安装的应用，无障碍开关会落在「受限设置」里，需要在应用信息页点「允许受限设置」。
- 开屏广告若在 WebView 或自绘 Surface 里渲染，节点树拿不到内容，识别不到。
- 用户主动点开淘宝链接也会被当成误触，需要到名单里去掉对应应用。

## 开发时遇到的几个问题

- **只匹配「关闭/跳过」会误点正文按钮。** 开屏广告的跳过按钮几乎都在右上角，而正文里的「关闭」分散在各处。所以文案先分强弱两级：强匹配（跳过、关闭广告、skip）允许在顶部区域点，弱匹配（关闭、×）只在右上角点。再加一层尺寸过滤，宽高超过屏幕 60% / 25% 的控件直接放弃 —— 那是整块广告卡片，不是按钮。
- **有些跳过按钮不暴露 clickable。** 自定义绘制的控件 `performAction(ACTION_CLICK)` 会返回 false。先向上找最多 4 层可点击父节点，仍然失败才退化为坐标手势点击；只在强匹配时走这条路，避免手势落到广告区域触发跳转。
- **内容变化事件太密集。** 开屏动画每帧都可能发 `TYPE_WINDOW_CONTENT_CHANGED`。同一应用内两次尝试间隔至少 130ms，单次事件最多遍历 700 个节点、深度 30 层，每次进入应用累计尝试 80 次后停止，防止低端机卡顿。
- **跳转拦截会和「用户自己打开淘宝」混淆。** 从桌面或系统界面切过来的应用一律放行（`AppInfoHelper.isHomeOrSystem()` 会缓存 HOME intent 的包名集合）。只有「上一个前台是普通应用 + 间隔小于 3 秒 + 目标在名单内」才拦，剩下的误判交给用户改名单。
- **返回一次有时不够。** 有些应用会连续拉起两层页面（列表页再盖一层详情页）。所以延迟 250ms 再返回，500ms 后检查前台是否仍停在目标应用，是的话补一次；只补一次，不会一路退回去。
- **服务被清理后失效。** 无障碍服务由 system_server 绑定，进程被杀后系统通常会重新拉起并重新绑定，这比任何自建保活都可靠。没有做双进程守护、互相拉起那类方案 —— 在 Android 8+ 上基本无效，还会触发后台限制。
- **Android 12+ 后台启动前台服务会被拒。** `KeepAliveService` 的 `startForeground` 整体包在 try/catch 里，被拒时静默降级为普通通知，不影响无障碍本身。
- **Android 15 强制 edge-to-edge。** targetSdk 35 下内容会顶到状态栏下面，三个页面的根布局都用 `WindowCompat.setDecorFitsSystemWindows(false)` + `ViewCompat.setOnApplyWindowInsetsListener` 补系统栏内边距。
- **统计跨天不清零。** 每次读写前比对日期，变更时只把今日字段归零，累计数据保留。
- **包名统计要拿应用名和图标。** 无障碍回调全在主线程，PackageManager 查询较慢，`AppInfoHelper` 对应用名、图标、桌面包名分别做缓存，只在首次查询时读系统。

## 构建

需要 JDK 17 和 Android SDK（compileSdk 35）。在 Windows 下从项目根目录运行：

```powershell
.\gradlew.bat clean assembleDebug assembleRelease
```

产物文件名随版本号变化，规则在 [`app/build.gradle.kts`](app/build.gradle.kts)：

```
app/build/outputs/apk/debug/AddBanner-debug-v-1-0-0.apk
app/build/outputs/apk/release/AddBanner-release-v-1-0-0.apk
```

Release 同样使用本机的 debug keystore 签名，方便直接安装；覆盖安装时新旧 APK 的签名必须一致。

## 使用

1. 安装后打开 AddBanner，点「去开启」进入系统无障碍设置，开启「AddBanner · 广告拦截服务」。
2. 回到应用，状态变绿即生效。Android 14+ 若提示受限设置，需先在应用信息页允许。
3. 按需到「跳转拦截名单」勾选要拦的应用，默认已包含主流电商、短视频和浏览器。
4. 想让它在清理后台后依然存活，点「加入电池优化白名单」，并参照应用内的国产 ROM 指引放行自启动与后台运行。
