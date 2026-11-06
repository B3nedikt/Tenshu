package app.tenshu.photos.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.tenshu.photos.ui.App
import app.tenshu.ui.theme.TenshuTheme

// Photos Android entry point (`docs/proposals/photos-app.md` PH-1). ComponentActivity provides the
// ViewModelStore / SavedState / Lifecycle owners for free, so the shell is TenshuTheme { App() }.
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
