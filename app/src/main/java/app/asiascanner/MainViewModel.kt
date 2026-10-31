package app.asiascanner

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDb.get(app).dao()
    private val prefs = app.getSharedPreferences("prefs", 0)
    private val source = File(app.filesDir, "source.xlsx")
    private val qrDir = File(app.filesDir, "qr")

    val items: StateFlow<List<Item>> =
        dao.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val fileName = MutableStateFlow(prefs.getString("file", "") ?: "")
    val useCode = MutableStateFlow(prefs.getBoolean("useCode", false))
    val msg = MutableStateFlow("")
    val busy = MutableStateFlow(false)

    fun setUseCode(v: Boolean) {
        useCode.value = v
        prefs.edit().putBoolean("useCode", v).apply()
    }

    fun exportName(): String =
        "Asia_Prices_" + SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(Date()) + ".xlsx"

    fun import(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            busy.value = true
            msg.value = "Читаю Excel…"
            try {
                val ctx = getApplication<Application>()
                var name = "file.xlsx"
                ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (i >= 0) name = c.getString(i)
                    }
                }
                ctx.contentResolver.openInputStream(uri)?.use { inp ->
                    source.outputStream().use { inp.copyTo(it) }
                } ?: error("Не удалось открыть файл")

                val rows = Xlsx.read(source)
                qrDir.deleteRecursively()
                qrDir.mkdirs()
                File(ctx.filesDir, "errors.log").delete()

                val list = rows.map { r ->
                    val path = r.image?.let { b ->
                        File(qrDir, "${r.row}.img").also { it.writeBytes(b) }.path
                    }
                    val txt = r.image?.let { Qr.decode(it) }
                    Item(
                        row = r.row,
                        name = r.name.ifBlank { "Строка ${r.row}" },
                        qrPath = path,
                        qrText = txt,
                        status = if (path == null) Status.SKIPPED else Status.PENDING,
                        price = null, appPrice = null, error = null
                    )
                }
                dao.clear()
                dao.insertAll(list)
                prefs.edit().putString("file", name).apply()
                fileName.value = name
                val withQr = list.count { it.qrPath != null }
                msg.value = if (withQr == 0) {
                    "QR-картинки в файле не найдены. Части файла: ${Xlsx.listParts(source)}"
                } else {
                    "Загружено: ${list.size} строк, QR: $withQr"
                }
            } catch (e: Exception) {
                msg.value = "Ошибка чтения Excel: ${e.message}"
            } finally {
                busy.value = false
            }
        }
    }

    fun retryErrors() {
        viewModelScope.launch(Dispatchers.IO) { dao.resetErrors() }
    }

    fun export(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            busy.value = true
            try {
                if (!source.exists()) error("Сначала загрузите Excel")
                val results = dao.all()
                    .filter { it.status == Status.DONE }
                    .associate { it.row to (it.price to it.appPrice) }
                val ctx = getApplication<Application>()
                ctx.contentResolver.openOutputStream(uri)?.use { Xlsx.export(source, results, it) }
                    ?: error("Не удалось создать файл")
                msg.value = "Файл сохранён (цен записано: ${results.size})"
            } catch (e: Exception) {
                msg.value = "Ошибка экспорта: ${e.message}"
            } finally {
                busy.value = false
            }
        }
    }
}
