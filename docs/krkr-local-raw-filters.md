# KR2 缺失版本的画质滤镜接入

分支最初从 `ac3a599799ea3088a412bf8f05a8832278bdac1e` 建立，随后快进同步 PR 审查修复至 `bec5d64bea110b19776b828e6b61a488f2c3c773`；分支为 `codex/krkr-local-raw-filters`。本轮仅增加宿主适配器，保留项目当前打包的引擎二进制，不推送远端，也不更新 PR。

## 接入选择

| 内核 | 路线 | 输入来源 |
| --- | --- | --- |
| Kirikiroid2 1.2.6 | 公共 Cocos 接口适配 | 游戏窗口合成后的纹理，在屏幕缩放之前处理 |
| krkrsdl3 OpenGL | 尺寸限制舞台输出 | 按游戏逻辑像素尺度合成的完整画布，必要时保留舞台外叠加内容 |
| krkrsdl3 软件渲染 | 不开放滤镜 | 保留原有运行路径和已保存的滤镜设置 |

1.2.6 的 `UpdateDrawBuffer` 参数与 1.3.x 不同，且关键绘制调用没有可替换的 PLT 入口，因此不能仅增加库名白名单。适配器需要独立处理其公共绘制接口，并保留现有 1.3.4 / 1.3.9 路径。

krkrsdl3 的窗口纹理使用不透明句柄，当前库没有完整舞台的公共 GLuint 导出接口；已有呈现 Hook 被 Live2D 使用，不能覆盖。尺寸限制路线先在完整合成帧内使用宿主离屏逻辑画布，保留引擎原有的输入映射及呈现 Hook。画布覆盖整个物理窗口在逻辑像素尺度下的范围，主舞台的像素中心保持 1:1；不会先渲染成屏幕分辨率再降采样。内容均被主舞台包含时，在合成结束后按整数边界裁剪回主舞台尺寸；否则处理完整画布。这样可保留 Underlay 回调期间新加入的叠加层，分析器输入尺寸也随实际处理画布统计。

## 二进制身份

| 文件 | GNU Build ID | SHA-256 |
| --- | --- | --- |
| libgame126.so | aaeecb4392e474ff267639faa0d5689210ca716e | c7d05f84d45deb3906987da7d02af288c1ea3bb949e4d6e5cbe80c09441b2560 |
| libkrkrsdl3.so | e088c4563fdbc75118564e68acf6e3e70ec83a91 | d5dff5f84b410576415ddbf2afcfdbad112ef9d71851e153584a759065e163ac |
| libSDL3.so | c6ae4383b991cdf2c14bc5555f7273923e47581a | 9b1ae950c5b90168193f474c3f53dbcf7eba7230729ae025dcdba83f34def790 |

上述库均使用现有打包文件。新的宿主桥随项目构建，未重编译或替换引擎库。未知 Build ID、缺失接口或不可信尺寸应安全回退，不能将屏幕尺寸冒充原始帧尺寸。

## 参考与范围

