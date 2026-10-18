package io.github.waittide.fontswitcher.core

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * HarmonyOS 主题字体的安装/还原逻辑。
 *
 * ── 这套配方是实机验证出来的（DBY-W09 / HarmonyOS 4.2.0.208）──
 *
 * 生效必需的全部条件只有三条：
 *   1. `/data/skin` 是指向 `/data/themes/0` 的软链（系统本来就建好了，缺了就补）
 *   2. `/data/themes/0/fonts/DroidSansChinese.ttf` 存在（文件名固定，不能改）
 *   3. 该文件的 SELinux 标签必须是 `u:object_r:theme_data_file:s0`
 *
 * 第 3 条是最容易踩的坑：如果只是 `cp`/`mv` 把文件搬进去，标签会变成
 * `shell_data_file`，文件明明存在、root 也读得到，但**全系统一个进程都不会加载它**，
 * 而且不会有任何报错。必须显式 `chcon`。
 *
 * 另外几条实测定律：
 *   · `dumpsys font` 永远看不到这个字体（它不在 FontManager 的族列表里）
 *     → 唯一可靠的生效判据是 `grep DroidSansChinese /proc/<pid>/maps`
 *   · 字体是**按进程懒加载**的：换文件后老进程仍持有旧 inode 的 mmap
 *     → 必须让目标进程重新走一遍 `Typeface.loadHwSystemFonts()`
 *   · `cmd font restart` 只会释放映射不会重载，没用
 *   · 替换必须换 inode（先写临时文件再 `mv -f`），否则正在映射的进程会读到半截数据
 *
 * 让进程重新加载字体有三条路，默认走第一条（见 [HotReloadTrigger]）：
 *   1. bump `HwConfiguration.hwtheme` → 配置变化位带上 0x8000 → 每个进程自己重载
 *      （~1.2 s，15–20 进程，**无黑屏**、不重启任何进程）
 *   2. `setprop ctl.restart zygote`：全部进程立即换字，但会杀掉所有应用
 *   3. `kill -9 systemui`：只有系统界面生效，黑屏约 0.4 s
 *
 * 全程不碰 /system、不碰 fonts.xml、不建 Magisk 模块 → 出问题删文件即恢复。
 */
object FontInstaller {

    const val FONT_DIR = "/data/themes/0/fonts"
    const val FONT_PATH = "$FONT_DIR/DroidSansChinese.ttf"
    const val SKIN_LINK = "/data/skin"
    const val THEME_DIR = "/data/themes/0"
    const val SELINUX_LABEL = "u:object_r:theme_data_file:s0"
    const val WORK_DIR = "/data/local/tmp/fontswitcher"

    /** 系统 UI，重启它就能让桌面/状态栏/新开应用换字，约 6 秒自动回来。 */
    private const val SYSTEMUI = "com.android.systemui"

    /** 热重载触发器的入口类，由 [HotReloadTrigger] 提供，必须留在主 dex 里。 */
    const val TRIGGER_CLASS = "io.github.waittide.fontswitcher.core.HotReloadTrigger"

    const val RESTART_HOT = "hot"
    const val RESTART_SYSTEMUI = "systemui"
    const val RESTART_ZYGOTE = "zygote"

    /** 当前进程的 APK 路径；`app_process` 用它当 CLASSPATH。 */
    var apkPath: String = ""

    // ─────────────────────────── 状态查询 ───────────────────────────

    data class Status(
        val installed: Boolean,
        val sizeBytes: Long,
        val md5: String?,
        val label: String?,
        val owner: String?,
        val mode: String?,
        val skinLinkOk: Boolean,
        val dirExists: Boolean,
        val loadedProcesses: Int,
        val loadedNames: List<String>,
        val raw: String,
    )

