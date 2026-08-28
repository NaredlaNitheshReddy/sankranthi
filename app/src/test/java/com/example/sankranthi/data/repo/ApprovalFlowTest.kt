package com.example.sankranthi.data.repo

import com.example.sankranthi.data.model.AccessStatus
import com.example.sankranthi.data.model.Permission
import com.example.sankranthi.data.repo.demo.DemoAuthRepository
import com.example.sankranthi.data.repo.demo.DemoBackend
import com.example.sankranthi.data.repo.demo.DemoMembersRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the request-then-approve journey against the demo backend, which
 * mirrors the rules the database enforces.
 */
class ApprovalFlowTest {

    private val backend = DemoBackend()
    private val auth = DemoAuthRepository(backend)
    private val members = DemoMembersRepository(backend)

    @Test
    fun `a new account arrives pending and cannot edit`() = runTest {
        auth.signInAsDemo("newcomer@example.com")

        val signedIn = auth.session.value as SessionState.SignedIn
        assertEquals(AccessStatus.PENDING, signedIn.profile.status)
        assertFalse(signedIn.profile.isApproved)
        Permission.entries.forEach { assertFalse(signedIn.profile.can(it)) }
    }

    @Test
    fun `a new account shows up in the admin pending queue`() = runTest {
        auth.signInAsDemo("newcomer@example.com")

        val pending = members.pendingRequests()
        assertTrue(pending.any { it.email == "newcomer@example.com" })
    }

    @Test
    fun `approving with permissions admits the member and grants only those rights`() = runTest {
        auth.signInAsDemo("newcomer@example.com")
        val request = members.pendingRequests().first { it.email == "newcomer@example.com" }

        members.setStatus(request.id, AccessStatus.APPROVED)
        members.setPermissions(request.id, setOf(Permission.EDIT_EXPENSES))
        auth.reloadProfile()

        val profile = (auth.session.value as SessionState.SignedIn).profile
        assertTrue(profile.isApproved)
        assertTrue(profile.can(Permission.EDIT_EXPENSES))
        assertFalse(profile.can(Permission.EDIT_LIVESTOCK))
        assertFalse(profile.can(Permission.DELETE_ENTRIES))
    }

    @Test
    fun `rejecting keeps the account out`() = runTest {
        auth.signInAsDemo("newcomer@example.com")
        val request = members.pendingRequests().first { it.email == "newcomer@example.com" }

        members.setStatus(request.id, AccessStatus.REJECTED)
        auth.reloadProfile()

        val profile = (auth.session.value as SessionState.SignedIn).profile
        assertEquals(AccessStatus.REJECTED, profile.status)
        assertFalse(profile.isApproved)
    }

    @Test
    fun `an approved account is no longer in the pending queue`() = runTest {
        auth.signInAsDemo("newcomer@example.com")
        val request = members.pendingRequests().first { it.email == "newcomer@example.com" }

        members.setStatus(request.id, AccessStatus.APPROVED)

        assertFalse(members.pendingRequests().any { it.id == request.id })
        assertTrue(members.decidedMembers().any { it.id == request.id })
    }

    @Test
    fun `revoking a member sends them back to pending with no rights`() = runTest {
        auth.signInAsDemo("newcomer@example.com")
        val request = members.pendingRequests().first { it.email == "newcomer@example.com" }
        members.setStatus(request.id, AccessStatus.APPROVED)
        members.setPermissions(request.id, Permission.entries.toSet())

        members.setStatus(request.id, AccessStatus.PENDING)
        members.setPermissions(request.id, emptySet())
        auth.reloadProfile()

        val profile = (auth.session.value as SessionState.SignedIn).profile
        assertEquals(AccessStatus.PENDING, profile.status)
        Permission.entries.forEach { assertFalse(profile.can(it)) }
    }

    @Test
    fun `signing in twice with the same address reuses the request`() = runTest {
        auth.signInAsDemo("newcomer@example.com")
        val first = (auth.session.value as SessionState.SignedIn).profile

        auth.signOut()
        auth.signInAsDemo("newcomer@example.com")
        val second = (auth.session.value as SessionState.SignedIn).profile

        assertEquals(first.id, second.id)
        assertEquals(1, members.pendingRequests().count { it.email == "newcomer@example.com" })
    }

    @Test
    fun `signing out clears the session`() = runTest {
        auth.signInAsDemo("newcomer@example.com")
        auth.signOut()
        assertEquals(SessionState.SignedOut, auth.session.value)
    }

    @Test
    fun `the seeded admin is approved and holds every right`() = runTest {
        auth.signInAsDemo(DemoBackend.DEMO_ADMIN_EMAIL)

        val profile = (auth.session.value as SessionState.SignedIn).profile
        assertTrue(profile.isAdmin)
        assertTrue(profile.isApproved)
        Permission.entries.forEach { assertTrue(profile.can(it)) }
    }
}
