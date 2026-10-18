package com.lemomation.dreamlog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lemomation.dreamlog.DreamLogViewModel
import com.lemomation.dreamlog.alarm.AlarmCommands
import com.lemomation.dreamlog.ui.components.Hairline
import com.lemomation.dreamlog.ui.screens.AlarmSetupScreen
import com.lemomation.dreamlog.ui.screens.GraphScreen
import com.lemomation.dreamlog.ui.screens.HostageAlarmScreen
import com.lemomation.dreamlog.ui.screens.NoteEditorScreen
import com.lemomation.dreamlog.ui.screens.NotesScreen
import com.lemomation.dreamlog.ui.theme.DreamColors

private const val NOTES_ROUTE = "notes"
private const val GRAPH_ROUTE = "graph?focusConceptId={focusConceptId}"
private const val ALARM_ROUTE = "alarm"
private const val HOSTAGE_ROUTE = "hostage"
private const val NOTE_ROUTE = "note/{noteId}"

@Composable
fun DreamLogApp(
    viewModel: DreamLogViewModel,
    startWithAlarm: Boolean,
    alarmRequested: Boolean,
    onPrepareAlarmWindow: () -> Unit,
    onAlarmFinished: () -> Unit,
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val configState by viewModel.config.collectAsStateWithLifecycle()
    val config = configState ?: com.lemomation.dreamlog.data.AlarmConfig()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    fun goToRoot(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun finishHostage() {
        onAlarmFinished()
        navController.navigate(NOTES_ROUTE) {
            popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
            launchSingleTop = true
        }
    }

    LaunchedEffect(alarmRequested) {
        if (alarmRequested) {
            onPrepareAlarmWindow()
            navController.navigate(HOSTAGE_ROUTE) {
                launchSingleTop = true
            }
        }
    }

    val showBottomBar = currentRoute == NOTES_ROUTE ||
        currentRoute == ALARM_ROUTE ||
        currentRoute?.startsWith("graph") == true

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DreamColors.Background),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            NavHost(
                navController = navController,
                startDestination = if (startWithAlarm) HOSTAGE_ROUTE else NOTES_ROUTE,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable(NOTES_ROUTE) {
                    NotesScreen(
                        viewModel = viewModel,
                        onOpenNote = { id -> navController.navigate("note/$id") },
                    )
                }

                composable(
                    route = NOTE_ROUTE,
                    arguments = listOf(navArgument("noteId") { type = NavType.LongType }),
                ) { entry ->
                    val noteId = entry.arguments?.getLong("noteId") ?: return@composable
                    NoteEditorScreen(
                        noteId = noteId,
                        viewModel = viewModel,
                        onBack = { navController.popBackStack() },
                        onOpenNote = { id -> navController.navigate("note/$id") },
                        onOpenConcept = { conceptId ->
                            navController.navigate("graph?focusConceptId=$conceptId") {
                                launchSingleTop = true
                            }
                        },
                    )
                }

                composable(
                    route = GRAPH_ROUTE,
                    arguments = listOf(
                        navArgument("focusConceptId") {
                            type = NavType.LongType
                            defaultValue = -1L
                        },
                    ),
                ) { entry ->
                    val focus = entry.arguments?.getLong("focusConceptId") ?: -1L
                    GraphScreen(
                        viewModel = viewModel,
                        focusConceptId = focus.takeIf { it > 0L },
                        onOpenNote = { id -> navController.navigate("note/$id") },
                    )
                }

                composable(ALARM_ROUTE) {
                    AlarmSetupScreen(
                        viewModel = viewModel,
                        onTriggerTestAlarm = {
                            AlarmCommands.start(context)
                            onPrepareAlarmWindow()
                            navController.navigate(HOSTAGE_ROUTE) {
                                launchSingleTop = true
                            }
                        },
                    )
                }

                composable(HOSTAGE_ROUTE) {
                    HostageAlarmScreen(
                        config = config,
                        viewModel = viewModel,
                        onFinished = ::finishHostage,
                    )
                }
            }
        }

        if (showBottomBar) {
            BottomBar(
                currentRoute = currentRoute,
                onNavigate = { route ->
                    when (route) {
                        NOTES_ROUTE -> goToRoot(NOTES_ROUTE)
                        GRAPH_ROUTE -> goToRoot("graph")
                        ALARM_ROUTE -> goToRoot(ALARM_ROUTE)
                    }
                },
            )
        }
    }
}

@Composable
private fun BottomBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
) {
    val active = when {
        currentRoute == NOTES_ROUTE -> NOTES_ROUTE
        currentRoute?.startsWith("graph") == true -> GRAPH_ROUTE
        currentRoute == ALARM_ROUTE -> ALARM_ROUTE
        else -> NOTES_ROUTE
    }

    Hairline()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DreamColors.Background)
            .navigationBarsPadding()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BottomTab(
            label = "Notes",
            selected = active == NOTES_ROUTE,
            modifier = Modifier.weight(1f),
            onClick = { onNavigate(NOTES_ROUTE) },
        )
        BottomTab(
            label = "Graph",
            selected = active == GRAPH_ROUTE,
            modifier = Modifier.weight(1f),
            onClick = { onNavigate(GRAPH_ROUTE) },
        )
        BottomTab(
            label = "Alarm",
            selected = active == ALARM_ROUTE,
            modifier = Modifier.weight(1f),
            onClick = { onNavigate(ALARM_ROUTE) },
        )
    }
}

@Composable
private fun BottomTab(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) DreamColors.TextPrimary else DreamColors.TextSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
    }
}
