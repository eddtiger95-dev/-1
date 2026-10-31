package app.asiascanner

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay

class TNode(val text: String, val rect: Rect, val cls: String, val node: AccessibilityNodeInfo)

/**
 * Управляет приложением «Азия Онлайн» через службу доступности.
 * Режим работы: поиск товара по названию (или по коду из QR) в строке поиска «Азии»,
 * открытие карточки и чтение цен из текстов экрана.
 */
class AsiaService : AccessibilityService() {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var dumps = 0

    companion object {
        const val PKG = "com.astor.loyalty.mobile.frunze"

        @Volatile
        var instance: AsiaService? = null
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    // ---------- вспомогательное ----------

    private fun asiaRoot(): AccessibilityNodeInfo? {
        val r = rootInActiveWindow
        if (r != null && r.packageName?.toString() == PKG) return r
        return try {
            windows.firstNotNullOfOrNull { w -> w.root?.takeIf { it.packageName?.toString() == PKG } }
        } catch (e: Exception) {
            null
        }
    }

    private fun collect(root: AccessibilityNodeInfo?): List<TNode> {
        val out = ArrayList<TNode>()
        fun walk(n: AccessibilityNodeInfo, d: Int) {
            if (d > 45) return
            val t = (n.text?.toString()?.takeIf { it.isNotBlank() } ?: n.contentDescription?.toString() ?: "").trim()
            val cls = n.className?.toString() ?: ""
            if (t.isNotEmpty() || cls.contains("EditText")) {
                val r = Rect()
                n.getBoundsInScreen(r)
                out.add(TNode(t, r, cls, n))
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, d + 1) }
        }
        if (root != null) walk(root, 0)
        return out
    }

    private suspend fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            delay(300)
        }
        return cond()
    }

    private fun launchAsia(): Boolean {
        val i = packageManager.getLaunchIntentForPackage(PKG) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun bringUsToFront() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        } catch (_: Exception) {
        }
    }

    private fun tapAt(x: Float, y: Float) {
        val p = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build()
        dispatchGesture(g, null, null)
    }

    private fun click(n: AccessibilityNodeInfo) {
        var c: AccessibilityNodeInfo? = n
        var d = 0
        while (c != null && d < 6) {
            if (c.isClickable && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            c = c.parent
            d++
        }
        val r = Rect()
        n.getBoundsInScreen(r)
        tapAt(r.exactCenterX(), r.exactCenterY())
    }

    // ---------- поиск и чтение ----------

    private fun findField(): TNode? = collect(asiaRoot()).firstOrNull { it.cls.contains("EditText") }

    private suspend fun openSearch(): TNode? {
        repeat(4) {
            if (asiaRoot() == null) {
                launchAsia()
                waitFor(15000) { asiaRoot() != null }
                delay(2500)
            }
            findField()?.let { return it }
            val entry = collect(asiaRoot()).firstOrNull {
                !it.cls.contains("EditText") &&
                    (it.text.contains("поиск", true) || it.text.contains("найти", true))
            }
            if (entry != null) {
                click(entry.node)
                delay(1500)
                findField()?.let { return it }
            }
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(1200)
        }
        return null
    }

    private suspend fun waitCard(item: Item, ms: Long): Res? {
        val end = System.currentTimeMillis() + ms
        var last: Res? = null
        while (System.currentTimeMillis() < end) {
            val r = Parse.card(collect(asiaRoot()), item.name)
            if (r is Res.Ok) return r
            if (r != null) last = r
            delay(500)
        }
        return last
    }

    suspend fun process(item: Item, useCode: Boolean): Res {
        try {
            val query = if (useCode && (item.qrText?.length ?: 0) >= 6) item.qrText!! else item.name

            val field = openSearch() ?: return Res.Fail("Не найдено поле поиска в «Азии» (сделайте диагностику экрана)")
            field.node.refresh()
            field.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            field.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
            }
            field.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            delay(600)
            if (Build.VERSION.SDK_INT >= 30) {
                field.node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
            delay(2500)

            // вдруг результат поиска сразу открыл карточку
            val early = waitCard(item, 2500)
            if (early is Res.Ok) return early

            val stems = Parse.stems(item.name)
            val hits = collect(asiaRoot())
                .filter { !it.cls.contains("EditText") && it.text.isNotEmpty() }
                .filter { n ->
                    val t = Parse.norm(n.text)
                    stems.isEmpty() || stems.count { t.contains(it) } >= minOf(2, stems.size)
                }
                .sortedBy { it.rect.top }
            val target = hits.firstOrNull { it.rect.top > field.rect.bottom } ?: hits.firstOrNull()
                ?: return Res.Fail("Товар не найден в результатах поиска")
            click(target.node)

            return waitCard(item, 9000) ?: Res.Fail("Карточка товара не открылась")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Res.Fail(e.message ?: e.javaClass.simpleName)
        }
    }

    // ---------- диагностика ----------

    fun dumpScreen(): String {
        val r = asiaRoot() ?: rootInActiveWindow ?: return "(нет активного окна)"
        val sb = StringBuilder("package=${r.packageName}\n")
        fun walk(n: AccessibilityNodeInfo, d: Int) {
            if (d > 45) return
            val rect = Rect()
            n.getBoundsInScreen(rect)
            sb.append("  ".repeat(d)).append(n.className)
                .append(" text=").append(n.text)
                .append(" desc=").append(n.contentDescription)
                .append(" id=").append(n.viewIdResourceName)
                .append(" click=").append(n.isClickable)
                .append(" ").append(rect.flattenToString()).append('\n')
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, d + 1) }
        }
        walk(r, 0)
        return sb.toString()
    }

    fun logError(item: Item, msg: String) {
        val f = File(filesDir, "errors.log")
        val sb = StringBuilder("== строка ${item.row} «${item.name}»: $msg\n")
        if (dumps < 5) {
            dumps++
            sb.append(dumpScreen()).append('\n')
        }
        f.appendText(sb.toString())
    }
}
