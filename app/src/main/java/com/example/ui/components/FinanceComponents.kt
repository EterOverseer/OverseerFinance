package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BusinessCenter
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.theme.ExpenseCoral
import com.example.ui.theme.IncomeEmerald
import com.example.ui.theme.NumericMediumStyle
import com.example.ui.theme.SpacingTokens

@Composable
fun OverseerCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.65f),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = containerColor),
            border = BorderStroke(1.dp, borderColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier.padding(SpacingTokens.lg),
                content = content
            )
        }
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = containerColor),
            border = BorderStroke(1.dp, borderColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier.padding(SpacingTokens.lg),
                content = content
            )
        }
    }
}

@Composable
fun StatusBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.16f),
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f))
    ) {
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun EmptyStateCard(
    icon: ImageVector,
    title: String,
    description: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    OverseerCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (actionLabel != null && onAction != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Button(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
fun DestructiveConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.testTag("confirm_destructive_button")
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("ยกเลิก")
            }
        }
    )
}

data class MonthlyChartPoint(
    val label: String,
    val incomeSatang: Long,
    val expenseSatang: Long
)

@Composable
fun IncomeExpenseBarChart(
    points: List<MonthlyChartPoint>,
    modifier: Modifier = Modifier
) {
    if (points.isEmpty() || points.all { it.incomeSatang == 0L && it.expenseSatang == 0L }) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(140.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "ยังไม่มีข้อมูลรายรับ-รายจ่ายในกราฟ",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val maxVal = points.maxOf { maxOf(it.incomeSatang, it.expenseSatang) }.coerceAtLeast(1L).toFloat()
    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .padding(top = 8.dp)
        ) {
            val groupWidth = size.width / points.size
            val barWidth = (groupWidth * 0.28f).coerceAtMost(24f)
            val gap = barWidth * 0.35f
            val chartHeight = size.height - 8f

            points.forEachIndexed { idx, pt ->
                val centerX = groupWidth * idx + groupWidth / 2f
                val incH = (pt.incomeSatang / maxVal) * chartHeight
                val expH = (pt.expenseSatang / maxVal) * chartHeight

                if (incH > 0f) {
                    drawRoundRect(
                        color = IncomeEmerald,
                        topLeft = Offset(centerX - barWidth - gap / 2f, chartHeight - incH),
                        size = Size(barWidth, incH),
                        cornerRadius = CornerRadius(6f, 6f)
                    )
                }
                if (expH > 0f) {
                    drawRoundRect(
                        color = ExpenseCoral,
                        topLeft = Offset(centerX + gap / 2f, chartHeight - expH),
                        size = Size(barWidth, expH),
                        cornerRadius = CornerRadius(6f, 6f)
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            points.forEach { pt ->
                Text(
                    text = pt.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LegendDot(color = IncomeEmerald, label = "รายรับ")
            LegendDot(color = ExpenseCoral, label = "รายจ่าย")
        }
    }
}

@Composable
fun SpendingTrendLineChart(
    points: List<MonthlyChartPoint>,
    modifier: Modifier = Modifier
) {
    if (points.size < 2 || points.all { it.expenseSatang == 0L }) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(110.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "ต้องมีข้อมูลรายจ่ายอย่างน้อย 2 เดือนเพื่อแสดงเส้นแนวโน้ม",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    val lineColor = MaterialTheme.colorScheme.primary
    val maxVal = points.maxOf { it.expenseSatang }.coerceAtLeast(1L).toFloat()

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            val stepX = size.width / (points.size - 1).coerceAtLeast(1)
            val coords = points.mapIndexed { i, p ->
                val x = i * stepX
                val y = size.height - ((p.expenseSatang / maxVal) * (size.height - 16f)) - 8f
                Offset(x, y)
            }
            val linePath = Path().apply {
                moveTo(coords.first().x, coords.first().y)
                for (i in 1 until coords.size) {
                    lineTo(coords[i].x, coords[i].y)
                }
            }
            val fillPath = Path().apply {
                addPath(linePath)
                lineTo(coords.last().x, size.height)
                lineTo(coords.first().x, size.height)
                close()
            }
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(lineColor.copy(alpha = 0.28f), Color.Transparent)
                )
            )
            drawPath(
                path = linePath,
                color = lineColor,
                style = Stroke(width = 5f, cap = StrokeCap.Round)
            )
            coords.forEach { pt ->
                drawCircle(color = lineColor, radius = 7f, center = pt)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            points.forEach {
                Text(
                    text = it.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun SectionHeaderRow(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (trailing != null) trailing()
    }
}

fun categoryIconForKey(key: String): ImageVector = when (key) {
    "work" -> Icons.Rounded.Work
    "storefront" -> Icons.Rounded.Storefront
    "laptop" -> Icons.Rounded.Laptop
    "business" -> Icons.Rounded.BusinessCenter
    "payments" -> Icons.Rounded.Payments
    "restaurant" -> Icons.Rounded.Restaurant
    "directions_car" -> Icons.Rounded.DirectionsCar
    "shopping_bag" -> Icons.Rounded.ShoppingBag
    "bolt" -> Icons.Rounded.Bolt
    "home" -> Icons.Rounded.Home
    "movie" -> Icons.Rounded.Movie
    "inventory_2" -> Icons.Rounded.Inventory2
    "account_balance" -> Icons.Rounded.AccountBalance
    else -> Icons.Rounded.ReceiptLong
}

@Composable
fun AmountText(
    formatted: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Text(
        text = formatted,
        style = NumericMediumStyle,
        color = color,
        modifier = modifier
    )
}
