package com.mopemope.urlfilter

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mopemope.urlfilter.ui.theme.urlfilterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            urlfilterTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    mainButton(
                        modifier = Modifier.padding(innerPadding),
                        onClick = {
                            if (isAccessibilityServiceEnabled()) {
                                Toast.makeText(this, "Service is enabled", Toast.LENGTH_SHORT).show()
                            } else {
                                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }
                        },
                    )
                }
            }
        }
    }

    fun isAccessibilityServiceEnabled(): Boolean {
        val manager: AccessibilityManager =
            this.getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        for (service in enabledServices) {
            val serviceInfo = service.resolveInfo.serviceInfo
            if (serviceInfo.packageName == packageName && serviceInfo.name == URLFilterService::class.java.name) {
                return true // Service is enabled
            }
        }
        return false // Service is not enabled
    }
}

@Composable
fun mainButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(modifier = modifier, onClick = { onClick() }) {
        Text("ユーザー補助サービスを確認")
    }
}

@Preview(showBackground = true)
@Composable
fun mainButtonPreview() {
    urlfilterTheme {
        mainButton(
            modifier = Modifier.padding(8.dp),
            onClick = { /* Do nothing */ },
        )
    }
}
