# 流量记录（TrafficStats）

一个自用、单机、无后端的极简 Android 应用：读取系统整机移动网络 / WiFi 流量，显示从首次使用或上次重置以来的本期累计，可按应用查看本期移动网络 / WiFi 用量，并在本地 SQLite 中保存采集明细。界面采用 Material You 风格的浅色卡片设计，不引入 AndroidX 或第三方 UI 库。

- 包名：`com.yh.trafficstats`
- 最低系统：Android 11（API 30）
- 目标系统：Android 14（API 34）
- 技术栈：Java + 原生 Activity + 原生 SQLite + `NetworkStatsManager`
- 不申请 `INTERNET` 权限，不引入 AndroidX 或第三方依赖

> 仓库只包含源码和构建说明，不包含 APK、签名证书或密码。

## 功能

1. 首次进入检测「使用情况访问」权限，未开启时显示引导按钮。
2. 点击按钮跳转到系统「使用情况访问」设置页，授权后返回应用自动切换界面。
3. 顶部「本期累计」显示：
   - 本期起点（首次使用时间或上次重置时间）
   - 移动网络下载 / 上传总量
   - WiFi 下载 / 上传总量
4. 点击「采集流量」会刷新本期累计，并写入本次增量明细：
   - 移动网络下载 / 上传字节数
   - 整机 WiFi 下载 / 上传字节数
5. 点击「重置统计」需确认；确认后清空采集明细、累计归零，并从当前时间重新开始本期统计。
6. 采集明细按最新在前显示，字节数自动转换为 KB / MB / GB。
7. 从本期累计卡片进入「各应用用量」，按 UID 展示各应用本期移动网络 / WiFi 用量；支持移动网络 / WiFi 切换、手动刷新和按总量排序。
8. 采集任务独立于页面生命周期，旋转或页面重建不会中断采集、产生额外记录或丢失列表状态。

## 统计口径

### 本期累计

- 起点：安装后首次启动；每次「重置统计」后，起点改为重置时刻。
- 顶部累计直接读取系统统计：`本期起点 → 当前时间` 的整机移动网络、WiFi 下载/上传总量。
- 进入应用、采集成功或重置后会刷新本期累计。
- 重置只重置本应用的统计起点和本地明细，不会修改手机系统的流量统计。

### 采集明细

- 每次采集写入两条明细，统计区间为**该网络类型上一条明细时间 → 当前时间**。
- 本期第一次采集从本期起点开始，不使用当天 00:00。
- 查询成功时，移动网络和 WiFi 两条明细会一起写入，即使某类流量为 0。
- 系统未返回有效数据时会中止本次采集并提示重试，不会写入零记录推进采集时间。
- 明细是本地增量记录；顶部累计来自系统统计，两者可能因系统延迟、保留周期以及采集期间新增流量而略有差异，以系统累计为准。
- 数据库保存的是原始字节数，界面只做格式化展示。

### 应用流量

- 应用页复用「本期累计」的起点：`本期起点 → 当前时间`。
- 进入页面或点击「刷新」时，通过 `NetworkStatsManager.querySummary()` 同时查询移动网络和 WiFi 的按 UID 数据；页面上的网络切换只改变展示，不重复查询。
- 列表按当前网络类型的总用量从高到低排序，并隐藏该网络下用量为 0 的项；顶部显示当前网络的「应用合计」和条目数。
- 同一 UID 下的多个应用会合并为一项，名称取首个可读取的包，并在副标题中标注「等 N 个应用」。
- 受 Android 11+ 包可见性限制，只有可启动应用会被单独列出；无法关联到可见应用的 UID（已卸载应用、其他用户、无启动入口的后台组件、热点共享等）会合并为「系统与其他」；VPN 流量通常计入 VPN 应用自身的 UID。
- 如果希望后台/系统组件也单独成项，可在清单中追加 `QUERY_ALL_PACKAGES`；本项目未声明该权限，以保持最小权限。
- 应用合计是各 UID 的汇总，可能大于、小于或等于整机总量，不能作为计费依据。
- 应用流量每次从系统实时查询，不写入本地数据库；重置统计后，应用页会直接切换到新的周期，旧周期没有本地快照可追溯。

### 系统统计的限制

