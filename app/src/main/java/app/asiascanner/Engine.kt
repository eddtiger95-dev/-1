package app.asiascanner

import android.content.Context
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RunUi(
    val running: Boolean = false,
    val paused: Boolean = false,
    val current: String = "",
    val message: String = ""
)

object Engine {
    val ui = MutableStateFlow(RunUi())

    @Volatile
    private var paused = false

    @Volatile
    private var stop = false
    private var job: Job? = null

    /** Возвращает текст ошибки или null, если запуск прошёл. */
    fun start(ctx: Context): String? {
        val svc = AsiaService.instance
            ?: return "Служба доступности выключена. Включите «Asia Price Scanner» в Специальных возможностях."
        if (job?.isActive == true) {
            paused = false
            ui.update { it.copy(paused = false) }
            return null
        }
        stop = false
        paused = false
        job = svc.scope.launch { loop(svc) }
        return null
    }

    fun pause() {
        paused = true
        ui.update { it.copy(paused = true) }
    }

    fun resume() {
        paused = false
        ui.update { it.copy(paused = false) }
    }

    fun stopRun() {
        stop = true
        paused = false
    }

    fun dumpLater(ctx: Context): String? {
        val svc = AsiaService.instance ?: return "Служба доступности выключена"
        val app = ctx.applicationContext
        svc.scope.launch {
            ui.update { it.copy(message = "Через 8 секунд снимаю экран. Откройте «Азию» на нужном экране.") }
            delay(8000)
            val s = svc.dumpScreen()
            val ok = Util.saveToDownloads(app, "asia_dump.txt", s.toByteArray())
            ui.update {
                it.copy(message = if (ok) "Дамп сохранён: Загрузки/asia_dump.txt" else "Не удалось сохранить дамп")
            }
            svc.bringUsToFront()
        }
        return null
    }

    private suspend fun loop(svc: AsiaService) {
        val dao = AppDb.get(svc).dao()
        val useCode = svc.getSharedPreferences("prefs", 0).getBoolean("useCode", false)
        ui.update { RunUi(running = true, message = "Запуск…") }
        delay(2000)
        var finishMsg = "Готово"
        try {
            while (!stop) {
                if (paused) {
                    delay(500)
                    continue
                }
                val item = dao.nextPending() ?: break
                ui.update { it.copy(current = item.name, message = "") }
                var res: Res = Res.Fail("")
                for (attempt in 1..2) {
                    res = svc.process(item, useCode)
                    if (res is Res.Ok || stop) break
                    delay(1500)
                }
                if (stop && res !is Res.Ok) break
                when (val r = res) {
                    is Res.Ok -> dao.update(
                        item.copy(status = Status.DONE, price = r.price, appPrice = r.appPrice, error = null)
                    )
                    is Res.Fail -> {
                        dao.update(item.copy(status = Status.ERROR, error = r.msg))
                        svc.logError(item, r.msg)
                    }
                }
            }
            if (stop) finishMsg = "Остановлено"
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            finishMsg = "Ошибка: ${e.message}"
        } finally {
            ui.update { RunUi(message = finishMsg) }
            svc.bringUsToFront()
        }
    }
}