    fun status(): Status {
        val script = """
            echo "@@dir@@"
            if [ -d $FONT_DIR ]; then echo yes; else echo no; fi
            echo "@@link@@"
            if [ -L $SKIN_LINK ]; then readlink $SKIN_LINK; else echo none; fi
            echo "@@file@@"
            if [ -f $FONT_PATH ]; then
              stat -c '%s|%U:%G|%a' $FONT_PATH
              ls -Z $FONT_PATH | awk '{print ${'$'}1}'
              md5sum $FONT_PATH | awk '{print ${'$'}1}'
            else
              echo missing
            fi
            echo "@@procs@@"
            # 一次 grep -l 扫全部 maps，比逐进程 grep -q 快 ~77 倍（0.14s vs 10.8s / 25 进程）
            grep -l "DroidSansChinese.ttf" /proc/[0-9]*/maps 2>/dev/null | head -80 |
            while read -r m; do
              pid=${'$'}{m#/proc/}; pid=${'$'}{pid%/maps}
              echo "${'$'}pid:${'$'}(cat /proc/${'$'}pid/cmdline 2>/dev/null | tr '\0' ' ')"
            done
        """.trimIndent()

        val r = RootShell.run(script)
        val out = r.output
        var dirExists = false
        var skinTarget = "none"
        var installed = false
        var size = 0L
        var md5: String? = null
        var label: String? = null
        var owner: String? = null
        var mode: String? = null
        val procs = ArrayList<String>()
        var section = ""
        for (line in out.lineSequence()) {
            when {
                line == "@@dir@@" -> { section = "dir"; continue }
                line == "@@link@@" -> { section = "link"; continue }
                line == "@@file@@" -> { section = "file"; continue }
                line == "@@procs@@" -> { section = "procs"; continue }
            }
            when (section) {
                "dir" -> dirExists = line.trim() == "yes"
                "link" -> skinTarget = line.trim()
                "file" -> when {
                    line.trim() == "missing" -> installed = false
                    line.startsWith("?") -> { /* stat 失败 */ }
                    '|' in line && line.split('|').size == 3 -> {
                        val p = line.split('|')
                        size = p[0].trim().toLongOrNull() ?: 0L
                        owner = p[1]
                        mode = p[2]
                        installed = size > 0
                    }
                    line.startsWith("u:") || line.startsWith("system_u:") -> label = line.trim()
                    line.matches(Regex("[0-9a-f]{32}")) -> md5 = line.trim()
                }
                "procs" -> if (line.isNotBlank()) procs += line.trim()
            }
        }
        return Status(
            installed = installed,
            sizeBytes = size,
            md5 = md5,
            label = label,
            owner = owner,
            mode = mode,
            skinLinkOk = skinTarget.trim() == THEME_DIR,
            dirExists = dirExists,
            loadedProcesses = procs.size,
            loadedNames = procs,
            raw = out,
        )
    }

    /**
     * 全系统有多少进程加载了主题字体（判断是否真的生效的唯一可靠办法）。
     *
     * 实现要点：必须用**单次** `grep -l` 扫全部 maps 文件。写成
     * `for m in ...; do grep -q ...; done` 会为每个 pid 起一个 grep 子进程，
     * 25 个进程实测 10.84s；单次 grep -l 只要 0.14s（77 倍差距）。
     */
    fun loadedProcessCount(): Int {
        val r = RootShell.run(
            "grep -l DroidSansChinese.ttf /proc/[0-9]*/maps 2>/dev/null | wc -l"
        )
        return r.output.trim().lines().lastOrNull()?.trim()?.toIntOrNull() ?: 0
    }

    // ─────────────────────────── 应用字体 ───────────────────────────

