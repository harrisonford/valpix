package com.franky.valpix

import android.app.Application
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.franky.valpix.core.LinkSource
import com.franky.valpix.ui.Root

class ValpixApp : Application() {
    /** One model for the life of the process; made on first use, after any debug extras are read. */
    val model: AppModel by lazy { AppModel(this) }
}

class MainActivity : FragmentActivity() {
    private val model: AppModel get() = (application as ValpixApp).model

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        AppConfig.readDebugLaunch(this)
        SelfTest.runIfAsked()
        setContent { Root(model) }
        if (savedInstanceState == null) openLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (AppConfig.readDebugLaunch(this)) {
            model.debugRelaunched()
            SelfTest.runIfAsked()
        }
        openLink(intent)
    }

    override fun onStart() {
        super.onStart()
        model.authenticate = { reason -> Authenticator.confirm(this, reason) }
        model.foreground()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            model.background()
            // Debug settings are for one run on screen; they do not linger.
            if (AppConfig.clearDebug()) model.debugRelaunched()
        }
    }

    /** A valpix: link tapped in another app. */
    private fun openLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        // Reopened from the recent-apps list: the system hands back the link
        // that first started the app. It was dealt with then; do not offer
        // the same payment again.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        intent.dataString?.let { model.open(it, LinkSource.EXTERNAL, whenReady = true) }
    }
}
