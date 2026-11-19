package com.fintrack.idr.util

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.hardware.fingerprint.FingerprintManager
import android.os.Build
import android.os.CancellationSignal
import android.provider.Settings
import android.widget.Toast
import com.fintrack.idr.data.Repo

/** In-app language: applied at startup and whenever the user changes it in Settings. */
object Lang {
    private fun systemLocale(): java.util.Locale = Resources.getSystem().configuration.locales[0]

    fun init() {
        L.systemCountry = systemLocale().country
        L.mode = Prefs.language
    }

    fun set(ctx: Context, mode: Int) {
        val wasId = L.id
        Prefs.language = mode
        L.mode = mode
        Notif.createChannels(ctx) // channel names follow the language too
        Repo.markChanged() // cached screen data (category names, insights) must reload in the new language
        val nowId = L.id
        if (wasId != nowId) Repo.fire { Repo.relabelDefaultWallets(nowId) }
    }

    fun name(mode: Int): String = when (mode) {
        L.EN -> "English"
        L.ID -> "Bahasa Indonesia"
        else -> tr("Follow phone", "Ikuti ponsel")
    }
}

/**
 * Fingerprint + fallback authentication.
 * Android 9+: system BiometricPrompt. Android 8: FingerprintManager (handled on the lock screen).
 * Fallback: the app PIN if one is set, otherwise the phone's own screen lock (PIN / pattern / password).
 */
object Auth {
    const val REQ_CREDENTIAL = 701
    private var pending: (() -> Unit)? = null

    fun hasSensor(ctx: Context): Boolean = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)

    /** A fingerprint is registered on the phone and can be used now. */
    fun fingerprintReady(ctx: Context): Boolean = try {
        when {
            Build.VERSION.SDK_INT >= 30 -> {
                val bm = ctx.getSystemService(BiometricManager::class.java)
                bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS ||
                    (hasSensor(ctx) && bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS)
            }
            Build.VERSION.SDK_INT == 29 ->
                @Suppress("DEPRECATION") (ctx.getSystemService(BiometricManager::class.java).canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS)
            else -> legacyManager(ctx)?.let {
                @Suppress("DEPRECATION")
                it.isHardwareDetected && it.hasEnrolledFingerprints()
            } ?: false
        }
    } catch (_: Throwable) { false }

    @Suppress("DEPRECATION")
    fun legacyManager(ctx: Context): FingerprintManager? = try { ctx.getSystemService(FingerprintManager::class.java) } catch (_: Throwable) { null }

    /** The phone has a PIN / pattern / password screen lock. */
    fun deviceSecure(ctx: Context): Boolean = try { ctx.getSystemService(KeyguardManager::class.java).isDeviceSecure } catch (_: Throwable) { false }

    fun fallbackLabel(): String = if (Security.enabled) tr("Use PIN", "Pakai PIN") else tr("Use screen lock", "Pakai kunci layar")

    private fun authenticators(ctx: Context): Int {
        val bm = ctx.getSystemService(BiometricManager::class.java)
        return if (bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS)
            BiometricManager.Authenticators.BIOMETRIC_STRONG else BiometricManager.Authenticators.BIOMETRIC_WEAK
    }

    /** System fingerprint dialog (Android 9+). On older versions calls [onFallback] directly. */
    fun prompt(a: Activity, title: String, negative: String, onSuccess: () -> Unit, onFallback: () -> Unit, onError: (String) -> Unit = {}) {
        if (Build.VERSION.SDK_INT < 28) { onFallback(); return }
        try {
            val exec = a.mainExecutor
            val b = BiometricPrompt.Builder(a)
                .setTitle(title)
                // Let the fingerprint dialog close before a fallback (e.g. the screen-lock prompt) opens.
                .setNegativeButton(negative, exec) { _, _ -> a.window.decorView.postDelayed({ onFallback() }, 300) }
            if (Build.VERSION.SDK_INT >= 30) b.setAllowedAuthenticators(authenticators(a))
            b.build().authenticate(CancellationSignal(), exec, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = onSuccess()
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED && errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED) {
                        onError(errString?.toString() ?: "")
                    }
                }
            })
        } catch (_: Throwable) {
            onFallback()
        }
    }

    /** Asks for the phone's own screen lock. Results for Android < 11 arrive via [onResult]. */
    fun deviceCredential(a: Activity, title: String, onSuccess: () -> Unit) {
        if (!deviceSecure(a)) {
            Toast.makeText(a, tr("This phone has no screen lock set.", "Ponsel ini belum memakai kunci layar."), Toast.LENGTH_LONG).show()
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                BiometricPrompt.Builder(a)
                    .setTitle(title)
                    .setAllowedAuthenticators(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    .build()
                    .authenticate(CancellationSignal(), a.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = onSuccess()
                    })
            } else {
                @Suppress("DEPRECATION")
                val i = a.getSystemService(KeyguardManager::class.java).createConfirmDeviceCredentialIntent(title, null) ?: return
                pending = onSuccess
                Lock.allowExternal()
                a.startActivityForResult(i, REQ_CREDENTIAL)
            }
        } catch (_: Throwable) {
            Lock.externalUntil = 0
            Toast.makeText(a, tr("Screen lock is not available.", "Kunci layar tidak tersedia."), Toast.LENGTH_LONG).show()
        }
    }

    fun onResult(requestCode: Int, resultCode: Int): Boolean {
        if (requestCode != REQ_CREDENTIAL) return false
        val p = pending
        pending = null
        if (resultCode == Activity.RESULT_OK) p?.invoke()
        return true
    }

    /** Opens the phone's fingerprint registration screen. */
    fun openEnroll(a: Activity) {
        val primary = when {
            Build.VERSION.SDK_INT >= 30 -> Intent(Settings.ACTION_BIOMETRIC_ENROLL)
                .putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, BiometricManager.Authenticators.BIOMETRIC_STRONG)
            Build.VERSION.SDK_INT >= 28 -> @Suppress("DEPRECATION") Intent(Settings.ACTION_FINGERPRINT_ENROLL)
            else -> Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        Lock.allowExternal()
        try { a.startActivity(primary) } catch (_: Throwable) {
            try { a.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) } catch (_: Throwable) { Lock.externalUntil = 0 }
        }
    }
}
