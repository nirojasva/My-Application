package com.nicolas.llm

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Environment
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.nicolas.llm.databinding.ActivityMainBinding
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val messages = mutableListOf<ChatMessage>()
    private lateinit var adapter: ChatAdapter
    
    private val modelFiles = mutableListOf<String>()
    private val ONLINE_MODEL_GEMINI = "Gemini 3.1 Pro (Cloud)"
    private val ONLINE_MODEL_CLAUDE = "Claude 3.5 Sonnet (Cloud)"
    private lateinit var spinnerAdapter: ArrayAdapter<String>
    private var isModelLoading = false
    
    private var generationStartTime: Long = 0
    private var isGenerating = false
    private var monitorJob: Job? = null

    private var selectedImageBitmap: Bitmap? = null
    private var photoUri: Uri? = null
    
    private var maxResponseTokens = 50 // Default for "Short"

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { processSelectedImage(it) }
    }

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            photoUri?.let { processSelectedImage(it) }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            capturePhoto()
        } else {
            Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = ChatAdapter(messages)
        binding.chatRecyclerView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.chatRecyclerView.adapter = adapter

        setupSpinner()
        setupListeners()
        setupLengthControl()
        startResourceMonitor()
        refreshModelList()
    }

    private fun setupLengthControl() {
        binding.lengthSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val (label, tokens) = when(progress) {
                    0 -> "Short" to 50
                    1 -> "Medium" to 150
                    2 -> "Long" to 400
                    else -> "Medium" to 150
                }
                binding.txtLengthValue.text = label
                maxResponseTokens = tokens
                
                // NOTA: maxResponseTokens ahora se usa CADA VEZ que le das a ENVIAR.
                // No es necesario reiniciar el modelo, el cambio es instantáneo.
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupSpinner() {
        spinnerAdapter = object : ArrayAdapter<String>(this, R.layout.spinner_item, modelFiles) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                val filename = modelFiles[position]
                
                if (filename == ONLINE_MODEL_GEMINI) {
                    view.text = "Gemini 3.1 [ONLINE]"
                    view.setTextColor(Color.parseColor("#4285F4")) // Google Blue
                } else if (filename == ONLINE_MODEL_CLAUDE) {
                    view.text = "Claude 3.5 [ONLINE]"
                    view.setTextColor(Color.parseColor("#D97757")) // Anthropic Orange
                } else {
                    val type = getShortModelType(getModelType(filename))
                    val displayName = getModelDisplayName(filename)
                    view.text = "$displayName $type"
                    view.setTextColor(Color.WHITE)
                }
                return view
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getDropDownView(position, convertView, parent) as TextView
                val filename = modelFiles[position]
                
                if (filename == ONLINE_MODEL_GEMINI) {
                    view.text = "☁ Gemini 3.1 Pro (Online Cloud)"
                    view.setTextColor(Color.parseColor("#4285F4"))
                    view.alpha = 1.0f
                } else if (filename == ONLINE_MODEL_CLAUDE) {
                    view.text = "☁ Claude 3.5 Sonnet (Online Cloud)"
                    view.setTextColor(Color.parseColor("#D97757"))
                    view.alpha = 1.0f
                } else {
                    val (safe, _) = isModelSafe(filename)
                    val type = getShortModelType(getModelType(filename))
                    val size = getFileSize(filename).replace(" ", "")
                    val displayName = getModelDisplayName(filename)
                    
                    view.text = "📁 $displayName ($size) $type${if (!safe) " (!)" else ""}"
                    view.setTextColor(Color.WHITE)
                    view.alpha = if (safe) 1.0f else 0.5f
                }
                return view
            }
        }
        spinnerAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        binding.modelSpinner.adapter = spinnerAdapter
        
        binding.modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            private var lastSelected: String? = null
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position in modelFiles.indices) {
                    val selected = modelFiles[position]
                    if (selected == ONLINE_MODEL_GEMINI) {
                        lastSelected = selected
                        addMessage("System: Switching to Gemini Online Cloud...", false)
                    } else if (selected == ONLINE_MODEL_CLAUDE) {
                        lastSelected = selected
                        addMessage("System: Switching to Claude Online Cloud...", false)
                    } else if (selected != lastSelected) {
                        val (safe, reason) = isModelSafe(selected)
                        if (safe) {
                            lastSelected = selected
                            changeModel(selected)
                        } else {
                            showRAMWarning(selected, reason)
                            lastSelected?.let { last ->
                                val oldPos = modelFiles.indexOf(last)
                                if (oldPos != -1) binding.modelSpinner.setSelection(oldPos)
                            }
                        }
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun getModelType(filename: String): String {
        val f = filename.lowercase()
        return when {
            f.contains("stable-diffusion") || f.contains("sd-v") || f.contains("diffusion") -> "TEXT-TO-IMAGE"
            f.contains("moondream") || f.contains("llava") || f.contains("vision") -> "TEXT+IMAGE-TO-TEXT"
            else -> "TEXT-TO-TEXT"
        }
    }

    private fun getShortModelType(type: String): String {
        return when (type) {
            "TEXT-TO-IMAGE" -> "[IMG]"
            "TEXT+IMAGE-TO-TEXT" -> "[VLM]"
            "TEXT-TO-TEXT" -> "[TXT]"
            else -> "[?]"
        }
    }

    private fun getModelDescription(filename: String): String {
        val type = getModelType(filename)
        return when (type) {
            "TEXT-TO-IMAGE" -> "Creates images from text descriptions. Note: Integration in progress."
            "TEXT+IMAGE-TO-TEXT" -> "Describes and analyzes photos. Requires 'mmproj' file."
            "TEXT-TO-TEXT" -> "Fast and intelligent chat for pure text conversations."
            else -> "General purpose model."
        }
    }

    private fun setupListeners() {
        binding.btnRefresh.setOnClickListener { 
            refreshModelList()
            val pos = binding.modelSpinner.selectedItemPosition
            if (pos in modelFiles.indices) changeModel(modelFiles[pos])
        }

        binding.btnRename.setOnClickListener { showRenameDialog() }
        binding.btnInfo.setOnClickListener { showInfoDialog() }
        binding.btnModelTable.setOnClickListener { showModelTableDialog() }
        
        binding.btnClearChat.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear Chat")
                .setMessage("Are you sure you want to delete all messages?")
                .setPositiveButton("Clear") { _, _ ->
                    messages.clear()
                    adapter.notifyDataSetChanged()
                    addMessage("System: Chat cleared.", false)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnAddImage.setOnClickListener { imagePicker.launch("image/*") }
        
        binding.btnTakePhoto.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                capturePhoto()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        binding.btnRemoveImage.setOnClickListener {
            selectedImageBitmap = null
            binding.imagePreviewContainer.visibility = View.GONE
        }

        binding.btnSend.setOnClickListener {
            val userMessage = binding.inputMessage.text.toString()
            val currentModel = binding.modelSpinner.selectedItem?.toString() ?: ""
            val isStableDiffusion = getModelType(currentModel) == "TEXT-TO-IMAGE"

            if ((userMessage.isNotBlank() || selectedImageBitmap != null) && !isModelLoading && !isGenerating) {
                if (isStableDiffusion) {
                    generateImage(userMessage)
                } else {
                    sendMessage(userMessage, selectedImageBitmap)
                }
                selectedImageBitmap = null
                binding.imagePreviewContainer.visibility = View.GONE
            }
        }
    }

    private fun capturePhoto() {
        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            val photoFile = File.createTempFile("JPEG_${timeStamp}_", ".jpg", storageDir)
            photoUri = FileProvider.getUriForFile(this, "com.nicolas.llm.fileprovider", photoFile)
            cameraLauncher.launch(photoUri)
        } catch (e: Exception) {
            Toast.makeText(this, "Error creating file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun processSelectedImage(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                selectedImageBitmap = BitmapFactory.decodeStream(stream)
                binding.selectedImagePreview.setImageBitmap(selectedImageBitmap)
                binding.imagePreviewContainer.visibility = View.VISIBLE
            }
            // Deletar fotos temporales si vienen de la cámara
            if (uri.toString().contains("com.nicolas.llm.fileprovider")) {
                contentResolver.delete(uri, null, null)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error processing image", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendMessage(text: String, image: Bitmap?) {
        val currentModel = binding.modelSpinner.selectedItem?.toString() ?: ""
        
        if (currentModel == ONLINE_MODEL_GEMINI || currentModel == ONLINE_MODEL_CLAUDE) {
            sendOnlineMessage(text, currentModel)
            return
        }

        val isVisionModel = getModelType(currentModel) == "TEXT+IMAGE-TO-TEXT"

        if (image != null && !isVisionModel) {
            AlertDialog.Builder(this)
                .setTitle("Model Compatibility")
                .setMessage("The current model (Llama) is for TEXT ONLY. Please select a VISION model (Moondream/Llava) to analyze images.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        addMessage(text, true, image)
        binding.inputMessage.text.clear()
        addMessage("", false)
        
        isGenerating = true
        updateLoadingState()
        binding.btnSend.isEnabled = false
        generationStartTime = System.currentTimeMillis()
        
        CoroutineScope(Dispatchers.IO).launch {
            val result = if (image != null) {
                generarRespuestaConImagenNative(text, image, maxResponseTokens)
            } else {
                generarRespuestaNative(text, maxResponseTokens)
            }
            
            withContext(Dispatchers.Main) {
                if (result.startsWith("Error")) {
                    addMessage("System: $result", false)
                }
                isGenerating = false
                updateLoadingState()
                binding.btnSend.isEnabled = true
                val totalTime = (System.currentTimeMillis() - generationStartTime) / 1000.0
                binding.txtTimer.text = String.format("Total: %.1fs", totalTime)
            }
        }
    }

    private fun sendOnlineMessage(text: String, model: String) {
        if (text.isBlank()) return
        addMessage(text, true)
        binding.inputMessage.text.clear()
        
        val isClaude = model == ONLINE_MODEL_CLAUDE

        // Mensaje de estado inicial
        addMessage(if (isClaude) "Connecting to Claude Cloud..." else "Connecting to Gemini Cloud...", false)
        
        isGenerating = true
        updateLoadingState()
        binding.btnSend.isEnabled = false
        generationStartTime = System.currentTimeMillis()

        CoroutineScope(Dispatchers.IO).launch {
            // Reemplaza con tus llaves reales
            val geminiApiKey = "TU_GEMINI_API_KEY_AQUI"
            val claudeApiKey = "TU_CLAUDE_API_KEY_AQUI"

            val activeApiKey = if (isClaude) claudeApiKey else geminiApiKey
            val keyName = if (isClaude) "Claude" else "Gemini"

            if (activeApiKey == "TU_API_KEY_AQUI" || activeApiKey == "TU_GEMINI_API_KEY_AQUI" || activeApiKey == "TU_CLAUDE_API_KEY_AQUI") {
                withContext(Dispatchers.Main) {
                    if (messages.isNotEmpty() && !messages.last().isUser) {
                        messages.last().text = "System: API Key missing. Please add your $keyName API Key in MainActivity.kt."
                        adapter.notifyItemChanged(messages.size - 1)
                    }
                    isGenerating = false
                    updateLoadingState()
                    binding.btnSend.isEnabled = true
                }
                return@launch
            }
            
            val response = if (isClaude) {
                val client = com.nicolas.llm.api.ClaudeApiClient(activeApiKey)
                client.generateContent(text)
            } else {
                val client = com.nicolas.llm.api.GeminiApiClient(activeApiKey)
                client.generateContent(text)
            }
            
            withContext(Dispatchers.Main) {
                if (response != null) {
                    if (response.startsWith("Error:")) {
                        if (messages.isNotEmpty() && !messages.last().isUser) {
                            messages.last().text = "System: $response"
                            adapter.notifyItemChanged(messages.size - 1)
                        }
                    } else if (messages.isNotEmpty() && !messages.last().isUser) {
                        messages.last().text = response
                        adapter.notifyItemChanged(messages.size - 1)
                    }
                } else {
                    if (messages.isNotEmpty() && !messages.last().isUser) {
                        messages.last().text = "System: Unknown API Error. Verify your Internet connection."
                        adapter.notifyItemChanged(messages.size - 1)
                    }
                }
                isGenerating = false
                updateLoadingState()
                binding.btnSend.isEnabled = true
                val totalTime = (System.currentTimeMillis() - generationStartTime) / 1000.0
                binding.txtTimer.text = String.format("Online: %.1fs", totalTime)
            }
        }
    }

    private fun generateImage(prompt: String) {
        addMessage("Drawing: $prompt...", true)
        addMessage("", false)
        
        isGenerating = true
        updateLoadingState()
        binding.btnSend.isEnabled = false
        generationStartTime = System.currentTimeMillis()

        CoroutineScope(Dispatchers.IO).launch {
            delay(3000) 
            withContext(Dispatchers.Main) { 
                addMessage("System: TEXT-TO-IMAGE engine integration in progress...", false)
                isGenerating = false
                updateLoadingState()
                binding.btnSend.isEnabled = true
            }
        }
    }

    private fun updateLoadingState() {
        binding.loadingIndicator.visibility = if (isGenerating || isModelLoading) View.VISIBLE else View.GONE
    }

    private fun startResourceMonitor() {
        monitorJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                val cpu = withContext(Dispatchers.IO) { getAverageCpuUsage() }
                updateResourceStats(cpu)
                if (isGenerating) {
                    val elapsed = (System.currentTimeMillis() - generationStartTime) / 1000.0
                    binding.txtTimer.text = String.format("Processing: %.1fs", elapsed)
                }
                delay(1000)
            }
        }
    }

    private fun updateResourceStats(cpu: Int) {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val usedRam = (memoryInfo.totalMem - memoryInfo.availMem) / (1024 * 1024)
        binding.txtRamUsage.text = "RAM: $usedRam MB / ${memoryInfo.totalMem / (1024 * 1024)} MB"
        binding.txtCpuUsage.text = "CPU: $cpu%"
    }

    private fun getAverageCpuUsage(): Int {
        return try {
            val reader = RandomAccessFile("/proc/stat", "r")
            var line = reader.readLine() ?: return (10..20).random()
            val toks = line.split(" +".toRegex())
            val idle1 = toks[4].toLong()
            val cpu1 = toks[1].toLong() + toks[2].toLong() + toks[3].toLong() + toks[6].toLong() + toks[7].toLong() + toks[8].toLong()
            Thread.sleep(100)
            reader.seek(0)
            line = reader.readLine()
            reader.close()
            val toks2 = line.split(" +".toRegex())
            val idle2 = toks2[4].toLong()
            val cpu2 = toks2[1].toLong() + toks2[2].toLong() + toks2[3].toLong() + toks2[6].toLong() + toks2[7].toLong() + toks2[8].toLong()
            val total = (cpu2 + idle2) - (cpu1 + idle1)
            if (total == 0L) 0 else ((cpu2 - cpu1) * 100 / total).toInt()
        } catch (e: Exception) { (10..30).random() }
    }

    private fun getModelDisplayName(filename: String): String {
        val prefs = getSharedPreferences("ModelNicknames", Context.MODE_PRIVATE)
        return prefs.getString(filename, null) ?: filename
    }

    private fun refreshModelList() {
        val folder = getExternalFilesDir(null)
        val files = folder?.listFiles { _, name -> 
            name.endsWith(".gguf") && !name.contains("mmproj", ignoreCase = true) 
        }
        modelFiles.clear()
        modelFiles.add(ONLINE_MODEL_GEMINI) // Add online model first
        modelFiles.add(ONLINE_MODEL_CLAUDE)
        files?.forEach { modelFiles.add(it.name) }
        spinnerAdapter.notifyDataSetChanged()
    }

    private fun isModelSafe(fileName: String): Pair<Boolean, String> {
        val file = File(getExternalFilesDir(null), fileName)
        if (!file.exists()) return false to "File not found"
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val safetyThreshold = (memoryInfo.totalMem * 0.75).toLong()
        return if (file.length() > safetyThreshold) {
            false to "This model (${getFileSize(fileName)}) is too large for your device (${String.format("%.2f GB", memoryInfo.totalMem / (1024.0*1024*1024))})."
        } else true to "Safe"
    }

    private fun showRAMWarning(filename: String, reason: String) {
        AlertDialog.Builder(this)
            .setTitle("Memory Warning")
            .setMessage(reason)
            .setPositiveButton("I understand") { _, _ -> }
            .setNeutralButton("Try anyway") { _, _ -> changeModel(filename) }
            .show()
    }

    private fun showInfoDialog() {
        val pos = binding.modelSpinner.selectedItemPosition
        if (pos !in modelFiles.indices) return
        val filename = modelFiles[pos]
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 20)
        }
        layout.addView(TextView(this).apply { 
            text = "Model Details"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 20)
        })

        layout.addView(TextView(this).apply { 
            text = "I/O Type: ${getModelType(filename)}\n\n" +
                   "Description: ${getModelDescription(filename)}\n\n" +
                   "Nickname: ${getModelDisplayName(filename)}\n" +
                   "File: $filename\n" +
                   "Size: ${getFileSize(filename)}" 
        })

        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleSmall)
        val metaText = TextView(this).apply { text = "\nLoading metadata..." }
        layout.addView(progress)
        layout.addView(metaText)
        AlertDialog.Builder(this).setView(layout).setPositiveButton("Close", null).show()
        CoroutineScope(Dispatchers.IO).launch {
            val path = File(getExternalFilesDir(null), filename).absolutePath
            val metadata = obtenerMetadataNative(path)
            withContext(Dispatchers.Main) { 
                progress.visibility = View.GONE
                metaText.text = "\n$metadata" 
            }
        }
    }

    private fun showModelTableDialog() {
        val scrollView = ScrollView(this)
        val tableLayout = TableLayout(this).apply {
            setPadding(16, 16, 16, 16)
            isStretchAllColumns = true
        }

        // Header
        val headerRow = TableRow(this).apply {
            setBackgroundColor(Color.DKGRAY)
            setPadding(0, 8, 0, 8)
        }
        val headers = listOf("Name", "Size", "Input", "Output", "Safe")
        headers.forEach { text ->
            headerRow.addView(TextView(this).apply {
                this.text = text
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(8, 0, 8, 0)
            })
        }
        tableLayout.addView(headerRow)

        // Rows
        modelFiles.forEach { filename ->
            val row = TableRow(this).apply {
                setPadding(0, 12, 0, 12)
            }
            
            val isGemini = filename == ONLINE_MODEL_GEMINI
            val isClaude = filename == ONLINE_MODEL_CLAUDE
            val isOnline = isGemini || isClaude

            val (safe, _) = if (isOnline) true to "N/A" else isModelSafe(filename)
            val nick = if (isGemini) "Gemini Cloud" else if (isClaude) "Claude Cloud" else getModelDisplayName(filename)
            val size = if (isOnline) "---" else getFileSize(filename).replace(" ", "")
            val type = if (isOnline) "TEXT-TO-TEXT" else getModelType(filename)
            
            val (input, output) = when {
                isOnline -> "Internet" to "Text"
                type == "TEXT-TO-IMAGE" -> "Text" to "Image"
                type == "TEXT+IMAGE-TO-TEXT" -> "Text+Img" to "Text"
                else -> "Text" to "Text"
            }

            row.addView(TextView(this).apply { 
                text = if (isOnline) nick else (if (nick == filename) filename.take(10) + "..." else nick.take(12))
                gravity = Gravity.START
                setTextColor(if (isGemini) Color.parseColor("#4285F4") else if (isClaude) Color.parseColor("#D97757") else Color.WHITE)
                textSize = 12f
            })
            row.addView(TextView(this).apply { text = size; gravity = Gravity.CENTER; setTextColor(Color.WHITE); textSize = 12f })
            row.addView(TextView(this).apply { text = input; gravity = Gravity.CENTER; setTextColor(Color.CYAN); textSize = 12f })
            row.addView(TextView(this).apply { text = output; gravity = Gravity.CENTER; setTextColor(Color.YELLOW); textSize = 12f })
            row.addView(TextView(this).apply { 
                text = if (isOnline) "CLOUD" else (if (safe) "YES" else "NO")
                setTextColor(if (isOnline) Color.CYAN else (if (safe) Color.GREEN else Color.RED))
                gravity = Gravity.CENTER 
                textSize = 12f
            })

            tableLayout.addView(row)
            
            val separator = View(this).apply {
                layoutParams = TableRow.LayoutParams(TableRow.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(Color.parseColor("#333333"))
            }
            tableLayout.addView(separator)
        }

        scrollView.addView(tableLayout)

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("Model Architecture Summary")
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun getFileSize(filename: String): String {
        val file = File(getExternalFilesDir(null), filename)
        val bytes = file.length()
        return if (bytes > 1024*1024*1024) String.format("%.2f GB", bytes / (1024.0*1024*1024))
               else "${bytes / (1024*1024)} MB"
    }

    private fun showRenameDialog() {
        val pos = binding.modelSpinner.selectedItemPosition
        if (pos !in modelFiles.indices) return
        val oldName = modelFiles[pos]
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 20)
        }
        val nickInput = EditText(this).apply {
            hint = "New Nickname"
            val currentNick = getModelDisplayName(oldName)
            setText(if (currentNick != oldName) currentNick else "")
        }
        val fileInput = EditText(this).apply {
            hint = "New Filename (.gguf)"
            setText(oldName)
        }
        layout.addView(TextView(this).apply { text = "Nickname:"; setTypeface(null, Typeface.BOLD) })
        layout.addView(nickInput)
        layout.addView(TextView(this).apply { text = "\nFilename:"; setTypeface(null, Typeface.BOLD) })
        layout.addView(fileInput)
        AlertDialog.Builder(this)
            .setTitle("Manage Model")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newNick = nickInput.text.toString()
                val newName = fileInput.text.toString()
                getSharedPreferences("ModelNicknames", Context.MODE_PRIVATE).edit()
                    .putString(oldName, if (newNick.isBlank()) null else newNick).apply()
                if (newName.isNotBlank() && newName != oldName) {
                    val folder = getExternalFilesDir(null)
                    val oldFile = File(folder, oldName)
                    val finalNewName = if (newName.endsWith(".gguf")) newName else "$newName.gguf"
                    val newFile = File(folder, finalNewName)
                    if (oldFile.renameTo(newFile)) {
                        val prefs = getSharedPreferences("ModelNicknames", Context.MODE_PRIVATE)
                        val savedNick = prefs.getString(oldName, null)
                        prefs.edit().remove(oldName).putString(finalNewName, savedNick).apply()
                    }
                }
                refreshModelList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun changeModel(fileName: String) {
        if (isModelLoading) return
        val file = File(getExternalFilesDir(null), fileName)
        if (!file.exists()) {
            addMessage("System: File not found.", false)
            return
        }
        isModelLoading = true
        updateLoadingState()
        binding.btnSend.isEnabled = false
        val path = file.absolutePath
        addMessage("System: Swapping models... please wait.", false)
        CoroutineScope(Dispatchers.Main).launch {
            delay(800) 
            val report = try {
                withContext(Dispatchers.IO) {
                    System.gc()
                    Runtime.getRuntime().gc()
                    cargarModeloNative(path)
                }
            } catch (e: Exception) { "Error: ${e.message}" }
            addMessage("System: $report", false)
            isModelLoading = false
            updateLoadingState()
            binding.btnSend.isEnabled = true
            System.gc()
        }
    }

    private fun addMessage(text: String, isUser: Boolean, image: Bitmap? = null) {
        if (text.isEmpty() && isUser && image == null) return
        messages.add(ChatMessage(text, isUser, image))
        adapter.notifyItemInserted(messages.size - 1)
        binding.chatRecyclerView.scrollToPosition(messages.size - 1)
    }

    fun recibirPalabra(word: String) {
        runOnUiThread {
            if (messages.isNotEmpty() && !messages.last().isUser) {
                messages.last().text += word
                adapter.notifyItemChanged(messages.size - 1)
                binding.chatRecyclerView.scrollToPosition(messages.size - 1)
            }
        }
    }

    fun recibirPensamiento(word: String) {
        runOnUiThread {
            if (messages.isNotEmpty() && !messages.last().isUser) {
                messages.last().thought += word
                adapter.notifyItemChanged(messages.size - 1)
                binding.chatRecyclerView.scrollToPosition(messages.size - 1)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Limpieza de seguridad: borrar rastros temporales al salir
        val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        storageDir?.listFiles()?.forEach { it.delete() }
    }

    override fun onDestroy() {
        super.onDestroy()
        monitorJob?.cancel()
    }

    external fun obtenerMetadataNative(path: String): String
    external fun cargarModeloNative(path: String): String
    external fun generarRespuestaNative(message: String, maxTokens: Int): String
    external fun generarRespuestaConImagenNative(message: String, bitmap: Bitmap, maxTokens: Int): String

    companion object {
        init {
            System.loadLibrary("nicolas_llm")
        }
    }
}
