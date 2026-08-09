package dev.cwtf.hidandseek.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.cwtf.hidandseek.AppContainer
import dev.cwtf.hidandseek.SharedContent
import dev.cwtf.hidandseek.data.DeviceRecord
import dev.cwtf.hidandseek.hid.TransportState
import dev.cwtf.hidandseek.ui.settings.AboutScreen
import dev.cwtf.hidandseek.ui.settings.AppearanceSettingsScreen
import dev.cwtf.hidandseek.ui.settings.ConnectionSettingsScreen
import dev.cwtf.hidandseek.ui.settings.DeviceDetailScreen
import dev.cwtf.hidandseek.ui.chat.ChatScreen
import dev.cwtf.hidandseek.ui.chat.ChatViewModel
import dev.cwtf.hidandseek.ui.chat.ConversationDrawer
import dev.cwtf.hidandseek.ui.settings.AgentSettingsScreen
import dev.cwtf.hidandseek.ui.settings.DataSettingsScreen
import dev.cwtf.hidandseek.ui.settings.DevicesScreen
import dev.cwtf.hidandseek.ui.settings.LiveSettingsScreen
import dev.cwtf.hidandseek.ui.settings.LlmProviderEditorScreen
import dev.cwtf.hidandseek.ui.settings.LlmProvidersScreen
import dev.cwtf.hidandseek.ui.settings.LlmViewModel
import dev.cwtf.hidandseek.ui.settings.ModelPickerScreen
import dev.cwtf.hidandseek.ui.settings.SettingsRootScreen
import dev.cwtf.hidandseek.ui.settings.SettingsRoutes
import dev.cwtf.hidandseek.ui.settings.SettingsViewModel
import dev.cwtf.hidandseek.ui.settings.TypingSettingsScreen
import dev.cwtf.hidandseek.ui.type.TypeScreen
import dev.cwtf.hidandseek.ui.type.TypeViewModel

