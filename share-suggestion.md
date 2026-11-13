图片分享建议：保持纯本地，不上传图片。用户在全屏查看时通过 A 长按打开的操作轮盘选择“分享”，使用 Android Sharesheet（`Intent.createChooser` + `ACTION_SEND`，MIME 为 `image/*`），将当前图片的 SAF `Uri` 放入 `EXTRA_STREAM`，并设置 `FLAG_GRANT_READ_URI_PERMISSION` 与对应 `ClipData`，让接收应用获得临时读取权限。分享前可尝试读取图片确认仍可访问；启动失败或 URI 不可读时提示“无法分享此图片”。

这样无需复制大图或申请宽泛存储权限，也能交给系统中已安装的聊天、邮件等应用处理。注意 SAF URI 通常比自行构造文件 URI 安全；持久化的目录授权只属于本应用，不能代替给接收方的临时 URI 授权。不同文档提供方对 URI 转发的支持可能不同，需在 Android 11/API 30 与目标掌机上验证。当前轮盘已有 Share 占位入口，可直接接入此行为；建议首版仅分享当前图片，不增加多选或网络上传。
