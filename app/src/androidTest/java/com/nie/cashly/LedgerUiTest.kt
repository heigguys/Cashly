package com.nie.cashly

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class LedgerUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun manualAddEditDeleteWithoutNotificationPermission() {
        val party = "UI-test-" + java.util.UUID.randomUUID().toString().take(8)
        ui.waitUntil(10000) { ui.onAllNodesWithContentDescription("记一笔").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithContentDescription("记一笔").performClick()
        ui.onNodeWithText("金额（元）").performTextInput("12.34")
        ui.onNodeWithText("交易对象（可选）").performScrollTo().performTextInput(party)
        ui.onNodeWithText("保存账目").performScrollTo().performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText("记下这一笔").fetchSemanticsNodes().isEmpty() }
        ui.onNodeWithText("账单").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText(party).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText(party).performClick()
        ui.onNodeWithText("金额（元）").performTextClearance()
        ui.onNodeWithText("金额（元）").performTextInput("20.00")
        ui.onNodeWithText("保存账目").performScrollTo().performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText("交易详情").fetchSemanticsNodes().isEmpty() }
        ui.onNodeWithText(party).performClick()
        ui.onNodeWithText("金额（元）").assertTextContains("20.00")
        ui.onNodeWithText("删除这笔账目").performScrollTo().performClick()
        ui.onNodeWithText("删除", useUnmergedTree = true).performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText(party).fetchSemanticsNodes().isEmpty() }
    }
}
