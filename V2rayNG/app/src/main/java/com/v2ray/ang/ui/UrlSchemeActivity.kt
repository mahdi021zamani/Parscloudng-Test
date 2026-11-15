package com.v2ray.ang.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.simple.SimpleVpnActivity
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder

class UrlSchemeActivity : BaseComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            when {
                intent.action == Intent.ACTION_SEND && "text/plain" == intent.type -> {
                    val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                    if (!sharedText.isNullOrBlank()) {
                        parseUri(sharedText, null)
                    } else {
                        openSimpleScreen()
                    }
                }

                intent.action == Intent.ACTION_VIEW -> {
                    val uri = intent.data
                    when (uri?.host) {
                        "install-config", "install-sub" -> {
                            val shareUrl = uri.getQueryParameter("url").orEmpty()
                            if (shareUrl.isNotBlank()) {
                                parseUri(shareUrl, uri.fragment)
                            } else {
                                toastError(R.string.toast_failure)
                                openSimpleScreen()
                            }
                        }

                        else -> {
                            toastError(R.string.toast_failure)
                            openSimpleScreen()
                        }
                    }
                }

                else -> openSimpleScreen()
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Error processing URL scheme", e)
            openSimpleScreen()
        }
    }

    private fun openSimpleScreen() {
        startActivity(Intent(this, SimpleVpnActivity::class.java))
        finish()
    }

    @Composable
    override fun ScreenContent() {
    }

    private fun parseUri(uriString: String?, fragment: String?) {
        if (uriString.isNullOrEmpty()) {
            return
        }
        val decodedUrl = URLDecoder.decode(uriString, "UTF-8")
        val uri = Uri.parse(decodedUrl)
        val importValue = if (uri.fragment.isNullOrEmpty() && !fragment.isNullOrEmpty()) {
            "$decodedUrl#$fragment"
        } else {
            decodedUrl
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val (count, countSub) = AngConfigManager.importBatchConfig(importValue, "", true)
            withContext(Dispatchers.Main) {
                if (count + countSub > 0) {
                    toast(R.string.import_subscription_success)
                } else {
                    toast(R.string.import_subscription_failure)
                }
                openSimpleScreen()
            }
        }
    }
}
