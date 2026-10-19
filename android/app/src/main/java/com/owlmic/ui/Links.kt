package com.owlmic.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/** Where the info page and settings send people. */
object Links {
    const val REPO = "https://github.com/diveshpatil9104/owlmic"
    const val RELEASES = "$REPO/releases"
    const val PRIVACY = "$REPO/blob/main/docs/user/privacy.md"
    const val LICENSE = "$REPO/blob/main/LICENSE"
    const val CONTRIBUTORS = "$REPO/graphs/contributors"
    const val WEBSITE = "https://owlmic.app"
    const val DONATE = "https://github.com/sponsors/diveshpatil9104"
    const val STORE = "https://play.google.com/store/apps/details?id=com.owlmic"

    /** The people who built Owlmic, with their GitHub names, for the credits. */
    val CONTRIBUTORS_LIST = listOf(
        "Divesh Patil" to "diveshpatil9104",
        "Yash Thorat" to "yashthorat7",
        "Vedant Newadkar" to "vedantnewadkar",
        "Vaibhav Sonawane" to "Vaibhav-Sonawane105",
        "aipd506" to "aipd506",
    )

    fun profile(login: String) = "https://github.com/$login"

    /** Opens [url]; a phone with nothing to open it with does nothing. */
    fun open(context: Context, url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** The store's own app when there is one, else its web page. */
    fun rate(context: Context) {
        val market = Intent(Intent.ACTION_VIEW, "market://details?id=${context.packageName}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(market)
        } catch (_: ActivityNotFoundException) {
            open(context, STORE)
        }
    }
}
