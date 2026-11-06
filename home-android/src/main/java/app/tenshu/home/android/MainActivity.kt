package app.tenshu.home.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.tenshu.home.ui.App
import app.tenshu.ui.theme.TenshuTheme

// Home Android entry point (docs/proposals/home-and-mia-apps.md §6, M1). ComponentActivity
// provides ViewModelStore / SavedState / Lifecycle owners for free, so the shell is just
// TenshuTheme { App() }.
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TenshuTheme {
                App()
            }
        }
    }
}