- **存在延迟**：`NetworkStatsManager` 的数据由系统定期汇总，刚产生的流量可能尚未计入，短时间内连续采集可能得到 0。
- **仅手动记录明细**：应用被杀死、被系统回收或未运行时不会自动采集明细；已保存的明细不会丢失。
- **保留周期有限**：本期累计依赖系统统计，系统只保留一段时间的历史；应用自己的 SQLite 明细会一直保留到重置、卸载应用或清除应用数据。
- **重置不可撤销**：重置会清空本期明细并重新设定起点，请确认后再操作。
- **统计范围受系统实现影响**：不同厂商、双卡、热点共享、系统应用流量等场景的统计结果可能有差异。
- **应用归属不精确**：系统按 UID 而非包名统计，共享 UID、已卸载应用、系统服务、其他用户、热点共享和 VPN 都可能导致应用列表与整机总量不一致。
- **应用数据同样受延迟和保留周期影响**：刚产生的流量可能尚未出现在应用列表中；系统清理统计后，应用页也无法恢复旧数据。
- **不能作为计费依据**：结果仅供自用参考，请以运营商账单为准。

## 权限说明

| 权限 | 用途 |
| --- | --- |
| `android.permission.ACCESS_NETWORK_STATE` | 读取网络相关状态，普通权限 |
| `android.permission.PACKAGE_USAGE_STATS` | 读取系统流量统计的特殊权限，需用户在系统设置中手动开启，无法通过运行时弹窗申请 |

本项目最低支持 Android 11；查询时 `subscriberId` 传 `null`，由系统聚合全部移动网络，因此不申请 `READ_PHONE_STATE`。

清单中的 `<queries>` 只声明「可启动应用」的可见性，用于把系统流量统计里的 UID 映射为应用名称与图标；没有申请 `INTERNET`，应用列表不会离开本机。

## 构建环境

- Android Studio（支持 AGP 8.5.x，建议 2024.1.1 及以上）
- JDK 17
- Android SDK Platform 34
- Gradle 8.7（工程已包含 Gradle Wrapper）
- AGP 8.5.2

## 构建与安装

### Android Studio

1. 用 Android Studio 打开本目录（包含 `settings.gradle` 的目录）。
2. 在 `Settings → Build, Execution, Deployment → Build Tools → Gradle` 中将 **Gradle JDK** 设为 JDK 17。
3. 等待 Gradle Sync 完成；首次同步需要联网下载 Gradle 与 AGP。
4. 菜单 `Build → Build Bundle(s) / APK(s) → Build APK(s)`。
5. Debug APK 输出在 `app/build/outputs/apk/debug/流量记录.apk`。

### 命令行

Windows：

```bat
gradlew.bat assembleDebug
```

macOS / Linux：

```bash
./gradlew assembleDebug
```

> 命令行构建要求 `JAVA_HOME` 指向 JDK 17，并已安装 Android SDK Platform 34。
> 还需要通过 `ANDROID_HOME` / `ANDROID_SDK_ROOT` 环境变量或项目根目录的 `local.properties`（`sdk.dir=...`）指定 SDK 路径。
> 首次构建需要联网下载 Gradle 与 AGP；Gradle Wrapper 已配置分发包的 SHA-256 校验。

### 安装到手机

- USB 调试：连接手机后执行 `adb install -r "app/build/outputs/apk/debug/流量记录.apk"`。
- 或把 APK 复制到手机，用文件管理器打开；Android 11+ 需要允许该文件管理器「安装未知应用」。
- 首次启动后，按页面按钮进入系统设置：
  `设置 → 应用 → 特殊应用权限 → 使用情况访问 → 流量记录 → 允许`
  不同厂商的入口名称可能略有差异，以系统页面为准。
- 授权后返回应用，点击「采集流量」即可。

> 更换签名（例如从 debug APK 换成 release APK）时，必须先卸载旧版本再安装；卸载会删除本地历史数据库。

## 打包签名

### 生成 release 证书

项目不附带证书和密码，请自行生成并妥善保存：

```bash
keytool -genkeypair -v -keystore traffic-release.jks -alias traffic -keyalg RSA -keysize 2048 -validity 10000
```

执行过程中按提示设置 keystore 密码、key 密码和证书信息。`*.jks` 已被 `.gitignore` 排除，不要提交到版本库。

### Android Studio 生成签名 APK

