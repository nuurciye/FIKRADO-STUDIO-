package com.cuscus.ffmpeggui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cuscus.ffmpeggui.core.production.model.ProductionProfile
import com.cuscus.ffmpeggui.core.production.model.ProductionProject

private val StudioBackground = Color(0xFF090E18)
private val StudioPanel = Color(0xFF141D2C)
private val StudioPanelRaised = Color(0xFF1B283A)
private val StudioGold = Color(0xFFD7AD62)
private val StudioCream = Color(0xFFF4EBDD)
private val StudioMuted = Color(0xFF9BA9BA)

@Composable
fun ProductionStudioScreen(
    projects: List<ProductionProject>,
    onCreate: (String, ProductionProfile) -> Unit,
    onOpen: (ProductionProject) -> Unit,
    onDelete: (ProductionProject) -> Unit,
    onBack: () -> Unit,
) {
    var projectName by remember { mutableStateOf("") }
    var profile by remember { mutableStateOf(ProductionProfile.DOCUMENTARY) }
    val profiles = listOf(
        ProductionProfile.DOCUMENTARY, ProductionProfile.ADVERTISING,
        ProductionProfile.SOCIAL_MEDIA, ProductionProfile.THREE_D,
        ProductionProfile.CORPORATE, ProductionProfile.EDUCATION,
        ProductionProfile.REAL_ESTATE, ProductionProfile.E_COMMERCE,
        ProductionProfile.GAMING, ProductionProfile.EVENTS,
        ProductionProfile.NEWS, ProductionProfile.CUSTOM,
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(StudioBackground),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onBack) { Text("‹  BACK", color = StudioMuted) }
                Text("STUDIO / HOME", color = StudioGold, style = MaterialTheme.typography.labelMedium)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("FIKRADO", color = StudioGold, style = MaterialTheme.typography.labelLarge)
                Text("Make something\nremarkable.", color = StudioCream, style = MaterialTheme.typography.headlineLarge)
                Text("Your creative production workspace", color = StudioMuted, style = MaterialTheme.typography.bodyMedium)
            }
        }
        item {
            Card(
                shape = RoundedCornerShape(26.dp),
                colors = CardDefaults.cardColors(containerColor = StudioPanel),
                modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF344154), RoundedCornerShape(26.dp)),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("NEW PROJECT", color = StudioGold, style = MaterialTheme.typography.labelLarge)
                        Text("01 / CREATE", color = StudioMuted, style = MaterialTheme.typography.labelSmall)
                    }
                    Text("Start with an idea", color = StudioCream, style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(
                        value = projectName,
                        onValueChange = { projectName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Project title") },
                        placeholder = { Text("e.g. The History of Money") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = StudioCream,
                            unfocusedTextColor = StudioCream,
                            focusedBorderColor = StudioGold,
                            unfocusedBorderColor = Color(0xFF435166),
                            focusedLabelColor = StudioGold,
                            unfocusedLabelColor = StudioMuted,
                            cursorColor = StudioGold,
                        ),
                    )
                    Text("CHOOSE A PRODUCTION FORMAT", color = StudioMuted, style = MaterialTheme.typography.labelSmall)
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        profiles.forEach { item ->
                            FilterChip(
                                selected = profile == item,
                                onClick = { profile = item },
                                label = { Text(item.name.replace('_', ' '), style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                    Button(
                        onClick = { onCreate(projectName.trim(), profile); projectName = "" },
                        enabled = projectName.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(15.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StudioGold,
                            contentColor = Color(0xFF15191F),
                            disabledContainerColor = Color(0xFF4B4A43),
                            disabledContentColor = Color(0xFFB9B2A5),
                        ),
                    ) { Text("＋  CREATE PRODUCTION", style = MaterialTheme.typography.labelLarge) }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("YOUR WORKSPACE", color = StudioGold, style = MaterialTheme.typography.labelMedium)
                    Text("Recent productions", color = StudioCream, style = MaterialTheme.typography.titleLarge)
                }
                Text("${projects.size} PROJECTS", color = StudioMuted, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (projects.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = StudioPanelRaised),
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("Your next story starts here.", color = StudioCream, style = MaterialTheme.typography.titleMedium)
                        Text("Create a production above. Your projects are saved locally on this device.", color = StudioMuted)
                    }
                }
            }
        } else {
            items(projects, key = { it.id.value }) { project ->
                Card(
                    modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF2A394D), RoundedCornerShape(20.dp)),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = StudioPanel),
                    onClick = { onOpen(project) },
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(project.name, color = StudioCream, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${project.profile.name.replace('_', ' ')}  ·  ${project.scenes.size} scenes  ·  ${project.assets.size} assets",
                                color = StudioMuted, style = MaterialTheme.typography.bodySmall,
                            )
                            Text("OPEN PROJECT  →", color = StudioGold, style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = { onDelete(project) }) { Text("DELETE", color = StudioMuted) }
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(10.dp))
            Text("FIKRADO STUDIO  ·  CREATIVE PRODUCTION", color = StudioMuted, style = MaterialTheme.typography.labelSmall)
        }
    }
}
