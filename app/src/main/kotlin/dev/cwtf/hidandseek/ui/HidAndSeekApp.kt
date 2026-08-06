package dev.cwtf.hidandseek.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.cwtf.hidandseek.bluetooth.HidController
import dev.cwtf.hidandseek.hid.TransportState
import dev.cwtf.hidandseek.ui.type.TypeScreen
import dev.cwtf.hidandseek.ui.type.TypeViewModel

private const val ROUTE_TYPE = "type"
private const val ROUTE_CHAT = "chat"
private const val ROUTE_SETTINGS = "settings"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HidAndSeekApp(controller: HidController) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route

    val typeViewModel: TypeViewModel = viewModel(
        factory = viewModelFactory { initializer { TypeViewModel(controller) } },
    )

    val transportState by controller.transport.state.collectAsState()
    var showDevicePicker by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted[Manifest.permission.BLUETOOTH_CONNECT] == true) {
            typeViewModel.registerAsKeyboard()
            showDevicePicker = true
        }
    }

    // Settings is a full-screen destination, never a bottom-bar tab.
    val showBottomBar = route != ROUTE_SETTINGS

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (route) {
                            ROUTE_CHAT -> "Chat"
                            ROUTE_SETTINGS -> "Settings"
                            else -> "Type"
                        },
                    )
                },
                actions = {
                    ConnectionChip(
                        state = transportState,
                        onClick = {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.BLUETOOTH_CONNECT,
                                    Manifest.permission.BLUETOOTH_SCAN,
                                    Manifest.permission.BLUETOOTH_ADVERTISE,
                                ),
                            )
                        },
                    )
                    IconButton(onClick = { navController.navigate(ROUTE_SETTINGS) }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    NavigationBarItem(
                        selected = route == ROUTE_TYPE,
                        onClick = { navController.navigateToTab(ROUTE_TYPE) },
                        icon = { Icon(Icons.Default.Keyboard, contentDescription = null) },
                        label = { Text("Type") },
                    )
                    NavigationBarItem(
                        selected = route == ROUTE_CHAT,
                        onClick = { navController.navigateToTab(ROUTE_CHAT) },
                        icon = { Icon(Icons.Outlined.Chat, contentDescription = null) },
                        label = { Text("Chat") },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = ROUTE_TYPE,
            modifier = Modifier.padding(padding),
        ) {
            composable(ROUTE_TYPE) { TypeScreen(typeViewModel) }
            composable(ROUTE_CHAT) { NotBuiltYet("Chat") }
            composable(ROUTE_SETTINGS) { NotBuiltYet("Settings") }
        }
    }

    if (showDevicePicker) {
        DevicePickerSheet(
            devices = typeViewModel.bondedDevices(),
            sheetState = rememberModalBottomSheetState(),
            onDismiss = { showDevicePicker = false },
            onSelect = { target ->
                typeViewModel.connect(target.address, target.name)
                showDevicePicker = false
            },
        )
    }
}

@Composable
private fun ConnectionChip(state: TransportState, onClick: () -> Unit) {
    val label = when (state) {
        TransportState.CONNECTED -> "Connected"
        TransportState.CONNECTING -> "Connecting…"
        TransportState.REGISTERED -> "Ready to pair"
        else -> "Not connected"
    }
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        colors = AssistChipDefaults.assistChipColors(),
        modifier = Modifier.padding(end = 4.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DevicePickerSheet(
    devices: List<dev.cwtf.hidandseek.hid.HidTarget>,
    sheetState: androidx.compose.material3.SheetState,
    onDismiss: () -> Unit,
    onSelect: (dev.cwtf.hidandseek.hid.HidTarget) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                "Connect to",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )

            if (devices.isEmpty()) {
                // Pairing runs backwards from what users expect: the host has to
                // initiate, because this phone is the keyboard.
                Text(
                    "No paired devices yet.\n\nOn the computer you want to type into, " +
                        "open its Bluetooth settings and add a new device — this phone " +
                        "appears there as \"HID & Seek\".",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            } else {
                LazyColumn {
                    items(devices) { device ->
                        ListItem(
                            headlineContent = { Text(device.name) },
                            supportingContent = { Text(device.address) },
                            modifier = Modifier.clickable { onSelect(device) },
                        )
                    }
                }
            }

            TextButton(
                onClick = onDismiss,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text("Close")
            }
        }
    }
}

@Composable
private fun NotBuiltYet(name: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(name, style = MaterialTheme.typography.headlineSmall)
        Text(
            "Not built yet — see SPEC.md.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun androidx.navigation.NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
