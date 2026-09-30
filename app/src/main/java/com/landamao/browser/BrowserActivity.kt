package com.landamao.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.Manifest
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.TextUtils
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.util.TypedValue
import android.view.ViewGroup
import android.view.Gravity
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.util.Base64
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.PopupWindow
import android.widget.ScrollView
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.TextView
import android.widget.BaseAdapter
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.AppCompatSpinner
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 轻量多标签浏览器(自 ldmbot 内置浏览器独立而来):
 * - 工具栏外露:后退 / 前进 / 地址栏 / 刷新(加载中变停止X) / 菜单
 * - 地址栏框内:左侧选搜索引擎(百度/Bing/Google,持久化),右侧动作按钮图标随输入
 *   自动切换(像网址=→前往,否则=🔍搜索,与主流浏览器一致);键盘回车同样智能判断;
 *   引擎/动作按钮只在编辑态显示,点输入框以外收起键盘并退出编辑态
 * - 标签页:长按弹菜单(锁定/重命名/关闭);锁定后当前标签 × 变锁图标,
 *   点锁只提示,长按菜单「解锁」后才能点 × 关闭(菜单「关闭」不受锁限制)
 * - 标签栏可隐藏:长按「菜单」/「全部标签页」按钮直接隐藏/显示(不走菜单),
 *   藏起后长按「菜单」按钮唤回,状态持久化;
 *   藏起时地址栏浏览态显示页面标题,编辑态才换回网址(导航途中仍先显示网址)
 * - 重命名:标题固定不再跟随网页;再次重命名清空内容确定即恢复跟随
 * - 排序:「全部标签页」列表里按住条目左缘三杠上下拖动换位,松手即保存
 * - 菜单内:主页(长按可设置自定义主页)、收藏、历史记录、退出前保存开关、设置、更多(最后一项)
 * - 更多弹窗:强制刷新、在其他浏览器打开、电脑模式、关于(版本号点击复制,
 *   点 GitHub 仓库收起弹窗并在本应用新开标签页打开);点条目执行并自动关闭弹窗
 * - 历史记录:以标签页方式打开(标题+网址居左,时间居右,点条目本标签打开该站);
 *   每次访问自动记录,清除数据的「浏览历史」与「仅当前网站」会同步清理;
 *   长按条目:删除 / 复制链接 / 在新标签页打开 / 多选模式(底部工具栏可全选、批量删除)
 *   顶部搜索框按标题/网址实时过滤(多选的「全选」只选可见条目)
 * - 收藏:地址栏框内右侧星星,仅浏览态显示(编辑态让位给引擎/动作按钮),
 *   已收藏=黄色实心,未收藏=空心,点按收藏/取消当前页;
 *   菜单「收藏」打开列表页(顶部搜索框按标题/网址过滤;点条目本标签打开,
 *   长按删除/复制链接/在新标签页打开)
 * - 退出前保存(默认开):导航/增删/切换时实时保存 + 退出时再落一份最终状态,内容涵盖
 *   标签页顺序、当前标签、重命名、锁定、前进后退历史;关闭后退出清档,下次打开为空白/主页
 * - 前进后退走自管历史(每标签一份 url 列表,不用 WebView 原生历史):
 *   自管历史才能随会话持久化,重启后仍能按原顺序前进后退
 * - 设置弹窗:清除数据(四项数据范围勾选 + 「仅当前网站」作用域开关)、
 *   标签页和网址栏设置(二级弹窗里调换位置/上下分布)、下载设置(二级弹窗里
 *   下载方式/下载目录下拉选择);点功能不关弹窗,弹窗逐层叠加,点空白处从最上层开始一层一层关闭
 * - 自定义主页:标题 + 链接;主页动作与新标签页都用它,未设置时不自动新建标签页,
 *   空状态下直接在地址栏输入网址即可新建标签页
 * - 电脑模式:桌面 UA + 强制宽布局视口(约 1280)+ 可缩放,让站点走桌面排版
 * - 刷新注意:加载失败不往 WebView 塞 data: 错误页(那会让 reload() 永远重放错误页),
 *   只显示原生覆盖层,WebView 里始终是真实地址,普通刷新即可恢复
 * - 网页里的文件选择(<input type=file>)走系统文件选择器
 * - 网页请求唤起外部 App:先在屏幕下方弹确认,允许才跳转
 * - 文本编辑悬浮窗:单实例;内容自动保存,「保存」唤起系统文件保存器导出 txt,
 *   「提取链接」抓出正文网址列表(每条可复制 / 在新标签页访问);
 *   快捷输入条背景跟随面板透明度;长按面板空白边缘进入调节状态 —— 半透明黑覆盖 +
 *   白色边框线,拖边框实时改窗口大小(完成保存,取消还原)
 * - 内置下载器(LdmDownloader.kt,自研):网页请求下载文件不再跳系统浏览器。
 *   下载方式可在设置的「下载设置」里下拉选「内置下载器 / 系统下载器」(默认内置):
 *   内置 = 自研 HttpURLConnection 引擎,断点续传(Range+If-Range 校验)、最多
 *   3 个并发排队、网络错误自动重试、进度条+速度,存到设置的下载目录(默认公共
 *   「下载」目录,可选自定义目录),进程被杀标为已暂停可继续,通知栏进度/完成;
 *   系统 = 系统 DownloadManager(带 Cookie/UA/Referer,固定存公共「下载」目录)
 *   blob:/data: 没有可下载地址,由页面 JS 分片转 base64 过 JS 桥原生落盘,
 *   同样存到设置的下载目录(Android 10+ 入 MediaStore「下载」,旧版入应用专属目录);
 *   菜单「下载管理」开列表页:点条目已完成唤起「打开方式」,长按暂停/继续/
 *   打开/复制来源链接/复制文件路径/删除,进度实时刷新
 * - 注册了 ACTION_VIEW http/https,可作为系统「打开方式」里的浏览器
 */
class BrowserActivity : AppCompatActivity() {

    data class TabItem(
        var title: String,
        /** 最近一次可再次打开的地址(http/https/file),禁止写成 about:blank / data: */
        var url: String,
        val webView: WebView,
        /** 是否处于加载失败状态(覆盖层可见,刷新时必须 loadUrl 原地址而不是 reload) */
        var showingErrorPage: Boolean = false,
        /** 失败原因(WebResourceError.description) */
        var errorDetail: String = "",
        /** 是否正在加载(刷新按钮此时显示为 X 停止按钮) */
        var isLoading: Boolean = false,
        /** 已锁定:点 × 只提示,需长按菜单「解锁」;长按菜单里的「关闭」不受限 */
        var locked: Boolean = false,
        /** 用户重命名的固定标题;null = 跟随网页标题 */
        var customTitle: String? = null,
        /** 自管前进后退历史(当前项由 [historyIndex] 指向),只存可恢复的 http/https/file 地址 */
        val history: MutableList<String> = mutableListOf(url),
        /** 自管历史当前项下标;新标签 = 0 */
        var historyIndex: Int = 0
    )

    private lateinit var tabLayout: TabLayout
    /** 标签栏容器(含右缘「全部标签页」按钮):布局调换时整体挪动 */
    private lateinit var tabBarWrap: View
    private lateinit var webContainer: FrameLayout
    private lateinit var addressBar: EditText
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnRefresh: ImageButton
    private lateinit var btnMenu: ImageButton
    private lateinit var btnAction: ImageButton
    private lateinit var btnEngine: TextView
    /** 地址栏框内右侧收藏星(仅浏览态显示):已收藏=黄色实心,未收藏=空心 */
    private lateinit var btnBookmark: ImageButton
    /** 地址栏圆角容器(引擎/动作按钮的宿主,判断「点按是否在输入框内」用) */
    private lateinit var addressBox: View
    private lateinit var browserEmpty: View
    private lateinit var progressBar: android.widget.ProgressBar
    private lateinit var errorOverlay: LinearLayout
    private lateinit var errorUrlText: TextView
    private lateinit var errorReasonText: TextView
    /** 标签栏末尾的「+」新建标签项 */
    private var plusTab: TabLayout.Tab? = null
    private val tabs = mutableListOf<TabItem>()
    private var currentIndex = 0

    /** 标签页选择监听:重建标签栏时先摘下再挂回,避免重建中的自动选中误触发 showTab */
    private val tabSelectedListener = object : TabLayout.OnTabSelectedListener {
        override fun onTabSelected(tab: TabLayout.Tab) {
            showTab(tab.position)
        }
        override fun onTabUnselected(tab: TabLayout.Tab) {}
        override fun onTabReselected(tab: TabLayout.Tab) {}
    }

