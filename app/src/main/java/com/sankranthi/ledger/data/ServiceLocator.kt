package com.sankranthi.ledger.data

import android.content.Context
import com.sankranthi.ledger.data.local.AppDatabase
import com.sankranthi.ledger.data.repo.AuthRepository
import com.sankranthi.ledger.data.repo.MembersRepository
import com.sankranthi.ledger.data.repo.demo.DemoAuthRepository
import com.sankranthi.ledger.data.repo.demo.DemoBackend
import com.sankranthi.ledger.data.repo.demo.DemoMembersRepository
import com.sankranthi.ledger.data.repo.supabase.SupabaseAuthRepository
import com.sankranthi.ledger.data.repo.supabase.SupabaseMembersRepository
import com.sankranthi.ledger.data.repository.LedgerRepository
import com.sankranthi.ledger.security.CredentialStore
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.serializer.KotlinXSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json

/**
 * Hand-rolled dependency container. The app is small enough that a DI framework
 * would be more ceremony than help; if it grows, this is the single seam to
 * replace with Hilt.
 *
 * Note the asymmetry, which is intentional: **the ledger is always local.**
 * [ledgerRepository] is Room-backed regardless of which backend is configured,
 * because the UI must never read from the network (§31.1–31.2). Only
 * authentication currently varies by backend; the ledger's remote half arrives
 * in Phase 4 as a `RemoteDataSource` behind the sync layer, not here.
 */
object ServiceLocator {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var initialised = false

    lateinit var database: AppDatabase
        private set

    lateinit var ledgerRepository: LedgerRepository
        private set

    /**
     * The encrypted gateway credential. Phase 5's sync layer reads it headlessly;
     * sign-in writes it once while the app has UI.
     */
    lateinit var credentialStore: CredentialStore
        private set

    lateinit var authRepository: AuthRepository
        private set

    lateinit var membersRepository: MembersRepository
        private set

    var googleSignInClient: GoogleSignInClient? = null
        private set

    /** True when authentication is running on the in-memory demo backend. */
    val usingDemoBackend: Boolean get() = !AppConfig.hasSupabase

    fun init(context: Context) {
        if (initialised) return
        initialised = true

        val appContext = context.applicationContext

        database = AppDatabase.build(appContext)
        ledgerRepository = LedgerRepository(database)
        credentialStore = CredentialStore.create(appContext)

        if (AppConfig.hasSupabase) {
            val client = buildClient()
            authRepository = SupabaseAuthRepository(client, scope)
            membersRepository = SupabaseMembersRepository(client)
            googleSignInClient =
                if (AppConfig.hasGoogleSignIn) GoogleSignInClient(appContext) else null
        } else {
            val backend = DemoBackend()
            authRepository = DemoAuthRepository(backend)
            membersRepository = DemoMembersRepository(backend)
            googleSignInClient = null
        }
    }

    /** Test seam: lets instrumented tests supply an in-memory database. */
    fun initForTest(database: AppDatabase, credentialStore: CredentialStore) {
        this.database = database
        this.credentialStore = credentialStore
        ledgerRepository = LedgerRepository(database)
        val backend = DemoBackend()
        authRepository = DemoAuthRepository(backend)
        membersRepository = DemoMembersRepository(backend)
        googleSignInClient = null
        initialised = true
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
