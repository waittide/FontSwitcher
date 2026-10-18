# FontSwitcher

HarmonyOS（华为 / EMUI）**全局主题字体切换器**，纯 root，无需 Magisk 模块、无需 LSPosed。

给一个 `.ttf` / `.otf` / `.ttc`，点一下按钮，全系统换字——**不重启设备、不黑屏**。

## 原理

HarmonyOS 的主题字体是一套**独立于 AOSP FontManager** 的机制，`dumpsys font` 里永远看不到它。生效条件只有三条，缺一不可：

| # | 条件 | 说明 |
|---|---|---|
| 1 | `/data/skin` → `/data/themes/0` 软链存在 | 系统自带，缺了就补 |
| 2 | `/data/themes/0/fonts/DroidSansChinese.ttf` 存在 | **文件名固定**，不能改 |
| 3 | 该文件的 SELinux 标签是 `u:object_r:theme_data_file:s0` | 最容易踩的坑，见下 |

第三条是关键：如果只是 `cp` / `mv` 把文件搬进去，标签会变成 `shell_data_file`——文件明明在、root 也读得到，但**全系统一个进程都不会加载它，且没有任何报错**。必须显式 `chcon`。

### 为什么能「不黑屏」

字体是**按进程懒加载**的：换文件后老进程仍持有旧 inode 的 mmap，只有重新走一遍 `Typeface.loadHwSystemFonts()` 才会换字。framework 里那段逻辑是：

```java
v1 = HwThemeManager.setThemeFontOnConfigChg(newConfig);   // 只看 0x8000 位
if ((changes & 0x200) != 0 || v1) {
    Typeface.loadHwSystemFonts(userId, nightMode);        // ← 这里才重读 ttf
}
```

而 `HwConfiguration.updateFrom()` 里 `0x8000` 严格等价于「送进来的 `hwtheme` 非 0 且与当前值不同」。所以本应用的做法是照着 ThemeManager 的路子，把 `hwtheme` 计数 +1 交给 `ActivityManagerEx.updateConfiguration()`：

```
su -c 'CLASSPATH=<APK> /system/bin/app_process /system/bin \
    io.github.waittide.fontswitcher.core.HotReloadTrigger'
```

> ⚠️ `setConfigItem` 的 mode **1** 才是 `hwtheme`（`Configuration.toString()` 里的 `hwt:N`）。
> 反编译时很容易读成 4，那是 `isClearCache` —— 写它永远不会触发重载，
> 因为 `updateFrom()` 里 `if (delta.hwtheme == 0) skip` 会整段跳过，返回 `changes == 0`。

## 三种生效方式

| 方式 | 耗时 | 视觉副作用 | 生效范围 |
|---|---|---|---|
| **热重载**（默认） | ~1.5 s | **无** | 15–28 个进程 |
| 重启系统界面 | ~0.9 s | 黑屏 ~0.4 s | 只有 SystemUI |
| 软重启 zygote | ~10 s | 所有 App 被关闭 | 全部进程 |

热重载不可用时（非华为设备、反射不到私有 API、AMS 拒收）会自动回落到「重启系统界面」。

## 安全边界

- 只修改 `/data/themes/0/fonts/DroidSansChinese.ttf` 一个文件
- **不碰** `/system`、**不修改** `fonts.xml`、**不安装** Magisk 模块
- 出问题时点「还原系统默认字体」，或直接删掉那个文件后重启
- 已打开的 App 若没换字，重启该 App 即可

## 限制

- **字体是全局的**：无法只给某一个 App 换字体（那需要 LSPosed 注入进程）
- 只有单一字重：粗体会走合成伪粗（这是 HarmonyOS 主题字体机制本身的限制）
- 换主题、清数据会重置字体，重跑一次即可

## 环境要求

- 华为 / 荣耀设备，HarmonyOS 4.x（EMUI 14）实测：MatePad 11 2021 `DBY-W09` / HarmonyOS 4.2.0.208
- root：APatch / KernelSU / Magisk 均可，**需要在 root 管理器里给本应用授权**
- minSdk 26

> APatch 用户注意：`su` 对未授权 uid 是**不可见**的（返回 `ENOENT` 而不是权限错误），
> 所以必须在 APatch 管理器 →「超级用户」里手动打开 FontSwitcher 的开关。

## 构建

```bash
export ANDROID_HOME=/path/to/android-sdk   # 需要 platforms;android-37.0 + build-tools;37.0.0
./gradlew assembleRelease
```

APK 输出到 `app/build/outputs/apk/release/`。

## 许可

GPL-3.0
