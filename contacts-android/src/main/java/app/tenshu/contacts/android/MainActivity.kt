package app.tenshu.contacts.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.tenshu.contacts.ui.App
import app.tenshu.ui.theme.TenshuTheme

// Contacts Android entry point (docs/proposals/contacts-app.md §6 Ph0). ComponentActivity
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
