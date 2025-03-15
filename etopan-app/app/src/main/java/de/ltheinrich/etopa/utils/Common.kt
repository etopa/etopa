package de.ltheinrich.etopa.utils

import android.app.Activity
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Context.POWER_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.PowerManager
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.iterator
import com.android.volley.RequestQueue
import com.android.volley.Response
import com.android.volley.VolleyError
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley
import com.google.android.material.textfield.TextInputLayout
import de.ltheinrich.etopa.AccountActivity
import de.ltheinrich.etopa.AddActivity
import de.ltheinrich.etopa.AppActivity
import de.ltheinrich.etopa.LicensesActivity
import de.ltheinrich.etopa.MainActivity
import de.ltheinrich.etopa.R
import de.ltheinrich.etopa.SettingsActivity
import org.json.JSONObject
import java.util.Random
import java.util.UUID
import kotlin.reflect.KClass

typealias Handler = (response: JSONObject) -> Unit
typealias StringHandler = (response: String) -> Unit
typealias ErrorHandler = (error: VolleyError) -> Unit

const val emptyPin = "******"
const val emptyPinHash = "8326de6693e2dc5e15d9d2031d26844c"

const val emptyPassword = "************"
const val emptyPasswordHash = "08d299150597a36973bf282c1ce59602eaa12c3607d3034d7ea29bb64710d65c"
const val emptyKeyHash = "c353cdd4c437c0dc01d6378525e25c1d"

var library: Boolean = false

fun inputString(inputLayout: TextInputLayout): String {
    return inputLayout.editText?.text.toString()
}

enum class MenuType {
    DISABLED, SIMPLE, FULL
}

class Common(activity: Activity) {

    var instance: String? = null
    var username: String = ""
    var passwordHash: String = ""
    var keyHash: String = ""
    var token: String = ""
    var storage: Storage? = null
    var pinHash: String = ""
    var backActivity: Class<*> = MainActivity::class.java
    var offline: Boolean = false
    var menuType: MenuType = MenuType.SIMPLE
    private var insets: WindowInsetsCompat? = null

    fun handleMenu(item: MenuItem) = when (item.itemId) {
        R.id.action_add -> {
            openActivity(AddActivity::class)
            true
        }

        R.id.action_account -> {
            openActivity(AccountActivity::class)
            true
        }

        R.id.action_settings -> {
            openActivity(SettingsActivity::class)
            true
        }

        R.id.action_licenses -> {
            openActivity(LicensesActivity::class)
            true
        }

        android.R.id.home -> {
            backKey(KeyEvent.KEYCODE_BACK)
            true
        }

        else -> {
            false
        }
    }

