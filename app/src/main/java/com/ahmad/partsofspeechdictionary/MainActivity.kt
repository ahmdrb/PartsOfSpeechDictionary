package com.ahmad.partsofspeechdictionary

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ahmad.partsofspeechdictionary.data.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var pronunciationHelper: PronunciationHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pronunciationHelper = PronunciationHelper(this)
        val db = AppDatabase.get(this)
        val repository = DictionaryRepository(db.dictionaryDao())
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                val vm: DictionaryViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        DictionaryViewModel(repository) as T
                })
                DictionaryScreen(vm, pronunciationHelper)
            }
        }
    }

    override fun onDestroy() {
        pronunciationHelper.release()
        super.onDestroy()
    }
}

class DictionaryViewModel(private val repository: DictionaryRepository) : ViewModel() {
    var query by mutableStateOf("")
    var selectedCategory by mutableStateOf<String?>(null)
    var showEditor by mutableStateOf(false)
    var selectedEntry by mutableStateOf<DictionaryEntry?>(null)
    var onlineResult by mutableStateOf<OnlineDictionaryResult?>(null)
    var isLookingUp by mutableStateOf(false)
    var lookupError by mutableStateOf<String?>(null)
    var entryToDelete by mutableStateOf<DictionaryEntry?>(null)
    var expandedEntryId by mutableStateOf<Long?>(null)
    var actionsEntryId by mutableStateOf<Long?>(null)

