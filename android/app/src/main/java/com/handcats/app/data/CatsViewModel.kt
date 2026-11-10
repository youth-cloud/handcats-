package com.handcats.app.data

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.handcats.app.data.remote.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.HttpException
import java.io.File
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import java.util.UUID
import android.os.SystemClock

class CatsViewModel(application: Application) : AndroidViewModel(application) {
    private data class CategoryCache(val items: List<Observation>, val total: Int, val fetchedAt: Long)
    private val cacheTtlMs = 60_000L
    private val categoryCache = mutableMapOf<String, CategoryCache>()
    private var dataCacheToken: String? = null
    private var dataLoadedAt = 0L
    private val photoCache = PhotoCache(File(application.cacheDir, "encounter-photos"), viewModelScope)
    private val store = SessionStore(application)
    private var token: String? = store.readToken()
    val server = store.server
    private val client = ApiClient.client { token }
    private val api = ApiClient.create(server, client)
    var user by mutableStateOf<User?>(null); private set
    var starting by mutableStateOf(true); private set
    var busy by mutableStateOf(false); private set
    var refreshing by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var notice by mutableStateOf<String?>(null); private set
    var cats by mutableStateOf<List<CatType>>(emptyList()); private set
    var observations by mutableStateOf<List<Observation>>(emptyList()); private set
    var total by mutableStateOf(0); private set
    var categoryCode by mutableStateOf<String?>(null); private set
    var categoryObservations by mutableStateOf<List<Observation>>(emptyList()); private set
    var categoryTotal by mutableStateOf(0); private set
    var categoryLoading by mutableStateOf(false); private set
    var categoryFailed by mutableStateOf(false); private set
    private var categoryJob: Job? = null
    private var categoryRequest = 0
    var selected by mutableStateOf<Observation?>(null); private set
    var review by mutableStateOf<ReviewResponse?>(null); private set
    var reviewObservationId by mutableStateOf<String?>(null); private set
    var reviewing by mutableStateOf(false); private set
    var pendingPhoto by mutableStateOf<File?>(null); private set
    private var requestId = UUID.randomUUID().toString()
    var photoRevision by mutableStateOf(0); private set
    init { restore() }