    fun backKey(keyCode: Int): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (backActivity == AppActivity::class.java && pinHash.isEmpty())
                openActivity(MainActivity::class)
            else if (backActivity == MainActivity::class.java)
                activity.moveTaskToBack(true)
            else
                openActivity(backActivity)
            return true
        }
        return false
    }

    fun createMenu(menu: Menu?): Boolean {
        activity.menuInflater.inflate(R.menu.toolbar_menu, menu)
        val simpleItems = arrayOf(R.id.action_licenses)
        val offlineDisabled = arrayOf(R.id.action_account, R.id.action_add)
        when (menuType) {
            MenuType.DISABLED -> menu?.iterator()?.forEach { item -> item.isVisible = false }
            MenuType.SIMPLE -> simpleItems.forEach { menu?.findItem(it)?.isVisible = true }
            MenuType.FULL -> menu?.iterator()?.forEach { item -> item.isVisible = true }
        }
        if (offline)
            offlineDisabled.forEach { menu?.findItem(it)?.isVisible = false }
        return true
    }

    fun setPasswordType(prefs: SharedPreferences, pin: TextInputLayout, type: ImageButton? = null) {
        if (prefs.getBoolean("textPasswordNoPin", false)) {
            pin.hint = activity.getString(R.string.password)
            pin.editText?.inputType =
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            type?.setImageResource(R.drawable.ic_baseline_fiber_pin_24)
        } else {
            pin.hint = activity.getString(R.string.pin)
            pin.editText?.inputType =
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            type?.setImageResource(R.drawable.ic_baseline_text_fields_24)
        }
    }

    fun lockListener(activity: Activity) {
        val intentFilter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        activity.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF)
                    triggerRestart(activity)
            }
        }, intentFilter)
    }

    fun triggerRestart(context: Activity) {
        val intent = Intent(context, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        context.finish()
        Runtime.getRuntime().exit(0)
    }

    fun parseSecretUri(uri: String): Pair<String, String?> {
        val otpAuth = uri.removePrefix("otpauth://")
        if (otpAuth != uri) {
            val totp = otpAuth.removePrefix("totp/")
            if (totp != otpAuth) {
                val name = totp.split('?', limit = 2)
                if (name.size == 2) {
                    val data = name[1].split('&')
                    for (d in data) {
                        if (d.startsWith("secret=")) {
                            val secret = d.split('=', limit = 2)
                            if (secret.size == 2)
                                return Pair(secret[1], decodeUrl(name[0]))
                        }
                    }
                }
            }
        }
        return Pair(uri, null)
    }

    fun decryptLogin(preferences: SharedPreferences) {
        instance = preferences.getString("instance", encrypt(pinHash, "etopa.de"))?.let {
            decrypt(
                pinHash,
                it
            )
        }.toString()
        username = decrypt(
            pinHash,
            preferences.getString("username", encrypt(pinHash, "")).orEmpty()
        )
        passwordHash = decrypt(
            pinHash,
            preferences.getString("passwordHash", encrypt(pinHash, "")).orEmpty()
        )
        keyHash = decrypt(
            pinHash,
            preferences.getString("keyHash", encrypt(pinHash, "")).orEmpty()
        )
        token = decrypt(
            pinHash,
            preferences.getString("token", encrypt(pinHash, "")).orEmpty()
        )
    }

    fun encryptLogin(preferences: SharedPreferences, pinHash: String) {
        val editor = preferences.edit()
        val defaultInstance = activity.getString(R.string.default_instance)

        if (instance.isNullOrEmpty() || instance == defaultInstance) {
            editor.remove("instance")
            instance = defaultInstance
        } else {
            editor.putString("instance", encrypt(pinHash, instance!!))
        }

        if (passwordHash == emptyPasswordHash) {
            passwordHash = decrypt(
                this.pinHash,
                preferences.getString("passwordHash", encrypt(this.pinHash, "")).orEmpty()
            )
        }

        if (keyHash == emptyKeyHash) {
            keyHash = decrypt(
                this.pinHash,
                preferences.getString("keyHash", encrypt(this.pinHash, "")).orEmpty()
            )
        }

        editor.putString("username", encrypt(pinHash, username))
        editor.putString("passwordHash", encrypt(pinHash, passwordHash))
        editor.putString("keyHash", encrypt(pinHash, keyHash))
        val secretStorage = preferences.getString("secretStorage", null)
        if (secretStorage != null) {
            editor.putString(
                "secretStorage",
                encrypt(pinHash, decrypt(this.pinHash, secretStorage))
            )
        }

        editor.remove(token)
        setPin(editor, pinHash)
    }

    fun setPin(editor: SharedPreferences.Editor, pinHash: String) {
        val splitAt = Random().nextInt(30)
        val uuid = UUID.randomUUID().toString()
        val pinSetEncrypted =
            encrypt(
                pinHash,
                uuid.substring(0, splitAt) + "etopan_pin_set" + uuid.substring(splitAt)
            )

        editor.putString("pin_set", pinSetEncrypted)
        editor.apply()

        this.pinHash = pinHash
    }

    fun newLogin(preferences: SharedPreferences) {
        request(
            "user/login",
            { response ->
                if (response.has("token")) {
                    token = response.getString("token")
                    preferences.edit {
                        putString("token", encrypt(pinHash, token))
                    }
                    openActivity(AppActivity::class)
                } else {
                    toast(R.string.incorrect_login)
                    openActivity(SettingsActivity::class, Pair("incorrectLogin", "incorrectLogin"))
                }
            },
            Pair("username", username),
            Pair("password", passwordHash),
            errorHandler = { offlineLogin(preferences) })
    }

    fun offlineLogin(preferences: SharedPreferences) {
        toast(R.string.network_unreachable)
        if (preferences.contains("secretStorage")) {
            openActivity(AppActivity::class)
        }
    }

    fun request(
        url: String,
        handler: Handler,
        vararg data: Pair<String, String>,
        errorHandler: ErrorHandler = { error: VolleyError ->
            Log.e(
                "HTTP Request",
                error.toString()
            )
        },
        body: String? = null,
    ) {
        val jsonObjectRequest = object : JsonObjectRequest(
            Method.POST, "https://$instance/$url", null,
            Response.Listener { response ->
                offline = false
                handler(response)
            },
            Response.ErrorListener { error ->
                offline = true
                Log.e("Network error", error.toString())
                errorHandler(error)
            }
        ) {
            override fun getHeaders(): Map<String, String> {
                return data.toMap()
            }

            override fun getBody(): ByteArray {
                if (!body.isNullOrEmpty())
                    return body.toByteArray(Charsets.UTF_8)
                return ByteArray(0)
            }
        }
        http.add(jsonObjectRequest)
    }

    fun requestString(
        url: String,
        handler: StringHandler,
        vararg data: Pair<String, String>,
        errorHandler: ErrorHandler = { error: VolleyError ->
            Log.e(
                "HTTP Request",
                error.toString()
            )
        },
        body: String? = null,
    ) {
        val stringRequest = object : StringRequest(
            Method.POST, "https://$instance/$url",
            Response.Listener { response ->
                offline = false
                handler(response)
            },
            Response.ErrorListener { error ->
                offline = true
                errorHandler(error)
            }
        ) {
            override fun getHeaders(): Map<String, String> {
                return data.toMap()
            }

            override fun getBody(): ByteArray {
                if (!body.isNullOrEmpty())
                    return body.toByteArray(Charsets.UTF_8)
                return ByteArray(0)
            }
        }
        http.add(stringRequest)
    }

    fun <T : Activity> openActivity(
        cls: KClass<T>,
        vararg extras: Pair<String, String>,
    ) {
        val app = Intent(activity, cls.java)
        for ((key, value) in extras) {
            app.putExtra(key, value)
        }
        activity.startActivity(app)
    }

    private fun openActivity(
        cls: Class<*>,
        vararg extras: Pair<String, String>,
    ) {
        val app = Intent(activity, cls)
        for ((key, value) in extras) {
            app.putExtra(key, value)
        }
        activity.startActivity(app)
    }

    fun toast(stringId: Int, length: Int = Toast.LENGTH_LONG) {
        Toast.makeText(activity, stringId, length).show()
    }

    fun toast(text: String, length: Int = Toast.LENGTH_LONG) {
        Toast.makeText(activity, text, length).show()
    }

    fun checkSdk(minSdk: Int): Boolean {
        return Build.VERSION.SDK_INT >= minSdk
    }

    fun checkBackground(): Boolean {
        val appProcessInfo = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(appProcessInfo)
        val powerManager = activity.getSystemService(POWER_SERVICE) as PowerManager
        return !powerManager.isInteractive
    }

    fun biometricAvailable(): Boolean {
        return BiometricManager.from(activity)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun hideKeyboard(currentFocus: View?) {
        val imm: InputMethodManager =
            activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
        if (currentFocus != null)
            imm.hideSoftInputFromWindow(currentFocus.windowToken, 0)
    }

    fun copyToClipboard(toCopy: String) {
        val clipboard = ContextCompat.getSystemService(
            activity,
            ClipboardManager::class.java
        )
        val clip = ClipData.newPlainText(toCopy, toCopy)
        clipboard?.setPrimaryClip(clip)
    }

    fun fixEdgeToEdge(toolbar: View, firstElement: View) {
        insets?.let {
            val innerPadding =
                it.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            toolbar.setPadding(0, innerPadding.top, 0, 0)
            toolbar.layoutParams.height += innerPadding.top
            (firstElement.layoutParams as MarginLayoutParams).topMargin += innerPadding.top
        } ?: run {
            ViewCompat.setOnApplyWindowInsetsListener(toolbar) { _, insets ->
                this.insets = insets
                fixEdgeToEdge(toolbar, firstElement)
                insets
            }
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: Common? = null
        fun getInstance(activity: Activity): Common =
            if (library) {
                INSTANCE ?: synchronized(this) {
                    INSTANCE
                        ?: Common(activity).also {
                            INSTANCE = it
                        }
                }
            } else {
                System.loadLibrary("etopan")
                library = true
                getInstance(activity)
            }
    }

    private val activity: Activity by lazy {
        activity
    }

    private val http: RequestQueue by lazy {
        Volley.newRequestQueue(activity.applicationContext)
    }

    external fun hashKey(key: String): String
    external fun hashPassword(password: String): String
    external fun hashPin(pin: String): String
    external fun hashName(name: String): String
    external fun hashArgon2Hashed(passwordHash: String): String
    external fun encrypt(key: String, data: String): String
    external fun decrypt(key: String, data: String): String
    external fun generateToken(secret: String): String
    private external fun decodeUrl(encodedUrl: String): String
}
