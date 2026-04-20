package com.nierduolong.morningbell.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 仅保留数字，限制位数，便于「时/分」可删空再输入 */
internal fun sanitizeClockDigits(
    raw: String,
    maxDigits: Int,
): String = raw.filter { it.isDigit() }.take(maxDigits)

@Composable
fun RowFields(
    hourText: String,
    minuteText: String,
    onHourTextChange: (String) -> Unit,
    onMinuteTextChange: (String) -> Unit,
    hourError: Boolean = false,
    minuteError: Boolean = false,
    hourSupportingText: String? = null,
    minuteSupportingText: String? = null,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = hourText,
            onValueChange = { raw -> onHourTextChange(sanitizeClockDigits(raw, 2)) },
            label = { Text("时") },
            modifier = Modifier.weight(1f),
            singleLine = true,
            isError = hourError,
            supportingText =
                hourSupportingText?.let {
                    {
                        Text(it)
                    }
                },
        )
        OutlinedTextField(
            value = minuteText,
            onValueChange = { raw -> onMinuteTextChange(sanitizeClockDigits(raw, 2)) },
            label = { Text("分") },
            modifier = Modifier.weight(1f),
            singleLine = true,
            isError = minuteError,
            supportingText =
                minuteSupportingText?.let {
                    {
                        Text(it)
                    }
                },
        )
    }
}
