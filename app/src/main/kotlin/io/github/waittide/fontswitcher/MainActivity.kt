package io.github.waittide.fontswitcher

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.waittide.fontswitcher.core.FontInstaller
import io.github.waittide.fontswitcher.core.RootShell
import io.github.waittide.fontswitcher.core.SfntInfo
import io.github.waittide.fontswitcher.core.WorkState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 热重载需要用 APK 自身当 CLASSPATH 拉起 app_process
        FontInstaller.apkPath = applicationInfo.sourceDir
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppScreen()
                }
            }
        }
    }
}

private data class PickedFont(
    val file: File,
    val name: String,
    val size: Long,
    val info: SfntInfo?,
    val typeface: Typeface?,
)

@Composable
private fun AppScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf<FontInstaller.Status?>(null) }
    // busy/log 由 WorkState 持有：热重载会重建 Activity，任务本身不能跟着死
    val busy = WorkState.busy
    val log = WorkState.log
    var pickedPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pickedName by rememberSaveable { mutableStateOf("") }
    var picked by remember { mutableStateOf<PickedFont?>(null) }
    var previewText by rememberSaveable {
        mutableStateOf("字体预览 Font Preview 0123\n中文与 English 混排，多字重测试：横竖撇捺")
    }
    var restartMode by rememberSaveable { mutableStateOf(FontInstaller.RESTART_HOT) }

    suspend fun refresh() {
        status = withContext(Dispatchers.IO) { runCatching { FontInstaller.status() }.getOrNull() }
    }

    LaunchedEffect(Unit) { refresh() }
    // 任务结束（含被 Activity 重建打断后的重建）自动刷新一次状态
    LaunchedEffect(WorkState.finishedTick) {
        if (WorkState.finishedTick > 0) refresh()
    }

    // Activity 重建后从 cacheDir 里的副本重新解析（Typeface 不可序列化）
    LaunchedEffect(pickedPath) {
        val p = pickedPath ?: return@LaunchedEffect
        if (picked != null) return@LaunchedEffect
        picked = withContext(Dispatchers.IO) { loadPickedFile(File(p), pickedName) }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val p = withContext(Dispatchers.IO) { loadPicked(ctx, uri) }
            if (p == null) {
                WorkState.submit("", reset = true) { "无法读取该文件，请确认选择的是 .ttf / .otf 字体" }
            } else {
                picked = p
                pickedPath = p.file.absolutePath
                pickedName = p.name
                WorkState.submit("", reset = true) { "已选择：${p.name}" }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Spacer(Modifier.height(12.dp))
        Text(
            "FontSwitcher",
            fontSize = 26.sp,
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            "HarmonyOS 全局主题字体切换器（需要 root）",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))

        // ── 当前状态 ──
        SectionCard("当前状态") {
            val st = status
            if (st == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("读取中…", fontSize = 13.sp)
                }
            } else {
                InfoRow("主题字体", if (st.installed) "已安装" else "未安装（系统默认）")
                if (st.installed) {
                    InfoRow("大小", "%.1f MB".format(st.sizeBytes / 1048576.0))
                    st.md5?.let { InfoRow("MD5", it) }
                    InfoRow(
                        "SELinux 标签",
                        st.label ?: "读不到",
                        warn = st.label != FontInstaller.SELINUX_LABEL,
                    )
                    st.owner?.let { InfoRow("属主/权限", "$it  ${st.mode ?: ""}") }
                }
                InfoRow("软链 /data/skin", if (st.skinLinkOk) "正常 → /data/themes/0" else "缺失")
                InfoRow(
                    "已加载进程",
                    "${st.loadedProcesses} 个",
                    warn = st.installed && st.loadedProcesses == 0,
                )
                if (st.installed && st.loadedProcesses == 0) {
                    Hint("有文件但没有进程加载 ⇒ SELinux 标签不对。点下方「修复标签」即可。")
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { scope.launch { refresh() } },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("刷新") }
                OutlinedButton(
                    onClick = {
                        WorkState.submit("修复 SELinux 标签…") {
                            RootShell.run(
                                "chcon ${FontInstaller.SELINUX_LABEL} ${FontInstaller.FONT_DIR} " +
                                    "${FontInstaller.FONT_PATH} 2>/dev/null; " +
                                    "chown system:media_rw ${FontInstaller.FONT_PATH} 2>/dev/null; " +
                                    "chmod 664 ${FontInstaller.FONT_PATH} 2>/dev/null; echo done"
                            ).output.ifBlank { "已执行" }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("修复标签") }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ── 选择字体 ──
        SectionCard("选择字体") {
            if (picked == null) {
                Text(
                    "未选择文件。支持 .ttf / .otf / .ttc。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val p = picked!!
                InfoRow("文件", p.name)
                InfoRow("大小", "%.1f MB".format(p.size / 1048576.0))
                if (p.info != null) {
                    InfoRow("字形名", p.info.familyName ?: p.info.fullName ?: "—")
                    InfoRow("格式", p.info.version)
                    p.info.numGlyphs?.let { InfoRow("字形数", it.toString()) }
                } else {
                    Hint("这个文件可能不是有效字体，仍可尝试应用。")
                }
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { picker.launch(arrayOf("*/*")) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (picked == null) "选择字体文件" else "重新选择") }
        }

        Spacer(Modifier.height(14.dp))

        // ── 预览 ──
        SectionCard("预览") {
            OutlinedTextField(
                value = previewText,
                onValueChange = { previewText = it },
                label = { Text("预览文本") },
                minLines = 2,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            val tf = picked?.typeface
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(12.dp)
            ) {
                Text(
                    text = previewText.ifBlank { "（无文本）" },
                    fontSize = 20.sp,
                    lineHeight = 30.sp,
                    fontFamily = tf?.let { FontFamily(it) } ?: FontFamily.Default,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "常规 / 粗体 / 斜体对照",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    previewText.lineSequence().firstOrNull() ?: "",
                    fontSize = 18.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    fontFamily = tf?.let { FontFamily(it) } ?: FontFamily.Default,
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // ── 应用 ──
        SectionCard("应用") {
            Text("生效方式", fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            ModeOption(
                title = "热重载（推荐）",
                sub = "不重启任何进程、无黑屏，约 1 秒；15–20 个进程立即换字",
                selected = restartMode == FontInstaller.RESTART_HOT,
                onClick = { restartMode = FontInstaller.RESTART_HOT },
            )
            ModeOption(
                title = "重启系统界面",
                sub = "黑屏约 0.4 秒；只有状态栏/桌面/新开的 app 换字",
                selected = restartMode == FontInstaller.RESTART_SYSTEMUI,
                onClick = { restartMode = FontInstaller.RESTART_SYSTEMUI },
            )
            ModeOption(
                title = "软重启 zygote（备用）",
                sub = "约 10 秒，全部 app 立即换字；已打开的应用会被关闭",
                selected = restartMode == FontInstaller.RESTART_ZYGOTE,
                onClick = { restartMode = FontInstaller.RESTART_ZYGOTE },
            )
            if (restartMode == FontInstaller.RESTART_ZYGOTE) {
                Hint("软重启会让所有应用重新启动，未保存的工作可能丢失。")
            }
            if (restartMode == FontInstaller.RESTART_HOT) {
                Hint("热重载不可用时会自动回落到「重启系统界面」。")
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    val f = picked ?: return@Button
                    WorkState.submit("开始安装…") {
                        FontInstaller.apply(f.file, restartMode) { msg -> WorkState.step(msg) }
                    }
                },
                enabled = !busy && picked != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("应用为系统字体")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    WorkState.submit("还原默认字体…") {
                        FontInstaller.restoreDefault { msg -> WorkState.step(msg) }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("还原系统默认字体") }
        }

        Spacer(Modifier.height(14.dp))

        // ── 日志 ──
        if (log.isNotBlank()) {
            SectionCard("操作日志") {
                Text(
                    log,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(14.dp))
        }

        // ── 说明 ──
        SectionCard("说明") {
            Bullet("只会修改 /data/themes/0/fonts/DroidSansChinese.ttf 这一个文件。")
            Bullet("不修改 /system、不修改 fonts.xml、不安装 Magisk 模块，因此不会导致无法开机。")
            Bullet("出问题时点「还原系统默认字体」，或直接删除上面那个文件后重启。")
            Bullet("字体是全局的：无法只给某一个 app 换字体（那需要 LSPosed 注入进程）。")
            Bullet("已打开的应用若没换字，重启该应用即可。")
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp)
    ) {
        Text(title, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun InfoRow(label: String, value: String, warn: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            fontSize = 12.sp,
            color = if (warn) Color(0xFFFFB74D) else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.6f),
        )
    }
}

@Composable
private fun ModeOption(
    title: String,
    sub: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp)
            Text(
                sub,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Spacer(Modifier.height(6.dp))
    Text(
        text,
        fontSize = 11.sp,
        color = Color(0xFFFFB74D),
    )
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text("· ", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

private fun loadPicked(ctx: Context, uri: Uri): PickedFont? {
    return try {
        val name = queryName(ctx, uri) ?: uri.lastPathSegment ?: "未命名.ttf"
        // 复制到固定文件名：Activity 重建后要靠这个副本恢复状态
        val dst = File(ctx.cacheDir, "picked.ttf")
        ctx.contentResolver.openInputStream(uri)!!.use { input ->
            dst.outputStream().use { input.copyTo(it) }
        }
        loadPickedFile(dst, name)
    } catch (_: Throwable) {
        null
    }
}

private fun loadPickedFile(file: File, name: String): PickedFont? {
    if (!file.isFile || file.length() == 0L) return null
    val info = SfntInfo.parse(file)
    val typeface = runCatching {
        Typeface.createFromFile(file)
    }.getOrNull() ?: runCatching {
        android.graphics.Typeface.Builder(file).build()
    }.getOrNull()
    return PickedFont(file, name.ifBlank { file.name }, file.length(), info, typeface)
}

private fun queryName(ctx: Context, uri: Uri): String? {
    return runCatching {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()
}
