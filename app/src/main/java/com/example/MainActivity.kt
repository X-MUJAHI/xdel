package com.example

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.content.ClipboardManager
import android.content.ClipData
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Handle permissions
        if (permissions[Manifest.permission.POST_NOTIFICATIONS] == true) {
            postReinstallNotification()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        // Binder received, status will be updated by the checkStatus loop
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        // Handle binder dead
    }

    private val requestPermissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        // Handle permission result
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)
        
        enableEdgeToEdge()
        
        checkPermissions()
        
        setContent {
            MyApplicationTheme {
                var showSettings by remember { mutableStateOf(false) }
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    if (showSettings) {
                        SettingsScreen(
                            modifier = Modifier.padding(innerPadding),
                            onBack = { showSettings = false }
                        )
                    } else {
                        XDELScreen(
                            modifier = Modifier.padding(innerPadding),
                            onSettingsClick = { showSettings = true }
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener)
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                postReinstallNotification()
            }
        } else {
            postReinstallNotification()
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.addCategory("android.intent.category.DEFAULT")
                    intent.data = android.net.Uri.parse(String.format("package:%s", packageName))
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent()
                    intent.action = android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                    startActivity(intent)
                }
            } else {
                createXdelDirectories()
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                createXdelDirectories()
            }
        }
        
        if (permissions.isNotEmpty()) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun createXdelDirectories() {
        try {
            arrayOf("xdel", "x", "y").forEach {
                val dir = File("/storage/emulated/0/$it")
                if (!dir.exists()) dir.mkdirs()
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun postReinstallNotification() {
        val channelId = "xdel_action_channel"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "XDEL Actions",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(channel)
        }

        val actionIntent = Intent(this, NotificationReceiver::class.java).apply {
            action = "com.example.action.START_REINSTALL"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, actionIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("XDEL")
            .setContentText("Tap to quick-reinstall Free Fire")
            .setSmallIcon(android.R.drawable.ic_menu_preferences)
            .addAction(
                NotificationCompat.Action.Builder(
                    0,
                    "REINSTALL FREE FIRE",
                    pendingIntent
                ).build()
            )
            .setOngoing(true)
            .build()

        manager.notify(2, notification)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun XDELScreen(modifier: Modifier = Modifier, onSettingsClick: () -> Unit) {
        var shizukuState by remember { mutableStateOf("Checking...") }
        var isShizukuOk by remember { mutableStateOf(false) }
        var apkState by remember { mutableStateOf("Checking...") }
        var apkSizeState by remember { mutableStateOf("") }
        var isApkOk by remember { mutableStateOf(false) }
        var freefireState by remember { mutableStateOf("Checking...") }
        
        var xdelState by remember { mutableStateOf("Checking...") }
        var xState by remember { mutableStateOf("Checking...") }
        var yState by remember { mutableStateOf("Checking...") }
        
        var hasRequestedShizuku by remember { mutableStateOf(false) }
        val context = LocalContext.current

        val isRunning by ReinstallController.isRunning.collectAsState()
        val logs by ReinstallController.logs.collectAsState()

        val checkStatus = {
            createXdelDirectories()

            try {
                if (Shizuku.pingBinder()) {
                    if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                        shizukuState = "Connected"
                        isShizukuOk = true
                    } else {
                        shizukuState = "Permission Denied"
                        isShizukuOk = false
                        if (!hasRequestedShizuku) {
                            hasRequestedShizuku = true
                            try { Shizuku.requestPermission(0) } catch (e: Exception) {}
                        }
                    }
                } else {
                    shizukuState = "Not Running"
                    isShizukuOk = false
                }
            } catch (e: Exception) {
                shizukuState = "Not Running"
                isShizukuOk = false
            }

            val apkFile = File(ReinstallController.APK_PATH)
            if (apkFile.exists() && apkFile.canRead()) {
                val sizeMb = apkFile.length() / (1024 * 1024)
                apkState = ReinstallController.APK_PATH
                apkSizeState = "~$sizeMb MB"
                isApkOk = true
            } else {
                apkState = "Not found at ${ReinstallController.APK_PATH}"
                apkSizeState = ""
                isApkOk = false
            }
            
            xdelState = if (File("/storage/emulated/0/xdel").exists()) "Exists" else "Not Found"
            xState = if (File("/storage/emulated/0/x").exists()) "Exists" else "Not Found"
            yState = if (File("/storage/emulated/0/y").exists()) "Exists" else "Not Found"

            try {
                packageManager.getPackageInfo(ReinstallController.TARGET_PACKAGE, 0)
                freefireState = "Installed"
            } catch (e: PackageManager.NameNotFoundException) {
                freefireState = "Not Installed"
            }
        }

        LaunchedEffect(Unit, isRunning) {
            while (!isRunning) {
                checkStatus()
                kotlinx.coroutines.delay(1000)
            }
        }

        Column(
            modifier = modifier
                .fillMaxSize()
        ) {
            TopAppBar(
                title = { Text("XDEL", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
            
            Column(modifier = Modifier.padding(horizontal = 16.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    StatusItem("Shizuku", shizukuState, isShizukuOk, modifier = Modifier.weight(1f))
                }
                StatusItem("Free Fire", freefireState, freefireState == "Installed")
                StatusItem("xdel", xdelState, xdelState == "Exists")
                StatusItem("x", xState, xState == "Exists")
                StatusItem("y", yState, yState == "Exists")
                
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text("APK", fontWeight = FontWeight.Bold)
                    Text(apkState, style = MaterialTheme.typography.bodyMedium, color = if (isApkOk) Color.Unspecified else MaterialTheme.colorScheme.error)
                    if (apkSizeState.isNotEmpty()) {
                        Text("Size: $apkSizeState", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                if (!isShizukuOk) {
                    Button(
                        onClick = { 
                            try {
                                if (Shizuku.pingBinder()) {
                                    Shizuku.requestPermission(0)
                                } else {
                                    this@MainActivity.requestPermissions(arrayOf("moe.shizuku.manager.permission.API_V23"), 0)
                                    val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                                    if (intent != null) {
                                        startActivity(intent)
                                    }
                                }
                            } catch (e: Exception) {}
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(if (shizukuState == "Not Running") "OPEN SHIZUKU / FIX PERMISSION" else "REQUEST SHIZUKU PERMISSION")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                Button(
                    onClick = {
                        val serviceIntent = Intent(this@MainActivity, ReinstallService::class.java).apply {
                            action = ReinstallService.ACTION_START
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(serviceIntent)
                        } else {
                            startService(serviceIntent)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isRunning && isShizukuOk && isApkOk
                ) {
                    Text(if (isRunning) "INSTALLING..." else "REINSTALL FREE FIRE")
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Operation Log", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("XDEL Logs", logs.joinToString("\n"))
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Logs copied", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy logs")
                    }
                }
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(8.dp)
                ) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(logs) { logLine ->
                            Text(
                                text = logLine,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    @Composable
    fun StatusItem(title: String, status: String, isOk: Boolean, modifier: Modifier = Modifier) {
        Row(
            modifier = modifier.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.width(100.dp))
            val color = if (isOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            Text("● $status", color = color, style = MaterialTheme.typography.bodyMedium)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun SettingsScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
        val context = LocalContext.current
        val prefs = remember { context.getSharedPreferences("xdel_prefs", Context.MODE_PRIVATE) }
        var useInstallPrompt by remember { mutableStateOf(prefs.getBoolean("installPrompt", false)) }

        Column(modifier = modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
            
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Show Install Prompt", fontWeight = FontWeight.Bold)
                        Text(
                            "If on, shows standard Android prompt to install APK via Package Manager. If off, attempts to install silently via shell.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = useInstallPrompt,
                        onCheckedChange = { 
                            useInstallPrompt = it
                            prefs.edit().putBoolean("installPrompt", it).apply()
                        }
                    )
                }
            }
        }
    }
}

