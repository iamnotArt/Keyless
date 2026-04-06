package com.example.keyless

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.example.keyless.KeylessViewModel.Companion.LOCKER_1_ID
import com.example.keyless.ui.theme.AvailableGreen
import com.example.keyless.ui.theme.DisabledLocker
import com.example.keyless.ui.theme.DisabledLockerText
import com.example.keyless.ui.theme.OccupiedRed
import kotlinx.coroutines.delay

private object Routes {
    const val LOGIN = "login"
    const val SIGN_UP = "sign_up"
    const val LOCKER_DISPLAY = "locker_display"
    const val LOCKERS = "lockers"
    const val PAYMENT = "payment"
    const val PAYMENT_SUCCESS = "payment_success"
    const val PAYMENT_CANCEL = "payment_cancel"
    const val LOCKER_DETAIL = "locker_detail"
    const val QR_SCANNER = "qr_scanner"

    fun payment(lockerId: String): String = "$PAYMENT/$lockerId"
    fun paymentSuccess(lockerId: String, planId: String): String = "$PAYMENT_SUCCESS/$lockerId/$planId"
    fun paymentCancel(lockerId: String, planId: String): String = "$PAYMENT_CANCEL/$lockerId/$planId"
    fun lockerDetail(lockerId: String): String = "$LOCKER_DETAIL/$lockerId"
    fun scanner(lockerId: String): String = "$QR_SCANNER/$lockerId"
}

private const val STRIPE_TEST_PAYMENT_LINK_3H = "https://buy.stripe.com/test_14AeVceC89Kj2iG4n02oE00"
private const val STRIPE_TEST_PAYMENT_LINK_12H = "https://buy.stripe.com/test_3cI14m51y3lV3mKdXA2oE01"
private const val STRIPE_TEST_PAYMENT_LINK_24H = "https://buy.stripe.com/test_14A3cu9hO9Kj6yWbPs2oE02"

private data class LockerGridItem(
    val id: String,
    val label: String,
    val enabled: Boolean
)