    val entries = repository.entries("").stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
    )

    fun lookupOnline() {
        val word = query.trim()
        if (word.isBlank() || isLookingUp) return

        viewModelScope.launch {
            isLookingUp = true
            lookupError = null
            onlineResult = null
            try {
                val dictionaryResult = withContext(Dispatchers.IO) {
                    OnlineDictionaryClient.lookup(word)
                }
                onlineResult = dictionaryResult
            } catch (e: Exception) {
                lookupError = e.message ?: "Lookup failed. Check your internet connection."
            } finally {
                isLookingUp = false
            }
        }
    }

    fun saveOnlineResult(result: OnlineDictionaryResult) {
        val categories = result.definitions
            .map { it.partOfSpeech.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(", ")

        val definitions = result.definitions
            .mapIndexed { index, item ->
                "${index + 1}. ${item.definition}"
            }
            .joinToString("\n")

        save(DictionaryEntry(
            word = result.word,
            definitions = definitions,
            pronunciation = result.pronunciation,
            categories = categories,
            synonyms = result.synonyms,
            antonyms = result.antonyms,
            source = "Free Dictionary API + Datamuse"
        ))
        onlineResult = null
    }

    fun delete(entry: DictionaryEntry) {
        viewModelScope.launch {
            repository.delete(entry.id)
            entryToDelete = null
            if (selectedEntry?.id == entry.id) {
                selectedEntry = null
                showEditor = false
            }
        }
    }

    fun save(entry: DictionaryEntry) {
        viewModelScope.launch { repository.save(entry); showEditor = false; selectedEntry = null }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DictionaryScreen(
    vm: DictionaryViewModel,
    pronunciationHelper: PronunciationHelper
) {
    val allEntries by vm.entries.collectAsStateWithLifecycle()
    val visible = allEntries.filter { entry ->
        (vm.query.isBlank() || entry.word.contains(vm.query, true) ||
            entry.definitions.contains(vm.query, true)) &&
        (vm.selectedCategory == null || entry.categories.split(",").any {
            it.trim().equals(vm.selectedCategory, true)
        })
    }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("Wordwise", fontWeight = FontWeight.Bold)
                    Text("Parts of Speech Dictionary", style = MaterialTheme.typography.labelSmall)
                }
            })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { vm.selectedEntry = null; vm.showEditor = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add word")
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = vm.query, onValueChange = { vm.query = it },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                label = { Text("Search words and definitions") },
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { vm.lookupOnline() },
                enabled = vm.query.isNotBlank() && !vm.isLookingUp,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (vm.isLookingUp) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Looking up online...")
                } else {
                    Text("Look up online")
                }
            }

            vm.lookupError?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(Modifier.height(12.dp))
            Text("Browse categories", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                FilterChip(selected = vm.selectedCategory == null,
                    onClick = { vm.selectedCategory = null }, label = { Text("All") })
                Spacer(Modifier.width(8.dp))
                GrammarTaxonomy.mainCategories.forEach { category ->
                    Spacer(Modifier.width(6.dp))
                    FilterChip(selected = vm.selectedCategory == category,
                        onClick = { vm.selectedCategory = if (vm.selectedCategory == category) null else category },
                        label = { Text(category) })
                }
            }
            Spacer(Modifier.height(12.dp))
            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (allEntries.isEmpty()) "Your dictionary starts here" else "No matching entries")
                        Spacer(Modifier.height(6.dp))
                        Text("Tap + to add a word and save its details.",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(visible, key = { it.id }) { entry ->
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth().combinedClickable(
                                onClick = {
                                    vm.expandedEntryId =
                                        if (vm.expandedEntryId == entry.id) null else entry.id
                                    vm.actionsEntryId = null
                                },
                                onLongClick = {
                                    vm.actionsEntryId =
                                        if (vm.actionsEntryId == entry.id) null else entry.id
                                }
                            )
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    entry.word,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold
                                )

                                if (vm.expandedEntryId == entry.id) {
                                    if (entry.categories.isNotBlank()) {
                                        Text(
                                            entry.categories,
                                            color = MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                    if (entry.pronunciation.isNotBlank()) {
                                        Text("Pronunciation: ${entry.pronunciation}")
                                    }
                                    if (entry.definitions.isNotBlank()) {
                                        val cleanDefinitions = entry.definitions
                                            .replace("\\n", "\n")
                                            .replace(Regex("\\s*\\[[^]]+\\]\\s*"), " ")
                                            .replace(Regex("[ \\t]+"), " ")
                                        Text(cleanDefinitions)
                                    }
                                    if (entry.notes.isNotBlank()) {
                                        Text("Personal Notes: ${entry.notes}")
                                    }
                                    if (entry.synonyms.isNotBlank()) {
                                        Text("Synonyms: ${entry.synonyms}")
                                    }
                                    if (entry.antonyms.isNotBlank()) {
                                        Text("Antonyms: ${entry.antonyms}")
                                    }
                                }

                                if (vm.actionsEntryId == entry.id) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        TextButton(onClick = {
                                            vm.selectedEntry = entry
                                            vm.showEditor = true
                                            vm.actionsEntryId = null
                                        }) {
                                            Text("Edit")
                                        }
                                        TextButton(onClick = {
                                            vm.entryToDelete = entry
                                            vm.actionsEntryId = null
                                        }) {
                                            Text("Delete")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    vm.onlineResult?.let { result ->
        AlertDialog(
            onDismissRequest = { vm.onlineResult = null },
            title = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            result.word,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.headlineSmall
                        )
                        IconButton(onClick = {
                            pronunciationHelper.speak(result.word)
                        }) {
                            Icon(
                                Icons.Default.VolumeUp,
                                contentDescription = "Pronounce ${result.word}"
                            )
                        }
                    }
                    if (result.pronunciation.isNotBlank()) {
                        Text(
                            result.pronunciation,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            },
            text = {
                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    result.definitions.forEachIndexed { index, item ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                "${index + 1}. ${item.partOfSpeech.ifBlank { "Definition" }}",
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(item.definition)
                        }
                    }
                    if (result.synonyms.isNotBlank()) {
                        Text(
                            "Synonyms: ${result.synonyms}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (result.antonyms.isNotBlank()) {
                        Text(
                            "Antonyms: ${result.antonyms}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Text(
                        "Source: Free Dictionary API",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.saveOnlineResult(result) }) {
                    Text("Save to my dictionary")
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.onlineResult = null }) {
                    Text("Close")
                }
            }
        )
    }

    vm.entryToDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { vm.entryToDelete = null },
            title = { Text("Delete word?") },
            text = {
                Text("Delete \"${entry.word}\" from your dictionary? This cannot be undone.")
            },
            confirmButton = {
                TextButton(onClick = { vm.delete(entry) }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.entryToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (vm.showEditor) {
        EntryEditor(
            initial = vm.selectedEntry,
            onDismiss = { vm.showEditor = false; vm.selectedEntry = null },
            onSave = vm::save
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryEditor(initial: DictionaryEntry?, onDismiss: () -> Unit, onSave: (DictionaryEntry) -> Unit) {
    var word by remember(initial) { mutableStateOf(initial?.word.orEmpty()) }
    var definitions by remember(initial) { mutableStateOf(initial?.definitions.orEmpty()) }
    var categories by remember(initial) { mutableStateOf(initial?.categories.orEmpty()) }
    var pronunciation by remember(initial) { mutableStateOf(initial?.pronunciation.orEmpty()) }
    var notes by remember(initial) { mutableStateOf(initial?.notes.orEmpty()) }
    var synonyms by remember(initial) { mutableStateOf(initial?.synonyms.orEmpty()) }
    var antonyms by remember(initial) { mutableStateOf(initial?.antonyms.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add dictionary entry" else "Edit entry") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(word, { word = it }, label = { Text("Word *") }, singleLine = true)
                OutlinedTextField(categories, { categories = it },
                    label = { Text("Parts of speech (comma-separated)") },
                    supportingText = { Text("Example: Noun, Verb") })
                OutlinedTextField(definitions, { definitions = it }, label = { Text("Definitions") })
                OutlinedTextField(pronunciation, { pronunciation = it }, label = { Text("Pronunciation") })
                OutlinedTextField(synonyms, { synonyms = it }, label = { Text("Synonyms (comma-separated)") })
                OutlinedTextField(antonyms, { antonyms = it }, label = { Text("Antonyms (comma-separated)") })
                OutlinedTextField(notes, { notes = it }, label = { Text("Personal notes") })
            }
        },
        confirmButton = {
            TextButton(enabled = word.isNotBlank(), onClick = {
                onSave(DictionaryEntry(
                    id = initial?.id ?: 0,
                    word = word,
                    definitions = definitions,
                    categories = categories,
                    pronunciation = pronunciation,
                    notes = notes,
                    source = initial?.source ?: "User entry",
                    synonyms = synonyms,
                    antonyms = antonyms,
                ))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
