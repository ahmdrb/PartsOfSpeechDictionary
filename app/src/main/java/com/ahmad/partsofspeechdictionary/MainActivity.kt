package com.ahmad.partsofspeechdictionary

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.BackHandler
import androidx.activity.compose.BackHandler
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ahmad.partsofspeechdictionary.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var pronunciationHelper: PronunciationHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pronunciationHelper = PronunciationHelper(this)
        val db = AppDatabase.get(this)
        val repository = DictionaryRepository(db.dictionaryDao())
        setContent {
            val darkTheme = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
                val vm: DictionaryViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                    factory = object : ViewModelProvider.Factory {
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            return DictionaryViewModel(repository, DictionaryBackupManager(db)) as T
                        }
                    }
                )
                DictionaryScreen(vm, pronunciationHelper)
            }
        }
    }

    override fun onDestroy() {
        pronunciationHelper.release()
        super.onDestroy()
    }
}

class DictionaryViewModel(
    private val repository: DictionaryRepository,
    private val backupManager: DictionaryBackupManager
) : ViewModel() {
    var query by mutableStateOf("")
    var showEditor by mutableStateOf(false)
    var selectedEntry by mutableStateOf<DictionaryEntry?>(null)
    var isLookingUp by mutableStateOf(false)
    var lookupError by mutableStateOf<String?>(null)
    var expandedEntryId by mutableStateOf<Long?>(null)
    
    var selectedCategory by mutableStateOf<String?>(null)
    var backupMessage by mutableStateOf<String?>(null)
        private set
    var backupError by mutableStateOf<String?>(null)
        private set
    var isBackupBusy by mutableStateOf(false)
        private set

    private val selectedCollectionFlow = MutableStateFlow<Long?>(null)

    val entries = selectedCollectionFlow.flatMapLatest { collectionId ->
        if (collectionId == null) repository.entries("")
        else repository.entriesInCollection(collectionId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun saveEntry(entry: DictionaryEntry) {
        viewModelScope.launch {
            repository.save(entry)
            selectedEntry = null
        }
    }

    fun deleteEntry(entry: DictionaryEntry) {
        viewModelScope.launch {
            repository.delete(entry.id)
        }
    }

    fun clearBackupStatus() {
        backupMessage = null
        backupError = null
    }

    fun exportData(onResult: (String) -> Unit) {
        viewModelScope.launch {
            isBackupBusy = true
            clearBackupStatus()
            try {
                val json = withContext(Dispatchers.IO) { backupManager.exportJson() }
                onResult(json)
                backupMessage = "Backup exported successfully."
            } catch (e: Exception) {
                backupError = "Export failed: ${e.message}"
            } finally {
                isBackupBusy = false
            }
        }
    }

    fun importData(json: String, mode: DictionaryBackupManager.ImportMode) {
        viewModelScope.launch {
            isBackupBusy = true
            clearBackupStatus()
            try {
                withContext(Dispatchers.IO) { backupManager.importJson(json, mode) }
                backupMessage = "Import completed successfully."
            } catch (e: Exception) {
                backupError = "Import failed: ${e.message}"
            } finally {
                isBackupBusy = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionaryScreen(vm: DictionaryViewModel, pronunciationHelper: PronunciationHelper) {
    var refreshCounter by remember { mutableStateOf(0) }
    var showCategoryDialog by remember { mutableStateOf(false) }
    var activeGroup by remember { mutableStateOf<String?>(null) }
    
    val context = LocalContext.current
    var pendingImportJson by remember { mutableStateOf<String?>(null) }
    var showImportModeDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = vm.showEditor || showImportModeDialog || showCategoryDialog || activeGroup != null || vm.selectedCategory != null) {
        when {
            vm.showEditor -> vm.showEditor = false
            showImportModeDialog -> showImportModeDialog = false
            showCategoryDialog -> showCategoryDialog = false
            vm.selectedCategory != null -> vm.selectedCategory = null
            activeGroup != null -> activeGroup = null
        }
    }



    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            vm.exportData { json ->
                try {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                } catch (e: Exception) { vm.clearBackupStatus() }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                if (json != null) {
                    pendingImportJson = json
                    showImportModeDialog = true
                }
            } catch (e: Exception) {}
        }
    }

    LaunchedEffect(vm.backupMessage, vm.backupError) {
        vm.backupMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.clearBackupStatus()
            refreshCounter++
        }
        vm.backupError?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.clearBackupStatus()
        }
    }

    val entries by vm.entries.collectAsStateWithLifecycle()

    val filteredEntries = entries.filter { entry ->
        val matchesQuery = vm.query.isBlank() ||
            entry.word.contains(vm.query, ignoreCase = true) ||
            entry.definitions.contains(vm.query, ignoreCase = true)
        val matchesCategory = vm.selectedCategory == null ||
            entry.categories.contains(vm.selectedCategory ?: "", ignoreCase = true)
        matchesQuery && matchesCategory
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("Wordwise", fontWeight = FontWeight.Bold)
                        Text("Parts of Speech Dictionary", style = MaterialTheme.typography.labelSmall)
                    }
                },
                actions = {
                    if (vm.isBackupBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(end = 8.dp))
                    } else {
                        IconButton(onClick = { vm.clearBackupStatus(); exportLauncher.launch("wordwise_backup.json") }) { Icon(Icons.Default.Upload, contentDescription = "Export") }
                        IconButton(onClick = { vm.clearBackupStatus(); importLauncher.launch(arrayOf("application/json")) }) { Icon(Icons.Default.Download, contentDescription = "Import") }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                vm.selectedEntry = null
                vm.showEditor = true
            }) {
                Icon(Icons.Default.Add, contentDescription = "Add word")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = vm.query,
                onValueChange = { vm.query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search words and definitions") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (vm.query.isNotBlank()) {
                        IconButton(onClick = { vm.query = "" }) { Icon(Icons.Default.Clear, contentDescription = "Clear search") }
                    }
                },
                singleLine = true
            )
            
            Spacer(Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (vm.selectedCategory != null) {
                    IconButton(onClick = { vm.selectedCategory = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
                Button(
                    onClick = { showCategoryDialog = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Text(
                        text = vm.selectedCategory ?: "Filter: All Words",
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            
            Spacer(Modifier.height(16.dp))

            if (vm.selectedCategory == null && vm.query.isBlank()) {
                CategoryGrid(
                    activeGroup = activeGroup,
                    onGroupClick = { activeGroup = it },
                    onCategoryClick = { cat ->
                        vm.selectedCategory = cat
                        refreshCounter++
                    }
                )
            } else {
                key(refreshCounter) {
                    DictionaryList(filteredEntries, vm, pronunciationHelper, onRefresh = { refreshCounter++ })
                }
            }
        }
    }

    if (vm.showEditor) {
        EntryEditor(
            initial = vm.selectedEntry,
            onDismiss = { vm.showEditor = false },
            onSave = { vm.saveEntry(it); vm.showEditor = false },
            onDelete = { vm.deleteEntry(it); vm.showEditor = false }
        )
    }

    if (showImportModeDialog) {
        AlertDialog(
            onDismissRequest = { showImportModeDialog = false },
            title = { Text("Import Backup") },
            text = { Text("Do you want to merge with existing words, or replace the entire dictionary?") },
            confirmButton = {
                TextButton(onClick = {
                    pendingImportJson?.let { vm.importData(it, DictionaryBackupManager.ImportMode.REPLACE) }
                    showImportModeDialog = false
                }) { Text("Replace All", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        pendingImportJson?.let { vm.importData(it, DictionaryBackupManager.ImportMode.MERGE) }
                        showImportModeDialog = false
                    }) { Text("Merge") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { showImportModeDialog = false }) { Text("Cancel") }
                }
            }
        )
    }

    if (showCategoryDialog) {
        SearchableCategoryDialog(
            selected = vm.selectedCategory,
            onSelect = {
                vm.selectedCategory = it
                showCategoryDialog = false
                refreshCounter++
            },
            onDismiss = { showCategoryDialog = false }
        )
    }
}

@Composable
fun CategoryGrid(
    activeGroup: String?,
    onGroupClick: (String?) -> Unit,
    onCategoryClick: (String) -> Unit
) {
    val openClasses = listOf("Noun", "Verb", "Adjective", "Adverb")
    val closedClasses = listOf("Pronoun", "Preposition", "Conjunction", "Determiner")

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (activeGroup == null) {
            item {
                ElevatedCard(modifier = Modifier.clickable { onCategoryClick("") }) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("All Words", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
            item {
                ElevatedCard(modifier = Modifier.clickable { onGroupClick("Open Classes") }) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Open Classes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
            item {
                ElevatedCard(modifier = Modifier.clickable { onGroupClick("Closed Classes") }) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Closed Classes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            item {
                ElevatedCard(modifier = Modifier.clickable { onGroupClick(null) }) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("← Back", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            
            val itemsToShow = if (activeGroup == "Open Classes") openClasses else closedClasses
            items(itemsToShow) { cat ->
                ElevatedCard(modifier = Modifier.clickable { onCategoryClick(cat) }) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(cat, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DictionaryList(
    entries: List<DictionaryEntry>,
    vm: DictionaryViewModel,
    pronunciationHelper: PronunciationHelper,
    onRefresh: () -> Unit
) {
    if (entries.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No words found.", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 80.dp)
    ) {
        items(entries, key = { it.id }) { entry ->
            EntryCard(entry, vm, pronunciationHelper, onRefresh)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EntryCard(
    entry: DictionaryEntry,
    vm: DictionaryViewModel,
    pronunciationHelper: PronunciationHelper,
    onRefresh: () -> Unit
) {
    val isExpanded = vm.expandedEntryId == entry.id
    
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    vm.expandedEntryId = if (isExpanded) null else entry.id
                },
                onLongClick = {
                    vm.selectedEntry = entry
                    vm.showEditor = true
                }
            )
    ) {
        Column(modifier = Modifier.padding(16.dp).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.word,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                if (isExpanded && !entry.pronunciation.isNullOrBlank()) {
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = { pronunciationHelper.speak(entry.word) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Pronounce")
                    }
                }
            }
            
            if (isExpanded) {
                if (entry.categories.isNotBlank()) {
                    Text(
                        text = entry.categories,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                Spacer(Modifier.height(4.dp))
                if (entry.definitions.isNotBlank()) {
                    val cleanDef = entry.definitions
                        .replace(Regex("\\[\\[\\]\\\"']"), "")
                        .replace(Regex("\"\"\""), "")
                    Text(cleanDef, style = MaterialTheme.typography.bodyMedium)
                }
                if (!entry.notes.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Notes: ${entry.notes}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun EntryEditor(
    initial: DictionaryEntry?,
    onDismiss: () -> Unit,
    onSave: (DictionaryEntry) -> Unit,
    onDelete: (DictionaryEntry) -> Unit
) {
    var word by remember(initial) { mutableStateOf(initial?.word ?: "") }
    val scope = rememberCoroutineScope()
    var isLookingUp by remember { mutableStateOf(false) }
    var lookupError by remember { mutableStateOf<String?>(null) }
    
    var definitions by remember(initial) { mutableStateOf(initial?.definitions ?: "") }
    var pronunciation by remember(initial) { mutableStateOf(initial?.pronunciation ?: "") }
    var categories by remember(initial) { mutableStateOf(initial?.categories ?: "") }
    var notes by remember(initial) { mutableStateOf(initial?.notes ?: "") }
    var synonyms by remember(initial) { mutableStateOf(initial?.synonyms ?: "") }
    var antonyms by remember(initial) { mutableStateOf(initial?.antonyms ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add Word" else "Edit Word") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = word, onValueChange = { word = it }, label = { Text("Word") }, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        if (word.isNotBlank()) {
                            isLookingUp = true
                            lookupError = null
                            scope.launch {
                                try {
                                    val res = withContext(Dispatchers.IO) { OnlineDictionaryClient.lookup(word) }
                                    pronunciation = res.pronunciation
                                    
                                    val partsOfSpeech = res.definitions.map { it.partOfSpeech }.filter { it.isNotBlank() }.distinct().joinToString(", ")
                                    if (partsOfSpeech.isNotBlank() && categories.isBlank()) categories = partsOfSpeech
                                    
                                    definitions = res.definitions.joinToString("\n\n") { it.definition }
                                    synonyms = res.synonyms
                                    antonyms = res.antonyms
                                    if (res.antonyms.isBlank()) lookupError = "No exact antonyms found online. Please type manually."
                                } catch (e: Exception) {
                                    lookupError = e.message
                                } finally {
                                    isLookingUp = false
                                }
                            }
                        }
                    }) {
                        if (isLookingUp) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        else Icon(Icons.Default.Search, contentDescription = "Look up")
                    }
                }
                if (lookupError != null) {
                    Text(lookupError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                
                OutlinedTextField(value = definitions, onValueChange = { definitions = it }, label = { Text("Definitions") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                OutlinedTextField(value = pronunciation, onValueChange = { pronunciation = it }, label = { Text("Pronunciation") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = categories, onValueChange = { categories = it }, label = { Text("Categories (comma separated)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = synonyms, onValueChange = { synonyms = it }, label = { Text("Synonyms") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = antonyms, onValueChange = { antonyms = it }, label = { Text("Antonyms") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (word.isNotBlank()) {
                        val newEntry = initial?.copy(
                            word = word.trim(),
                            definitions = definitions.trim(),
                            pronunciation = pronunciation.trim(),
                            categories = categories.trim(),
                            synonyms = synonyms.trim(),
                            antonyms = antonyms.trim(),
                            notes = notes.trim(),
                            updatedAt = System.currentTimeMillis()
                        ) ?: DictionaryEntry(
                            word = word.trim(),
                            definitions = definitions.trim(),
                            pronunciation = pronunciation.trim(),
                            categories = categories.trim(),
                            synonyms = synonyms.trim(),
                            antonyms = antonyms.trim(),
                            notes = notes.trim()
                        )
                        onSave(newEntry)
                    }
                },
                enabled = word.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (initial != null) {
                    TextButton(
                        onClick = { onDelete(initial) },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("Delete") }
                } else {
                    Spacer(Modifier.width(1.dp))
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
fun SearchableCategoryDialog(
    selected: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var activeDialogGroup by remember { mutableStateOf<String?>(null) }

    val openClasses = listOf("Noun", "Verb", "Adjective", "Adverb")
    val closedClasses = listOf("Pronoun", "Preposition", "Conjunction", "Determiner")
    val allCategories = openClasses + closedClasses

    // 🚨 System Back Button logic for the dialog drill-down

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filter by Category") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { 
                        query = it
                        if (it.isNotBlank()) activeDialogGroup = null // Reset drill-down if searching
                    },
                    placeholder = { Text("Search categories...") },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    if (query.isBlank()) {
                        if (activeDialogGroup == null) {
                            item {
                                ListItem(
                                    headlineContent = { Text("All Words", fontWeight = FontWeight.Bold) },
                                    modifier = Modifier.clickable { onSelect(null) }
                                )
                            }
                            item {
                                ListItem(
                                    headlineContent = { Text("Open Classes", fontWeight = FontWeight.Bold) },
                                    modifier = Modifier.clickable { activeDialogGroup = "Open Classes" }
                                )
                            }
                            item {
                                ListItem(
                                    headlineContent = { Text("Closed Classes", fontWeight = FontWeight.Bold) },
                                    modifier = Modifier.clickable { activeDialogGroup = "Closed Classes" }
                                )
                            }
                        } else {
                            item {
                                ListItem(
                                    headlineContent = { Text("← Back", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) },
                                    modifier = Modifier.clickable { activeDialogGroup = null }
                                )
                            }
                            val itemsToShow = if (activeDialogGroup == "Open Classes") openClasses else closedClasses
                            items(itemsToShow) { cat ->
                                ListItem(
                                    headlineContent = { Text(cat) },
                                    modifier = Modifier.clickable { onSelect(cat) }
                                )
                            }
                        }
                    } else {
                        item {
                            ListItem(
                                headlineContent = { Text("All Words", fontWeight = FontWeight.Bold) },
                                modifier = Modifier.clickable { onSelect(null) }
                            )
                        }
                        val filtered = allCategories.filter { it.contains(query, ignoreCase = true) }
                        items(filtered) { cat ->
                            ListItem(
                                headlineContent = { Text(cat) },
                                modifier = Modifier.clickable { onSelect(cat) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