    /**
     * 把 [fontFile] 装成系统主题字体。
     *
     * @param restartMode RESTART_HOT 热重载（默认，无黑屏）；RESTART_SYSTEMUI 重启系统界面；
     *                    RESTART_ZYGOTE 软重启（全部应用立即换字，但会杀掉所有 app）
     * @return 安装日志（多行）
     */
    fun apply(
        fontFile: File,
        restartMode: String = RESTART_HOT,
        onProgress: (String) -> Unit = {},
    ): String {
        require(fontFile.isFile && fontFile.length() > 0) { "字体文件无效" }
        val info = SfntInfo.parse(fontFile) ?: throw RootException("不是有效的 ttf/otf 文件")
        val log = StringBuilder()

        fun step(msg: String) {
            log.append(msg).append('\n')
            onProgress(msg)
        }

        step("字体：${info.summary}")
        step("校验 md5 …")
        val md5 = md5Hex(fontFile.readBytes())
        step("md5 = $md5")

        // 1. 先把文件喂进 su 侧临时文件（root 才能写 /data/local/tmp 下的属主）
        step("写入临时文件 …")
        val copy = RootShell.run(
            script = "set -e; mkdir -p $WORK_DIR; cat > $WORK_DIR/stage.ttf; stat -c %s $WORK_DIR/stage.ttf",
            stdin = fontFile.inputStream(),
            timeoutMs = 180_000L,
        )
        if (!copy.ok) throw RootException("写入临时文件失败\n${copy.output.trim()}")

        val staged = parseStat(copy.output)
        if (staged != null && staged != fontFile.length()) {
            throw RootException("写入不完整：期望 ${fontFile.length()} 字节，实际 $staged 字节")
        }

        // 2. 准备目标目录并补软链
        step("准备 $FONT_DIR …")
        val prep = RootShell.run(
            """
            set -e
            if [ ! -d $THEME_DIR ]; then mkdir -p $THEME_DIR; fi
            if [ ! -L $SKIN_LINK ] && [ ! -d $SKIN_LINK ]; then ln -sfn $THEME_DIR $SKIN_LINK; fi
            mkdir -p $FONT_DIR
            chown system:media_rw $FONT_DIR 2>/dev/null || true
            chmod 775 $FONT_DIR
            chcon $SELINUX_LABEL $FONT_DIR 2>/dev/null || restorecon -R $FONT_DIR 2>/dev/null || true
            ls -Zd $FONT_DIR
            """.trimIndent()
        )
        if (!prep.ok) throw RootException("准备字体目录失败\n${prep.output.trim()}")
        step("目录就绪")

        // 3. 属性 + 原子替换
        step("设置属主/权限/SELinux 标签并原子替换 …")
        val install = RootShell.run(
            """
            set -e
            T=$WORK_DIR/stage.ttf
            chown system:media_rw "${'$'}T"
            chmod 664 "${'$'}T"
            chcon $SELINUX_LABEL "${'$'}T" || { echo "chcon 失败"; exit 2; }
            mv -f "${'$'}T" $FONT_PATH
            chown system:media_rw $FONT_PATH
            chmod 664 $FONT_PATH
            chcon $SELINUX_LABEL $FONT_PATH || true
            ls -lZ $FONT_PATH
            md5sum $FONT_PATH
            """.trimIndent()
        )
        if (!install.ok) throw RootException("安装失败\n${install.output.trim()}")
        if (!install.output.contains(md5)) {
            throw RootException("写入后 md5 不一致，已保留旧文件\n${install.output.trim()}")
        }
        step("文件已就位（标签 $SELINUX_LABEL）")

        // 4. 让字体真正被加载
        val t0 = System.currentTimeMillis()
        step(restartFontHosts(restartMode))

        val count = loadedProcessCount()
        step("当前有 $count 个进程加载了该字体（总耗时 ${System.currentTimeMillis() - t0} ms）")
        if (count == 0) {
            step("⚠ 没有任何进程加载字体，请检查 SELinux 标签是否为 $SELINUX_LABEL")
        }
        log.append("完成。")
        return log.toString()
    }

    /** 还原成系统默认字体（删掉主题字体文件），并重启 SystemUI。 */
    fun restoreDefault(onProgress: (String) -> Unit = {}): String {
        val log = StringBuilder()
        fun step(msg: String) { log.append(msg).append('\n'); onProgress(msg) }
        step("删除 $FONT_PATH …")
        val r = RootShell.run("rm -f $FONT_PATH; if [ -f $FONT_PATH ]; then echo STILL; else echo GONE; fi")
        if (!r.output.contains("GONE")) throw RootException("删除失败\n${r.output.trim()}")
        step("已删除，系统将回落到 HarmonyOS_Sans_SC")
        step(restartFontHosts(RESTART_HOT))
        step("当前有 ${loadedProcessCount()} 个进程仍持有旧字体（重启设备后归零）")
        return log.toString()
    }

    fun restartFontHosts(mode: String): String {
        return when (mode) {
            RESTART_HOT -> hotReload()
            RESTART_ZYGOTE -> {
                RootShell.run("setprop ctl.restart zygote")
                "已软重启 zygote（全部进程将重新加载字体）"
            }
            else -> restartSystemUi()
        }
    }

