package cz.flos.pos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import cz.flos.pos.ui.FlosScreen
import cz.flos.pos.ui.PosViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as FlosApp).container
        setContent {
            val vm = viewModel { PosViewModel(container) }
            FlosScreen(vm)
        }
    }
}