    fun clearMessage() { error = null; notice = null }
    private fun report(e: Exception) {
        // Record the exception type, without URLs, credentials, or response bodies.
        Log.w("HandCats", "Request failed: ${e.javaClass.simpleName}; cause=${e.cause?.javaClass?.simpleName}")
        error = when (e) {
            is HttpException -> {
                if (e.code() == 401 && user != null) clearSession()
                runCatching { ApiClient.json.parseToJsonElement(e.response()?.errorBody()?.string() ?: "").jsonObject["detail"]?.jsonPrimitive?.content }.getOrNull()
                    ?: when (e.code()) { 503 -> "服务暂时不可用，请稍后重试"; 401 -> "请重新登录"; else -> "请求未完成（${e.code()}），请重试" }
            }
            is SocketTimeoutException -> "连接超时，请稍后重试"
            is SSLException -> "安全连接失败，请稍后重试；若持续出现，请联系管理员"
            is ConnectException -> "暂时无法连接拾猫服务，请稍后重试"
            is IOException -> "网络连接失败，请检查网络及应用联网权限后重试"
            is IllegalArgumentException -> e.message ?: "请检查输入内容"
            else -> "操作未完成，请稍后重试"
        }
    }
    private fun action(onSuccess: (() -> Unit)? = null, block: suspend () -> Unit) {
        if (busy || refreshing) return
        busy = true; clearMessage()
        viewModelScope.launch {
            var succeeded = false
            try { block(); succeeded = true } catch (e: CancellationException) { throw e } catch (e: Exception) { report(e) }
            finally { busy = false }
            if (succeeded) onSuccess?.invoke()
        }
    }
    fun restore() {
        starting = true; clearMessage()
        viewModelScope.launch {
            try { if (token != null) { user = api.me(); loadData() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (e is HttpException && e.code() == 401) clearSession(); report(e) }
            finally { starting = false }
        }
    }
    fun authenticate(register: Boolean, username: String, password: String, nickname: String) = action {
        val response = if (register) api.register(Registration(username.trim(), password, nickname.trim())) else api.login(Credentials(username.trim(), password))
        store.saveToken(response.token); token = response.token; user = response.user
        loadData()
    }
    private suspend fun loadData(force: Boolean = false) {
        val sessionToken = token
        if (!force && dataCacheToken == sessionToken && dataLoadedAt > 0L &&
            SystemClock.elapsedRealtime() - dataLoadedAt < cacheTtlMs) return
        coroutineScope {
            val c = async { api.catalog() }; val o = async { api.observations() }
            val newCats = c.await(); val page = o.await()
            if (token == sessionToken) {
                cats = newCats; observations = page.items; total = page.total
                dataCacheToken = sessionToken; dataLoadedAt = SystemClock.elapsedRealtime()
            }
        }
    }
    fun refresh() {
        if (refreshing || busy) return
        refreshing = true; clearMessage()
        viewModelScope.launch {
            try { invalidateCategories(); loadData(force = true) } catch (e: CancellationException) { throw e } catch (e: Exception) { report(e) }
            finally { refreshing = false }
        }
    }
    fun more() = action {
        val page = api.observations(observations.size)
        observations = (observations + page.items).distinctBy { it.id }; total = page.total
    }
    fun loadCategory(code: String, more: Boolean = false, force: Boolean = false) {
        if (categoryLoading && categoryCode == code && !force) return
        if (more && (categoryLoading || categoryCode != code || categoryObservations.size >= categoryTotal)) return
        categoryJob?.cancel()
        val request = ++categoryRequest
        val sessionToken = token
        val cached = categoryCache[code]
        if (!more && !force && cached != null && SystemClock.elapsedRealtime() - cached.fetchedAt < cacheTtlMs) {
            categoryCode = code; categoryObservations = cached.items; categoryTotal = cached.total
            categoryLoading = false; categoryFailed = false
            return
        }
        if (!more) { categoryObservations = emptyList(); categoryTotal = 0 }
        categoryCode = code; categoryLoading = true; categoryFailed = false
        val offset = if (more) categoryObservations.size else 0
        categoryJob = viewModelScope.launch {
            try {
                val page = api.observations(offset = offset, catCode = code)
                if (request == categoryRequest && sessionToken == token) {
                    categoryObservations = (if (more) categoryObservations + page.items else page.items).distinctBy { it.id }
                    categoryTotal = page.total
                    categoryCache[code] = CategoryCache(categoryObservations, categoryTotal,
                        if (more) cached?.fetchedAt ?: SystemClock.elapsedRealtime() else SystemClock.elapsedRealtime())
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (request == categoryRequest && sessionToken == token) { report(e); categoryFailed = true } }
            finally { if (request == categoryRequest) categoryLoading = false }
        }
    }
    fun select(item: Observation?) {
        selected = item
        review = item?.let { pendingReview(it, null) }; reviewObservationId = item?.id
    }
    private fun updateObservation(result: Observation) {
        categoryRequest++; categoryJob?.cancel(); categoryLoading = false
        if (selected?.id == result.id) selected = result
        observations = observations.map { if (it.id == result.id) result else it }
        categoryObservations = categoryObservations.map { if (it.id == result.id) result else it }
        categoryCache.replaceAll { _, cached ->
            cached.copy(items = cached.items.map { if (it.id == result.id) result else it })
        }
    }
    private fun invalidateCategories() {
        categoryRequest++; categoryJob?.cancel(); categoryLoading = false
        categoryCache.clear()
    }
    fun requestReview(item: Observation) = action {
        if ((selected?.takeIf { it.id == item.id } ?: item).let { it.reviewAttempts >= it.reviewLimit }) {
            notice = "这张照片已达到智能复核上限"; return@action
        }
        review = null; reviewObservationId = null; reviewing = true
        val sessionToken = token
        try {
            val result = api.review(item.id)
            if (selected?.id == item.id && token == sessionToken) {
                updateObservation((selected ?: item).copy(reviewAttempts = result.reviewAttempts,
                    reviewLimit = result.reviewLimit, reviewResult = if (result.success) result else item.reviewResult))
                review = result; reviewObservationId = item.id
            }
        } finally {
            reviewing = false
            // One detail refresh after a paid request also reconciles timeouts and
            // quota consumed by another device. Lists continue to use their cache.
            if (token == sessionToken) try {
                val latest = api.observation(item.id)
                if (token == sessionToken) updateObservation(latest)
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }
    fun applyReview(item: Observation, result: ReviewResponse, indices: List<Int>) = action {
        val resultId = result.reviewId ?: return@action
        val updated = try {
            api.applyReview(item.id, ReviewApply(resultId, indices))
        } catch (e: HttpException) {
            if (e.code() == 409) {
                val latest = api.observation(item.id)
                updateObservation(latest)
                if (selected?.id == item.id) {
                    review = pendingReview(latest, null)
                    reviewObservationId = item.id
                }
            }
            throw e
        }
        updateObservation(updated)
        review = null; reviewObservationId = null
        // Accepted review may change the category in which this record belongs.
        invalidateCategories()
        cats = api.catalog()
        categoryCode?.let { loadCategory(it, force = true) }
        notice = "已采用复核结果并收进图鉴"
    }
    fun choosePhoto(file: File) { pendingPhoto?.takeIf { it != file }?.delete(); pendingPhoto = file; requestId = UUID.randomUUID().toString(); photoRevision++; clearMessage() }
    fun discardPhoto() { pendingPhoto?.delete(); pendingPhoto = null; clearMessage() }
    fun importPhoto(uri: Uri) = action {
        val file = withContext(Dispatchers.IO) {
            val result = File.createTempFile("picked_", ".jpg", getApplication<Application>().cacheDir)
            try {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                    result.outputStream().use { output ->
                        val buffer = ByteArray(8192); var bytes = 0
                        while (true) { val n = input.read(buffer); if (n < 0) break; bytes += n
                            require(bytes <= 12 * 1024 * 1024) { "请选择小于 12 MB 的照片" }; output.write(buffer, 0, n) }
                    }
                } ?: throw IOException("无法读取照片")
                require(result.length() > 0) { "照片为空，请重新选择" }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(result.absolutePath, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取这张照片，请选择 JPEG、PNG 或 WebP 图片" }
                require(bounds.outWidth.toLong() * bounds.outHeight <= 25_000_000) { "照片分辨率过大，请选择小于 2500 万像素的照片" }
                result
            } catch (e: Exception) { result.delete(); throw e }
        }
        choosePhoto(file)
    }
    fun upload(onSuccess: () -> Unit) = action {
        val file = pendingPhoto ?: return@action
        val part = MultipartBody.Part.createFormData("file", file.name, file.asRequestBody("image/jpeg".toMediaType()))
        val result = api.upload(part, requestId)
        select(result); discardPhoto()
        val existed = observations.any { it.id == result.id }
        observations = listOf(result) + observations.filterNot { it.id == result.id }; if (!existed) total++
        invalidateCategories()
        onSuccess()
    }
    fun edit(item: Observation, change: ObservationEdit) = action {
        val result = api.edit(item.id, change)
        updateObservation(result)
        if (change.favorite == null) notice = observationEditNotice(change)
        // Favorites and notes do not change catalog counts or category membership.
        if (change.confirmed != null || change.confirmedDetectionIds != null) cats = api.catalog()
    }
    fun delete(item: Observation, done: () -> Unit) = action(onSuccess = done) {
        api.delete(item.id); observations = observations.filterNot { it.id == item.id }; total = (total - 1).coerceAtLeast(0)
        categoryObservations = categoryObservations.filterNot { it.id == item.id }
        selected = null; invalidateCategories()
        token?.let { photoCache.remove(it, item.imageUrl) }
        // Photo deletion has already succeeded. A failed catalog refresh must
        // not strand the user on an empty detail page or invite another delete.
        try { cats = api.catalog() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { report(e) }
    }
    fun nickname(value: String, done: () -> Unit) = action { user = api.nickname(Nickname(value.trim())); notice = "昵称已更新"; done() }
    fun password(old: String, new: String, done: () -> Unit) = action { api.password(PasswordChange(old, new)); clearSession(); notice = "密码已修改，请重新登录"; done() }
    private fun clearSession() {
        categoryRequest++; categoryJob?.cancel(); categoryCode = null; categoryObservations = emptyList()
        categoryTotal = 0; categoryLoading = false; categoryFailed = false
        store.clear(); token = null; user = null; cats = emptyList(); observations = emptyList(); total = 0; selected = null; review = null; reviewObservationId = null
        categoryCache.clear(); dataCacheToken = null; dataLoadedAt = 0L; photoCache.clearMemory(); discardPhoto()
    }
    fun logout(done: () -> Unit) = action { api.logout(); clearSession(); done() }
    fun deleteAccount(password: String, done: () -> Unit) = action { api.deleteAccount(AccountRemoval(password)); clearSession(); notice = "账号及相遇数据已删除"; done() }
    fun forgetLocalSession() { clearSession(); error = null }
    suspend fun image(path: String, preview: Boolean = false): Bitmap? {
        val session = token ?: return null
        return photoCache.get(session, path, preview) {
            val url = server.trimEnd('/') + path
            client.newCall(Request.Builder().url(url).header("Authorization", "Bearer $session").build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body.byteStream().use { stream ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > 12 * 1024 * 1024) throw IOException("Photo too large")
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            }
        }
    }
}
