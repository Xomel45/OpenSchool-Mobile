package io.github.xomel45.openschool

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status

/** The one-time code inside an SMS text: the first run of 4 to 8 digits that is not part of a longer number. */
fun extractCode(sms: String): String? = Regex("(?<!\\d)\\d{4,8}(?!\\d)").find(sms)?.value

/**
 * While this is in the composition, waits for an SMS with a code (Google's SMS User Consent API): the system shows its own
 * "allow this app to read this message?" window, and only that one message is handed over. No SMS permission is needed.
 * Without Google Play services nothing happens and the user types the code.
 */
@Composable
fun SmsCodeEffect(onCode: (String) -> Unit) {
    val context = LocalContext.current
    val current by rememberUpdatedState(onCode)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra(SmsRetriever.EXTRA_SMS_MESSAGE)?.let(::extractCode)?.let { current(it) }
        }
    }
    val launch by rememberUpdatedState { intent: Intent -> launcher.launch(intent) }
    DisposableEffect(context) {
        val client = SmsRetriever.getClient(context)
        fun listen() { client.startSmsUserConsent(null) } // lives for 5 minutes or until one message arrives
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != SmsRetriever.SMS_RETRIEVED_ACTION) return
                val status = intent.extras?.get(SmsRetriever.EXTRA_STATUS) as? Status ?: return
                when (status.statusCode) {
                    CommonStatusCodes.SUCCESS -> {
                        @Suppress("DEPRECATION")
                        val consent = intent.extras?.getParcelable<Intent>(SmsRetriever.EXTRA_CONSENT_INTENT)
                        if (consent != null) launch(consent)
                        listen() // the next message (a resent code) is waited for as well
                    }
                    CommonStatusCodes.TIMEOUT -> listen()
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION), SmsRetriever.SEND_PERMISSION, null, ContextCompat.RECEIVER_EXPORTED)
        listen()
        onDispose { context.unregisterReceiver(receiver) }
    }
}
