package com.example.sankranthi.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.sankranthi.data.model.AccessStatus
import com.example.sankranthi.data.model.Permission
import com.example.sankranthi.data.model.Profile
import com.example.sankranthi.data.model.Role
import com.example.sankranthi.ui.admin.AdminScreen
import com.example.sankranthi.ui.admin.AdminUiState
import com.example.sankranthi.ui.expenses.ExpensesScreen
import com.example.sankranthi.ui.ledger.LedgerUiState
import com.example.sankranthi.ui.livestock.LivestockScreen
import com.example.sankranthi.ui.theme.SankranthiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdminAndPermissionsUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val admin = Profile(
        id = "admin",
        email = "admin@example.com",
        fullName = "Admin",
        role = Role.ADMIN,
        status = AccessStatus.APPROVED,
    )

    private val request = Profile(
        id = "req",
        email = "lakshmi@example.com",
        fullName = "Lakshmi Devi",
        status = AccessStatus.PENDING,
        requestedAt = "2026-08-20",
    )

    @Test
    fun pendingTab_listsTheRequest() {
        composeTestRule.setContent { AdminScreenUnderTest() }

        composeTestRule.onNodeWithText("Lakshmi Devi").assertIsDisplayed()
        composeTestRule.onNodeWithText("lakshmi@example.com").assertIsDisplayed()
    }

    @Test
    fun approving_reportsOnlyTheTickedPermissions() {
        var approved: Pair<Profile, Set<Permission>>? = null
        composeTestRule.setContent {
            AdminScreenUnderTest(onApprove = { profile, permissions -> approved = profile to permissions })
        }

        composeTestRule.onNodeWithText(Permission.EDIT_EXPENSES.label).performClick()
        composeTestRule.onNodeWithText("Approve").performClick()

        assertEquals(request.id, approved?.first?.id)
        assertEquals(setOf(Permission.EDIT_EXPENSES), approved?.second)
    }

    @Test
    fun approving_withNothingTicked_grantsNoPermissions() {
        var approved: Pair<Profile, Set<Permission>>? = null
        composeTestRule.setContent {
            AdminScreenUnderTest(onApprove = { profile, permissions -> approved = profile to permissions })
        }

        composeTestRule.onNodeWithText("Approve").performClick()

        assertEquals(emptySet<Permission>(), approved?.second)
    }

    @Test
    fun membersTab_isReachable() {
        composeTestRule.setContent { AdminScreenUnderTest() }

        composeTestRule.onNodeWithText("Members").performClick()
        composeTestRule.onNodeWithText("admin@example.com").assertIsDisplayed()
    }

    @Test
    fun livestockScreen_hidesAddEntry_withoutTheLivestockPermission() {
        val member = Profile(
            id = "m",
            email = "member@example.com",
            status = AccessStatus.APPROVED,
            permissions = listOf(Permission.EDIT_EXPENSES.wire),
        )
        composeTestRule.setContent {
            SankranthiTheme {
                LivestockScreen(
                    profile = member,
                    state = LedgerUiState(loading = false),
                    onSave = { _, _ -> },
                    onDelete = {},
                    onDismissError = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Add entry").assertDoesNotExist()
        composeTestRule.onNodeWithText("View only — ask an admin for edit rights.")
            .assertIsDisplayed()
    }

    @Test
    fun livestockScreen_showsAddEntry_withThePermission() {
        val member = Profile(
            id = "m",
            email = "member@example.com",
            status = AccessStatus.APPROVED,
            permissions = listOf(Permission.EDIT_LIVESTOCK.wire),
        )
        composeTestRule.setContent {
            SankranthiTheme {
                LivestockScreen(
                    profile = member,
                    state = LedgerUiState(loading = false),
                    onSave = { _, _ -> },
                    onDelete = {},
                    onDismissError = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Add entry").assertIsDisplayed()
    }

    @Test
    fun expensesScreen_hidesAddExpense_withoutTheExpensesPermission() {
        val member = Profile(
            id = "m",
            email = "member@example.com",
            status = AccessStatus.APPROVED,
            permissions = listOf(Permission.EDIT_LIVESTOCK.wire),
        )
        composeTestRule.setContent {
            SankranthiTheme {
                ExpensesScreen(
                    profile = member,
                    state = LedgerUiState(loading = false),
                    onSave = { _, _ -> },
                    onDelete = {},
                    onDismissError = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Add expense").assertDoesNotExist()
    }

    @Composable
    private fun AdminScreenUnderTest(
        onApprove: (Profile, Set<Permission>) -> Unit = { _, _ -> },
    ) {
        SankranthiTheme {
            AdminScreen(
                currentUser = admin,
                state = AdminUiState(
                    loading = false,
                    pending = listOf(request),
                    members = listOf(admin),
                ),
                onApprove = onApprove,
                onReject = {},
                onRevoke = {},
                onTogglePermission = { _, _, _ -> },
                onSetRole = { _, _ -> },
                onDismissError = {},
            )
        }
    }
}
