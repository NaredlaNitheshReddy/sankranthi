package com.example.sankranthi.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What a signed-in person is allowed to be. */
@Serializable
enum class Role {
    @SerialName("admin") ADMIN,
    @SerialName("member") MEMBER,
    ;

    val label: String get() = if (this == ADMIN) "Admin" else "Member"

    /** Value stored in the Postgres enum column. */
    val wire: String get() = if (this == ADMIN) "admin" else "member"
}

/** Where a person sits in the admin approval queue. */
@Serializable
enum class AccessStatus {
    @SerialName("pending") PENDING,
    @SerialName("approved") APPROVED,
    @SerialName("rejected") REJECTED,
    ;

    val label: String get() = when (this) {
        PENDING -> "Pending"
        APPROVED -> "Approved"
        REJECTED -> "Rejected"
    }

    /** Value stored in the Postgres enum column. */
    val wire: String get() = when (this) {
        PENDING -> "pending"
        APPROVED -> "approved"
        REJECTED -> "rejected"
    }
}

/**
 * Fine-grained edit rights an admin grants per member. Everyone approved can
 * *read* the books; these control who may change them.
 */
@Serializable
enum class Permission {
    @SerialName("edit_livestock") EDIT_LIVESTOCK,
    @SerialName("edit_expenses") EDIT_EXPENSES,
    @SerialName("delete_entries") DELETE_ENTRIES,
    ;

    val label: String get() = when (this) {
        EDIT_LIVESTOCK -> "Add & edit livestock entries"
        EDIT_EXPENSES -> "Add & edit maintenance expenses"
        DELETE_ENTRIES -> "Delete entries"
    }

    /** Value stored in the Postgres `permissions text[]` column. */
    val wire: String get() = when (this) {
        EDIT_LIVESTOCK -> "edit_livestock"
        EDIT_EXPENSES -> "edit_expenses"
        DELETE_ENTRIES -> "delete_entries"
    }

    companion object {
        fun fromWire(value: String): Permission? =
            entries.firstOrNull { it.wire == value }
    }
}

/** A row of `public.profiles` — one per Google account that has signed in. */
@Serializable
data class Profile(
    val id: String,
    val email: String,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val role: Role = Role.MEMBER,
    val status: AccessStatus = AccessStatus.PENDING,
    val permissions: List<String> = emptyList(),
    @SerialName("requested_at") val requestedAt: String? = null,
) {
    val displayName: String get() = fullName?.takeIf { it.isNotBlank() } ?: email.substringBefore('@')

    val isAdmin: Boolean get() = role == Role.ADMIN

    val isApproved: Boolean get() = status == AccessStatus.APPROVED

    /** Admins implicitly hold every permission. */
    val grantedPermissions: Set<Permission>
        get() = if (isAdmin) Permission.entries.toSet()
        else permissions.mapNotNull(Permission::fromWire).toSet()

    fun can(permission: Permission): Boolean =
        isApproved && permission in grantedPermissions
}
