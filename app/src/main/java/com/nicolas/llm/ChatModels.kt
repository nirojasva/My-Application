package com.nicolas.llm

import android.graphics.Bitmap

data class ChatMessage(
    var text: String,
    val isUser: Boolean,
    val image: Bitmap? = null,
    var thought: String = "", // Para guardar el razonamiento de la IA
    var isThoughtExpanded: Boolean = false // Estado del desplegable
)
