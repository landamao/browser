package com.landamao.browser

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 保存的网站密码(登录表单捕获与自动填充共用的存储层):
 * - 密文存储:AndroidKeyStore 生成不可导出的 AES-256-GCM 密钥,账号/密码逐条加密后
 *   以 Base64(iv+密文) 存进独立 SharedPreferences;站点(origin)明文,便于按源匹配
 * - 一条记录 = 站点 + 账号 + 密码;同站点同账号视为同一条(改密码=更新),
 *   同站点不同账号各占一条(多账号)
 * - 导出/导入:明文 JSON(用户自选存放位置,格式见 [exportJson]),导入按
 *   「站点+账号」合并:已有账号只在其密码变化时更新,其余新增
 * - Keystore 密钥不随系统备份走,换机/恢复备份后密文解不开:解密失败的条目在
 *   读取时静默丢弃,数据迁移请走导出/导入
 */
object PasswordStore {

    /** 一条已解密的密码记录 */
    class Entry(
        val id: Long,
        /** 形如 https://host:443 的站点源 */
        val origin: String,
        val username: String,
        val password: String,
        val time: Long
    )

    private const val PREFS = "ldmbrowser_passwords"
    private const val KEY_ENTRIES = "entries"
    private const val KEY_NEXT_ID = "next_id"
    private const val KEY_ASK_SAVE = "ask_save"
    private const val KEY_NEVER_SITES = "never_sites"

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "ldmbrowser_pwd_key"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    private val lock = Any()

