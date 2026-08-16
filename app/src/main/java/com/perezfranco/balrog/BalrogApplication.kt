package com.perezfranco.balrog

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.perezfranco.balrog.crypto.VaultKeyManager
import com.perezfranco.balrog.data.VaultDatabase
import com.perezfranco.balrog.data.VaultRepository
import com.perezfranco.balrog.security.SessionManager

class BalrogApplication : Application() {

    val keyManager: VaultKeyManager by lazy { VaultKeyManager(this) }
    val repository: VaultRepository by lazy {
        VaultRepository(VaultDatabase.get(this).vaultDao())
    }

    override fun onCreate() {
        super.onCreate()
        // Auto-lock: drop the vault key when the app has been backgrounded too long.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = SessionManager.onAppForegrounded()
            override fun onStop(owner: LifecycleOwner) = SessionManager.onAppBackgrounded()
        })
    }
}
