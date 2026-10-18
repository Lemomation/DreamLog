package com.lemomation.dreamlog.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lemomation.dreamlog.ui.theme.DreamColors

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        thickness = 1.dp,
        color = DreamColors.Border,
    )
}

/**
 * A flat, sharp-cornered control. No elevation, fill, animation, or rounded edge.
 * [strong] uses the primary text color for the hairline; otherwise the divider color is used.
 */
@Composable
fun FlatButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    strong: Boolean = false,
    textColor: Color = if (enabled) DreamColors.TextPrimary else DreamColors.TextSecondary,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 44.dp)
            .border(
                width = 1.dp,
                color = when {
                    !enabled -> DreamColors.Border
                    strong -> DreamColors.TextPrimary
                    else -> DreamColors.Border
                },
            )
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            letterSpacing = 0.3.sp,
        )
    }
}

@Composable
fun TextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = if (enabled) DreamColors.TextPrimary else DreamColors.TextSecondary,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .defaultMinSize(minHeight = 36.dp)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 13.sp,
            letterSpacing = 0.2.sp,
        )
    }
}

@Composable
fun FlatRow(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        content()
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        color = DreamColors.TextSecondary,
        fontSize = 10.sp,
        letterSpacing = 1.4.sp,
    )
}

@Composable
fun MonoNotice(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier
            .background(DreamColors.Surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        color = DreamColors.TextSecondary,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    )
}
