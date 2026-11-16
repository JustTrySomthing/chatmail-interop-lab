package app.p2p

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.PermMedia
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun ChannelBoard(
    model: P2pModel,
    page: String,
    selected: Int?,
    search: String,
    selectPage: (String) -> Unit,
    selectChannel: (Int?) -> Unit,
    join: () -> Unit,
    share: (ChatmailChannel) -> Unit,
    legacy: () -> Unit,
    preview: (PreviewLink) -> Unit,
    openLink: (PreviewLink) -> Unit,
    profilePhoto: String
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val saveMedia = LocalMediaDownload.current
    val inlineAudio = LocalInlineAudio.current
    var channels by remember { mutableStateOf<List<ChatmailChannel>?>(null) }
    var posts by remember(page, selected) { mutableStateOf<List<ChannelPost>>(emptyList()) }
    var error by remember(page, selected) { mutableStateOf<String?>(null) }
    var actionError by remember(page, selected) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var channelName by rememberSaveable(page, selected) { mutableStateOf("") }
    var draft by rememberSaveable(page, selected, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var composePost by rememberSaveable(page, selected) { mutableStateOf(false) }
    var attachments by remember(page, selected) { mutableStateOf<List<AttachmentDraft>>(emptyList()) }
    var trimIndex by remember(page, selected) { mutableStateOf<Int?>(null) }
    var previewing by remember(page, selected) { mutableStateOf<ChannelPost?>(null) }
    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val drafts = uris.distinct().map { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            describeAttachment(context, uri, asFile = false)
        }
        attachments = (attachments + drafts).distinctBy(AttachmentDraft::uri)
    }
    var revision by remember { mutableIntStateOf(0) }
    var limit by remember(selected) { mutableIntStateOf(50) }
    var hasOlder by remember(selected) { mutableStateOf(false) }

    val owned = channels.orEmpty().filter { it.owned }
    val current = channels.orEmpty().firstOrNull { it.id == selected }
        ?: owned.singleOrNull()?.takeIf { selected == null && page == "My Channel" }
    val active = selected ?: current?.id
    val viewingFeed = page == "Feed" && selected == null
    LaunchedEffect(model, lifecycle, page, selected, active, limit, revision) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                error = channelAction {
                    val catalog = model.chatmailChannels()
                    channels = catalog
                    when {
                        active != null -> {
                            val history = model.chatmailChannelHistory(active, limit)
                            posts = history.posts.asReversed()
                            hasOlder = history.hasOlder
                        }
                        viewingFeed -> posts = model.chatmailChannelFeed(catalog, limit)
                    }
                }
                delay(3_000)
            }
        }
    }
    fun submit(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        actionError = null
        scope.launch {
            try {
                actionError = channelAction(action)
                if (actionError == null) revision++
            } finally {
                busy = false
            }
        }
    }

    fun openAttachment(post: ChannelPost) {
        if (post.localFile == null) {
            submit { model.requestChannelAttachment(post.id) }
        } else if (post.mediaPage() != null) {
            inlineAudio?.value = null
            previewing = post
        } else {
            post.download()?.let { saveMedia?.invoke(it) }
        }
    }

    if (composePost && current?.owned == true && trimIndex == null) {
        val channel = current
        Sheet("Write a post", { if (!busy) composePost = false }) {
            val focus = LocalFocusManager.current
            val keyboard = LocalSoftwareKeyboardController.current
            var showEmoji by rememberSaveable { mutableStateOf(false) }
            OutlinedTextField(draft, { if (it.text.length <= INTEROP_TEXT_LIMIT) draft = it },
                modifier = Modifier.fillMaxWidth(), label = { Text("What would you like to share?") },
                minLines = 3, maxLines = 6, enabled = !busy,
                textStyle = contentTextStyle(MaterialTheme.typography.bodyLarge),
                shape = RoundedFieldShape)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                P2pTextButton(enabled = !busy,
                    onClick = { mediaPicker.launch(arrayOf("image/*", "video/*", "audio/*")) }) {
                    Icon(Icons.Outlined.PermMedia, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add media")
                }
                IconButton(enabled = !busy, onClick = {
                    if (!showEmoji) {
                        keyboard?.hide()
                        focus.clearFocus()
                    }
                    showEmoji = !showEmoji
                }) { Icon(Icons.Outlined.EmojiEmotions, "Emoji picker") }
            }
            if (showEmoji) EmojiPicker(Modifier.fillMaxWidth().height(210.dp), insert = { emoji ->
                val cursor = draft.selection
                val text = draft.text.replaceRange(cursor.min, cursor.max, emoji)
                if (text.length <= INTEROP_TEXT_LIMIT) draft = TextFieldValue(text, TextRange(cursor.min + emoji.length))
            }, close = { showEmoji = false })
            AttachmentDraftList(attachments, busy,
                removeAttachment = { index -> attachments = attachments.filterIndexed { position, _ -> position != index } },
                trimAttachment = {
                    inlineAudio?.value = null
                    trimIndex = it
                })
            actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            PageActionButton(
                if (busy) "Publishing…" else "Post",
                onClick = {
                    val text = draft.text
                    val media = attachments
                    submit {
                        model.publishChatmailChannelPost(channel.id, text, media)
                        draft = TextFieldValue()
                        attachments = emptyList()
                        composePost = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = channel.canPost && !busy && (draft.text.isNotBlank() || attachments.isNotEmpty())
            )
        }
    }

    trimIndex?.let { index ->
        attachments.getOrNull(index)?.let { item ->
            Sheet("Trim attachment", { trimIndex = null }) {
                TrimAttachmentPanel(item) { range ->
                    attachments = attachments.mapIndexed { position, draft ->
                        if (position == index) draft.copy(range = range) else draft
                    }
                    trimIndex = null
                }
            }
        }
    }
    previewing?.let { post ->
        post.mediaPage()?.let { media ->
            MediaDialog(listOf(media), media.id, close = { previewing = null }, feedback = null) {
                post.download()?.let { saveMedia?.invoke(it) }
            }
        }
    }

    Segments(listOf("Feed", "Following", "My Channel"), page, select = selectPage)
    val matches = posts.filter { search.isBlank() || it.channelName.contains(search, true) || it.text.contains(search, true) }
    LazyColumn(contentPadding = PaddingValues(16.dp, 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (error != null) item { Text(error.orEmpty(), color = MaterialTheme.colorScheme.error) }
        if (channels == null && error == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (actionError != null && !composePost) item { Text(actionError.orEmpty(), color = MaterialTheme.colorScheme.error) }
        if (selected != null) item {
            P2pTextButton(onClick = { selectChannel(null) }) { Text("Back to $page") }
        }

        when {
            active != null -> {
                if (current != null) {
                    item {
                        ChannelProfileCard(current, profilePhoto.takeIf { current.owned }.orEmpty(), share)
                    }
                    if (current.owned) item {
                        PageActionButton("Write a post", {
                            actionError = null
                            composePost = true
                        }, Modifier.fillMaxWidth(), icon = Icons.Outlined.Edit, enabled = current.canPost && !busy)
                    }
                    if (current.owned && matches.isNotEmpty()) item {
                        val colors = MaterialTheme.colorScheme
                        Surface(color = colors.surfaceVariant, contentColor = colors.onSurface,
                            shape = RoundedCornerShape(16.dp)) {
                            Text("My posts", Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                if (matches.isEmpty() && current != null && error == null) item {
                    EmptyTabMessage(if (search.isBlank()) "No posts yet" else "No matching posts")
                }
                items(matches, key = { it.id }) { post ->
                    ChannelPostCard(post, current?.owned == true, profilePhoto.takeIf { current?.owned == true }.orEmpty(),
                        null, preview, openLink, { openAttachment(post) })
                }
                if (hasOlder) item { P2pTextButton(onClick = { limit += 50 }) { Text("Load older posts") } }
            }
            viewingFeed -> {
                if (matches.isEmpty() && channels != null && error == null) item {
                    EmptyTabMessage(if (search.isBlank()) "No posts from channels you follow" else "No matching posts")
                }
                items(matches, key = { it.id }) { post ->
                    ChannelPostCard(post, false, "", { selectChannel(post.channelId) }, preview, openLink, { openAttachment(post) })
                }
            }
            page == "Following" -> {
                item { P2pOutlinedButton(onClick = join, modifier = Modifier.fillMaxWidth()) { Text("Join channel") } }
                val following = channels.orEmpty().filter { !it.owned && it.name.contains(search, true) }
                if (following.isEmpty() && channels != null && error == null) item {
                    EmptyTabMessage(if (search.isBlank()) "You’re not following any channels yet" else "No matching channels")
                }
                items(following, key = { it.id }) { channel -> ChannelRow(channel) { selectChannel(channel.id) } }
            }
            channels != null -> {
                if (owned.isEmpty()) item {
                    OutlinedTextField(channelName, { channelName = it.take(80) }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Channel name") }, singleLine = true, enabled = !busy)
                    Button(shape = P2pButtonShape, enabled = !busy && channelName.isNotBlank(), onClick = {
                        val name = channelName
                        submit {
                            val id = model.createChatmailChannel(name)
                            selectChannel(id)
                        }
                    }) { Text("Create channel") }
                } else items(owned, key = { it.id }) { channel -> ChannelRow(channel) { selectChannel(channel.id) } }
            }
        }
        if (page == "My Channel" && (selected == null || current?.owned == true)) item {
            P2pTextButton(onClick = legacy) { Text("Previous Social posts") }
        }
    }
}

private suspend fun channelAction(action: suspend () -> Unit): String? = try {
    action()
    null
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    error.message ?: "Couldn’t complete the channel action."
} catch (_: LinkageError) {
    "Chatmail couldn’t start on this device."
}

@Composable
private fun ChannelRow(channel: ChatmailChannel, open: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = open, shape = RoundedCornerShape(16.dp),
        color = colors.surfaceVariant, contentColor = colors.onSurfaceVariant) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatar(channel.name, icon = Icons.Outlined.Campaign)
            Text(channel.name, Modifier.weight(1f), style = contentTextStyle(MaterialTheme.typography.titleMedium))
            if (channel.unread > 0 && !channel.owned) Badge { Text(channel.unread.toString()) }
        }
    }
}

@Composable
private fun ChannelProfileCard(channel: ChatmailChannel, photo: String, share: (ChatmailChannel) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceVariant, contentColor = colors.onSurface, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(72.dp).background(colors.primary))
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Avatar(channel.name, icon = Icons.Outlined.Campaign.takeIf { photo.isBlank() }, photo = photo, size = 52.dp)
                    Text(channel.name, Modifier.weight(1f),
                        style = contentTextStyle(MaterialTheme.typography.headlineSmall),
                        fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (channel.owned) P2pTextButton(onClick = { share(channel) }) { Text("Share invite") }
            }
        }
    }
}

@Composable
internal fun SocialPostCard(
    author: String, date: String?, key: String, body: String, photo: String = "",
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null, open: (() -> Unit)? = null,
    modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier = modifier, color = colors.surfaceVariant, contentColor = colors.onSurface,
        shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(author, icon = icon, photo = photo)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    if (open != null) P2pTextButton(onClick = open, contentPadding = PaddingValues(0.dp)) {
                        Text(author, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    } else Text(author, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    date?.let { Text(it, color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium) }
                }
                ReadAloudButton(key, body, "post")
            }
            content()
        }
    }
}

