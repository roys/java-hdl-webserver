package com.roysolberg.java.hdlserver

import com.roysolberg.java.hdlserver.hdl.Action
import com.roysolberg.java.hdlserver.hdl.component.HdlComponent

/**
 * What the web pages show. The templates escape every value themselves.
 */
class Page(
    val name: String,
    val authToken: String,
    val components: List<HdlComponent>,
    val actions: Collection<Action>,
    val host: String,
    val successMessage: String?,
    val errorMessage: String?,
) {
    /** The page's name in the menu and in the browser tab. */
    val title: String
        get() = MENU.firstOrNull { it.name == name }?.title ?: name.replaceFirstChar(Char::uppercase)

    class MenuItem(val name: String, val title: String) {
        val path: String
            get() = if (name == "index") "/" else "/$name"
    }

    companion object {
        @JvmField
        val MENU = listOf(
            MenuItem("index", "Devices"),
            MenuItem("actions", "Actions"),
            MenuItem("activity", "Activity"),
            MenuItem("status", "Status"),
            MenuItem("security", "Security"),
            MenuItem("settings", "Settings"),
            MenuItem("demo", "Demo"),
        )
    }
}
