package com.greenart7c3.citrine.ui.dialogs

import android.app.Activity
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.toEvent
import com.greenart7c3.citrine.ui.CloseButton
import com.greenart7c3.citrine.utils.toDateString
import com.vitorpamplona.ammolite.relays.COMMON_FEED_TYPES
import com.vitorpamplona.ammolite.relays.Client
import com.vitorpamplona.ammolite.relays.Relay
import com.vitorpamplona.ammolite.relays.RelayPool
import com.vitorpamplona.ammolite.relays.RelaySetupInfo
import com.vitorpamplona.ammolite.service.HttpClientManager
import com.vitorpamplona.quartz.encoders.bechToBytes
import com.vitorpamplona.quartz.encoders.toHexKey
import com.vitorpamplona.quartz.events.AdvertisedRelayListEvent
import com.vitorpamplona.quartz.events.ContactListEvent
import com.vitorpamplona.quartz.signers.ExternalSignerLauncher
import com.vitorpamplona.quartz.signers.NostrSignerExternal
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ContactsDialog(pubKey: String, onClose: () -> Unit) {
    var loading by remember {
        mutableStateOf(true)
    }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val signer = NostrSignerExternal(
        pubKey.bechToBytes().toHexKey(),
        ExternalSignerLauncher(pubKey, ""),
    )
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result ->
            if (result.resultCode != Activity.RESULT_OK) {
                Toast.makeText(
                    context,
                    context.getString(R.string.sign_request_rejected),
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                result.data?.let {
                    coroutineScope.launch(Dispatchers.IO) {
                        signer.launcher.newResult(it)
                    }
                }
            }
        },
    )
    signer.launcher.registerLauncher(
        launcher = {
            try {
                launcher.launch(it)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("Signer", "Error opening Signer app", e)
                coroutineScope.launch {
                    Toast.makeText(
                        context,
                        context.getString(R.string.make_sure_the_signer_application_has_authorized_this_transaction),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        },
        contentResolver = { context.contentResolver },
    )

    val events = mutableListOf<ContactListEvent>()
    var outboxRelays: AdvertisedRelayListEvent? = null
    LaunchedEffect(Unit) {
        coroutineScope.launch(Dispatchers.IO) {
            loading = true
            val dataBaseEvents = AppDatabase.getDatabase(context).eventDao().getContactLists(pubKey.bechToBytes().toHexKey())
            dataBaseEvents.forEach {
                events.add(it.toEvent() as ContactListEvent)
            }
            val dataBaseOutboxRelays = AppDatabase.getDatabase(context).eventDao().getAdvertisedRelayList(pubKey.bechToBytes().toHexKey())
            outboxRelays = dataBaseOutboxRelays?.toEvent() as? AdvertisedRelayListEvent

            loading = false
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            Modifier.fillMaxSize(),
        ) {
            if (loading) {
                Column(
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                var useProxy by remember {
                    mutableStateOf(false)
                }
                var proxyPort by remember {
                    mutableStateOf(TextFieldValue("9050"))
                }

                Column(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.background)
                        .fillMaxSize(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CloseButton(
                            onCancel = onClose,
                        )
                    }

                    if (events.isEmpty()) {
                        Column(
                            Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                stringResource(R.string.no_follow_list_found),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }

                    LazyColumn(
                        Modifier.fillMaxSize(),
                    ) {
                        item {
                            Column {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 4.dp)
                                        .clickable {
                                            useProxy = !useProxy
                                        },
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        modifier = Modifier.weight(1f),
                                        text = stringResource(R.string.use_proxy),
                                    )
                                    Switch(
                                        checked = useProxy,
                                        onCheckedChange = {
                                            useProxy = !useProxy
                                        },
                                    )
                                }
                                OutlinedTextField(
                                    proxyPort,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(4.dp),
                                    onValueChange = {
                                        if (it.text.toIntOrNull() == null) {
                                            Toast.makeText(
                                                context,
                                                "Invalid port",
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                            return@OutlinedTextField
                                        }
                                        proxyPort = it
                                    },
                                    label = {
                                        Text(stringResource(R.string.proxy_port))
                                    },
                                )
                            }
                        }
                        items(events.size) {
                            val event = events[it]
                            Card(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(4.dp),
                            ) {
                                Text(
                                    "Date: ${event.createdAt.toDateString()}",
                                    modifier = Modifier.padding(
                                        start = 6.dp,
                                        end = 6.dp,
                                        top = 6.dp,
                                    ),
                                )
                                Text(
                                    "Following: ${event.verifiedFollowKeySet().size}",
                                    modifier = Modifier.padding(horizontal = 6.dp),
                                )
                                Text(
                                    "Communities: ${event.verifiedFollowAddressSet().size}",
                                    modifier = Modifier.padding(horizontal = 6.dp),
                                )
                                Text(
                                    "Hashtags: ${event.countFollowTags()}",
                                    modifier = Modifier.padding(horizontal = 6.dp),
                                )
                                Text(
                                    "Relays: ${event.relays()?.keys?.size ?: 0}",
                                    modifier = Modifier.padding(horizontal = 6.dp),
                                )
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    ElevatedButton(
                                        onClick = {
                                            val relays = event.relays()
                                            if (relays.isNullOrEmpty() && outboxRelays == null) {
                                                coroutineScope.launch {
                                                    Toast.makeText(
                                                        context,
                                                        context.getString(R.string.no_relays_found),
                                                        Toast.LENGTH_SHORT,
                                                    ).show()
                                                }
                                                return@ElevatedButton
                                            }

                                            ContactListEvent.create(
                                                event.content,
                                                event.tags,
                                                signer,
                                            ) { signedEvent ->
                                                val outbox = outboxRelays?.writeRelays()?.map { relay ->
                                                    RelaySetupInfo(
                                                        url = relay,
                                                        read = true,
                                                        write = true,
                                                        feedTypes = COMMON_FEED_TYPES,
                                                    )
                                                }
                                                val localRelays = outbox ?: relays?.mapNotNull { relay ->
                                                    if (relay.value.write) {
                                                        RelaySetupInfo(
                                                            relay.key,
                                                            relay.value.read,
                                                            relay.value.write,
                                                            COMMON_FEED_TYPES,
                                                        )
                                                    } else {
                                                        null
                                                    }
                                                }

                                                if (localRelays == null) return@create

                                                coroutineScope.launch(Dispatchers.IO) {
                                                    loading = true
                                                    if (useProxy) {
                                                        HttpClientManager.setDefaultProxyOnPort(proxyPort.text.toInt())
                                                    } else {
                                                        HttpClientManager.setDefaultProxy(null)
                                                    }

                                                    localRelays.forEach { relayInfo ->
                                                        RelayPool.addRelay(Relay(relayInfo.url, relayInfo.read, relayInfo.write, useProxy, relayInfo.feedTypes))
                                                    }
                                                    RelayPool.requestAndWatch()
                                                    delay(1000)
                                                    Client.sendAndWaitForResponse(signedEvent, forceProxy = useProxy, relayList = localRelays)
                                                    RelayPool.disconnect()
                                                    loading = false
                                                    onClose()
                                                }
                                            }
                                        },
                                        modifier = Modifier.padding(
                                            start = 6.dp,
                                            end = 6.dp,
                                            bottom = 6.dp,
                                        ),
                                    ) {
                                        Text(stringResource(R.string.restore))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
