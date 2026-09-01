package com.alex.mailstubdetails.model

data class EmailMessage(
    val id: String,
    val fromName: String,
    val fromEmail: String,
    val toList: List<String>,
    val ccList: List<String> = emptyList(),
    val bccList: List<String> = emptyList(),
    val subject: String,
    val date: String,
    val htmlBody: String,
    val plainPreview: String,
    val isRead: Boolean = true,
    val hasAttachment: Boolean = false
)

data class EmailThread(
    val id: String,
    val subject: String,
    val messages: List<EmailMessage>
) {
    val latestMessage: EmailMessage get() = messages.last()
    val messageCount: Int get() = messages.size
    val isUnread: Boolean get() = messages.any { !it.isRead }
}
