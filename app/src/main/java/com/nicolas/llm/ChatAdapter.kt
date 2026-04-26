package com.nicolas.llm

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChatAdapter(private val messages: MutableList<ChatMessage>) :
    RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    private val VIEW_TYPE_USER = 1
    private val VIEW_TYPE_AI = 2

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].isUser) VIEW_TYPE_USER else VIEW_TYPE_AI
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val layout = if (viewType == VIEW_TYPE_USER) R.layout.item_chat_user else R.layout.item_chat_ai
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        holder.bind(messages[position])
    }

    override fun getItemCount() = messages.size

    class ChatViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val txtMessage = view.findViewById<TextView>(R.id.txtMessage)
        private val imgMessage = view.findViewById<ImageView>(R.id.imgMessage)
        private val thoughtContainer = view.findViewById<LinearLayout>(R.id.thoughtContainer)
        private val btnToggleThought = view.findViewById<TextView>(R.id.btnToggleThought)
        private val txtThought = view.findViewById<TextView>(R.id.txtThought)

        private val handler = Handler(Looper.getMainLooper())
        private var dotCount = 0
        private val dotRunnable = object : Runnable {
            override fun run() {
                dotCount = (dotCount + 1) % 4
                val dots = ".".repeat(dotCount)
                txtMessage?.text = "Thinking$dots"
                handler.postDelayed(this, 500)
            }
        }

        fun bind(message: ChatMessage) {
            handler.removeCallbacks(dotRunnable)

            if (txtMessage != null) {
                if (message.text.isNotEmpty()) {
                    txtMessage.text = message.text
                    txtMessage.visibility = View.VISIBLE
                } else if (!message.isUser && message.image == null && message.thought.isEmpty()) {
                    // Estado de procesamiento (Thinking...)
                    txtMessage.visibility = View.VISIBLE
                    handler.post(dotRunnable)
                } else {
                    txtMessage.visibility = View.GONE
                }
            }
            
            if (imgMessage != null) {
                if (message.image != null) {
                    imgMessage.setImageBitmap(message.image)
                    imgMessage.visibility = View.VISIBLE
                } else {
                    imgMessage.visibility = View.GONE
                }
            }

            if (thoughtContainer != null && btnToggleThought != null && txtThought != null) {
                if (message.thought.isNotEmpty()) {
                    thoughtContainer.visibility = View.VISIBLE
                    txtThought.text = message.thought
                    val arrow = if (message.isThoughtExpanded) "▲ Hide" else "▼ Show"
                    btnToggleThought.text = "$arrow Reasoning"
                    txtThought.visibility = if (message.isThoughtExpanded) View.VISIBLE else View.GONE
                    
                    btnToggleThought.setOnClickListener {
                        message.isThoughtExpanded = !message.isThoughtExpanded
                        val newArrow = if (message.isThoughtExpanded) "▲ Hide" else "▼ Show"
                        btnToggleThought.text = "$newArrow Reasoning"
                        txtThought.visibility = if (message.isThoughtExpanded) View.VISIBLE else View.GONE
                    }
                } else {
                    thoughtContainer.visibility = View.GONE
                }
            }
        }
    }
}
