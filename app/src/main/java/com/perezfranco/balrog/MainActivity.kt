package com.perezfranco.balrog

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import com.perezfranco.balrog.ui.BalrogApp
import com.perezfranco.balrog.ui.theme.BalrogTheme

// FragmentActivity (not ComponentActivity) because BiometricPrompt requires it.
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Block screenshots and hide content in the recent-apps switcher.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        setContent {
            BalrogTheme {
                BalrogApp(activity = this)
            }
        }
    }
}
