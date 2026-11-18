package com.example.scamdetect.logic

import androidx.annotation.StringRes

/** Looks a string resource up by id. The app uses Context.getString; unit tests read res/values/strings.xml. */
fun interface Txt {
    fun get(@StringRes id: Int, args: Array<out Any>): String
}

/**
 * App wording for code outside Compose (view model, pure logic). MainActivity points [txt] at the app's string
 * resources. Same role as L.s on iOS.
 */
object Tr {
    @Volatile var txt: Txt = Txt { id, _ -> "#$id" }

    fun s(@StringRes id: Int, vararg args: Any): String = txt.get(id, args)
}
