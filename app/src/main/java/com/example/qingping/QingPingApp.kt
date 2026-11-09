package com.example.qingping

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.room.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

// ============================================================================
// 1. 数据持久层 (Room Database)：按 APP 包名统计开屏跳过次数与误触跳转拦截次数
// ============================================================================

@Entity(tableName = "app_intercept_stats")
data class AppInterceptStat(
    @PrimaryKey val packageName: String,
    val appName: String,
    val splashSkipCount: Int = 0,
    val jumpBlockCount: Int = 0,
    val lastInterceptTime: Long = 0L
)

@Entity(tableName = "intercept_logs")
data class InterceptLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appName: String,
    val type: String, // "SPLASH_SKIP" 或 "JUMP_BLOCK"
    val detail: String,
    val timestamp: Long
)

@Dao
interface InterceptDao {
    @Query("SELECT * FROM app_intercept_stats ORDER BY (splashSkipCount + jumpBlockCount) DESC")
    fun observeAllStats(): Flow<List<AppInterceptStat>>

    @Query("SELECT * FROM intercept_logs ORDER BY timestamp DESC LIMIT 100")
    fun observeRecentLogs(): Flow<List<InterceptLog>>

    @Query("SELECT * FROM app_intercept_stats WHERE packageName = :pkg LIMIT 1")
    suspend fun getStatByPackage(pkg: String): AppInterceptStat?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStat(stat: AppInterceptStat)

    @Insert
    suspend fun insertLog(log: InterceptLog)

    @Transaction
    suspend fun recordInterception(
        packageName: String,
        appName: String,
        isSplashSkip: Boolean,
        detail: String
    ) {
        val now = System.currentTimeMillis()
        val current = getStatByPackage(packageName) ?: AppInterceptStat(
            packageName = packageName,
            appName = appName
        )
        val updated = if (isSplashSkip) {
            current.copy(
                appName = appName,
                splashSkipCount = current.splashSkipCount + 1,
                lastInterceptTime = now
            )
        } else {
            current.copy(
                appName = appName,
                jumpBlockCount = current.jumpBlockCount + 1,
                lastInterceptTime = now
            )
        }
        upsertStat(updated)
        insertLog(
            InterceptLog(
                packageName = packageName,
                appName = appName,
                type = if (isSplashSkip) "开屏跳过" else "误触拦截",
                detail = detail,
                timestamp = now
            )
        )
    }

    @Query("DELETE FROM app_intercept_stats")
    suspend fun clearStats()

    @Query("DELETE FROM intercept_logs")
    suspend fun clearLogs()
}

@Database(entities = [AppInterceptStat::class, InterceptLog::class], version = 1, exportSchema = false)
abstract class QingPingDatabase : RoomDatabase() {
    abstract fun interceptDao(): InterceptDao

