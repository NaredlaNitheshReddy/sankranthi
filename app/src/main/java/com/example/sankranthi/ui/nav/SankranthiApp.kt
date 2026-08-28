package com.example.sankranthi.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.sankranthi.data.model.Profile
import com.example.sankranthi.data.repo.SessionState
import com.example.sankranthi.ui.admin.AdminScreen
import com.example.sankranthi.ui.admin.AdminViewModel
import com.example.sankranthi.ui.auth.PendingApprovalScreen
import com.example.sankranthi.ui.auth.SessionViewModel
import com.example.sankranthi.ui.auth.SignInScreen
import com.example.sankranthi.ui.dashboard.DashboardScreen
import com.example.sankranthi.ui.expenses.ExpensesScreen
import com.example.sankranthi.ui.ledger.LedgerViewModel
import com.example.sankranthi.ui.livestock.LivestockScreen

private const val ROUTE_DASHBOARD = "dashboard"
private const val ROUTE_LIVESTOCK = "livestock"
private const val ROUTE_EXPENSES = "expenses"
private const val ROUTE_ADMIN = "admin"

private data class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

/**
 * Root of the app. Which of the three shells you get — sign-in, waiting room, or
 * the books — is decided solely by the session, so an unapproved account can
 * never reach the data screens.
 */
@Composable
fun SankranthiApp(modifier: Modifier = Modifier) {
    val sessionViewModel: SessionViewModel = viewModel()
    val session by sessionViewModel.session.collectAsStateWithLifecycle()
    val signInState by sessionViewModel.signIn.collectAsStateWithLifecycle()

    when (val current = session) {
        is SessionState.Loading -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }

        is SessionState.SignedOut -> SignInScreen(
            state = signInState,
            demoBackend = sessionViewModel.demoBackend,
            onGoogleSignIn = sessionViewModel::signInWithGoogle,
            onDemoSignIn = sessionViewModel::signInAsDemo,
            onDismissError = sessionViewModel::dismissError,
            modifier = modifier,
        )

        is SessionState.SignedIn ->
            if (current.profile.isApproved) {
                ApprovedShell(
                    profile = current.profile,
                    onSignOut = sessionViewModel::signOut,
                    modifier = modifier,
                )
            } else {
                PendingApprovalScreen(
                    profile = current.profile,
                    onRefresh = sessionViewModel::refresh,
                    onSignOut = sessionViewModel::signOut,
                    modifier = modifier,
                )
            }
    }
}

/** The signed-in, approved experience. The admin tab appears only for admins. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApprovedShell(
    profile: Profile,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ledgerViewModel: LedgerViewModel = viewModel()
    val ledgerState by ledgerViewModel.state.collectAsStateWithLifecycle()

    val adminViewModel: AdminViewModel? = if (profile.isAdmin) viewModel() else null

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination

    val destinations = buildList {
        add(TopLevelDestination(ROUTE_DASHBOARD, "Overview", Icons.Filled.Dashboard))
        add(TopLevelDestination(ROUTE_LIVESTOCK, "Livestock", Icons.Filled.Pets))
        add(TopLevelDestination(ROUTE_EXPENSES, "Expenses", Icons.Filled.Receipt))
        if (profile.isAdmin) {
            add(TopLevelDestination(ROUTE_ADMIN, "Admin", Icons.Filled.AdminPanelSettings))
        }
    }

    val pendingCount = adminViewModel
        ?.state
        ?.collectAsStateWithLifecycle()
        ?.value
        ?.pending
        ?.size
        ?: 0

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Sankranthi") },
                actions = {
                    IconButton(onClick = onSignOut) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Sign out")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                destinations.forEach { destination ->
                    val selected = currentRoute?.hierarchy?.any { it.route == destination.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            if (destination.route == ROUTE_ADMIN && pendingCount > 0) {
                                BadgedBox(badge = { Badge { Text(pendingCount.toString()) } }) {
                                    Icon(destination.icon, contentDescription = null)
                                }
                            } else {
                                Icon(destination.icon, contentDescription = null)
                            }
                        },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = ROUTE_DASHBOARD,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(ROUTE_DASHBOARD) {
                DashboardScreen(
                    profile = profile,
                    state = ledgerState,
                    onDismissError = ledgerViewModel::dismissError,
                )
            }
            composable(ROUTE_LIVESTOCK) {
                LivestockScreen(
                    profile = profile,
                    state = ledgerState,
                    onSave = { entry, onDone -> ledgerViewModel.saveLivestock(entry, onDone) },
                    onDelete = ledgerViewModel::deleteLivestock,
                    onDismissError = ledgerViewModel::dismissError,
                )
            }
            composable(ROUTE_EXPENSES) {
                ExpensesScreen(
                    profile = profile,
                    state = ledgerState,
                    onSave = { expense, onDone -> ledgerViewModel.saveExpense(expense, onDone) },
                    onDelete = ledgerViewModel::deleteExpense,
                    onDismissError = ledgerViewModel::dismissError,
                )
            }
            if (adminViewModel != null) {
                composable(ROUTE_ADMIN) {
                    val adminState by adminViewModel.state.collectAsStateWithLifecycle()
                    AdminScreen(
                        currentUser = profile,
                        state = adminState,
                        onApprove = adminViewModel::approve,
                        onReject = adminViewModel::reject,
                        onRevoke = adminViewModel::revoke,
                        onTogglePermission = adminViewModel::togglePermission,
                        onSetRole = adminViewModel::setRole,
                        onDismissError = adminViewModel::dismissError,
                    )
                }
            }
        }
    }
}
