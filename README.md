# 电脑附近快传

只含电脑端脚本，不包含安卓 App。标准库即可运行，无需 `pip install`。

- `share.py`：电脑当文件源，手机/其他电脑用浏览器下载
- `download.py`：从「你尔多龙吗」App 的附近快传地址断点续传到电脑

完整 App 与闹钟/日志功能在仓库 `main` 分支。

## 环境

- Python 3.9+（Mac 不要用 conda 的 `python3`，脚本会自动改用系统/Homebrew）
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

网页中点击文件名用于浏览器预览；点击每个文件右侧的「下载」按钮会以附件方式保存原文件，
不会停留在图片、PDF 或文本的预览页面。大文件仍支持 HTTP Range 断点续传。

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
