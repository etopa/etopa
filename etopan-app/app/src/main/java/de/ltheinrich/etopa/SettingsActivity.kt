package de.ltheinrich.etopa

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import de.ltheinrich.etopa.databinding.ActivitySettingsBinding
import de.ltheinrich.etopa.utils.Common
import de.ltheinrich.etopa.utils.MenuType
import de.ltheinrich.etopa.utils.emptyPassword
import de.ltheinrich.etopa.utils.emptyPin
import de.ltheinrich.etopa.utils.emptyPinHash
import de.ltheinrich.etopa.utils.inputString

class SettingsActivity : AppCompatActivity() {

    private val common: Common = Common.getInstance(this)
    private lateinit var preferences: SharedPreferences
    private lateinit var binding: ActivitySettingsBinding
    private var biometricDisabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        common.fixEdgeToEdge(findViewById(R.id.toolbar), findViewById(R.id.scrollview))
        common.menuType = MenuType.DISABLED
        binding.toolbar.root.title =
            getString(R.string.app_name) + ": " + getString(R.string.settings)
        setSupportActionBar(binding.toolbar.root)
        preferences = getSharedPreferences("etopa", Context.MODE_PRIVATE)
        if (intent.hasExtra("incorrectLogin")) {
            common.backActivity = MainActivity::class.java
        } else {
            common.backActivity = AppActivity::class.java
        }
        common.lockListener(this)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        biometricDisabled = preferences.getBoolean("biometricDisabled", false)
        binding.disableBiometric.isChecked = biometricDisabled
        binding.pin.editText?.setText(emptyPin)
        binding.instance.editText?.setText(common.instance)
        common.setPasswordType(preferences, binding.pin)

        if (common.username.isNotEmpty())
            binding.username.editText?.setText(common.username)
        if (common.passwordHash.isNotEmpty())
            binding.password.editText?.setText(emptyPassword)
        if (common.keyHash.isNotEmpty())
            binding.key.editText?.setText(emptyPassword)

        if (intent.hasExtra("incorrectLogin")) {
            binding.register.visibility = View.VISIBLE
        }

        binding.save.setOnClickListener {
            save()
        }
        binding.key.editText?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO)
                save()
            true
        }
    }

    private fun save() {
        common.hideKeyboard(currentFocus)
        val instance = inputString(binding.instance)
        val username = inputString(binding.username)
        val password = inputString(binding.password)
        val key = inputString(binding.key)
        val pinHash = common.hashPin(inputString(binding.pin))
            .let {
                if (it == emptyPinHash) {
                    common.pinHash
                } else {
                    it
                }
            }

        if (binding.disableBiometric.isChecked && !biometricDisabled) {
            preferences.edit { putBoolean("biometricDisabled", true).remove("encryptedPin") }
        } else if (!binding.disableBiometric.isChecked && biometricDisabled) {
            preferences.edit { remove("biometricDisabled") }
        }

        if (pinHash != common.pinHash &&
            !preferences.getBoolean("biometricDisabled", false)
        ) {
            preferences.edit { remove("encryptedPin") }
        }

        common.instance = instance
        common.username = username
        common.passwordHash = common.hashPassword(password)
        common.keyHash = common.hashKey(key)

        common.encryptLogin(preferences, pinHash)
        if (binding.register.isChecked) {
            register()
        } else {
            common.newLogin(preferences)
        }
    }

    private fun register() {
        common.request(
            "user/register",
            { response ->
                if (response.has("token")) {
                    common.token = response.getString("token")
                    preferences.edit {
                        putString("token", common.encrypt(common.pinHash, common.token))
                    }
                    common.toast(R.string.settings_saved)
                    common.newLogin(preferences)
                } else {
                    common.toast(R.string.name_exists)
                    Log.e("API error", response.getString("error"))
                }
            },
            Pair("username", common.username),
            Pair("password", common.hashArgon2Hashed(common.passwordHash)),
            errorHandler = {
                common.toast(R.string.network_unreachable)
            })
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?) = common.backKey(keyCode)
    override fun onOptionsItemSelected(item: MenuItem) = common.handleMenu(item)
    override fun onCreateOptionsMenu(menu: Menu): Boolean = common.createMenu(menu)
}
