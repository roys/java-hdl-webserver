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
)
