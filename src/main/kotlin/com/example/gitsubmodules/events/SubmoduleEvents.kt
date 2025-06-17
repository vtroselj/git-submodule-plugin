package com.example.gitsubmodules.events

import com.intellij.util.messages.Topic

/**
 * Events for submodule changes
 */
interface SubmoduleChangeListener {
    fun submodulesChanged()
}

object SubmoduleTopics {
    val SUBMODULE_CHANGE_TOPIC = Topic.create(
        "Submodule Changes",
        SubmoduleChangeListener::class.java
    )
}
