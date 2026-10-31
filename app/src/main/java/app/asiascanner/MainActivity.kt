package app.asiascanner

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme {
                Surface { Screen(vm) }
            }
        }
    }
}

@Composable
fun Screen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val items by vm.items.collectAsState()
    val file by vm.fileName.collectAsState()
    val useCode by vm.useCode.collectAsState()
    val msg by vm.msg.collectAsState()
    val busy by vm.busy.collectAsState()
    val ui by Engine.ui.collectAsState()

    var enabled by remember { mutableStateOf(false) }
    var local by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            enabled = AsiaService.instance != null
            delay(1000)
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.import(uri)
    }
    val save = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri -> if (uri != null) vm.export(uri) }

    val withQr = items.count { it.qrPath != null }
    val done = items.count { it.status == Status.DONE }
    val err = items.count { it.status == Status.ERROR }
    val skipped = items.count { it.status == Status.SKIPPED }
    val processed = done + err
    val progress = if (withQr == 0) 0f else processed.toFloat() / withQr

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Asia Price Scanner", style = MaterialTheme.typography.headlineMedium)

        Button(
            onClick = {
                pick.launch(
                    arrayOf(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "application/octet-stream"
                    )
                )
            },
            enabled = !busy && !ui.running,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Выбрать Excel") }

        Text("Файл: ${file.ifBlank { "не выбран" }}")
        Text("Товаров: ${items.size}   QR-кодов: $withQr")

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = useCode, onCheckedChange = { vm.setUseCode(it) })
            Text("  Искать по коду из QR (иначе — по названию)")
        }

        if (!enabled) {
            Text("Служба доступности выключена.", color = MaterialTheme.colorScheme.error)
            OutlinedButton(
                onClick = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Открыть настройки доступности") }
        } else {
            Text("Служба доступности: включена")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val i = ctx.packageManager.getLaunchIntentForPackage(AsiaService.PKG)
                    if (i == null) {
                        local = "Приложение «Азия Онлайн» не установлено"
                    } else {
                        val e = Engine.start(ctx)
                        if (e != null) local = e else {
                            local = ""
                            ctx.startActivity(i)
                        }
                    }
                },
                enabled = withQr > 0 && !ui.running && !busy
            ) { Text("Начать") }

            if (ui.paused) {
                Button(onClick = { Engine.resume() }, enabled = ui.running) { Text("Продолжить") }
            } else {
                OutlinedButton(onClick = { Engine.pause() }, enabled = ui.running) { Text("Пауза") }
            }
            OutlinedButton(onClick = { Engine.stopRun() }, enabled = ui.running) { Text("Стоп") }
        }

        Text("Обработка товаров", style = MaterialTheme.typography.titleMedium)
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        Text("Обработано: $processed / $withQr")
        Text("Успешно: $done")
        Text("Ошибок: $err")
        Text("Пропущено (нет QR): $skipped")
        if (ui.current.isNotEmpty() && ui.running) Text("Текущий товар: ${ui.current}")

        listOf(local, ui.message, msg).filter { it.isNotBlank() }.forEach {
            Text(it, color = MaterialTheme.colorScheme.primary)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.retryErrors() }, enabled = err > 0 && !ui.running) {
                Text("Повторить ошибки")
            }
            Button(
                onClick = { save.launch(vm.exportName()) },
                enabled = done > 0 && !busy && !ui.running
            ) { Text("Скачать готовый Excel") }
        }

        Text("Диагностика", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(
            onClick = { Engine.dumpLater(ctx)?.let { local = it } },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Снять дамп экрана «Азии» (через 8 сек)") }
        OutlinedButton(
            onClick = {
                val f = File(ctx.filesDir, "errors.log")
                local = if (f.exists() && Util.saveToDownloads(ctx, "asia_errors.log", f.readBytes()))
                    "Лог сохранён: Загрузки/asia_errors.log" else "Лога ошибок пока нет"
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Сохранить лог ошибок") }

        val errs = items.filter { it.status == Status.ERROR }.take(30)
        if (errs.isNotEmpty()) {
            Text("Ошибки:", style = MaterialTheme.typography.titleSmall)
            errs.forEach { Text("стр. ${it.row}: ${it.name} — ${it.error}") }
        }
    }
}