1. `Build → Generate Signed Bundle / APK`。
2. 选择 `APK`，点击 `Next`。
3. 选择或新建 keystore，填写 alias 和密码。
4. 选择 `release`，保持 V1 / V2 签名启用（Android 11+ 必须包含 V2 或更高版本签名）。
5. 完成后按 Android Studio 提示的路径获取 APK；Gradle 的输出文件名为 `流量记录.apk`，release 目录为 `app/build/outputs/apk/release/`。

本仓库把签名好的安装包统一放在 `dist/流量记录.apk` 便于取用；`dist/` 只存本地产物，已在 `.gitignore` 中排除，不会提交到版本库。

### 验证签名

```bash
# 对 dist/ 或 app/build/outputs/apk/release/ 下的 APK 执行，或写完整路径
apksigner verify --print-certs 流量记录.apk
```

`apksigner` 位于 Android SDK 的 `build-tools/<版本>/` 目录下。

## 数据存储

- 数据库：应用私有目录下的 `traffic.db`
- 明细表：`traffic_record(_id, record_time, network_type, rx_bytes, tx_bytes)`
- 周期表：`stats_period(_id, period_start)`，固定一行，保存本期起点
- `record_time`：采集时间，Unix 毫秒时间戳
- `network_type`：`MOBILE` 或 `WIFI`
- `rx_bytes`：下载字节数
- `tx_bytes`：上传字节数
- 应用流量不落库：应用页每次从系统按 UID 查询，不新增数据表；系统统计被清理后无法恢复旧的应用数据。
- 应用已关闭云备份，并在 Android 12+ 禁用换机迁移；卸载应用会删除全部记录。

## 项目结构

```text
traffic-stats/
├── app/
│   ├── build.gradle
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/yh/trafficstats/
│       │   ├── MainActivity.java       权限引导、本期累计、重置、明细与应用页入口
│       │   ├── AppUsageActivity.java   应用流量页、网络切换与刷新
│       │   ├── MaxHeightScrollView.java 限制高度的可滚动头部
│       │   ├── TrafficCollector.java   进程级采集、累计/应用查询、重置调度
│       │   ├── TrafficDbHelper.java    SQLite 建表、周期起点与读写
│       │   ├── AppUsage.java           应用/系统项流量模型
│       │   ├── TrafficRecord.java      流量明细数据模型
│       │   └── TrafficFormatter.java   字节数格式化
│       └── res/
│           ├── color/segment_text.xml  分段按钮文字颜色
│           ├── drawable/              图标、卡片、按钮、徽章与空状态图形
│           ├── layout/activity_main.xml
│           ├── layout/activity_app_usage.xml
│           ├── layout/item_traffic_record.xml
│           ├── layout/item_app_usage.xml
│           ├── mipmap-anydpi/ic_launcher.xml
│           ├── values/colors.xml       Material You 风格配色
│           ├── values/strings.xml
│           ├── values/styles.xml       主题与按钮样式
│           ├── xml/backup_rules.xml
│           └── xml/data_extraction_rules.xml
├── gradle/wrapper/                     Gradle 8.7 Wrapper
├── build.gradle
├── settings.gradle
├── gradle.properties
├── gradlew
├── gradlew.bat
├── dist/                              本地签名 APK（不入库）
├── .gitignore
└── README.md
```

## 手工验收清单

1. 在 Android 11+ 手机上安装 APK 并启动，首先看到权限引导页。
2. 点击引导按钮能打开系统「使用情况访问」页面。
3. 开启权限并返回应用，界面自动切换到本期累计/采集页，并显示统计起点。
4. 点击「采集流量」，出现成功提示，本期累计刷新，采集明细新增移动网络和 WiFi 两条记录。
5. 产生一些网络流量后再次采集，累计和明细同步增长。
6. 点击「重置统计」并确认，本期累计归零、采集明细清空，统计起点更新为当前时间。
7. 点击「各应用用量」，默认显示移动网络列表；切换 WiFi 后列表、合计和条目数同步变化；点击「刷新」可重新读取系统数据。
8. 结束应用进程后重新打开，统计起点和采集明细仍在。
9. 到系统设置关闭「使用情况访问」权限，再次打开应用，回到权限引导页。
10. 在系统应用信息中确认应用没有申请或使用 `INTERNET` 权限。
