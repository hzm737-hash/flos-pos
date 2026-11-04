package cz.flos.desktop

import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cz.flos.pos.FlosContainer
import cz.flos.pos.data.FlosDatabase
import cz.flos.pos.data.FlosDatabase_Impl
import cz.flos.pos.ui.CustomerDisplay
import cz.flos.pos.ui.FlosScreen
import cz.flos.pos.ui.PosViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** Where Flos keeps its data on Windows: %APPDATA%\Flos (or ~/.flos elsewhere). */
fun dataDir(): File {
    val appData = System.getenv("APPDATA")
    val dir = if (appData != null) File(appData, "Flos") else File(System.getProperty("user.home"), ".flos")
    return dir.apply { mkdirs() }
}

fun main() {
    val dir = dataDir()
    val database = Room.databaseBuilder<FlosDatabase>(name = File(dir, FlosDatabase.FILE_NAME).absolutePath, factory = { FlosDatabase_Impl() })
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .addMigrations(*FlosDatabase.MIGRATIONS)
        .build()
    val platform = DesktopPlatform(dir)
    val container = FlosContainer(platform, database, RegistryStore())

    // Resends sales that are still waiting for EET confirmation: shortly after a failure, and every 5 minutes.
    val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    background.launch {
        while (true) {
            platform.retrySignal.tryReceive()
            if (container.settings.state.value.eetEnabled) runCatching { container.eet.sendPending() }
            kotlinx.coroutines.withTimeoutOrNull(5 * 60_000L) { platform.retrySignal.receive(); delay(30_000) }
        }
    }

    application {
        val vm = remember { PosViewModel(container) }
        // The lotus mark in the title bar and the taskbar.
        val icon = remember {
            Thread.currentThread().contextClassLoader.getResourceAsStream("flos.png")?.use { stream ->
                BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(stream.readBytes()).toComposeImageBitmap())
            }
        }
        Window(
            onCloseRequest = ::exitApplication,
            title = "Flos",
            icon = icon,
            state = rememberWindowState(width = 1280.dp, height = 820.dp),
        ) {
            FlosScreen(vm)
        }
        // Customer display: a second window to drag onto the monitor that faces the customer.
        val settings by vm.settings.collectAsState()
        if (settings.customerDisplay) {
            Window(
                onCloseRequest = { vm.updateSettings { it.copy(customerDisplay = false) } },
                title = "Flos – zákazník",
                icon = icon,
                state = rememberWindowState(width = 1024.dp, height = 600.dp),
            ) {
                CustomerDisplay(vm)
            }
        }
    }
}
