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
import androidx.compose.material.icons.filled.Close
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
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
    var selectedCollectionId by mutableStateOf<Long?>(null)
        private set
    var collectionBeingEdited by mutableStateOf<WordCollection?>(null)
    var showCollectionDialog by mutableStateOf(false)
    var collectionName by mutableStateOf("")
    var collectionError by mutableStateOf<String?>(null)
    var collectionToDelete by mutableStateOf<WordCollection?>(null)

    private val selectedCollectionFlow = MutableStateFlow<Long?>(null)

    val collections = repository.collections().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
    )

    val entries = selectedCollectionFlow.flatMapLatest { collectionId ->
        if (collectionId == null) repository.entries("")
        else repository.entriesInCollection(collectionId)
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
    )

    fun selectCollection(id: Long?) {
        selectedCollectionId = id
        selectedCollectionFlow.value = id
    }

    fun beginCreateCollection() {
        collectionBeingEdited = null
        collectionName = ""
        collectionError = null
        showCollectionDialog = true
    }

    fun beginRenameCollection() {
        val collection = collections.value.firstOrNull { it.id == selectedCollectionId }
            ?: return
        collectionBeingEdited = collection
        collectionName = collection.name
        collectionError = null
        showCollectionDialog = true
    }

    fun saveCollection() {
        val name = collectionName.trim()
        if (name.isBlank()) {
            collectionError = "Enter a collection name."
            return
        }

        viewModelScope.launch {
            try {
                val editing = collectionBeingEdited
                if (editing == null) {
                    val id = repository.createCollection(name)
                    if (id == -1L) {
                        collectionError = "A collection with that name already exists."
                        return@launch
                    }
                    selectCollection(id)
                } else {
                    repository.renameCollection(editing.id, name)
                }
                showCollectionDialog = false
                collectionError = null
            } catch (e: Exception) {
                collectionError = if (e.message.orEmpty().contains("UNIQUE", true)) {
                    "A collection with that name already exists."
                } else {
                    e.message ?: "Couldn't save this collection."
                }
            }
        }
    }

    fun deleteCollection(collection: WordCollection) {
        viewModelScope.launch {
            repository.deleteCollection(collection.id)
            if (selectedCollectionId == collection.id) selectCollection(null)
            collectionToDelete = null
        }
    }

    var manageCollectionsEntry by mutableStateOf<DictionaryEntry?>(null)
    var showManageCollectionsDialog by mutableStateOf(false)
    var selectedCollectionIds by mutableStateOf<Set<Long>>(emptySet())

    fun beginManageCollections(entry: DictionaryEntry) {
        manageCollectionsEntry = entry
        viewModelScope.launch {
            selectedCollectionIds = repository.memberships(entry.id)
                .map { it.collectionId }
                .toSet()
            showManageCollectionsDialog = true
        }
    }

    fun toggleCollectionMembership(collectionId: Long, checked: Boolean) {
        selectedCollectionIds = if (checked) {
            selectedCollectionIds + collectionId
        } else {
            selectedCollectionIds - collectionId
        }
    }

    fun saveCollectionMemberships() {
        val entry = manageCollectionsEntry ?: return
        val selectedIds = selectedCollectionIds

        viewModelScope.launch {
            val existingIds = repository.memberships(entry.id)
                .map { it.collectionId }
                .toSet()

            (existingIds - selectedIds).forEach { id ->
                repository.removeEntryFromCollection(entry.id, id)
            }
            (selectedIds - existingIds).forEach { id ->
                repository.addEntryToCollection(entry.id, id)
            }

            showManageCollectionsDialog = false
            manageCollectionsEntry = null
        }
    }

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
                trailingIcon = {
                    if (vm.query.isNotEmpty()) {
                        IconButton(onClick = { vm.query = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true
            )
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
            Spacer(Modifier.height(14.dp))
            Text("Collections", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            val collections by vm.collections.collectAsStateWithLifecycle()
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                FilterChip(
                    selected = vm.selectedCollectionId == null,
                    onClick = { vm.selectCollection(null) },
                    label = { Text("All words") }
                )
                collections.forEach { collection ->
                    Spacer(Modifier.width(6.dp))
                    FilterChip(
                        selected = vm.selectedCollectionId == collection.id,
                        onClick = { vm.selectCollection(collection.id) },
                        label = { Text(collection.name) }
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = { vm.beginCreateCollection() }) {
                    Text("+ New collection")
                }
                if (vm.selectedCollectionId != null) {
                    TextButton(onClick = { vm.beginRenameCollection() }) {
                        Text("Rename")
                    }
                    TextButton(onClick = {
                        vm.collectionToDelete = collections.firstOrNull {
                            it.id == vm.selectedCollectionId
                        }
                    }) {
                        Text("Delete")
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${visible.size} ${if (visible.size == 1) "word" else "words"} found",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
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
                                            vm.beginManageCollections(entry)
                                            vm.actionsEntryId = null
                                        }) {
                                            Text("Collections")
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

    if (vm.showCollectionDialog) {
        AlertDialog(
            onDismissRequest = { vm.showCollectionDialog = false },
            title = {
                Text(if (vm.collectionBeingEdited == null) {
                    "New collection"
                } else {
                    "Rename collection"
                })
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = vm.collectionName,
                        onValueChange = {
                            vm.collectionName = it
                            vm.collectionError = null
                        },
                        label = { Text("Collection name") },
                        singleLine = true
                    )
                    vm.collectionError?.let { error ->
                        Text(error, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.saveCollection() }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.showCollectionDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    vm.collectionToDelete?.let { collection ->
        AlertDialog(
            onDismissRequest = { vm.collectionToDelete = null },
            title = { Text("Delete collection?") },
            text = {
                Text(
                    "Delete \"${collection.name}\"? The words themselves will remain in your dictionary."
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.deleteCollection(collection) }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.collectionToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (vm.showManageCollectionsDialog) {
        val collections by vm.collections.collectAsStateWithLifecycle()
        AlertDialog(
            onDismissRequest = {
                vm.showManageCollectionsDialog = false
                vm.manageCollectionsEntry = null
            },
            title = {
                Text("Collections for ${vm.manageCollectionsEntry?.word.orEmpty()}")
            },
            text = {
                Column(
                    Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (collections.isEmpty()) {
                        Text("Create a collection first, then assign words to it.")
                    } else {
                        collections.forEach { collection ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        vm.toggleCollectionMembership(
                                            collection.id,
                                            collection.id !in vm.selectedCollectionIds
                                        )
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = collection.id in vm.selectedCollectionIds,
                                    onCheckedChange = { checked ->
                                        vm.toggleCollectionMembership(collection.id, checked)
                                    }
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(collection.name)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.saveCollectionMemberships() }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.showManageCollectionsDialog = false
                    vm.manageCollectionsEntry = null
                }) {
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
