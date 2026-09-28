package com.landamao.browser

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

/**
 * 进程入口:在任何 Activity 创建前应用持久化的夜间模式。
 *
 * 夜间模式必须在 Activity attach 前生效:attachBaseContext 阶段 context 的
 * 资源配置与主题就已按默认模式(跟随系统)解析并缓存,之后在 onCreate 里
 * 再调 setDefaultNightMode 只能更新资源层,主题层(弹窗/对话框背景全部
 * 来自主题属性)仍是日间值 —— 重启后就会出现标签栏已夜间、菜单和弹窗
 * 白底、甚至白底白字的错位。在这里提前设置,Activity attach 时即带
 * 目标模式的配置与主题,从冷启动就保持一致。
 */
class LdmBrowserApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(
            getSharedPreferences(BrowserActivity.PREFS_NAME, MODE_PRIVATE)
                .getInt(BrowserActivity.PREF_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        )
    }
}