@Composable
private fun ChannelPostCard(
    post: ChannelPost, owned: Boolean, photo: String, open: (() -> Unit)?, preview: (PreviewLink) -> Unit,
    openLink: (PreviewLink) -> Unit, openAttachment: () -> Unit
) {
    val save = LocalMediaDownload.current
    val colors = MaterialTheme.colorScheme
    val date = remember(post.time) {
        post.time.takeIf { it > 0 }?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) }
    }
    SocialPostCard(post.channelName, date, "channel-post:${post.id}", post.text, photo,
        icon = Icons.Outlined.Campaign.takeIf { photo.isBlank() }, open = open) {
        MessageBodyWithPreviews(post.text, preview, openLink, colors.surfaceVariant)
        post.attachment?.let { attachment ->
            val download = post.download()
            ChatAttachment(attachment.displayData(post.localFile), post.localFile, !owned,
                openAttachment, if (post.localFile == null || download == null || save == null) null else ({ save(download) }),
                playbackKey = "channel:${post.id}", usePlaybackPoster = true)
        }
        if (owned && post.state == 20) Text("Queued", style = MaterialTheme.typography.labelMedium)
        if (owned && post.state == 24) Text("Send failed", color = colors.error, style = MaterialTheme.typography.labelMedium)
    }
}

private fun ChannelPost.download(): MediaDownload? = attachment?.let {
    MediaDownload(it.name, it.mime, channelMessage = id)
}

private fun ChannelPost.mediaPage(): MediaPage? {
    val file = localFile ?: return null
    val media = attachment ?: return null
    val kind = mediaKind(media.mime, media.name, file) ?: return null
    return MediaPage(id.toString(), file, media.name, kind)
}
