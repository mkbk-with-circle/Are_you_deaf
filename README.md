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

Windows（不要写 `~/Download`，那不是 Windows 路径）：

```powershell
python share.py %USERPROFILE%\Downloads
python share.py D:\夹1 D:\夹2 E:\a.mp4
```

防火墙放行 Python（专用网络）。路径不对时脚本会直接退出并打印「路径不存在」。

## 手机 App → 电脑

手机在「附近快传」开始分享后：

```bash
python3 download.py 'http://手机地址:8765/s/令牌/'
python3 download.py URL -o ~/Pictures/CameraCard
```

默认保存到桌面「你尔多龙吗快传」。大文件、整卡用本脚本；浏览器 ZIP 只适合少量小文件。
