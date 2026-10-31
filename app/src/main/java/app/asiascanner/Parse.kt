package app.asiascanner

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Environment
import android.provider.MediaStore
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlin.math.hypot

sealed class Res {
    data class Ok(val price: Double, val appPrice: Double?) : Res()
    data class Fail(val msg: String) : Res()
}

object Qr {
    fun decode(bytes: ByteArray): String? {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        val hints = mapOf(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.POSSIBLE_FORMATS to listOf(
                BarcodeFormat.QR_CODE, BarcodeFormat.EAN_13, BarcodeFormat.EAN_8,
                BarcodeFormat.CODE_128, BarcodeFormat.DATA_MATRIX
            )
        )
        return try {
            MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, h, px))), hints).text
        } catch (e: Exception) {
            null
        }
    }
}

object Util {
    fun saveToDownloads(ctx: Context, name: String, bytes: ByteArray): Boolean {
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return false
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        return true
    }
}

/** Разбор текста карточки товара (тексты берутся из дерева доступности). */
object Parse {
    private val numRe = Regex(
        "^(\\d[\\d\\s\\u00a0]*(?:[.,]\\d{1,2})?)\\s*(?:сомов|сом|kgs|руб|тг|₸|₽|с|c|р|т)?\\.?$",
        RegexOption.IGNORE_CASE
    )

    fun norm(s: String): String =
        s.lowercase().replace(' ', ' ').replace(Regex("\\s+"), " ").trim()

    private fun toNum(s: String): Double? =
        s.replace(Regex("[\\s\\u00a0]"), "").replace(',', '.').toDoubleOrNull()

    /** Число, если текст — это ровно цена (например «252,99», «252,99 с»). «A+» и «110 г» не проходят. */
    fun number(text: String): Double? =
        numRe.find(text.trim())?.groupValues?.get(1)?.let { toNum(it) }?.takeIf { it > 0 && it < 1_000_000 }

    fun stems(name: String): List<String> =
        Regex("[\\p{L}\\p{N}]+").findAll(name.lowercase()).map { it.value }
            .filter { it.length >= 4 && !it.all { ch -> ch.isDigit() } }
            .map { it.take(5) }.toList()

    private fun dist(a: Rect, b: Rect): Double =
        hypot((a.exactCenterX() - b.exactCenterX()).toDouble(), (a.exactCenterY() - b.exactCenterY()).toDouble())

    private fun inlineNum(n: TNode, label: String): Double? =
        Regex("^$label:?\\s*(.+)$").find(norm(n.text))?.groupValues?.get(1)?.let { number(it) }

    /** null — это вообще не карточка (нет подписей «Цена» / «С приложением»). */
    fun card(nodes: List<TNode>, name: String): Res? {
        val priceLbl = nodes.firstOrNull {
            val t = norm(it.text)
            t == "цена" || t == "цена:" || Regex("^цена:?\\s+\\d").containsMatchIn(t)
        }
        val appLbl = nodes.firstOrNull { norm(it.text).startsWith("с приложением") }
        if (priceLbl == null && appLbl == null) return null

        val res = HashMap<String, Double>()
        val pending = ArrayList<Pair<TNode, String>>()
        if (priceLbl != null) {
            val v = inlineNum(priceLbl, "цена")
            if (v != null) res["p"] = v else pending.add(priceLbl to "p")
        }
        if (appLbl != null) {
            val v = inlineNum(appLbl, "с приложением")
            if (v != null) res["a"] = v else pending.add(appLbl to "a")
        }

        val cands = nodes.mapNotNull { n -> number(n.text)?.let { n to it } }
        val pairs = pending.flatMap { (ln, k) ->
            cands.map { (cn, v) -> Triple(k, cn to v, dist(ln.rect, cn.rect)) }
        }.sortedBy { it.third }
        val used = HashSet<TNode>()
        for ((k, cv, d) in pairs) {
            if (res.containsKey(k) || used.contains(cv.first) || d > 700) continue
            res[k] = cv.second
            used.add(cv.first)
        }

        val joined = nodes.joinToString(" ") { norm(it.text) }
        val st = stems(name)
        if (st.isNotEmpty() && st.count { joined.contains(it) } < minOf(2, st.size)) {
            return Res.Fail("Открылась карточка другого товара")
        }
        val p = res["p"] ?: return Res.Fail("Не удалось прочитать «Цена»")
        val a = res["a"]
        if (a != null && a > p) return Res.Fail("Подозрительно: «С приложением» ($a) больше «Цена» ($p)")
        return Res.Ok(p, a)
    }
}
