# 电脑附近快传

只含电脑端脚本，不包含安卓 App。标准库即可运行，无需 `pip install`。

- `share.py`：电脑当文件源，手机/其他电脑用浏览器下载
- `download.py`：从「你尔多龙吗」App 的附近快传地址断点续传到电脑

完整 App 与闹钟/日志功能在仓库 `main` 分支。

## 环境

- Python 3.10+（Mac 不要用 conda 的 `python3`，脚本会自动改用系统/Homebrew）
- 两端连同一热点或 Wi-Fi，先关掉 VPN
- 用 `http://`，不要改成 `https://`

```bash
git clone -b computer-transfer --single-branch https://github.com/mkbk-with-circle/Are_you_deaf.git
cd Are_you_deaf
```

## 电脑 → 手机

```bash
python3 share.py ~/Downloads
python3 share.py ~/Downloads ~/Movies/a.mp4 ~/Pictures
```

终端里选标成「热点/Wi-Fi」的地址，在手机系统浏览器打开。传完 Ctrl+C。

网页使用响应式文件网格，手机和电脑浏览器都可以：

- 勾选文件或整个文件夹，选择可以跨目录保留
- 「下载当前目录」「下载目录」「下载所选」都会先递归统计文件数与大小
- 确认后边读取边生成 ZIP，并自动触发浏览器下载；分享端不写完整临时副本
- 点击单文件可预览，点击「下载」会保存原文件；单文件仍支持 HTTP Range 断点续传

批量 ZIP 最多 10,000 个文件、8 GiB，且 ZIP 本身不能断点续传；大型视频和整张相机卡建议分目录或逐文件下载。

### 多台分享端自动发现

每台电脑都加 `--discoverable` 后，网页顶部会自动列出同一局域网中的其他在线分享端：

```bash
python3 share.py ~/Pictures --discoverable --name "我的 Mac"
python3 share.py ~/Downloads -p 8766 --discoverable --name "Windows 笔记本"
```

默认不广播，仍是只有拿到随机链接才能访问的私密模式。`--discoverable` 会把临时访问入口广播给当前局域网，
只应在可信热点或 Wi-Fi 中使用。服务停止约 8 秒后会从其他页面自动消失。

不同电脑可以使用相同端口；同一台电脑启动多个分享进程时要分别指定 `-p`。部分热点启用了客户端隔离，
这种网络中各客户端可能只能访问热点主机、不能互访，脚本无法绕过热点系统的隔离策略。

Windows 默认目录名是 `Downloads` 复数，不是 `Download`。

PowerShell：

```powershell
python share.py "$HOME\Downloads"
python share.py "D:\夹1" "D:\夹2" "E:\a.mp4"
```

CMD：

```bat
python share.py "%USERPROFILE%\Downloads"
python share.py "D:\夹1" "D:\夹2" "E:\a.mp4"
```

Git Bash：

```bash
python share.py "$HOME/Downloads"
# ~/Downloads 也可以；不要少写最后的 s
```

首次运行先检查 `python --version`。若 `python` 是微软商店占位程序，改用
`py -3 share.py ...`，或在 Windows 的「管理应用执行别名」中关闭 Python 商店别名。

防火墙放行 Python（专用网络）。路径不对时脚本会直接退出并打印「路径不存在」。

WSL 里的 `python3` 是 Linux Python，`~/Downloads` 也是 Linux 家目录。脚本会显示 WSL 警告；
由于 WSL2 NAT 不一定能被手机热点直接访问，本功能优先在 PowerShell 或 Git Bash 中运行。

## 手机 App → 电脑

手机在「附近快传」开始分享后：

```bash
python3 download.py 'http://手机地址:8765/s/令牌/'
python3 download.py URL -o ~/Pictures/CameraCard
```

默认保存到桌面「你尔多龙吗快传」。大文件、整卡用本脚本；浏览器 ZIP 只适合少量小文件。
