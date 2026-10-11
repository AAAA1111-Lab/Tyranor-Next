# Siglus 方案 A 下游补丁

此目录服务于原始舞台帧的滤镜处理。固定原上游 `xmoezzz/siglus_rs` 的提交 `5f358ef1360b0439e92aed2b749e8f25d8721954`；补丁仅应用于 `build/siglus-stage-a/source` 构建副本，不修改只读参考仓库。

## 接口与呈现路径

`siglus_android_get_stage_geometry_v1(handle, out_geometry, out_count)` 是可选 C ABI。调用者必须在引擎所属线程传入仍存活的 handle，以及至少十个可写 `uint32_t` 的输出空间。返回 1 表示查询成功，空指针或数量不足返回 0；接口不接受任意伪造/已经销毁的指针，JNI bridge 另检查自身持有的 live handle。

| 数组索引 | 含义 |
| --- | --- |
| 0–1 | VM 逻辑舞台宽、高 |
| 2–3 | renderer 实际配置的生产 Surface 宽、高 |
| 4–7 | renderer viewport 的 x、y、宽、高 |
| 8–9 | render serial 的低、高 32 位，无符号位模式 |

生产 Surface 和 viewport 必须与逻辑舞台同尺寸且无偏移，宿主才启用滤镜。输出路径是：完整舞台合成 → 同尺寸 SurfaceTexture → 应用变换矩阵的 OES 转 RGBA8 → 公共有序 GLES 滤镜链 → 等比屏幕 viewport。该接口用于滤镜输入核验与帧提交节流；不导出内部 wgpu 纹理，也不报告单张素材分辨率。完整舞台包含文本、UI 和转场。

原有 `set_surface` 实际返回 `i32`，补丁同步修正头文件声明。JNI 持有 `ANativeWindow_fromSurface` 的引用直到 Rust Surface 替换/销毁；成功替换后释放旧引用，失败释放新引用。所有 handle 调用留在 Activity 线程，消费端独立 GLES 线程不等待主线程。

## 复现构建

要求 Windows、系统 Git、Python、Android NDK `30.0.16248370`、CMake 与 Rust `1.99.0` 的 GNU Windows 工具链及 `aarch64-linux-android` target。Cargo/Rustup home 使用项目 build 下的独立目录，不修改用户全局配置。manifest 固定源码、Cargo.lock、补丁、Rust 完整提交和旧库 ABI 锚点哈希。

```powershell
python tools/build-siglus-graphics.py `
  --ndk <AndroidSDK>\ndk\30.0.16248370 `
  --cmake-bin <AndroidSDK>\cmake\3.22.1\bin `
  --cargo-home <工作目录>\build\toolchains\cargo `
  --rustup-home <工作目录>\build\toolchains\rustup `
  --toolchain stable-x86_64-pc-windows-gnu --install
```

本机 `stable-x86_64-pc-windows-gnu` 恰为 manifest 固定版本；脚本仍核对 rustc 版本和完整提交，不接受后续浮动 stable。也可安装精确版本后省略 `--toolchain`。脚本需要的 Cargo 工具代理位于所传 cargo home 的 bin 目录。

构建目标为完整 `game_launcher --lib`，包含原有 Siglus 与 RealLive/AVG32/UK2 导出，不能用仅含 `siglus_scene_vm` 的库替换。使用 `--baseline-only` 生成无补丁对照库，不能安装；`--prepare-only` 只准备/验证源码。新库检查全部旧 ABI、16 KiB ELF LOAD 对齐后才允许 `--install`。

C ABI 最低 API 为 26，目标 arm64-v8a。补丁库 SHA-256：`9e7fa0c0b8f1830a276b3994d5258860c0c0cdc5359adf71b397ea087bdb26a6`。旧库全部导出符号保留，仅增加舞台查询符号；导出一致不等于全部共享引擎已真机回归。

Siglus 原上游声明 MPL-2.0。补丁及修改后的 MPL 文件与对应完整原上游源码按 manifest 的固定提交取得；本目录提供对应下游补丁；各第三方包声明单列，不据根许可证推定所有子包授权。
