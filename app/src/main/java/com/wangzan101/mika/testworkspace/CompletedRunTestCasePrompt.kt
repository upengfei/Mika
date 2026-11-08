package com.wangzan101.mika.testworkspace

import android.widget.Toast
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.R
import com.wangzan101.mika.core.automation.TestTaskContracts
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.ui.ChatViewModel
import com.wangzan101.mika.ui.ExchangeStatus
import com.wangzan101.mika.ui.components.MikaDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** MP-only prompt for turning a successful exploratory run into a reusable test case. */
@Composable
fun CompletedRunTestCasePrompt(vm: ChatViewModel) {
    if (BuildConfig.BUILD_TYPE != "workspace") return

    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Pair<String, List<RunEvent.Activity>>?>(null) }
    var saving by remember { mutableStateOf(false) }
    val promptedRuns = remember(vm) {
        mutableStateOf(ui.exchanges.filter { it.status is ExchangeStatus.Done }.mapNotNull { it.runId }.toSet())
    }

    LaunchedEffect(ui.exchanges) {
        val fresh = ui.exchanges.firstOrNull { exchange ->
            exchange.live && exchange.status is ExchangeStatus.Done && exchange.runId != null &&
                exchange.runId !in promptedRuns.value && exchange.activities.any { it.kind == "act" && !it.failed } &&
                !exchange.goal.contains("Test case id:") && !exchange.goal.contains("Test task id:")
        }
        if (fresh != null) {
            promptedRuns.value = promptedRuns.value + requireNotNull(fresh.runId)
            pending = fresh.goal to fresh.activities
        }
    }

    pending?.let { (goal, activities) ->
        MikaDialog(
            onDismissRequest = { if (!saving) pending = null },
            title = { Text(stringResource(R.string.test_case_capture_title)) },
            text = { Text(stringResource(R.string.test_case_capture_body, activities.count { it.kind == "act" && !it.failed })) },
            dismissButton = {
                TextButton(enabled = !saving, onClick = { pending = null }) {
                    Text(stringResource(R.string.test_case_capture_skip))
                }
            },
            confirmButton = {
                TextButton(enabled = !saving, onClick = {
                    scope.launch {
                        saving = true
                        val result = try {
                            withContext(Dispatchers.IO) {
                                val contract = TestCaseDraft.fromRun(goal, activities, "captured-${UUID.randomUUID()}")
                                    ?: return@withContext false
                                TestTaskContracts.requireValid(contract)
                                TestCaseRepository(File(context.filesDir, "test-case-repository")).createCase(contract)
                                true
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            false
                        }
                        saving = false
                        pending = null
                        Toast.makeText(
                            context,
                            context.getString(if (result) R.string.test_case_capture_saved else R.string.test_case_capture_failed),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }) {
                    Text(stringResource(if (saving) R.string.test_case_capture_saving else R.string.test_case_capture_confirm))
                }
            },
        )
    }
}
