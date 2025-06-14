package com.example.gitsubmodules.model

sealed class SubmoduleResult {
    object Success : SubmoduleResult()
    data class Error(val message: String) : SubmoduleResult()
}
