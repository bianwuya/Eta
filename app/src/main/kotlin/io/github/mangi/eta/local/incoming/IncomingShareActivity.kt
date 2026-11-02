package io.github.mangi.eta.local.incoming

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.MainActivity
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Keep the sending activity's temporary URI grant alive until every file has been copied. */
class IncomingShareActivity : ComponentActivity() {
    private val model by viewModels<IncomingShareViewModel>()
    private var requestId = ""
    private var dispatched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState?.getBoolean("dispatched") == true) {
            finish()
            return
        }
        requestId = savedInstanceState?.getString("request_id") ?: UUID.randomUUID().toString()
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(ProgressBar(this@IncomingShareActivity))
            addView(TextView(this@IncomingShareActivity).apply {
                setText(R.string.local_incoming_importing)
                gravity = Gravity.CENTER
                setPadding(24, 24, 24, 24)
            })
        })
        model.accept(intent, requestId)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val success = model.result.filterNotNull().first()
                if (!dispatched) {
                    dispatched = true
                    if (success) {
                        startActivity(Intent(this@IncomingShareActivity, MainActivity::class.java).apply {
                            action = Intent.ACTION_MAIN
                            addCategory(Intent.CATEGORY_LAUNCHER)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        })
                    } else {
                        Toast.makeText(this@IncomingShareActivity, R.string.local_incoming_unreadable, Toast.LENGTH_LONG).show()
                    }
                    finish()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("request_id", requestId)
        outState.putBoolean("dispatched", dispatched)
        super.onSaveInstanceState(outState)
    }
}

internal class IncomingShareViewModel(application: Application) : AndroidViewModel(application) {
    val result = MutableStateFlow<Boolean?>(null)
    private var started = false

    fun accept(intent: Intent, id: String) {
        if (started) return
        started = true
        viewModelScope.launch {
            result.value = try {
                val payload = IncomingContentParser.fromIntent(intent)
                payload != null && withTimeout(60_000) {
                    IncomingContentInbox.import(getApplication(), payload, id) != null
                }
            } catch (cancel: CancellationException) {
                if (cancel is kotlinx.coroutines.TimeoutCancellationException) false else throw cancel
            } catch (_: Exception) {
                false
            }
        }
    }
}
