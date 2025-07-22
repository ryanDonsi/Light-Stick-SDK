package io.lightstick.demoapp.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.lightstick.demoapp.ui.dialog.ColorPickerDialog
import io.lightstick.demoapp.ui.dialog.DeviceInfoDialog
import io.lightstick.demoapp.ui.dialog.LSEffectPayloadDialog
import io.lightstick.demoapp.ui.dialog.OtaDialog
import io.lightstick.demoapp.ui.list.BondedList
import io.lightstick.demoapp.ui.list.DeviceList
import io.lightstick.demoapp.util.getFileNameFromUri
import io.lightstick.demoapp.viewmodel.BleDeviceViewModel
import io.lightstick.sdk.ble.model.LSEffectPayload

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BleTabbedScreen(
    viewModel: BleDeviceViewModel = hiltViewModel()
) {
    val context = LocalContext.current

    var selectedTab by remember { mutableIntStateOf(0) }

    var showColorDialog by remember { mutableStateOf<String?>(null) }
    var showEffectDialog by remember { mutableStateOf<String?>(null) }
    var showOtaDialog by remember { mutableStateOf<String?>(null) }
    var showInfoDialog by remember { mutableStateOf<String?>(null) }

    val scanResults by viewModel.scanResults.collectAsState()
    val bondedDevices by viewModel.bondedDevices.collectAsState()
    val connectedAddresses by viewModel.connectedAddresses.collectAsState()
    val deviceInfoMap by viewModel.deviceInfoMap.collectAsState()
    val batteryLevels by viewModel.batteryLevels.collectAsState()
    val otaProgress by viewModel.otaProgress.collectAsState()
    val otaStatus by viewModel.otaStatus.collectAsState()

    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileName by remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            selectedUri = it
            selectedFileName = getFileNameFromUri(context, it) ?: "알 수 없는 파일"
        }
    }

    LaunchedEffect(Unit) {
        viewModel.startScan()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Light Stick SDK") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)) {

            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("BLE") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("BOND") })
            }

            Spacer(modifier = Modifier.height(8.dp))

            Box(modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)) {

                when (selectedTab) {
                    0 -> DeviceList(
                        devices = scanResults,
                        connectedAddresses = connectedAddresses,
                        deviceInfoMap = deviceInfoMap,
                        onConnectClick = viewModel::connect,
                        onDisconnectClick = {
                            viewModel.disconnect(it)
                            viewModel.stopEffectSending()
                        },
                        onColorClick = { showColorDialog = it },
                        onEffectClick = { showEffectDialog = it },
                        onOtaClick = { showOtaDialog = it },
                        onInfoClick = { showInfoDialog = it }
                    )

                    1 -> BondedList(
                        devices = bondedDevices,
                        connectedAddresses = connectedAddresses,
                        deviceInfoMap = deviceInfoMap,
                        batteryLevel = batteryLevels,
                        onConnectClick = viewModel::connect,
                        onDisconnectClick = {
                            viewModel.disconnect(it)
                            viewModel.stopEffectSending()
                        },
                        onColorClick = { showColorDialog = it },
                        onEffectClick = { showEffectDialog = it },
                        onOtaClick = { showOtaDialog = it },
                        onInfoClick = { showInfoDialog = it }
                    )
                }

                showColorDialog?.let { address ->
                    ColorPickerDialog(
                        onDismiss = { showColorDialog = null },
                        onColorSelected = { color, transition ->
                            viewModel.sendLedColor(address, color, transition)
                        }
                    )
                }

                showEffectDialog?.let { address ->
                    LSEffectPayloadDialog(
                        onSend = { payload: LSEffectPayload, interval: Long, isSequential: Boolean ->
                            if (isSequential) {
                                viewModel.sendEffectSequentially(address, payload, interval)
                            } else {
                                viewModel.sendEffectRepeatedly(address, payload, interval)
                            }
                        },
                        onStop = {
                            viewModel.stopEffectSending()
                        },
                        onDismiss = {
                            viewModel.stopEffectSending()
                            showEffectDialog = null
                        }
                    )
                }

                showOtaDialog?.let { address ->
                    OtaDialog(
                        selectedFileName = selectedFileName,
                        progress = otaProgress,
                        statusText = otaStatus,
                        onDismiss = {
                            viewModel.clearOtaStatus()
                            selectedUri = null
                            selectedFileName = null
                            showOtaDialog = null
                        },
                        onPickFile = {
                            selectedUri = null
                            selectedFileName = null
                            launcher.launch(arrayOf("*/*"))
                        },
                        onStartOta = {
                            if (selectedUri != null) {
                                context.contentResolver.openInputStream(selectedUri!!)?.use { input ->
                                    val data = input.readBytes()
                                    viewModel.startOta(address, data)
                                }
                            } else {
                                launcher.launch(arrayOf("*/*"))
                            }
                        }
                    )
                }

                showInfoDialog?.let { address ->
                    val info = deviceInfoMap[address]
                    DeviceInfoDialog(
                        address = address,
                        info = info,
                        onDismiss = { showInfoDialog = null }
                    )
                }
            }
        }
    }
}
