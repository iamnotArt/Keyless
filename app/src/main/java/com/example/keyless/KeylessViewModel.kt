package com.example.keyless

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
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

class KeylessViewModel : ViewModel() {
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val lockerStatusCache = mutableStateMapOf<String, LockerStatus>()

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

    fun occupyLocker(
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
        if (operationInProgress) return
        operationInProgress = true

        viewModelScope.launch {
            val lockerRef = firestore.collection("lockers").document(lockerId)
            try {
                firestore.runTransaction { transaction ->
                    val snapshot = transaction.get(lockerRef)
                    val isOccupied = snapshot.getBoolean("isOccupied") ?: false
                    val occupiedAt = snapshot.getTimestamp("occupiedAt")
                    val durationSeconds = snapshot.getLong("occupiedDurationSeconds")
                        ?: DEFAULT_OCCUPANCY_SECONDS
                    val isExpired = occupiedAt != null &&
                        System.currentTimeMillis() >= occupiedAt.toDate().time + (durationSeconds * 1000)

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
                            "occupiedDurationSeconds" to DEFAULT_OCCUPANCY_SECONDS,
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
        const val LOCKER_1_ID = "locker_1"
        const val DEFAULT_OCCUPANCY_SECONDS = 30L * 60L
        const val DOOR_OPEN = "open"
        const val DOOR_CLOSED = "closed"

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
    }
}