@Composable
fun KeylessApp(keylessViewModel: KeylessViewModel = viewModel()) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val startDestination = if (keylessViewModel.currentUser != null) {
        destinationForUser(keylessViewModel.currentUser?.email)
    } else {
        Routes.LOGIN
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = { fadeIn(animationSpec = tween(200)) },
        exitTransition = { fadeOut(animationSpec = tween(200)) },
        popEnterTransition = { fadeIn(animationSpec = tween(200)) },
        popExitTransition = { fadeOut(animationSpec = tween(200)) }
    ) {
        composable(Routes.LOGIN) {
            var loginError by rememberSaveable { mutableStateOf<String?>(null) }
            LoginScreen(
                loading = keylessViewModel.operationInProgress,
                errorMessage = loginError,
                onLogin = { email, password ->
                    loginError = null
                    keylessViewModel.login(
                        email = email,
                        password = password,
                        onSuccess = {
                            navController.navigate(destinationForUser(keylessViewModel.currentUser?.email)) {
                                popUpTo(navController.graph.id) { inclusive = true }
                                launchSingleTop = true
                            }
                        },
                        onError = { loginError = it }
                    )
                },
                onGoToSignUp = { navController.navigate(Routes.SIGN_UP) }
            )
        }

        composable(Routes.SIGN_UP) {
            var signUpError by rememberSaveable { mutableStateOf<String?>(null) }
            SignUpScreen(
                loading = keylessViewModel.operationInProgress,
                errorMessage = signUpError,
                onCreateAccount = { email, password ->
                    signUpError = null
                    keylessViewModel.signUp(
                        email = email,
                        password = password,
                        onSuccess = {
                            Toast.makeText(
                                context,
                                "Account created. Please log in.",
                                Toast.LENGTH_SHORT
                            ).show()
                            navController.popBackStack()
                        },
                        onError = { signUpError = it }
                    )
                },
                onBackToLogin = { navController.popBackStack() }
            )
        }

        composable(Routes.LOCKERS) {
            LockerGridScreen(
                userEmail = keylessViewModel.currentUser?.email.orEmpty(),
                keylessViewModel = keylessViewModel,
                onLogout = {
                    keylessViewModel.logout()
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(navController.graph.id) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onOpenLocker = { lockerId ->
                    navController.navigate(Routes.lockerDetail(lockerId))
                }
            )
        }

        composable(Routes.LOCKER_DISPLAY) {
            LockerQrDisplayScreen(
                onLogout = {
                    keylessViewModel.logout()
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(navController.graph.id) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(
            route = "${Routes.PAYMENT}/{lockerId}",
            arguments = listOf(navArgument("lockerId") { type = NavType.StringType })
        ) { backStackEntry ->
            val lockerId = backStackEntry.arguments?.getString("lockerId").orEmpty()
            val pendingPayment = keylessViewModel.pendingLockerPayment
            val selectedPlanId = pendingPayment?.selectedPlanId ?: KeylessViewModel.PLAN_3H
            PaymentScreen(
                lockerId = lockerId,
                pendingLockerPayment = pendingPayment,
                paymentPlans = KeylessViewModel.PAYMENT_PLANS,
                selectedPlanId = selectedPlanId,
                onBack = {
                    keylessViewModel.clearPendingLockerPayment()
                    navController.popBackStack()
                },
                onSelectPlan = { keylessViewModel.selectPendingPaymentPlan(it) },
                onOpenStripe = {
                    val paymentLink = stripePaymentLinkForPlan(selectedPlanId)
                    if (paymentLink == null) {
                        Toast.makeText(
                            context,
                            "Please replace the Stripe test link for $selectedPlanId in KeylessApp.kt",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        openExternalUrl(
                            context = context,
                            url = paymentLink,
                            onError = { message ->
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            )
        }

        composable(
            route = "${Routes.PAYMENT_SUCCESS}/{lockerId}/{planId}",
            arguments = listOf(
                navArgument("lockerId") { type = NavType.StringType },
                navArgument("planId") { type = NavType.StringType }
            ),
            deepLinks = listOf(
                navDeepLink { uriPattern = "keyless://stripe/success/{lockerId}/{planId}" }
            )
        ) { backStackEntry ->
            val lockerId = backStackEntry.arguments?.getString("lockerId").orEmpty()
            val planId = backStackEntry.arguments?.getString("planId").orEmpty()
            PaymentResultScreen(
                title = "Payment Success",
                message = "Finalizing locker payment...",
                onProcess = { onDone ->
                    keylessViewModel.completeLockerPaymentAction(
                        paidPlanId = planId,
                        onSuccess = { completedLockerId ->
                            onDone()
                            navController.navigate(Routes.lockerDetail(completedLockerId)) {
                                popUpTo(navController.graph.id)
                            }
                        },
                        onError = { error ->
                            onDone()
                            Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                            navController.navigate(Routes.payment(lockerId))
                        }
                    )
                }
            )
        }

        composable(
            route = "${Routes.PAYMENT_CANCEL}/{lockerId}/{planId}",
            arguments = listOf(
                navArgument("lockerId") { type = NavType.StringType },
                navArgument("planId") { type = NavType.StringType }
            ),
            deepLinks = listOf(
                navDeepLink { uriPattern = "keyless://stripe/cancel/{lockerId}/{planId}" }
            )
        ) { backStackEntry ->
            val lockerId = backStackEntry.arguments?.getString("lockerId").orEmpty()
            PaymentResultScreen(
                title = "Payment Canceled",
                message = "Payment was canceled. You can try again.",
                onProcess = { onDone ->
                    onDone()
                    navController.navigate(Routes.payment(lockerId)) {
                        popUpTo(Routes.payment(lockerId)) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = "${Routes.LOCKER_DETAIL}/{lockerId}",
            arguments = listOf(navArgument("lockerId") { type = NavType.StringType })
        ) { backStackEntry ->
            val lockerId = backStackEntry.arguments?.getString("lockerId").orEmpty()
            LockerDetailScreen(
                lockerId = lockerId,
                keylessViewModel = keylessViewModel,
                loading = keylessViewModel.operationInProgress,
                onBack = { navController.popBackStack() },
                onScanQr = { navController.navigate(Routes.scanner(lockerId)) },
                onOpenExtensionPayment = { navController.navigate(Routes.payment(lockerId)) }
            )
        }

        composable(
            route = "${Routes.QR_SCANNER}/{lockerId}",
            arguments = listOf(navArgument("lockerId") { type = NavType.StringType })
        ) { backStackEntry ->
            val lockerId = backStackEntry.arguments?.getString("lockerId").orEmpty()
            var scannerError by rememberSaveable(lockerId) { mutableStateOf<String?>(null) }
            QrScannerScreen(
                lockerId = lockerId,
                isLoading = keylessViewModel.operationInProgress,
                errorMessage = scannerError,
                onBack = { navController.popBackStack() },
                onScanned = { qrValue ->
                    scannerError = null
                    keylessViewModel.prepareLockerPayment(
                        lockerId = lockerId,
                        scannedQrValue = qrValue,
                        onSuccess = { navController.navigate(Routes.payment(lockerId)) },
                        onError = { scannerError = it }
                    )
                }
            )
        }
    }
}

private fun destinationForUser(email: String?): String {
    return if (email.equals("locker1@gmail.com", ignoreCase = true)) {
        Routes.LOCKER_DISPLAY
    } else {
        Routes.LOCKERS
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginScreen(
    loading: Boolean,
    errorMessage: String?,
    onLogin: (String, String) -> Unit,
    onGoToSignUp: () -> Unit
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    )
                    .padding(20.dp)
            ) {
                Column {
                    Text(
                        text = "Keyless Locker Login",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Secure QR access for smart lockers.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = "Use your customer account to continue.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(20.dp))
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(16.dp))
            if (errorMessage != null) {
                Text(
                    text = errorMessage,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onLogin(email, password) },
                enabled = !loading && email.isNotBlank() && password.isNotBlank()
            ) {
                Text(if (loading) "Signing in..." else "Login")
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                modifier = Modifier.align(Alignment.End),
                onClick = onGoToSignUp,
                enabled = !loading
            ) {
                Text("Create Account")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignUpScreen(
    loading: Boolean,
    errorMessage: String?,
    onCreateAccount: (String, String) -> Unit,
    onBackToLogin: () -> Unit
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    var localError by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create Customer Account") },
                navigationIcon = {
                    IconButton(onClick = onBackToLogin) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.Center
        ) {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = email,
                onValueChange = {
                    email = it
                    localError = null
                },
                label = { Text("Email") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = password,
                onValueChange = {
                    password = it
                    localError = null
                },
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = confirmPassword,
                onValueChange = {
                    confirmPassword = it
                    localError = null
                },
                label = { Text("Confirm Password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(14.dp))

            val shownError = localError ?: errorMessage
            if (shownError != null) {
                Text(
                    text = shownError,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading && email.isNotBlank() && password.isNotBlank() && confirmPassword.isNotBlank(),
                onClick = {
                    when {
                        password.length < 6 -> localError = "Password must be at least 6 characters."
                        password != confirmPassword -> localError = "Passwords do not match."
                        else -> onCreateAccount(email, password)
                    }
                }
            ) {
                Text(if (loading) "Creating..." else "Create Account")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaymentScreen(
    lockerId: String,
    pendingLockerPayment: PendingLockerPayment?,
    paymentPlans: List<LockerPaymentPlan>,
    selectedPlanId: String,
    onBack: () -> Unit,
    onSelectPlan: (String) -> Unit,
    onOpenStripe: () -> Unit
) {
    val hasPendingPayment = pendingLockerPayment?.lockerId == lockerId
    val selectedPlan = paymentPlans.firstOrNull { it.id == selectedPlanId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Stripe Test Payment") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Locker 1 Payment",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Choose a timer plan and continue to Stripe test checkout.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Test card: 4242 4242 4242 4242",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Choose Timer Plan",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    paymentPlans.forEach { plan ->
                        val isSelected = selectedPlanId == plan.id
                        OutlinedButton(
                            onClick = { onSelectPlan(plan.id) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                }
                            )
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(plan.label)
                                Text(plan.priceLabel, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            if (!hasPendingPayment) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        modifier = Modifier.padding(12.dp),
                        text = "Payment session expired. Please scan locker QR again.",
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                onClick = onOpenStripe,
                shape = RoundedCornerShape(14.dp),
                enabled = hasPendingPayment && selectedPlan != null
            ) {
                val buttonLabel = if (selectedPlan != null) {
                    "Pay ${selectedPlan.priceLabel} with Stripe"
                } else {
                    "Open Stripe Test Checkout"
                }
                Text(buttonLabel)
            }
            Text(
                text = "Payments are non-refundable.",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PaymentResultScreen(
    title: String,
    message: String,
    onProcess: (onDone: () -> Unit) -> Unit
) {
    var handled by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!handled) {
            handled = true
            onProcess { }
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LockerGridScreen(
    userEmail: String,
    keylessViewModel: KeylessViewModel,
    onLogout: () -> Unit,
    onOpenLocker: (String) -> Unit
) {
    var locker1Status by remember {
        mutableStateOf(
            keylessViewModel.cachedLockerStatus(LOCKER_1_ID) ?: LockerStatus(
                lockerId = LOCKER_1_ID,
                label = "Locker 1",
                isOccupied = false,
                occupiedBy = null,
                occupiedAt = null,
                occupiedDurationSeconds = KeylessViewModel.DEFAULT_OCCUPANCY_SECONDS,
                doorState = KeylessViewModel.DOOR_CLOSED
            )
        )
    }
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }

    DisposableEffect(Unit) {
        val registration = keylessViewModel.observeLocker(
            lockerId = LOCKER_1_ID,
            onUpdate = { locker1Status = it },
            onError = { }
        )
        onDispose { registration.remove() }
    }

    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1000)
        }
    }

    val lockers = remember {
        (1..20).map { lockerNumber ->
            val isEnabled = lockerNumber == 1
            LockerGridItem(
                id = if (isEnabled) LOCKER_1_ID else "locker_$lockerNumber",
                label = "Locker $lockerNumber",
                enabled = isEnabled
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Select a Locker") },
                actions = {
                    IconButton(onClick = onLogout) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = "Logout"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Signed in as $userEmail",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(10.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(lockers) { locker ->
                    LockerGridCard(
                        locker = locker,
                        lockerStatus = if (locker.id == LOCKER_1_ID) locker1Status else null,
                        nowMillis = nowMillis,
                        currentUserEmail = userEmail,
                        onOpenLocker = onOpenLocker
                    )
                }
            }
        }
    }
}

@Composable
private fun LockerGridCard(
    locker: LockerGridItem,
    lockerStatus: LockerStatus?,
    nowMillis: Long,
    currentUserEmail: String,
    onOpenLocker: (String) -> Unit
) {
    val cardColors = if (locker.enabled) {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    } else {
        CardDefaults.cardColors(containerColor = DisabledLocker)
    }
    val textColor = if (locker.enabled) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        DisabledLockerText
    }
    val isOccupied = lockerStatus?.let {
        KeylessViewModel.isActivelyOccupied(it, nowMillis)
    } == true
    val isOwnedByCurrentUser = isOccupied &&
        lockerStatus?.occupiedBy?.equals(currentUserEmail, ignoreCase = true) == true
    val remainingLabel = lockerStatus?.let {
        KeylessViewModel.remainingTimeMillis(it, nowMillis)
    }?.let { remaining: Long -> formatRemainingTime(remaining) }
    val statusLabel = when {
        !locker.enabled -> "Coming Soon"
        isOwnedByCurrentUser && remainingLabel != null -> "Your Locker - $remainingLabel"
        isOwnedByCurrentUser -> "Your Locker"
        isOccupied && remainingLabel != null -> "Occupied - $remainingLabel"
        isOccupied -> "Occupied"
        else -> "Available"
    }

    Card(
        modifier = Modifier
            .height(90.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = locker.enabled) { onOpenLocker(locker.id) },
        colors = cardColors,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = locker.label,
                color = textColor,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = statusLabel,
                modifier = Modifier.fillMaxWidth(),
                color = textColor,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LockerDetailScreen(
    lockerId: String,
    keylessViewModel: KeylessViewModel,
    loading: Boolean,
    onBack: () -> Unit,
    onScanQr: () -> Unit,
    onOpenExtensionPayment: () -> Unit
) {
    var lockerStatus by remember(lockerId) {
        mutableStateOf(
            keylessViewModel.cachedLockerStatus(lockerId) ?: LockerStatus(
                lockerId = lockerId,
                label = KeylessViewModel.lockerLabel(lockerId),
                isOccupied = false,
                occupiedBy = null,
                occupiedAt = null,
                occupiedDurationSeconds = KeylessViewModel.DEFAULT_OCCUPANCY_SECONDS,
                doorState = KeylessViewModel.DOOR_CLOSED
            )
        )
    }
    var listenerError by rememberSaveable(lockerId) { mutableStateOf<String?>(null) }
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var showExtendConfirmDialog by rememberSaveable(lockerId) { mutableStateOf(false) }
    var showCancelConfirmDialog by rememberSaveable(lockerId) { mutableStateOf(false) }

    DisposableEffect(lockerId) {
        val registration = keylessViewModel.observeLocker(
            lockerId = lockerId,
            onUpdate = {
                lockerStatus = it
                listenerError = null
            },
            onError = { listenerError = it }
        )
        onDispose { registration.remove() }
    }

    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1000)
        }
    }

    val isOccupied = KeylessViewModel.isActivelyOccupied(lockerStatus, nowMillis)
    val isOwnedByCurrentUser = isOccupied &&
        lockerStatus.occupiedBy?.equals(
            keylessViewModel.currentUser?.email.orEmpty(),
            ignoreCase = true
        ) == true
    val statusColor = if (isOccupied) OccupiedRed else AvailableGreen
    val statusText = if (isOccupied) "Occupied" else "Available"
    val isDoorOpen = lockerStatus.doorState.equals(KeylessViewModel.DOOR_OPEN, ignoreCase = true)
    val doorStateText = if (isDoorOpen) "Open" else "Closed"
    val remainingMillis = KeylessViewModel.remainingTimeMillis(lockerStatus, nowMillis)
    val remainingText = remainingMillis?.let { remaining: Long -> formatRemainingTime(remaining) }
    val statusDescription = if (isOccupied) {
        if (isOwnedByCurrentUser && remainingText != null) {
            "This locker is occupied by your account. Time remaining: $remainingText."
        } else if (remainingText != null) {
            "This locker is currently occupied. Time remaining: $remainingText."
        } else {
            "This locker is currently occupied."
        }
    } else {
        "Locker is available and ready for QR verification."
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(lockerStatus.label) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "Locker Status",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(statusColor)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (isOwnedByCurrentUser) {
                            OwnershipBadge(label = "Your Locker")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = statusDescription,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Locker Details",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    LockerInfoRow(label = "Locker", value = lockerStatus.label)
                    LockerInfoRow(label = "State", value = statusText)
                }
            }

            if (isOccupied && isOwnedByCurrentUser) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Locker Controls",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        LockerInfoRow(label = "Door", value = doorStateText)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    keylessViewModel.setLockerDoorState(
                                        lockerId = lockerId,
                                        open = true,
                                        onSuccess = { },
                                        onError = { listenerError = it }
                                    )
                                },
                                enabled = !loading && !isDoorOpen,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Open")
                            }
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    keylessViewModel.setLockerDoorState(
                                        lockerId = lockerId,
                                        open = false,
                                        onSuccess = { },
                                        onError = { listenerError = it }
                                    )
                                },
                                enabled = !loading && isDoorOpen,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Close")
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    showExtendConfirmDialog = true
                                },
                                enabled = !loading,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Extend Timer")
                            }
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    showCancelConfirmDialog = true
                                },
                                enabled = !loading,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Cancel Timer")
                            }
                        }
                        Text(
                            text = "Extensions require another payment. Duration follows the plan you choose.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (listenerError != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        modifier = Modifier.padding(12.dp),
                        text = listenerError.orEmpty(),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))
            if (!isOccupied) {
                Button(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    onClick = onScanQr,
                    enabled = !loading,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(if (loading) "Processing..." else "Occupy Locker 1")
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        text = if (isOwnedByCurrentUser) {
                            "This locker is currently assigned to your account."
                        } else {
                            "Occupy action is disabled while this locker is occupied."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    if (showExtendConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showExtendConfirmDialog = false },
            title = { Text("Extend Locker Timer?") },
            text = { Text("You will be redirected to Stripe and need to pay again to extend your timer.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        keylessViewModel.prepareLockerExtensionPayment(
                            lockerId = lockerId,
                            onSuccess = {
                                showExtendConfirmDialog = false
                                onOpenExtensionPayment()
                            },
                            onError = {
                                showExtendConfirmDialog = false
                                listenerError = it
                            }
                        )
                    },
                    enabled = !loading
                ) {
                    Text("Confirm")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showExtendConfirmDialog = false },
                    enabled = !loading
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showCancelConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showCancelConfirmDialog = false },
            title = { Text("Cancel Locker Timer?") },
            text = { Text("This will release the locker immediately. Payments are non-refundable.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        keylessViewModel.cancelLockerTimer(
                                        lockerId = lockerId,
                                        onSuccess = { },
                                        onError = { listenerError = it }
                                    )
                        showCancelConfirmDialog = false
                    },
                    enabled = !loading
                ) {
                    Text("Confirm")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showCancelConfirmDialog = false },
                    enabled = !loading
                ) {
                    Text("Keep Timer")
                }
            }
        )
    }
}

@Composable
private fun LockerInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun OwnershipBadge(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun formatRemainingTime(remainingMillis: Long): String {
    val totalSeconds = (remainingMillis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}

private fun stripePaymentLinkForPlan(planId: String): String? {
    val url = when (planId) {
        KeylessViewModel.PLAN_3H -> STRIPE_TEST_PAYMENT_LINK_3H
        KeylessViewModel.PLAN_12H -> STRIPE_TEST_PAYMENT_LINK_12H
        KeylessViewModel.PLAN_24H -> STRIPE_TEST_PAYMENT_LINK_24H
        else -> ""
    }
    return if (url.startsWith("REPLACE_WITH_")) null else url
}

private fun openExternalUrl(
    context: android.content.Context,
    url: String,
    onError: (String) -> Unit
) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        onError("No browser app found on this device.")
    } catch (_: Exception) {
        onError("Unable to open Stripe checkout link.")
    }
}

