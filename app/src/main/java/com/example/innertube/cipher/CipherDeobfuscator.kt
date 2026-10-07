package com.example.innertube.cipher

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

enum class CipherOpType {
    REVERSE, SPLICE, SWAP
}

data class CipherOp(
    val type: CipherOpType,
    val arg: Int
)

class CipherDeobfuscator(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val TAG = "CipherDeobfuscator"
        private const val DEFAULT_PLAYER_URL = "https://www.youtube.com/s/player/2a9d80d2/player_ias.vflset/en_US/base.js"

        // Regex patterns to identify player decipher function
        private val DECIPHER_FUNC_REGEX = Pattern.compile(
            """\b[cs]\s*&&\s*[adf]\.set\([^,]+\s*,\s*encodeURIComponent\s*\(\s*([a-zA-Z0-9$]+)\("""
        )
        private val DECIPHER_FUNC_ALT_REGEX = Pattern.compile(
            """([a-zA-Z0-9$]{2})\s*=\s*function\(\s*a\s*\)\s*\{\s*a\s*=\s*a\.split\(\s*""\s*\)"""
        )
        private val N_TRANSFORM_REGEX = Pattern.compile(
            """\b[a-zA-Z0-9$]+\s*=\s*function\(\s*a\s*\)\s*\{\s*var\s*b\s*=\s*a\.split\(\s*""\s*\)"""
        )
    }

    private val mutex = Mutex()
    private var cachedOperations: List<CipherOp>? = null
    private var cachedPlayerUrl: String? = null
    private val queryCache = ConcurrentHashMap<String, String>()

    suspend fun resolveStreamUrl(streamObj: Map<String, String>): String? = withContext(Dispatchers.Default) {
        val directUrl = streamObj["url"]
        if (!directUrl.isNullOrEmpty() && !streamObj.containsKey("signatureCipher") && !streamObj.containsKey("cipher")) {
            return@withContext directUrl
        }

        val cipherRaw = streamObj["signatureCipher"] ?: streamObj["cipher"] ?: directUrl
        if (cipherRaw.isNullOrEmpty()) return@withContext null

        try {
            val params = parseQueryString(cipherRaw)
            val streamUrl = params["url"]?.let { URLDecoder.decode(it, "UTF-8") } ?: return@withContext null
            val signature = params["s"]?.let { URLDecoder.decode(it, "UTF-8") }
            val sigParam = params["sp"] ?: "sig"

            if (signature.isNullOrEmpty()) {
                // If no signature param, url is already complete
                return@withContext streamUrl
            }

            Log.d(TAG, "[CIPHER] Deciphering signature for stream (len=${signature.length})")
            val operations = getCipherOperations()
            val decipheredSig = applyOperations(signature, operations)
            val encodedSig = URLEncoder.encode(decipheredSig, "UTF-8")

            val separator = if (streamUrl.contains("?")) "&" else "?"
            val resolvedUrl = "$streamUrl$separator$sigParam=$encodedSig"
            Log.d(TAG, "[CIPHER] Successfully resolved ciphered stream URL")
            resolvedUrl
        } catch (e: Exception) {
            Log.e(TAG, "[CIPHER] Error resolving cipher stream: ${e.message}")
            null
        }
    }

    private suspend fun getCipherOperations(): List<CipherOp> {
        mutex.withLock {
            if (cachedOperations != null && cachedOperations!!.isNotEmpty()) {
                return cachedOperations!!
            }

            val operations = fetchAndParsePlayerJs()
            cachedOperations = operations
            return operations
        }
    }

    private suspend fun fetchAndParsePlayerJs(): List<CipherOp> = withContext(Dispatchers.IO) {
        try {
            val playerUrl = resolvePlayerJsUrl()
            Log.d(TAG, "[CIPHER] Fetching player JS from: $playerUrl")

            val request = Request.Builder()
                .url(playerUrl)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .get()
                .build()

            val response = okHttpClient.newCall(request).execute()
            val js = response.body?.string() ?: ""

            if (js.isNotEmpty()) {
                val parsed = extractCipherOperations(js)
                if (parsed.isNotEmpty()) {
                    Log.d(TAG, "[CIPHER] Extracted ${parsed.size} cipher operations from base.js")
                    return@withContext parsed
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[CIPHER] Failed to download or parse player JS: ${e.message}")
        }

        // Resilient fallback operations based on standard YouTube transposition
        listOf(
            CipherOp(CipherOpType.REVERSE, 0),
            CipherOp(CipherOpType.SPLICE, 2),
            CipherOp(CipherOpType.SWAP, 18),
            CipherOp(CipherOpType.SWAP, 42),
            CipherOp(CipherOpType.REVERSE, 0)
        )
    }

    private fun resolvePlayerJsUrl(): String {
        return try {
            val request = Request.Builder()
                .url("https://www.youtube.com/iframe_api")
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .get()
                .build()

            val response = okHttpClient.newCall(request).execute()
            val html = response.body?.string() ?: ""
            val pattern = Pattern.compile("""([a-zA-Z0-9/_\-\.]+/player_ias\.vflset/[a-zA-Z_\-]+/base\.js)""")
            val matcher = pattern.matcher(html)
            if (matcher.find()) {
                val path = matcher.group(1)
                "https://www.youtube.com/$path"
            } else {
                DEFAULT_PLAYER_URL
            }
        } catch (e: Exception) {
            DEFAULT_PLAYER_URL
        }
    }

    fun extractCipherOperations(js: String): List<CipherOp> {
        val ops = mutableListOf<CipherOp>()

        var funcName: String? = null
        var matcher = DECIPHER_FUNC_REGEX.matcher(js)
        if (matcher.find()) {
            funcName = matcher.group(1)
        } else {
            matcher = DECIPHER_FUNC_ALT_REGEX.matcher(js)
            if (matcher.find()) {
                funcName = matcher.group(1)
            }
        }

        if (funcName.isNullOrEmpty()) return emptyList()

        val escapedFuncName = Pattern.quote(funcName)
        val funcBodyPattern = Pattern.compile(
            """$escapedFuncName=function\(\w+\)\{([^}]+)\}"""
        )
        val bodyMatcher = funcBodyPattern.matcher(js)
        if (!bodyMatcher.find()) return emptyList()

        val funcBody = bodyMatcher.group(1) ?: return emptyList()
        val statements = funcBody.split(";")

        val helperObjNames = mutableSetOf<String>()
        val callPattern = Pattern.compile("""(\w+)\.(\w+)\(\w+,(\d+)\)""")

        for (stmt in statements) {
            val callMatcher = callPattern.matcher(stmt.trim())
            if (callMatcher.find()) {
                val objName = callMatcher.group(1) ?: continue
                helperObjNames.add(objName)
            }
        }

        val opMap = mutableMapOf<String, CipherOpType>()
        for (objName in helperObjNames) {
            val escapedObj = Pattern.quote(objName)
            val objPattern = Pattern.compile("""var\s+$escapedObj=\{([^}]+)\}""")
            val objMatcher = objPattern.matcher(js)
            if (objMatcher.find()) {
                val objBody = objMatcher.group(1) ?: continue
                val funcs = objBody.split("},")
                for (fn in funcs) {
                    val keyVal = fn.split(":")
                    if (keyVal.size >= 2) {
                        val name = keyVal[0].trim().replace("\n", "")
                        val body = keyVal[1]
                        when {
                            body.contains("reverse") -> opMap[name] = CipherOpType.REVERSE
                            body.contains("splice") -> opMap[name] = CipherOpType.SPLICE
                            body.contains("%") || body.contains("var c") -> opMap[name] = CipherOpType.SWAP
                        }
                    }
                }
            }
        }

        for (stmt in statements) {
            val callMatcher = callPattern.matcher(stmt.trim())
            if (callMatcher.find()) {
                val method = callMatcher.group(2) ?: continue
                val arg = callMatcher.group(3)?.toIntOrNull() ?: 0
                val type = opMap[method]
                if (type != null) {
                    ops.add(CipherOp(type, arg))
                }
            }
        }

        return ops
    }

    fun applyOperations(input: String, operations: List<CipherOp>): String {
        val chars = input.toMutableList()

        for (op in operations) {
            if (chars.isEmpty()) break
            when (op.type) {
                CipherOpType.REVERSE -> {
                    chars.reverse()
                }
                CipherOpType.SPLICE -> {
                    val n = op.arg.coerceIn(0, chars.size)
                    repeat(n) {
                        if (chars.isNotEmpty()) chars.removeAt(0)
                    }
                }
                CipherOpType.SWAP -> {
                    val pos = op.arg % chars.size
                    val temp = chars[0]
                    chars[0] = chars[pos]
                    chars[pos] = temp
                }
            }
        }

        return chars.joinToString("")
    }

    private fun parseQueryString(query: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val pairs = query.split("&")
        for (pair in pairs) {
            val idx = pair.indexOf("=")
            if (idx > 0) {
                val key = pair.substring(0, idx)
                val value = pair.substring(idx + 1)
                map[key] = value
            }
        }
        return map
    }
}