公开舞台结构参考 [krkrsdl3 固定版本的 PlatformView.h](https://github.com/krkrsdl3/krkrsdl3/blob/e5cabc20a0ebcc4bc024fa245fc661036bc1790a/environ/PlatformView.h)。打包库含扩展接口，实际挂接以当前 ELF 的导出符号、重定位和 Build ID 校验为准，不假定最新上游源码与打包库完全一致。

未引入新的滤镜上游或运行时依赖。本轮不包含 CuNNy2、用户自定义导入、自研 Artemis 内核或既有暂缓问题的修复。

## 实现与资源边界

### Kirikiroid2 1.2.6

- 使用 `UpdateDrawBuffer(iTVPBaseBitmap*)` 对应的独立路径，核对窗口、主 Sprite、可见纹理矩形和游戏舞台尺寸；拒绝多窗口歧义、不透明度或混合方式不匹配、批处理节点及源像素与内容尺寸不一致的情况。
- 在屏幕放大前处理纹理，通过引擎公共 `RenderTexture` / `Sprite` 接口承载结果；同尺寸 GPU 拷贝，不做 CPU 读回。保留原几何和输入坐标，绘制队列 flush 后才恢复源纹理、UV 和混合方式。
- 将承载纹理可能的 POT 填充及两份 RGBA 存储保守计入共享的 256 MiB 整链预算。新尺寸规划前释放旧承载目标；分配失败有限退避，GL 附件、拷贝及恢复失败均不发布无效结果。
- 原有 1.3.4 / 1.3.9 路径保留。正常暂停在当前 GL 上下文内释放；意外上下文丢失且旧承载仍被保留时，最多放弃一份有界旧资源，并停用本次进程内的 1.2.6 滤镜，避免旧 GL 名称误删新上下文对象。这一保守路径仍需设备验证。

### krkrsdl3 OpenGL

- 不替换 Live2D 使用的 PrePresent / Underlay / Overlay 回调。仅在已核验的 arm64 引擎、SDL Build ID、导出符号、304 字节 vtable 及重定位匹配时安装宿主适配器；未修改原库的磁盘文件。
- 以游戏源像素为尺度建立覆盖完整显示范围的画布，整数 padding 保持主舞台像素中心 1:1。实际 Draw 调用决定当帧裁回主舞台还是处理完整画布，兼容 Underlay 内新加入或改变可见性的叠加层。裁剪前再次核对 UV 能否恢复精确整数矩形。
- 分析器报告实际处理的主舞台或完整画布尺寸；舞台外叠加内容存在时，输入尺寸可含留白。不能把它误称为引擎内部直接导出的纯游戏纹理。
- 离屏 RGBA8 与 D24S8 合计按 8 字节/像素预留到共享预算，另计裁剪存储；临时分配失败按 2 / 4 / 8 / 16 / 30 秒退避，尺寸或上下文变化后可重新规划。
- GL 状态保存与恢复包含读写 FBO、viewport、VAO、采样器、像素解包 PBO、深度和 stencil 写掩码等。上下文代数防止 EGL 句柄复用时误删旧资源。JNI 回调限制在 SDLThread，结束时先停止渲染再解除适配；指针恢复无法确认时保留有界会话到进程结束，不卸载仍被引用的桥。
- 未验证的直接 GL 插件、自定义 viewport 和绕过标准合成路径的渲染不在保证范围内；可检测到契约不符时当前帧保留整张画布并跳过滤镜，之后交回原引擎直到几何或上下文变化。软件渲染仍走原路径。

### 设置与层次

全局、单游戏预览与实际启动共用能力判断，并传入实际 renderer；SDL3 OpenGL 不借用 Kirikiroid2 的版本选项，software / sw 禁用滤镜请求但不抹掉已保存链。Artemis 自动版本的原有运行时能力判断保留。引擎适配不依赖 App UI 或 Compose，设置页沿用现有 Miuix 组件，本轮没有新增布局或取色实现。

## 静态审查报告（2026-10-16）

按 `AGENTS.md` 的逻辑、架构、健壮性、回归和规范视角完成独立交叉走读，并在修正后复核最终源码。复核闭环包括：

| 范围 | 检查与处理 | 最终结论 |
| --- | --- | --- |
| 1.2.6 ABI 与绘制生命周期 | 区分 bitmap / texture ABI；仅处理明确的主 Sprite；延迟至 flush 后恢复；retain 源对象 | 未遗留已确认的静态问题 |
| 1.2.6 资源与失败 | POT 及承载双份存储预算、旧尺寸释放、附件与拷贝错误、上下文丢失保守停用 | 未遗留已确认的静态问题；异常上下文仍待实机验证 |
| SDL 完整合成与输入 | 不占用现有插件回调；物理坐标逆变换只用于绘制，不反馈到输入；分数居中与整数像素裁剪 | 未遗留已确认的静态问题 |
| SDL 动态叠加层 | 初版主舞台 FBO 会裁去留白内内容；仅预判已存在叠加层仍漏掉当帧 Underlay 新增内容 | 已改为每帧完整画布，按实际 Draw 范围选择裁剪；独立复核确认闭环 |
| 原生资源 / JNI / 卸载 | 异常与分配出口、PBO 和深度 stencil 状态、上下文代数、事务失败与第三方 Hook 冲突 | 未遗留已确认的静态问题；未知插件契约不作兼容承诺 |
| App 能力 / 架构 / 回归 | renderer 预览与启动一致；切换版本保留设置；保持 Artemis 自动判断及 1.3.4 / 1.3.9 路径 | JVM 回归及静态检查通过 |
| 规范 / 资源归属 | 无引擎二进制替换、无新 UI 控件/色值、无重复滤镜资源；功能与文档分开提交 | 本地候选符合本轮改动范围 |

当前环境未找到 AGENTS 指定的 Android CLI 审查技能；没有把独立静态审查或 Gradle 构建声称为执行了该技能。静态复核未确认尚未闭环的可操作缺陷，不构成运行时兼容性证明。

## 本地验证与交付

最终完整画布版本执行以下命令通过（JDK 17、NDK 28.0.13004108，离线缓存）：

```powershell
$env:TYRANOR_APPLICATION_ID = 'com.tyranor.next.debug'
.\gradlew.bat :engine:testDebugUnitTest :app:testDebugUnitTest :engine:externalNativeBuildDebug :app:assembleDebug --offline --no-daemon
```

- `BUILD SUCCESSFUL in 35s`，83 个任务，17 个执行、66 个复用。
- engine：310 个 JVM 测试，0 失败、0 错误、0 跳过。
- app：557 个 JVM 测试，0 失败、0 错误、1 跳过；跳过的是依赖大小写敏感文件系统的 `RpgSaveScanDirsTest.keepsSavedataAndSavedataDistinctOnCaseSensitiveFilesystem`。
- 新增 C++ 几何回归源码通过 NDK C++17 `-Wall -Wextra -Werror` 编译检查；未在本机执行该独立可执行测试，不计入上面的 JVM 测试数量。
- 三个原始引擎库的 SHA-256 与上表一致；APK 包含新 SDL3 宿主桥和 KR 桥，包名经 aapt2 核对为 `com.tyranor.next.debug`，版本名 `1.58`、versionCode `1058000`。未提交 APK 或构建产物。
- 构建保留既有 Android 废弃 API 警告；AGP 提示新 SDL3 桥未剥离符号而按原文件打包，没有导致构建失败。

APK：`app/build/outputs/apk/debug/app-debug.apk`。

SHA-256：`2517e031d442fcbbcb240de62d212cf8df23ff2fa2cf5c6b38989c3c38cb9674`。

本轮未执行远端 CI、推送、PR 更新、安装或实机测试。待设备确认：两个新版本的纹理方向、不同分辨率的原始输入与铺屏、滤镜开关与链切换、后台恢复和 EGL 上下文重建、动态叠加层与 Live2D、实际游戏输入。不能用先前官方 Artemis 的两小时验收代替上述新增路径的验证。

提交 head 与本地备份索引记录在仓库外的 `D:/Tyranor-Next/local-notes/krkr-local-20261016/index.json`，以该索引和当前分支 `git rev-parse HEAD` 为准。
