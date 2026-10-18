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
    val snackbar = remember { SnackbarHostState() }

    var status by remember { mutableStateOf<FontInstaller.Status?>(null) }
    /** null = 正在检测；false = 没有 root；true = 可用 */
    var rootOk by remember { mutableStateOf<Boolean?>(null) }
    // busy/log 由 WorkState 持有：热重载会重建 Activity，任务本身不能跟着死
    val busy = WorkState.busy
    var pickedPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pickedName by rememberSaveable { mutableStateOf("") }
    var picked by remember { mutableStateOf<PickedFont?>(null) }
    var previewText by rememberSaveable {
        mutableStateOf("字体预览 Font Preview 0123\n中文与 English 混排，多字重测试：横竖撇捺")
    }

    suspend fun refresh() {
        val ok = withContext(Dispatchers.IO) {
            val r = RootShell.run("id", timeoutMs = 15_000L)
            r.ok && r.output.contains("uid=0")
        }
        rootOk = ok
        status = if (ok) {
            withContext(Dispatchers.IO) { runCatching { FontInstaller.status() }.getOrNull() }
        } else {
            null
        }
    }

    LaunchedEffect(Unit) { refresh() }

    // 任务结束后自动刷新；失败原因用底部提示条弹出，不再占用界面空间
    LaunchedEffect(WorkState.finishedTick) {
        val tick = WorkState.finishedTick
        if (tick == 0) return@LaunchedEffect
        refresh()
        if (!WorkState.lastOk) {
            snackbar.showSnackbar(WorkState.lastMessage.ifBlank { "操作失败" })
        }
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
                snackbar.showSnackbar("无法读取该文件，请确认选择的是 .ttf / .otf 字体")
            } else {
                picked = p
                pickedPath = p.file.absolutePath
                pickedName = p.name
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            Spacer(Modifier.height(16.dp))
            Text(
                "系统字体更改",
                fontSize = 26.sp,
                style = MaterialTheme.typography.headlineMedium,
            )

            Spacer(Modifier.height(12.dp))

            // ── 无 root 横幅 ──
            if (rootOk != true) {
                RootBanner(
                    checking = rootOk == null,
                    onRefresh = { scope.launch { refresh() } },
                )
                Spacer(Modifier.height(14.dp))
            }

            // ── 当前状态 ──
            if (rootOk == true) {
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
                        }
                        InfoRow("软链 /data/skin", if (st.skinLinkOk) "正常" else "缺失")
                        InfoRow(
                            "已加载进程",
                            "${st.loadedProcesses} 个",
                            warn = st.installed && st.loadedProcesses == 0,
                        )
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
            }

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
                    textStyle = MaterialTheme.typography.bodyLarge,
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
                    // 字号与上面输入框一致（bodyLarge = 16sp / 24sp），只换字形
                    Text(
                        text = previewText.ifBlank { "（无文本）" },
                        fontSize = 16.sp,
                        lineHeight = 24.sp,
                        fontFamily = tf?.let { FontFamily(it) } ?: FontFamily.Default,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── 应用 / 还原 ──
            Button(
                onClick = {
                    val f = picked ?: return@Button
                    WorkState.submit("应用字体…") {
                        FontInstaller.apply(f.file, FontInstaller.RESTART_HOT) { msg ->
                            WorkState.step(msg)
                        }
                    }
                },
                enabled = !busy && picked != null && rootOk == true,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("应用")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    WorkState.submit("还原默认字体…") {
                        FontInstaller.restoreDefault { msg -> WorkState.step(msg) }
                    }
                },
                enabled = !busy && rootOk == true,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("还原") }

            Spacer(Modifier.height(24.dp))
        }

        SnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        )
    }
}

/** 没有 root / 正在检测 root 时，标题下方的横幅。 */
@Composable
private fun RootBanner(checking: Boolean, onRefresh: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF3A2A12))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (checking) {
                CircularProgressIndicator(
                    Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = Color(0xFFFFB74D),
                )
            } else {
                Text("⚠", fontSize = 16.sp, color = Color(0xFFFFB74D))
            }
            Spacer(Modifier.width(10.dp))
            Text(
                if (checking) "正在检测 root 权限…" else "未获取 root 权限",
                fontSize = 14.sp,
                color = Color(0xFFFFCC80),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = onRefresh,
                enabled = !checking,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text("刷新", fontSize = 13.sp, color = Color(0xFFFFB74D))
            }
        }
        if (!checking) {
            Spacer(Modifier.height(6.dp))
            Text(
                "请在 root 管理器里给本应用授权，然后点右上角刷新。",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = Color(0xFFBFA98A),
            )
        }
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
private fun Hint(text: String) {
    Spacer(Modifier.height(6.dp))
    Text(
        text,
        fontSize = 11.sp,
        color = Color(0xFFFFB74D),
    )
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
