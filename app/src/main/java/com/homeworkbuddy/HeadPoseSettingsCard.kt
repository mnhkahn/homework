package com.homeworkbuddy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HeadPoseSettingsCard() {
    val context = LocalContext.current
    val store = remember { HeadPoseSettings(context) }
    val initial = remember { store.load() }
    var pitchMin by rememberSaveable { mutableStateOf(initial.pitchMin.toString()) }
    var pitchMax by rememberSaveable { mutableStateOf(initial.pitchMax.toString()) }
    var yawMin by rememberSaveable { mutableStateOf(initial.yawMin.toString()) }
    var yawMax by rememberSaveable { mutableStateOf(initial.yawMax.toString()) }
    var distanceMin by rememberSaveable { mutableStateOf(initial.distanceMin.toString()) }
    var distanceMax by rememberSaveable { mutableStateOf(initial.distanceMax.toString()) }
    var trigger by rememberSaveable { mutableStateOf(initial.triggerSeconds.toString()) }
    var recovery by rememberSaveable { mutableStateOf(initial.recoverySeconds.toString()) }
    var sound by rememberSaveable { mutableStateOf(initial.sound) }
    var message by remember { mutableStateOf<String?>(null) }
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("低头提醒", fontSize = 21.sp)
            Text("目前只提醒头过低：默认俯仰角低于 −29° 持续 3 秒后提醒。抬头、左右看和距离变化只显示数值，不提醒，也不判断专注程度。", fontSize = 14.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PoseRange("俯仰参考（仅下限提醒）", pitchMin, { pitchMin = it }, pitchMax, { pitchMax = it })
                PoseRange("转角参考（不提醒）", yawMin, { yawMin = it }, yawMax, { yawMax = it })
                PoseRange("距离参考（不提醒）", distanceMin, { distanceMin = it }, distanceMax, { distanceMax = it })
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(trigger, { trigger = it }, label = { Text("持续超限秒数") }, singleLine = true, modifier = Modifier.width(160.dp))
                OutlinedTextField(recovery, { recovery = it }, label = { Text("恢复正常秒数") }, singleLine = true, modifier = Modifier.width(160.dp))
                Row { Checkbox(sound, { sound = it }); Text("语音提醒（跟随媒体音量）", Modifier.padding(top = 12.dp)) }
            }
            Text("角度范围 −90～90°；距离 0.2～5×；超限 1～60 秒、恢复 1～30 秒。下限必须小于上限。低头超限后播报“头太低了，请抬高一点”，每句结束后间隔 8 秒；恢复正常解除提醒或检测暂停后停止。", fontSize = 12.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    val values = listOf(pitchMin, pitchMax, yawMin, yawMax, distanceMin, distanceMax).map { it.trim().toFloatOrNull() }
                    val t = trigger.trim().toIntOrNull(); val r = recovery.trim().toIntOrNull()
                    val value = if (values.all { it != null } && t != null && r != null) HeadPoseLimits(values[0]!!, values[1]!!, values[2]!!, values[3]!!, values[4]!!, values[5]!!, t, r, sound) else null
                    if (value == null || !value.valid()) message = "请检查范围和秒数，尚未保存。"
                    else { store.save(value); message = "已保存，返回作业页生效。" }
                }) { Text("保存提醒设置") }
                OutlinedButton(onClick = {
                    val d = HeadPoseLimits(); store.save(d)
                    pitchMin = d.pitchMin.toString(); pitchMax = d.pitchMax.toString()
                    yawMin = d.yawMin.toString(); yawMax = d.yawMax.toString()
                    distanceMin = d.distanceMin.toString(); distanceMax = d.distanceMax.toString()
                    trigger = d.triggerSeconds.toString(); recovery = d.recoverySeconds.toString(); sound = d.sound
                    message = "已恢复并保存默认范围。"
                }) { Text("恢复默认范围") }
                OutlinedButton(onClick = { store.resetBaseline(); message = "已重置距离基准。请返回作业页，保持正确写字姿势约 2 秒。" }) { Text("重新校准距离") }
            }
            Text("距离基准会保存，重启或拍照后仍沿用。移动平板或换人后请重新校准；距离是相对平板的估计值，并非离书本的厘米数。", fontSize = 12.sp)
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }
}

@Composable
private fun PoseRange(title: String, min: String, onMin: (String) -> Unit, max: String, onMax: (String) -> Unit) {
    Column {
        Text(title)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(min, onMin, label = { Text("下限") }, singleLine = true, modifier = Modifier.width(105.dp))
            OutlinedTextField(max, onMax, label = { Text("上限") }, singleLine = true, modifier = Modifier.width(105.dp))
        }
    }
}
