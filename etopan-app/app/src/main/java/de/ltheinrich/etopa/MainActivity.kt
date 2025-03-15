package de.ltheinrich.etopa

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import de.ltheinrich.etopa.databinding.ActivityMainBinding
import de.ltheinrich.etopa.utils.Common
import de.ltheinrich.etopa.utils.MenuType
import de.ltheinrich.etopa.utils.inputString
import java.nio.charset.Charset
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import kotlin.system.exitProcess

class MainActivity : AppCompatActivity() {

    private val common: Common = Common.getInstance(this)
    private lateinit var preferences: SharedPreferences
    private lateinit var binding: ActivityMainBinding
    private var pinSet: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG)
            DebugConfig()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        common.fixEdgeToEdge(findViewById(R.id.toolbar), findViewById(R.id.scrollview))
        common.menuType = MenuType.SIMPLE
        binding.toolbar.root.title =
            getString(R.string.app_name) + ": " + getString(R.string.unlock)
        setSupportActionBar(binding.toolbar.root)
        preferences = getSharedPreferences("etopa", Context.MODE_PRIVATE)
        common.backActivity = MainActivity::class.java
        common.lockListener(this)

        hideProgress()

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        pinSet = preferences.getString("pin_set", null)
        if (pinSet == null) {
            binding.unlock.text = getString(R.string.set_pin)
        }

        binding.unlock.setOnClickListener {
            unlock()
        }
        binding.pin.editText?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO)
                unlock()
            true
        }

        common.setPasswordType(preferences, binding.pin, binding.passwordType)
        binding.passwordType.setOnClickListener {
            preferences.edit {
                putBoolean(
                    "textPasswordNoPin",
                    !preferences.getBoolean("textPasswordNoPin", false)
                )
            }
            common.setPasswordType(preferences, binding.pin, binding.passwordType)
        }

        val exitApp = intent.getBooleanExtra("exitApp", false)
        if (exitApp) {
            finish()
            finishAffinity()
            if (common.checkSdk(Build.VERSION_CODES.LOLLIPOP))
                finishAndRemoveTask()
            exitProcess(0)
        }

        if (common.checkSdk(Build.VERSION_CODES.M) && !preferences.getBoolean(
                "biometricDisabled",
                false
            ) &&
            preferences.getString("encryptedPin", null) != null && common.biometricAvailable()
        ) {
            requestBiometric()
            binding.fingerprint.visibility = View.VISIBLE
            binding.fingerprint.setOnClickListener { requestBiometric() }
        } else {
            binding.pin.editText?.requestFocus()
        }
    }

    override fun onResume() {
        if (common.checkSdk(Build.VERSION_CODES.M) && !preferences.getBoolean(
                "biometricDisabled",
                false
            ) && !common.checkBackground() &&
            preferences.getString("encryptedPin", null) != null && common.biometricAvailable()
        ) {
            requestBiometric()
        } else {
            binding.pin.editText?.requestFocus()
        }
        super.onResume()
    }

    private fun requestBiometric() {
        biometricLogin { result ->
            showProgress()
            val encryptedPin = preferences.getString("encryptedPin", null)
            if (encryptedPin != null) {
                common.hideKeyboard(currentFocus)
                common.pinHash =
                    result.cryptoObject?.cipher?.let { cipher ->
                        decryptBiometric(cipher, encryptedPin)
                    }!!
                doUnlock()
            }
        }
    }

    @SuppressLint("CommitPrefEdits")
    private fun unlock() {
        common.hideKeyboard(currentFocus)
        val pinHash = common.hashPin(inputString(binding.pin))
        binding.pin.editText?.text?.clear()

        if (pinSet == null) {
            common.setPin(preferences.edit(), pinHash)
            common.toast(R.string.pin_set)
        } else if (!common.decrypt(pinHash, pinSet!!).contains("etopan_pin_set")) {
            return common.toast(R.string.incorrect_pin, 500)
        } else {
            common.pinHash = pinHash
        }

        if (common.checkSdk(Build.VERSION_CODES.M) && !preferences.getBoolean(
                "biometricDisabled",
                false
            ) &&
            preferences.getString("encryptedPin", null) == null && common.biometricAvailable()
        ) {
            biometricLogin({ doUnlock() }) { result ->
                val encryptedPin =
                    result.cryptoObject?.cipher?.let { encryptBiometric(it, pinHash) }
                preferences.edit { putString("encryptedPin", encryptedPin) }
            }
        } else {
            doUnlock()
        }
    }

    private fun doUnlock() {
        showProgress()
        common.menuType = MenuType.FULL
        common.decryptLogin(preferences)

        if (pinSet == null) {
            hideProgress()
            // binding.unlock.text = getString(R.string.unlock)
            common.openActivity(
                SettingsActivity::class,
                Pair("incorrectLogin", "incorrectLogin")
            )
        } else {
            login()
        }
    }

    private fun login() {
        showProgress()
        if (preferences.getString("token", "").isNullOrEmpty()) {
            common.newLogin(preferences)
        } else {
            tokenLogin()
        }
    }

    private fun tokenLogin() {
        showProgress()
        common.request(
            "user/valid",
            { responseValid ->
                if (responseValid.has("valid") && responseValid.getBoolean("valid")) {
                    common.openActivity(AppActivity::class)
                } else {
                    common.newLogin(preferences)
                }
            },
            Pair("username", common.username),
            Pair("token", common.token),
            errorHandler = {
                common.offlineLogin(preferences)
            })
    }

    private fun encryptBiometric(cipher: Cipher, plain: String): String? {
        return try {
            val enc = cipher.doFinal(plain.toByteArray(Charset.defaultCharset()))
            Base64.encodeToString(enc, Base64.DEFAULT) + Base64.encodeToString(
                cipher.iv,
                Base64.DEFAULT
            )
        } catch (ex: Exception) {
            ex.printStackTrace()
            null
        }
    }

    private fun decryptBiometric(cipher: Cipher, encrypted: String): String? {
        return try {
            val dec = Base64.decode(encrypted.split('\n')[0], Base64.DEFAULT)
            cipher.doFinal(dec)?.toString(Charsets.UTF_8)
        } catch (ex: Exception) {
            ex.printStackTrace()
            null
        }
    }

    private fun biometricLogin(
        always: () -> Unit = {},
        onSuccess: (result: BiometricPrompt.AuthenticationResult) -> Unit,
    ) {
        try {
            showProgress()
            val biometricPrompt =
                BiometricPrompt(
                    this, ContextCompat.getMainExecutor(this),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationError(
                            errorCode: Int,
                            errString: CharSequence,
                        ) {
                            super.onAuthenticationError(errorCode, errString)
                            hideProgress()
                            binding.pin.editText?.requestFocus()
                            if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON && errorCode != BiometricPrompt.ERROR_CANCELED)
                                common.toast(
                                    getString(R.string.biometric_error, errString),
                                    length = Toast.LENGTH_SHORT
                                )
                            try {
                                always()
                            } catch (ex: Exception) {
                                common.toast(R.string.unknown_error)
                            }
                        }

                        override fun onAuthenticationSucceeded(
                            result: BiometricPrompt.AuthenticationResult,
                        ) {
                            super.onAuthenticationSucceeded(result)
                            try {
                                showProgress()
                                onSuccess(result)
                                always()
                            } catch (ex: Exception) {
                                hideProgress()
                                common.toast(R.string.unknown_error)
                            }
                        }
                    })

            val encryptedPin = preferences.getString("encryptedPin", null)
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.biometric_unlock))
                .setSubtitle(
                    if (encryptedPin == null) {
                        getString(R.string.biometric_setup)
                    } else {
                        getString(R.string.biometric_verify)
                    }
                )
                .setNegativeButtonText(getString(R.string.cancel))
                .build()

            val keyStore = getKeyStore()
            if (!keyStore.containsAlias("etopan_pin")) {
                var keySpec = KeyGenParameterSpec.Builder(
                    "etopan_pin",
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                    .setUserAuthenticationRequired(true)
                keySpec = if (common.checkSdk(Build.VERSION_CODES.R)) {
                    keySpec.setUserAuthenticationParameters(
                        Integer.MAX_VALUE,
                        KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG
                    )
                } else {
                    @Suppress("DEPRECATION")
                    keySpec.setUserAuthenticationValidityDurationSeconds(Integer.MAX_VALUE)
                }
                generateSecretKey(keySpec.build())
            }

            val secretKey = getSecretKey(keyStore)
            val cipher = getCipher()
            if (encryptedPin == null) {
                cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            } else {
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    secretKey,
                    IvParameterSpec(Base64.decode(encryptedPin.split('\n')[1], Base64.DEFAULT))
                )
            }
            biometricPrompt.authenticate(
                promptInfo,
                BiometricPrompt.CryptoObject(cipher)
            )
        } catch (ex: Exception) {
            hideProgress()
            preferences.edit { putBoolean("biometricDisabled", true) }
            common.toast(R.string.unknown_error)
            common.toast(R.string.disable_biometric)
        }
    }

    private fun showProgress() {
        binding.pin.visibility = View.GONE
        binding.unlock.visibility = View.GONE
        binding.fingerprint.visibility = View.GONE
        binding.passwordType.visibility = View.GONE
        binding.progress.visibility = View.VISIBLE
    }

    private fun hideProgress() {
        binding.progress.visibility = View.GONE
        binding.pin.visibility = View.VISIBLE
        binding.unlock.visibility = View.VISIBLE
        if (common.checkSdk(Build.VERSION_CODES.M) && !preferences.getBoolean(
                "biometricDisabled",
                false
            ) &&
            preferences.getString("encryptedPin", null) != null && common.biometricAvailable()
        )
            binding.fingerprint.visibility = View.VISIBLE
        binding.passwordType.visibility = View.VISIBLE
    }

    private fun generateSecretKey(keyGenParameterSpec: KeyGenParameterSpec) {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        )
        keyGenerator.init(keyGenParameterSpec)
        keyGenerator.generateKey()
    }

    private fun getSecretKey(keyStore: KeyStore): SecretKey {
        return keyStore.getKey("etopan_pin", null) as SecretKey
    }

    private fun getKeyStore(): KeyStore {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore?.load(null)
        return keyStore
    }

    private fun getCipher(): Cipher {
        return Cipher.getInstance(
            KeyProperties.KEY_ALGORITHM_AES + "/"
                    + KeyProperties.BLOCK_MODE_CBC + "/"
                    + KeyProperties.ENCRYPTION_PADDING_PKCS7
        )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?) = common.backKey(keyCode)
    override fun onOptionsItemSelected(item: MenuItem) = common.handleMenu(item)
    override fun onCreateOptionsMenu(menu: Menu): Boolean = common.createMenu(menu)
}