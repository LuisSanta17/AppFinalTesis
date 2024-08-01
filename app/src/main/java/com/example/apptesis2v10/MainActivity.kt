// File: MainActivity.kt
@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.apptesis2v10

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import com.example.apptesis2v10.ui.theme.AppTesis2V10Theme
import com.example.apptesis2v10.ui.theme.LightGreen
import kotlinx.coroutines.*
import java.io.IOException
import java.io.OutputStream
import java.util.*
import androidx.compose.ui.tooling.preview.Preview

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var selectedDevice: BluetoothDevice? = null
    private var currentMode by mutableStateOf(1)
    private var intensity by mutableStateOf(1)
    private var savedConfigurations by mutableStateOf(listOf<Pair<String, Pair<Int, Int>>>())

    companion object {
        private const val PREF_NAME = "AppTesisConfigs"
        private const val PREF_KEY_CONFIGS = "savedConfigs"
        private val UUID_INSECURE = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        loadConfigurations()
        setContent {
            var currentScreen by remember { mutableStateOf("main") }

            AppTesis2V10Theme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    when (currentScreen) {
                        "main" -> MainScreen(
                            currentMode = currentMode,
                            intensity = intensity,
                            modifier = Modifier
                                .padding(innerPadding)
                                .verticalScroll(rememberScrollState()),
                            onConnectBluetooth = { currentScreen = "devices" },
                            onBluetoothDevices = { openBluetoothSettings() },
                            onTurnOnStimulus = {
                                sendSignal("1")
                                showToast("Encendiendo estimulador muscular")
                            },
                            onTurnOffStimulus = {
                                sendSignal("0")
                                showToast("Apagando estimulador muscular")
                            },
                            onModeChange = { newMode -> currentMode = newMode },
                            onSendMode = {
                                sendMode()
                                showToast("Enviando numero de modo para los estimulos")
                            },
                            onSaveStimulus = { name -> saveConfiguration(name) },
                            onRegisterList = { currentScreen = "registers" },
                            onIntensityChange = { newIntensity -> intensity = newIntensity },
                            onSendIntensity = {
                                sendIntensity()
                                showToast("Enviando numero de intensidad para los estimulos")
                            }
                        )
                        "registers" -> RegisterListScreen(
                            configurations = savedConfigurations,
                            modifier = Modifier.padding(innerPadding),
                            onDelete = { deleteConfiguration(it) },
                            onApply = { index ->
                                val config = savedConfigurations[index]
                                currentMode = config.second.first
                                intensity = config.second.second
                                currentScreen = "main"
                            },
                            onBack = { currentScreen = "main" }
                        )
                        "devices" -> DeviceListScreen(
                            devices = getPairedDevices(),
                            modifier = Modifier.padding(innerPadding),
                            onDeviceSelected = { device ->
                                selectedDevice = device
                                connectToDevice(device)
                                currentScreen = "main" }
                        ) {
                            currentScreen = "main"
                        }
                    }
                }
            }
        }
    }

    private fun loadConfigurations() {
        val sharedPreferences = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val configJson = sharedPreferences.getString(PREF_KEY_CONFIGS, null)
        configJson?.let {
            val savedList = it.split("|").map { config ->
                val parts = config.split(":")
                val name = parts[0]
                val modeIntensity = parts[1].split(",").map { it.toInt() }
                name to (modeIntensity[0] to modeIntensity[1])
            }
            savedConfigurations = savedList
        }
    }

    private fun saveConfiguration(name: String) {
        val newConfig = name to (currentMode to intensity)
        savedConfigurations = savedConfigurations + newConfig
        saveToSharedPreferences()
    }

    private fun saveToSharedPreferences() {
        val sharedPreferences = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        val configString = savedConfigurations.joinToString("|") { (name, values) ->
            "${name}:${values.first},${values.second}"
        }
        editor.putString(PREF_KEY_CONFIGS, configString)
        editor.apply()
    }

    private fun deleteConfiguration(index: Int) {
        savedConfigurations = savedConfigurations.toMutableList().apply { removeAt(index) }
        saveToSharedPreferences()
    }

    private fun getPairedDevices(): List<BluetoothDevice> {
        val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter?.bondedDevices
        return pairedDevices?.toList() ?: listOf()
    }

    private fun connectBluetooth() {
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "El dispositivo no soporta Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, 1)
        } else {
            checkPermissionsAndScan()
        }
    }

    private fun checkPermissionsAndScan() {
        val requiredPermissions = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN
        )

        val missingPermissions = requiredPermissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), 1)
        } else {
            scanDevices()
        }
    }

    private fun scanDevices() {
        val pairedDevices = getPairedDevices()
        if (pairedDevices.isNotEmpty()) {
            setContent {
                var currentScreen by remember { mutableStateOf("devices") }
                AppTesis2V10Theme {
                    Scaffold(
                        topBar = { BackButtonAppBar(onBackPressed = { currentScreen = "main" }) },
                        modifier = Modifier.fillMaxSize()
                    ) { innerPadding ->
                        DeviceListScreen(
                            devices = pairedDevices,
                            onDeviceSelected = { device ->
                                selectedDevice = device
                                connectToDevice(device)
                            },
                            onBack = { currentScreen = "main" },
                            modifier = Modifier.padding(innerPadding)
                        )
                    }
                }
            }
        } else {
            Toast.makeText(this, "No se encontraron dispositivos emparejados.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                bluetoothSocket = device.createRfcommSocketToServiceRecord(UUID_INSECURE)
                bluetoothSocket?.connect()
                outputStream = bluetoothSocket?.outputStream

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Conectado a ${device.name}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error de conexión", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun sendSignal(signal: String) {
        if (outputStream == null) {
            Toast.makeText(this, "No hay conexión Bluetooth activa", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            outputStream?.write(signal.toByteArray())
        } catch (e: IOException) {
            Toast.makeText(this, "Error al enviar señal", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendMode() {
        sendSignal("M${currentMode}")
    }

    private fun sendIntensity() {
        sendSignal("I${intensity}")
    }

    private fun openBluetoothSettings() {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        startActivity(intent)
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun MainScreen(
    currentMode: Int,
    intensity: Int,
    modifier: Modifier = Modifier,
    onConnectBluetooth: () -> Unit,
    onBluetoothDevices: () -> Unit,
    onTurnOnStimulus: () -> Unit,
    onTurnOffStimulus: () -> Unit,
    onModeChange: (Int) -> Unit,
    onSendMode: () -> Unit,
    onSaveStimulus: (String) -> Unit,
    onRegisterList: () -> Unit,
    onIntensityChange: (Int) -> Unit,
    onSendIntensity: () -> Unit
) {
    var saveDialogVisible by remember { mutableStateOf(false) }
    var configName by remember { mutableStateOf("") }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Estimulación Eléctrica",
            fontWeight = FontWeight.Bold,
            fontSize = 28.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Modo de Estimulación", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = onTurnOnStimulus,
                        colors = ButtonDefaults.buttonColors(containerColor = LightGreen),
                        modifier = Modifier
                            .weight(1f)
                            .height(60.dp)
                    ) {
                        Icon(Icons.Filled.Power, contentDescription = "Encender Estímulo")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "Encender")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onTurnOffStimulus,
                        colors = ButtonDefaults.buttonColors(containerColor = LightGreen),
                        modifier = Modifier
                            .weight(1f)
                            .height(60.dp)
                    ) {
                        Icon(Icons.Filled.PowerOff, contentDescription = "Apagar Estímulo")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "Apagar")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "Intensidad: $intensity", modifier = Modifier.padding(bottom = 8.dp))
                Slider(
                    value = intensity.toFloat(),
                    onValueChange = { onIntensityChange(it.toInt()) },
                    valueRange = 1f..19f,
                    steps = 18,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onSendIntensity,
                    colors = ButtonDefaults.buttonColors(containerColor = LightGreen),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Icon(Icons.Filled.Send, contentDescription = "Enviar Intensidad")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Enviar Intensidad")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "Modo: $currentMode", modifier = Modifier.padding(bottom = 8.dp))
                Slider(
                    value = currentMode.toFloat(),
                    onValueChange = { onModeChange(it.toInt()) },
                    valueRange = 1f..8f,
                    steps = 7,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onSendMode,
                    colors = ButtonDefaults.buttonColors(containerColor = LightGreen),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Icon(Icons.Filled.Send, contentDescription = "Enviar Modo")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Enviar Modo")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Registros Guardados", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { saveDialogVisible = true },
                        colors = ButtonDefaults.buttonColors(containerColor = LightGreen),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = "Guardar Configuración")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "Guardar Configuración")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onRegisterList,
                        colors = ButtonDefaults.buttonColors(containerColor = LightGreen),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Icon(Icons.Filled.List, contentDescription = "Lista de Registros")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "Lista de Registros")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Conexión Bluetooth", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = onBluetoothDevices,
                        colors = ButtonDefaults.buttonColors(containerColor = LightGreen),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Icon(Icons.Filled.Bluetooth, contentDescription = "Encender Bluetooth")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "Encender Bluetooth")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onConnectBluetooth,
                        colors = ButtonDefaults.buttonColors(containerColor = LightGreen),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Icon(Icons.Filled.Devices, contentDescription = "Dispositivos Emparejados")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "Dispositivos Emparejados")
                    }
                }
            }
        }
    }

    if (saveDialogVisible) {
        AlertDialog(
            onDismissRequest = { saveDialogVisible = false },
            title = { Text(text = "Guardar Configuración", fontSize = 20.sp) },
            text = {
                Column {
                    Text(text = "Ingrese un nombre para la configuración:")
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = configName,
                        onValueChange = { configName = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSaveStimulus(configName)
                        saveDialogVisible = false
                    }
                ) {
                    Text("Guardar")
                }
            },
            dismissButton = {
                Button(
                    onClick = { saveDialogVisible = false }
                ) {
                    Text("Cancelar")
                }
            }
        )
    }
}

