package com.vtu.translate.data.repository

import android.util.Log
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.vtu.translate.data.model.ChatCompletionRequest
import com.vtu.translate.data.model.ChatCompletionResponse
import com.vtu.translate.data.model.ChatMessage
import com.vtu.translate.data.model.GroqModelsResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * Repository for interacting with the Groq API
 * 
 * OPTIMIZED WITH 6 PRINCIPLES OF EXCELLENT TRANSLATION
 */
@OptIn(ExperimentalSerializationApi::class)
class GroqRepository(private val preferencesRepository: PreferencesRepository) {
    
    companion object {
        private const val BASE_URL = "https://api.groq.com/openai/v1/"
        
        // Context map for UI components
        private val CONTEXT_MAPPING = mapOf(
            "btn_" to "button",
            "title_" to "title",
            "hint_" to "hint",
            "error_" to "error",
            "msg_" to "message",
            "label_" to "label",
            "tab_" to "tab"
        )
    }
    
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }
    
    private val contentType = "application/json".toMediaType()
    
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }
    
    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .retryOnConnectionFailure(true)
        .build()
    
    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory(contentType))
        .build()
    
    private val groqService = retrofit.create(GroqService::class.java)
    
    /**
     * Get available models from Groq API
     */
    suspend fun getModels(): Result<GroqModelsResponse> {
        return try {
            val apiKey = preferencesRepository.apiKey.first()
            if (apiKey.isBlank()) {
                return Result.failure(Exception("API Key is not set"))
            }
            
            val response = groqService.getModels("Bearer $apiKey")
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Translates the given text from one language to another using the Groq API, applying context-aware translation principles.
     *
     * Analyzes the optional string resource name to infer UI context, then constructs a detailed translation prompt that incorporates six principles of excellent translation, including technical preservation and context-specific guidelines. Returns the translated text or a failure if the API key or model is missing, or if an error occurs during the request.
     *
     * @param text The text to translate.
     * @param fromLanguage The source language code.
     * @param toLanguage The target language code.
     * @param stringName Optional string resource name used to determine UI context for improved translation accuracy.
     * @return A [Result] containing the translated text on success, or an exception on failure.
     */
    suspend fun translateTextWithLanguages(
        text: String, 
        fromLanguage: String, 
        toLanguage: String,
        stringName: String? = null // Add string name parameter to parse context
    ): Result<String> {
        return try {
            val apiKey = preferencesRepository.apiKey.first()
            if (apiKey.isBlank()) {
                return Result.failure(Exception("API Key is not set"))
            }
            
            val model = preferencesRepository.selectedModel.first()
            if (model.isBlank()) {
                return Result.failure(Exception("Model is not selected"))
            }
            
            // Create prompt based on string name analysis and translation rules
            val (contextType, contextInfo) = analyzeStringContext(stringName)
            val prompt = buildTranslationPrompt(text, fromLanguage, toLanguage, contextType, contextInfo)
            
            val request = ChatCompletionRequest(
                model = model,
                messages = listOf(ChatMessage(role = "user", content = prompt)),
                temperature = 0.7
            )
            
            // Handling retries with exponential backoff
            return executeWithRetry(apiKey, request)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Constructs a detailed translation prompt for the Groq API, applying six key translation principles and context-specific guidelines.
     *
     * The prompt includes preservation of technical elements, context type and information, length optimization rules, Vietnamese language usage, and output formatting. Additional UI context-specific rules are appended based on the provided context type.
     *
     * @param text The original string resource value to be translated.
     * @param fromLanguage The source language code.
     * @param toLanguage The target language code.
     * @param contextType The inferred UI context type (e.g., button, title), or null if unknown.
     * @param contextInfo Detailed context information about the string resource.
     * @return A formatted prompt string for use with the translation API.
     */
    private fun buildTranslationPrompt(
        text: String,
        fromLanguage: String,
        toLanguage: String,
        contextType: String?,
        contextInfo: String
    ): String {
        val languageMap = mapOf(
            "vi" to "Vietnamese", "en" to "English", "es" to "Spanish",
            "fr" to "French", "de" to "German", "ja" to "Japanese",
            "ko" to "Korean", "zh" to "Chinese", "ru" to "Russian"
        )
        
        val fromLangName = languageMap[fromLanguage] ?: fromLanguage
        val toLangName = languageMap[toLanguage] ?: toLanguage
        
        // Core Prompt with 6 Golden Rules
        return """
        Translate the following Android string resource value from $fromLangName to $toLangName.
        
        **CRITICAL RULES**:
        1. PRESERVE technical elements:
           - Placeholders: %s, %d, %1$s, {0}, {count}
           - HTML tags: <b>, <i>, <a href="...">
           - Special chars: \n, \', \"
           - Package names: androidx.*, com.*
           - URLs, class names, variables
        2. CONTEXT TYPE: $contextType
        3. CONTEXT INFO: $contextInfo
        4. LENGTH OPTIMIZATION:
           - Buttons: ≤12 chars
           - Titles: ≤25 chars
           - Messages: ≤180% original
        5. VIETNAMESE Only: Use "bạn", avoid dialects
        6. OUTPUT: Only translated text, no explanations
        
        **ADDITIONAL GUIDELINES**:
        ${getContextSpecificRules(contextType)}
        
        Original text: "$text"
        """.trimIndent()
    }
    
    /**
     * Analyzes a string resource name to infer its UI context and provide descriptive context information.
     *
     * @param stringName The resource name to analyze, or null if unavailable.
     * @return A pair containing the detected context type key (or null) and a detailed context info string.
     */
    private fun analyzeStringContext(stringName: String?): Pair<String?, String> {
        if (stringName == null) return Pair(null, "No context info")
        
        // Analyze name structure according to principle 2
        val components = stringName.split("_")
        val contextType = CONTEXT_MAPPING.keys.firstOrNull { stringName.contains(it) }
        
        // Create detailed contextual information
        val contextInfo = buildString {
            append("Name structure: ${components.joinToString(" → ")}")
            contextType?.let { append("\nDetected UI element: ${CONTEXT_MAPPING[it]}") }
        }
        
        return Pair(contextType, contextInfo)
    }
    
    /**
     * Returns translation guidelines tailored to the specified UI context type.
     *
     * Provides context-specific rules for translating UI strings such as buttons, titles, errors, and hints to ensure appropriate tone, length, and style. Defaults to general clarity guidelines if the context type is unrecognized.
     *
     * @param contextType The UI context type prefix (e.g., "btn_", "title_", "error_", "hint_"), or null for general context.
     * @return A string containing translation instructions relevant to the given context type.
     */
    private fun getContextSpecificRules(contextType: String?): String {
        return when (contextType) {
            "btn_" -> "• Translate as imperative verb (e.g., 'Save' → 'Lưu')\n" +
                      "• Max 2 words\n" +
                      "• Examples: 'Try Again' → 'Thử lại', 'Send' → 'Gửi'"
                      
            "title_" -> "• Translate as noun phrase (e.g., 'Account Settings' → 'Cài đặt tài khoản')\n" +
                        "• Capitalize first letter\n" +
                        "• Max 4 words"
                        
            "error_" -> "• Translate as complete sentence\n" +
                         "• Include solution if possible\n" +
                         "• Examples: 'Network error' → 'Lỗi mạng'"
                         
            "hint_" -> "• Short phrase ending with '...' if appropriate\n" +
                        "• Examples: 'Search' → 'Tìm kiếm...'"
                        
            else -> "• Use natural, conversational language\n" +
                    "• Prioritize clarity over literal translation"
        }
    }
    
    /**
     * Executes a chat completion request with up to three retries on HTTP 429 (rate limit) errors using exponential backoff.
     *
     * Attempts to obtain a translation from the Groq API, retrying on rate limiting and returning the translated text on success.
     *
     * @param apiKey The API key used for authorization.
     * @param request The chat completion request to send.
     * @return A [Result] containing the translated text on success, or an exception on failure.
     */
    private suspend fun executeWithRetry(
        apiKey: String,
        request: ChatCompletionRequest
    ): Result<String> {
        val maxRetries = 3
        var retryCount = 0
        var lastException: Exception? = null
        
        while (retryCount < maxRetries) {
            try {
                val response = groqService.createChatCompletion("Bearer $apiKey", request)
                
                if (response.choices.isNotEmpty()) {
                    return Result.success(response.choices[0].message.content.trim())
                } else {
                    return Result.failure(Exception("No response from API"))
                }
            } catch (e: HttpException) {
                if (e.code() == 429) {
                    lastException = Exception("Rate limit exceeded (HTTP 429). Retrying...")
                    Log.w("GroqRepository", "HTTP 429 received, retrying after delay. Attempt ${retryCount + 1}/$maxRetries")
                    
                    val delayMs = (1000L * Math.pow(2.0, retryCount.toDouble())).toLong()
                    delay(delayMs)
                    retryCount++
                } else {
                    return Result.failure(e)
                }
            } catch (e: Exception) {
                return Result.failure(e)
            }
        }
        
        return Result.failure(lastException ?: Exception("Failed after $maxRetries retries"))
    }
    
    /**
     * Translates English text to Vietnamese using the Groq API.
     *
     * This legacy method defaults to translating from English ("en") to Vietnamese ("vi") and optionally accepts a string resource name to provide UI context for improved translation quality.
     *
     * @param text The English text to translate.
     * @param stringName Optional resource name used to infer UI context for the translation.
     * @return The translation result as a [Result] containing the translated text or an error.
     */
    suspend fun translateText(
        text: String,
        stringName: String? = null // Add string name parameter
    ): Result<String> {
        return translateTextWithLanguages(text, "en", "vi", stringName)
    }
    
    /**
     * Get the currently selected model
     */
    suspend fun getSelectedModel(): String {
        return preferencesRepository.selectedModel.first()
    }
    
    /**
     * Groq API service interface
     */
    private interface GroqService {
        @GET("models")
        suspend fun getModels(@Header("Authorization") authorization: String): GroqModelsResponse
        
        /**
         * Sends a chat completion request to the Groq API and returns the response.
         *
         * @param authorization The authorization token for the Groq API.
         * @param request The chat completion request payload.
         * @return The response containing the chat completion result.
         */
        @POST("chat/completions")
        suspend fun createChatCompletion(
            @Header("Authorization") authorization: String,
            @Body request: ChatCompletionRequest
        ): ChatCompletionResponse
    }
}
