package com.cuscus.ffmpeggui

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cuscus.ffmpeggui.core.production.model.ProductionProfile
import com.cuscus.ffmpeggui.core.production.model.ProductionProject

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

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("PRODUCTION STUDIO", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Text("Create a production", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Start with a project profile. Your scenes, media assets and render settings will live in a separate production project.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = projectName,
            onValueChange = { projectName = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Project name") },
            placeholder = { Text("e.g. The History of Money") },
            singleLine = true,
        )
        Text("Production type", style = MaterialTheme.typography.titleSmall)
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            profiles.forEach { item ->
                FilterChip(
                    selected = profile == item,
                    onClick = { profile = item },
                    label = { Text(item.name.replace('_', ' ')) },
                )
            }
        }
        Button(
            onClick = { onCreate(projectName.trim(), profile); projectName = "" },
            enabled = projectName.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Create project") }

        Spacer(Modifier.height(4.dp))
        Text("Your productions", style = MaterialTheme.typography.titleLarge)
        if (projects.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("No production projects yet", style = MaterialTheme.typography.titleMedium)
                    Text("Create your first project above. It will be saved on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(projects, key = { it.id.value }) { project ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        onClick = { onOpen(project) },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(project.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${project.profile.name.replace('_', ' ')}  •  ${project.scenes.size} scenes  •  ${project.assets.size} assets",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { onDelete(project) }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}
