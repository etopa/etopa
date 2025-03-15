package de.ltheinrich.etopa

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.google.android.material.textfield.TextInputLayout
import de.ltheinrich.etopa.databinding.ActivityAccountBinding
import de.ltheinrich.etopa.utils.Common
import de.ltheinrich.etopa.utils.MenuType
import de.ltheinrich.etopa.utils.Storage

class AccountActivity : AppCompatActivity() {

    private val common: Common = Common.getInstance(this)
    private lateinit var preferences: SharedPreferences
    private lateinit var binding: ActivityAccountBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAccountBinding.inflate(layoutInflater)
        setContentView(binding.root)
        common.fixEdgeToEdge(findViewById(R.id.toolbar), findViewById(R.id.scrollview))
        common.menuType = MenuType.DISABLED
        binding.toolbar.root.title =
            getString(R.string.app_name) + ": " + getString(R.string.account)
        setSupportActionBar(binding.toolbar.root)
        preferences = getSharedPreferences("etopa", Context.MODE_PRIVATE)
        common.backActivity = AppActivity::class.java
        common.lockListener(this)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        common.setPasswordType(preferences, binding.verifyPin)
        binding.verifyPin.hint = getString(R.string.verify_pin)

        handleUsername()
        handlePassword()
        handleKey()
        handleDelete()
        keyboardHider(
            binding.verifyPin,
            binding.newUsername,
            binding.newPasswordRepeat,
            binding.newKeyRepeat,
            binding.keyAccountDeletion
        )
    }

    private fun handleDelete() {
        binding.deleteAccount.setOnClickListener {
            deleteAccount()
        }

        binding.confirmAccountDeletion.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (common.hashKey(binding.keyAccountDeletion.editText?.text.toString()) != common.keyHash) {
                    binding.confirmAccountDeletion.isChecked = false
                    return@setOnCheckedChangeListener common.toast(R.string.invalid_key)
                }
                binding.deleteAccount.visibility = View.VISIBLE
            } else {
                binding.deleteAccount.visibility = View.GONE
            }
        }

        binding.keyAccountDeletion.editText?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun afterTextChanged(key: Editable) {
                if (key.isNotEmpty()) {
                    if (binding.confirmAccountDeletion.isChecked && common.hashKey(binding.keyAccountDeletion.editText?.text.toString()) != common.keyHash) {
                        binding.confirmAccountDeletion.isChecked = false
                    }
                    binding.confirmAccountDeletion.visibility = View.VISIBLE
                } else {
                    binding.confirmAccountDeletion.visibility = View.GONE
                    binding.confirmAccountDeletion.isChecked = false
                    binding.deleteAccount.visibility = View.GONE
                }
            }
        })
    }

    private fun deleteAccount() {
        common.hideKeyboard(currentFocus)
        if (common.hashPin(binding.verifyPin.editText?.text.toString()) != common.pinHash)
            return common.toast(R.string.incorrect_pin)
        else if (!binding.confirmAccountDeletion.isChecked || common.hashKey(binding.keyAccountDeletion.editText?.text.toString()) != common.keyHash)
            return common.toast(R.string.invalid_key)

        common.request(
            "user/delete",
            { response ->
                if (!response.optBoolean("error", true)) {
                    preferences.edit {
                        clear()
                        common.setPin(this, common.pinHash)
                    }
                    common.toast(R.string.account_deleted)
                    common.openActivity(MainActivity::class)
                } else {
                    common.toast(R.string.unknown_error)
                    Log.e("API error", response.getString("error"))
                }
            },
            Pair("username", common.username),
            Pair("token", common.token),
            errorHandler = {
                common.toast(R.string.network_unreachable)
            })
    }

    private fun handleKey() {
        binding.changeKey.setOnClickListener {
            changeKey()
        }

        binding.newKey.editText?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun afterTextChanged(key: Editable) {
                if (key.isNotEmpty() && key.toString() == binding.newKeyRepeat.editText?.text.toString()) {
                    binding.changeKey.visibility = View.VISIBLE
                } else {
                    binding.changeKey.visibility = View.GONE
                }
            }
        })

        binding.newKeyRepeat.editText?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun afterTextChanged(key: Editable) {
                if (key.isNotEmpty() && key.toString() == binding.newKey.editText?.text.toString()) {
                    binding.changeKey.visibility = View.VISIBLE
                } else {
                    binding.changeKey.visibility = View.GONE
                }
            }
        })
    }

    private fun changeKey() {
        common.hideKeyboard(currentFocus)
        if (common.hashPin(binding.verifyPin.editText?.text.toString()) != common.pinHash)
            return common.toast(R.string.incorrect_pin)

        val newKeyHash = common.hashKey(binding.newKey.editText?.text.toString())
        binding.newKey.editText?.text?.clear()
        binding.newKeyRepeat.editText?.text?.clear()

        common.requestString(
            "data/get_secure",
            { secureStorage ->
                val storage = Storage(common, secureStorage)
                if (storage.map.containsValue(""))
                    return@requestString common.toast(R.string.decryption_failed)

                val encryptedStorage = storage.encrypt(newKeyHash)
                common.request(
                    "data/set_secure",
                    { response ->
                        if (!response.optBoolean("error", true)) {
                            common.keyHash = newKeyHash
                            preferences.edit {
                                putString(
                                    "secretStorage",
                                    common.encrypt(common.pinHash, encryptedStorage)
                                )
                                putString(
                                    "keyHash",
                                    common.encrypt(common.pinHash, common.keyHash)
                                )
                            }
                            common.toast(R.string.key_changed)
                            common.backKey(KeyEvent.KEYCODE_BACK)
                        } else {
                            common.toast(R.string.unknown_error)
                            Log.e("API error", response.getString("error"))
                        }
                    },
                    Pair("username", common.username),
                    Pair("token", common.token),
                    errorHandler = {
                        common.toast(R.string.network_unreachable)
                    },
                    body = encryptedStorage
                )
            },
            Pair("username", common.username),
            Pair("token", common.token),
            errorHandler = {
                common.toast(R.string.network_unreachable)
            })
    }

    private fun handlePassword() {
        binding.changePassword.setOnClickListener {
            changePassword()
        }

        binding.newPassword.editText?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun afterTextChanged(password: Editable) {
                if (password.isNotEmpty() && password.toString() == binding.newPasswordRepeat.editText?.text.toString()) {
                    binding.changePassword.visibility = View.VISIBLE
                } else {
                    binding.changePassword.visibility = View.GONE
                }
            }
        })

        binding.newPasswordRepeat.editText?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun afterTextChanged(password: Editable) {
                if (password.isNotEmpty() && password.toString() == binding.newPassword.editText?.text.toString()) {
                    binding.changePassword.visibility = View.VISIBLE
                } else {
                    binding.changePassword.visibility = View.GONE
                }
            }
        })
    }

    private fun changePassword() {
        common.hideKeyboard(currentFocus)
        if (common.hashPin(binding.verifyPin.editText?.text.toString()) != common.pinHash)
            return common.toast(R.string.incorrect_pin)

        val newPasswordHash =
            common.hashArgon2Hashed(common.hashPassword(binding.newPassword.editText?.text.toString()))
        binding.newPassword.editText?.text?.clear()
        binding.newPasswordRepeat.editText?.text?.clear()
        common.request(
            "user/change_password",
            { response ->
                if (!response.optBoolean("error", true)) {
                    common.passwordHash = newPasswordHash
                    preferences.edit {
                        putString(
                            "passwordHash",
                            common.encrypt(common.pinHash, common.passwordHash)
                        )
                    }
                    common.toast(R.string.password_changed)
                    common.backKey(KeyEvent.KEYCODE_BACK)
                } else {
                    common.toast(R.string.unknown_error)
                    Log.e("API error", response.getString("error"))
                }
            },
            Pair("username", common.username),
            Pair("token", common.token),
            Pair("newpassword", newPasswordHash),
            errorHandler = {
                common.toast(R.string.network_unreachable)
            })
    }

    private fun handleUsername() {
        binding.changeUsername.setOnClickListener {
            changeUsername()
        }

        binding.newUsername.editText?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun afterTextChanged(username: Editable) {
                if (username.isNotEmpty()) {
                    binding.changeUsername.visibility = View.VISIBLE
                } else {
                    binding.changeUsername.visibility = View.GONE
                }
            }
        })
    }

    private fun changeUsername() {
        common.hideKeyboard(currentFocus)
        if (common.hashPin(binding.verifyPin.editText?.text.toString()) != common.pinHash)
            return common.toast(R.string.incorrect_pin)

        val newUsername = binding.newUsername.editText?.text.toString()
        binding.newUsername.editText?.text?.clear()
        common.request(
            "user/change_username",
            { response ->
                if (!response.optBoolean("error", true)) {
                    common.username = newUsername
                    preferences.edit {
                        putString(
                            "username",
                            common.encrypt(common.pinHash, common.username)
                        )
                    }
                    common.toast(R.string.username_changed)
                    common.backKey(KeyEvent.KEYCODE_BACK)
                } else {
                    common.toast(R.string.name_exists)
                    Log.e("API error", response.getString("error"))
                }
            },
            Pair("username", common.username),
            Pair("token", common.token),
            Pair("newusername", newUsername),
            errorHandler = {
                common.toast(R.string.network_unreachable)
            })
    }

    private fun keyboardHider(vararg inputs: TextInputLayout) {
        for (input in inputs) {
            input.editText?.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO)
                    common.hideKeyboard(currentFocus)
                true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?) = common.backKey(keyCode)
    override fun onOptionsItemSelected(item: MenuItem) = common.handleMenu(item)
    override fun onCreateOptionsMenu(menu: Menu): Boolean = common.createMenu(menu)
}