    /** 「登录时询问保存密码」总开关(关 = 任何站点都不再弹询问,已存的密码照常自动填充) */
    fun askSaveEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ASK_SAVE, true)

    fun setAskSaveEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ASK_SAVE, enabled).apply()
    }

    /**
     * 按站点的「一律不保存」名单:保存询问弹窗里选「一律不保存」的站点记进这里,
     * 之后这些站点登录不再询问;密码页点站点可移出名单恢复询问。
     */
    fun neverSaveOrigins(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_NEVER_SITES, emptySet()) ?: emptySet()

    fun addNeverSave(context: Context, origin: String) {
        prefs(context).edit()
            .putStringSet(KEY_NEVER_SITES, neverSaveOrigins(context) + origin)
            .apply()
    }

    /** 移出「一律不保存」名单(恢复询问),原本不在名单里返回 false */
    fun removeNeverSave(context: Context, origin: String): Boolean {
        val set = neverSaveOrigins(context)
        if (origin !in set) return false
        prefs(context).edit().putStringSet(KEY_NEVER_SITES, set - origin).apply()
        return true
    }

    /** 全部记录,按添加顺序(最新在前);解密失败的条目丢弃 */
    fun list(context: Context): MutableList<Entry> = synchronized(lock) {
        prefs(context).getString(KEY_ENTRIES, null)
            ?.let { raw -> runCatching { JSONArray(raw) }.getOrNull() }
            ?.let { arr ->
                (0 until arr.length())
                    .mapNotNull { i -> arr.optJSONObject(i)?.let { decode(it) } }
                    .toMutableList()
            }
            ?: mutableListOf()
    }

    /**
     * 新增或更新一条记录:同站点同账号且密码相同则不动(返回 null,无需询问保存);
     * 密码变化则更新并返回(是否本来就存在,记录本身);全新账号则插入最前。
     */
    fun addOrUpdate(
        context: Context,
        origin: String,
        username: String,
        password: String
    ): Pair<Boolean, Entry>? = synchronized(lock) {
        val prefs = prefs(context)
        val records = rawRecords(prefs)
        val existing = records.indexOfFirst { it.origin == origin && dec(it.uEnc) == username }
        if (existing >= 0) {
            val old = records[existing]
            if (dec(old.pEnc) == password) return@synchronized null
            val entry = Entry(old.id, origin, username, password, System.currentTimeMillis())
            records.removeAt(existing)
            records.add(0, toRecord(entry))
            writeRecords(prefs, records)
            return@synchronized false to entry
        }
        val entry = Entry(nextId(prefs), origin, username, password, System.currentTimeMillis())
        records.add(0, toRecord(entry))
        writeRecords(prefs, records)
        true to entry
    }

    fun delete(context: Context, id: Long): Boolean = synchronized(lock) {
        val prefs = prefs(context)
        val records = rawRecords(prefs)
        val removed = records.removeAll { it.id == id }
        if (removed) writeRecords(prefs, records)
        removed
    }

    /** 删除某站点的全部记录(「清除数据 → 仅当前网站」用),返回是否删了东西 */
    fun deleteForOrigin(context: Context, origin: String): Boolean = synchronized(lock) {
        val prefs = prefs(context)
        val records = rawRecords(prefs)
        val removed = records.removeAll { it.origin == origin }
        if (removed) writeRecords(prefs, records)
        removed
    }

    fun clearAll(context: Context) {
        synchronized(lock) {
            prefs(context).edit().remove(KEY_ENTRIES).apply()
        }
    }

    /** 该站点的全部记录(最新在前):单条时自动填充用它,多条时多账号选择条用它 */
    fun forOrigin(context: Context, origin: String): List<Entry> =
        list(context).filter { it.origin == origin }

    // ---------- 导出 / 导入(明文 JSON) ----------

    /**
     * 导出内容:
     * {"app":"ldmbrowser","type":"passwords","version":1,"time":...,
     *  "items":[{"origin":"https://example.com","username":"...","password":"...","time":...}]}
     */
    fun exportJson(context: Context): String {
        val items = JSONArray()
        list(context).forEach { e ->
            items.put(
                JSONObject()
                    .put("origin", e.origin)
                    .put("username", e.username)
                    .put("password", e.password)
                    .put("time", e.time)
            )
        }
        return JSONObject()
            .put("app", "ldmbrowser")
            .put("type", "passwords")
            .put("version", 1)
            .put("time", System.currentTimeMillis())
            .put("items", items)
            .toString(2)
    }

    /**
     * 导入并合并,返回 [新增条数, 更新条数];整体格式不对返回 null。
     * 单条坏记录跳过不打断;密码为空或站点不是 http(s) 的条目忽略。
     */
    fun importJson(context: Context, text: String): IntArray? {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val items = root.optJSONArray("items") ?: return null
        var added = 0
        var updated = 0
        synchronized(lock) {
            val prefs = prefs(context)
            val records = rawRecords(prefs)
            val now = System.currentTimeMillis()
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                val origin = originOf(o.optString("origin")) ?: continue
                val username = o.optString("username")
                val password = o.optString("password")
                if (password.isEmpty()) continue
                val time = o.optLong("time", 0L).takeIf { it > 0 } ?: now
                val existing = records.indexOfFirst { it.origin == origin && dec(it.uEnc) == username }
                if (existing >= 0) {
                    val old = records[existing]
                    if (dec(old.pEnc) == password) continue
                    records[existing] = RawRecord(old.id, origin, enc(username), enc(password), time)
                    updated++
                } else {
                    records.add(RawRecord(nextId(prefs), origin, enc(username), enc(password), time))
                    added++
                }
            }
            if (added > 0 || updated > 0) writeRecords(prefs, records)
        }
        return intArrayOf(added, updated)
    }

    /** 任意网址/源 → https://host:port 形式的源(仅 http/https),解析不出返回 null */
    fun originOf(url: String): String? {
        if (url.isBlank()) return null
        return try {
            val uri = android.net.Uri.parse(url)
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            val host = uri.host?.takeIf { it.isNotEmpty() } ?: return null
            val port = if (uri.port == -1) if (scheme == "https") 443 else 80 else uri.port
            "$scheme://$host:$port"
        } catch (_: Exception) {
            null
        }
    }

    // ---------- 内部:原始记录(JSONArray,账号/密码为密文) ----------

    private class RawRecord(
        val id: Long,
        val origin: String,
        val uEnc: String,
        val pEnc: String,
        val time: Long
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun rawRecords(prefs: android.content.SharedPreferences): MutableList<RawRecord> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return mutableListOf()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return mutableListOf()
        val out = mutableListOf<RawRecord>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                RawRecord(
                    o.optLong("id"),
                    o.optString("o"),
                    o.optString("u"),
                    o.optString("p"),
                    o.optLong("t")
                )
            )
        }
        return out
    }

    private fun writeRecords(prefs: android.content.SharedPreferences, records: List<RawRecord>) {
        val arr = JSONArray()
        records.forEach { r ->
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("o", r.origin)
                    .put("u", r.uEnc)
                    .put("p", r.pEnc)
                    .put("t", r.time)
            )
        }
        prefs.edit().putString(KEY_ENTRIES, arr.toString()).apply()
    }

    private fun nextId(prefs: android.content.SharedPreferences): Long {
        val id = prefs.getLong(KEY_NEXT_ID, 1L)
        prefs.edit().putLong(KEY_NEXT_ID, id + 1).apply()
        return id
    }

    private fun decode(o: JSONObject): Entry? {
        val origin = originOf(o.optString("o")) ?: return null
        val u = dec(o.optString("u")) ?: return null
        val p = dec(o.optString("p")) ?: return null
        return Entry(o.optLong("id"), origin, u, p, o.optLong("t"))
    }

    private fun toRecord(e: Entry): RawRecord =
        RawRecord(e.id, e.origin, enc(e.username), enc(e.password), e.time)

    // ---------- 加密:AndroidKeyStore AES-256-GCM ----------

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE)
        ks.load(null)
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun enc(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + data, Base64.NO_WRAP)
    }

    private fun dec(blob: String): String? = runCatching {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        if (raw.size <= GCM_IV_BYTES) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(GCM_TAG_BITS, raw, 0, GCM_IV_BYTES)
        )
        String(
            cipher.doFinal(raw, GCM_IV_BYTES, raw.size - GCM_IV_BYTES),
            Charsets.UTF_8
        )
    }.getOrNull()
}
