package com.example.sankranthi.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePermissionsTest {

    private fun profile(
        role: Role = Role.MEMBER,
        status: AccessStatus = AccessStatus.APPROVED,
        permissions: List<String> = emptyList(),
    ) = Profile(
        id = "u1",
        email = "partner@example.com",
        role = role,
        status = status,
        permissions = permissions,
    )

    @Test
    fun `granted permission allows the action`() {
        val member = profile(permissions = listOf(Permission.EDIT_LIVESTOCK.wire))
        assertTrue(member.can(Permission.EDIT_LIVESTOCK))
    }

    @Test
    fun `permission not granted denies the action`() {
        val member = profile(permissions = listOf(Permission.EDIT_LIVESTOCK.wire))
        assertFalse(member.can(Permission.EDIT_EXPENSES))
        assertFalse(member.can(Permission.DELETE_ENTRIES))
    }

    @Test
    fun `admins hold every permission without them being listed`() {
        val admin = profile(role = Role.ADMIN)
        Permission.entries.forEach { assertTrue(admin.can(it)) }
    }

    @Test
    fun `pending accounts can do nothing even if permissions were set`() {
        val pending = profile(
            status = AccessStatus.PENDING,
            permissions = Permission.entries.map(Permission::wire),
        )
        Permission.entries.forEach { assertFalse(pending.can(it)) }
    }

    @Test
    fun `rejected accounts can do nothing`() {
        val rejected = profile(
            status = AccessStatus.REJECTED,
            permissions = Permission.entries.map(Permission::wire),
        )
        Permission.entries.forEach { assertFalse(rejected.can(it)) }
    }

    @Test
    fun `a pending admin is not treated as an admin for access`() {
        val pendingAdmin = profile(role = Role.ADMIN, status = AccessStatus.PENDING)
        assertFalse(pendingAdmin.isApproved)
        Permission.entries.forEach { assertFalse(pendingAdmin.can(it)) }
    }

    @Test
    fun `unknown permission strings are ignored rather than crashing`() {
        val member = profile(permissions = listOf("edit_livestock", "launch_missiles"))
        assertEquals(setOf(Permission.EDIT_LIVESTOCK), member.grantedPermissions)
    }

    @Test
    fun `display name falls back to the local part of the email`() {
        assertEquals("partner", profile().displayName)
        assertEquals(
            "Ravi Kumar",
            profile().copy(fullName = "Ravi Kumar").displayName,
        )
        assertEquals("partner", profile().copy(fullName = "  ").displayName)
    }

    @Test
    fun `every permission has a distinct wire value`() {
        val wires = Permission.entries.map(Permission::wire)
        assertEquals(wires.size, wires.toSet().size)
        wires.forEach { assertEquals(Permission.fromWire(it)?.wire, it) }
    }
}
