package com.riyadm.unsendvault

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Text
import androidx.fragment.app.FragmentActivity

/** SKELETON STUB — replaced by the UI layer. FragmentActivity is required by androidx.biometric. */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { Text("UnsendVault") }
    }
}