    companion object {
        @Volatile
        private var INSTANCE: QingPingDatabase? = null

        fun getInstance(context: Context): QingPingDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    QingPingDatabase::class.java,
                    "qingping_stats.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}

// ============================================================================
// 2. 核心无障碍服务 (AccessibilityService)：开屏广告自动跳过 + 摇一摇/误触跨应用跳转拦截
// ============================================================================

class QingPingAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: QingPingDatabase

    // 防抖冷却表：避免同一次开屏或跳转重复点击/统计 (packageName -> timestamp)
    private val splashCooldownMap = ConcurrentHashMap<String, Long>()
    private val jumpCooldownMap = ConcurrentHashMap<String, Long>()

    // 记录当前前台应用包名与用户最后一次主动触摸屏幕的时间戳
    @Volatile
    private var currentForegroundPkg: String = ""
    @Volatile
    private var lastAppSwitchTime: Long = 0L
    @Volatile
    private var lastUserTouchTime: Long = 0L

    // 白名单包名（系统桌面、系统UI、本应用、常见授权/支付组件不拦截）
    private val systemWhitelist = setOf(
        "com.android.systemui",
        "com.android.settings",
        "com.example.qingping",
        "com.tencent.mm",          // 微信支付/登录按需配置
        "com.eg.android.AlipayGphone"
    )

    // 常见购物/广告落地页目标包名（常被“摇一摇”或误触唤起）
    private val adTargetPackages = setOf(
        "com.taobao.taobao",
        "com.jingdong.app.mall",
        "com.xunmeng.pinduoduo",
        "com.sankuai.meituan",
        "com.ss.android.ugc.aweme",
        "com.kuaishou.nebula",
        "com.achievo.vipshop",
        "com.suning.mobile.ebuy"
    )

    // 开屏跳过按钮精确匹配正则（匹配 "跳过", "跳过 5", "跳过广告", "3s 跳过" 等，最大长度 <= 10）
    private val skipTextRegex = Regex("^(?:\d+\s*[sS秒]?\s*)?跳过(?:\s*广告)?(?:\s*\d+\s*[sS秒]?)?$")

    // 常见广告 SDK 跳过控件 ViewId 关键字
    private val knownSkipViewIdKeywords = listOf(
        "skip_view", "skip_button", "btn_skip", "tv_skip",
        "tt_splash_skip_btn", "ksad_splash_circle_skip_view",
        "beizi_ad_splash_skip", "gdt_splash_skip"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        db = QingPingDatabase.getInstance(applicationContext)

        // 启动前台常驻通知服务，提升进程优先级
        KeepAliveForegroundService.start(applicationContext)

        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_START
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        info.notificationTimeout = 80L
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        val now = SystemClock.elapsedRealtime()

        // 1. 记录用户主动点击/触摸事件，用于区分“主动打开”与“摇一摇/陀螺仪无触摸误触跳转”
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            event.eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START
        ) {
            lastUserTouchTime = now
            return
        }

        if (pkg in systemWhitelist || isLauncherPackage(pkg)) {
            currentForegroundPkg = pkg
            return
        }

        // 2. 检测跨应用跳转（误触/摇一摇广告跳转拦截）
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (currentForegroundPkg.isNotEmpty() &&
                currentForegroundPkg != pkg &&
                !isLauncherPackage(currentForegroundPkg)
            ) {
                val timeSinceLastSwitch = now - lastAppSwitchTime
                val timeSinceLastTouch = now - lastUserTouchTime

                // 判定条件：
                // (a) 从一个非桌面 APP 突然跳入常见电商/广告目标 APP，且刚刚处于开屏窗口期（< 6秒）
                // (b) 且用户在最近 1.2 秒内没有任何主动点击行为（典型摇一摇/陀螺仪触发跳转）
                if (pkg in adTargetPackages && timeSinceLastSwitch < 6000L && timeSinceLastTouch > 1200L) {
                    val lastJump = jumpCooldownMap[currentForegroundPkg] ?: 0L
                    if (now - lastJump > 3000L) {
                        jumpCooldownMap[currentForegroundPkg] = now
                        // 立即执行全局返回，退回原应用
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        recordStatAsync(
                            packageName = currentForegroundPkg,
                            isSplashSkip = false,
                            detail = "拦截无触摸自动跳转至 ${ resolveAppName(pkg) }"
                        )
                        return
                    }
                }
            }
            currentForegroundPkg = pkg
            lastAppSwitchTime = now
        }

