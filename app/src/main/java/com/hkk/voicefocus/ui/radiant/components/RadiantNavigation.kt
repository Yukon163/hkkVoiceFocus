// Adapted from AHUTong RadiantBottomNavBar at 0492acc076c81533a8238acd1b796c2c6e0fe488.
// SPDX-License-Identifier: GPL-3.0-only
package com.hkk.voicefocus.ui.radiant.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hkk.voicefocus.ui.LocalContentBackdrop

@Composable
fun BoxScope.RadiantNavigation(selected: Int, onSelected: (Int) -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(vertical = 16.dp).navigationBarsPadding()) {
        LiquidBottomTabs(selectedTabIndex = { selected }, onTabSelected = onSelected,
            backdrop = LocalContentBackdrop.current, tabsCount = 3,
            modifier = Modifier.padding(horizontal = 36.dp), content = content)
    }
}
