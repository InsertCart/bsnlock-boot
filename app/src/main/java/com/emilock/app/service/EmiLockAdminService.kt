package com.emilock.app.service

import android.app.admin.DeviceAdminService
import android.util.Log

/**
 * EmiLockAdminService
 *
 * Required by Samsung Knox for transferOwnership() to succeed.
 *
 * Samsung's DevicePolicyManagerService.transferOwnership() checks that the
 * target app has an active DeviceAdminService bound and connected before
 * allowing the transfer. Without this, it throws:
 *   android.os.RemoteException: checkArgument failed at transferOwnership:17206
 *
 * TestDPC has com.afwsamples.testdpc/.DeviceAdminService — this is why
 * TestDPC works as a transfer source. EmiLock needs the same on the target side.
 *
 * This service runs automatically once EmiLock's Device Admin is activated.
 * No logic needed — its mere existence and binding satisfies Samsung Knox.
 */
class EmiLockAdminService : DeviceAdminService() {

    override fun onCreate() {
        super.onCreate()
        Log.d("EmiLock.AdminService", "DeviceAdminService bound — transfer target ready")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("EmiLock.AdminService", "DeviceAdminService unbound")
    }
}