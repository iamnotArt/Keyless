package com.example.keyless

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class LockerStatus(
    val lockerId: String,
    val label: String,
    val isOccupied: Boolean,
    val occupiedBy: String?,
    val occupiedAt: Timestamp?,
    val occupiedDurationSeconds: Long,
    val doorState: String
)

data class PendingLockerPayment(
    val lockerId: String,
    val scannedQrValue: String?,
    val selectedPlanId: String,
    val paymentMode: String
)

data class LockerPaymentPlan(
    val id: String,
    val label: String,
    val priceLabel: String,
    val durationSeconds: Long
)

class KeylessViewModel(application: Application) : AndroidViewModel(application) {
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val lockerStatusCache = mutableStateMapOf<String, LockerStatus>()
    private val appContext = application.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, 0)

    private val authListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        currentUser = firebaseAuth.currentUser
        if (currentUser != null) {
            ensureLockerSeeded()
        }
    }

    var currentUser by mutableStateOf(auth.currentUser)
        private set

    var operationInProgress by mutableStateOf(false)
        private set

    var pendingLockerPayment by mutableStateOf<PendingLockerPayment?>(null)
        private set

    init {
        // Keep Firestore local cache enabled for smoother UI updates and offline reads.
        runCatching {
            firestore.firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(
                    PersistentCacheSettings.newBuilder()
                        .setSizeBytes(FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
                        .build()
                )
                .build()
        }
        auth.addAuthStateListener(authListener)
        restorePendingPayment()
        if (currentUser != null) {
            ensureLockerSeeded()
        }
    }

    fun signUp(
        email: String,
        password: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (operationInProgress) return
        operationInProgress = true
        viewModelScope.launch {
            try {
                auth.createUserWithEmailAndPassword(email.trim(), password).await()
                auth.signOut()
                onSuccess()
            } catch (error: Exception) {
                onError(error.message ?: "Unable to create account.")
            } finally {
                operationInProgress = false
            }
        }
    }

    fun login(
        email: String,
        password: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (operationInProgress) return
        operationInProgress = true
        viewModelScope.launch {
            try {
                auth.signInWithEmailAndPassword(email.trim(), password).await()
                ensureLockerSeeded()
                onSuccess()
            } catch (error: Exception) {
                onError(error.message ?: "Unable to log in.")
            } finally {
                operationInProgress = false
            }
        }
    }

    fun logout() {
        auth.signOut()
    }

    fun observeLocker(
        lockerId: String,
        onUpdate: (LockerStatus) -> Unit,
        onError: (String) -> Unit
    ): ListenerRegistration {
        lockerStatusCache[lockerId]?.let(onUpdate)
        return firestore.collection("lockers").document(lockerId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onError(error.message ?: "Unable to load locker status.")
                    return@addSnapshotListener
                }

                if (snapshot == null || !snapshot.exists()) {
                    ensureLockerSeeded()
                    val emptyStatus = LockerStatus(
                        lockerId = lockerId,
                        label = lockerLabel(lockerId),
                        isOccupied = false,
                        occupiedBy = null,
                        occupiedAt = null,
                        occupiedDurationSeconds = DEFAULT_OCCUPANCY_SECONDS,
                        doorState = DOOR_CLOSED
                    )
                    lockerStatusCache[lockerId] = emptyStatus
                    onUpdate(emptyStatus)
                    return@addSnapshotListener
                }

                val status = LockerStatus(
                    lockerId = lockerId,
                    label = snapshot.getString("label") ?: lockerLabel(lockerId),
                    isOccupied = snapshot.getBoolean("isOccupied") ?: false,
                    occupiedBy = snapshot.getString("occupiedBy"),
                    occupiedAt = snapshot.getTimestamp("occupiedAt"),
                    occupiedDurationSeconds = snapshot.getLong("occupiedDurationSeconds")
                        ?: DEFAULT_OCCUPANCY_SECONDS,
                    doorState = snapshot.getString("doorState") ?: DOOR_CLOSED
                )
                lockerStatusCache[lockerId] = status
                onUpdate(status)
            }
    }

    fun cachedLockerStatus(lockerId: String): LockerStatus? = lockerStatusCache[lockerId]

    fun prepareLockerPayment(
        lockerId: String,
        scannedQrValue: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val userEmail = currentUser?.email
        if (userEmail.isNullOrBlank()) {
            onError("Please log in again.")
            return
        }
        if (!isValidLockerQr(lockerId, scannedQrValue)) {
            onError("That QR code does not match ${lockerLabel(lockerId)}.")
            return
        }
        pendingLockerPayment = PendingLockerPayment(
            lockerId = lockerId,
            scannedQrValue = scannedQrValue,
            selectedPlanId = PLAN_3H,
            paymentMode = PAYMENT_MODE_OCCUPY
        )
        persistPendingPayment(pendingLockerPayment)
        onSuccess()
    }

    fun prepareLockerExtensionPayment(
        lockerId: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val userEmail = currentUser?.email
        if (userEmail.isNullOrBlank()) {
            onError("Please log in again.")
            return
        }
        val cachedStatus = lockerStatusCache[lockerId]
        if (cachedStatus == null || !cachedStatus.isOccupied) {
            onError("Locker is not occupied.")
            return
        }
        if (!cachedStatus.occupiedBy.equals(userEmail, ignoreCase = true)) {
            onError("Only the locker owner can extend the timer.")
            return
        }
        pendingLockerPayment = PendingLockerPayment(
            lockerId = lockerId,
            scannedQrValue = null,
            selectedPlanId = PLAN_3H,
            paymentMode = PAYMENT_MODE_EXTEND
        )
        persistPendingPayment(pendingLockerPayment)
        onSuccess()
    }

    fun selectPendingPaymentPlan(planId: String) {
        val pending = pendingLockerPayment ?: return
        if (paymentPlanById(planId) == null) return
        pendingLockerPayment = pending.copy(selectedPlanId = planId)
        persistPendingPayment(pendingLockerPayment)
    }

    fun clearPendingLockerPayment() {
        pendingLockerPayment = null
        clearPersistedPendingPayment()
    }

    fun completeLockerPaymentAction(
        paidPlanId: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val pending = pendingLockerPayment
        if (pending == null) {
            onError("No pending locker payment found.")
            return
        }
        val plan = paymentPlanById(paidPlanId)
        if (plan == null) {
            onError("Unknown payment plan.")
            return
        }
        when (pending.paymentMode) {
            PAYMENT_MODE_OCCUPY -> {
                val scannedQr = pending.scannedQrValue
                if (scannedQr.isNullOrBlank()) {
                    onError("Missing QR data for locker payment.")
                    return
                }
                occupyLocker(
                    lockerId = pending.lockerId,
                    scannedQrValue = scannedQr,
                    durationSeconds = plan.durationSeconds,
                    onSuccess = {
                        val lockerId = pending.lockerId
                        pendingLockerPayment = null
                        clearPersistedPendingPayment()
                        onSuccess(lockerId)
                    },
                    onError = onError
                )
            }

            PAYMENT_MODE_EXTEND -> {
                extendLockerTimerByDuration(
                    lockerId = pending.lockerId,
                    additionalSeconds = plan.durationSeconds,
                    onSuccess = {
                        val lockerId = pending.lockerId
                        pendingLockerPayment = null
                        clearPersistedPendingPayment()
                        onSuccess(lockerId)
                    },
                    onError = onError
                )
            }

            else -> onError("Unknown payment mode.")
        }
    }

    fun occupyLocker(
        lockerId: String,
        scannedQrValue: String,
        durationSeconds: Long = DEFAULT_OCCUPANCY_SECONDS,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val userEmail = currentUser?.email
        if (userEmail.isNullOrBlank()) {
            onError("Please log in again.")
            return
        }
        if (!isValidLockerQr(lockerId, scannedQrValue)) {
            onError("That QR code does not match ${lockerLabel(lockerId)}.")
            return
        }
        if (operationInProgress) return
        operationInProgress = true
        val requestedDurationSeconds = durationSeconds
            .coerceAtLeast(1L)
            .coerceAtMost(MAX_OCCUPANCY_SECONDS)

        viewModelScope.launch {
            val lockerRef = firestore.collection("lockers").document(lockerId)
            try {
                firestore.runTransaction { transaction ->
                    val snapshot = transaction.get(lockerRef)
                    val isOccupied = snapshot.getBoolean("isOccupied") ?: false
                    val occupiedAt = snapshot.getTimestamp("occupiedAt")
                    val existingDurationSeconds = snapshot.getLong("occupiedDurationSeconds")
                        ?: DEFAULT_OCCUPANCY_SECONDS
                    val isExpired = occupiedAt != null &&
                        System.currentTimeMillis() >= occupiedAt.toDate().time + (existingDurationSeconds * 1000)

                    if (isOccupied && !isExpired) {
                        throw FirebaseFirestoreException(
                            "Locker is already occupied.",
                            FirebaseFirestoreException.Code.ABORTED
                        )
                    }
                    transaction.set(
                        lockerRef,
                        mapOf(
                            "lockerId" to lockerId,
                            "label" to lockerLabel(lockerId),
                            "isOccupied" to true,
                            "occupiedBy" to userEmail,
                            "occupiedAt" to FieldValue.serverTimestamp(),
                            "occupiedDurationSeconds" to requestedDurationSeconds,
                            "doorState" to DOOR_CLOSED,
                            "updatedAt" to FieldValue.serverTimestamp()
                        ),
                        SetOptions.merge()
                    )
                }.await()
                onSuccess()
            } catch (error: Exception) {
                onError(error.message ?: "Unable to occupy locker.")
            } finally {
                operationInProgress = false
            }
        }
    }

    private fun ensureLockerSeeded() {
        val lockerRef = firestore.collection("lockers").document(LOCKER_1_ID)
        lockerRef.get().addOnSuccessListener { snapshot ->
            if (!snapshot.exists()) {
                lockerRef.set(
                    mapOf(
                        "lockerId" to LOCKER_1_ID,
                        "label" to "Locker 1",
                        "isOccupied" to false,
                        "occupiedBy" to null,
                        "occupiedAt" to null,
                        "occupiedDurationSeconds" to DEFAULT_OCCUPANCY_SECONDS,
                        "doorState" to DOOR_CLOSED,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
        }
    }

    fun setLockerDoorState(
        lockerId: String,
        open: Boolean,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val userEmail = currentUser?.email
        if (userEmail.isNullOrBlank()) {
            onError("Please log in again.")
            return
        }
        if (operationInProgress) return
        operationInProgress = true

        viewModelScope.launch {
            val lockerRef = firestore.collection("lockers").document(lockerId)
            try {
                firestore.runTransaction { transaction ->
                    val snapshot = transaction.get(lockerRef)
                    val isOccupied = snapshot.getBoolean("isOccupied") ?: false
                    val occupiedBy = snapshot.getString("occupiedBy").orEmpty()
                    if (!isOccupied) {
                        throw FirebaseFirestoreException(
                            "Locker is not occupied.",
                            FirebaseFirestoreException.Code.ABORTED
                        )
                    }
                    if (!occupiedBy.equals(userEmail, ignoreCase = true)) {
                        throw FirebaseFirestoreException(
                            "Only the locker owner can control open/close.",
                            FirebaseFirestoreException.Code.PERMISSION_DENIED
                        )
                    }
                    transaction.set(
                        lockerRef,
                        mapOf(
                            "doorState" to if (open) DOOR_OPEN else DOOR_CLOSED,
                            "updatedAt" to FieldValue.serverTimestamp()
                        ),
                        SetOptions.merge()
                    )
                }.await()
                onSuccess()
            } catch (error: Exception) {
                onError(error.message ?: "Unable to update locker door state.")
            } finally {
                operationInProgress = false
            }
        }
    }

    fun extendLockerTimer(
        lockerId: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val userEmail = currentUser?.email
        if (userEmail.isNullOrBlank()) {
            onError("Please log in again.")
            return
        }
        if (operationInProgress) return
        operationInProgress = true

        viewModelScope.launch {
            val lockerRef = firestore.collection("lockers").document(lockerId)
            try {
                firestore.runTransaction { transaction ->
                    val snapshot = transaction.get(lockerRef)
                    val isOccupied = snapshot.getBoolean("isOccupied") ?: false
                    val occupiedBy = snapshot.getString("occupiedBy").orEmpty()
                    if (!isOccupied) {
                        throw FirebaseFirestoreException(
                            "Locker is not occupied.",
                            FirebaseFirestoreException.Code.ABORTED
                        )
                    }
                    if (!occupiedBy.equals(userEmail, ignoreCase = true)) {
                        throw FirebaseFirestoreException(
                            "Only the locker owner can extend the timer.",
                            FirebaseFirestoreException.Code.PERMISSION_DENIED
                        )
                    }
                    val currentDuration = snapshot.getLong("occupiedDurationSeconds")
                        ?: DEFAULT_OCCUPANCY_SECONDS
                    val extendedDuration = (currentDuration + TIMER_EXTENSION_SECONDS)
                        .coerceAtMost(MAX_OCCUPANCY_SECONDS)
                    transaction.set(
                        lockerRef,
                        mapOf(
                            "occupiedDurationSeconds" to extendedDuration,
                            "updatedAt" to FieldValue.serverTimestamp()
                        ),
                        SetOptions.merge()
                    )
                }.await()
                onSuccess()
            } catch (error: Exception) {
                onError(error.message ?: "Unable to extend locker timer.")
            } finally {
                operationInProgress = false
            }
        }
    }

    private fun extendLockerTimerByDuration(
        lockerId: String,
        additionalSeconds: Long,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val userEmail = currentUser?.email
        if (userEmail.isNullOrBlank()) {
            onError("Please log in again.")
            return
        }
        if (operationInProgress) return
        operationInProgress = true

        viewModelScope.launch {
            val lockerRef = firestore.collection("lockers").document(lockerId)
            try {
                firestore.runTransaction { transaction ->
                    val snapshot = transaction.get(lockerRef)
                    val isOccupied = snapshot.getBoolean("isOccupied") ?: false
                    val occupiedBy = snapshot.getString("occupiedBy").orEmpty()
                    if (!isOccupied) {
                        throw FirebaseFirestoreException(
                            "Locker is not occupied.",
                            FirebaseFirestoreException.Code.ABORTED
                        )
                    }
                    if (!occupiedBy.equals(userEmail, ignoreCase = true)) {
                        throw FirebaseFirestoreException(
                            "Only the locker owner can extend the timer.",
                            FirebaseFirestoreException.Code.PERMISSION_DENIED
                        )
                    }
                    val currentDuration = snapshot.getLong("occupiedDurationSeconds")
                        ?: DEFAULT_OCCUPANCY_SECONDS
                    val extendedDuration = (currentDuration + additionalSeconds)
                        .coerceAtMost(MAX_OCCUPANCY_SECONDS)
                    transaction.set(
                        lockerRef,
                        mapOf(
                            "occupiedDurationSeconds" to extendedDuration,
                            "updatedAt" to FieldValue.serverTimestamp()
                        ),
                        SetOptions.merge()
                    )
                }.await()
                onSuccess()
            } catch (error: Exception) {
                onError(error.message ?: "Unable to extend locker timer.")
            } finally {
                operationInProgress = false
            }
        }
    }

    fun cancelLockerTimer(
        lockerId: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val userEmail = currentUser?.email
        if (userEmail.isNullOrBlank()) {
            onError("Please log in again.")
            return
        }
        if (operationInProgress) return
        operationInProgress = true

        viewModelScope.launch {
            val lockerRef = firestore.collection("lockers").document(lockerId)
            try {
                firestore.runTransaction { transaction ->
                    val snapshot = transaction.get(lockerRef)
                    val isOccupied = snapshot.getBoolean("isOccupied") ?: false
                    val occupiedBy = snapshot.getString("occupiedBy").orEmpty()
                    if (!isOccupied) {
                        throw FirebaseFirestoreException(
                            "Locker is not occupied.",
                            FirebaseFirestoreException.Code.ABORTED
                        )
                    }
                    if (!occupiedBy.equals(userEmail, ignoreCase = true)) {
                        throw FirebaseFirestoreException(
                            "Only the locker owner can cancel the timer.",
                            FirebaseFirestoreException.Code.PERMISSION_DENIED
                        )
                    }
                    transaction.set(
                        lockerRef,
                        mapOf(
                            "isOccupied" to false,
                            "occupiedBy" to null,
                            "occupiedAt" to null,
                            "occupiedDurationSeconds" to DEFAULT_OCCUPANCY_SECONDS,
                            "doorState" to DOOR_CLOSED,
                            "updatedAt" to FieldValue.serverTimestamp()
                        ),
                        SetOptions.merge()
                    )
                }.await()
                onSuccess()
            } catch (error: Exception) {
                onError(error.message ?: "Unable to cancel locker timer.")
            } finally {
                operationInProgress = false
            }
        }
    }

    private fun isValidLockerQr(lockerId: String, scannedQrValue: String): Boolean {
        val normalizedValue = scannedQrValue.trim().lowercase()
        val allowed = when (lockerId) {
            LOCKER_1_ID -> setOf(
                "locker_1",
                "locker-1",
                "locker 1",
                "locker1",
                "locker1@gmail.com"
            )
            else -> setOf(lockerId.lowercase())
        }
        return normalizedValue in allowed
    }

    override fun onCleared() {
        auth.removeAuthStateListener(authListener)
        super.onCleared()
    }

    companion object {
        private const val PREFS_NAME = "keyless_payment_prefs"
        private const val PREF_PENDING_LOCKER_ID = "pref_pending_locker_id"
        private const val PREF_PENDING_SCANNED_QR = "pref_pending_scanned_qr"
        private const val PREF_PENDING_PLAN_ID = "pref_pending_plan_id"
        private const val PREF_PENDING_PAYMENT_MODE = "pref_pending_payment_mode"

        const val LOCKER_1_ID = "locker_1"
        const val DEFAULT_OCCUPANCY_SECONDS = 30L * 60L
        const val TIMER_EXTENSION_SECONDS = 3L * 60L * 60L
        const val MAX_OCCUPANCY_SECONDS = 7L * 24L * 60L * 60L
        const val PAYMENT_MODE_OCCUPY = "occupy"
        const val PAYMENT_MODE_EXTEND = "extend"
        const val PLAN_3H = "3h"
        const val PLAN_12H = "12h"
        const val PLAN_24H = "24h"
        const val DOOR_OPEN = "open"
        const val DOOR_CLOSED = "closed"
        val PAYMENT_PLANS = listOf(
            LockerPaymentPlan(
                id = PLAN_3H,
                label = "3 Hours",
                priceLabel = "PHP 50",
                durationSeconds = 3L * 60L * 60L
            ),
            LockerPaymentPlan(
                id = PLAN_12H,
                label = "12 Hours",
                priceLabel = "PHP 75",
                durationSeconds = 12L * 60L * 60L
            ),
            LockerPaymentPlan(
                id = PLAN_24H,
                label = "24 Hours",
                priceLabel = "PHP 100",
                durationSeconds = 24L * 60L * 60L
            )
        )

        fun lockerLabel(lockerId: String): String {
            return when (lockerId) {
                LOCKER_1_ID -> "Locker 1"
                else -> lockerId.replace('_', ' ').replaceFirstChar { char ->
                    if (char.isLowerCase()) char.titlecase() else char.toString()
                }
            }
        }

        fun remainingTimeMillis(status: LockerStatus, nowMillis: Long): Long? {
            if (!status.isOccupied) return null
            val occupiedAt = status.occupiedAt ?: return null
            val expiryMillis = occupiedAt.toDate().time + (status.occupiedDurationSeconds * 1000)
            return (expiryMillis - nowMillis).coerceAtLeast(0)
        }

        fun isActivelyOccupied(status: LockerStatus, nowMillis: Long): Boolean {
            val remaining = remainingTimeMillis(status, nowMillis)
            return status.isOccupied && (remaining == null || remaining > 0)
        }

        fun paymentPlanById(planId: String): LockerPaymentPlan? {
            return PAYMENT_PLANS.firstOrNull { it.id == planId }
        }
    }

    private fun persistPendingPayment(pending: PendingLockerPayment?) {
        if (pending == null) {
            clearPersistedPendingPayment()
            return
        }
        prefs.edit()
            .putString(PREF_PENDING_LOCKER_ID, pending.lockerId)
            .putString(PREF_PENDING_SCANNED_QR, pending.scannedQrValue ?: "")
            .putString(PREF_PENDING_PLAN_ID, pending.selectedPlanId)
            .putString(PREF_PENDING_PAYMENT_MODE, pending.paymentMode)
            .apply()
    }

    private fun clearPersistedPendingPayment() {
        prefs.edit()
            .remove(PREF_PENDING_LOCKER_ID)
            .remove(PREF_PENDING_SCANNED_QR)
            .remove(PREF_PENDING_PLAN_ID)
            .remove(PREF_PENDING_PAYMENT_MODE)
            .apply()
    }

    private fun restorePendingPayment() {
        if (pendingLockerPayment != null) return
        val lockerId = prefs.getString(PREF_PENDING_LOCKER_ID, null) ?: return
        val scannedQr = prefs.getString(PREF_PENDING_SCANNED_QR, null)
        val planId = prefs.getString(PREF_PENDING_PLAN_ID, PLAN_3H) ?: PLAN_3H
        val paymentMode = prefs.getString(PREF_PENDING_PAYMENT_MODE, PAYMENT_MODE_OCCUPY)
            ?: PAYMENT_MODE_OCCUPY
        pendingLockerPayment = PendingLockerPayment(
            lockerId = lockerId,
            scannedQrValue = scannedQr?.takeIf { it.isNotBlank() },
            selectedPlanId = planId,
            paymentMode = paymentMode
        )
    }
}
