package ro.earnapp

import android.app.Activity
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.rewarded.ServerSideVerificationOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

const val MIN_WITHDRAW = 0.5

fun fmt(v: Double): String = String.format(Locale.getDefault(), "%.3f €", v)

object Api {
    suspend fun call(method: String, path: String, token: String?, body: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val c = URL(BuildConfig.BASE_URL + path).openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.setRequestProperty("Content-Type", "application/json")
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            val json = JSONObject(text.ifBlank { "{}" })
            if (code !in 200..299) throw Exception(json.optString("error", "Eroare $code"))
            json
        }
}

class EarnViewModel : ViewModel() {
    var balance by mutableStateOf(0.0)
    var history by mutableStateOf(listOf<String>())
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)

    private var token: String? = null
    private var userId: String = ""
    private var ad: RewardedAd? = null
    private var started = false

    fun start(ctx: Context) {
        if (started) return
        started = true
        val prefs = ctx.getSharedPreferences("earn", Context.MODE_PRIVATE)
        token = prefs.getString("token", null)
        userId = prefs.getString("uid", "").orEmpty()
        viewModelScope.launch {
            try {
                if (token == null) {
                    val r = Api.call("POST", "/register", null, JSONObject())
                    token = r.getString("token")
                    userId = r.getString("userId")
                    prefs.edit().putString("token", token).putString("uid", userId).apply()
                }
                refresh()
                loadAd(ctx)
            } catch (e: Exception) {
                message = e.message
                started = false
            }
        }
    }

    suspend fun refresh() {
        val r = Api.call("GET", "/me", token)
        balance = r.getDouble("balance")
        val h = r.getJSONArray("history")
        history = (0 until h.length()).map {
            val o = h.getJSONObject(it)
            "${o.getString("date").take(10)}   ${o.getString("label")}   ${fmt(o.getDouble("amount"))}"
        }
    }

    private fun loadAd(ctx: Context) {
        RewardedAd.load(ctx, BuildConfig.REWARDED_AD_UNIT, AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(a: RewardedAd) {
                    // userId ajunge la server în apelul de verificare (SSV), de unde se creditează câștigul
                    a.setServerSideVerificationOptions(
                        ServerSideVerificationOptions.Builder().setUserId(userId).build()
                    )
                    ad = a
                }

                override fun onAdFailedToLoad(e: LoadAdError) {
                    ad = null
                }
            })
    }

    fun watch(act: Activity) {
        val a = ad
        if (a == null) {
            message = "Reclama se încarcă. Încearcă din nou în câteva secunde."
            loadAd(act)
            return
        }
        ad = null
        a.show(act) { _ ->
            viewModelScope.launch {
                delay(4000) // serverul primește verificarea de la Google cu o mică întârziere
                try {
                    refresh()
                    message = "Recompensă primită!"
                } catch (e: Exception) {
                    message = e.message
                }
            }
        }
        loadAd(act)
    }

    fun withdraw(email: String) {
        viewModelScope.launch {
            busy = true
            try {
                val r = Api.call("POST", "/withdraw", token, JSONObject().put("paypalEmail", email))
                message = "Retragere trimisă: ${fmt(r.getDouble("amount"))}"
                refresh()
            } catch (e: Exception) {
                message = e.message
            } finally {
                busy = false
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MobileAds.initialize(this)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }
}

@Composable
fun Screen(vm: EarnViewModel = viewModel()) {
    val ctx = LocalContext.current
    val activity = ctx as Activity
    var showDialog by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.start(ctx) }

    Column(
        Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Soldul tău", fontSize = 16.sp)
        Text(fmt(vm.balance), fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Text("Retragere minimă: 0,50 € prin PayPal", fontSize = 13.sp)
        Spacer(Modifier.height(20.dp))

        Button(onClick = { vm.watch(activity) }, modifier = Modifier.fillMaxWidth()) {
            Text("Vezi o reclamă și câștigă")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { showDialog = true },
            enabled = vm.balance >= MIN_WITHDRAW && !vm.busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Retrage pe PayPal") }

        vm.message?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(20.dp))
        Text("Istoric", fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            items(vm.history) { Text(it, fontSize = 13.sp) }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Retragere PayPal") },
            text = {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email PayPal") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false; vm.withdraw(email) }) { Text("Retrage") }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("Anulează") } }
        )
    }
}
