package com.mxrider.sysmon.data.apps

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.UserManager
import com.mxrider.sysmon.core.UserRepository
import com.mxrider.sysmon.core.UserRow

/**
 * Users/profiles visible to this app. Android intentionally hides other
 * users' details from third-party apps, so only the current user gets real
 * flags; the rest are identified by serial number alone. Always returns at
 * least the current user.
 */
class DefaultUserRepository(context: Context) : UserRepository {

    private val appContext = context.applicationContext

    override suspend fun users(): List<UserRow> {
        return try {
            val um = appContext.getSystemService(Context.USER_SERVICE) as? UserManager
                ?: return listOf(fallbackCurrentUser())
            val current = Process.myUserHandle()
            val handles = try {
                um.userProfiles ?: listOf(current)
            } catch (e: Exception) {
                listOf(current)
            }

            val rows = ArrayList<UserRow>(handles.size)
            for (handle in handles) {
                val serial = try {
                    um.getSerialNumberForUser(handle)
                } catch (e: Exception) {
                    -1L
                }
                val isCurrent = handle == current
                val isSystemUser = if (isCurrent) {
                    try {
                        um.isSystemUser
                    } catch (e: Exception) {
                        serial == 0L
                    }
                } else {
                    serial == 0L
                }
                val isManagedProfile = if (isCurrent && Build.VERSION.SDK_INT >= 30) {
                    try {
                        um.isManagedProfile
                    } catch (e: Exception) {
                        false
                    }
                } else {
                    false
                }
                rows += UserRow(
                    handleId = serial.toInt(),
                    name = null, // Not exposed to third-party apps; UI renders "User <id>".
                    isCurrent = isCurrent,
                    isSystemUser = isSystemUser,
                    isManagedProfile = isManagedProfile,
                )
            }
            if (rows.isEmpty()) listOf(fallbackCurrentUser()) else rows
        } catch (e: Exception) {
            listOf(fallbackCurrentUser())
        }
    }

    private fun fallbackCurrentUser(): UserRow = UserRow(
        handleId = 0,
        name = null,
        isCurrent = true,
        isSystemUser = true,
        isManagedProfile = false,
    )
}
