package io.github.waittide.fontswitcher.core

import android.content.res.Configuration

/**
 * 免重启的字体热重载触发器。
 *
 * ── 这个类的 main() 不是在 App 进程里跑的 ──
 * 它由 [FontInstaller.hotReload] 通过
 *
 *     CLASSPATH=<本 APK> /system/bin/app_process /system/bin \
 *         io.github.waittide.fontswitcher.core.HotReloadTrigger
 *
 * 以 root 身份单独拉起一个 `app_process` 来执行。这样做的两个原因：
 *   · App 进程受 hidden-API 限制，`com.huawei.android.app.ActivityManagerEx` 这类
 *     华为扩展类不一定能反射到；app_process 不是 app 进程，没有这层限制。
 *   · 路径里用的是 APK 自己的 classes.dex，不需要额外打包 dex 资产。
 *
 * ── 原理 ──
 * 华为的字体是**每个进程各自**在 `ConfigurationController.handleConfigurationChanged`
 * 里懒加载的。framework 里那段逻辑是：
 *
 *     v1 = HwThemeManager.setThemeFontOnConfigChg(newConfig)   // 只看 0x8000 位
 *     if ((changes & 0x200) != 0 || v1) {
 *         Typeface.loadHwSystemFonts(userId, night)            // 这里才重读 ttf
 *     }
 *
 * 而 `HwConfiguration.updateFrom()` 里 `0x8000` 严格等价于
 * 「送进来的 hwtheme 非 0 且与当前值不同」。ThemeManager 自己就是这么干的：
 * `HwThemeManagerImpl.updateConfiguration()` 把 `getConfigItem(1)` 加一，
 * 再交给 `ActivityManagerEx.updateConfiguration(cfg)`。
 *
 * ⚠ 这里 mode **1** 才是 hwtheme 计数（Configuration.toString 里的 `hwt:N`）。
 * 网上/反编译容易读成 4，那是 isClearCache，写它永远不会触发重载
 * （`updateFrom` 里 `if (delta.hwtheme == 0) skip` 会整段跳过，返回 changes==0）。
 *
 * 实测（DBY-W09 / HarmonyOS 4.2.0.208）：单进程改完 48–119 ms，
 * 全系统 15–20 个进程换到新 inode，**无黑屏、无进程重启、无崩溃**。
 */
object HotReloadTrigger {

    private const val MODE_HWTHEME = 1

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val amex = Class.forName("com.huawei.android.app.ActivityManagerEx")
            val getConfig = amex.getMethod("getConfiguration")
            val update = amex.getMethod("updateConfiguration", Configuration::class.java)

            val adapter = Class.forName("com.huawei.android.content.res.ConfigurationAdapter")
            val getExtra = adapter.getMethod("getExtraConfig", Configuration::class.java)

            val ihw = Class.forName("android.content.res.IHwConfiguration")
            val intCls = Int::class.javaPrimitiveType
            val getItem = ihw.getMethod("getConfigItem", intCls)
            val setItem = ihw.getMethod("setConfigItem", intCls, intCls)

            val cur = getConfig.invoke(null) as Configuration
            val curExtra = getExtra.invoke(null, cur)
            val old = getItem.invoke(curExtra, MODE_HWTHEME) as Int

            // 必须以当前全局配置为基底复制，不能用 new Configuration()：
            // 空白配置会让 AOSP 侧 Configuration.updateFrom() 认为什么都没变。
            val next = Configuration(cur)
            val nextExtra = getExtra.invoke(null, next)
            setItem.invoke(nextExtra, MODE_HWTHEME, old + 1)

            val t0 = System.currentTimeMillis()
            val applied = update.invoke(null, next) as Boolean
            val ms = System.currentTimeMillis() - t0

            println("RESULT applied=$applied old=$old new=${old + 1} ms=$ms")
            if (!applied) kotlin.system.exitProcess(2)
        } catch (t: Throwable) {
            println("RESULT error=${t.javaClass.name}: ${t.message}")
            kotlin.system.exitProcess(3)
        }
    }
}
