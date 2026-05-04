package com.choupeanut.fitstepcontroller.auth

import android.app.Activity
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.api.ApiException

sealed interface GoogleSignInResult {
    data class Success(val account: GoogleSignInAccount) : GoogleSignInResult
    data class Failure(val statusCode: Int, val statusText: String, val message: String?) : GoogleSignInResult {
        fun displayMessage(): String {
            return "Google Sign-In failed: $statusText ($statusCode)" +
                message?.let { " - $it" }.orEmpty()
        }
    }
}

class GoogleSignInManager(activity: Activity) {
    private val client: GoogleSignInClient = GoogleSignIn.getClient(
        activity,
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
            .build()
    )

    fun lastSignedInAccount(activity: Activity): GoogleSignInAccount? {
        return GoogleSignIn.getLastSignedInAccount(activity)
    }

    fun signInIntent(): Intent = client.signInIntent

    fun parseResult(data: Intent?): GoogleSignInResult {
        return try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
            GoogleSignInResult.Success(account)
        } catch (exception: ApiException) {
            val statusText = GoogleSignInStatusCodes.getStatusCodeString(exception.statusCode)
            Log.w(TAG, "Google Sign-In failed: $statusText (${exception.statusCode})", exception)
            GoogleSignInResult.Failure(
                statusCode = exception.statusCode,
                statusText = statusText,
                message = exception.message,
            )
        }
    }

    fun signOut() = client.signOut()

    companion object {
        private const val TAG = "GoogleSignInManager"
    }
}
