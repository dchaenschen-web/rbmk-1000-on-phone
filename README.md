# RBMK-1000 · 控制棒动态壁纸

以切尔诺贝利反应堆控制棒阵列为原型的 Android 动态壁纸。
709 根控制棒随**本机正在播放的音乐**起伏，并显示当前歌曲名。

> 本项目是 Andy Wang 的 [reactor-audio-visualizer](https://github.com/Andy-Wang/reactor-audio-visualizer)
> （MIT 许可证）的 Android 移植衍生作品。详见 [LICENSE](LICENSE)。

## 功能

* **动态壁纸**：`WallpaperService` 绘制，60fps，透明层叠在背景图上
* **跟随系统音频**：用 `AudioPlaybackCapture` 采集系统混音（不是麦克风！）
* **显示歌名**：`NotificationListenerService` 读取正在播放的曲目，可开关
* **可选背景图**：动态壁纸会取代原壁纸，选一张自己的图当底，控制棒就浮在它上面
* **可调参数**：灵敏度、平滑度、歌名开关

## 构建

本仓库自带 GitHub Actions：推送代码后自动编译，到 **Actions → 最近一次运行 → Artifacts**
下载 `rbmk-1000-debug-apk`。

本地构建（可选）：

```bash
gradle assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 安装与使用

1. 安装 APK
2. 打开 App，按顺序完成三件事：
   * **授权采集系统音频** —— 系统会弹「开始录制或投射屏幕」，确认即可
   * **通知使用权** —— 去设置里给「RBMK-1000」打开，用于读歌名（可跳过）
   * **选择动态壁纸** —— 系统壁纸选择器里选中它
3. 可选：设置 → 背景图，挑一张图当底层

## 关于「透明」

Android 的动态壁纸会**取代**原静态壁纸，所以画成透明不会露出旧壁纸，只会是黑色。
想要「浮在自己壁纸上」的效果，用 App 里的**选择背景图**功能。

## 外屏（Z Flip 系列）

在 **Good Lock → MultiStar → 启动器小部件** 里允许本应用，即可在外屏打开。
注意：三星外屏是否接受第三方**动态壁纸**，取决于机型与 One UI 版本，需实测。

## 技术结构

| 文件 | 作用 |
|---|---|
| `RodArray.kt` | 控制棒几何 + 等距投影（移植自网页版）+ FFT |
| `ReactorRenderer.kt` | Canvas 绘制（金属配色） |
| `ReactorWallpaperService.kt` | 动态壁纸引擎，60fps 绘制循环 |
| `AudioCaptureService.kt` | AudioPlaybackCapture 前台服务 + 频谱计算 |
| `MusicListenerService.kt` | 通知监听 → 歌名/歌手 |
| `MainActivity.kt` | 授权与参数设置 |
| `LicensesActivity.kt` | MIT 许可全文（再分发合规要求） |

## 许可证

MIT。原始版权 © 2026 Andy Wang。
