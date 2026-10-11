package io.github.xomel45.openschool

import android.app.Application
import android.webkit.CookieManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.openschool_bridge.BridgeException
import uniffi.openschool_bridge.Client
import uniffi.openschool_bridge.Student

sealed interface Screen {
    data object Boot : Screen
    /** The first screen of a signed-out app: what the app is and the button that opens the Gosuslugi window. */
    data class Login(val message: String? = null) : Screen
    /** The Gosuslugi/ESIA window itself. */
    data object Web : Screen
    data class Home(val data: SchoolData, val students: List<Student> = listOf(data.student)) : Screen
    /** Gosuslugi could not be reached or answered with an error; the saved session is kept. */
    data class Failed(val text: String) : Screen
}

class AppViewModel(private val app: Application) : AndroidViewModel(app) {
    private val store = SessionStore(app)
    val news = NewsStore(app)
    val net = NetPolicy(app)
    val dev = DevStore(app)
    init { AiLog.enabled = AiLog.flag || dev.enabled }
    var client = net.newClient()
        private set
    private val _screen = MutableStateFlow<Screen>(Screen.Boot)
    val screen: StateFlow<Screen> = _screen

    init {
        start()
    }

    /** Try the saved session; fall back to the login screen. */
    fun start() {
        _screen.value = Screen.Boot
        // Every Bridge call runs on IO: the TLS check on Android may fetch revocation lists over the network, which the system forbids on the main thread.
        viewModelScope.launch(Dispatchers.IO) {
            val header = store.load()
            if (header == null) {
                _screen.value = Screen.Login()
                return@launch
            }
            client.setSessionFromHeader(header)
            _screen.value = openHome(onRejected = { Screen.Login("Сессия истекла. Войдите снова.") })
        }
    }

    /** The server rejected the session while data was loading. */
    fun expired() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.clear() }
            _screen.value = Screen.Login("Сессия истекла. Войдите снова.")
        }
    }

    /** Settle the route first (a foreign address means no proxy for the login window), then open the Gosuslugi window. */
    /** Debug builds only: invented student and weeks, so every screen can be tried without an account (and without anyone's data). */
    fun startDemo(offline: Boolean = false) {
        _screen.value = Screen.Home(SchoolData(DemoSource(offline), DemoSource.student, DemoSource.classInfo, app, dev))
    }

    fun openWeb() {
        viewModelScope.launch {
            if (net.mode == NetMode.Auto) _screen.value = Screen.Boot
            net.resolve()
            net.applyToWebView(app)
            client = net.newClient()
            _screen.value = Screen.Web
        }
    }

    fun closeWeb() { _screen.value = Screen.Login() }

    /** The login WebView reached the diary: hand its cookies to the Bridge and let the server decide. */
    suspend fun tryLogin(header: String): Boolean = withContext(Dispatchers.IO) {
        client = net.newClient() // a clean cookie jar for every attempt
        client.setSessionFromHeader(header)
        val ok = try { AiLog.timed("login_check") { client.checkSession() } } catch (e: BridgeException) { false }
        if (ok) {
            store.save(header)
            news.expired = false // a fresh session: the background check may run again
            NewsWorker.schedule(app, news.settings)
            _screen.value = openHome(onRejected = { Screen.Login("Госуслуги не приняли вход. Попробуйте ещё раз.") })
        }
        ok
    }

    fun logout() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.clear(); news.reset(); appPrefs.edit().remove("studentId").apply(); WeekCache(app).clear() }
            NewsWorker.cancel(app)
            CookieManager.getInstance().removeAllCookies(null) // or the next login signs the same account straight back in
            CookieManager.getInstance().flush()
            client = net.newClient()
            _screen.value = Screen.Login()
        }
    }

    /** Which student of the account is shown (an account can have several); kept for the next start and for the background check. */
    private val appPrefs = app.getSharedPreferences("app", android.content.Context.MODE_PRIVATE)

    fun selectStudent(id: String) {
        appPrefs.edit().putString("studentId", id).apply()
        viewModelScope.launch(Dispatchers.IO) { _screen.value = openHome(onRejected = { Screen.Login("Сессия истекла. Войдите снова.") }) }
    }

    /** Ask the server whether the saved session still works; the text is for the user. */
    suspend fun checkSession(): String = withContext(Dispatchers.IO) {
        try { if (AiLog.timed("check_session") { client.checkSession() }) "Сессия действует." else "Сессия не принимается сервером. Выйдите и войдите снова." }
        catch (e: BridgeException.Network) { if (e.v1.startsWith("HTTP ")) "Госуслуги ответили ошибкой: ${e.v1}" else "Нет связи с Госуслугами." }
        catch (e: BridgeException) { "Неожиданный ответ Госуслуг." }
    }

    private suspend fun openHome(onRejected: () -> Screen): Screen = try {
        val all = AiLog.timed("students") { client.students() }
        val student = all.firstOrNull { it.id == appPrefs.getString("studentId", null) } ?: all.firstOrNull()
        if (student != null) {
            val info = try { AiLog.timed("class_info") { client.classInfo(student.id, academicYear().toUInt()) } } catch (e: BridgeException.Network) { null }
            NewsWorker.schedule(app, news.settings) // the background check runs while the session lives
            Screen.Home(SchoolData(WeekSource { id, y, w -> AiLog.timed("week") { client.week(id, y, w) } }, student, info, app, dev), all)
        }
        else Screen.Login("К этому аккаунту не привязан ученик. Войдите под аккаунтом ученика.")
    } catch (e: BridgeException.Auth) {
        withContext(Dispatchers.IO) { store.clear() }
        onRejected()
    } catch (e: BridgeException.Network) {
        Screen.Failed(if (e.v1.startsWith("HTTP ")) "Госуслуги ответили ошибкой: ${e.v1}" else "Не удалось связаться с Госуслугами. Проверьте интернет.")
    } catch (e: BridgeException) {
        Screen.Failed("Неожиданный ответ Госуслуг: ${e.message}")
    }
}

/** The school year is named after the year it starts in (2026 for 2026/2027); it starts in September. */
private fun academicYear(): Int = java.time.LocalDate.now().let { if (it.monthValue >= 8) it.year else it.year - 1 }