@Composable
fun RegisterListScreen(
    configurations: List<Pair<String, Pair<Int, Int>>>,
    modifier: Modifier = Modifier,
    onDelete: (Int) -> Unit,
    onApply: (Int) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Lista de Registros",
            fontWeight = FontWeight.Bold,
            fontSize = 28.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (configurations.isEmpty()) {
            Text(text = "No hay configuraciones guardadas.")
        } else {
            configurations.forEachIndexed { index, (name, values) ->
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = "Configuración: $name", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = "Modo: ${values.first}, Intensidad: ${values.second}")
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Button(
                                onClick = { onApply(index) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFADD8E6)),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp)
                            ) {
                                Icon(Icons.Filled.Check, contentDescription = "Aplicar Configuración")
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = "Aplicar")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = { onDelete(index) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB6C1)),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp)
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = "Eliminar Configuración")
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = "Eliminar")
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onBack,
            colors = ButtonDefaults.buttonColors(containerColor = LightGreen),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "Volver")
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "Volver")
        }
    }
}

@Composable
fun DeviceListScreen(
    devices: List<BluetoothDevice>,
    modifier: Modifier = Modifier,
    onDeviceSelected: (BluetoothDevice) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Dispositivos Emparejados",
            fontWeight = FontWeight.Bold,
            fontSize = 28.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (devices.isEmpty()) {
            Text(text = "No se encontraron dispositivos emparejados.")
        } else {
            devices.forEach { device ->
                Button(
                    onClick = { onDeviceSelected(device) },
                    colors = ButtonDefaults.buttonColors(containerColor = LightGreen),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp) // Tamaño aumentado
                        .padding(bottom = 16.dp) // Más separación entre botones
                ) {
                    Icon(Icons.Filled.Bluetooth, contentDescription = device.name)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = device.name)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onBack,
            colors = ButtonDefaults.buttonColors(containerColor = LightGreen),
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp) // Tamaño aumentado
        ) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "Volver")
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "Volver")
        }
    }
}

@Composable
fun BackButtonAppBar(onBackPressed: () -> Unit) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onBackPressed) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Volver")
            }
        },
        title = { Text(text = "Dispositivos") }
    )
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    MainScreen(
        currentMode = 1,
        intensity = 1,
        onConnectBluetooth = {},
        onBluetoothDevices = {},
        onTurnOnStimulus = {},
        onTurnOffStimulus = {},
        onModeChange = {},
        onSendMode = {},
        onSaveStimulus = {},
        onRegisterList = {},
        onIntensityChange = {},
        onSendIntensity = {}
    )
}

@Preview(showBackground = true)
@Composable
fun DeviceListScreenPreview() {
    DeviceListScreen(
        devices = listOf(),
        onDeviceSelected = {},
        onBack = {}
    )
}

@Preview(showBackground = true)
@Composable
fun RegisterListScreenPreview() {
    RegisterListScreen(
        configurations = listOf(),
        onDelete = {},
        onApply = {},
        onBack = {}
    )
}
