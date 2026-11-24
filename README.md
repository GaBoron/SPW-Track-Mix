# SPW Track Mix

SPW Track Mix 是 Salt Player for Windows 的六轨调音台。插件在后台将本地歌曲分离为人声、鼓组、贝斯、吉他、钢琴和其他乐器。播放时可以直接用本地缓存混音，独立调节每条音轨的音量、静音或独奏。

## 安装

- **播放器**需使用 Windows x64 上的 **Steam 版 SPW 1.19.3**。
- **分离环境**需安装带 Py Launcher 的 **64 位 Python 3.13**。可用 `py -3.13 --version` 检查启动器能否找到 Python。
- **首次分离**需联网下载约 700 MB 的模型并安装独立推理环境。CUDA 环境还需数 GiB 磁盘空间。环境和模型准备好后，歌曲分离与混音都可离线使用。

通过[源码构建](#源码构建)取得 `.spmod`，在 SPW 的「创意工坊 → 模组管理 → 导入模组」中选择该文件。启用时允许类转换（`CLASS_TRANSFORM`）和曲库读取（`LIBRARY_READ`），以接入播放链路和获取当前曲目。[SPW 模组安装说明](https://saltplayer.com/workshop/usage)

## 分离与混音

1. 播放本地立体声歌曲，从播放页「更多 → 六轨混音」打开调音台。也可以在插件设置中点击「打开六轨调音台」。
2. 选择「标准分离」或「快速分离」。分离期间可以照常听原曲、切歌或将其他歌曲加入队列。后台任务按歌名显示进度，并可单独取消。
3. 分离完成后，选择「纯伴奏」去掉人声，或选择「仅人声」。也可以用 0%～150% 的推子、静音和独奏自定义混音。选「原曲」或「重置」可恢复原曲直通。

| 分离模式 | 适用场景 |
| --- | --- |
| 标准分离 | 优先保证分离质量。可在设置中开启「高质量 TTA」，使用同一模型做两次推理后合成结果，耗时更长。 |
| 快速分离 | 优先缩短等待时间。减少分段重叠，并在可用时优先尝试 CUDA FP16。之后仍可对同一首歌做标准分离。 |

两种模式均使用 BS-RoFormer SW Fixed。分离优先使用可用的 NVIDIA CUDA，必要时回退到 CPU，也可以在设置中选择「仅 CPU」。

暂停和进度跳转会保留当前混音。切歌或重启 SPW 后默认播放原曲，已有音轨可直接复用。如果启用插件前歌曲已经打开，重新打开这首歌即可接入混音。

## AMLL 联动

使用支持播放页扩展 API 的 AMLL into SPW 构建，并开启「重构播放页面」。点击播放页右下角的推子图标打开六轨调音台，频谱会随混音变化。[AMLL 播放页扩展接口](https://github.com/GaBoron/AMLL-into-SPW/blob/main/docs/%E6%92%AD%E6%94%BE%E9%A1%B5%E6%89%A9%E5%B1%95%E6%8E%A5%E5%8F%A3.md)

在调音台右上角点齿轮打开混音设置。模型一栏显示下载状态及磁盘占用，可直接下载或删除模型。「管理缓存」列出所有已缓存歌曲，并显示总占用和每首歌的大小。同一首歌的快速与标准结果共用一条记录，可按首删除或清空。再次播放对应歌曲时，会为旧缓存补上歌名。

## 缓存与磁盘空间

缓存、模型和推理环境都保存在 `%LOCALAPPDATA%\SPW Track Mix`。音轨以 32 位浮点 PCM 保存，默认缓存上限为 **10 GiB**，可在设置中调整。SPW 原生调音台可在「模型和缓存」中清理当前歌曲或全部音轨。手动删除和自动淘汰的音轨缓存会移入 Windows 回收站。

## 源码构建

在 Windows 上安装 **JDK 25**，从仓库根目录运行 Gradle Wrapper。

```powershell
.\gradlew.bat check plugin
```

`check` 会运行混音、缓存和任务队列等回归检查。`plugin` 会将安装包写入 `build/libs/spw-track-mix-<版本>.spmod`。首次构建需联网获取 Gradle 和构建依赖。

## 依赖与模型

- SPW Workshop API 和 SaltUI 采用 Apache-2.0。插件包带有 SaltUI 组件及其 Apache-2.0 依赖 Haze、AndroidX 和 JSpecify。音频播放沿用 SPW 提供的 BASS。[SPW Workshop API](https://github.com/Moriafly/spw-workshop-api) · [SaltUI](https://github.com/Moriafly/SaltUI) · [随包附带的许可文本](licenses/Apache-2.0.txt)
- 首次分离会安装 Audio Separator、PyTorch 及其运行依赖。[Audio Separator 许可证](https://github.com/nomadkaraoke/python-audio-separator/blob/v0.48.0/LICENSE) · [PyTorch 许可证](https://github.com/pytorch/pytorch/blob/v2.10.0/LICENSE)
- BS-RoFormer SW Fixed 权重由插件按需下载，模型镜像将权重许可证标为未知。[模型镜像](https://huggingface.co/enerjazzer/BS-ROFO-SW-Fixed)

遇到问题可在 [GitHub Issues](https://github.com/GaBoron/SPW-Track-Mix/issues) 附上 SPW 版本、分离模式和完整的报错提示。
