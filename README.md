# SPW Track Mix

在 SPW 播放页分离本地歌曲的六条音轨，实时调整人声、鼓组、贝斯、吉他、钢琴和其他乐器。

## 安装与使用

需要 Windows x64、Steam 版 SPW 1.19.3，以及带 Py Launcher 的 64 位 Python 3.13。导入 `.spmod` 并启用，允许读取当前曲目信息和修改客户端界面。

1. 在 SPW 播放本地立体声歌曲，打开播放页「更多 → 六轨混音」。开启兼容版 AMLL into SPW 的「重构播放页面」时，改用右下角的推子图标，频谱随六轨混音变化。也可以从插件设置打开调音台。
2. 选择「标准分离」或「快速分离」。快速分离优先缩短用时，减少分段重叠并优先使用 CUDA FP16。可在快速分离完成后再跑标准分离。首次使用会联网安装独立 Python 推理环境并下载约 700 MB 的模型。带 CUDA 的环境需要额外数 GiB 磁盘空间。后续分离可离线进行。
3. 分离完成后选择纯伴奏或仅人声，也可拖动推子进入自定义。可同时独奏多轨，单轨静音优先于独奏。静音时推子归零，取消静音会恢复该轨音量。拉起静音或被独奏排除的轨道可重新听到该轨。切回原曲或重置即可恢复原曲直通。

切歌后，正在处理和排队的歌曲仍按各自歌名显示进度，可单独取消。每次切歌或重启 SPW 都从原曲直通开始。暂停、继续播放和进度跳转时保留当前混音。下次播放同一首歌时可直接复用已分离的音轨。

伴奏和独奏按启用音轨的整体响度向原曲补偿，推子仍可以单独调低各轨。已有缓存以六轨合成的响度估算原曲，无需重新分离。混音中跳到尚未预读的位置时，短暂静音后从已选音轨淡入。

启用插件前已经打开的曲目，重新打开一次即可接入混音。音频解码失败、无可定位的立体声 PCM 或缓存缺失时，SPW 继续播放原曲。

SPW 调音台和 AMLL 弹层的「模型和缓存」都可调整缓存上限、FP16、CPU 或高质量 TTA，并清理当前歌曲、全部缓存或模型。缓存保留 32-bit float PCM，目前默认 10 GiB。自动淘汰和手动清理使用 Windows 回收站。

## 构建


## 依赖与模型

- [SPW Workshop API](https://github.com/Moriafly/spw-workshop-api) 和 [SaltUI](https://github.com/Moriafly/SaltUI) 使用 Apache-2.0。插件编译时依赖 Workshop API，随包提供 SaltUI 组件及其 Apache-2.0 依赖 Haze、AndroidX 和 JSpecify。播放沿用 SPW 提供的 BASS 库。
- [Audio Separator](https://github.com/nomadkaraoke/python-audio-separator/blob/v0.48.0/LICENSE)、[PyTorch](https://github.com/pytorch/pytorch/blob/v2.10.0/LICENSE) 和离线推理环境的其他依赖由安装器获取。
- 插件包不包含权重。[BS-RoFormer SW Fixed 的公开镜像](https://huggingface.co/enerjazzer/BS-ROFO-SW-Fixed) 将权重许可标为未知。