        // 3. 扫描并自动点击开屏广告“跳过”按钮（仅在应用切换后的前 8 秒开屏窗口期内高频扫描，省电且防误触正文）
        if (now - lastAppSwitchTime <= 8000L) {
            val lastSkip = splashCooldownMap[pkg] ?: 0L
            if (now - lastSkip < 2500L) return

            val rootNode = rootInActiveWindow ?: return
            val matchedDetail = findAndClickSkipNode(rootNode)
            if (matchedDetail != null) {
                splashCooldownMap[pkg] = now
                recordStatAsync(
                    packageName = pkg,
                    isSplashSkip = true,
                    detail = matchedDetail
                )
            }
        }
    }

    /**
     * 递归遍历无障碍节点树，结合文本正则、ViewId 特征与屏幕物理坐标范围定位开屏“跳过”按钮
     */
    private fun findAndClickSkipNode(root: AccessibilityNodeInfo): String? {
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isVisibleToUser) {
                val text = node.text?.toString()?.trim().orEmpty()
                val desc = node.contentDescription?.toString()?.trim().orEmpty()
                val viewId = node.viewIdResourceName?.lowercase().orEmpty()

                val isTextMatch = (text.length in 2..10 && skipTextRegex.matches(text)) ||
                        (desc.length in 2..10 && skipTextRegex.matches(desc))
                val isIdMatch = knownSkipViewIdKeywords.any { viewId.contains(it) }

                if (isTextMatch || isIdMatch) {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    // 校验控件面积与位置（排除正文大段落，开屏跳过按钮通常位于右上角或右下角，宽高小于屏幕 40%）
                    val validSize = rect.width() in 20..(screenW * 0.45).toInt() &&
                            rect.height() in 15..(screenH * 0.15).toInt()
                    if (validSize && performClickOrParentClick(node)) {
                        val label = if (text.isNotEmpty()) text else if (desc.isNotEmpty()) desc else viewId
                        return "自动点击跳过控件 [$label]"
                    }
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun performClickOrParentClick(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 4) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
            depth++
        }
        return false
    }

    private fun isLauncherPackage(pkg: String): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolveInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolveInfo?.activityInfo?.packageName == pkg
    }

    private fun resolveAppName(pkg: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    private fun recordStatAsync(packageName: String, isSplashSkip: Boolean, detail: String) {
        val appName = resolveAppName(packageName)
        serviceScope.launch {
            db.interceptDao().recordInterception(
                packageName = packageName,
                appName = appName,
                isSplashSkip = isSplashSkip,
                detail = detail
            )
        }
    }

    override fun onInterrupt() {}
}

// ============================================================================
// 3. 保活与开机自启模块：前台常驻通知服务 + 开机广播接收器
// ============================================================================

class KeepAliveForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()
        // START_STICKY：当服务被系统因内存不足回收后，尝试在资源充裕时重建
        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        val channelId = "qingping_guard_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "清屏后台守护服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持无障碍广告拦截服务在后台稳定运行"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = builder
            .setContentTitle("清屏正在守护中")
            .setContentText("实时拦截开屏广告与误触跳转")
            .setSmallIcon(android.R.drawable.ic_secure)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(1001, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, KeepAliveForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
                // Android 12+ 后台启动前台服务受限时，由无障碍服务绑定或用户前台打开时拉起
            }
        }
    }
}

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED ||
            intent?.action == Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            KeepAliveForegroundService.start(context)
        }
    }
}

