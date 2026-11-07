package cz.flos.pos

import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import cz.flos.pos.ui.CustomerDisplay
import cz.flos.pos.ui.FlosScreen
import cz.flos.pos.ui.PosViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var vm: PosViewModel
    private var presentation: Presentation? = null
    private var displayWanted = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = updateCustomerDisplay()
        override fun onDisplayRemoved(displayId: Int) = updateCustomerDisplay()
        override fun onDisplayChanged(displayId: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as FlosApp).container
        vm = ViewModelProvider(this, viewModelFactory { initializer { PosViewModel(container) } })[PosViewModel::class.java]
        setContent { FlosScreen(vm) }

        // Customer display on a second screen (a till with a customer-facing display, or a monitor over HDMI).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.settings.map { it.customerDisplay }.distinctUntilChanged().collect { on ->
                    displayWanted = on
                    updateCustomerDisplay()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Google Play's donation payment sheet opens on top of this screen.
        ((application as FlosApp).container.platform as? AndroidPlatform)?.activity = java.lang.ref.WeakReference(this)
    }

    override fun onStart() {
        super.onStart()
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).registerDisplayListener(displayListener, null)
    }

    override fun onStop() {
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(displayListener)
        presentation?.dismiss()
        presentation = null
        super.onStop()
    }

    private fun updateCustomerDisplay() {
        val target: Display? = if (displayWanted) {
            (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull()
        } else null
        val current = presentation
        if (current != null && current.display.displayId == target?.displayId) return
        current?.dismiss()
        presentation = null
        if (target == null) return
        val view = ComposeView(this).apply {
            // The presentation has its own window, so the Compose owners are taken from this activity.
            setViewTreeLifecycleOwner(this@MainActivity)
            setViewTreeViewModelStoreOwner(this@MainActivity)
            setViewTreeSavedStateRegistryOwner(this@MainActivity)
            setContent { CustomerDisplay(vm) }
        }
        val p = Presentation(this, target)
        p.setContentView(view)
        p.setOnDismissListener { if (presentation === p) presentation = null }
        presentation = p
        runCatching { p.show() }.onFailure { presentation = null }
    }
}
