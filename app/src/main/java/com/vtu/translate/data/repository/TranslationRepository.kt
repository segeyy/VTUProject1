package com.vtu.translate.data.repository

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.vtu.translate.data.model.LogType
import com.vtu.translate.data.model.StringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import org.xmlpull.v1.XmlSerializer
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStreamWriter

/**
 * Repository for handling XML translation operations
 */
class TranslationRepository(
    private val groqRepository: GroqRepository,
    private val logRepository: LogRepository
) {
    
    private val _stringResources = MutableStateFlow<List<StringResource>>(emptyList())
    val stringResources: StateFlow<List<StringResource>> = _stringResources.asStateFlow()
    
    private val _isTranslating = MutableStateFlow(false)
    val isTranslating: StateFlow<Boolean> = _isTranslating.asStateFlow()
    
    private val _selectedFileName = MutableStateFlow<String?>(null)
    val selectedFileName: StateFlow<String?> = _selectedFileName.asStateFlow()
    
    /**
     * Parse strings.xml file from URI
     */
    suspend fun parseStringsXml(context: Context, uri: Uri): Result<List<StringResource>> {
        return withContext(Dispatchers.IO) {
            try {
                val fileName = getFileName(context, uri)
                _selectedFileName.value = fileName
                
                val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                inputStream?.use { stream ->
                    val factory = XmlPullParserFactory.newInstance()
                    factory.isNamespaceAware = true
                    val parser = factory.newPullParser()
                    parser.setInput(stream, null)
                    
                    val stringResources = mutableListOf<StringResource>()
                    var eventType = parser.eventType
                    var name: String? = null
                    var translatable: String? = null
                    
                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        when (eventType) {
                            XmlPullParser.START_TAG -> {
                                if (parser.name == "string") {
                                    name = parser.getAttributeValue(null, "name")
                                    translatable = parser.getAttributeValue(null, "translatable")
                                }
                            }
                            XmlPullParser.TEXT -> {
                                if (name != null) {
                                    val value = parser.text
                                    // Check if string is translatable
                                    val isTranslatable = translatable == null || translatable != "false"
                                    
                                    // Special case handling for known non-translatable strings
                    val isSpecialCase = isSpecialNonTranslatableString(value)
                    
                    if (isTranslatable && !isSpecialCase) {
                        // Check if the string contains technical parts that should be preserved
                        if (containsTechnicalParts(value)) {
                            // Add with pre-processed value that marks technical parts
                            val preprocessedValue = preprocessStringWithTechnicalParts(value)
                            stringResources.add(StringResource(name, preprocessedValue))
                        } else {
                            stringResources.add(StringResource(name, value))
                        }
                    } else if (isSpecialCase) {
                        // Add with pre-defined translation for special cases
                        val predefinedTranslation = getSpecialCaseTranslation(value)
                        stringResources.add(StringResource(
                            name = name,
                            value = value,
                            translatedValue = predefinedTranslation,
                            isTranslating = false,
                            hasError = false
                        ))
                                    }
                                }
                            }
                            XmlPullParser.END_TAG -> {
                                if (parser.name == "string") {
                                    name = null
                                    translatable = null
                                }
                            }
                        }
                        eventType = parser.next()
                    }
                    
                    _stringResources.value = stringResources
                    return@withContext Result.success(stringResources)
                } ?: return@withContext Result.failure(Exception("Could not open file"))
            } catch (e: Exception) {
                return@withContext Result.failure(e)
            }
        }
    }
    
    /**
     * Check if a string is a special non-translatable string
     * 
     * @param value The string value to check
     * @return True if the string is a special non-translatable string
     */
    private fun isSpecialNonTranslatableString(value: String): Boolean {
        // Check for package names, class names, or other technical strings
        return value.matches(Regex("^[a-zA-Z0-9]+(\\.[a-zA-Z0-9]+)+$")) || // Package names like androidx.startup
               value.matches(Regex("^[A-Z][a-zA-Z0-9]*$")) || // Class names like MainActivity
               value.matches(Regex("^[a-zA-Z0-9_]+$")) || // Simple technical identifiers
               value.startsWith("http://") || value.startsWith("https://") || // URLs
               value.startsWith("androidx.") || // Specific package prefixes
               value.startsWith("android.") ||
               value.startsWith("java.") ||
               value.startsWith("kotlin.") ||
               value.contains("@") || // Email addresses or resource references
               value.matches(Regex(".*\\{.*\\}.*")) || // Strings with placeholders like {0}
               value.matches(Regex(".*%[sdfx].*")) || // Format specifiers like %s, %d
               value.matches(Regex("^[0-9]+$")) || // Pure numbers
               value.trim().isEmpty() // Empty strings
    }
    
    /**
     * Get predefined translation for special case strings
     * 
     * @param value The string value to translate
     * @return The predefined translation for the special case string
     */
    private fun getSpecialCaseTranslation(value: String): String {
        // For technical strings, we keep them as-is without translation
        return when {
            value.startsWith("androidx.") -> value // Keep package names as-is
            value.startsWith("android.") -> value
            value.startsWith("java.") -> value
            value.startsWith("kotlin.") -> value
            value.startsWith("http") -> value // Keep URLs as is
            else -> value // Keep as is if no special handling defined
        }
    }
    
    /**
     * Boolean to control whether to stop the translation process
     */
    private var shouldStopTranslation = false
    
    /**
     * Stop the current translation process
     */
    fun stopTranslation() {
        shouldStopTranslation = true
        logRepository.logWarning("Đã yêu cầu dừng quá trình dịch.")
    }
    
    /**
     * Translate all string resources
     */
    suspend fun translateAll(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                _isTranslating.value = true
                shouldStopTranslation = false
                
                val resources = _stringResources.value
                if (resources.isEmpty()) {
                    return@withContext Result.failure(Exception("No strings to translate"))
                }
                
                logRepository.logInfo("Bắt đầu dịch ${resources.size} chuỗi với model [${groqRepository.getSelectedModel()}].")
                
                // Create a mutable copy of the resources
                val updatedResources = resources.toMutableList()
                
                // Translate all resources concurrently
                val translationJobs = resources.mapIndexed { index, resource ->
                    async {
                        // Check if translation should be stopped
                        if (shouldStopTranslation) {
                            return@async null
                        }

                        // Only translate strings that haven't been translated yet
                        if (resource.translatedValue == null) {
                            // Update isTranslating status
                            withContext(Dispatchers.Main) {
                                updatedResources[index] = resource.copy(isTranslating = true)
                                _stringResources.value = updatedResources.toList()
                            }

                            logRepository.log(LogType.INFO, "Translating string [${resource.name}]: '${resource.value}'")

                            val result = groqRepository.translateText(resource.value)
                            result.onSuccess {
                                withContext(Dispatchers.Main) {
                                    updatedResources[index] = resource.copy(
                                        translatedValue = it,
                                        isTranslating = false,
                                        hasError = false
                                    )
                                    _stringResources.value = updatedResources.toList()
                                }
                                logRepository.log(LogType.SUCCESS, "Successfully translated string [${resource.name}]: '$it'")
                            }.onFailure {
                                withContext(Dispatchers.Main) {
                                    updatedResources[index] = resource.copy(
                                        isTranslating = false,
                                        hasError = true
                                    )
                                    _stringResources.value = updatedResources.toList()
                                }
                                logRepository.log(LogType.ERROR, "Error translating string [${resource.name}]: ${it.message}")
                            }
                        }
                    }
                }

                // Wait for all translation jobs to complete
                translationJobs.awaitAll()
                
                _isTranslating.value = false
                return@withContext Result.success(Unit)
            } catch (e: Exception) {
                _isTranslating.value = false
                return@withContext Result.failure(e)
            }
        }
    }
    
    /**
     * Save translated strings to a new XML file
     */
    suspend fun saveTranslatedFile(): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val resources = _stringResources.value
                if (resources.isEmpty()) {
                    return@withContext Result.failure(Exception("No strings to save"))
                }
                
                // Create directory structure in Downloads folder
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val resDir = File(downloadsDir, "res")
                val valuesViDir = File(resDir, "values-vi")
                
                if (!valuesViDir.exists()) {
                    valuesViDir.mkdirs()
                }
                
                val outputFile = File(valuesViDir, "strings.xml")
                
                // Create XML file
                val serializer = XmlPullParserFactory.newInstance().newSerializer()
                val writer = OutputStreamWriter(FileOutputStream(outputFile), "UTF-8")
                
                serializer.setOutput(writer)
                serializer.startDocument("UTF-8", true)
                serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true)
                
                serializer.startTag("", "resources")
                
                for (resource in resources) {
                    serializer.startTag("", "string")
                    serializer.attribute("", "name", resource.name)
                    
                    // Use translated value if available, otherwise use original
                    val value = if (resource.translatedValue.isNotBlank()) {
                        resource.translatedValue
                    } else {
                        resource.value
                    }
                    
                    serializer.text(value)
                    serializer.endTag("", "string")
                }
                
                serializer.endTag("", "resources")
                serializer.endDocument()
                writer.close()
                
                val filePath = outputFile.absolutePath
                logRepository.logInfo("Đã lưu file vào $filePath.")
                
                return@withContext Result.success(filePath)
            } catch (e: Exception) {
                logRepository.logError("Lỗi khi lưu file: ${e.message}")
                return@withContext Result.failure(e)
            }
        }
    }
    
    /**
     * Update a string resource in the list
     */
    fun updateStringResource(index: Int, translatedValue: String) {
        val currentList = _stringResources.value.toMutableList()
        if (index in currentList.indices) {
            val updatedResource = currentList[index].copy(translatedValue = translatedValue)
            currentList[index] = updatedResource
            _stringResources.value = currentList
        }
    }
    
    /**
     * Clear all string resources
     */
    fun clearResources() {
        _stringResources.value = emptyList()
        _selectedFileName.value = null
    }
    
    /**
     * Check if a string contains technical parts that should be preserved during translation
     * 
     * @param value The string value to check
     * @return True if the string contains technical parts
     */
    private fun containsTechnicalParts(value: String): Boolean {
        // Check for strings that contain a mix of translatable text and technical identifiers
        val technicalPatterns = listOf(
            Regex("androidx\\.[a-zA-Z0-9_.]+"),  // androidx package references
            Regex("android\\.[a-zA-Z0-9_.]+"),  // android package references
            Regex("java\\.[a-zA-Z0-9_.]+"),     // java package references
            Regex("kotlin\\.[a-zA-Z0-9_.]+"),   // kotlin package references
            Regex("%[sdfx]"),                    // Format specifiers
            Regex("\\{[^}]*\\}")                // Placeholders in curly braces
        )
        
        return technicalPatterns.any { pattern -> pattern.containsMatchIn(value) }
    }
    
    /**
     * Preprocess a string to mark technical parts that should be preserved during translation
     * 
     * @param value The string value to preprocess
     * @return The preprocessed string with technical parts marked
     */
    private fun preprocessStringWithTechnicalParts(value: String): String {
        // This is a simple implementation that doesn't modify the string
        // The actual preprocessing is handled by the improved prompt in GroqRepository
        return value
    }
    
    /**
     * Get file name from URI
     */
    private fun getFileName(context: Context, uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        result = it.getString(nameIndex)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != -1 && cut != null) {
                result = result?.substring(cut + 1)
            }
        }
        return result ?: "Unknown file"
    }
    
    /**
     * Get the currently selected model
     */
    private suspend fun getSelectedModel(): String {
        return groqRepository.getSelectedModel()
    }
}