## v1.1

- **热重载生效**：换字体不再杀进程、不再黑屏。把 `HwConfiguration.hwtheme` 计数 +1 后交给 AMS，框架会在所有活着的进程里原地重建 FontFamily —— 15~28 个进程立即换字，SystemUI pid 全程不变。
- 默认生效方式改为「热重载」，失败时自动回落到「重启系统界面」。
- 三种生效方式可选：热重载（~1.5 s，无副作用）/ 重启系统界面（~0.9 s，黑屏 0.4 s）/ 软重启 zygote（~10 s，全部进程）。
- **修复**：热重载会触发全局配置变更，本应用自己也会被重建，之前用 `rememberCoroutineScope()` 导致任务中途被取消、日志丢失。改为 App 级 `WorkState` 单例 + 独立 CoroutineScope，界面重建后日志与选中的字体都还在。
- 修复 `parseStat` 把 `ls -l` 的硬链接数当成文件大小导致「写入不完整」误报。

## v1.0

- 首个版本。纯 root，无需 Magisk 模块与 LSPosed。
- 全局替换 HarmonyOS 主题字体：写 `/data/themes/0/fonts/DroidSansChinese.ttf`，自动处理属主、权限与 SELinux 标签 `theme_data_file`。
- SAF 文件选择器、字体信息解析（SFNT/name 表）、实时预览。
- 还原系统默认字体、修复 SELinux 标签。
