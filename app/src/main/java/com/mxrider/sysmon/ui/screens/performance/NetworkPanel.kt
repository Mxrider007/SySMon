package com.mxrider.sysmon.ui.screens.performance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mxrider.sysmon.R
import com.mxrider.sysmon.core.ChartHistory
import com.mxrider.sysmon.core.Format
import com.mxrider.sysmon.core.NetTransport
import com.mxrider.sysmon.ui.components.StatTile
import com.mxrider.sysmon.ui.components.chart.PerfChart
import com.mxrider.sysmon.ui.TokenLabels

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NetworkPanel(
    state: PerfUiState,
    rxHistory: ChartHistory,
    txHistory: ChartHistory,
    modifier: Modifier = Modifier,
) {
    val net = state.network
    val transportText = if (net != null) transportName(net.transport) else DASH

    Column(modifier.fillMaxWidth()) {
        PanelHeader(
            title = stringResource(R.string.perf_tile_network),
            trailing = if (net != null) transportText else null,
        )
        Spacer(Modifier.height(12.dp))

        PerfChart(
            history = rxHistory,
            windowPoints = state.windowPoints,
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp),
            autoScale = true,
            secondaryHistory = txHistory,
            topLeftLabel = stringResource(R.string.perf_axis_throughput),
            bottomLeftLabel = stringResource(R.string.perf_axis_60s),
            bottomRightLabel = stringResource(R.string.perf_axis_0),
            primaryLabel = stringResource(R.string.perf_net_receive),
            secondaryLabel = stringResource(R.string.perf_net_send),
            valueFormatter = { Format.bitsPerSec(it.toDouble()) },
            ceilingFormatter = { Format.bitsPerSec(it.toDouble()) },
            contentDescription = stringResource(
                R.string.perf_cd_network,
                net?.let { Format.bitsPerSec(it.rxBytesPerSec) } ?: DASH,
                net?.let { Format.bitsPerSec(it.txBytesPerSec) } ?: DASH,
            ),
        )

        Spacer(Modifier.height(16.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatTile(
                label = stringResource(R.string.perf_net_receive),
                value = net?.let { Format.bitsPerSec(it.rxBytesPerSec) } ?: DASH,
            )
            StatTile(
                label = stringResource(R.string.perf_net_send),
                value = net?.let { Format.bitsPerSec(it.txBytesPerSec) } ?: DASH,
            )
        }

        Spacer(Modifier.height(24.dp))
        SpecRow(stringResource(R.string.perf_net_connection_type), transportText)
        SpecRow(
            stringResource(R.string.perf_net_name),
            TokenLabels.localized(net?.networkName, TokenLabels::networkName) ?: DASH,
        )
        SpecRow(stringResource(R.string.perf_net_ipv4), net?.ipv4 ?: DASH)
        SpecRow(stringResource(R.string.perf_net_ipv6), net?.ipv6 ?: DASH)
        SpecRow(
            stringResource(R.string.perf_net_total_rx),
            net?.let { Format.bytes(it.totalRxBytes) } ?: DASH,
        )
        SpecRow(
            stringResource(R.string.perf_net_total_tx),
            net?.let { Format.bytes(it.totalTxBytes) } ?: DASH,
        )
    }
}

@Composable
private fun transportName(transport: NetTransport): String = stringResource(
    when (transport) {
        NetTransport.WIFI -> R.string.perf_transport_wifi
        NetTransport.CELLULAR -> R.string.perf_transport_cellular
        NetTransport.ETHERNET -> R.string.perf_transport_ethernet
        NetTransport.VPN -> R.string.perf_transport_vpn
        NetTransport.NONE -> R.string.perf_transport_none
        NetTransport.OTHER -> R.string.perf_transport_other
    },
)