    /**
     * 用 `app_process` 以 root 跑 [HotReloadTrigger]，把 `hwtheme` 计数 +1。
     *
     * 失败（缺类、反射不到华为私有 API、AMS 拒收）时自动回落到杀 SystemUI，
     * 保证「应用为系统字体」这个动作在任何设备上至少有一种生效方式。
     */
    private fun hotReload(): String {
        if (apkPath.isBlank()) return "跳过热重载（未知 APK 路径）\n" + restartSystemUi()
        val q = if (' ' in apkPath) "\"$apkPath\"" else apkPath
        val r = RootShell.run(
            "CLASSPATH=$q /system/bin/app_process /system/bin $TRIGGER_CLASS 2>&1",
            timeoutMs = 90_000L,
        )
        val line = r.output.lineSequence().firstOrNull { it.trimStart().startsWith("RESULT ") }
        val ms = line?.let { Regex("ms=(\\d+)").find(it)?.groupValues?.get(1) }
        return if (line != null && line.contains("applied=true")) {
            "已触发热重载（hwtheme +1，配置变化位带上 0x8000，耗时 ${ms ?: "?"} ms）"
        } else {
            val why = line?.removePrefix("RESULT ")?.trim()
                ?: r.output.trim().lineSequence().lastOrNull()?.take(160)
                ?: "无输出"
            "热重载未生效（$why），改用重启系统界面\n" + restartSystemUi()
        }
    }

    /**
     * 杀 SystemUI 并轮询等它重生。
     *
     * 实测量级：kill 后 **0.5 秒**新 pid 就已存在，所以不要固定 `sleep 2500`，
     * 那会把体感拖长 2 秒。这里 150ms 一轮，拿到不同 pid 立刻返回。
     */
    private fun restartSystemUi(): String {
        val old = RootShell.run("pidof $SYSTEMUI").output.trim()
        if (old.isBlank()) return "未找到 $SYSTEMUI 进程，跳过重启"
        val t0 = System.currentTimeMillis()
        RootShell.run("kill -9 $old")
        var newPid = old
        while (System.currentTimeMillis() - t0 < 10_000L) {
            Thread.sleep(150L)
            val p = RootShell.run("pidof $SYSTEMUI").output.trim()
            if (p.isNotBlank() && p != old) { newPid = p; break }
        }
        val ms = System.currentTimeMillis() - t0
        return if (newPid != old) {
            "已重启系统界面（$old → $newPid，黑屏 ${ms} ms）"
        } else {
            "已杀 $old，但 10 秒内未见新进程（可能已自行恢复）"
        }
    }

    // ─────────────────────────── 备份 ───────────────────────────

    private fun backupDir(ctx: Context): File =
        File(ctx.filesDir, "backups").apply { mkdirs() }

    fun listBackups(ctx: Context): List<File> =
        backupDir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".ttf") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    /** 把当前系统里的主题字体备份到应用私有目录（供以后切回）。 */
    fun backupCurrent(ctx: Context): File? {
        val st = status()
        if (!st.installed) return null
        val md5 = st.md5 ?: return null
        val target = File(backupDir(ctx), "$md5.ttf")
        if (target.exists() && target.length() == st.sizeBytes) return target
        val tmp = File(backupDir(ctx), "$md5.part")
        // 通过 su cat 把文件读出来（应用自己读不到 /data/themes）
        val proc = ProcessBuilder("su", "-c", "cat $FONT_PATH").redirectErrorStream(false).start()
        tmp.outputStream().use { out -> proc.inputStream.copyTo(out, 64 * 1024) }
        proc.waitFor()
        if (proc.exitValue() != 0 || tmp.length() != st.sizeBytes) {
            tmp.delete()
            return null
        }
        return if (tmp.renameTo(target)) target else { tmp.delete(); null }
    }

    /** 删掉某个备份。 */
    fun deleteBackup(file: File) {
        file.delete()
    }

    // ─────────────────────────── 工具 ───────────────────────────

    /** 从 `stat -c %s` 的输出里取字节数（注意不能用 ls -l 猜：第二列是硬链接计数，不是大小）。 */
    private fun parseStat(output: String): Long? = output.trim().lineSequence()
        .mapNotNull { it.trim().toLongOrNull() }
        .firstOrNull()

    fun md5Hex(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("MD5").digest(bytes)
        return d.joinToString("") { "%02x".format(it) }
    }

    fun md5Hex(file: File): String = md5Hex(file.readBytes())
}
