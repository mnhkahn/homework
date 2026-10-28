package com.homeworkbuddy

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun HomeworkGradeBadge(result: HomeworkGradeResult, label: String = "最近评分") {
    if (result.grade == HomeworkGrade.PERFECT) {
        val shape = RoundedCornerShape(16.dp)
        Row(
            modifier = Modifier
                .widthIn(min = 194.dp)
                .shadow(5.dp, shape)
                .background(Brush.horizontalGradient(listOf(Color(0xFFFFF7DB), Color(0xFFFFE28A), Color(0xFFFFCD52))), shape)
                .border(1.dp, Color(0xFFCA9223), shape)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(38.dp).background(Color(0xFF9B610A), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.EmojiEvents, contentDescription = "满分奖杯", tint = Color.White, modifier = Modifier.size(25.dp))
            }
            Column {
                Text("$label · 满分", color = Color(0xFF6C4300), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text("100 分", color = Color(0xFF503000), fontSize = 25.sp, fontWeight = FontWeight.Black, lineHeight = 28.sp)
            }
        }
        return
    }
    val (background, foreground) = when (result.grade) {
        HomeworkGrade.PERFECT -> Color(0xFFFFD54F) to Color(0xFF533600)
        HomeworkGrade.EXCELLENT -> Color(0xFFFFE9A6) to Color(0xFF704600)
        HomeworkGrade.GOOD -> Color(0xFFD9EFDE) to Color(0xFF205A32)
        HomeworkGrade.FAIR -> Color(0xFFFFE1BC) to Color(0xFF7C4200)
        HomeworkGrade.NEEDS_PRACTICE -> Color(0xFFFFDAD6) to Color(0xFF8C2824)
    }
    Surface(color = background, contentColor = foreground, shape = RoundedCornerShape(if (result.grade == HomeworkGrade.EXCELLENT) 8.dp else 18.dp)) {
        Text("${result.grade.symbol} $label · ${result.fieldValue}", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontWeight = FontWeight.Bold)
    }
}
