package com.cybersensei.academy.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.cybersensei.academy.core.ui.component.ProfessorBubble
import com.cybersensei.academy.core.ui.component.SenseiPrimaryButton
import com.cybersensei.academy.core.ui.component.SenseiTextButton
import com.cybersensei.academy.core.ui.component.TerminalBlock

/**
 * What the app shows after it has died: the reason, in full, on the screen.
 *
 * It is also the first real lesson of the course, delivered by example — when something
 * fails, you read the log. Hiding the error would be the opposite of what this school
 * teaches.
 */
@Composable
fun DiagnosticsScreen(
    trace: String,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            text = "Diagnostica",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )

        ProfessorBubble(
            text = "L'ultima volta l'app si è chiusa da sola, e questo è il motivo esatto. " +
                "Copialo e mandalo a chi la sta costruendo: un errore letto è un errore " +
                "già mezzo risolto.",
            animate = false,
        )

        Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            TerminalBlock(text = trace)
        }

        SenseiPrimaryButton(
            text = "Copia l'errore",
            onClick = { clipboard.setText(AnnotatedString(trace)) },
            modifier = Modifier.fillMaxWidth(),
        )
        SenseiTextButton(text = "Prova ad aprire lo stesso", onClick = onDismiss)
    }
}