    /** 网页文件选择:挂起中的 WebView 回调(必须 always onReceiveValue,否则后续选择不再弹出) */
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = fileChooserCallback
            fileChooserCallback = null
            callback?.onReceiveValue(extractFileChooserUris(result.resultCode, result.data))
        }

    /** 「保存」把笔记导出为 txt:系统文件保存器(SAF CreateDocument),取消不写文件 */
    private val saveNoteLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri == null) return@registerForActivityResult
            val ok = try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(editorBox.text?.toString().orEmpty().toByteArray(Charsets.UTF_8))
                }
                true
            } catch (_: Exception) {
                false
            }
            toast(if (ok) R.string.browser_note_saved_to_file else R.string.browser_note_save_fail)
        }

    /** 电脑模式:桌面 UA + 宽布局视口 + 缩放;全局开关,切标签时同步 */
    private var desktopMode = false
    private var mobileUserAgent: String = ""
    private var desktopUserAgent: String = DESKTOP_UA

    /** 搜索引擎(地址栏框内左侧选择):当前选中的下标,持久化在 prefs */
    private var engineIndex = 0

    /** 退出前保存(菜单开关,默认开):开=实时+退出时落盘完整会话,关=退出清档不恢复 */
    private var saveOnExit = true

    /** 调换布局:标签页在网址栏上方(「标签页和网址栏设置」弹窗切换,持久化);上下分布时决定哪一行贴顶 */
    private var tabsOnTop = false

    /** 上下分布:标签栏与网址栏整行分居屏幕顶/底(「标签页和网址栏设置」弹窗切换,持久化) */
    private var urlBarBottom = false

    /** 标签栏隐藏(长按「菜单」/「全部标签页」切换,持久化;隐藏后省出整行屏幕) */
    private var tabBarHidden = false

    /** 下载设置弹窗里的目录下拉框:目录选择器返回后刷新选项与选中项 */
    private var dlDirSpinner: AppCompatSpinner? = null
    private var dlDirAdapter: ArrayAdapter<String>? = null

    /** 全部标签页弹窗引用(切走/关闭时收起) */
    private var popupRef: PopupWindow? = null

    /** 文本编辑器弹窗与输入框(「保存」/「完成」时落盘) */
    private var textEditorDialog: Dialog? = null
    private lateinit var editorBox: EditText

    /** 唤端确认弹窗引用:开着时忽略后续唤端请求,退出时收掉 */
    private var openAppDialog: AlertDialog? = null

    /** 当前日夜状态(系统自动切换时判断是否需要换肤) */
    private var lastKnownNight = false

    /** 键盘上方快捷输入条:地址栏编辑态显示 */
    private lateinit var inputHelperBar: LinearLayout

    /** 标签页宽度范围 dp(设置弹窗滑块;两值相同 = 固定宽度) */
    private var tabMinWidth = TAB_WIDTH_DEFAULT_MIN
    private var tabMaxWidth = TAB_WIDTH_DEFAULT_MAX

    /** 快捷输入条列数(设置弹窗调整,1–6) */
    private var quickCols = 5

    /** 最近一次触摸 WebView 的时刻:区分「用户点了外链」和「页面 JS 自动唤端」 */
    private var lastTouchTime = 0L

    /** 标题先于页面提交到达时的暂存(提交记录时带上,避免历史条目只剩网址) */
    private val pendingVisitTitles = mutableMapOf<String, String>()

    /** 下载管理页进度观察者(系统下载变化时去抖推送刷新;onCreate 注册,onDestroy 摘除) */
    private var downloadsObserver: ContentObserver? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var downloadsRefreshPending = false
    private val downloadsRefreshRunnable = Runnable {
        downloadsRefreshPending = false
        pushDownloadsData()
    }

    /** 下载方式:内置下载器(自研引擎)或系统下载器(设置弹窗切换,持久化) */
    private var downloadMethod = DL_METHOD_BUILTIN

    /** 自研下载引擎(应用级单例,设置变化回调切回主线程去抖推送下载管理页) */
    private val downloader: LdmDownloader
        get() = LdmDownloader.get(applicationContext)

    /** blob 分片接收中的任务:key → 名称/类型/临时文件/输出流(JavaBridge 线程写,主线程收尾) */
    private val blobTasks = ConcurrentHashMap<String, BlobTask>()

    override fun onCreate(savedInstanceState: Bundle?) {
        // 夜间模式:LdmBrowserApp 已在 Activity attach 前应用,此处兜底重设(相同值时为空操作);
        // 运行中切换不重建,由 reapplyTheme 换肤
        AppCompatDelegate.setDefaultNightMode(
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(PREF_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        )
        super.onCreate(savedInstanceState)
        bindViews()
        lastKnownNight = isNightNow()

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 恢复上次会话(顺序/重命名/锁定/前进后退历史);一个都没有则按主页开一个标签
        val session = loadSession(prefs)
        if (session == null || session.tabs.isEmpty()) {
            val home = homeUrl()
            if (home == null) {
                // 未设置主页:保持空状态,不自动新建标签页,地址栏输入即新建
                showEmptyState()
            } else {
                addTab(homeTitle() ?: shortHost(home), home)
                showTab(0)
            }
        } else {
            session.tabs.forEach { saved ->
                val current = saved.history.getOrNull(saved.historyIndex) ?: saved.url
                val tab = TabItem(
                    saved.title.ifBlank { shortHost(current) },
                    current,
                    createWebView(current)
                )
                tab.customTitle = saved.customTitle
                tab.locked = saved.locked
                tab.history.clear()
                tab.history.addAll(saved.history)
                tab.historyIndex = saved.historyIndex
                // 历史记录/收藏/下载管理页内容不靠 loadUrl,恢复后要主动渲染
                if (current == HISTORY_URL) loadHistoryPageInto(tab)
                if (current == BOOKMARKS_URL) loadBookmarksPageInto(tab)
                if (current == DOWNLOADS_URL) loadDownloadsPageInto(tab)
                addTab(saved.customTitle ?: tab.title, current, tab)
            }
            showTab(session.index.coerceIn(0, tabs.lastIndex))
        }

        // 系统返回键:当前网页能后退(自管历史)则后退,否则退出应用
        // (只在这里注册一次,不随界面外壳重建)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val tab = tabs.getOrNull(currentIndex)
                if (tab != null && tab.historyIndex > 0) {
                    tabGoBack(tab)
                } else {
                    finish()
                }
            }
        })

        // 下载方式设置 + 自研下载引擎回调:引擎状态变化 → 开着的下载管理页去抖刷新
        downloadMethod = prefs.getInt(PREF_DL_METHOD, DL_METHOD_BUILTIN)
        downloader.onUpdate = { mainHandler.post { scheduleDownloadsRefresh() } }

        handleIntent(intent)

        // 系统下载进度变化 → 开着的下载管理页去抖实时刷新
        registerDownloadsObserver()
    }

    /**
     * 构建全部界面(布局 + 视图 + 监听)。夜间模式切换时也会调用:
     * 只重建界面外壳,WebView 与标签状态原样保留,网页不刷新。
     */
    private fun bindViews() {
        setContentView(R.layout.activity_browser)
        tabLayout = findViewById(R.id.browser_tabs)
        tabBarWrap = findViewById(R.id.browser_tab_bar)
        webContainer = findViewById(R.id.web_container)
        addressBar = findViewById(R.id.address_bar)
        btnBack = findViewById(R.id.btn_back)
        btnForward = findViewById(R.id.btn_forward)
        btnRefresh = findViewById(R.id.btn_refresh)
        btnMenu = findViewById(R.id.btn_menu)
        browserEmpty = findViewById(R.id.browser_empty)
        progressBar = findViewById(R.id.browser_progress)
        errorOverlay = findViewById(R.id.browser_error_overlay)
        errorUrlText = findViewById(R.id.browser_error_url)
        errorReasonText = findViewById(R.id.browser_error_reason)
        findViewById<Button>(R.id.btn_error_retry).setOnClickListener { forceRefreshCurrent() }

        // 记录默认移动 UA,供电脑模式切换
        val probe = WebView(this)
        mobileUserAgent = probe.settings.userAgentString ?: ""
        // 桌面 UA 基于系统 WebView 的 Chrome 版本更不易被站点拒绝
        desktopUserAgent = buildDesktopUa(probe.settings.userAgentString)
        probe.destroy()

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        desktopMode = prefs.getBoolean(PREF_DESKTOP, false)
        saveOnExit = prefs.getBoolean(PREF_SAVE_ON_EXIT, true)
        tabsOnTop = prefs.getBoolean(PREF_TABS_ON_TOP, false)
        urlBarBottom = prefs.getBoolean(PREF_URL_BAR_BOTTOM, false)
        tabBarHidden = prefs.getBoolean(PREF_TAB_BAR_HIDDEN, false)
        tabMinWidth = prefs.getInt(PREF_TAB_MIN_WIDTH, TAB_WIDTH_DEFAULT_MIN)
        tabMaxWidth = prefs.getInt(PREF_TAB_MAX_WIDTH, TAB_WIDTH_DEFAULT_MAX)
        quickCols = prefs.getInt(PREF_QUICK_COLS, 5)

        tabLayout.addOnTabSelectedListener(tabSelectedListener)

        // 标签栏宽度变化(首帧布局完成/旋转/折叠/分屏)后按新宽度重排标签:
        // 宽屏摊满的份额依赖实际宽度;post 到布局结束后执行,避免在布局过程中改尺寸
        tabLayout.addOnLayoutChangeListener { _, _, _, right, _, _, _, oldRight, _ ->
            if (right != oldRight) tabLayout.post { renderBrowserTabs() }
        }

        // 布局调换(设置弹窗切换):标签页放到网址栏上方
        applyLayoutOrder()
        // 标签栏隐藏状态(长按「菜单」/「全部标签页」切换):重建外壳后保持
        tabBarWrap.visibility = if (tabBarHidden) View.GONE else View.VISIBLE

        addressBar.setOnEditorActionListener { _, actionId, event ->
            val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP
            if (actionId == EditorInfo.IME_ACTION_GO || isEnter) {
                loadAddress(addressBar.text?.toString().orEmpty())
                true
            } else {
                false
            }
        }

        btnBack.setOnClickListener { tabs.getOrNull(currentIndex)?.let { tabGoBack(it) } }
        btnForward.setOnClickListener { tabs.getOrNull(currentIndex)?.let { tabGoForward(it) } }
        // 工具栏刷新:加载中变成 X(点击停止加载),空闲时是普通刷新(reload,遵循缓存);菜单里是强制刷新
        btnRefresh.setOnClickListener {
            val tab = tabs.getOrNull(currentIndex) ?: return@setOnClickListener
            if (tab.isLoading) {
                stopCurrentLoad()
            } else {
                normalRefreshCurrent()
            }
        }
        btnMenu.setOnClickListener { showBrowserMenu(it) }
        // 长按菜单按钮:直接隐藏/显示标签栏(标签栏藏起后这里也能唤回)
        btnMenu.setOnLongClickListener { toggleTabBar(); true }
        findViewById<View>(R.id.btn_empty_new_tab).setOnClickListener { openNewTab() }
        val allTabsBtn = findViewById<View>(R.id.btn_all_tabs)
        allTabsBtn.setOnClickListener { showAllTabsPopup(it) }
        allTabsBtn.setOnLongClickListener { toggleTabBar(); true }

        // 键盘上方快捷输入条:挂到根布局底部,adjustResize 时正好在键盘上方
        inputHelperBar = newQuickInputContainer()
        fillQuickInputBar(inputHelperBar) { insertIntoEditText(addressBar, it) }
        val rootLayout = findViewById<LinearLayout>(R.id.browser_root)
        rootLayout.addView(
            inputHelperBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // 地址栏框内控件:左引擎选择,右动作按钮(图标随输入内容切换)
        addressBox = findViewById(R.id.address_bar_box)
        btnAction = findViewById(R.id.btn_action)
        btnEngine = findViewById(R.id.btn_engine)
        btnBookmark = findViewById(R.id.btn_bookmark)
        engineIndex = resolveEngineIndex(prefs.getString(PREF_ENGINE, null))
        btnEngine.text = getString(searchEngines[engineIndex].labelRes)
        btnEngine.setOnClickListener { showEnginePopup(it) }
        btnAction.setOnClickListener { performAddressAction() }
        btnBookmark.setOnClickListener { toggleBookmark() }
        // 编辑态(地址栏有焦点)才显示引擎/动作按钮,浏览态收起;
        // 隐藏标签栏时浏览态显示页面标题,进入编辑态换回真实网址(全选方便直接输入)
        addressBar.setOnFocusChangeListener { _, hasFocus ->
            if (tabBarHidden) {
                tabs.getOrNull(currentIndex)?.let { tab ->
                    if (hasFocus) {
                        if (!isHistoryTab(tab) && !isBookmarksTab(tab) && !isDownloadsTab(tab)) {
                            addressBar.setText(tab.url)
                            addressBar.setSelection(0, addressBar.text?.length ?: 0)
                        }
                    } else {
                        syncAddressBrowseText(tab)
                    }
                }
            }
            updateAddressChrome()
        }
        // 输入变化时切换图标:像网址显示 →(前往),否则 🔍(搜索),与主流浏览器一致
        addressBar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateAddressActionIcon()
            }
        })
        updateAddressChrome()
        updateAddressActionIcon()
        updateToolbarState()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 系统级日夜自动切换:只重建界面外壳,WebView 原样保留
        val nightNow =
            (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (nightNow != lastKnownNight && ::inputHelperBar.isInitialized) {
            lastKnownNight = nightNow
            reapplyTheme()
        }
    }

    /** 夜间模式切换后重建界面外壳:WebView 与标签状态原样保留,网页不刷新 */
    private fun reapplyTheme() {
        bindViews()
        if (tabs.isNotEmpty()) {
            rebuildTabLayout()
            showTab(currentIndex.coerceIn(0, tabs.lastIndex))
        } else {
            showEmptyState()
        }
        refreshQuickInputBar()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun openNewTab() {
        val home = homeUrl() ?: run {
            // 未设置主页就不新建标签页:聚焦地址栏,输入网址即新建
            addressBar.requestFocus()
            android.widget.Toast.makeText(
                this, R.string.browser_home_need_setup, android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        addTab(homeTitle() ?: shortHost(home), home)
        showTab(tabs.lastIndex)
    }

    /** 自定义主页地址(菜单「主页」长按设置),未设置或无效时返回 null */
    private fun homeUrl(): String? {
        val url = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_HOME_URL, null) ?: return null
        return url.takeIf { isWebUrl(it) }
    }

    private fun homeTitle(): String? {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_HOME_TITLE, null)?.takeIf { it.isNotBlank() }
    }

    /** 菜单「主页」:点按回自定义主页(未设置则直接打开设置弹窗) */
    private fun goHome() {
        val home = homeUrl()
        if (home == null) {
            showHomeConfigDialog()
            return
        }
        if (tabs.isEmpty()) {
            addTab(homeTitle() ?: shortHost(home), home)
            showTab(tabs.lastIndex)
            return
        }
        val tab = tabs.getOrNull(currentIndex) ?: return
        tab.url = home
        tab.webView.loadUrl(home)
        markLoading(tab)
        addressBar.setText(home)
    }

    /** 长按菜单「主页」:设置自定义主页(标题 + 链接);主页动作与新标签页都会用它 */
    private fun showHomeConfigDialog() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val titleInput = EditText(this).apply {
            hint = getString(R.string.browser_home_config_title_hint)
            setText(prefs.getString(PREF_HOME_TITLE, null))
            setSingleLine(true)
        }
        val urlInput = EditText(this).apply {
            hint = getString(R.string.browser_home_config_url_hint)
            setText(prefs.getString(PREF_HOME_URL, null))
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        box.addView(titleInput)
        box.addView(urlInput)

        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.browser_home_config_title)
            .setView(box)
            .setPositiveButton(R.string.confirm, null)
            .setNegativeButton(R.string.cancel, null)
        if (homeUrl() != null) {
            builder.setNeutralButton(R.string.browser_home_clear) { _, _ ->
                prefs.edit().remove(PREF_HOME_URL).remove(PREF_HOME_TITLE).apply()
                android.widget.Toast.makeText(
                    this, R.string.browser_home_config_cleared, android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
        val dialog = builder.show()
        // 链接为空时不关闭弹窗,提示后继续编辑
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val rawUrl = urlInput.text?.toString()?.trim().orEmpty()
            if (rawUrl.isEmpty()) {
                android.widget.Toast.makeText(
                    this, R.string.browser_home_need_url, android.widget.Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            prefs.edit()
                .putString(PREF_HOME_URL, normalizeInputToUrl(rawUrl))
                .putString(PREF_HOME_TITLE, titleInput.text?.toString()?.trim().orEmpty())
                .apply()
            dialog.dismiss()
            android.widget.Toast.makeText(
                this, R.string.browser_home_config_saved, android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** 一个可恢复标签页的存档(当前页 = history[historyIndex]) */
    private class SavedTab(
        val url: String,
        val title: String,
        val customTitle: String?,
        val locked: Boolean,
        val history: List<String>,
        val historyIndex: Int
    )

    private class SavedSession(val index: Int, val tabs: List<SavedTab>)

    /**
     * 保存标签页状态:「退出前保存」开着才落盘。导航/增删/切换标签时实时写
     * (意外被杀也能恢复),退出时 onDestroy 再落一份最终状态。
     * 内容:顺序、当前标签、标题与重命名、锁定、前进后退历史 —— 应用重新打开时恢复。
     */
    private fun persistTabs() {
        if (saveOnExit) saveSession()
    }

    private fun saveSession() {
        val root = JSONObject()
        root.put("index", if (currentIndex in tabs.indices) currentIndex else 0)
        val arr = JSONArray()
        tabs.forEach { t ->
            val o = JSONObject()
            o.put("url", t.url)
            o.put("title", t.title)
            t.customTitle?.let { o.put("customTitle", it) }
            if (t.locked) o.put("locked", true)
            val history = JSONArray()
            t.history.forEach { if (isWebUrl(it)) history.put(it) }
            o.put("history", history)
            o.put(
                "historyIndex",
                t.historyIndex.coerceIn(0, (history.length() - 1).coerceAtLeast(0))
            )
            arr.put(o)
        }
        root.put("tabs", arr)
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_SAVED_SESSION, root.toString())
            .apply()
    }

    /** 读会话:新格式存完整状态;旧版本只有链接+选中位置,读出即迁移删除旧键 */
    private fun loadSession(prefs: SharedPreferences): SavedSession? {
        val raw = prefs.getString(PREF_SAVED_SESSION, null)
        if (raw != null) {
            return try {
                val root = JSONObject(raw)
                val arr = root.optJSONArray("tabs") ?: return null
                val list = mutableListOf<SavedTab>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val url = o.optString("url")
                    // 历史记录页/收藏页/下载管理页是 about: 虚拟地址,也随会话保存
                    if (
                        url != HISTORY_URL && url != BOOKMARKS_URL &&
                        url != DOWNLOADS_URL && !isWebUrl(url)
                    ) continue
                    val history = mutableListOf<String>()
                    o.optJSONArray("history")?.let { ha ->
                        for (j in 0 until ha.length()) {
                            val u = ha.optString(j)
                            if (isWebUrl(u)) history.add(u)
                        }
                    }
                    if (history.isEmpty()) history.add(url)
                    list.add(
                        SavedTab(
                            url = url,
                            title = o.optString("title"),
                            customTitle = o.optString("customTitle").takeIf { it.isNotEmpty() },
                            locked = o.optBoolean("locked", false),
                            history = history,
                            historyIndex = o.optInt("historyIndex", 0)
                                .coerceIn(0, history.lastIndex)
                        )
                    )
                }
                SavedSession(root.optInt("index", 0), list)
            } catch (_: Exception) {
                null
            }
        }
        val legacy = try {
            JSONArray(prefs.getString(PREF_SAVED_TABS, null).orEmpty())
        } catch (_: Exception) {
            null
        } ?: return null
        val list = mutableListOf<SavedTab>()
        for (i in 0 until legacy.length()) {
            val url = legacy.optString(i)
            if (isWebUrl(url)) list.add(SavedTab(url, shortHost(url), null, false, listOf(url), 0))
        }
        if (list.isEmpty()) return null
        val legacyIndex = prefs.getInt(PREF_SAVED_TAB_INDEX, 0)
        // 迁移完删旧键:避免「退出前保存」关闭后旧数据又把会话带回来
        prefs.edit().remove(PREF_SAVED_TABS).remove(PREF_SAVED_TAB_INDEX).apply()
        return SavedSession(legacyIndex, list)
    }

    /** 清掉会话存档(「退出前保存」关闭时退出/切换开关用) */
    private fun clearSession() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(PREF_SAVED_SESSION)
            .apply()
    }

    /**
     * 外部入口(系统「打开方式」/分享链接):
     * - http/https:已有同源(scheme://host:port)标签则聚焦,否则新开一个标签
     * - file://:文件管理器给的本地路径,WebView 直接加载(allowFileAccess 已开)
     * - content://:WebView 加载不了外部内容地址,先把流拷到缓存目录再按 file:// 打开
     * 普通启动(MAIN,无数据)不做任何事。
     */
    private fun handleIntent(intent: Intent?) {
        // 下载通知点击:只聚焦下载管理页,不带网址
        if (intent?.getBooleanExtra(LdmDownloader.EXTRA_OPEN_DOWNLOADS, false) == true) {
            openDownloadsTab()
            return
        }
        val uri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return
        when (uri.scheme?.lowercase(Locale.ROOT)) {
            "http", "https", "file" -> focusOrCreateTab(uri.toString())
            "content" -> focusOrCreateTab(copyContentUriToCache(uri) ?: return)
        }
    }

    /** 已有同源标签则聚焦(file:// 无同源概念,每次新开),否则新开一个标签 */
    private fun focusOrCreateTab(url: String) {
        if (!isWebUrl(url)) return
        val origin = urlOrigin(url)
        val idx = origin?.let { o -> tabs.indexOfFirst { urlOrigin(it.url) == o } } ?: -1
        if (idx >= 0) {
            showTab(idx, reveal = true)
        } else {
            addTab(externalOpenTitle(url), url)
            showTab(tabs.lastIndex, reveal = true)
        }
    }

    /** 外部打开的标签标题:file:// 用文件名,其余用主机名 */
    private fun externalOpenTitle(url: String): String {
        if (url.startsWith("file://")) {
            Uri.parse(url).lastPathSegment?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return shortHost(url)
    }

    /**
     * WebView 加载不了外部 content:// 地址:把流拷到 cache/external_open/<显示名>,
     * 返回可直接加载的 file:// 地址;读取失败提示并返回 null。
     */
    private fun copyContentUriToCache(uri: Uri): String? {
        return try {
            val resolver = contentResolver
            val displayName = resolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                ?.takeIf { it.isNotBlank() }
                ?: "external_${System.currentTimeMillis()}"
            val dir = File(cacheDir, "external_open").apply { mkdirs() }
            val out = File(dir, displayName.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
            val input = resolver.openInputStream(uri) ?: throw IllegalStateException()
            input.use { it.copyTo(out.outputStream()) }
            Uri.fromFile(out).toString()
        } catch (_: Exception) {
            android.widget.Toast.makeText(
                this, R.string.browser_open_file_fail, android.widget.Toast.LENGTH_SHORT
            ).show()
            null
        }
    }

    private fun urlOrigin(url: String): String? {
        return try {
            val uri = Uri.parse(url)
            val host = uri.host ?: return null
            val port = if (uri.port == -1) {
                when (uri.scheme) {
                    "https" -> 443
                    else -> 80
                }
            } else {
                uri.port
            }
            "${uri.scheme ?: "http"}://$host:$port"
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 重绘所有标签的自定义视图:标题 + 右侧按钮。
     * - 复用已挂上的 customView 只更新内容,不整体重建(避免打断进行中的手势)
     * - 监听器按 TabItem 动态定位(不捕获下标),换位后依然正确
     * - 只有「当前所在标签」显示右侧按钮,其余隐藏但占位(宽度恒定不跳动)
     *   当前标签已锁定时按钮显示锁图标(点击仅提示),未锁定显示 × 关闭
     * - 点按切标签,长按弹标签菜单(锁定/重命名/排序/关闭)
     * - 末尾追加「+」新建标签项(跟随最后一个标签,不固定在屏幕右侧)
     */
    private fun renderBrowserTabs() {
        // 宽屏摊满:标签少而整排有空位时,标签按份额长大,平板上不再缩在左边
        // 空出一大截(桌面浏览器同款行为);份额不大于设置上限时行为不变。
        // 上限用宽度滑杆的全局上限(240dp),不突破设置可调的范围。
        // 首帧 tabLayout 还没量出宽度时按 0 处理,布局完成后有监听器触发重排。
        val density = resources.displayMetrics.density
        val sharePx = if (tabLayout.width > 0 && tabs.isNotEmpty()) {
            (tabLayout.width - tabLayout.paddingEnd - PLUS_TAB_WIDTH_DP * density) / tabs.size
        } else {
            0f
        }
        val growMax = if (sharePx > tabMaxWidth * density) {
            minOf(sharePx, TAB_WIDTH_RANGE_MAX * density)
        } else {
            tabMaxWidth * density
        }
        tabs.forEachIndexed { i, item ->
            val tab = tabLayout.getTabAt(i) ?: return@forEachIndexed
            val v = tab.customView
                ?: layoutInflater.inflate(R.layout.item_browser_tab, tabLayout, false)
                    .also { tab.customView = it }
            val titleView = v.findViewById<TextView>(R.id.tab_title)
            titleView.text = displayTitle(item)
            if (i == currentIndex) {
                // 当前标签:被截断的长标题走马灯滚动两遍(次数在布局里)
                titleView.ellipsize = TextUtils.TruncateAt.MARQUEE
                titleView.isSelected = true
            } else {
                titleView.ellipsize = TextUtils.TruncateAt.END
                titleView.isSelected = false
            }
            // 当前标签:标题紧贴 × 左侧(× 区 = 14dp 图标 + 2dp 边距,再留 2dp 呼吸 = 18dp),
            // 文字不再压在 × 底下;非当前标签 × 隐藏,右缘只留 4dp 小空隙隔开相邻文字
            titleView.setPadding(
                (12 * density).toInt(), 0,
                ((if (i == currentIndex) 18 else 4) * density).toInt(), 0
            )
            // 标签宽度贴合文字 = 左边距(12dp) + 标题 + 当前标签的 × 区(18dp),夹在 min/max 之间;
            // 与是否选中无关 —— 手动点标签时宽度不变,整排标签零位移、不滚动
            val natural = 30 * density + titleView.paint.measureText(displayTitle(item))
            val widthPx = natural.coerceIn(
                tabMinWidth * density,
                growMax
            )
            v.layoutParams = v.layoutParams.also { it.width = widthPx.toInt() }
            val action = v.findViewById<ImageButton>(R.id.tab_close)
            // 非当前标签:关闭钮完全让位(GONE),标题吃满整行而不是留着空位打省略号
            action.visibility = if (i == currentIndex) View.VISIBLE else View.GONE
            if (item.locked) {
                action.setImageResource(R.drawable.ic_lock_tight)
                action.contentDescription = getString(R.string.browser_tab_locked_hint)
                action.setOnClickListener {
                    android.widget.Toast.makeText(
                        this, R.string.browser_tab_locked_hint, android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                action.setImageResource(R.drawable.ic_close)
                action.contentDescription = getString(R.string.close_tab)
                action.setOnClickListener { closeTabAt(tabs.indexOf(item)) }
            }
            v.setOnClickListener {
                val idx = tabs.indexOf(item)
                // 手动点标签:直接切换,不走 select() —— TabLayout 的选中动画会滚动标签栏
                if (idx >= 0 && idx != currentIndex) showTab(idx, updateSelection = false)
            }
            v.setOnLongClickListener { showTabMenu(tabs.indexOf(item), v); true }
        }
        // 尾部「+」:始终在最后一个标签旁边
        if (plusTab == null) {
            plusTab = tabLayout.newTab()
            tabLayout.addTab(plusTab!!)
        }
        val plusView = plusTab?.customView
            ?: layoutInflater.inflate(R.layout.item_browser_tab_add, tabLayout, false)
                .also { plusTab?.customView = it }
        plusView.setOnClickListener { openNewTab() }
    }

    /** 标签显示标题:用户重命名优先,否则跟随网页 */
    private fun displayTitle(item: TabItem): String = item.customTitle ?: item.title

    /** 地址栏浏览态文本:隐藏标签栏时显示页面标题(编辑态才换回网址),虚拟页留空 */
    private fun addressBrowseText(tab: TabItem): String = when {
        isHistoryTab(tab) || isBookmarksTab(tab) || isDownloadsTab(tab) -> ""
        tabBarHidden -> displayTitle(tab)
        else -> tab.url
    }

    /** 按浏览态规则刷新地址栏文本(编辑中不动用户正在输入的内容) */
    private fun syncAddressBrowseText(tab: TabItem) {
        if (!addressBar.hasFocus()) addressBar.setText(addressBrowseText(tab))
    }

    private class BrowserMenuAction(
        val label: String,
        val action: () -> Unit,
        val longAction: (() -> Unit)? = null
    )

    /** 搜索引擎:持久化用的 key + 显示名 + 搜索链接前缀 */
    private class SearchEngine(val key: String, val labelRes: Int, val queryPrefix: String)

    private val searchEngines = listOf(
        SearchEngine("baidu", R.string.engine_baidu, "https://www.baidu.com/s?wd="),
        SearchEngine("bing", R.string.engine_bing, "https://www.bing.com/search?q="),
        SearchEngine("google", R.string.engine_google, "https://www.google.com/search?q=")
    )

    /**
     * 菜单:主页 / 历史记录 / 退出前保存 / 设置 / 更多(最后一项)。
     * 主页项长按 = 设置自定义主页;系统 PopupMenu 不支持长按菜单项,故用 ListPopupWindow。
     */
    private fun showBrowserMenu(anchor: View) {
        val actions = listOf(
            BrowserMenuAction(
                getString(R.string.browser_home),
                action = { goHome() },
                longAction = { showHomeConfigDialog() }
            ),
            BrowserMenuAction(getString(R.string.browser_bookmark), action = { openBookmarksTab() }),
            BrowserMenuAction(getString(R.string.browser_history), action = { openHistoryTab() }),
            BrowserMenuAction(getString(R.string.browser_downloads), action = { openDownloadsTab() }),
            BrowserMenuAction(getString(R.string.browser_note), action = { showTextEditor() }),
            BrowserMenuAction(
                getString(if (saveOnExit) R.string.browser_save_on else R.string.browser_save_off),
                action = { toggleSaveOnExit() }
            ),
            BrowserMenuAction(getString(R.string.browser_settings), action = { showSettingsDialog() }),
            BrowserMenuAction(getString(R.string.browser_more), action = { showMoreDialog() })
        )
        showListPopup(anchor, actions)
    }

    /**
     * 更多弹窗(菜单最后一项):强制刷新、在其他浏览器打开、电脑模式、关于。
     * 与设置弹窗不同,点条目即执行并自动关闭弹窗。
     */
    private fun showMoreDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lateinit var dialog: AlertDialog
        fun addRow(label: String, action: () -> Unit) {
            box.addView(layoutInflater.inflate(R.layout.item_browser_menu, box, false).apply {
                findViewById<TextView>(R.id.menu_item_text).text = label
                setOnClickListener {
                    dialog.dismiss()
                    action()
                }
            })
        }
        addRow(getString(R.string.browser_force_refresh)) { forceRefreshCurrent() }
        addRow(getString(R.string.browser_open_in_system)) { openCurrentInSystemBrowser() }
        addRow(
            getString(if (desktopMode) R.string.browser_desktop_on else R.string.browser_desktop_off)
        ) { toggleDesktopMode() }
        addRow(
            getString(if (isNightNow()) R.string.browser_night_on else R.string.browser_night_off)
        ) { toggleNightMode() }
        addRow(getString(R.string.browser_about)) { showAboutDialog() }
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.browser_more)
            .setView(box)
            .show()
    }

    /**
     * 关于弹窗(更多最后一项):版本号 + GitHub 仓库入口。
     * 版本行点击复制「版本名(版本号)」;仓库行点击收起弹窗并在本应用新开标签页打开
     * (与历史/收藏的「在新标签页打开」同款)。
     */
    private fun showAboutDialog() {
        val info = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        val version = info?.versionName.orEmpty().ifBlank { "-" }
        // longVersionCode 是 28+,26/27 读旧字段
        val code = info?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode else it.versionCode.toLong()
        } ?: 0L
        val versionText = getString(R.string.browser_about_version, version, code)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lateinit var dialog: AlertDialog
        // 版本行:点击复制
        box.addView(layoutInflater.inflate(R.layout.item_browser_menu, box, false).apply {
            findViewById<TextView>(R.id.menu_item_text).text = versionText
            setOnClickListener {
                (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                    ?.setPrimaryClip(ClipData.newPlainText(null, versionText))
                toast(R.string.browser_copied)
            }
        })
        // 仓库行:点击收起弹窗,再新开标签页跳转
        box.addView(layoutInflater.inflate(R.layout.item_browser_menu, box, false).apply {
            findViewById<TextView>(R.id.menu_item_text).setText(R.string.browser_about_repo)
            setOnClickListener {
                dialog.dismiss()
                openGitHubRepo()
            }
        })
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.browser_about)
            .setView(box)
            .show()
    }

    /** 关于里的 GitHub 仓库:当前就在仓库页则不动,已有相同标签切换过去,没有才新开 */
    private fun openGitHubRepo() {
        // 先看当前:当前标签就是仓库页(不管重开了几个同页标签),什么都不用动
        if (tabs.getOrNull(currentIndex)?.url == GITHUB_URL) return
        val existing = tabs.indexOfFirst { it.url == GITHUB_URL }
        if (existing >= 0) {
            showTab(existing, reveal = true)
        } else {
            addTab(shortHost(GITHUB_URL), GITHUB_URL)
            showTab(tabs.lastIndex, reveal = true)
        }
    }

    /** 当前是否夜间模式 */
    private fun isNightNow(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** 更多「夜间模式」:不重建 Activity(网页不刷新),只重建界面外壳换肤 */
    private fun toggleNightMode() {
        val target = if (isNightNow()) {
            AppCompatDelegate.MODE_NIGHT_NO
        } else {
            AppCompatDelegate.MODE_NIGHT_YES
        }
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(PREF_NIGHT_MODE, target)
            .apply()
        lastKnownNight = target == AppCompatDelegate.MODE_NIGHT_YES
        // uiMode 在 configChanges 里:setDefaultNightMode 只更新资源,不触发重建
        AppCompatDelegate.setDefaultNightMode(target)
        reapplyTheme()
    }

    /**
     * 菜单「文本编辑」:悬浮小窗编辑器(类弹窗居中),边看网页边摘抄:
     * - 单实例:已开着就不再开第二个,菜单里重复点击无效
     * - 面板透明度可调(滑条,持久化),网页从面板后透出来,快捷输入条背景一并跟随
     * - 窗口外触摸穿透:不压暗、FLAG_NOT_TOUCH_MODAL,手指滑窗外的网页直接滚动网页
     * - 面板紧凑(系统避让键盘时整窗都在键盘上方);内容自动保存,
     *   「保存」唤起系统文件保存器把内容导出 txt 到手机本地;快捷输入条随面板
     * - 长按面板上没有按钮的空白处:收起键盘进入调节大小状态,按住拖动自由改窗口大小
     */
    private fun showTextEditor() {
        // 单实例:悬浮窗开着时直接返回,不开第二个
        if (textEditorDialog?.isShowing == true) return
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val density = resources.displayMetrics.density
        editorBox = EditText(this).apply {
            gravity = Gravity.TOP or Gravity.START
            textSize = 15f
            setTextColor(ContextCompat.getColor(this@BrowserActivity, R.color.on_surface))
            setHintTextColor(ContextCompat.getColor(this@BrowserActivity, R.color.hint))
            hint = getString(R.string.browser_note_hint)
            setText(prefs.getString(PREF_NOTE, ""))
            // 内容自动保存(边输边写)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    prefs.edit().putString(PREF_NOTE, s?.toString().orEmpty()).apply()
                }
            })
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // mutate:改透明度不影响别处共享的同一个 drawable
            background = ContextCompat
                .getDrawable(this@BrowserActivity, R.drawable.bg_editor_panel)!!
                .mutate()
            setPadding((12 * density).toInt(), (4 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt())
        }
        titleRow.addView(TextView(this).apply {
            text = getString(R.string.browser_note)
            textSize = 16f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(ContextCompat.getColor(this@BrowserActivity, R.color.on_surface))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        fun addTitleAction(labelRes: Int, bold: Boolean = false, onClick: () -> Unit) {
            titleRow.addView(TextView(this).apply {
                text = getString(labelRes)
                textSize = 14f
                if (bold) setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(ContextCompat.getColor(this@BrowserActivity, R.color.primary_dark))
                setPadding(
                    (12 * density).toInt(), (8 * density).toInt(),
                    (12 * density).toInt(), (8 * density).toInt()
                )
                setOnClickListener { onClick() }
            })
        }
        addTitleAction(R.string.browser_note_extract) { extractNoteLinks() }
        addTitleAction(R.string.browser_note_clear) { editorBox.setText("") }
        addTitleAction(R.string.browser_note_save) { saveNoteToFile() }
        addTitleAction(R.string.done, bold = true) { textEditorDialog?.dismiss() }
        panel.addView(titleRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        // 快捷输入条先建好(滑条回调要引用它),挂在编辑框下面
        val quickBar = newQuickInputContainer().also {
            fillQuickInputBar(it) { token -> insertIntoEditText(editorBox, token) }
        }
        // 打开时恢复上次的透明度:面板与快捷输入条一起套用
        val savedAlpha = prefs.getInt(PREF_EDITOR_ALPHA, PREF_EDITOR_ALPHA_DEFAULT)
        (panel.background as? GradientDrawable)?.alpha = savedAlpha
        applyEditorAlphaToQuickBar(quickBar, savedAlpha)
        // 透明度滑条:调面板背景 alpha,文字保持不透明,快捷输入条背景同步跟随
        panel.addView(SeekBar(this).apply {
            max = 255
            progress = savedAlpha
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, (4 * density).toInt())
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    (panel.background as? GradientDrawable)?.alpha = value
                    applyEditorAlphaToQuickBar(quickBar, value)
                    prefs.edit().putInt(PREF_EDITOR_ALPHA, value).apply()
                }

                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        })
        panel.addView(editorBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (320 * density).toInt()
        ))
        panel.addView(quickBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val dialog = Dialog(this)
        // 面板外包一层:调节大小状态的覆盖层(半透明黑 + 边框线)盖在面板上
        val editorRoot = FrameLayout(this)
        editorRoot.addView(panel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        dialog.setContentView(editorRoot)
        // 上次「完成」保存的窗口大小(重开恢复;没调过或值异常回退默认 85% 宽)
        val savedW = prefs.getInt(PREF_EDITOR_W, 0)
            .takeIf { it >= (200 * density).toInt() }
            ?.coerceAtMost(resources.displayMetrics.widthPixels)
        val savedH = prefs.getInt(PREF_EDITOR_H, 0)
            .takeIf { it >= (180 * density).toInt() }
            ?.coerceAtMost(resources.displayMetrics.heightPixels)
        dialog.window?.apply {
            // 宽度约屏宽 85%,默认垂直居中(键盘收起时回到居中)
            setLayout(
                savedW ?: (resources.displayMetrics.widthPixels * 0.85f).toInt(),
                savedH ?: ViewGroup.LayoutParams.WRAP_CONTENT
            )
            // 不压暗网页;窗口外触摸穿透到网页(滑动即滚动网页)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            // 键盘避让完全交给系统:ADJUST_RESIZE 让窗口管理器压缩 frame 避开键盘,
            // 默认 decor 填充会把面板自动抬到键盘顶(收起回正居中) —— 零手动代码,
            // 各 ROM 行为一致。之前的手动挪窗(ADJUST_NOTHING+decorFits=false+insets)
            // 在部分 ROM 上 insets 报告与窗口压缩都不可靠,不如系统标准机制稳定。
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
            )
        }
        // 长按面板空白处进入「调节大小」状态
        setupEditorWindowResize(dialog, panel, editorRoot)
        textEditorDialog = dialog
        dialog.setOnDismissListener {
            textEditorDialog = null
        }
        dialog.show()
        editorBox.requestFocus()
    }

    /** 「保存」:唤起系统文件保存器,把当前笔记内容存成 txt 到手机本地 */
    private fun saveNoteToFile() {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        try {
            saveNoteLauncher.launch(getString(R.string.browser_note_file_name) + "_$stamp.txt")
        } catch (_: Exception) {
            toast(R.string.browser_note_save_fail)
        }
    }

    /**
     * 编辑器「调节大小」状态(边框线拉的那种):
     * - 进入:长按面板上没有按钮的空白处(子视图不接管的边框/空位)——清焦点收起键盘,
     *   弹出覆盖层:半透明黑盖住全部控件(都点不了),白色边框线标出窗口范围,中间「取消/完成」
     * - 调节:按住边框线/角拖动,窗口实时跟随(最小 200×180dp,不出屏);
     *   按住中间空白拖动 = 整窗移动;「完成」保存大小(重开恢复),「取消」恢复调节前的样子
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupEditorWindowResize(dialog: Dialog, panel: LinearLayout, root: FrameLayout) {
        val wm = dialog.window ?: return
        val decor = wm.decorView
        val density = resources.displayMetrics.density
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val minW = (200 * density).toInt()
        val minH = (180 * density).toInt()
        val grabZone = (26 * density).toInt()
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        val slop = ViewConfiguration.get(this).scaledTouchSlop

        var pending = false          // 已按下,长按计时中
        var timer: Runnable? = null
        var downX = 0f
        var downY = 0f
        var resizing = false

        // 进入调节时的窗口存档(「取消」恢复)与屏幕系 → lp 系的坐标修正
        var savedGravity = 0
        var savedX = 0
        var savedY = 0
        var savedW = 0
        var savedH = 0
        var offX = 0
        var offY = 0
        var overlayRef: FrameLayout? = null

        fun cancelPending() {
            timer?.let { decor.removeCallbacks(it) }
            timer = null
            pending = false
        }

        fun enterResize() {
            if (resizing) return
            resizing = true
            wm.attributes.also {
                savedGravity = it.gravity
                savedX = it.x
                savedY = it.y
                savedW = it.width
                savedH = it.height
            }
            val loc = IntArray(2)
            decor.getLocationOnScreen(loc)
            // gravity 改 TOP|START 后才能用 x/y 精确摆放;先按屏幕坐标放,下一帧量 frame 偏差补回
            wm.attributes = wm.attributes.also {
                it.gravity = Gravity.TOP or Gravity.START
                it.x = loc[0]
                it.y = loc[1]
            }
            decor.post {
                if (!resizing) return@post
                val now = IntArray(2)
                decor.getLocationOnScreen(now)
                offX = now[0] - loc[0]
                offY = now[1] - loc[1]
                if (offX != 0 || offY != 0) {
                    wm.attributes = wm.attributes.also {
                        it.x = loc[0] - offX
                        it.y = loc[1] - offY
                    }
                }
            }
            // 退出输入态:清焦点收起键盘
            editorBox.clearFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(editorBox.windowToken, 0)
            panel.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            overlayRef?.visibility = View.VISIBLE
        }

        /** 「完成」:保留当前大小并记档,重开悬浮窗按这个尺寸开 */
        fun finishResize() {
            if (!resizing) return
            resizing = false
            overlayRef?.visibility = View.GONE
            wm.attributes.also {
                if (it.width > 0 && it.height > 0) {
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                        .putInt(PREF_EDITOR_W, it.width)
                        .putInt(PREF_EDITOR_H, it.height)
                        .apply()
                }
            }
        }

        /** 「取消」:恢复进入调节前的窗口位置和大小 */
        fun cancelResize() {
            // 没真正进入过调节状态就别拿未记录的初始值恢复(会把窗口摆去角落)
            if (!resizing) return
            resizing = false
            overlayRef?.visibility = View.GONE
            wm.attributes = wm.attributes.also {
                it.gravity = savedGravity
                it.x = savedX
                it.y = savedY
            }
            wm.setLayout(savedW, savedH)
        }

        // 调节覆盖层:半透明黑 + 白色边框线盖住面板全部控件;拖边框改大小,拖中间移动窗口
        val overlay = object : FrameLayout(this@BrowserActivity) {
            private val stroke = (2 * density).toInt()
            private val radius = 16 * density
            private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke.toFloat()
                color = 0xD9FFFFFF.toInt()
            }
            private var baseLeft = 0
            private var baseTop = 0
            private var baseRight = 0
            private var baseBottom = 0
            private var grabLeft = false
            private var grabRight = false
            private var grabTop = false
            private var grabBottom = false
            private var moving = false
            private var moveDx = 0f
            private var moveDy = 0f

            init {
                background = GradientDrawable().apply {
                    setColor(0x66000000.toInt())
                    setCornerRadii(floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f))
                }
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                fun action(labelRes: Int, bold: Boolean, onClick: () -> Unit) {
                    row.addView(TextView(context).apply {
                        text = getString(labelRes)
                        textSize = 15f
                        if (bold) setTypeface(Typeface.DEFAULT_BOLD)
                        setTextColor(ContextCompat.getColor(context, R.color.on_surface))
                        background = ContextCompat.getDrawable(context, R.drawable.bg_address_bar)
                        setPadding(
                            (20 * density).toInt(), (9 * density).toInt(),
                            (20 * density).toInt(), (9 * density).toInt()
                        )
                        setOnClickListener { onClick() }
                    }, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginStart = (8 * density).toInt()
                        marginEnd = (8 * density).toInt()
                    })
                }
                action(R.string.cancel, false) { cancelResize() }
                action(R.string.done, true) { finishResize() }
                addView(row, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                ))
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                // 白色边框线:顶角与面板同款圆角,描边整体内缩半线宽避免被裁
                val w = width.toFloat()
                val h = height.toFloat()
                val half = stroke / 2f
                if (w <= stroke || h <= stroke) return
                val path = Path()
                path.moveTo(half, h - half)
                path.lineTo(half, radius)
                path.quadTo(half, half, radius, half)
                path.lineTo(w - radius, half)
                path.quadTo(w - half, half, w - half, radius)
                path.lineTo(w - half, h - half)
                path.close()
                canvas.drawPath(path, borderPaint)
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val loc = IntArray(2)
                        getLocationOnScreen(loc)
                        baseLeft = loc[0]
                        baseTop = loc[1]
                        baseRight = loc[0] + width
                        baseBottom = loc[1] + height
                        val x = event.rawX
                        val y = event.rawY
                        grabLeft = x - baseLeft <= grabZone
                        grabRight = baseRight - x <= grabZone
                        grabTop = y - baseTop <= grabZone
                        grabBottom = baseBottom - y <= grabZone
                        moving = !(grabLeft || grabRight || grabTop || grabBottom)
                        moveDx = x - loc[0]
                        moveDy = y - loc[1]
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (moving) {
                            // 中间空白拖动 = 整窗移动
                            val nx = (event.rawX - moveDx).toInt()
                                .coerceIn(0, (screenW - width).coerceAtLeast(0))
                            val ny = (event.rawY - moveDy).toInt()
                                .coerceIn(0, (screenH - height).coerceAtLeast(0))
                            wm.attributes = wm.attributes.also {
                                it.x = nx - offX
                                it.y = ny - offY
                            }
                        } else {
                            // 边框拖动:抓住的边跟随手指,对边固定,实时改窗口大小
                            val left = (if (grabLeft) event.rawX.toInt() else baseLeft)
                                .coerceIn(0, baseRight - minW)
                            val right = (if (grabRight) event.rawX.toInt() else baseRight)
                                .coerceIn(baseLeft + minW, screenW)
                            val top = (if (grabTop) event.rawY.toInt() else baseTop)
                                .coerceIn(0, baseBottom - minH)
                            val bottom = (if (grabBottom) event.rawY.toInt() else baseBottom)
                                .coerceIn(baseTop + minH, screenH)
                            wm.attributes = wm.attributes.also {
                                it.x = left - offX
                                it.y = top - offY
                            }
                            wm.setLayout(right - left, bottom - top)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
                    else -> return false
                }
            }
        }
        overlay.visibility = View.GONE
        overlayRef = overlay
        root.addView(overlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // 长按面板空白处(子视图不接管的触摸)进入调节状态
        panel.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    pending = true
                    timer = Runnable { if (pending) enterResize() }
                        .also { decor.postDelayed(it, longPressTimeout) }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (pending && (abs(ev.rawX - downX) > slop || abs(ev.rawY - downY) > slop)) {
                        // 按下就拖走:是滑动不是长按,取消计时
                        cancelPending()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancelPending()
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 编辑器快捷输入条跟随面板透明度:容器换成独立的 GradientDrawable,
     * 每个快捷键背景 mutate 后各自调 alpha(bg_address_bar 与地址栏框共享同一资源,
     * 不 mutate 会把地址栏背景一起改透明)
     */
    private fun applyEditorAlphaToQuickBar(bar: LinearLayout, alpha: Int) {
        if (bar.background !is GradientDrawable) {
            bar.background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(this@BrowserActivity, R.color.surface))
            }
        }
        (bar.background as GradientDrawable).alpha = alpha
        for (i in 0 until bar.childCount) {
            val row = bar.getChildAt(i) as? ViewGroup ?: continue
            for (j in 0 until row.childCount) {
                (row.getChildAt(j).background?.mutate() as? GradientDrawable)?.alpha = alpha
            }
        }
    }

    /** 「提取链接」:抓出正文里的 http(s)/www 链接(去重保序)弹窗列出;一条都没有则提示 */
    private fun extractNoteLinks() {
        val links = LinkedHashSet<String>()
        NOTE_URL_REGEX.findAll(editorBox.text?.toString().orEmpty()).forEach { m ->
            trimNoteUrl(m.value).takeIf { it.isNotEmpty() }?.let { links.add(it) }
        }
        if (links.isEmpty()) {
            toast(R.string.browser_note_no_links)
            return
        }
        showExtractedLinksDialog(links.toList())
    }

    /** 去掉链接尾部黏带的标点;有配对左括号的右括号算链接本身(如维基词条)保留 */
    private fun trimNoteUrl(raw: String): String {
        var u = raw
        while (u.isNotEmpty() && u.last() in NOTE_URL_TRAILING) {
            val c = u.last()
            if ((c == ')' || c == '）') && (u.contains('(') || u.contains('（'))) break
            u = u.dropLast(1)
        }
        return u
    }

    /** 提取出的链接转可打开地址:没有 scheme 的(www. 开头)补上 http:// */
    private fun extractedLinkToOpen(raw: String): String =
        if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw
        else "http://$raw"

    /** 提取链接弹窗:每条链接一行,「复制链接」只复制不关窗,「访问」关窗并开新标签页 */
    private fun showExtractedLinksDialog(links: List<String>) {
        val density = resources.displayMetrics.density
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lateinit var dialog: AlertDialog
        links.forEach { raw ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    (16 * density).toInt(), (8 * density).toInt(),
                    (8 * density).toInt(), (8 * density).toInt()
                )
                addView(TextView(this@BrowserActivity).apply {
                    text = raw
                    textSize = 13f
                    setTextColor(ContextCompat.getColor(this@BrowserActivity, R.color.on_surface))
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(this@BrowserActivity).apply {
                    text = getString(R.string.browser_history_copy_link)
                    textSize = 14f
                    setTextColor(ContextCompat.getColor(this@BrowserActivity, R.color.primary_dark))
                    setPadding(
                        (10 * density).toInt(), (6 * density).toInt(),
                        (6 * density).toInt(), (6 * density).toInt()
                    )
                    setOnClickListener {
                        (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                            ?.setPrimaryClip(ClipData.newPlainText(null, raw))
                        toast(R.string.browser_history_copied)
                    }
                })
                addView(TextView(this@BrowserActivity).apply {
                    text = getString(R.string.browser_note_visit)
                    textSize = 14f
                    setTextColor(ContextCompat.getColor(this@BrowserActivity, R.color.primary_dark))
                    setPadding(
                        (10 * density).toInt(), (6 * density).toInt(),
                        (6 * density).toInt(), (6 * density).toInt()
                    )
                    setOnClickListener {
                        dialog.dismiss()
                        val url = extractedLinkToOpen(raw)
                        addTab(shortHost(url), url)
                        showTab(tabs.lastIndex, reveal = true)
                    }
                })
            }
            box.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.browser_note_links_title, links.size))
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(R.string.done, null)
            .show()
    }

    /** 新建快捷输入条容器(纵向,内容由 [fillQuickInputBar] 按设置填充) */
    private fun newQuickInputContainer(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(ContextCompat.getColor(this@BrowserActivity, R.color.surface))
        val pad = (4 * resources.displayMetrics.density).toInt()
        setPadding(0, pad, 0, pad)
    }

    /** 按当前设置(快捷键列表 + 列数)把快捷输入条填成网格 */
    private fun fillQuickInputBar(bar: LinearLayout, onInsert: (String) -> Unit) {
        bar.removeAllViews()
        val tokens = loadQuickTokens()
        if (tokens.isEmpty()) return
        val density = resources.displayMetrics.density
        val cols = quickCols.coerceIn(1, 6)
        var i = 0
        while (i < tokens.size) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            var c = 0
            while (c < cols) {
                if (i < tokens.size) {
                    val token = tokens[i]
                    row.addView(TextView(this).apply {
                        text = token
                        textSize = 15f
                        gravity = Gravity.CENTER
                        setTextColor(ContextCompat.getColor(this@BrowserActivity, R.color.on_surface))
                        background = ContextCompat.getDrawable(this@BrowserActivity, R.drawable.bg_address_bar)
                        layoutParams = LinearLayout.LayoutParams(
                            0, (36 * density).toInt(), 1f
                        ).apply {
                            marginStart = (3 * density).toInt()
                            marginEnd = (3 * density).toInt()
                            topMargin = (2 * density).toInt()
                            bottomMargin = (2 * density).toInt()
                        }
                        setOnClickListener { onInsert(token) }
                    })
                    i++
                } else {
                    row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                }
                c++
            }
            bar.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    /** 重建地址栏的快捷输入条(设置变化后调用) */
    private fun refreshQuickInputBar() {
        fillQuickInputBar(inputHelperBar) { insertIntoEditText(addressBar, it) }
    }

    private fun loadQuickTokens(): MutableList<String> {
        val defaults = mutableListOf("http://", ":", "/", "?", ".")
        val raw = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_QUICK_TOKENS, null) ?: return defaults
        val list = mutableListOf<String>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val t = arr.optString(i).trim()
                if (t.isNotEmpty()) list.add(t)
            }
        }
        return list.ifEmpty { defaults }
    }

    private fun saveQuickTokens(tokens: List<String>) {
        val arr = JSONArray()
        tokens.forEach { arr.put(it) }
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_QUICK_TOKENS, arr.toString())
            .apply()
    }

    /**
     * 快捷输入设置弹窗(叠在设置弹窗上):增删快捷键、调列数,
     * 每次改动实时持久化并刷新地址栏的快捷输入条。
     */
    private fun showQuickTokensDialog() {
        val density = resources.displayMetrics.density
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val colsLabel = TextView(this).apply {
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@BrowserActivity, R.color.on_surface))
            setPadding((20 * density).toInt(), (14 * density).toInt(), (20 * density).toInt(), 0)
        }
        fun refreshCols() {
            colsLabel.text = getString(R.string.browser_quick_cols, quickCols)
        }
        fun refreshDialogList() {
            listContainer.removeAllViews()
            val tokens = loadQuickTokens()
            if (tokens.isEmpty()) {
                listContainer.addView(TextView(this).apply {
                    text = getString(R.string.browser_note_hint)
                    textSize = 13f
                    setTextColor(ContextCompat.getColor(this@BrowserActivity, R.color.hint))
                    setPadding((20 * density).toInt(), (6 * density).toInt(), 0, 0)
                })
            }
            tokens.forEachIndexed { idx, token ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding((20 * density).toInt(), (6 * density).toInt(), (20 * density).toInt(), (6 * density).toInt())
                }
                row.addView(TextView(this).apply {
                    text = token
                    textSize = 15f
                    setTextColor(ContextCompat.getColor(this@BrowserActivity, R.color.on_surface))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(TextView(this).apply {
                    text = getString(R.string.browser_history_delete)
                    textSize = 14f
                    setTextColor(ContextCompat.getColor(this@BrowserActivity, R.color.primary_dark))
                    setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
                    setOnClickListener {
                        val updated = loadQuickTokens().toMutableList()
                        if (idx in updated.indices) {
                            updated.removeAt(idx)
                            saveQuickTokens(updated)
                            refreshDialogList()
                            refreshQuickInputBar()
                        }
                    }
                })
                listContainer.addView(row)
            }
        }
        refreshDialogList()
        refreshCols()
        box.addView(listContainer)
        // 添加行
        val addRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((20 * density).toInt(), (10 * density).toInt(), (20 * density).toInt(), 0)
        }
        val addInput = EditText(this).apply {
            setSingleLine(true)
            textSize = 15f
            hint = "http://example.com"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        addRow.addView(addInput)
        addRow.addView(TextView(this).apply {
            text = getString(R.string.browser_quick_add)
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@BrowserActivity, R.color.primary_dark))
            setPadding((12 * density).toInt(), (6 * density).toInt(), 0, (6 * density).toInt())
            setOnClickListener {
                val token = addInput.text?.toString()?.trim().orEmpty()
                when {
                    token.isEmpty() -> toast(R.string.browser_quick_empty)
                    loadQuickTokens().contains(token) -> toast(R.string.browser_quick_exists)
                    loadQuickTokens().size >= 24 -> toast(R.string.browser_quick_limit)
                    else -> {
                        val updated = loadQuickTokens()
                        updated.add(token)
                        saveQuickTokens(updated)
                        addInput.setText("")
                        refreshDialogList()
                        refreshQuickInputBar()
                    }
                }
            }
        })
        box.addView(addRow)
        // 列数调节
        val colsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((20 * density).toInt(), (14 * density).toInt(), (20 * density).toInt(), 0)
        }
        colsRow.addView(colsLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun stepCols(delta: Int) {
            quickCols = (quickCols + delta).coerceIn(1, 6)
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putInt(PREF_QUICK_COLS, quickCols).apply()
            refreshCols()
            refreshQuickInputBar()
        }
        colsRow.addView(TextView(this).apply {
            text = "−"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@BrowserActivity, R.color.on_surface))
            setPadding((16 * density).toInt(), (4 * density).toInt(), (16 * density).toInt(), (4 * density).toInt())
            setOnClickListener { stepCols(-1) }
        })
        colsRow.addView(TextView(this).apply {
            text = "+"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@BrowserActivity, R.color.on_surface))
            setPadding((16 * density).toInt(), (4 * density).toInt(), (16 * density).toInt(), (4 * density).toInt())
            setOnClickListener { stepCols(1) }
        })
        box.addView(colsRow)
        AlertDialog.Builder(this)
            .setTitle(R.string.browser_quick)
            .setView(box)
            .setPositiveButton(R.string.done, null)
            .show()
    }

    private fun toast(res: Int) {
        android.widget.Toast.makeText(this, res, android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * 在 EditText 光标处插入文本并把光标移到插入内容之后;
     * 有选中区间时(地址栏聚焦即全选)替换选区,而不是插到选区前面
     */
    private fun insertIntoEditText(editText: EditText, token: String) {
        val editable = editText.text ?: return
        val from = minOf(editText.selectionStart, editText.selectionEnd)
            .coerceIn(0, editable.length)
        val to = maxOf(editText.selectionStart, editText.selectionEnd)
            .coerceIn(0, editable.length)
        editable.replace(from, to, token)
        editText.setSelection(from + token.length)
    }

    /** 菜单「退出前保存」:点按切换,立即生效(关=清掉已存会话,下次打开不恢复) */
    private fun toggleSaveOnExit() {
        saveOnExit = !saveOnExit
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_SAVE_ON_EXIT, saveOnExit)
            .apply()
        if (saveOnExit) {
            saveSession()
            android.widget.Toast.makeText(
                this, R.string.browser_save_on_toast, android.widget.Toast.LENGTH_SHORT
            ).show()
        } else {
            clearSession()
            android.widget.Toast.makeText(
                this, R.string.browser_save_off_toast, android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * 设置弹窗:清除数据、标签页和网址栏设置、标签页宽度、快捷输入、下载设置。
     * 点功能项不关弹窗;二级弹窗叠加在本弹窗上面;点空白处 AlertDialog
     * 默认只关最上面一层,实现一层一层关闭。
     */
    private fun showSettingsDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(layoutInflater.inflate(R.layout.item_browser_menu, box, false).apply {
            findViewById<TextView>(R.id.menu_item_text).setText(R.string.browser_clear_data)
            setOnClickListener { confirmClearBrowserData() }
        })
        box.addView(layoutInflater.inflate(R.layout.item_browser_menu, box, false).apply {
            findViewById<TextView>(R.id.menu_item_text).setText(R.string.browser_toolbar_layout)
            setOnClickListener { showToolbarLayoutDialog() }
        })
        box.addView(layoutInflater.inflate(R.layout.item_browser_menu, box, false).apply {
            findViewById<TextView>(R.id.menu_item_text).setText(R.string.browser_tab_width)
            setOnClickListener { showTabWidthDialog() }
        })
        box.addView(layoutInflater.inflate(R.layout.item_browser_menu, box, false).apply {
            findViewById<TextView>(R.id.menu_item_text).setText(R.string.browser_quick)
            setOnClickListener { showQuickTokensDialog() }
        })
        box.addView(layoutInflater.inflate(R.layout.item_browser_menu, box, false).apply {
            findViewById<TextView>(R.id.menu_item_text).setText(R.string.browser_dl_settings)
            setOnClickListener { showDownloadSettingsDialog() }
        })
        AlertDialog.Builder(this)
            .setTitle(R.string.browser_settings)
            .setView(box)
            .show()
    }

    /**
     * 标签页和网址栏设置弹窗(设置里的「标签页和网址栏设置」进入,叠在设置弹窗上面):
     * 两个条目点一下立即生效并持久化;「完成」只收本弹窗,回到设置弹窗继续调别的。
     */
    private fun showToolbarLayoutDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(layoutInflater.inflate(R.layout.item_browser_menu, box, false).apply {
            findViewById<TextView>(R.id.menu_item_text).setText(R.string.browser_swap_toolbar)
            setOnClickListener { toggleTabsOnTop() }
        })
        box.addView(layoutInflater.inflate(R.layout.item_browser_menu, box, false).apply {
            findViewById<TextView>(R.id.menu_item_text).setText(R.string.browser_url_bar_bottom)
            setOnClickListener { toggleUrlBarBottom() }
        })
        AlertDialog.Builder(this)
            .setTitle(R.string.browser_toolbar_layout)
            .setView(box)
            .setPositiveButton(R.string.done, null)
            .show()
    }

    /**
     * 下载设置弹窗(设置里的「下载设置」进入,叠在设置弹窗上面):
     * 下载方式、下载目录都是下拉选择,选中即生效并持久化;
     * 「完成」只收本弹窗,回到设置弹窗继续调别的。
     */
    private fun showDownloadSettingsDialog() {
        val density = resources.displayMetrics.density
        val padH = (20 * density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, (8 * density).toInt())
        }

        fun rowLabel(textRes: Int): TextView = TextView(this).apply {
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@BrowserActivity, R.color.on_surface))
            setPadding(padH, (16 * density).toInt(), padH, 0)
            setText(textRes)
        }

        fun rowSpinner(): AppCompatSpinner = AppCompatSpinner(this).also { sp ->
            box.addView(
                sp,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(padH, 0, padH, 0) }
            )
        }

        // 下载方式下拉:内置下载器(自研,断点续传/暂停)/ 系统下载器
        box.addView(rowLabel(R.string.browser_dl_method))
        val methodSpinner = rowSpinner()
        methodSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, arrayOf(
                getString(R.string.browser_dl_method_builtin),
                getString(R.string.browser_dl_method_system)
            )
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        methodSpinner.setSelection(if (downloadMethod == DL_METHOD_BUILTIN) 0 else 1, false)
        methodSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                val method = if (position == 0) DL_METHOD_BUILTIN else DL_METHOD_SYSTEM
                if (method == downloadMethod) return  // 初始选中的回显,不是用户操作
                downloadMethod = method
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putInt(PREF_DL_METHOD, method)
                    .apply()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 下载目录下拉:默认「下载」;自定义后第二项显示所选路径,末项总是「选择自定义目录…」
        box.addView(rowLabel(R.string.browser_dl_dir))
        val dirSpinner = rowSpinner()
        val dirAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, downloadDirOptions())
            .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        dirSpinner.adapter = dirAdapter
        dirSpinner.setSelection(if (hasCustomDownloadDir()) 1 else 0, false)
        dirSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                val custom = hasCustomDownloadDir()
                if (position == if (custom) 1 else 0) return  // 当前生效项的回显,不是用户操作
                when {
                    custom && position == 0 -> clearDownloadDir()
                    else -> pickDownloadDir()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        dlDirSpinner = dirSpinner
        dlDirAdapter = dirAdapter

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.browser_dl_settings)
            .setView(box)
            .setPositiveButton(R.string.done, null)
            .show()
        dialog.setOnDismissListener {
            dlDirSpinner = null
            dlDirAdapter = null
        }
    }

    /** 是否设置了自定义下载目录 */
    private fun hasCustomDownloadDir(): Boolean =
        !getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(LdmDownloader.PREF_DL_DIR, null).isNullOrBlank()

    /** 下载目录下拉项:0=默认「下载」;自定义时追加所选路径,末项总是「选择自定义目录…」 */
    private fun downloadDirOptions(): List<String> {
        val options = mutableListOf(getString(R.string.browser_dl_dir_default))
        if (hasCustomDownloadDir()) options.add(downloadDirLabel(this))
        options.add(getString(R.string.browser_dl_dir_pick))
        return options
    }

    /** 恢复默认下载目录:清掉自定义目录记录并刷新下拉框 */
    private fun clearDownloadDir() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(LdmDownloader.PREF_DL_DIR)
            .apply()
        refreshDownloadDirSpinner()
    }

    /** 目录下拉框按当前设置重建:选项与选中项都对齐到实际状态 */
    private fun refreshDownloadDirSpinner() {
        val spinner = dlDirSpinner ?: return
        val adapter = dlDirAdapter ?: return
        adapter.clear()
        adapter.addAll(downloadDirOptions())
        spinner.setSelection(if (hasCustomDownloadDir()) 1 else 0, false)
    }

    /** 唤起系统目录选择器(SAF),选中后持久化授权 */
    private fun pickDownloadDir() {
        try {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_PICK_DOWNLOAD_DIR
            )
        } catch (_: Exception) {
            toast(R.string.browser_dl_dir_pick_fail)
        }
    }

    /** 目录选择结果:拿到持久化读写授权后记下目录,下载设置弹窗的目录下拉框跟着刷新 */
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_DOWNLOAD_DIR) return
        val treeUri = data?.data
        if (resultCode != RESULT_OK || treeUri == null) {
            // 取消选择:目录下拉框弹回当前生效项
            refreshDownloadDirSpinner()
            return
        }
        runCatching {
            contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(LdmDownloader.PREF_DL_DIR, treeUri.toString())
            .apply()
        refreshDownloadDirSpinner()
        toast(R.string.browser_dl_dir_set)
    }

    /**
     * 标签页宽度弹窗(设置里的「标签页宽度」进入,叠在设置弹窗上面):
     * 双点滑块拖动实时生效并保存;「完成」只收本弹窗,回到设置弹窗继续调别的。
     */
    private fun showTabWidthDialog() {
        val density = resources.displayMetrics.density
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(this).apply {
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@BrowserActivity, R.color.on_surface))
            setPadding((20 * density).toInt(), (14 * density).toInt(), (20 * density).toInt(), 0)
            text = getString(R.string.browser_tab_width)
        })
        val widthLabel = TextView(this).apply {
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@BrowserActivity, R.color.hint))
            setPadding((20 * density).toInt(), (6 * density).toInt(), (20 * density).toInt(), 0)
            text = tabWidthText()
        }
        box.addView(widthLabel)
        box.addView(TabWidthSliderView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (76 * density).toInt()
            )
            setValues(tabMinWidth, tabMaxWidth)
            onValuesChanged = { mn, mx ->
                tabMinWidth = mn
                tabMaxWidth = mx
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putInt(PREF_TAB_MIN_WIDTH, mn)
                    .putInt(PREF_TAB_MAX_WIDTH, mx)
                    .apply()
                widthLabel.text = tabWidthText()
                renderBrowserTabs()
            }
        })
        AlertDialog.Builder(this)
            .setTitle(R.string.browser_tab_width)
            .setView(box)
            .setPositiveButton(R.string.done, null)
            .show()
    }

    /** 宽度值文案:两值相同显示固定宽度 */
    private fun tabWidthText(): String =
        if (tabMinWidth == tabMaxWidth) {
            getString(R.string.browser_tab_width_fixed, tabMinWidth)
        } else {
            getString(R.string.browser_tab_width_range, tabMinWidth, tabMaxWidth)
        }

    /**
     * 标签页宽度双点滑块:轨道上两个端点 = 最小/最大宽度;
     * 上方▼箭头指向最小点、下方▲箭头指向最大点,拖箭头调位置,
     * 两点可拖成同一值(固定宽度);箭头分居上下,端点重叠也不互相挡。
     */
    private inner class TabWidthSliderView(context: Context) : View(context) {
        var minDp: Int = tabMinWidth
            private set
        var maxDp: Int = tabMaxWidth
            private set
        var onValuesChanged: ((Int, Int) -> Unit)? = null

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var dragging = false
        private var dragMin = false

        private fun dp(v: Float): Float = v * resources.displayMetrics.density
        private fun trackLeft(): Float = dp(24f)
        private fun trackRight(): Float = width - dp(24f)
        private fun trackY(): Float = height / 2f

        private fun valueToX(v: Int): Float =
            trackLeft() + (v - TAB_WIDTH_RANGE_MIN) * (trackRight() - trackLeft()) /
                (TAB_WIDTH_RANGE_MAX - TAB_WIDTH_RANGE_MIN)

        private fun xToValue(x: Float): Int =
            (TAB_WIDTH_RANGE_MIN + (x - trackLeft()) * (TAB_WIDTH_RANGE_MAX - TAB_WIDTH_RANGE_MIN) /
                (trackRight() - trackLeft())).roundToInt()
                .coerceIn(TAB_WIDTH_RANGE_MIN, TAB_WIDTH_RANGE_MAX)

        fun setValues(min: Int, max: Int) {
            minDp = min.coerceAtMost(max)
            maxDp = max
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val ty = trackY()
            // 底轨 + 选区
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(3f)
            paint.color = ContextCompat.getColor(context, R.color.divider)
            canvas.drawLine(trackLeft(), ty, trackRight(), ty, paint)
            paint.color = ContextCompat.getColor(context, R.color.primary)
            canvas.drawLine(valueToX(minDp), ty, valueToX(maxDp), ty, paint)
            // 两个端点圆
            paint.style = Paint.Style.FILL
            paint.color = ContextCompat.getColor(context, R.color.primary_dark)
            canvas.drawCircle(valueToX(minDp), ty, dp(6f), paint)
            canvas.drawCircle(valueToX(maxDp), ty, dp(6f), paint)
            // 箭头:上▼指向最小点,下▲指向最大点
            drawArrow(canvas, valueToX(minDp), ty - dp(19f), true, dragging && dragMin)
            drawArrow(canvas, valueToX(maxDp), ty + dp(19f), false, dragging && !dragMin)
        }

        private fun drawArrow(canvas: Canvas, cx: Float, cy: Float, down: Boolean, active: Boolean) {
            paint.color = ContextCompat.getColor(
                context, if (active) R.color.primary_dark else R.color.on_surface
            )
            val w = dp(9f)
            val h = dp(6f)
            val path = Path()
            if (down) {
                path.moveTo(cx - w, cy - h)
                path.lineTo(cx + w, cy - h)
                path.lineTo(cx, cy + h)
            } else {
                path.moveTo(cx - w, cy + h)
                path.lineTo(cx + w, cy + h)
                path.lineTo(cx, cy - h)
            }
            path.close()
            canvas.drawPath(path, paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // 上半区 = 拖最小值箭头,下半区 = 拖最大值箭头
                    dragMin = event.y < trackY()
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    applyDrag(event.x)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging) applyDrag(event.x)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun applyDrag(x: Float) {
            val v = xToValue(x)
            if (dragMin) minDp = v.coerceAtMost(maxDp) else maxDp = v.coerceAtLeast(minDp)
            invalidate()
            onValuesChanged?.invoke(minDp, maxDp)
        }
    }

    /** 「标签页和网址栏设置」弹窗「调换标签页和网址栏的位置」:切换标签栏与工具栏行的上下位置并持久化 */
    private fun toggleTabsOnTop() {
        tabsOnTop = !tabsOnTop
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_TABS_ON_TOP, tabsOnTop)
            .apply()
        applyLayoutOrder()
    }

    /** 「标签页和网址栏设置」弹窗「标签页和网址栏上下分布」:两行分居屏幕顶/底并持久化 */
    private fun toggleUrlBarBottom() {
        urlBarBottom = !urlBarBottom
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_URL_BAR_BOTTOM, urlBarBottom)
            .apply()
        applyLayoutOrder()
    }

    /**
     * 按两个开关重排根布局(「调换」决定哪一行贴顶),进度条始终贴着网址栏行:
     * - 默认:网址栏行 / 进度条 / 标签栏 / 内容
     * - 调换:标签栏 / 网址栏行 / 进度条 / 内容(两行都在顶部,只换先后)
     * - 上下分布:一行贴顶、另一行贴底,网页内容夹在中间
     */
    private fun applyLayoutOrder() {
        val root = findViewById<LinearLayout>(R.id.browser_root) ?: return
        val toolbarRow = findViewById<View>(R.id.toolbar_row) ?: return
        // 内容锚点 = 套住 WebView 的那层权重 FrameLayout,重排时它的位置不动
        val contentFrame = findViewById<View>(R.id.content_frame) ?: return
        root.removeView(tabBarWrap)
        root.removeView(progressBar)
        root.removeView(toolbarRow)
        if (urlBarBottom) {
            if (tabsOnTop) {
                // 标签栏贴顶;网址栏行贴底,进度条紧贴在它上方
                root.addView(tabBarWrap, 0)
                root.addView(progressBar, root.indexOfChild(contentFrame) + 1)
                root.addView(toolbarRow, root.indexOfChild(contentFrame) + 2)
            } else {
                // 网址栏行贴顶,进度条紧随其后;标签栏贴底
                root.addView(toolbarRow, 0)
                root.addView(progressBar, 1)
                root.addView(tabBarWrap, root.indexOfChild(contentFrame) + 1)
            }
        } else if (tabsOnTop) {
            root.addView(tabBarWrap, 0)
            root.addView(toolbarRow, 1)
            root.addView(progressBar, 2)
        } else {
            // 原顺序:工具栏行 / 进度条 / 标签栏 / 内容
            root.addView(toolbarRow, 0)
            root.addView(progressBar, 1)
            root.addView(tabBarWrap, 2)
        }
    }

    /** 标签栏右缘箭头:下方弹出全部标签页列表(左缘三杠拖动排序,点行切换,右侧 × 关闭) */
    private fun showAllTabsPopup(anchor: View) {
        if (tabs.isEmpty()) return
        val density = resources.displayMetrics.density
        val recycler = RecyclerView(this)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.overScrollMode = View.OVER_SCROLL_NEVER
        val adapter = AllTabsAdapter { popupRef?.dismiss() }
        recycler.adapter = adapter
        // 三杠按住上下拖动换位,实时生效,松手把新顺序写回标签栏与存档
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
            override fun isLongPressDragEnabled() = false
            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int = makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.adapterPosition
                val to = target.adapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                adapter.moveItem(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                adapter.saveOrder()
            }
        })
        touchHelper.attachToRecyclerView(recycler)
        adapter.startDrag = { vh -> touchHelper.startDrag(vh) }
        val paint = TextView(this).apply { textSize = 15f }.paint
        var maxText = 0f
        tabs.forEach { maxText = maxOf(maxText, paint.measureText("✓ " + displayTitle(it))) }
        // 余量 = 三杠(32)+ 右 × 按钮(28)+ 行内边距(4+6)+ 标题左距(4)+ 呼吸(14)
        val width = (maxText + 88 * density).toInt().coerceIn(
            (180 * density).toInt(),
            (resources.displayMetrics.widthPixels * 0.8f).toInt()
        )
        val height = minOf(
            (tabs.size * 44 * density).toInt(),
            (resources.displayMetrics.heightPixels * 0.55f).toInt()
        )
        val popup = PopupWindow(recycler, width, height, true).apply {
            setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(ContextCompat.getColor(this@BrowserActivity, R.color.surface))
            )
            isOutsideTouchable = true
        }
        popupRef = popup
        popup.setOnDismissListener { popupRef = null }
        // 右对齐到箭头按钮下方
        popup.showAsDropDown(anchor, -(width - anchor.width), 0)
    }

    /**
     * 长按「菜单」或「全部标签页」按钮:直接隐藏/显示标签栏(不走菜单)。
     * 标签栏藏起后「全部标签页」按钮也随行隐藏,长按「菜单」按钮是唯一唤回入口。
     */
    private fun toggleTabBar() {
        tabBarHidden = !tabBarHidden
        tabBarWrap.visibility = if (tabBarHidden) View.GONE else View.VISIBLE
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_TAB_BAR_HIDDEN, tabBarHidden)
            .apply()
        // 隐藏后地址栏立刻换成标题,显示回来换回网址
        tabs.getOrNull(currentIndex)?.let { syncAddressBrowseText(it) }
    }

    /**
     * 全部标签页列表:左缘三杠按住拖动排序,点行切换,
     * 右侧 × 关闭(锁定则显示锁图标、点按只提示),当前项打 ✓。
     */
    private inner class AllTabsAdapter(
        private val onSwitch: () -> Unit
    ) : RecyclerView.Adapter<AllTabsAdapter.VH>() {

        /** 三杠按下时回调(弹窗里接好的 ItemTouchHelper 据此启动拖拽) */
        var startDrag: ((VH) -> Unit)? = null

        /** 本次拖拽是否动过顺序:没动过就不重排标签栏 */
        private var orderChanged = false

        inner class VH(
            v: View,
            val handle: View,
            val title: TextView,
            val close: ImageButton
        ) : RecyclerView.ViewHolder(v)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = layoutInflater.inflate(R.layout.item_browser_all_tab, parent, false)
            val vh = VH(
                v,
                v.findViewById(R.id.all_tab_handle),
                v.findViewById(R.id.all_tab_title),
                v.findViewById(R.id.all_tab_close)
            )
            // 按下三杠立即进入拖拽(不用长按);后续移动由 ItemTouchHelper 接管
            vh.handle.setOnTouchListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                    startDrag?.invoke(vh)
                }
                false
            }
            return vh
        }

        override fun getItemCount(): Int = tabs.size

        override fun onBindViewHolder(vh: VH, position: Int) {
            val item = tabs[position]
            val name = displayTitle(item).let { if (it.length > 16) it.take(16) + "…" else it }
            vh.title.text = if (position == currentIndex) "✓ $name" else name
            vh.itemView.setOnClickListener {
                val idx = tabs.indexOf(item)
                if (idx >= 0) {
                    showTab(idx, reveal = true)
                    onSwitch()
                }
            }
            // 锁定状态跟随标签栏:锁定显示锁图标、点按只提示;未锁定显示 × 直接关闭
            if (item.locked) {
                vh.close.setImageResource(R.drawable.ic_lock_tight)
                vh.close.contentDescription = getString(R.string.browser_tab_locked_hint)
                vh.close.setOnClickListener {
                    android.widget.Toast.makeText(
                        this@BrowserActivity, R.string.browser_tab_locked_hint,
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                vh.close.setImageResource(R.drawable.ic_close_tight)
                vh.close.contentDescription = getString(R.string.close_tab)
                vh.close.setOnClickListener {
                    val idx = tabs.indexOf(item)
                    if (idx >= 0) {
                        closeTabAt(idx)
                        notifyDataSetChanged()
                    }
                }
            }
        }

        /** 拖拽换位:tabs 同步移动,currentIndex 跟着自己的标签走 */
        fun moveItem(from: Int, to: Int) {
            if (from == to || from !in tabs.indices || to !in tabs.indices) return
            val item = tabs.removeAt(from)
            tabs.add(to, item)
            currentIndex = when {
                currentIndex == from -> to
                from < to && currentIndex in from + 1..to -> currentIndex - 1
                from > to && currentIndex in to until from -> currentIndex + 1
                else -> currentIndex
            }
            orderChanged = true
            notifyItemMoved(from, to)
        }

        /** 拖拽结束:标签栏按新顺序重建并落盘 */
        fun saveOrder() {
            if (!orderChanged) return
            orderChanged = false
            rebuildTabLayout()
            persistTabs()
        }
    }

    /** ListPopupWindow 菜单:工具栏菜单与标签页长按菜单共用;onDismiss 在收起时回调 */
    private fun showListPopup(anchor: View, actions: List<BrowserMenuAction>, onDismiss: (() -> Unit)? = null) {
        val popup = ListPopupWindow(this)
        popup.anchorView = anchor
        popup.isModal = true
        popup.setContentWidth(measureMenuWidth(actions))
        popup.setOnDismissListener { onDismiss?.invoke() }
        popup.setAdapter(object : BaseAdapter() {
            override fun getCount(): Int = actions.size
            override fun getItem(position: Int): BrowserMenuAction = actions[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView
                    ?: layoutInflater.inflate(R.layout.item_browser_menu, parent, false)
                v.findViewById<TextView>(R.id.menu_item_text).text = actions[position].label
                v.setOnClickListener {
                    popup.dismiss()
                    actions[position].action()
                }
                v.setOnLongClickListener {
                    popup.dismiss()
                    actions[position].longAction?.invoke()
                    true
                }
                return v
            }
        })
        popup.show()
    }

    /** 标签页长按菜单:锁定/解锁、重命名、关闭(锁定的标签同样能从这里关闭) */
    private fun showTabMenu(index: Int, anchor: View) {
        val item = tabs.getOrNull(index) ?: return
        val actions = listOf(
            BrowserMenuAction(
                getString(if (item.locked) R.string.browser_tab_unlock else R.string.browser_tab_lock),
                action = {
                    item.locked = !item.locked
                    renderBrowserTabs()
                    // 锁定/解锁立即落盘:退出保存不只在导航时写,否则划卡清理丢状态
                    persistTabs()
                }
            ),
            BrowserMenuAction(getString(R.string.browser_tab_rename), action = { showRenameDialog(item) }),
            BrowserMenuAction(getString(R.string.close_tab), action = {
                val pos = tabs.indexOf(item)
                if (pos >= 0) closeTabAt(pos)
            })
        )
        showListPopup(anchor, actions)
    }

    /**
     * 重命名标签页:确定后标题固定,不再跟随网页;
     * 再次重命名并清空内容确定,则恢复跟随网页标题。
     */
    private fun showRenameDialog(item: TabItem) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val input = EditText(this).apply {
            setSingleLine(true)
            hint = getString(R.string.browser_rename_hint)
            setText(item.customTitle ?: item.title)
        }
        box.addView(input)
        AlertDialog.Builder(this)
            .setTitle(R.string.browser_rename_title)
            .setView(box)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    item.customTitle = null
                    item.title = pageTabTitle(item, item.webView.title)
                } else {
                    item.customTitle = name
                    item.title = name
                }
                renderBrowserTabs()
                persistTabs()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 菜单宽度:按最长文字测量,并保证不至于太窄 */
    private fun measureMenuWidth(actions: List<BrowserMenuAction>): Int {
        val density = resources.displayMetrics.density
        val paint = TextView(this).apply { textSize = 16f }.paint
        var max = 0f
        actions.forEach { max = maxOf(max, paint.measureText(it.label)) }
        return (max + 40 * density).toInt().coerceAtLeast((180 * density).toInt())
    }

    /** 在系统浏览器打开当前标签页地址(优先 WebView 实际地址,其次最近可导航地址) */
    private fun openCurrentInSystemBrowser() {
        val tab = tabs.getOrNull(currentIndex) ?: return
        val url = tab.webView.url?.takeIf { !isInternalOrBlankUrl(it) }
            ?: tab.url.takeIf { !isInternalOrBlankUrl(it) }
            ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            android.widget.Toast.makeText(
                this, R.string.browser_no_browser, android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun toggleDesktopMode() {
        desktopMode = !desktopMode
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_DESKTOP, desktopMode)
            .apply()
        tabs.forEach { applyDesktopMode(it.webView) }
        // 重新加载当前页:UA + 视口注入都生效
        forceRefreshCurrent()
        val tip = if (desktopMode) {
            "电脑模式已开:按桌面宽度排版,双指缩放可点细节"
        } else {
            "电脑模式已关:恢复手机排版"
        }
        android.widget.Toast.makeText(this, tip, android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * 电脑模式关键不只是 UA:
     * 很多站点用 CSS 媒体查询看「布局宽度」,手机屏宽会一直出移动布局。
     * 做法:桌面 UA + 强制 layout 视口约 [DESKTOP_LAYOUT_WIDTH] + 整页缩放到屏幕宽(可 pinch)。
     */
    private fun applyDesktopMode(wv: WebView) {
        val s = wv.settings
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.useWideViewPort = true
        if (desktopMode) {
            s.userAgentString = desktopUserAgent
            // overview:先整页缩进屏幕,再双指放大操作
            s.loadWithOverviewMode = true
            val widthPx = resources.displayMetrics.widthPixels.coerceAtLeast(1)
            val scale = ((widthPx * 100f) / DESKTOP_LAYOUT_WIDTH)
                .toInt()
                .coerceIn(20, 100)
            wv.setInitialScale(scale)
        } else {
            s.userAgentString = mobileUserAgent
            s.loadWithOverviewMode = true
            // 0 = 系统默认,回到随控件宽度
            wv.setInitialScale(0)
        }
    }

    /** 页面加载后注入:强制 viewport / 欺骗 min-width 媒体查询 / 最小内容宽度 */
    private fun injectDesktopLayout(view: WebView?) {
        if (!desktopMode || view == null) return
        val w = DESKTOP_LAYOUT_WIDTH
        // 单行 JS,避免 Kotlin 原始字符串里引号地狱
        val js = """
            (function(){
              var W=$w;
              try {
                var meta=document.querySelector('meta[name="viewport"]');
                if(!meta){
                  meta=document.createElement('meta');
                  meta.setAttribute('name','viewport');
                  (document.head||document.documentElement).appendChild(meta);
                }
                meta.setAttribute('content','width='+W+', initial-scale=1, minimum-scale=0.2, maximum-scale=5, user-scalable=yes');
              } catch(e) {}
              try {
                if(!window.__ldmBrowserMatchMediaPatched && window.matchMedia){
                  window.__ldmBrowserMatchMediaPatched=true;
                  var orig=window.matchMedia.bind(window);
                  window.matchMedia=function(query){
                    try {
                      var q=String(query||'');
                      var mMin=q.match(/min-width:\s*(\d+)px/i);
                      if(mMin && parseInt(mMin[1],10)<=W){
                        return {matches:true,media:q,onchange:null,addListener:function(){},removeListener:function(){},addEventListener:function(){},removeEventListener:function(){},dispatchEvent:function(){return false;}};
                      }
                      var mMax=q.match(/max-width:\s*(\d+)px/i);
                      if(mMax && parseInt(mMax[1],10)<W){
                        return {matches:false,media:q,onchange:null,addListener:function(){},removeListener:function(){},addEventListener:function(){},removeEventListener:function(){},dispatchEvent:function(){return false;}};
                      }
                    } catch(e) {}
                    return orig(query);
                  };
                }
              } catch(e) {}
              try {
                if(!document.getElementById('ldmbrowser-desktop-css')){
                  var st=document.createElement('style');
                  st.id='ldmbrowser-desktop-css';
                  st.textContent='html,body{min-width:'+W+'px!important;}';
                  (document.head||document.documentElement).appendChild(st);
                }
              } catch(e) {}
              try { window.dispatchEvent(new Event('resize')); } catch(e) {}
            })();
        """.trimIndent()
        try {
            view.evaluateJavascript(js, null)
        } catch (_: Exception) {
            // 旧 WebView 忽略
        }
    }

    private fun buildDesktopUa(raw: String?): String {
        val src = raw.orEmpty()
        // 尽量保留系统 Chrome 版本号,去掉 Mobile 痕迹,改成桌面 Windows UA
        val chrome = Regex("""Chrome/[\d.]+""").find(src)?.value ?: "Chrome/120.0.0.0"
        // 明确无 Mobile / 带 Windows,部分站点只认这个
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) $chrome Safari/537.36"
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(url: String): WebView {
        val wv = WebView(this)
        wv.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        // 与主题一致:日间淡粉 / 夜间深色
        wv.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))
        val settings = wv.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.setSupportZoom(true)
        settings.mediaPlaybackRequiresUserGesture = false
        if (mobileUserAgent.isBlank()) {
            mobileUserAgent = settings.userAgentString ?: ""
        }
        applyDesktopMode(wv)

        // 网页请求下载文件:内置下载器接管 —— blob:/data: 原生落盘,
        // http(s) 确认后交系统 DownloadManager,其余 scheme 转交外部应用
        wv.setDownloadListener { url, ua, disposition, mimeType, contentLength ->
            if (url.isNullOrBlank() || isFinishing || isDestroyed) return@setDownloadListener
            handleDownloadRequest(wv, url, ua, disposition, mimeType, contentLength)
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString()
                // 自定义 scheme(baiduboxapp://、weixin://、intent://、tel: 等)交给外部应用,
                // 留给 WebView 自己加载只会报 ERR_UNKNOWN_URL_SCHEME
                if (request?.isForMainFrame == true && url != null && isExternalSchemeUrl(url)) {
                    launchExternalScheme(url)
                    return true
                }
                // 页面内点链接:导航一发起点就置加载态(不等 onPageStarted——
                // 慢站点要等 DNS/握手/首包,那期间用户应当看到停止按钮)
                if (request?.isForMainFrame == true && view != null) {
                    markLoading(tabs.firstOrNull { it.webView == view })
                }
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                markErrorPage(view, false)
                rememberNavigableUrl(view, url)
                val index = tabs.indexOfFirst { it.webView == view }
                if (index >= 0) {
                    tabs[index].isLoading = true
                    // 进度反馈只跟当前标签:后台标签开始加载时不能点亮当前页的进度条
                    if (index == currentIndex) {
                        progressBar.visibility = View.VISIBLE
                        progressBar.progress = 5
                        // 隐藏标签栏:加载中显示网址,加载完成由 onPageFinished 换回标题
                        if (tabBarHidden && !addressBar.hasFocus()) {
                            addressBar.setText(url ?: tabs[index].url)
                        }
                    }
                }
                // 尽早注入,减少 SPA 先按手机宽度初始化
                injectDesktopLayout(view)
                // blob 下载文件名钩子:捕获 <a download href="blob:..."> 的文件名
                injectBlobDownloadHook(view)
                updateToolbarState()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 强制刷新用的 LOAD_NO_CACHE 只作用于一次请求,完成后恢复默认
                view?.settings?.cacheMode = WebSettings.LOAD_DEFAULT
                tabs.firstOrNull { it.webView == view }?.let {
                    // 先提交历史再落盘,存档里的前进后退才是最新状态
                    commitHistory(it, url)
                    it.isLoading = false
                }
                rememberNavigableUrl(view, url)
                injectDesktopLayout(view)
                injectBlobDownloadHook(view)
                val index = tabs.indexOfFirst { it.webView == view }
                if (index == currentIndex && index >= 0) {
                    if (isInternalOrBlankUrl(url)) {
                        addressBar.setText(addressBrowseText(tabs[index]))
                    }
                    // 隐藏标签栏:加载完成回到标题显示(失败页保持网址,编辑中不打扰)
                    if (tabBarHidden && !tabs[index].isLoading &&
                        !tabs[index].showingErrorPage && !addressBar.hasFocus()
                    ) {
                        addressBar.setText(addressBrowseText(tabs[index]))
                    }
                }
                updateToolbarState()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame != true || view == null) return
                // 不往 WebView 里塞 data: 错误页 —— 那会让 reload() 永远重放错误页,
                // 普通刷新永远恢复不了。WebView 保持真实地址,用原生覆盖层提示。
                val msg = error?.description?.toString()
                    ?: error?.errorCode?.toString()
                    ?: "网络错误"
                val failUrl = request.url?.toString().orEmpty()
                tabs.firstOrNull { it.webView == view }?.let {
                    // 自定义 scheme 等非网页地址不写进标签,避免污染持久化(重启后反复打开失败页)
                    if (isWebUrl(failUrl)) it.url = failUrl
                    it.showingErrorPage = true
                    it.errorDetail = msg
                    it.isLoading = false
                }
                persistTabs()
                val index = tabs.indexOfFirst { it.webView == view }
                if (index == currentIndex && failUrl.isNotEmpty()) {
                    addressBar.setText(failUrl)
                    errorUrlText.text = failUrl
                    errorReasonText.text = msg
                    errorOverlay.visibility = View.VISIBLE
                }
                progressBar.visibility = View.GONE
                updateToolbarState()
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                // 顶部进度条只跟随当前标签(后台标签的加载不打扰当前页显示)
                if (view == null || tabs.getOrNull(currentIndex)?.webView !== view) return
                // 加载中有反馈,100% 后收起
                if (newProgress >= 100) {
                    progressBar.progress = 100
                    progressBar.visibility = View.GONE
                } else {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress.coerceAtLeast(5)
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                val index = tabs.indexOfFirst { it.webView == view }
                // 用户重命名过的标签标题固定,不再跟随网页
                if (index >= 0 && tabs[index].customTitle == null) {
                    val customHome = homeUrl()
                    val customHomeTitle = homeTitle()
                    tabs[index].title = if (
                        // 停在自定义主页时固定显示用户起的标题,导航到别处后恢复页面标题
                        customHome != null && customHomeTitle != null && tabs[index].url == customHome
                    ) {
                        customHomeTitle
                    } else {
                        pageTabTitle(tabs[index], title)
                    }
                    renderBrowserTabs()
                    // 隐藏标签栏时地址栏浏览态跟随最新标题(加载中先保持网址,编辑中不打扰)
                    if (index == currentIndex && tabBarHidden && !tabs[index].isLoading) {
                        syncAddressBrowseText(tabs[index])
                    }
                }
                // 历史记录标题回填:标题早于提交先到 → 暂存,提交时带上;
                // 晚到(含 SPA 改址后才改标题)→ 按标签当前提交地址回填
                val pageTitle = title?.trim().orEmpty()
                val viewUrl = view?.url
                if (pageTitle.isNotEmpty() && viewUrl != null) {
                    if (pendingVisitTitles.size > 32) pendingVisitTitles.clear()
                    pendingVisitTitles[viewUrl] = pageTitle
                }
                val titleTab = view?.let { v -> tabs.firstOrNull { it.webView == v } }
                if (titleTab != null && !titleTab.isLoading && pageTitle.isNotEmpty()) {
                    applyVisitTitle(titleTab.history.getOrNull(titleTab.historyIndex), pageTitle)
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams?
            ): Boolean {
                // 上一次回调若还挂着必须先清掉,否则 WebView 不再弹下一次
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = callback
                val intent = buildFileChooserIntent(params)
                return try {
                    fileChooserLauncher.launch(
                        Intent.createChooser(intent, getString(R.string.browser_choose_file))
                    )
                    true
                } catch (_: ActivityNotFoundException) {
                    fileChooserCallback = null
                    callback.onReceiveValue(null)
                    android.widget.Toast.makeText(
                        this@BrowserActivity,
                        R.string.browser_no_file_picker,
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    true
                }
            }
        }
        // 历史记录/收藏页等虚拟地址不在这里 load(由调用方 loadDataWithBaseURL 填充)
        // 历史记录页挂 JS 桥:长按菜单 / 多选删除;收藏页挂桥:长按菜单;
        // 下载管理页挂桥:点条目打开 / 长按菜单;blob 桥挂所有页面(下载请求到达时钩子已就位)
        if (url == HISTORY_URL) wv.addJavascriptInterface(HistoryBridge(), "LdmHistory")
        if (url == BOOKMARKS_URL) wv.addJavascriptInterface(BookmarksBridge(), "LdmBookmarks")
        if (url == DOWNLOADS_URL) wv.addJavascriptInterface(DownloadsBridge(), "LdmDownloads")
        wv.addJavascriptInterface(BlobBridge(), "LdmBlob")
        if (isWebUrl(url)) wv.loadUrl(url)
        // 记录触摸时刻(不消费事件):外部 scheme 打不开时,只有手势触发的才提示
        wv.setOnTouchListener { _, _ ->
            lastTouchTime = SystemClock.elapsedRealtime()
            false
        }
        return wv
    }

    /** 新建标签;[restored] 不为空时直接挂上恢复好的标签页(会话恢复路径) */
    private fun addTab(title: String, url: String, restored: TabItem? = null) {
        val tab = restored ?: TabItem(title, url, createWebView(url))
        // 新标签一创建就处于加载中:立即显示停止按钮/进度条(恢复的标签同样在加载当前页)
        markLoading(tab)
        tabs.add(tab)
        // 已有「+」尾项时,新标签插到它前面,保证「+」始终在最后
        val pos = if (plusTab != null) tabLayout.tabCount - 1 else tabLayout.tabCount
        tabLayout.addTab(tabLayout.newTab(), pos)
        renderBrowserTabs()
        browserEmpty.visibility = View.GONE
        persistTabs()
    }

    /**
     * 切到指定标签。[reveal]=true 时把标签滚进可视范围;[updateSelection]=false 时不调
     * TabLayout 的 select()(其选中动画自带滚动)—— 手动点标签用它,保证零位移;
     * 「全部标签页」弹窗、新标签打开、外部跳转等「跳过去」的场景两者都开。
     */
    private fun showTab(index: Int, reveal: Boolean = false, updateSelection: Boolean = true) {
        if (index !in tabs.indices) {
            if (tabs.isEmpty()) showEmptyState()
            return
        }
        browserEmpty.visibility = View.GONE
        currentIndex = index
        persistTabs()
        clearWebFocusBeforeSwap()
        webContainer.removeAllViews()
        val wv = tabs[index].webView
        (wv.parent as? ViewGroup)?.removeView(wv)
        webContainer.addView(wv)
        applyDesktopMode(wv)
        injectDesktopLayout(wv)
        if (updateSelection && tabLayout.selectedTabPosition != index) {
            tabLayout.getTabAt(index)?.select()
        }
        renderBrowserTabs()
        val live = wv.url
        addressBar.setText(
            when {
                // 虚拟页(历史记录/收藏/下载管理)地址栏留空
                isHistoryTab(tabs[index]) || isBookmarksTab(tabs[index]) ||
                    isDownloadsTab(tabs[index]) -> ""
                // 隐藏标签栏:浏览态显示页面标题(该标签正在加载则显示网址),编辑态才换回网址
                tabBarHidden -> if (tabs[index].isLoading) (live ?: tabs[index].url)
                else displayTitle(tabs[index])
                isInternalOrBlankUrl(live) -> tabs[index].url
                else -> (live ?: tabs[index].url)
            }
        )
        // 进度条跟随新当前标签的加载状态
        progressBar.visibility = if (tabs[index].isLoading) View.VISIBLE else View.GONE
        syncErrorOverlay(tabs[index])
        updateToolbarState()
        // 切到下载管理页立即推送最新数据:引擎空闲时没有回调,
        // 不推的话页面停留在外面操作期间(下载完成/删除)的旧内容
        if (isDownloadsTab(tabs[index])) pushDownloadsData()
        if (reveal) revealCurrentTab()
    }

    /** 把选中的标签滚进标签栏可视范围(标签多时,新开/切到的标签可能排在屏幕外) */
    private fun revealCurrentTab() {
        tabLayout.post {
            if (currentIndex in tabs.indices && currentIndex < tabLayout.tabCount) {
                tabLayout.setScrollPosition(currentIndex, 0f, false)
            }
        }
    }

    private fun showEmptyState() {
        currentIndex = 0
        clearWebFocusBeforeSwap()
        webContainer.removeAllViews()
        browserEmpty.visibility = View.VISIBLE
        errorOverlay.visibility = View.GONE
        addressBar.setText("")
        updateToolbarState()
    }

    /**
     * 摘走 webContainer 里的 WebView 前先收掉焦点:带焦点的视图直接 remove,框架会
     * 无条件把窗口焦点兜底重派(ViewGroup.removeAllViewsInLayout → rootViewRequestFocus,
     * 不看触摸模式),DecorView 按 FOCUS_DOWN 找到的第一个可聚焦控件是地址栏 EditText,
     * 于是编辑态外壳(引擎/动作按钮、快捷输入条)连带键盘莫名弹出。
     * 触摸模式下 clearFocus 不触发兜底,焦点静默清空,正好是想要的浏览态。
     */
    private fun clearWebFocusBeforeSwap() {
        for (i in 0 until webContainer.childCount) {
            webContainer.getChildAt(i).takeIf { it.hasFocus() }?.clearFocus()
        }
    }

    /** 关闭指定标签(× 按钮与长按菜单共用;锁定的标签同样能从这里关闭) */
    private fun closeTabAt(index: Int) {
        val removed = tabs.getOrNull(index) ?: return
        tabs.removeAt(index)
        removed.webView.stopLoading()
        removed.webView.destroy()
        persistTabs()
        if (tabLayout.tabCount > index) {
            tabLayout.removeTabAt(index)
        }
        if (tabs.isEmpty()) {
            // 允许关闭最后一个标签:关闭后显示空状态
            showEmptyState()
            return
        }
        when {
            index < currentIndex -> currentIndex -= 1
            index == currentIndex -> currentIndex = index.coerceAtMost(tabs.lastIndex)
        }
        showTab(currentIndex)
    }

    /**
     * 输入是否像网址:决定右侧按钮显示 →(前往)还是 🔍(搜索)。
     * 与 normalizeInputToUrl 的判断同源,保证图标和实际行为一致。
     */
    private fun inputLooksLikeUrl(rawInput: String): Boolean {
        val input = rawInput.trim()
        if (input.isEmpty()) return false
        val lower = input.lowercase(Locale.ROOT)
        val hasScheme = lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("file://")
        if (hasScheme) return true
        val looksLikeHost = input.contains('.') || lower.startsWith("localhost") ||
            lower.startsWith("127.0.0.1") || lower.startsWith("[::1]")
        return looksLikeHost && !input.contains(' ')
    }

    /** 地址栏编辑态(有焦点)才显示引擎选择、动作按钮与快捷输入条;浏览态收起,右侧换成收藏星 */
    private fun updateAddressChrome() {
        val editing = addressBar.hasFocus()
        btnEngine.visibility = if (editing) View.VISIBLE else View.GONE
        btnAction.visibility = if (editing) View.VISIBLE else View.GONE
        btnBookmark.visibility = if (editing) View.GONE else View.VISIBLE
        inputHelperBar.visibility = if (editing) View.VISIBLE else View.GONE
    }

    /** 按输入内容切换右侧按钮图标与描述(空输入默认显示搜索) */
    private fun updateAddressActionIcon() {
        if (inputLooksLikeUrl(addressBar.text?.toString().orEmpty())) {
            btnAction.setImageResource(R.drawable.ic_arrow_forward)
            btnAction.contentDescription = getString(R.string.browser_go)
        } else {
            btnAction.setImageResource(R.drawable.ic_search)
            btnAction.contentDescription = getString(R.string.browser_search)
        }
    }

    /** 右侧动作按钮:跟着当前图标走 —— → 当网址打开,🔍 用所选引擎搜索 */
    private fun performAddressAction() {
        val input = addressBar.text?.toString()?.trim().orEmpty()
        if (input.isEmpty()) {
            addressBar.requestFocus()
            return
        }
        loadAddress(
            if (inputLooksLikeUrl(input)) normalizeInputToUrl(input) else searchUrl(input)
        )
    }

    /** 当前所选搜索引擎的搜索链接 */
    private fun searchUrl(query: String): String {
        val engine = searchEngines[engineIndex.coerceIn(0, searchEngines.lastIndex)]
        return engine.queryPrefix + URLEncoder.encode(query, "UTF-8")
    }

    /** 持久化的引擎 key → 下标(未知 key 回退百度) */
    private fun resolveEngineIndex(key: String?): Int =
        searchEngines.indexOfFirst { it.key == key }.takeIf { it >= 0 } ?: 0

    /** 引擎选择弹出:与浏览器菜单同款 ListPopupWindow,当前项前打勾 */
    private fun showEnginePopup(anchor: View) {
        val popup = ListPopupWindow(this)
        popup.anchorView = anchor
        popup.isModal = true
        popup.setContentWidth(measureEnginePopupWidth())
        popup.setAdapter(object : BaseAdapter() {
            override fun getCount(): Int = searchEngines.size
            override fun getItem(position: Int): SearchEngine = searchEngines[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView
                    ?: layoutInflater.inflate(R.layout.item_browser_menu, parent, false)
                val label = getString(searchEngines[position].labelRes)
                v.findViewById<TextView>(R.id.menu_item_text).text =
                    if (position == engineIndex) "✓ $label" else label
                v.setOnClickListener {
                    popup.dismiss()
                    selectEngine(position)
                }
                return v
            }
        })
        popup.show()
    }

    private fun measureEnginePopupWidth(): Int {
        val density = resources.displayMetrics.density
        val paint = TextView(this).apply { textSize = 16f }.paint
        var max = 0f
        searchEngines.forEach { max = maxOf(max, paint.measureText(getString(it.labelRes))) }
        return (max + 40 * density).toInt().coerceAtLeast((120 * density).toInt())
    }

    /** 切换引擎:更新按钮显示并持久化,之后的搜索都走新引擎 */
    private fun selectEngine(index: Int) {
        engineIndex = index.coerceIn(0, searchEngines.lastIndex)
        btnEngine.text = getString(searchEngines[engineIndex].labelRes)
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_ENGINE, searchEngines[engineIndex].key)
            .apply()
    }

    /**
     * 按当前 tabs 顺序整体重建 TabLayout(排序后调用)。
     * 重建期间摘掉选择监听:新挂上的标签自动选中不该再触发 showTab(WebView 本就挂在容器里)。
     */
    private fun rebuildTabLayout() {
        tabLayout.clearOnTabSelectedListeners()
        tabLayout.removeAllTabs()
        plusTab = null
        repeat(tabs.size) { tabLayout.addTab(tabLayout.newTab()) }
        plusTab = tabLayout.newTab()
        tabLayout.addTab(plusTab!!)
        if (currentIndex !in tabs.indices) currentIndex = 0
        tabLayout.getTabAt(currentIndex)?.select()
        tabLayout.addOnTabSelectedListener(tabSelectedListener)
        renderBrowserTabs()
    }

    private fun loadAddress(rawInput: String) {
        val url = normalizeInputToUrl(rawInput)
        if (url.isEmpty()) {
            addressBar.clearFocus()
            return
        }
        if (tabs.isEmpty()) {
            // 空状态:地址栏输入即新建标签页
            addTab(shortHost(url), url)
            showTab(tabs.lastIndex)
        } else {
            val tab = tabs.getOrNull(currentIndex) ?: return
            tab.url = url
            tab.webView.loadUrl(url)
            markLoading(tab)
        }
        addressBar.clearFocus()
        hideKeyboard()
        // 先收焦点(隐藏标签栏时失焦会恢复标题显示),再显示目标网址,标题到达后由 onReceivedTitle 换回
        addressBar.setText(url)
    }

    /** 工具栏/返回键后退:走自管历史(只有它才随会话持久化,重启后仍能后退) */
    private fun tabGoBack(tab: TabItem) {
        if (tab.historyIndex <= 0) return
        navigateHistory(tab, tab.historyIndex - 1)
    }

    /** 工具栏前进:走自管历史 */
    private fun tabGoForward(tab: TabItem) {
        if (tab.historyIndex >= tab.history.lastIndex) return
        navigateHistory(tab, tab.historyIndex + 1)
    }

    private fun navigateHistory(tab: TabItem, target: Int) {
        tab.historyIndex = target.coerceIn(0, tab.history.lastIndex)
        val url = tab.history[tab.historyIndex]
        // 同步回写 tab.url:落到历史页时必须改回虚拟地址,否则刷新/地址栏的
        // isHistoryTab 判断失效(表现为地址栏停在网站、刷新回网站)
        tab.url = url
        // 离开失败页:覆盖层先收起,真实状态由 onPageStarted / onReceivedError 再同步
        tab.showingErrorPage = false
        tab.errorDetail = ""
        if (tabs.getOrNull(currentIndex) === tab) {
            errorOverlay.visibility = View.GONE
            addressBar.setText(
                if (url == HISTORY_URL || url == BOOKMARKS_URL || url == DOWNLOADS_URL) "" else url
            )
        }
        tab.webView.stopLoading()
        markLoading(tab)
        if (url == HISTORY_URL) {
            loadHistoryPageInto(tab)
        } else if (url == BOOKMARKS_URL) {
            loadBookmarksPageInto(tab)
        } else if (url == DOWNLOADS_URL) {
            loadDownloadsPageInto(tab)
        } else {
            tab.webView.loadUrl(url)
        }
        updateToolbarState()
    }

    /**
     * 页面提交后维护自管历史:
     * - 与当前项相同(刷新、会话恢复后的重载)游标不动
     * - 与相邻项相同(后退/前进加载完成)移动游标
     * - http→https 同址升级原位替换,重定向不产生垃圾项
     * - 其余视为新导航:截断当前项之后的前进分支再追加
     * 无论走哪个分支,只要成功提交就记录一次访问(失败页除外):
     * 刷新、前进、后退、新标签首开、会话恢复后的重载都算。
     */
    private fun commitHistory(tab: TabItem, url: String?) {
        val u = url ?: return
        if (!isWebUrl(u)) return
        val i = tab.historyIndex
        when {
            u == tab.history.getOrNull(i) -> {}
            u == tab.history.getOrNull(i + 1) -> tab.historyIndex = i + 1
            u == tab.history.getOrNull(i - 1) -> tab.historyIndex = i - 1
            else -> {
                val cur = tab.history.getOrNull(i)
                if (cur != null && cur.startsWith("http://") &&
                    u == "https://" + cur.removePrefix("http://")
                ) {
                    tab.history[i] = u
                } else {
                    while (tab.history.size > i + 1) tab.history.removeAt(tab.history.lastIndex)
                    tab.history.add(u)
                    tab.historyIndex = tab.history.lastIndex
                }
            }
        }
        if (!tab.showingErrorPage) recordVisit(u)
    }

    /**
     * 点输入框以外:收起键盘并退出编辑态(引擎/动作按钮随之收起)。
     * 在 dispatchTouchEvent 里拦(WebView 等子视图会消费触摸,Activity.onTouchEvent 收不到);
     * 只处理 DOWN 且不消费事件,点按本身照常派发给子视图。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && addressBar.hasFocus()) {
            val loc = IntArray(2)
            addressBox.getLocationOnScreen(loc)
            val outside = ev.rawX < loc[0] || ev.rawX > loc[0] + addressBox.width ||
                ev.rawY < loc[1] || ev.rawY > loc[1] + addressBox.height
            if (outside) {
                // 快捷输入条上的点按不算「点输入框以外」,否则条还没用就被收起
                val barLoc = IntArray(2)
                inputHelperBar.getLocationOnScreen(barLoc)
                val onBar = ev.rawX >= barLoc[0] && ev.rawX <= barLoc[0] + inputHelperBar.width &&
                    ev.rawY >= barLoc[1] && ev.rawY <= barLoc[1] + inputHelperBar.height
                if (!onBar) {
                    addressBar.clearFocus()
                    hideKeyboard()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(addressBar.windowToken, 0)
    }

    /**
     * 强制刷新(电脑 Ctrl+F5 语义):
     * - 本标签 HTTP 缓存一并清掉(clearCache + LOAD_NO_CACHE,onPageFinished 恢复 LOAD_DEFAULT)
     * - 处于失败状态时必须 loadUrl 原地址(reload 只会重放失败的导航)
     */
    private fun forceRefreshCurrent() {
        val tab = tabs.getOrNull(currentIndex) ?: return
        // 虚拟页(历史记录/收藏/下载管理):重新生成 HTML 即为刷新
        if (isHistoryTab(tab)) {
            loadHistoryPageInto(tab)
            return
        }
        if (isBookmarksTab(tab)) {
            loadBookmarksPageInto(tab)
            return
        }
        if (isDownloadsTab(tab)) {
            loadDownloadsPageInto(tab)
            return
        }
        if (tab.showingErrorPage) {
            retryCurrent(force = true)
            return
        }
        val live = tab.webView.url
        val target = when {
            !isInternalOrBlankUrl(live) -> live!!
            !isInternalOrBlankUrl(tab.url) -> tab.url
            homeUrl() != null -> homeUrl()!!
            else -> return
        }
        tab.url = target
        addressBar.setText(target)
        addressBar.clearFocus()
        tab.webView.stopLoading()
        tab.webView.clearCache(true)
        tab.webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        markLoading(tab)
        if (target == live) tab.webView.reload() else tab.webView.loadUrl(target)
    }

    /**
     * 普通刷新(工具栏刷新按钮):reload 当前页,遵循缓存。
     * 失败状态覆盖层下 WebView 里仍是真实地址,但 reload 会重放失败的导航,
     * 所以失败时转 loadUrl 原地址(普通绕过缓存重新请求)。
     */
    private fun normalRefreshCurrent() {
        val tab = tabs.getOrNull(currentIndex) ?: return
        if (isHistoryTab(tab)) {
            loadHistoryPageInto(tab)
            return
        }
        if (isBookmarksTab(tab)) {
            loadBookmarksPageInto(tab)
            return
        }
        if (isDownloadsTab(tab)) {
            loadDownloadsPageInto(tab)
            return
        }
        if (tab.showingErrorPage || isInternalOrBlankUrl(tab.webView.url)) {
            retryCurrent(force = false)
            return
        }
        addressBar.clearFocus()
        markLoading(tab)
        tab.webView.reload()
    }

    /** 从失败状态恢复:隐藏覆盖层,重新请求真实地址 */
    private fun retryCurrent(force: Boolean) {
        val tab = tabs.getOrNull(currentIndex) ?: return
        val target = if (!isInternalOrBlankUrl(tab.url)) tab.url else homeUrl() ?: return
        tab.url = target
        tab.showingErrorPage = false
        tab.errorDetail = ""
        errorOverlay.visibility = View.GONE
        addressBar.setText(target)
        addressBar.clearFocus()
        tab.webView.stopLoading()
        markLoading(tab)
        if (force) tab.webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        tab.webView.loadUrl(target)
    }

    private fun markErrorPage(view: WebView?, isError: Boolean) {
        if (view == null) return
        val tab = tabs.firstOrNull { it.webView == view } ?: return
        tab.showingErrorPage = isError
        if (!isError) tab.errorDetail = ""
        // 当前标签状态变化时同步覆盖层;切标签由 showTab 自己同步
        if (tabs.getOrNull(currentIndex) === tab) {
            errorOverlay.visibility = if (isError) View.VISIBLE else View.GONE
            if (isError) {
                errorUrlText.text = tab.url
                errorReasonText.text = tab.errorDetail
            }
        }
    }

    /** 切换标签 / 空状态时,按当前标签的错误状态显示覆盖层 */
    private fun syncErrorOverlay(tab: TabItem?) {
        if (tab == null || !tab.showingErrorPage) {
            errorOverlay.visibility = View.GONE
            return
        }
        errorUrlText.text = tab.url
        errorReasonText.text = tab.errorDetail
        errorOverlay.visibility = View.VISIBLE
    }

    /**
     * 清除数据弹窗(代码构建:14sp 提示文字 + 13sp CheckBox + 底栏 继续/取消):
     * 前四项勾选数据范围,末尾「仅当前网站」是作用域开关 —— 勾上时「继续」按勾选的
     * 范围只清当前网站,不勾则清所有网站;一项数据都没勾点继续给提示后留在弹窗。
     */
    private fun confirmClearBrowserData() {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        box.addView(TextView(this).apply {
            text = getString(R.string.browser_clear_scope_hint)
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@BrowserActivity, R.color.on_surface))
            setLineSpacing(0f, 1.1f)
        })
        val cbCache = CheckBox(this).apply {
            text = getString(R.string.browser_clear_scope_cache)
            textSize = 13f
        }
        val cbCookies = CheckBox(this).apply {
            text = getString(R.string.browser_clear_scope_cookies)
            textSize = 13f
        }
        val cbStorage = CheckBox(this).apply {
            text = getString(R.string.browser_clear_scope_storage)
            textSize = 13f
        }
        val cbHistory = CheckBox(this).apply {
            text = getString(R.string.browser_clear_scope_history)
            textSize = 13f
        }
        box.addView(cbCache)
        box.addView(cbCookies)
        box.addView(cbStorage)
        box.addView(cbHistory)
        // 作用域开关:勾上 =「继续」按上面的范围只清当前网站;不勾 = 清所有网站
        val cbSiteOnly = CheckBox(this).apply {
            text = getString(R.string.browser_clear_site_only)
            textSize = 13f
        }
        box.addView(cbSiteOnly)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.browser_clear_data)
            .setView(box)
            .setPositiveButton(R.string.browser_clear_continue, null)
            .setNegativeButton(R.string.cancel, null)
            .show()
        // 清理完成才关弹窗,没有可清的网站时提示后留在弹窗
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            if (!cbCache.isChecked && !cbCookies.isChecked && !cbStorage.isChecked && !cbHistory.isChecked) {
                android.widget.Toast.makeText(
                    this@BrowserActivity,
                    R.string.browser_clear_scope_none,
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            val cleared = if (cbSiteOnly.isChecked) {
                clearCurrentSiteData(
                    cache = cbCache.isChecked,
                    cookies = cbCookies.isChecked,
                    storage = cbStorage.isChecked,
                    history = cbHistory.isChecked
                )
            } else {
                clearBrowserData(
                    cache = cbCache.isChecked,
                    cookies = cbCookies.isChecked,
                    storage = cbStorage.isChecked,
                    history = cbHistory.isChecked
                )
                true
            }
            if (cleared) dialog.dismiss()
        }
    }

    /**
     * 按所选范围清除所有标签页的浏览数据,清理后重新加载当前页让效果立即生效:
     * - cache:HTTP 磁盘缓存
     * - cookies:Cookie(含登录状态)+ HTTP 基本认证凭据
     * - storage:localStorage / IndexedDB / WebSQL
     * - history:浏览历史(前进/后退列表)+ 表单自动填充
     */
    @Suppress("DEPRECATION")
    private fun clearBrowserData(cache: Boolean, cookies: Boolean, storage: Boolean, history: Boolean) {
        if (cookies) {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().removeSessionCookies(null)
            CookieManager.getInstance().flush()
            WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword()
        }
        if (storage) {
            WebStorage.getInstance().deleteAllData()
        }
        tabs.forEach {
            if (cache) it.webView.clearCache(true)
            if (history) {
                it.webView.clearHistory()
                it.webView.clearFormData()
            }
        }
        // 访问记录列表(历史记录页的数据源)一并清掉,开着的历史页同步重渲染
        if (history) {
            clearHistoryRecords()
            reloadHistoryPage()
        }
        // 无缓存状态重新加载,清理立即可见
        forceRefreshCurrent()
        android.widget.Toast.makeText(
            this,
            R.string.browser_clear_data_done,
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * 按勾选范围仅清除当前标签所属网站的数据(尽力而为,系统没有全部按站点清除的公开 API):
     * - cookies:按当前地址逐条写入过期 Cookie。host-only 写 Path=/ 与默认路径两种形态,
     *   带 Domain 的补 .host / .去www.host 两种域(删 Domain=.example.com 这类常用 Cookie)
     * - storage:WebStorage.deleteOrigin,带/不带默认端口两种 origin 都试(Chromium 匹配)
     * - history:自管前进后退历史按源过滤(表单记录是全局数据,只能随「继续」全清)
     * - cache:无按站点清除的 API,跳过并提示(清缓存走「继续」全局路径)
     * 返回是否实际清了东西;清了则重载当前页让效果立即可见(被清登录态的站会跳登录页)。
     */
    private fun clearCurrentSiteData(
        cache: Boolean,
        cookies: Boolean,
        storage: Boolean,
        history: Boolean
    ): Boolean {
        val tab = tabs.getOrNull(currentIndex)
        val live = tab?.webView?.url
        val url = when {
            live != null && !isInternalOrBlankUrl(live) -> live
            tab != null && isWebUrl(tab.url) -> tab.url
            else -> {
                android.widget.Toast.makeText(
                    this, R.string.browser_clear_site_none, android.widget.Toast.LENGTH_SHORT
                ).show()
                return false
            }
        }
        val origin = urlOrigin(url)
        var cleared = false
        if (cookies) {
            val host = Uri.parse(url).host.orEmpty()
            val cookieManager = CookieManager.getInstance()
            cookieManager.getCookie(url)?.split(";")?.forEach { part ->
                val name = part.substringBefore('=').trim()
                if (name.isEmpty()) return@forEach
                cookieManager.setCookie(url, "$name=; Max-Age=0; Path=/")
                cookieManager.setCookie(url, "$name=; Max-Age=0")
                if (host.isNotEmpty()) {
                    cookieManager.setCookie(url, "$name=; Max-Age=0; Path=/; Domain=.$host")
                    val bare = host.removePrefix("www.")
                    if (bare != host) {
                        cookieManager.setCookie(url, "$name=; Max-Age=0; Path=/; Domain=.$bare")
                    }
                }
            }
            cookieManager.flush()
            cleared = true
        }
        if (storage && origin != null) {
            val siteStorage = WebStorage.getInstance()
            siteStorage.deleteOrigin(origin)
            // Chromium 对 origin 的匹配可能不带默认端口,两种形态都试一次
            val noDefaultPort = origin.removeSuffix(":443").removeSuffix(":80")
            if (noDefaultPort != origin) siteStorage.deleteOrigin(noDefaultPort)
            cleared = true
        }
        if (history && origin != null) {
            tabs.forEach { filterTabHistory(it, origin) }
            removeVisitsForOrigin(origin)
            reloadHistoryPage()
            persistTabs()
            updateToolbarState()
            cleared = true
        }
        if (cache) {
            android.widget.Toast.makeText(
                this, R.string.browser_clear_site_cache_skip, android.widget.Toast.LENGTH_SHORT
            ).show()
        }
        if (!cleared) return false
        forceRefreshCurrent()
        android.widget.Toast.makeText(
            this, R.string.browser_clear_site_done, android.widget.Toast.LENGTH_SHORT
        ).show()
        return true
    }

    /** 从标签自管历史里去掉指定源的条目;当前页所在条目保留,否则游标无处指向 */
    private fun filterTabHistory(tab: TabItem, origin: String) {
        if (tab.history.none { urlOrigin(it) == origin }) return
        val kept = mutableListOf<String>()
        var newIndex = tab.historyIndex
        tab.history.forEachIndexed { i, u ->
            if (i != tab.historyIndex && urlOrigin(u) == origin) {
                if (i < tab.historyIndex) newIndex--
                return@forEachIndexed
            }
            kept.add(u)
        }
        tab.history.clear()
        tab.history.addAll(kept)
        tab.historyIndex = newIndex.coerceIn(0, kept.lastIndex)
    }

    // ---------- 历史记录(以标签页方式打开的虚拟页) ----------

    /** 一条访问记录:网址、标题、时间 */
    private class HistoryEntry(val url: String, val title: String, val time: Long)

    private fun isHistoryTab(item: TabItem) = item.url == HISTORY_URL

    private fun loadHistoryPageInto(tab: TabItem) {
        tab.webView.loadDataWithBaseURL(null, buildHistoryHtml(), "text/html", "utf-8", HISTORY_URL)
    }

    /** 菜单「历史记录」:以标签页方式打开;已开着就聚焦并按最新数据重渲染(标签栏滚过去) */
    private fun openHistoryTab() {
        val existing = tabs.indexOfFirst { isHistoryTab(it) }
        if (existing >= 0) {
            loadHistoryPageInto(tabs[existing])
            showTab(existing, reveal = true)
            return
        }
        val tab = TabItem(getString(R.string.browser_history), HISTORY_URL, createWebView(HISTORY_URL))
        loadHistoryPageInto(tab)
        addTab(getString(R.string.browser_history), HISTORY_URL, tab)
        showTab(tabs.lastIndex, reveal = true)
    }

    private fun loadHistoryRecords(): MutableList<HistoryEntry> {
        val raw = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_HISTORY, null) ?: return mutableListOf()
        val list = mutableListOf<HistoryEntry>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("u")
                if (isWebUrl(url)) list.add(HistoryEntry(url, o.optString("t"), o.optLong("ts")))
            }
        }
        return list
    }

    private fun saveHistoryRecords(records: List<HistoryEntry>) {
        val arr = JSONArray()
        records.forEach { e ->
            arr.put(JSONObject().put("u", e.url).put("t", e.title).put("ts", e.time))
        }
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_HISTORY, arr.toString())
            .apply()
    }

    /** 记录一次访问:连续同址只刷新时间;超出上限丢最旧的;带上提交前暂存的标题 */
    private fun recordVisit(url: String) {
        if (!isWebUrl(url)) return
        val records = loadHistoryRecords()
        val pendingTitle = pendingVisitTitles.remove(url)
        val last = records.lastOrNull()
        if (last != null && last.url == url) {
            val betterTitle = pendingTitle?.takeIf { it.isNotBlank() } ?: last.title
            records[records.lastIndex] = HistoryEntry(url, betterTitle, System.currentTimeMillis())
        } else {
            records.add(HistoryEntry(url, pendingTitle.orEmpty(), System.currentTimeMillis()))
        }
        while (records.size > MAX_HISTORY_RECORDS) records.removeAt(0)
        saveHistoryRecords(records)
    }

    /** 标题晚到时回填最近一次同址访问的标题 */
    private fun applyVisitTitle(url: String?, title: String) {
        val u = url ?: return
        if (!isWebUrl(u)) return
        val records = loadHistoryRecords()
        for (i in records.indices.reversed()) {
            if (records[i].url == u) {
                if (records[i].title != title) {
                    records[i] = HistoryEntry(u, title, records[i].time)
                    saveHistoryRecords(records)
                }
                return
            }
        }
    }

    private fun clearHistoryRecords() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(PREF_HISTORY)
            .apply()
    }

    /** 按站点清除访问记录(「仅当前网站」勾了浏览历史时用) */
    private fun removeVisitsForOrigin(origin: String) {
        saveHistoryRecords(loadHistoryRecords().filter { urlOrigin(it.url) != origin })
    }

    private fun htmlEscape(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /** 时间一律带日期:今年 M/d HH:mm,往年 yyyy/M/d HH:mm */
    private fun formatHistoryTime(ts: Long): String {
        val then = Calendar.getInstance().apply { timeInMillis = ts }
        val now = Calendar.getInstance()
        val pattern = if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR)) {
            "M/d HH:mm"
        } else {
            "yyyy/M/d HH:mm"
        }
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ts))
    }

    private fun colorHex(res: Int): String =
        String.format(Locale.US, "#%06X", 0xFFFFFF and ContextCompat.getColor(this, res))

    // ---------- 虚拟列表页(历史记录/收藏)共用的顶部搜索条 ----------

    /** 搜索条样式(配色随调用方取当前主题) */
    private fun listPageSearchCss(surface: String, bg: String, fg: String, hint: String, div: String): String {
        return "#swrap{position:sticky;top:0;z-index:2;background:$surface;" +
            "padding:8px 12px;border-bottom:1px solid $div;}" +
            "#q{width:100%;box-sizing:border-box;padding:8px 12px;font-size:14px;color:$fg;" +
            "background:$bg;border:1px solid $div;border-radius:8px;outline:none;" +
            "-webkit-appearance:none;appearance:none;}" +
            "#q::placeholder{color:$hint;}"
    }

    /**
     * 搜索过滤 JS:按条目上的 data-search(预拼好的小写「标题 网址」)实时过滤,
     * 全部滤掉时显示 #noresult 占位;多选的「全选」只选可见条目(见各页 selectAll)。
     */
    private val listPageSearchJs: String = """
        function onSearch(v){
          var q=(v||'').toLowerCase().trim();
          var els=document.querySelectorAll('a[data-id]');
          var n=0;
          for(var i=0;i<els.length;i++){
            var hit=(q==='')||((els[i].getAttribute('data-search')||'').indexOf(q)>=0);
            els[i].style.display=hit?'':'none';
            if(hit){ n++; }
          }
          var nr=document.getElementById('noresult');
          if(nr){ nr.style.display=(n===0)?'':'none'; }
        }
    """.trimIndent()

    /** 搜索条 HTML(吸顶;[hintRes] 是占位文案) */
    private fun listPageSearchBar(hintRes: Int): String =
        "<div id='swrap'><input id='q' type='search' autocomplete='off' " +
            "placeholder='${getString(hintRes)}' " +
            "oninput='onSearch(this.value)' onsearch='onSearch(this.value)'></div>"

    /** 历史记录页 HTML:标题+网址居左、时间居右,点条目在本标签打开该网址;配色跟日夜主题。
     *  顶部搜索框按标题/网址实时过滤;条目可长按(500ms,JS 检测)弹原生菜单;
     *  多选模式由页面内工具栏完成(全选/删除/完成),全选只选当前搜索结果里的可见条目。 */
    private fun buildHistoryHtml(): String {
        val records = loadHistoryRecords()
        val rows = StringBuilder()
        // 倒序渲染(最新在上);data-id 用存储数组下标,删除按它回传,追加/回填不会挪动已有下标;
        // data-search 预先拼好小写的「标题 网址」,JS 过滤直接拿来 indexOf
        for (i in records.indices.reversed()) {
            val e = records[i]
            val searchable = (e.title.ifBlank { shortHost(e.url) } + " " + e.url)
                .lowercase(Locale.ROOT)
            rows.append(
                "<a href=\"${htmlEscape(e.url)}\" data-id=\"$i\" " +
                    "data-search=\"${htmlEscape(searchable)}\" " +
                    "ontouchstart=\"lpStart(event,'$i',this)\" ontouchmove=\"lpCancel()\" " +
                    "ontouchend=\"lpEnd(event)\" onclick=\"return onTap(this)\">" +
                    "<span class='col'>" +
                    "<span class='t'>${htmlEscape(e.title.ifBlank { shortHost(e.url) })}</span>" +
                    "<span class='u'>${htmlEscape(e.url)}</span></span>" +
                    "<span class='time'>${formatHistoryTime(e.time)}</span></a>"
            )
        }
        val rowsHtml = if (rows.isEmpty()) {
            "<div class='empty'>${getString(R.string.browser_history_empty)}</div>"
        } else {
            rows.toString()
        }
        // 有记录才需要「无匹配结果」占位:一条记录都没有时页面本身就有「暂无历史记录」
        val noResultHtml = if (records.isNotEmpty()) {
            "<div id='noresult' class='empty' style='display:none'>" +
                getString(R.string.browser_history_search_empty) + "</div>"
        } else {
            ""
        }
        val bg = colorHex(R.color.bg)
        val fg = colorHex(R.color.on_surface)
        val hint = colorHex(R.color.hint)
        val div = colorHex(R.color.divider)
        val surface = colorHex(R.color.surface)
        val highlight = colorHex(R.color.primary_light)
        val countLabel = getString(R.string.browser_history_selected_count)
        val script = """
            var selecting=false, selected={};
            function onTap(el){
              if(selecting){ toggleSel(el.getAttribute('data-id')); return false; }
              return true;
            }
            function toggleSel(id){
              if(selected[id]){ delete selected[id]; } else { selected[id]=1; }
              paintRows(); updateBar();
            }
            function paintRows(){
              var els=document.querySelectorAll('a[data-id]');
              for(var i=0;i<els.length;i++){
                els[i].className = selected[els[i].getAttribute('data-id')] ? 'sel' : '';
              }
            }
            function updateBar(){
              var n=0; for(var k in selected){ n++; }
              document.getElementById('count').textContent='$countLabel '+n;
              document.getElementById('delbtn').style.display = n>0 ? '' : 'none';
            }
            function enterSelect(id){
              selecting=true; selected={};
              document.body.className='selecting';
              if(id!==null && id!==''){ selected[String(id)]=1; }
              paintRows();
              document.getElementById('bar').style.display='';
              updateBar();
            }
            function exitSelect(){
              selecting=false; selected={};
              document.body.className='';
              paintRows();
              document.getElementById('bar').style.display='none';
            }
            function selectAll(){
              var els=document.querySelectorAll('a[data-id]');
              for(var i=0;i<els.length;i++){
                if(els[i].style.display==='none'){ continue; }
                selected[els[i].getAttribute('data-id')]=1;
              }
              paintRows(); updateBar();
            }
            function delSelected(){
              var ids=[]; for(var k in selected){ ids.push(k); }
              if(ids.length===0){ return; }
              if(window.LdmHistory){ LdmHistory.deleteIds(JSON.stringify(ids)); }
            }
            var lpTimer=null, lpFired=false, lpEl=null;
            function lpStart(ev, id, el){
              lpFired=false; lpEl=el;
              lpTimer=setTimeout(function(){
                lpFired=true;
                if(window.LdmHistory && lpEl){
                  var r=lpEl.getBoundingClientRect();
                  LdmHistory.onLongPress(parseInt(id,10), r.top, r.height);
                }
              }, 500);
            }
            function lpCancel(){ if(lpTimer){ clearTimeout(lpTimer); lpTimer=null; } }
            function lpEnd(ev){ lpCancel(); if(lpFired){ ev.preventDefault(); lpFired=false; } }
        """.trimIndent()
        return "<!DOCTYPE html><html><head><meta charset='utf-8'>" +
            "<meta name='viewport' content='width=device-width, initial-scale=1'>" +
            "<title>${getString(R.string.browser_history)}</title><style>" +
            "body{margin:0;background:$bg;color:$fg;}" +
            "body.selecting{padding-bottom:62px;}" +
            "a{display:flex;align-items:center;gap:10px;padding:10px 14px;" +
            "text-decoration:none;color:$fg;border-bottom:1px solid $div;" +
            "-webkit-user-select:none;user-select:none;}" +
            "a:active{background:$div;}" +
            "a.sel{background:$highlight;}" +
            ".col{flex:1;min-width:0;display:flex;flex-direction:column;}" +
            ".t{font-size:15px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}" +
            ".u{font-size:12px;color:$hint;white-space:nowrap;overflow:hidden;" +
            "text-overflow:ellipsis;margin-top:2px;}" +
            ".time{flex-shrink:0;font-size:12px;color:$hint;}" +
            ".empty{padding:48px 0;text-align:center;color:$hint;font-size:14px;}" +
            "#bar{position:fixed;left:0;right:0;bottom:0;display:flex;align-items:center;" +
            "gap:14px;padding:10px 14px;background:$surface;border-top:1px solid $div;font-size:14px;}" +
            ".bb{padding:6px 12px;border:1px solid $div;border-radius:8px;}" +
            listPageSearchCss(surface, bg, fg, hint, div) +
            "</style><script>$script$listPageSearchJs</script></head><body>" +
            listPageSearchBar(R.string.browser_history_search_hint) +
            "$rowsHtml$noResultHtml" +
            "<div id='bar' style='display:none'>" +
            "<span id='count'></span>" +
            "<span class='bb' onclick='selectAll()'>${getString(R.string.browser_history_select_all)}</span>" +
            "<span class='bb' id='delbtn' onclick='delSelected()'>${getString(R.string.browser_history_delete)}</span>" +
            "<span class='bb' onclick='exitSelect()'>${getString(R.string.done)}</span>" +
            "</div></body></html>"
    }

    /** 历史页 JS 桥:长按弹原生菜单(带条目纵向位置)、多选删除回传 */
    private inner class HistoryBridge {
        @JavascriptInterface
        fun onLongPress(id: Int, cssTop: Double, cssHeight: Double) {
            runOnUiThread { showHistoryItemMenu(id, cssTop.toFloat(), cssHeight.toFloat()) }
        }

        @JavascriptInterface
        fun deleteIds(idsJson: String) {
            val ids = mutableSetOf<Int>()
            runCatching {
                val arr = JSONArray(idsJson)
                for (i in 0 until arr.length()) {
                    arr.optInt(i, -1).takeIf { it >= 0 }?.let { ids.add(it) }
                }
            }
            if (ids.isEmpty()) return
            runOnUiThread {
                saveHistoryRecords(loadHistoryRecords().filterIndexed { idx, _ -> idx !in ids })
                reloadHistoryPage()
                android.widget.Toast.makeText(
                    this@BrowserActivity, R.string.browser_history_deleted,
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * 历史条目长按菜单:删除 / 复制链接 / 在新标签页打开 / 多选模式(点条目即执行并关闭)。
     * 菜单位置与收藏页同款:水平固定屏幕右边,垂直落在长按条目下方(贴底自动翻上方)。
     */
    private fun showHistoryItemMenu(id: Int, cssTop: Float, cssHeight: Float) {
        val entry = loadHistoryRecords().getOrNull(id) ?: return
        val title = entry.title.ifBlank { shortHost(entry.url) }
        val historyView = tabs.firstOrNull { isHistoryTab(it) }?.webView ?: return
        val actions = listOf(
            BrowserMenuAction(getString(R.string.browser_history_delete), action = {
                saveHistoryRecords(loadHistoryRecords().filterIndexed { idx, _ -> idx != id })
                reloadHistoryPage()
                android.widget.Toast.makeText(
                    this, R.string.browser_history_deleted, android.widget.Toast.LENGTH_SHORT
                ).show()
            }),
            BrowserMenuAction(getString(R.string.browser_history_copy_link), action = {
                (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                    ?.setPrimaryClip(ClipData.newPlainText(null, entry.url))
                android.widget.Toast.makeText(
                    this, R.string.browser_history_copied, android.widget.Toast.LENGTH_SHORT
                ).show()
            }),
            BrowserMenuAction(getString(R.string.browser_history_open_new_tab), action = {
                addTab(title, entry.url)
                showTab(tabs.lastIndex, reveal = true)
            }),
            BrowserMenuAction(getString(R.string.browser_history_multi_select), action = {
                enterHistorySelectMode(id)
            })
        )
        showRowMenuInWebView(historyView, cssTop, cssHeight, actions)
    }

    /** 重新渲染当前开着的历史页(记录变化后调用;选择状态随之复位) */
    private fun reloadHistoryPage() {
        tabs.firstOrNull { isHistoryTab(it) }?.let { loadHistoryPageInto(it) }
    }

    /** 进入历史页多选模式,并预选长按的那一条 */
    private fun enterHistorySelectMode(id: Int) {
        tabs.firstOrNull { isHistoryTab(it) }?.webView
            ?.evaluateJavascript("enterSelect('$id')", null)
    }

    // ---------- 收藏(地址栏星星 + 列表页) ----------

    /** 一条收藏记录:网址、标题 */
    private class BookmarkEntry(val url: String, val title: String)

    private fun isBookmarksTab(item: TabItem) = item.url == BOOKMARKS_URL

    private fun loadBookmarksPageInto(tab: TabItem) {
        tab.webView.loadDataWithBaseURL(null, buildBookmarksHtml(), "text/html", "utf-8", BOOKMARKS_URL)
    }

    /** 菜单「收藏」:打开收藏列表页;已开着就聚焦并按最新数据重渲染(标签栏滚过去) */
    private fun openBookmarksTab() {
        val existing = tabs.indexOfFirst { isBookmarksTab(it) }
        if (existing >= 0) {
            loadBookmarksPageInto(tabs[existing])
            showTab(existing, reveal = true)
            return
        }
        val tab = TabItem(getString(R.string.browser_bookmark), BOOKMARKS_URL, createWebView(BOOKMARKS_URL))
        loadBookmarksPageInto(tab)
        addTab(getString(R.string.browser_bookmark), BOOKMARKS_URL, tab)
        showTab(tabs.lastIndex, reveal = true)
    }

    private fun loadBookmarks(): MutableList<BookmarkEntry> {
        val raw = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_BOOKMARKS, null) ?: return mutableListOf()
        val list = mutableListOf<BookmarkEntry>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("u")
                if (isWebUrl(url)) list.add(BookmarkEntry(url, o.optString("t")))
            }
        }
        return list
    }

    private fun saveBookmarks(records: List<BookmarkEntry>) {
        val arr = JSONArray()
        records.forEach { e ->
            arr.put(JSONObject().put("u", e.url).put("t", e.title))
        }
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_BOOKMARKS, arr.toString())
            .apply()
    }

    /** 当前页(优先 WebView 实际地址)是否已收藏 */
    private fun currentBookmarkableUrl(tab: TabItem?): String? {
        return tab?.webView?.url?.takeIf { !isInternalOrBlankUrl(it) && isWebUrl(it) }
            ?: tab?.url?.takeIf { !isInternalOrBlankUrl(it) && isWebUrl(it) }
    }

    /** 星星图标:当前页已收藏=黄色实心,未收藏=空心 */
    private fun updateBookmarkStar() {
        if (!::btnBookmark.isInitialized) return
        val url = currentBookmarkableUrl(tabs.getOrNull(currentIndex))
        if (url != null && loadBookmarks().any { it.url == url }) {
            btnBookmark.setImageResource(R.drawable.ic_star_filled)
        } else {
            btnBookmark.setImageResource(R.drawable.ic_star_outline)
        }
    }

    /** 点收藏星:收藏/取消收藏当前页(收藏列表页开着的话同步重渲染) */
    private fun toggleBookmark() {
        val tab = tabs.getOrNull(currentIndex)
        val url = currentBookmarkableUrl(tab)
        if (url == null) {
            toast(R.string.browser_bookmark_none)
            return
        }
        val records = loadBookmarks()
        val existing = records.indexOfFirst { it.url == url }
        if (existing >= 0) {
            records.removeAt(existing)
            toast(R.string.browser_bookmark_removed)
        } else {
            // 新收藏插最前(列表页最新在上),标题用当前标签显示标题
            records.add(0, BookmarkEntry(url, tab?.let { displayTitle(it) }.orEmpty()))
            toast(R.string.browser_bookmark_added)
        }
        saveBookmarks(records)
        updateBookmarkStar()
        reloadBookmarksPage()
    }

    /** 重新渲染开着的收藏页(收藏变化后调用) */
    private fun reloadBookmarksPage() {
        tabs.firstOrNull { isBookmarksTab(it) }?.let { loadBookmarksPageInto(it) }
    }

    /** 收藏列表页 HTML:与历史页同款布局(标题+网址居左),顶部搜索框按标题/网址实时过滤;
     *  点条目本标签打开,长按弹原生菜单 */
    private fun buildBookmarksHtml(): String {
        val records = loadBookmarks()
        val rows = StringBuilder()
        for ((i, e) in records.withIndex()) {
            val searchable = (e.title.ifBlank { shortHost(e.url) } + " " + e.url)
                .lowercase(Locale.ROOT)
            rows.append(
                "<a href=\"${htmlEscape(e.url)}\" data-id=\"$i\" " +
                    "data-search=\"${htmlEscape(searchable)}\" " +
                    "ontouchstart=\"lpStart(event,'$i',this)\" ontouchmove=\"lpCancel()\" " +
                    "ontouchend=\"lpEnd(event)\">" +
                    "<span class='col'>" +
                    "<span class='t'>${htmlEscape(e.title.ifBlank { shortHost(e.url) })}</span>" +
                    "<span class='u'>${htmlEscape(e.url)}</span></span></a>"
            )
        }
        val rowsHtml = if (rows.isEmpty()) {
            "<div class='empty'>${getString(R.string.browser_bookmark_empty)}</div>"
        } else {
            rows.toString()
        }
        // 有记录才需要「无匹配结果」占位:一条收藏都没有时页面本身就有「暂无收藏」
        val noResultHtml = if (records.isNotEmpty()) {
            "<div id='noresult' class='empty' style='display:none'>" +
                getString(R.string.browser_history_search_empty) + "</div>"
        } else {
            ""
        }
        val bg = colorHex(R.color.bg)
        val fg = colorHex(R.color.on_surface)
        val hint = colorHex(R.color.hint)
        val div = colorHex(R.color.divider)
        val surface = colorHex(R.color.surface)
        val script = """
            var lpTimer=null, lpFired=false, lpEl=null;
            function lpStart(ev, id, el){
              lpFired=false; lpEl=el;
              lpTimer=setTimeout(function(){
                lpFired=true;
                if(window.LdmBookmarks && lpEl){
                  var r=lpEl.getBoundingClientRect();
                  LdmBookmarks.onLongPress(parseInt(id,10), r.top, r.height);
                }
              }, 500);
            }
            function lpCancel(){ if(lpTimer){ clearTimeout(lpTimer); lpTimer=null; } }
            function lpEnd(ev){ lpCancel(); if(lpFired){ ev.preventDefault(); lpFired=false; } }
        """.trimIndent()
        return "<!DOCTYPE html><html><head><meta charset='utf-8'>" +
            "<meta name='viewport' content='width=device-width, initial-scale=1'>" +
            "<title>${getString(R.string.browser_bookmark)}</title><style>" +
            "body{margin:0;background:$bg;color:$fg;}" +
            "a{display:flex;align-items:center;padding:10px 14px;" +
            "text-decoration:none;color:$fg;border-bottom:1px solid $div;" +
            "-webkit-user-select:none;user-select:none;}" +
            "a:active{background:$div;}" +
            ".col{flex:1;min-width:0;display:flex;flex-direction:column;}" +
            ".t{font-size:15px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}" +
            ".u{font-size:12px;color:$hint;white-space:nowrap;overflow:hidden;" +
            "text-overflow:ellipsis;margin-top:2px;}" +
            ".empty{padding:48px 0;text-align:center;color:$hint;font-size:14px;}" +
            listPageSearchCss(surface, bg, fg, hint, div) +
            "</style><script>$script$listPageSearchJs</script></head><body>" +
            listPageSearchBar(R.string.browser_bookmark_search_hint) +
            "$rowsHtml$noResultHtml</body></html>"
    }

    /** 收藏页 JS 桥:长按弹原生菜单(带条目纵向位置) */
    private inner class BookmarksBridge {
        @JavascriptInterface
        fun onLongPress(id: Int, cssTop: Double, cssHeight: Double) {
            runOnUiThread { showBookmarkItemMenu(id, cssTop.toFloat(), cssHeight.toFloat()) }
        }
    }

    /** 收藏条目长按菜单:删除 / 复制链接 / 在新标签页打开(点条目即执行并关闭) */
    private fun showBookmarkItemMenu(id: Int, cssTop: Float, cssHeight: Float) {
        val entry = loadBookmarks().getOrNull(id) ?: return
        val title = entry.title.ifBlank { shortHost(entry.url) }
        val bookmarksView = tabs.firstOrNull { isBookmarksTab(it) }?.webView ?: return
        val actions = listOf(
            BrowserMenuAction(getString(R.string.browser_history_delete), action = {
                saveBookmarks(loadBookmarks().filterIndexed { idx, _ -> idx != id })
                reloadBookmarksPage()
                android.widget.Toast.makeText(
                    this, R.string.browser_bookmark_deleted, android.widget.Toast.LENGTH_SHORT
                ).show()
            }),
            BrowserMenuAction(getString(R.string.browser_history_copy_link), action = {
                (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                    ?.setPrimaryClip(ClipData.newPlainText(null, entry.url))
                android.widget.Toast.makeText(
                    this, R.string.browser_history_copied, android.widget.Toast.LENGTH_SHORT
                ).show()
            }),
            BrowserMenuAction(getString(R.string.browser_history_open_new_tab), action = {
                addTab(title, entry.url)
                showTab(tabs.lastIndex, reveal = true)
            })
        )
        showRowMenuInWebView(bookmarksView, cssTop, cssHeight, actions)
    }

    /**
     * 在 WebView 内某一行(行顶/行高,CSS px)下方弹 ListPopupWindow 菜单:
     * 水平固定从屏幕右边展开,垂直落在行的下方(贴底时整块翻到行上方)。
     * 历史记录页与收藏页的长按菜单共用。
     */
    private fun showRowMenuInWebView(
        webView: WebView,
        cssTop: Float,
        cssHeight: Float,
        actions: List<BrowserMenuAction>
    ) {
        val root = findViewById<ViewGroup>(android.R.id.content)
        val density = resources.displayMetrics.density
        val rootLoc = IntArray(2)
        root.getLocationOnScreen(rootLoc)
        val viewLoc = IntArray(2)
        webView.getLocationOnScreen(viewLoc)
        val rowBottom = viewLoc[1] - rootLoc[1] + ((cssTop + cssHeight) * density).toInt()
        val rowTop = viewLoc[1] - rootLoc[1] + (cssTop * density).toInt()
        // 估算菜单总高度,判断下方放不放得下
        val menuWidth = measureMenuWidth(actions)
        val sample = layoutInflater.inflate(R.layout.item_browser_menu, root, false)
        sample.measure(
            View.MeasureSpec.makeMeasureSpec(menuWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val menuHeight = sample.measuredHeight * actions.size
        // 水平固定右边:菜单左缘 = 屏宽 - 菜单宽 - 8dp;
        // 垂直:下方放得下就落条目底缘往下弹,贴底放不下则锚到条目上缘,整块翻到条目上方
        val spaceBelow = root.height - rowBottom
        val anchorY = if (menuHeight <= spaceBelow) rowBottom else rowTop
        val anchor = View(this)
        anchor.layoutParams = FrameLayout.LayoutParams(1, 1).apply {
            leftMargin = (
                resources.displayMetrics.widthPixels - menuWidth - 8 * density
                ).toInt().coerceAtLeast(0)
            topMargin = anchorY
        }
        root.addView(anchor)
        // 等锚点完成一次布局再弹:否则 getLocationOnScreen 还是 (0,0),菜单位置跑偏
        anchor.post {
            if (anchor.isAttachedToWindow) {
                showListPopup(anchor, actions) { root.removeView(anchor) }
            }
        }
    }

    // ---------- 内置下载器 ----------

    /**
     * 下载(LdmDownloader.kt 为自研引擎,这里只做 UI 编排):
     * - http(s) 按设置的下载方式分发:内置 = 自研引擎(断点续传/暂停/并发/通知),
     *   系统 = DownloadManager;确认弹窗带文件名/大小/目标目录
     * - blob:/data: 没有可直接下载的地址:页面 JS fetch 内容、按 1MB 分片转 base64 过
     *   JS 桥(BlobBridge),原生拼成临时文件再落盘(与引擎共用 commitDownloadFile,
     *   同样存到设置的下载目录)
     * - 下载管理页(about:downloads,虚拟地址同历史/收藏页)合并展示引擎任务、系统下载与
     *   blob/data 落盘记录:进行中点条目=暂停、已暂停/失败点=继续、已完成唤起「打开方式」;
     *   长按暂停/继续/打开/复制来源链接/复制文件路径/删除(删除需确认,可勾选连本地文件
     *   一起删);引擎进度与系统下载 ContentObserver 都节流后增量刷新当前页
     */
    private class BlobTask(
        val name: String,
        var mime: String,
        val file: File,
        val stream: FileOutputStream
    )

    /** 一条下载条目(引擎任务/系统下载/blob·data 落盘记录),渲染成下载管理页的行 */
    private class DownloadItem(
        val id: String,        // "ldm-<引擎任务 id>" / "dm-<系统下载 id>" 或 "nat-<落盘记录 id>"
        val name: String,
        val kind: String,      // running|waiting|paused|failed|done|saved
        val status: String,    // 状态文案(含进度/大小)
        val time: Long,
        val sourceUrl: String?,// 来源链接(长按可复制)
        val path: String? = null,   // 已完成文件的路径(长按可复制)
        val pct: Int = -1      // 下载进度 0-100;-1=未知/无进度条
    )

    /** blob/data 落盘记录(MediaStore 内容地址或应用专属目录路径) */
    private class SavedFile(
        val id: Long,
        val uri: String,   // MediaStore 内容地址(29+);空串表示走 [path]
        val path: String?, // 应用专属目录文件路径(≤28),打开走 FileProvider
        val name: String,
        val mime: String,
        val size: Long,
        val time: Long
    )

    private fun isDownloadsTab(item: TabItem) = item.url == DOWNLOADS_URL

    private fun loadDownloadsPageInto(tab: TabItem) {
        tab.webView.loadDataWithBaseURL(
            null, buildDownloadsHtml(), "text/html", "utf-8", DOWNLOADS_URL
        )
    }

    /** 菜单「下载管理」:以标签页方式打开;已开着就聚焦并按最新数据重渲染 */
    private fun openDownloadsTab() {
        val existing = tabs.indexOfFirst { isDownloadsTab(it) }
        if (existing >= 0) {
            loadDownloadsPageInto(tabs[existing])
            showTab(existing, reveal = true)
            return
        }
        val tab = TabItem(
            getString(R.string.browser_downloads), DOWNLOADS_URL, createWebView(DOWNLOADS_URL)
        )
        loadDownloadsPageInto(tab)
        addTab(getString(R.string.browser_downloads), DOWNLOADS_URL, tab)
        showTab(tabs.lastIndex, reveal = true)
    }

    /** 重新渲染当前开着的下载管理页(记录变化后调用;系统下载进度走增量推送不走这里) */
    private fun reloadDownloadsPage() {
        tabs.firstOrNull { isDownloadsTab(it) }?.let { loadDownloadsPageInto(it) }
    }

    /** 大小的人类可读格式:0 以下返回空串(大小未知),1KB 起带一位小数 */
    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return ""
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        val pattern = if (unit == 0) "%.0f%s" else "%.1f%s"
        return String.format(Locale.US, pattern, value, units[unit])
    }

    /**
     * DownloadListener 分发:blob:/data: 原生落盘,http(s) 确认后交系统下载器,
     * 其余(file:// 等,极罕见)保留跳外部应用的老行为。
     */
    private fun handleDownloadRequest(
        wv: WebView,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long
    ) {
        when {
            url.startsWith("blob:", ignoreCase = true) -> startBlobDownload(wv, url, mimeType)
            url.startsWith("data:", ignoreCase = true) -> saveDataUriDownload(url, mimeType)
            url.startsWith("http://", true) || url.startsWith("https://", true) ->
                confirmHttpDownload(wv, url, userAgent, contentDisposition, mimeType, contentLength)
            else -> openDownloadUrlExternally(url)
        }
    }

    /** 下载器不可用时的老行为兜底:交给系统里的其他应用打开 */
    private fun openDownloadUrlExternally(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            toast(R.string.browser_no_browser)
        }
    }

    /**
     * http(s) 下载:确认文件名/大小/保存目录后按设置的下载方式入队 ——
     * 内置下载器(自研引擎,断点续传/暂停/并发排队)或系统下载器
     * (固定存公共「下载」目录,自定义下载目录对它不生效)。
     */
    private fun confirmHttpDownload(
        wv: WebView,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long
    ) {
        val name = guessDownloadName(url, contentDisposition, mimeType)
        val sizeText = formatBytes(contentLength)
        val builtin = downloadMethod == DL_METHOD_BUILTIN
        val dirName = if (builtin) {
            downloadDirLabel(this)
        } else {
            getString(R.string.browser_dl_dir_default_name)
        }
        val message = if (sizeText.isEmpty()) {
            getString(R.string.browser_dl_confirm_dir, name, dirName)
        } else {
            getString(R.string.browser_dl_confirm_size_dir, name, sizeText, dirName)
        }
        try {
            AlertDialog.Builder(this)
                .setTitle(R.string.browser_download_title)
                .setMessage(message)
                .setPositiveButton(R.string.browser_dl_start) { _, _ ->
                    if (builtin) {
                        ensureNotificationPermission()
                        downloader.enqueue(url, userAgent, wv.url, name, mimeType)
                    } else {
                        enqueueHttpDownload(wv, url, userAgent, name, mimeType)
                    }
                    toast(R.string.browser_dl_started)
                }
                .setNeutralButton(R.string.browser_dl_copy_url) { _, _ ->
                    copyToClipboard(url)
                    toast(R.string.browser_history_copied)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } catch (_: Exception) {
        }
    }

    /** 通知权限(13+):首次用内置下载器时请求;不授予也不影响下载,页面里照样有进度 */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) return
        runCatching {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIFICATIONS)
        }
    }

    /**
     * 系统下载器(设置的「下载方式=系统下载器」时用):Cookie/UA/Referer 一并带上
     * (登录后的下载、防盗链都依赖),固定存公共「下载」目录;入队失败
     * (个别 ROM 裁掉下载器)退回外部应用打开。
     */
    private fun enqueueHttpDownload(
        wv: WebView,
        url: String,
        userAgent: String?,
        name: String,
        mimeType: String?
    ) {
        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
                val cookies = CookieManager.getInstance().getCookie(url)
                if (!cookies.isNullOrBlank()) addRequestHeader("Cookie", cookies)
                if (!userAgent.isNullOrBlank()) addRequestHeader("User-Agent", userAgent)
                val referer = wv.url
                if (!referer.isNullOrBlank()) addRequestHeader("Referer", referer)
                setTitle(name)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            }
            (getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager)?.enqueue(request)
            toast(R.string.browser_dl_started)
        } catch (_: Exception) {
            openDownloadUrlExternally(url)
        }
    }

    /** 下载文件名:优先 Content-Disposition(含 RFC5987 filename*),否则系统猜测,统一去非法字符 */
    private fun guessDownloadName(url: String, contentDisposition: String?, mimeType: String?): String {
        if (!contentDisposition.isNullOrBlank()) {
            Regex("filename\\*=(?:UTF-8|utf-8)''([^;]+)").find(contentDisposition)
                ?.groupValues?.get(1)?.let { encoded ->
                    val decoded = runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull() ?: encoded
                    return sanitizeFileName(decoded.trim().trim('"'))
                }
            Regex("filename=\"?([^\";]+)").find(contentDisposition)?.groupValues?.get(1)?.let {
                return sanitizeFileName(it.trim())
            }
        }
        val guessed = runCatching {
            URLUtil.guessFileName(url, contentDisposition, mimeType)
        }.getOrNull().orEmpty()
        return sanitizeFileName(guessed)
    }

    /** 文件名统一清洗:去掉路径分隔符等非法字符(公共下载目录不允许子目录) */
    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "download" }

    /**
     * blob: 下载:blob 是页面内存对象,监听器拿不到内容。先读页面钩子
     * (见 injectBlobDownloadHook)记录的 <a download> 文件名并清空,确认后让页面
     * fetch 内容分片回传(见 BlobBridge)。文件名拿不到时按 mime 推扩展名兜底。
     */
    private fun startBlobDownload(wv: WebView, blobUrl: String, mimeType: String?) {
        // 读取后立即清空,避免下一个 blob 下载误用旧文件名
        wv.evaluateJavascript("var n=window.__ldmBlobName||'';window.__ldmBlobName='';n") { value ->
            val hooked = runCatching {
                value?.trim()
                    ?.removeSurrounding("\"")
                    ?.replace("\\\"", "\"")
                    ?.replace("\\\\", "\\")
            }.getOrNull().orEmpty()
            val ext = runCatching {
                MimeTypeMap.getSingleton()
                    .getExtensionFromMimeType(mimeType?.takeIf { it.isNotBlank() })
            }.getOrNull() ?: "bin"
            val fallback = "download_" +
                SimpleDateFormat("HHmmss", Locale.getDefault()).format(Date()) + "." + ext
            val name = sanitizeFileName(hooked.ifBlank { fallback })
            try {
                AlertDialog.Builder(this)
                    .setTitle(R.string.browser_download_title)
                    .setMessage(getString(R.string.browser_dl_confirm, name))
                    .setPositiveButton(R.string.browser_dl_start) { _, _ ->
                        fetchBlobIntoNative(wv, blobUrl, name, mimeType)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            } catch (_: Exception) {
            }
        }
    }

    /** 让页面 fetch blob 内容按 1MB 分片转 base64 回传;先建好接收任务再注入脚本 */
    private fun fetchBlobIntoNative(wv: WebView, blobUrl: String, name: String, mimeType: String?) {
        val key = System.currentTimeMillis().toString() + "_" + (0..9999).random()
        val temp = File(cacheDir, "ldm_blob_$key.bin")
        val stream = runCatching { FileOutputStream(temp) }.getOrNull() ?: run {
            toast(R.string.browser_dl_failed)
            return
        }
        blobTasks[key] = BlobTask(name, mimeType.orEmpty(), temp, stream)
        val js = "(function(){var K='$key',U=" + JSONObject.quote(blobUrl) + ";" +
            "var B=window.LdmBlob;if(!B){return;}" +
            "fetch(U).then(function(r){return r.blob();}).then(function(b){" +
            "try{B.onMeta(K,b.type||'');}catch(e){}" +
            "var STEP=$DOWNLOAD_BLOB_CHUNK_BYTES,off=0;" +
            "function next(){" +
            "if(off>=b.size){try{B.onEnd(K,b.size);}catch(e){}return;}" +
            "var fr=new FileReader();" +
            "fr.onload=function(){var s=String(fr.result),i=s.indexOf(',');" +
            "try{B.onChunk(K,i>=0?s.slice(i+1):s);}catch(e){return;}" +
            "off+=STEP;setTimeout(next,0);};" +
            "fr.onerror=function(){try{B.onError(K);}catch(e){}};" +
            "fr.readAsDataURL(b.slice(off,off+STEP));" +
            "}" +
            "next();" +
            "}).catch(function(){try{B.onError(K);}catch(e){}});" +
            "})()"
        wv.evaluateJavascript(js, null)
    }

    /**
     * blob 下载 JS 桥:onMeta 补报类型(监听器没给 mime 时用页面 blob 的类型),
     * onChunk 收 base64 分片追加进临时文件,onEnd/onError 收尾。
     * 回调都在 WebView 的 JavaBridge 线程,临时文件操作在此进行,UI 反馈回主线程。
     */
    private inner class BlobBridge {
        @JavascriptInterface
        fun onMeta(key: String, mime: String) {
            blobTasks[key]?.let { if (it.mime.isBlank() && mime.isNotBlank()) it.mime = mime }
        }

        @JavascriptInterface
        fun onChunk(key: String, base64: String) {
            val task = blobTasks[key] ?: return
            try {
                task.stream.write(Base64.decode(base64, Base64.NO_WRAP))
            } catch (_: Exception) {
                blobTasks.remove(key)?.let { failed ->
                    runCatching { failed.stream.close() }
                    runCatching { failed.file.delete() }
                    runOnUiThread { toast(R.string.browser_dl_failed) }
                }
            }
        }

        @JavascriptInterface
        fun onEnd(key: String, @Suppress("UNUSED_PARAMETER") size: Double) {
            val task = blobTasks.remove(key) ?: return
            runCatching { task.stream.close() }
            if (task.file.length() == 0L) {
                runCatching { task.file.delete() }
                runOnUiThread { toast(R.string.browser_dl_failed) }
                return
            }
            saveDownloadFromTemp(task)
        }

        @JavascriptInterface
        fun onError(key: String) {
            blobTasks.remove(key)?.let { task ->
                runCatching { task.stream.close() }
                runCatching { task.file.delete() }
                runOnUiThread { toast(R.string.browser_dl_failed) }
            }
        }
    }

    /** blob 内容收完:临时文件落为用户可见的下载文件并记录,回主线程提示 */
    private fun saveDownloadFromTemp(task: BlobTask) {
        val mime = task.mime.ifBlank { "application/octet-stream" }
        val result = runCatching {
            val saved = persistDownloadFile(task.file, task.name, mime)
            addSavedFile(saved)
            saved
        }
        runCatching { task.file.delete() }
        runOnUiThread {
            if (result.isSuccess) toast(R.string.browser_dl_saved) else toast(R.string.browser_dl_failed)
        }
    }

    /**
     * data: 下载(前端导出的 CSV/图片等):头部分出类型,负载按 base64/URL 编码解码,
     * 确认后在后台线程走与 blob 相同的落盘路径。
     */
    private fun saveDataUriDownload(dataUrl: String, mimeType: String?) {
        val header = dataUrl.substringBefore(',').removePrefix("data:")
        val payload = dataUrl.substringAfter(',', "")
        if (payload.isEmpty()) {
            toast(R.string.browser_dl_failed)
            return
        }
        val bytes = runCatching {
            if (header.contains(";base64", ignoreCase = true)) {
                Base64.decode(payload, Base64.DEFAULT)
            } else {
                // data: 的非 base64 负载按 percent-encoding 解,+ 是字面量而非空格
                URLDecoder.decode(payload.replace("+", "%2B"), "UTF-8")
                    .toByteArray(Charsets.UTF_8)
            }
        }.getOrNull() ?: run {
            toast(R.string.browser_dl_failed)
            return
        }
        val mime = header.substringBefore(';').trim().ifBlank { mimeType.orEmpty() }
            .ifBlank { "application/octet-stream" }
        val name = guessDownloadName(dataUrl, null, mime)
        val sizeText = formatBytes(bytes.size.toLong())
        val message = if (sizeText.isEmpty()) {
            getString(R.string.browser_dl_confirm, name)
        } else {
            getString(R.string.browser_dl_confirm_size, name, sizeText)
        }
        try {
            AlertDialog.Builder(this)
                .setTitle(R.string.browser_download_title)
                .setMessage(message)
                .setPositiveButton(R.string.browser_dl_start) { _, _ ->
                    Thread {
                        val temp = File(cacheDir, "ldm_data_${System.currentTimeMillis()}")
                        val result = runCatching {
                            temp.writeBytes(bytes)
                            val saved = persistDownloadFile(temp, name, mime)
                            addSavedFile(saved)
                            saved
                        }
                        runCatching { temp.delete() }
                        runOnUiThread {
                            if (result.isSuccess) {
                                toast(R.string.browser_dl_saved)
                            } else {
                                toast(R.string.browser_dl_failed)
                            }
                        }
                    }.start()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } catch (_: Exception) {
        }
    }

    /**
     * 把已生成的临时文件落为用户可见的下载文件,走与自研引擎相同的提交逻辑
     * (见 LdmDownloader.kt 的 commitDownloadFile):设置了自定义下载目录写该目录
     * (SAF);否则 Android 10+ 写 MediaStore「下载」集合,更早版本写应用专属
     * 下载目录(打开走 FileProvider)。失败抛异常,调用方负责提示。
     */
    private fun persistDownloadFile(src: File, name: String, mime: String): SavedFile {
        val now = System.currentTimeMillis()
        val treeUri = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(LdmDownloader.PREF_DL_DIR, null)?.takeIf { it.isNotBlank() }
        val result = commitDownloadFile(this, src, name, mime, treeUri)
        return SavedFile(now, result.uri, result.path.takeIf { result.uri.isEmpty() }, result.name, mime, result.size, now)
    }

    private fun loadSavedFiles(): MutableList<SavedFile> {
        val raw = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_DOWNLOADS, null) ?: return mutableListOf()
        val list = mutableListOf<SavedFile>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(
                    SavedFile(
                        id = o.optLong("id"),
                        uri = o.optString("uri"),
                        path = o.optString("path").takeIf { it.isNotEmpty() },
                        name = o.optString("n"),
                        mime = o.optString("m"),
                        size = o.optLong("s"),
                        time = o.optLong("ts")
                    )
                )
            }
        }
        return list
    }

    private fun saveSavedFiles(records: List<SavedFile>) {
        val arr = JSONArray()
        records.forEach { f ->
            arr.put(
                JSONObject()
                    .put("id", f.id)
                    .put("uri", f.uri)
                    .put("path", f.path.orEmpty())
                    .put("n", f.name)
                    .put("m", f.mime)
                    .put("s", f.size)
                    .put("ts", f.time)
            )
        }
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_DOWNLOADS, arr.toString())
            .apply()
    }

    /** 追加一条落盘记录,超出上限丢最旧的 */
    private fun addSavedFile(record: SavedFile) {
        val records = loadSavedFiles()
        records.add(record)
        while (records.size > MAX_DOWNLOAD_RECORDS) records.removeAt(0)
        saveSavedFiles(records)
    }

    /** 合并引擎任务、系统下载(DownloadManager)与 blob/data 落盘记录,时间倒序供页面渲染 */
    private fun collectDownloadItems(): List<DownloadItem> {
        val items = mutableListOf<DownloadItem>()
        // 自研引擎任务(内置下载器)
        downloader.snapshot().forEach { s ->
            items.add(
                DownloadItem(
                    id = "ldm-${s.id}",
                    name = s.name,
                    kind = s.kind,
                    status = s.status,
                    time = s.time,
                    sourceUrl = s.url.takeIf { it.startsWith("http") },
                    path = s.path,
                    pct = s.pct
                )
            )
        }
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        if (dm != null) {
            runCatching {
                // 「只删记录」过的系统下载不再展示(文件仍留在公共下载目录)
                val hiddenDm = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getStringSet(PREF_DM_HIDDEN, null).orEmpty()
                dm.query(DownloadManager.Query()).use { c ->
                    val iId = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                    val iTitle = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)
                    val iStatus = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                    val iDone = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val iTotal = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    // 最后修改时间列是 @hide(DownloadManager.COLUMN_LAST_MODIFICATION_TIMESTAMP),
                    // 直接用 downloads provider 底层列名;极旧版本取不到时以当前时间兜底
                    val iTime = runCatching { c.getColumnIndexOrThrow("lastmod") }.getOrDefault(-1)
                    val iUri = c.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)
                    while (c.moveToNext()) {
                        val id = c.getLong(iId)
                        if (id.toString() in hiddenDm) continue
                        val status = c.getInt(iStatus)
                        val sofar = c.getLong(iDone).coerceAtLeast(0)
                        val total = c.getLong(iTotal)
                        val time = if (iTime >= 0) c.getLong(iTime) else System.currentTimeMillis()
                        val kind = when (status) {
                            DownloadManager.STATUS_RUNNING -> "running"
                            DownloadManager.STATUS_PENDING -> "waiting"
                            DownloadManager.STATUS_PAUSED -> "paused"
                            DownloadManager.STATUS_SUCCESSFUL -> "done"
                            else -> "failed"
                        }
                        val statusText = when (kind) {
                            "running" -> {
                                val running = getString(R.string.browser_dl_status_running)
                                when {
                                    total > 0 -> running + "·" + (sofar * 100 / total) +
                                        "%(" + formatBytes(sofar) + "/" + formatBytes(total) + ")"
                                    sofar > 0 -> running + "·" + formatBytes(sofar)
                                    else -> running
                                }
                            }
                            "waiting" -> getString(R.string.browser_dl_status_waiting)
                            "paused" -> getString(R.string.browser_dl_status_paused)
                            // 完成态给满进度,样式与引擎任务一致(无进度条)
                            "done" -> {
                                val base = getString(R.string.browser_dl_status_done)
                                val size = formatBytes(if (total > 0) total else sofar)
                                if (size.isEmpty()) "$base·100%" else "$base·100%·$size"
                            }
                            else -> getString(R.string.browser_dl_status_failed)
                        }
                        items.add(
                            DownloadItem(
                                id = "dm-$id",
                                name = c.getString(iTitle)?.takeIf { it.isNotBlank() } ?: "download",
                                kind = kind,
                                status = statusText,
                                time = time,
                                sourceUrl = c.getString(iUri),
                                pct = if (kind == "running" && total > 0) {
                                    ((sofar * 100) / total).toInt().coerceIn(0, 100)
                                } else {
                                    -1
                                }
                            )
                        )
                    }
                }
            }
        }
        loadSavedFiles().forEach { f ->
            val sizeText = formatBytes(f.size)
            items.add(
                DownloadItem(
                    id = "nat-${f.id}",
                    name = f.name,
                    kind = "saved",
                    status = getString(R.string.browser_dl_status_saved) +
                        if (sizeText.isEmpty()) "" else "·$sizeText",
                    time = f.time,
                    sourceUrl = null
                )
            )
        }
        return items.sortedByDescending { it.time }
    }

    /**
     * 点条目:已完成的唤起「打开方式」选择器;引擎的进行中任务点按=暂停、
     * 已暂停/失败点按=继续下载(主流浏览器语义),系统下载进行中提示状态。
     */
    private fun openDownloadItem(id: String) {
        if (id.startsWith("ldm-")) {
            val taskId = id.removePrefix("ldm-").toLongOrNull() ?: return
            val snap = downloader.snapshot().firstOrNull { it.id == taskId } ?: return
            when (snap.kind) {
                "done" -> {
                    val target = when {
                        // MediaStore/SAF 落盘:内容地址直接打开
                        !snap.uri.isNullOrBlank() -> Uri.parse(snap.uri)
                        // ≤28 应用专属目录:FileProvider 授权后交外部应用打开
                        !snap.path.isNullOrBlank() && File(snap.path).exists() -> runCatching {
                            FileProvider.getUriForFile(this, "$packageName.fileprovider", File(snap.path))
                        }.getOrNull()
                        else -> null
                    } ?: run {
                        toast(R.string.browser_dl_open_fail)
                        return
                    }
                    openWithViewer(target, snap.mime.orEmpty())
                }
                "running", "waiting" -> {
                    downloader.pause(taskId)
                    toast(R.string.browser_dl_paused)
                }
                "paused", "failed" -> {
                    downloader.resume(taskId)
                    toast(R.string.browser_dl_resumed)
                }
            }
            return
        }
        if (id.startsWith("dm-")) {
            val dmId = id.removePrefix("dm-").toLongOrNull() ?: return
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return
            runCatching {
                dm.query(DownloadManager.Query().setFilterById(dmId)).use { c ->
                    if (!c.moveToFirst()) return
                    when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            val uri = dm.getUriForDownloadedFile(dmId) ?: run {
                                toast(R.string.browser_dl_open_fail)
                                return
                            }
                            openWithViewer(uri, dm.getMimeTypeForDownloadedFile(dmId).orEmpty())
                        }
                        DownloadManager.STATUS_FAILED -> toast(R.string.browser_dl_status_failed)
                        else -> toast(R.string.browser_dl_in_progress)
                    }
                }
            }.onFailure { toast(R.string.browser_dl_open_fail) }
            return
        }
        val nid = id.removePrefix("nat-").toLongOrNull() ?: return
        val record = loadSavedFiles().firstOrNull { it.id == nid } ?: return
        val uri = when {
            // ≤28 落在应用专属目录:FileProvider 授权后交外部应用打开
            record.path != null -> runCatching {
                FileProvider.getUriForFile(this, "$packageName.fileprovider", File(record.path))
            }.getOrNull() ?: run {
                toast(R.string.browser_dl_open_fail)
                return
            }
            record.uri.isNotEmpty() -> Uri.parse(record.uri)
            else -> null
        } ?: return
        openWithViewer(uri, record.mime)
    }

    /**
     * 用外部应用打开内容地址:唤起系统「打开方式」选择器(不走默认应用),
     * mime 缺省通配;FileProvider 的地址补上 ClipData 授权,选择器里任何应用都能读。
     */
    private fun openWithViewer(uri: Uri, mime: String) {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(view, getString(R.string.browser_dl_open_with))
        if (uri.authority == "$packageName.fileprovider") {
            chooser.clipData = ClipData.newRawUri(null, uri)
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(chooser)
        } catch (_: Exception) {
            toast(R.string.browser_dl_open_fail)
        }
    }

    /**
     * 长按「删除」:先弹确认框,可勾选同时删除本地文件(默认只删记录)。
     * 内容区自绘(文件名 + 勾选行),不依赖系统列表:appcompat 弹窗
     * setMessage 与 setMultiChoiceItems 同用时勾选行不渲染。
     */
    private fun confirmDeleteDownload(item: DownloadItem) {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val box = CheckBox(this)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            val a = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            background = a.getDrawable(0)
            a.recycle()
            setOnClickListener { box.toggle() }   // 整行可点,不必精确点中框
            addView(box)
            addView(
                TextView(this@BrowserActivity).apply {
                    setText(R.string.browser_dl_delete_file_too)
                    setTextAppearance(android.R.style.TextAppearance_Material_Medium)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(8) }
            )
        }
        // 内边距取主题的 dialogPreferredPadding:与弹窗标题/正文对齐,不猜各 ROM 的数值
        val pad = TypedValue().let { tv ->
            if (theme.resolveAttribute(androidx.appcompat.R.attr.dialogPreferredPadding, tv, true) &&
                tv.type == TypedValue.TYPE_DIMENSION
            ) {
                TypedValue.complexToDimension(tv.data, resources.displayMetrics).toInt()
            } else {
                dp(20)
            }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(4), pad, dp(4))
            addView(
                TextView(this@BrowserActivity).apply {
                    text = item.name
                    setTextAppearance(android.R.style.TextAppearance_Material_Medium)
                }
            )
            addView(
                row, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(12) }
            )
        }
        try {
            val dialog = AlertDialog.Builder(this)
                .setTitle(R.string.browser_dl_delete_title)
                .setView(content)
                .setPositiveButton(R.string.browser_dl_delete) { _, _ ->
                    deleteDownloadItem(item.id, box.isChecked)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            // 部分主题会给自定义内容区再包一层内边距,清掉,间距以自绘区为准
            (dialog.findViewById(androidx.appcompat.R.id.custom) as? ViewGroup)?.setPadding(0, 0, 0, 0)
        } catch (_: Exception) {
        }
    }

    /**
     * 删除下载条目:记录总是删;本地文件按 [deleteFile] 决定去留。
     * 引擎任务连 .part 中转文件一起清;系统下载勾选时 dm.remove(记录与文件一起删),
     * 不勾选时只把条目从列表隐藏(DownloadManager.remove 连文件一起删,只能记隐藏名单);
     * 落盘记录删除 MediaStore 条目/SAF 文档/应用目录文件。
     */
    private fun deleteDownloadItem(id: String, deleteFile: Boolean) {
        when {
            id.startsWith("ldm-") -> {
                val taskId = id.removePrefix("ldm-").toLongOrNull() ?: return
                downloader.delete(taskId, deleteFile)
            }
            id.startsWith("dm-") -> {
                val dmId = id.removePrefix("dm-").toLongOrNull() ?: return
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return
                if (deleteFile) {
                    runCatching { dm.remove(dmId) }
                } else {
                    hideDownloadManagerRow(dmId)
                }
            }
            else -> {
                val nid = id.removePrefix("nat-").toLongOrNull() ?: return
                val record = loadSavedFiles().firstOrNull { it.id == nid } ?: return
                saveSavedFiles(loadSavedFiles().filter { it.id != nid })
                if (deleteFile) runCatching { deleteCommitted(this, record.uri, record.path ?: "") }
            }
        }
        toast(R.string.browser_dl_deleted)
        reloadDownloadsPage()
    }

    /** 把系统下载条目从下载管理页隐藏但保留文件:名单存主配置 */
    private fun hideDownloadManagerRow(dmId: Long) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hidden = prefs.getStringSet(PREF_DM_HIDDEN, null)?.toMutableSet() ?: mutableSetOf()
        hidden.add(dmId.toString())
        prefs.edit().putStringSet(PREF_DM_HIDDEN, hidden).apply()
    }

    /** 下载管理页 JS 桥:点条目打开、长按弹原生菜单(带条目纵向位置) */
    private inner class DownloadsBridge {
        @JavascriptInterface
        fun open(id: String) {
            runOnUiThread { openDownloadItem(id) }
        }

        @JavascriptInterface
        fun onLongPress(id: String, cssTop: Double, cssHeight: Double) {
            runOnUiThread { showDownloadItemMenu(id, cssTop.toFloat(), cssHeight.toFloat()) }
        }
    }

    /**
     * 下载条目长按菜单:暂停/继续(引擎任务)/ 打开(已完成/已保存,走「打开方式」)/
     * 复制来源链接(有链接的)/ 复制文件路径(已落盘的)/ 删除
     */
    private fun showDownloadItemMenu(id: String, cssTop: Float, cssHeight: Float) {
        val item = collectDownloadItems().firstOrNull { it.id == id } ?: return
        val view = tabs.firstOrNull { isDownloadsTab(it) }?.webView ?: return
        val actions = mutableListOf<BrowserMenuAction>()
        when (item.kind) {
            "running", "waiting" -> if (id.startsWith("ldm-")) {
                val taskId = id.removePrefix("ldm-").toLongOrNull()
                if (taskId != null) {
                    actions.add(
                        BrowserMenuAction(getString(R.string.browser_dl_pause), action = {
                            downloader.pause(taskId)
                        })
                    )
                }
            }
            "paused", "failed" -> if (id.startsWith("ldm-")) {
                val taskId = id.removePrefix("ldm-").toLongOrNull()
                if (taskId != null) {
                    actions.add(
                        BrowserMenuAction(getString(R.string.browser_dl_resume), action = {
                            downloader.resume(taskId)
                            reloadDownloadsPage()
                        })
                    )
                }
            }
            "done", "saved" -> actions.add(
                BrowserMenuAction(getString(R.string.browser_dl_open), action = { openDownloadItem(id) })
            )
        }
        item.sourceUrl?.takeIf { it.startsWith("http") }?.let { source ->
            actions.add(
                BrowserMenuAction(getString(R.string.browser_dl_copy_link), action = {
                    copyToClipboard(source)
                    toast(R.string.browser_history_copied)
                })
            )
        }
        itemPathForCopy(item)?.let { path ->
            actions.add(
                BrowserMenuAction(getString(R.string.browser_dl_copy_path), action = {
                    copyToClipboard(path)
                    toast(R.string.browser_dl_path_copied)
                })
            )
        }
        actions.add(
            BrowserMenuAction(getString(R.string.browser_dl_delete), action = { confirmDeleteDownload(item) })
        )
        showRowMenuInWebView(view, cssTop, cssHeight, actions)
    }

    /** 复制到系统剪贴板 */
    private fun copyToClipboard(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.setPrimaryClip(ClipData.newPlainText(null, text))
    }

    /**
     * 条目的文件路径(供长按复制):引擎任务/应用专属目录直接给;
     * MediaStore/SAF 内容地址尽力解析成物理路径,解析不出就没有该菜单项。
     */
    private fun itemPathForCopy(item: DownloadItem): String? {
        when (item.id.substringBefore('-')) {
            "ldm" -> return item.path
            "nat" -> {
                val nid = item.id.removePrefix("nat-").toLongOrNull() ?: return null
                val record = loadSavedFiles().firstOrNull { it.id == nid } ?: return null
                record.path?.let { return it }
                val uri = record.uri.takeIf { it.isNotEmpty() } ?: return null
                return resolveMediaPath(this, Uri.parse(uri))
            }
            "dm" -> {
                val dmId = item.id.removePrefix("dm-").toLongOrNull() ?: return null
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return null
                val uri = runCatching { dm.getUriForDownloadedFile(dmId) }.getOrNull() ?: return null
                return resolveMediaPath(this, uri)
            }
        }
        return null
    }

    /** 下载管理页数据(JSON 数组:id/name/kind/status/time/pct/search) */
    private fun downloadsJson(): String {
        val arr = JSONArray()
        collectDownloadItems().forEach { item ->
            arr.put(
                JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("kind", item.kind)
                    .put("status", item.status)
                    .put("time", formatHistoryTime(item.time))
                    .put("pct", item.pct)
                    .put("search", (item.name + " " + item.sourceUrl.orEmpty()).lowercase(Locale.ROOT))
            )
        }
        // 内联进 <script>:转义 < 防止 </script> 提前闭合
        return arr.toString().replace("<", "\\u003c")
    }

    /** 进度实时刷新:ContentObserver 去抖后把最新数据推给当前开着的下载管理页 */
    private fun pushDownloadsData() {
        val tab = tabs.getOrNull(currentIndex) ?: return
        if (!isDownloadsTab(tab)) return
        tab.webView.evaluateJavascript("renderData(${downloadsJson()})", null)
    }

    /**
     * 节流式刷新:第一次回调后最多 1 秒推一次,推送期间后续回调合并掉 ——
     * 不能用 removeCallbacks+postDelayed 去抖:引擎每 400ms 回调一次,
     * 每次都把待执行的推送往后推,下载期间永远等不到触发。
     */
    private fun scheduleDownloadsRefresh() {
        if (downloadsRefreshPending) return
        downloadsRefreshPending = true
        mainHandler.postDelayed(downloadsRefreshRunnable, DOWNLOAD_REFRESH_DELAY_MS)
    }

    /** 系统下载进度观察者:Activity 存续期注册,只在下载管理页是当前页时才推送刷新 */
    private fun registerDownloadsObserver() {
        if (downloadsObserver != null) return
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                scheduleDownloadsRefresh()
            }
        }
        downloadsObserver = observer
        runCatching {
            contentResolver.registerContentObserver(
                Uri.parse("content://downloads/my_downloads"), true, observer
            )
        }
    }

    /**
     * 下载管理页 HTML:行数据由页面内 renderData(JSON) 渲染 —— 初始数据内联,
     * 之后进度变化走 evaluateJavascript 增量推 JSON,不整页重载(滚动位置/搜索词不丢)。
     * 条目可长按(500ms,JS 检测)弹原生菜单;顶部搜索框按文件名/来源过滤。
     */
    private fun buildDownloadsHtml(): String {
        val bg = colorHex(R.color.bg)
        val fg = colorHex(R.color.on_surface)
        val hint = colorHex(R.color.hint)
        val div = colorHex(R.color.divider)
        val surface = colorHex(R.color.surface)
        val accent = colorHex(R.color.accent)
        val empty = getString(R.string.browser_dl_empty)
        val script = """
            var items=[];
            function esc(s){return String(s==null?'':s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');}
            function renderData(list){
              items=list||[];
              var html='';
              for(var i=0;i<items.length;i++){
                var it=items[i];
                html+="<a href='javascript:void(0)' data-id='"+i+"' data-search=\""+esc(it.search)+"\" "+
                  "ontouchstart='lpStart(event,this)' ontouchmove='lpCancel()' ontouchend='lpEnd(event)' "+
                  "onclick='return onTap(this)'>"+
                  "<span class='col'><span class='t'>"+esc(it.name)+"</span>"+
                  "<span class='u'>"+esc(it.status)+"</span>"+
                  (it.pct>=0?"<span class='bar'><i style='width:"+it.pct+"%'></i></span>":"")+
                  "</span>"+
                  "<span class='time'>"+esc(it.time)+"</span></a>";
              }
              document.getElementById('list').innerHTML=html||"<div class='empty'>$empty</div>";
              var q=document.getElementById('q');
              onSearch(q?q.value:'');
            }
            function onTap(el){
              var it=items[parseInt(el.getAttribute('data-id'),10)];
              if(it&&window.LdmDownloads){ LdmDownloads.open(it.id); }
              return false;
            }
            var lpTimer=null, lpFired=false, lpEl=null;
            function lpStart(ev, el){
              lpFired=false; lpEl=el;
              lpTimer=setTimeout(function(){
                lpFired=true;
                if(window.LdmDownloads && lpEl){
                  var it=items[parseInt(lpEl.getAttribute('data-id'),10)];
                  if(it){
                    var r=lpEl.getBoundingClientRect();
                    LdmDownloads.onLongPress(it.id, r.top, r.height);
                  }
                }
              }, 500);
            }
            function lpCancel(){ if(lpTimer){ clearTimeout(lpTimer); lpTimer=null; } }
            function lpEnd(ev){ lpCancel(); if(lpFired){ ev.preventDefault(); lpFired=false; } }
            renderData(__init);
        """.trimIndent()
        return "<!DOCTYPE html><html><head><meta charset='utf-8'>" +
            "<meta name='viewport' content='width=device-width, initial-scale=1'>" +
            "<title>${getString(R.string.browser_downloads)}</title><style>" +
            "body{margin:0;background:$bg;color:$fg;}" +
            "a{display:flex;align-items:center;gap:10px;padding:10px 14px;" +
            "text-decoration:none;color:$fg;border-bottom:1px solid $div;" +
            "-webkit-user-select:none;user-select:none;}" +
            "a:active{background:$div;}" +
            ".col{flex:1;min-width:0;display:flex;flex-direction:column;}" +
            ".t{font-size:15px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}" +
            ".u{font-size:12px;color:$hint;white-space:nowrap;overflow:hidden;" +
            "text-overflow:ellipsis;margin-top:2px;}" +
            ".bar{display:block;height:3px;margin-top:5px;background:$div;" +
            "border-radius:2px;overflow:hidden;}" +
            ".bar i{display:block;height:100%;background:$accent;border-radius:2px;}" +
            ".time{flex-shrink:0;font-size:12px;color:$hint;}" +
            ".empty{padding:48px 0;text-align:center;color:$hint;font-size:14px;}" +
            listPageSearchCss(surface, bg, fg, hint, div) +
            "</style><script>$listPageSearchJs</script></head><body>" +
            listPageSearchBar(R.string.browser_dl_search_hint) +
            "<div id='list'></div>" +
            "<div id='noresult' class='empty' style='display:none'>" +
            getString(R.string.browser_dl_search_empty) + "</div>" +
            "<script>var __init=${downloadsJson()};$script</script></body></html>"
    }

    /**
     * blob 下载文件名钩子:捕获阶段监听点击,记录被点 <a download href="blob:..."> 的
     * download 属性 —— DownloadListener 拿不到 anchor 信息,随后从这里读文件名。
     * window 标记防重复注入;每次导航是新 document,标记自然复位。
     */
    private fun injectBlobDownloadHook(view: WebView?) {
        if (view == null) return
        view.evaluateJavascript(
            "(function(){if(window.__ldmBlobHook){return;}window.__ldmBlobHook=1;" +
                "document.addEventListener('click',function(e){" +
                "var el=e.target;while(el&&el.tagName!=='A'){el=el.parentElement;}" +
                "if(!el){return;}var h=el.getAttribute('href')||'';" +
                "if(h.indexOf('blob:')===0){window.__ldmBlobName=el.getAttribute('download')||'';}" +
                "},true);})()",
            null
        )
    }

    private fun rememberNavigableUrl(view: WebView?, url: String?) {
        if (view == null || !isWebUrl(url)) return
        val index = tabs.indexOfFirst { it.webView == view }
        if (index < 0) return
        tabs[index].url = url!!
        persistTabs()
    }

    private fun isInternalOrBlankUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return true
        val lower = url.lowercase(Locale.ROOT)
        return lower == "about:blank" ||
            lower.startsWith("about:") ||
            lower.startsWith("data:") ||
            lower.startsWith("javascript:")
    }

    /** 可作为标签持久化/恢复的网址:仅 http/https/file(自定义 scheme 交外部应用,不进标签) */
    private fun isWebUrl(url: String?): Boolean {
        if (isInternalOrBlankUrl(url)) return false
        val scheme = try {
            Uri.parse(url)?.scheme?.lowercase(Locale.ROOT)
        } catch (_: Exception) {
            null
        }
        return scheme == "http" || scheme == "https" || scheme == "file"
    }

    /** 非 http(s)/file 的自定义 scheme(百度App、微信、支付宝、intent://、tel: 等) */
    private fun isExternalSchemeUrl(url: String): Boolean {
        return try {
            val scheme = Uri.parse(url)?.scheme?.lowercase(Locale.ROOT)
            !scheme.isNullOrBlank() && !isInternalOrBlankUrl(url) &&
                scheme != "http" && scheme != "https" && scheme != "file"
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 自定义 scheme 链接(网页请求唤起外部应用)的标准处理:
     * intent:// 按 Intent URI 解析,普通 scheme 解析成 ACTION_VIEW;
     * 为防 intent 重定向攻击,解析结果清空 component / selector 只按隐式 intent 匹配。
     * 跳转不立即执行:先弹「打开外部应用」确认,允许才走 [startExternalIntent]。
     */
    private fun launchExternalScheme(url: String) {
        val intent = try {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        } catch (_: Exception) {
            null
        } ?: return
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        showOpenAppConfirm(intent, url)
    }

    /**
     * 唤端确认弹窗:整宽、靠屏幕下方显示,确定才真正跳转。
     * 弹窗开着时忽略后续唤端请求(页面反复唤端不叠窗);
     * 目标应用名解析不到(API 30+ 无 queries 声明受包可见性限制)就在正文里展示要跳的链接。
     */
    private fun showOpenAppConfirm(intent: Intent, sourceUrl: String) {
        if (isFinishing || isDestroyed || openAppDialog?.isShowing == true) return
        val appName = try {
            packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.loadLabel(packageManager)?.toString().orEmpty()
        } catch (_: Exception) {
            ""
        }
        val desc = appName.ifBlank { (intent.data?.toString() ?: sourceUrl).take(64) }
        openAppDialog = AlertDialog.Builder(this)
            .setTitle(R.string.browser_open_app_title)
            .setMessage(getString(R.string.browser_open_app_message, desc))
            .setPositiveButton(R.string.confirm) { _, _ -> startExternalIntent(intent) }
            .setNegativeButton(R.string.cancel, null)
            .show()
        openAppDialog?.window?.apply {
            // 靠屏幕下方:整宽贴底,再留一点离屏底的呼吸距离
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            setGravity(Gravity.BOTTOM)
            attributes = attributes.also { it.y = (12 * resources.displayMetrics.density).toInt() }
        }
    }

    /** 确认后真正唤起;打不开时优先回退 intent 携带的 browser_fallback_url,否则提示 */
    private fun startExternalIntent(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            val fallback = intent.getStringExtra("browser_fallback_url")
            if (fallback != null && isWebUrl(fallback)) {
                loadAddress(fallback)
            } else if (SystemClock.elapsedRealtime() - lastTouchTime < GESTURE_TOAST_WINDOW_MS) {
                // 只在用户主动点链接时提示;页面 JS 自动唤端(如百度首屏)静默忽略
                android.widget.Toast.makeText(
                    this,
                    R.string.browser_no_app,
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        } catch (_: Exception) {
        }
    }

    /** 网页文件选择参数 → 系统选择器 Intent(打开/多选/另存) */
    private fun buildFileChooserIntent(params: WebChromeClient.FileChooserParams?): Intent {
        val mode = params?.mode ?: WebChromeClient.FileChooserParams.MODE_OPEN
        val accepts = params?.acceptTypes?.filter { it.isNotBlank() }.orEmpty()
        val mime = accepts.firstOrNull { it.contains('/') } ?: "*/*"
        val mimes = accepts.filter { it.contains('/') }.distinct().toTypedArray()
        return if (mode == WebChromeClient.FileChooserParams.MODE_SAVE) {
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type = mime
                addCategory(Intent.CATEGORY_OPENABLE)
                params?.filenameHint?.takeIf { it.isNotBlank() }?.let {
                    putExtra(Intent.EXTRA_TITLE, it)
                }
                if (mimes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimes)
            }
        } else {
            Intent(Intent.ACTION_GET_CONTENT).apply {
                type = mime
                addCategory(Intent.CATEGORY_OPENABLE)
                if (mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                if (mimes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimes)
            }
        }
    }

    /** 选择结果 → WebView 回调参数(取消返回 null) */
    private fun extractFileChooserUris(resultCode: Int, data: Intent?): Array<Uri>? {
        if (resultCode != Activity.RESULT_OK || data == null) return null
        val clip = data.clipData
        if (clip != null && clip.itemCount > 0) {
            val uris = ArrayList<Uri>(clip.itemCount)
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).uri?.let { uris.add(it) }
            }
            if (uris.isNotEmpty()) return uris.toTypedArray()
        }
        return data.data?.let { arrayOf(it) }
    }

    /** 输入 → 可加载地址:带 scheme 原样返回;像主机名的补 http://;否则用所选搜索引擎搜索 */
    private fun normalizeInputToUrl(rawInput: String): String {
        val input = rawInput.trim()
        if (input.isBlank()) return ""
        val lower = input.lowercase(Locale.ROOT)
        val hasScheme = lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("file://")
        if (hasScheme) return input
        if (inputLooksLikeUrl(input)) return "http://$input"
        return searchUrl(input)
    }

    private fun updateToolbarState() {
        // 前进/后退按钮跟自管历史游标走(WebView 原生历史无法跨启动恢复)
        val tab = tabs.getOrNull(currentIndex)
        btnBack.isEnabled = (tab?.historyIndex ?: 0) > 0
        btnForward.isEnabled = tab != null && tab.historyIndex < tab.history.lastIndex
        btnBack.alpha = if (btnBack.isEnabled) 1f else 0.35f
        btnForward.alpha = if (btnForward.isEnabled) 1f else 0.35f
        // 刷新/停止按钮:当前标签加载中显示 X(点按停止),空闲时显示刷新
        val loading = tabs.getOrNull(currentIndex)?.isLoading == true
        if (loading) {
            btnRefresh.setImageResource(R.drawable.ic_close)
            btnRefresh.contentDescription = getString(R.string.browser_stop)
        } else {
            btnRefresh.setImageResource(R.drawable.ic_refresh)
            btnRefresh.contentDescription = getString(R.string.refresh)
        }
        // 收藏星:当前页是否已收藏(导航/切标签等地址变化都会走到这里)
        updateBookmarkStar()
    }

    /** 停止当前标签的加载:X 按钮语义;onPageFinished 可能不再回调,状态手动复位 */
    private fun stopCurrentLoad() {
        val tab = tabs.getOrNull(currentIndex) ?: return
        tab.webView.stopLoading()
        tab.isLoading = false
        progressBar.visibility = View.GONE
        updateToolbarState()
    }

    /**
     * 立即置为加载中:所有主动发起的加载(回车/刷新/点链接/新标签)都要调,
     * onPageStarted 只在服务器开始返回数据时才触发,慢站点(如 GitHub)挂起阶段
     * 什么回调都没有,不提前标记的话刷新按钮迟迟不变 X。
     */
    private fun markLoading(tab: TabItem?) {
        tab ?: return
        tab.isLoading = true
        if (tabs.getOrNull(currentIndex) === tab) {
            progressBar.visibility = View.VISIBLE
            progressBar.progress = 5
            updateToolbarState()
        }
    }

    /**
     * 由网页标题生成标签标题:空白或 WebView 内置错误页标题回退主机名。
     * 存完整标题,截断交给显示层(标签 TextView 的 maxWidth + ellipsize),
     * 这样重命名等地方拿到的是完整文字而不是带省略号的截断版。
     */
    private fun pageTabTitle(item: TabItem, pageTitle: String?): String {
        val raw = pageTitle?.trim().orEmpty()
        return when {
            raw.isBlank() || raw.equals("about:blank", true) -> shortHost(item.url)
            // WebView 内置错误页标题(网页无法打开 / Webpage not available)不当标签名
            raw.contains("无法打开") || raw.contains("not available", true) -> shortHost(item.url)
            else -> raw
        }
    }

    private fun shortHost(url: String): String = runCatching {
        Uri.parse(url).host ?: url
    }.getOrDefault(url).take(MAX_TAB_TITLE_LENGTH)

    override fun onPause() {
        super.onPause()
        // 兜底保存:离开界面(回桌面/灭屏/被其他界面覆盖)就落一次盘,
        // 之后进程被直接杀(后台划卡清理)也能恢复到最新状态
        if (saveOnExit) saveSession()
    }

    override fun onResume() {
        super.onResume()
        // 回到界面时下载管理页可能已过期(在外面时下载完成/被删),推一次最新数据
        if (tabs.getOrNull(currentIndex)?.let { isDownloadsTab(it) } == true) {
            pushDownloadsData()
        }
    }

    override fun onDestroy() {
        // 下载器收尾:摘掉下载进度观察者与引擎回调,丢弃进行中的 blob 接收任务(临时文件一并清理)
        downloadsObserver?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
        downloadsObserver = null
        mainHandler.removeCallbacks(downloadsRefreshRunnable)
        downloader.onUpdate = null
        blobTasks.values.forEach { task ->
            runCatching { task.stream.close() }
            runCatching { task.file.delete() }
        }
        blobTasks.clear()
        // 退出前保存:开 → 落一份最终完整状态;关 → 真正退出时清档(下次打开不恢复)
        if (saveOnExit) {
            saveSession()
        } else if (isFinishing) {
            clearSession()
        }
        openAppDialog?.dismiss()
        openAppDialog = null
        tabs.forEach {
            it.webView.stopLoading()
            it.webView.destroy()
        }
        tabs.clear()
        super.onDestroy()
    }

    companion object {
        private const val MAX_TAB_TITLE_LENGTH = 12

        /** GitHub 仓库地址(关于弹窗里点击新开标签页跳转) */
        private const val GITHUB_URL = "https://github.com/landamao/browser"

        // 夜间模式在 LdmBrowserApp(Application)里也要读,故设 internal 供其引用
        internal const val PREFS_NAME = "ldmbrowser_settings"
        private const val PREF_DESKTOP = "browser_desktop_mode"

        /** 自定义主页(菜单「主页」长按设置):主页动作与新标签页都用它 */
        private const val PREF_HOME_URL = "home_url"
        private const val PREF_HOME_TITLE = "home_title"

        /** 标签页状态持久化(旧版只存链接与选中位置;现由 PREF_SAVED_SESSION 全量接管) */
        private const val PREF_SAVED_TABS = "browser_saved_tabs"
        private const val PREF_SAVED_TAB_INDEX = "browser_saved_tab_index"

        /** 退出前保存开关(菜单切换):关 = 退出时清档,下次打开空白/主页 */
        private const val PREF_SAVE_ON_EXIT = "save_on_exit"

        /** 完整会话存档:顺序/当前标签/标题与重命名/锁定/前进后退历史 */
        private const val PREF_SAVED_SESSION = "browser_saved_session"

        /** 调换布局:标签页在地址栏上方(「标签页和网址栏设置」弹窗切换) */
        private const val PREF_TABS_ON_TOP = "tabs_on_top"

        /** 上下分布:标签栏与网址栏整行分居屏幕顶/底(「标签页和网址栏设置」弹窗切换) */
        private const val PREF_URL_BAR_BOTTOM = "url_bar_bottom"

        /** 标签栏隐藏(长按「菜单」/「全部标签页」切换,持久化) */
        private const val PREF_TAB_BAR_HIDDEN = "tab_bar_hidden"

        /** 历史记录页虚拟地址(about: 系,不进网页历史、不随会话持久化) */
        private const val HISTORY_URL = "about:history"

        /** 访问记录存储 key 与条数上限(超出丢最旧) */
        private const val PREF_HISTORY = "browser_history"
        private const val MAX_HISTORY_RECORDS = 300

        /** 收藏列表页虚拟地址(about: 系,同历史记录页,不进网页历史、随会话保存) */
        private const val BOOKMARKS_URL = "about:bookmarks"

        /** 收藏记录存储 key(JSONArray:u=网址 t=标题,新的在前) */
        private const val PREF_BOOKMARKS = "browser_bookmarks"

        /** 下载管理页虚拟地址(about: 系,同历史记录页,不进网页历史、随会话保存) */
        private const val DOWNLOADS_URL = "about:downloads"

        /** blob/data 落盘记录存储 key(JSONArray:id/uri/path/n/m/s/ts,新的在后) */
        private const val PREF_DOWNLOADS = "browser_downloads"

        /** 只删记录保留文件的系统下载 id 名单(DownloadManager.remove 连文件一起删) */
        private const val PREF_DM_HIDDEN = "browser_dm_hidden"
        private const val MAX_DOWNLOAD_RECORDS = 300

        /** 下载方式(「下载设置」弹窗下拉切换,持久化):0=内置下载器(自研引擎) 1=系统下载器 */
        internal const val PREF_DL_METHOD = "browser_dl_method"
        internal const val DL_METHOD_BUILTIN = 0
        internal const val DL_METHOD_SYSTEM = 1

        private const val REQ_POST_NOTIFICATIONS = 4001
        private const val REQ_PICK_DOWNLOAD_DIR = 4002

        /** 下载进度实时刷新:ContentObserver 触发后的去抖间隔(ms) */
        private const val DOWNLOAD_REFRESH_DELAY_MS = 1000L

        /** blob 内容分片大小(字节):1MB,base64 后过 JS 桥的字符串长度可控 */
        private const val DOWNLOAD_BLOB_CHUNK_BYTES = 1024 * 1024

        /** 标签页宽度设置(设置弹窗双点滑块,dp;两值相同 = 固定宽度) */
        private const val PREF_TAB_MIN_WIDTH = "tab_min_width"
        private const val PREF_TAB_MAX_WIDTH = "tab_max_width"
        private const val TAB_WIDTH_RANGE_MIN = 48
        private const val TAB_WIDTH_RANGE_MAX = 240

        /** 尾部「+」标签的宽度(item_browser_tab_add):计算摊满份额时要从整排里扣掉 */
        private const val PLUS_TAB_WIDTH_DP = 32
        private const val TAB_WIDTH_DEFAULT_MIN = 48
        private const val TAB_WIDTH_DEFAULT_MAX = 186

        /** 夜间模式开关(更多菜单):存 AppCompatDelegate 的模式值(LdmBrowserApp 启动时读取) */
        internal const val PREF_NIGHT_MODE = "night_mode"

        /** 文本编辑器内容(自动保存) */
        private const val PREF_NOTE = "note_text"

        /** 快捷输入条:快捷键列表(JSONArray)与列数 */
        private const val PREF_QUICK_TOKENS = "quick_tokens"
        private const val PREF_QUICK_COLS = "quick_cols"

        /** 文本编辑器面板透明度(0–255) */
        private const val PREF_EDITOR_ALPHA = "editor_alpha"
        private const val PREF_EDITOR_ALPHA_DEFAULT = 235

        /** 文本编辑器窗口大小(调节状态「完成」保存;重开恢复,0 = 用默认宽) */
        private const val PREF_EDITOR_W = "editor_window_w"
        private const val PREF_EDITOR_H = "editor_window_h"

        /** 提取链接:正文里的 http(s)/www 链接(忽略大小写) */
        private val NOTE_URL_REGEX = Regex("(?:https?://|www\\.)\\S+", RegexOption.IGNORE_CASE)

        /** 提取链接时黏在链接尾部、要去掉的常见标点(ASCII + 中文) */
        private const val NOTE_URL_TRAILING = ".,;:!?)]}>\"'、。，,;:!!??）】》」》”’"

        /** 电脑模式强制布局宽度(CSS px),让媒体查询走桌面分支 */
        private const val DESKTOP_LAYOUT_WIDTH = 1280

        /** 所选搜索引擎(地址栏框内左侧)持久化 key */
        private const val PREF_ENGINE = "search_engine"

        /** 手势触发外部 scheme 失败后提示「没有应用」的时间窗(ms),超过视为页面自动唤端 */
        private const val GESTURE_TOAST_WINDOW_MS = 1500L
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
