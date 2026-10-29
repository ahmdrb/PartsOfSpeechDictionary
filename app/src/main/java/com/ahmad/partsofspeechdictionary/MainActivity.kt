package com.ahmad.partsofspeechdictionary

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.text.style.TextAlign
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
                MainApp(vm, pronunciationHelper)
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
    var expandedEntryId by mutableStateOf<Long?>(null)
    
    var selectedCategory by mutableStateOf<String?>(null)
    
    // Collections State
    var selectedCollection by mutableStateOf<WordCollection?>(null)
    var showCreateCollectionDialog by mutableStateOf(false)
    var showAddToCollectionDialog by mutableStateOf(false)

    // Backup State
    var backupMessage by mutableStateOf<String?>(null)
        private set
    var backupError by mutableStateOf<String?>(null)
        private set
    var isBackupBusy by mutableStateOf(false)
        private set

    val allEntries = repository.entries("").stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val collections = repository.collections().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    
    private val selectedCollectionFlow = MutableStateFlow<Long?>(null)
    val collectionEntries = selectedCollectionFlow.flatMapLatest { collectionId ->
        if (collectionId == null) MutableStateFlow(emptyList<DictionaryEntry>())
        else repository.entriesInCollection(collectionId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectCollection(collection: WordCollection?) {
        selectedCollection = collection
        selectedCollectionFlow.value = collection?.id
    }

    fun createCollection(name: String) {
        viewModelScope.launch {
            repository.createCollection(name)
            showCreateCollectionDialog = false
        }
    }

    fun toggleWordInCollection(entry: DictionaryEntry) {
        val collection = selectedCollection ?: return
        viewModelScope.launch {
            // A hacky but effective way to check if it's already in the collection for now
            val isInCollection = collectionEntries.value.any { it.id == entry.id }
            if (isInCollection) {
                repository.removeEntryFromCollection(entry.id, collection.id)
            } else {
                repository.addEntryToCollection(entry.id, collection.id)
            }
        }
    }

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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(vm: DictionaryViewModel, pronunciationHelper: PronunciationHelper) {
    var currentTab by remember { mutableIntStateOf(0) }

    // Global Back Handler
    BackHandler(enabled = vm.showEditor || vm.selectedCollection != null || vm.selectedCategory != null || vm.showAddToCollectionDialog || vm.showCreateCollectionDialog) {
        when {
            vm.showEditor -> vm.showEditor = false
            vm.showAddToCollectionDialog -> vm.showAddToCollectionDialog = false
            vm.showCreateCollectionDialog -> vm.showCreateCollectionDialog = false
            vm.selectedCollection != null -> vm.selectCollection(null)
            vm.selectedCategory != null -> vm.selectedCategory = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("Wordwise", fontWeight = FontWeight.Bold)
                        Text(if (currentTab == 0) "Parts of Speech Dictionary" else "My Collections", style = MaterialTheme.typography.labelSmall)
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentTab == 0,
                    onClick = { currentTab = 0 },
                    icon = { Icon(Icons.Default.Search, contentDescription = "Dictionary") },
                    label = { Text("Dictionary") }
                )
                NavigationBarItem(
                    selected = currentTab == 1,
                    onClick = { currentTab = 1; vm.selectCollection(null) },
                    icon = { Icon(Icons.Default.Star, contentDescription = "Collections") },
                    label = { Text("Collections") }
                )
            }
        },
        floatingActionButton = {
            if (currentTab == 0) {
                FloatingActionButton(onClick = { vm.selectedEntry = null; vm.showEditor = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add word")
                }
            } else if (vm.selectedCollection == null) {
                FloatingActionButton(onClick = { vm.showCreateCollectionDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "New Collection")
                }
            } else {
                FloatingActionButton(onClick = { vm.showAddToCollectionDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add Words")
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (currentTab == 0) {
                DictionaryScreen(vm, pronunciationHelper)
            } else {
                CollectionsScreen(vm)
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
}

@Composable
fun DictionaryScreen(vm: DictionaryViewModel, pronunciationHelper: PronunciationHelper) {
    val allEntries by vm.allEntries.collectAsStateWithLifecycle()
    var activeGroup by remember { mutableStateOf<String?>(null) }

    val filteredEntries = allEntries.filter { entry ->
        val matchesQuery = vm.query.isBlank() || entry.word.contains(vm.query, ignoreCase = true) || entry.definitions.contains(vm.query, ignoreCase = true)
        val matchesCategory = when (vm.selectedCategory) {
            null -> true
            "Open Classes" -> listOf("noun", "verb", "adjective", "adverb").any { entry.categories.contains(it, ignoreCase = true) }
            "Closed Classes" -> listOf("pronoun", "preposition", "conjunction", "determiner").any { entry.categories.contains(it, ignoreCase = true) }
            else -> entry.categories.contains(vm.selectedCategory ?: "", ignoreCase = true)
        }
        matchesQuery && matchesCategory
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = vm.query,
            onValueChange = { vm.query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search words and definitions") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (vm.query.isNotBlank()) {
                    IconButton(onClick = { vm.query = "" }) { Icon(Icons.Default.Clear, contentDescription = "Clear") }
                }
            },
            singleLine = true
        )
        
        Spacer(Modifier.height(8.dp))
        
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (vm.selectedCategory != null) {
                IconButton(onClick = { vm.selectedCategory = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            }
            Button(
                onClick = { /* We can add the searchable dialog back later if needed */ },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Text(vm.selectedCategory ?: "Filter: All Words", color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        
        Spacer(Modifier.height(16.dp))

        if (vm.selectedCategory == null && vm.query.isBlank()) {
            val openClasses = listOf("Noun", "Verb", "Adjective", "Adverb")
            val closedClasses = listOf("Pronoun", "Preposition", "Conjunction", "Determiner")
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                if (activeGroup == null) {
                    item { CategoryCard("All Words", false) { vm.selectedCategory = "" } }
                    item { CategoryCard("Open Classes", true) { activeGroup = "Open" } }
                    item { CategoryCard("Closed Classes", true) { activeGroup = "Closed" } }
                } else {
                    item { CategoryCard("← Back", true) { activeGroup = null } }
                    val itemsToShow = if (activeGroup == "Open") openClasses else closedClasses
                    items(itemsToShow) { cat ->
                        CategoryCard(cat, false) { vm.selectedCategory = cat }
                    }
                }
            }
        } else {
            DictionaryList(filteredEntries, vm, pronunciationHelper, onRefresh = {})
        }
    }
}

@Composable
fun CategoryCard(title: String, isGroup: Boolean, onClick: () -> Unit) {
    ElevatedCard(modifier = Modifier.clickable { onClick() }) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (title.startsWith("←")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun CollectionsScreen(vm: DictionaryViewModel) {
    val collections by vm.collections.collectAsStateWithLifecycle()
    val collectionEntries by vm.collectionEntries.collectAsStateWithLifecycle()

    if (vm.selectedCollection == null) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(collections) { col ->
                ElevatedCard(modifier = Modifier.clickable { vm.selectCollection(col) }) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(col.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { vm.selectCollection(null) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(
                    text = vm.selectedCollection!!.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            
            if (collectionEntries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No words in this collection yet.\nTap + to add words.", textAlign = TextAlign.Center)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 80.dp)) {
                    items(collectionEntries) { entry ->
                        ListItem(
                            headlineContent = { Text(entry.word, fontWeight = FontWeight.Bold) },
                            supportingContent = { Text(entry.definitions, maxLines = 1) }
                        )
                    }
                }
            }
        }
    }

    if (vm.showCreateCollectionDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { vm.showCreateCollectionDialog = false },
            title = { Text("New Collection") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Collection Name") })
            },
            confirmButton = {
                TextButton(onClick = { if (name.isNotBlank()) vm.createCollection(name) }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { vm.showCreateCollectionDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (vm.showAddToCollectionDialog) {
        AddWordsDialog(vm)
    }
}

@Composable
fun AddWordsDialog(vm: DictionaryViewModel) {
    val allEntries by vm.allEntries.collectAsStateWithLifecycle()
    val collectionEntries by vm.collectionEntries.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    
    val filtered = allEntries.filter { it.word.contains(query, ignoreCase = true) }

    AlertDialog(
        onDismissRequest = { vm.showAddToCollectionDialog = false },
        title = { Text("Add Words to '${vm.selectedCollection?.name}'") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search words...") },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 350.dp)) {
                    items(filtered) { entry ->
                        val isInCollection = collectionEntries.any { it.id == entry.id }
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { vm.toggleWordInCollection(entry) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isInCollection,
                                onCheckedChange = { vm.toggleWordInCollection(entry) }
                            )
                            Text(entry.word, fontWeight = if (isInCollection) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.showAddToCollectionDialog = false }) { Text("Done") } }
    )
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
            EntryCard(entry, vm, pronunciationHelper)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EntryCard(entry: DictionaryEntry, vm: DictionaryViewModel, pronunciationHelper: PronunciationHelper) {
    val isExpanded = vm.expandedEntryId == entry.id
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = { vm.expandedEntryId = if (isExpanded) null else entry.id },
            onLongClick = { vm.selectedEntry = entry; vm.showEditor = true }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.word, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (isExpanded && !entry.pronunciation.isNullOrBlank()) {
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { pronunciationHelper.speak(entry.word) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Pronounce")
                    }
                }
            }
            if (isExpanded) {
                if (entry.categories.isNotBlank()) {
                    Text(entry.categories, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.height(4.dp))
                if (entry.definitions.isNotBlank()) Text(entry.definitions, style = MaterialTheme.typography.bodyMedium)
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
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = word, onValueChange = { word = it }, label = { Text("Word") }, modifier = Modifier.fillMaxWidth())
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
                            word = word.trim(), definitions = definitions.trim(), pronunciation = pronunciation.trim(), categories = categories.trim(), synonyms = synonyms.trim(), antonyms = antonyms.trim(), notes = notes.trim(), updatedAt = System.currentTimeMillis()
                        ) ?: DictionaryEntry(
                            word = word.trim(), definitions = definitions.trim(), pronunciation = pronunciation.trim(), categories = categories.trim(), synonyms = synonyms.trim(), antonyms = antonyms.trim(), notes = notes.trim()
                        )
                        onSave(newEntry)
                    }
                },
                enabled = word.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (initial != null) {
                    TextButton(onClick = { onDelete(initial) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
                } else { Spacer(Modifier.width(1.dp)) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
