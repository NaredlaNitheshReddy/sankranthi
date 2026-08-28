package com.example.sankranthi.data

import android.content.Context
import com.example.sankranthi.data.repo.AuthRepository
import com.example.sankranthi.data.repo.LedgerRepository
import com.example.sankranthi.data.repo.MembersRepository
import com.example.sankranthi.data.repo.demo.DemoAuthRepository
import com.example.sankranthi.data.repo.demo.DemoBackend
import com.example.sankranthi.data.repo.demo.DemoLedgerRepository
import com.example.sankranthi.data.repo.demo.DemoMembersRepository
import com.example.sankranthi.data.repo.supabase.SupabaseAuthRepository
import com.example.sankranthi.data.repo.supabase.SupabaseLedgerRepository
import com.example.sankranthi.data.repo.supabase.SupabaseMembersRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.serializer.KotlinXSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json

/**
 * Hand-rolled dependency container. The app is small enough that a DI framework
 * would be more ceremony than help; if it grows, this is the single seam to
 * replace with Hilt.
 *
 * Which backend is live depends purely on whether Supabase credentials were
 * present at build time — see [AppConfig].
 */
object ServiceLocator {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var initialised = false

    lateinit var authRepository: AuthRepository
        private set

    lateinit var membersRepository: MembersRepository
        private set

    lateinit var ledgerRepository: LedgerRepository
        private set

    var googleSignInClient: GoogleSignInClient? = null
        private set

    /** True when the app is running on the in-memory demo backend. */
    val usingDemoBackend: Boolean get() = !AppConfig.hasSupabase

    fun init(context: Context) {
        if (initialised) return
        initialised = true

        if (AppConfig.hasSupabase) {
            val client = buildClient()
            authRepository = SupabaseAuthRepository(client, scope)
            membersRepository = SupabaseMembersRepository(client)
            ledgerRepository = SupabaseLedgerRepository(client)
            googleSignInClient =
                if (AppConfig.hasGoogleSignIn) GoogleSignInClient(context.applicationContext) else null
        } else {
            val backend = DemoBackend()
            authRepository = DemoAuthRepository(backend)
            membersRepository = DemoMembersRepository(backend)
            ledgerRepository = DemoLedgerRepository(backend)
            googleSignInClient = null
        }
    }

    private fun buildClient(): SupabaseClient =
        createSupabaseClient(
            supabaseUrl = AppConfig.supabaseUrl,
            supabaseKey = AppConfig.supabaseAnonKey,
        ) {
            // Rows carry audit columns the client models do not declare; without
            // this a schema addition would break every decode.
            defaultSerializer = KotlinXSerializer(Json { ignoreUnknownKeys = true })
            install(Auth)
            install(Postgrest)
        }
}
