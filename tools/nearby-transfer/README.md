# 附近快传电脑下载器

手机在「我的 → 附近快传」开始分享后，把完整下载地址复制到电脑：

```bash
python3 download.py 'http://手机地址:8765/s/临时令牌/'
```

默认保存到桌面「你尔多龙吗快传」。下载使用 `.part` 和 ETag/If-Range 断点续传；相机卡目录按层读取，不会让手机一次递归加载整张卡。

常用选项：

```bash
python3 download.py URL -o ~/Pictures/CameraCard
python3 download.py URL --quality high
python3 download.py URL --path DCIM/100CANON/IMG_0001.JPG
```

网页中的 ZIP 适合临时下载少量文件，不支持断点续传。大视频、RAW 或整张卡请使用本脚本逐个下载。

如果要让电脑反过来成为分享端：

```bash
python3 share.py ~/Movies/big.mp4
python3 share.py ~/Pictures/CameraCard
```

手机连接同一热点/Wi-Fi 后用浏览器打开终端显示的带随机令牌地址即可。电脑分享端同样支持 Range、ETag 与断点续传。