// ============================================================================
// 4. Jetpack Compose 交互界面：权限状态自检、保活引导、按 APP 统计看板与实时日志
// ============================================================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KeepAliveForegroundService.start(this)
        val db = QingPingDatabase.getInstance(this)

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF0F766E),
                    secondary = Color(0xFF0D9488),
                    surface = Color(0xFFF8FAFC),
                    background = Color(0xFFF1F5F9)
                )
            ) {
                QingPingHomeScreen(dao = db.interceptDao())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QingPingHomeScreen(dao: InterceptDao) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stats by dao.observeAllStats().collectAsState(initial = emptyList())
    val logs by dao.observeRecentLogs().collectAsState(initial = emptyList())

    var isAccessibilityEnabled by remember { mutableStateOf(checkAccessibilityEnabled(context)) }
    var isBatteryIgnored by remember { mutableStateOf(checkBatteryOptimizationIgnored(context)) }
    var selectedTab by remember { mutableIntStateOf(0) }

    // 每次从系统设置页返回时自动刷新权限状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isAccessibilityEnabled = checkAccessibilityEnabled(context)
                isBatteryIgnored = checkBatteryOptimizationIgnored(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val totalSkip = stats.sumOf { it.splashSkipCount }
    val totalJumpBlock = stats.sumOf { it.jumpBlockCount }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("清屏 · 广告与跳转拦截", fontWeight = FontWeight.Bold) },
                actions = {
                    if (stats.isNotEmpty() || logs.isNotEmpty()) {
                        TextButton(onClick = {
                            scope.launch(Dispatchers.IO) {
                                dao.clearStats()
                                dao.clearLogs()
                            }
                        }) {
                            Text("清空统计", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 16.dp)
        ) {
            // 1. 权限与后台保活状态卡片
            PermissionStatusCard(
                isAccessibilityEnabled = isAccessibilityEnabled,
                isBatteryIgnored = isBatteryIgnored,
                onOpenAccessibility = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
                onRequestBatteryIgnore = {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 2. 累计拦截概览卡片
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SummaryMetricCard(
                    title = "已跳过开屏广告",
                    count = totalSkip,
                    unit = "次",
                    modifier = Modifier.weight(1f)
                )
                SummaryMetricCard(
                    title = "已拦截误触跳转",
                    count = totalJumpBlock,
                    unit = "次",
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 3. 切换标签：按 APP 统计 / 最近拦截记录
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("按 APP 统计 (${stats.size})") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("最近拦截明细 (${logs.size})") }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (selectedTab == 0) {
                if (stats.isEmpty()) {
                    EmptyHintView("暂无拦截数据，开启无障碍服务后将自动统计各 APP 拦截次数")
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        items(stats, key = { it.packageName }) { item ->
                            AppStatRowCard(item)
                        }
                    }
                }
            } else {
                if (logs.isEmpty()) {
                    EmptyHintView("暂无最近拦截明细")
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        items(logs, key = { it.id }) { log ->
                            InterceptLogRowCard(log)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionStatusCard(
    isAccessibilityEnabled: Boolean,
    isBatteryIgnored: Boolean,
    onOpenAccessibility: () -> Unit,
    onRequestBatteryIgnore: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isAccessibilityEnabled) "无障碍拦截服务：运行中" else "无障碍拦截服务：未开启",
                        fontWeight = FontWeight.SemiBold,
                        color = if (isAccessibilityEnabled) Color(0xFF0F766E) else Color(0xFFB91C1C),
                        fontSize = 15.sp
                    )
                    Text(
                        text = "用于识别开屏“跳过”按钮及拦截摇一摇跨应用跳转",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
                Button(onClick = onOpenAccessibility) {
                    Text(if (isAccessibilityEnabled) "已开启" else "去开启")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isBatteryIgnored) "电池优化白名单：已加入" else "电池优化白名单：未加入",
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "建议同时在系统多任务界面为“清屏”加锁并开启自启动",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
                OutlinedButton(onClick = onRequestBatteryIgnore, enabled = !isBatteryIgnored) {
                    Text(if (isBatteryIgnored) "已豁免" else "加入白名单")
                }
            }
        }
    }
}

@Composable
fun SummaryMetricCard(title: String, count: Int, unit: String, modifier: Modifier = Modifier) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, fontSize = 13.sp, color = Color.Gray)
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = count.toString(),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0F766E)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = unit, fontSize = 13.sp, color = Color.Gray)
            }
        }
    }
}

@Composable
fun AppStatRowCard(stat: AppInterceptStat) {
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = stat.appName, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(text = stat.packageName, fontSize = 11.sp, color = Color.Gray)
                if (stat.lastInterceptTime > 0) {
                    Text(
                        text = "最近拦截：${fmt.format(Date(stat.lastInterceptTime))}",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "开屏跳过 ${stat.splashSkipCount} 次",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF0F766E)
                )
                Text(
                    text = "误触拦截 ${stat.jumpBlockCount} 次",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFD97706)
                )
            }
        }
    }
}

@Composable
fun InterceptLogRowCard(log: InterceptLog) {
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${log.appName} · ${log.type}",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                Text(text = log.detail, fontSize = 12.sp, color = Color.DarkGray)
            }
            Text(
                text = fmt.format(Date(log.timestamp)),
                fontSize = 11.sp,
                color = Color.Gray
            )
        }
    }
}

@Composable
fun EmptyHintView(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = message, fontSize = 13.sp, color = Color.Gray)
    }
}

fun checkAccessibilityEnabled(context: Context): Boolean {
    val expected = "${context.packageName}/${QingPingAccessibilityService::class.java.canonicalName}"
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
}

fun checkBatteryOptimizationIgnored(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}
