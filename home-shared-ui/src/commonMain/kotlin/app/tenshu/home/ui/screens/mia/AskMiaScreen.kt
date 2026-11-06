package app.tenshu.home.ui.screens.mia

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tenshu.home.shared.resources.Res
import app.tenshu.home.shared.resources.home_ask_mia_back
import app.tenshu.home.shared.resources.home_ask_mia_title
import app.tenshu.mia.ui.screens.chat.ChatScreen
import app.tenshu.mia.ui.screens.chat.ChatViewModel
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Home's embedded assistant (docs/proposals/home-and-mia-apps.md D1): a back header over
 * the SAME chat surface the standalone Mia app ships — `ChatScreen` from :mia-shared-ui,
 * resolved through the same Koin bindings (miaModules rides in homeModules).
 */
@Composable
fun AskMiaScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(Res.string.home_ask_mia_back),
                )
            }
            Text(
                stringResource(Res.string.home_ask_mia_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
        }
        ChatScreen(viewModel = koinViewModel<ChatViewModel>())
    }
}