private const val ROUTE_TYPE = "type"
private const val ROUTE_CHAT = "chat"
private const val DISCOVERABLE_DURATION_SECONDS = 5 * 60
private val PAIRING_PERMISSIONS = arrayOf(
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.BLUETOOTH_ADVERTISE,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HidAndSeekApp(
    container: AppContainer,
    sharedContent: SharedContent? = null,
    onSharedContentConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route

    val typeViewModel: TypeViewModel = viewModel(
        factory = viewModelFactory { initializer { TypeViewModel(container) } },
    )
    val settingsViewModel: SettingsViewModel = viewModel(
        factory = viewModelFactory { initializer { SettingsViewModel(container) } },
    )
    val chatViewModel: ChatViewModel = viewModel(
        factory = viewModelFactory { initializer { ChatViewModel(container) } },
    )
    val llmViewModel: LlmViewModel = viewModel(
        factory = viewModelFactory { initializer { LlmViewModel(container) } },
    )

    val transportState by container.hidController.transport.state.collectAsState()
    val context = LocalContext.current
    var showDevicePicker by remember { mutableStateOf(false) }
    var pickerDevices by remember { mutableStateOf<List<DeviceRecord>>(emptyList()) }

    // Pairing is initiated by the host, so Android can add a bond while this
    // sheet is already open. bondedDevices() is a platform snapshot rather
    // than observable state; poll only while the picker is visible so a newly
    // paired host appears without closing and reopening it.
    LaunchedEffect(showDevicePicker) {
        if (!showDevicePicker) return@LaunchedEffect
        while (true) {
            pickerDevices = typeViewModel.pickerDevices()
            delay(1_000)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted[Manifest.permission.BLUETOOTH_CONNECT] == true) {
            typeViewModel.registerAsKeyboard()
            showDevicePicker = true
        }
    }
    val discoverableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {}
    val launchDiscoverabilityPrompt = {
        typeViewModel.registerAsKeyboard()
        discoverableLauncher.launch(
            Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(
                BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,
                DISCOVERABLE_DURATION_SECONDS,
            ),
        )
    }
    val discoverabilityPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (context.hasPairingPermissions()) launchDiscoverabilityPrompt()
    }
    val makeDiscoverable = {
        if (context.hasPairingPermissions()) {
            launchDiscoverabilityPrompt()
        } else {
            discoverabilityPermissionLauncher.launch(PAIRING_PERMISSIONS)
        }
    }

    // Something shared in from elsewhere lands in the chat composer, on the
    // chat tab, ready to send — not in a holding area to be found later.
    LaunchedEffect(sharedContent) {
        when (sharedContent) {
            is SharedContent.Image -> chatViewModel.attach(sharedContent.uri)
            is SharedContent.Text -> chatViewModel.onComposerChange(sharedContent.text)
            null -> return@LaunchedEffect
        }
        navController.navigateToTab(ROUTE_CHAT)
        onSharedContentConsumed()
    }

    val inSettings = SettingsRoutes.isSettings(route)
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // Width, not device type: a foldable opened out should get the rail too.
    val wideLayout = LocalConfiguration.current.screenWidthDp >= 600

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Only the chat tab has a drawer; elsewhere an edge swipe would be a
        // surprise.
        gesturesEnabled = route == ROUTE_CHAT,
        drawerContent = {
            ModalDrawerSheet {
                ConversationDrawer(
                    viewModel = chatViewModel,
                    onConversationChosen = { scope.launch { drawerState.close() } },
                )
            }
        },
    ) {
        Scaffold(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            // Edge-to-edge windows do not resize Compose content automatically.
            // Keep the active screen and its bottom controls above the keyboard.
            .imePadding(),
        topBar = {
            Column {
                LargeFlexibleTopAppBar(
                    title = {
                        Text(
                            when {
                                inSettings -> SettingsRoutes.titleFor(route)
                                route == ROUTE_CHAT -> "Chat"
                                else -> "Type"
                            },
                        )
                    },
                    subtitle = {
                        if (!inSettings) {
                            Text(
                                when (transportState) {
                                    TransportState.CONNECTED -> "Connected to a device"
                                    TransportState.CONNECTING -> "Connecting…"
                                    TransportState.REGISTERED -> "Ready to pair"
                                    else -> "No device connected"
                                },
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        when {
                            inSettings -> IconButton(
                                onClick = { navController.popBackStack() },
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                )
                            }

                            route == ROUTE_CHAT -> IconButton(
                                onClick = { scope.launch { drawerState.open() } },
                            ) {
                                Icon(Icons.Default.Menu, contentDescription = "Conversations")
                            }
                        }
                    },
                    actions = {
                        if (!inSettings) {
                            ConnectionChip(state = transportState) {
                                permissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.BLUETOOTH_CONNECT,
                                        Manifest.permission.BLUETOOTH_SCAN,
                                        Manifest.permission.BLUETOOTH_ADVERTISE,
                                    ),
                                )
                            }
                            IconButton(
                                onClick = { navController.navigate(SettingsRoutes.ROOT) },
                            ) {
                                Icon(Icons.Default.Settings, contentDescription = "Settings")
                            }
                        }
                    },
                )

                // Settings owns the whole screen, and wider windows use the rail instead.
                if (!inSettings && !wideLayout) {
                    PrimaryTabRow(
                        selectedTabIndex = if (route == ROUTE_CHAT) 1 else 0,
                    ) {
                        Tab(
                            selected = route == ROUTE_TYPE,
                            onClick = { navController.navigateToTab(ROUTE_TYPE) },
                            text = { Text("Type") },
                        )
                        Tab(
                            selected = route == ROUTE_CHAT,
                            onClick = { navController.navigateToTab(ROUTE_CHAT) },
                            text = { Text("Chat") },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Row(Modifier.padding(padding)) {
            if (!inSettings && wideLayout) {
                NavigationRailForWideScreens(route) { navController.navigateToTab(it) }
            }

            NavHost(
                navController = navController,
                startDestination = ROUTE_TYPE,
            ) {
            composable(ROUTE_TYPE) { TypeScreen(typeViewModel) }
            composable(ROUTE_CHAT) {
                ChatScreen(
                    viewModel = chatViewModel,
                    onOpenProviderSettings = { navController.navigate(SettingsRoutes.LLM) },
                )
            }

            composable(SettingsRoutes.LLM) {
                LlmProvidersScreen(
                    viewModel = llmViewModel,
                    onOpenProvider = { navController.navigate(SettingsRoutes.llmProvider(it)) },
                )
            }
            composable(SettingsRoutes.LLM_PROVIDER) { entry ->
                val id = entry.arguments?.getString("providerId").orEmpty()
                LlmProviderEditorScreen(
                    viewModel = llmViewModel,
                    providerId = id,
                    onOpenModelPicker = {
                        navController.navigate(SettingsRoutes.llmModels(id))
                    },
                    onDeleted = { navController.popBackStack() },
                )
            }
            composable(SettingsRoutes.LLM_MODELS) { entry ->
                val id = entry.arguments?.getString("providerId").orEmpty()
                ModelPickerScreen(
                    viewModel = llmViewModel,
                    providerId = id,
                    onChosen = { navController.popBackStack() },
                )
            }

            composable(SettingsRoutes.ROOT) {
                SettingsRootScreen(
                    viewModel = settingsViewModel,
                    onNavigate = { navController.navigate(it) },
                )
            }
            composable(SettingsRoutes.DEVICES) {
                DevicesScreen(
                    viewModel = settingsViewModel,
                    onOpenDevice = { address ->
                        navController.navigate(SettingsRoutes.deviceDetail(address))
                    },
                )
            }
            composable(SettingsRoutes.DEVICE_DETAIL) { entry ->
                val address = entry.arguments?.getString("address").orEmpty()
                DeviceDetailScreen(
                    viewModel = settingsViewModel,
                    address = address,
                    onForgotten = { navController.popBackStack() },
                )
            }
            composable(SettingsRoutes.AGENT) { AgentSettingsScreen(settingsViewModel) }
            composable(SettingsRoutes.DATA) { DataSettingsScreen(settingsViewModel) }
            composable(SettingsRoutes.CONNECTION) { ConnectionSettingsScreen(settingsViewModel) }
            composable(SettingsRoutes.TYPING) { TypingSettingsScreen(settingsViewModel) }
            composable(SettingsRoutes.LIVE) { LiveSettingsScreen(settingsViewModel) }
            composable(SettingsRoutes.APPEARANCE) { AppearanceSettingsScreen(settingsViewModel) }
            composable(SettingsRoutes.ABOUT) { AboutScreen() }
            }
        }
        }
    }

    if (showDevicePicker) {
        DevicePickerSheet(
            devices = pickerDevices,
            activeAddress = typeViewModel.activeAddress,
            sheetState = rememberModalBottomSheetState(),
            onDismiss = { showDevicePicker = false },
            onSelect = { device ->
                typeViewModel.connect(device.address, device.name)
                showDevicePicker = false
            },
            onRefresh = { pickerDevices = typeViewModel.pickerDevices() },
            onMakeDiscoverable = makeDiscoverable,
            onDisconnect = {
                typeViewModel.disconnect()
                showDevicePicker = false
            },
            onManageDevices = {
                showDevicePicker = false
                navController.navigate(SettingsRoutes.DEVICES)
            },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
        leadingIcon = if (state == TransportState.CONNECTING) {
            { LoadingIndicator(Modifier.size(18.dp)) }
        } else {
            null
        },
        modifier = Modifier.padding(end = 4.dp),
    )
}

/**
 * Bottom bar on phones, side rail on anything wider.
 *
 * A bottom bar on a tablet wastes the height that the staging area actually
 * wants, and puts the destinations a long way from where the hands are.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NavigationRailForWideScreens(
    route: String?,
    onNavigate: (String) -> Unit,
) {
    WideNavigationRail {
        WideNavigationRailItem(
            railExpanded = false,
            selected = route == ROUTE_TYPE,
            onClick = { onNavigate(ROUTE_TYPE) },
            icon = { Icon(Icons.Default.Keyboard, contentDescription = null) },
            label = { Text("Type") },
        )
        WideNavigationRailItem(
            railExpanded = false,
            selected = route == ROUTE_CHAT,
            onClick = { onNavigate(ROUTE_CHAT) },
            icon = { Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = null) },
            label = { Text("Chat") },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DevicePickerSheet(
    devices: List<DeviceRecord>,
    activeAddress: String?,
    sheetState: androidx.compose.material3.SheetState,
    onDismiss: () -> Unit,
    onSelect: (DeviceRecord) -> Unit,
    onRefresh: () -> Unit,
    onMakeDiscoverable: () -> Unit,
    onDisconnect: () -> Unit,
    onManageDevices: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.navigationBarsPadding()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 12.dp, top = 4.dp),
            ) {
                Text(
                    "Connect to",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRefresh) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text("Refresh")
                }
            }

            if (devices.isEmpty()) {
                // Pairing runs backwards from what users expect: the host has to
                // initiate, because this phone is the keyboard.
                Text(
                    "No paired devices yet.\n\nOn the computer you want to type into, " +
                        "open its Bluetooth settings and add a new device — this phone " +
                        "appears there as a keyboard.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            } else {
                LazyColumn {
                    items(devices) { device ->
                        ListItem(
                            headlineContent = { Text(device.displayName) },
                            supportingContent = {
                                Text(
                                    if (device.address == activeAddress) {
                                        "Connected"
                                    } else {
                                        device.address
                                    },
                                )
                            },
                            modifier = Modifier.clickable { onSelect(device) },
                        )
                    }
                }
            }

            Button(
                onClick = onMakeDiscoverable,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Text("Make discoverable for 5 minutes")
            }

            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                if (activeAddress != null) {
                    TextButton(onClick = onDisconnect) { Text("Disconnect") }
                }
                TextButton(onClick = onManageDevices) { Text("Manage devices") }
            }
        }
    }
}

private fun Context.hasPairingPermissions(): Boolean = PAIRING_PERMISSIONS.all { permission ->
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}

@Composable
private fun NotBuiltYet(name: String, reference: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(name, style = MaterialTheme.typography.headlineSmall)
        Text("Not built yet — see $reference.", style = MaterialTheme.typography.bodyMedium)
    }
}

private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
