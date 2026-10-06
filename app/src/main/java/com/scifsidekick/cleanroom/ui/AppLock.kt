package com.scifsidekick.cleanroom.ui

import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Gates [content] behind a biometric/device-credential check when [enabled]. The unlocked flag
 * is `rememberSaveable`, so it survives rotation and an OS-restored recent task, but a genuinely
 * fresh process start always begins locked -- this is a reasonable, disclosed middle ground
 * between "never re-prompt" and re-authenticating on every configuration change. Falls back to
 * showing [content] directly (with a one-time toast) if the device has no usable biometric or
 * device-credential enrollment, rather than locking the user out of their own app.
 */
@Composable
fun AppLockGate(
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var unlocked by rememberSaveable { mutableStateOf(!enabled) }
    // Without this, turning the setting on from *inside* an already-unlocked session would
    // silently do nothing until the next cold start -- `unlocked`'s initial value only reflects
    // whatever `enabled` was the first time this composable entered composition. Locking the
    // instant the setting is turned on is what a security toggle should actually do.
    LaunchedEffect(enabled) {
        if (enabled) unlocked = false
    }
    var checkedAvailability by remember { mutableStateOf(false) }

    if (!enabled) {
        content()
        return
    }
    if (unlocked) {
        content()
        return
    }

    val activity = context as? FragmentActivity
    LaunchedEffect(Unit) {
        if (activity == null) {
            unlocked = true
            return@LaunchedEffect
        }
        val manager = BiometricManager.from(activity)
        val canAuthenticate =
            manager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            if (!checkedAvailability) {
                Toast
                    .makeText(
                        context,
                        "App lock is on, but no screen lock or biometric is set up on this device -- skipping the lock screen",
                        Toast.LENGTH_LONG,
                    ).show()
            }
            checkedAvailability = true
            unlocked = true
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("SCIF Sidekick is locked", style = MaterialTheme.typography.titleLarge)
            androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 8.dp))
            Text("Unlock to view forwarded messages and settings.", style = MaterialTheme.typography.bodyMedium)
            androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 24.dp))
            Button(onClick = { activity?.let { promptUnlock(it) { unlocked = true } } }) {
                Text("Unlock")
            }
        }
    }
}

private fun promptUnlock(
    activity: FragmentActivity,
    onSuccess: () -> Unit,
) {
    val executor = ContextCompat.getMainExecutor(activity)
    val prompt =
        BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence,
                ) {
                    // Left locked; the user can tap Unlock again. A transient scanner error or a
                    // deliberate cancel should never silently open the app.
                }
            },
        )
    val info =
        BiometricPrompt.PromptInfo
            .Builder()
            .setTitle("Unlock SCIF Sidekick")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            ).build()
    prompt.authenticate(info)
}
