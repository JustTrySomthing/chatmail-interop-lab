package app.p2p

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    openLink: (PreviewLink) -> Unit
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
    var draft by remember(page, selected) { mutableStateOf("") }
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
        if (actionError != null) item { Text(actionError.orEmpty(), color = MaterialTheme.colorScheme.error) }
        if (selected != null) item {
            P2pTextButton(onClick = { selectChannel(null) }) { Text("Back to $page") }
        }

        when {
            active != null -> {
                if (current != null) item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(current.name, icon = Icons.Outlined.Campaign)
                        Text(current.name, Modifier.padding(start = 12.dp),
                            style = MaterialTheme.typography.titleLarge)
                    }
                    if (current.owned) {
                        P2pTextButton(onClick = { share(current) }) { Text("Share invite") }
                        OutlinedTextField(draft, { draft = it.take(INTEROP_TEXT_LIMIT) },
                            modifier = Modifier.fillMaxWidth(), label = { Text("Write a post") },
                            maxLines = 5, enabled = !busy)
                        AttachmentDraftList(attachments, busy,
                            removeAttachment = { index -> attachments = attachments.filterIndexed { position, _ -> position != index } },
                            trimAttachment = {
                                inlineAudio?.value = null
                                trimIndex = it
                            })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            P2pOutlinedButton(
                                enabled = current.canPost && !busy,
                                onClick = { mediaPicker.launch(arrayOf("image/*", "video/*", "audio/*")) }
                            ) {
                                Icon(Icons.Outlined.AttachFile, null)
                                Text("Add media")
                            }
                            Button(shape = P2pButtonShape,
                                enabled = current.canPost && (draft.isNotBlank() || attachments.isNotEmpty()) && !busy,
                                onClick = {
                                    val text = draft
                                    val media = attachments
                                    submit {
                                        model.publishChatmailChannelPost(current.id, text, media)
                                        draft = ""
                                        attachments = emptyList()
                                    }
                                }) { Text("Publish") }
                        }
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                if (matches.isEmpty() && current != null && error == null) item {
                    EmptyTabMessage(if (search.isBlank()) "No posts yet" else "No matching posts")
                }
                items(matches, key = { it.id }) { post ->
                    ChannelPostCard(post, current?.owned == true, null, preview, openLink, { openAttachment(post) })
                }
                if (hasOlder) item { P2pTextButton(onClick = { limit += 50 }) { Text("Load older posts") } }
            }
            viewingFeed -> {
                if (matches.isEmpty() && channels != null && error == null) item {
                    EmptyTabMessage(if (search.isBlank()) "No posts from channels you follow" else "No matching posts")
                }
                items(matches, key = { it.id }) { post ->
                    ChannelPostCard(post, false, { selectChannel(post.channelId) }, preview, openLink, { openAttachment(post) })
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
                    OutlinedTextField(draft, { draft = it.take(80) }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Channel name") }, singleLine = true, enabled = !busy)
                    Button(shape = P2pButtonShape, enabled = !busy && draft.isNotBlank(), onClick = {
                        val name = draft
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
private fun ChannelPostCard(
    post: ChannelPost, owned: Boolean, open: (() -> Unit)?, preview: (PreviewLink) -> Unit,
    openLink: (PreviewLink) -> Unit, openAttachment: () -> Unit
) {
    val save = LocalMediaDownload.current
    val colors = MaterialTheme.colorScheme
    val date = remember(post.time) {
        post.time.takeIf { it > 0 }?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) }
    }
    Surface(color = colors.surfaceVariant, contentColor = colors.onSurfaceVariant, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(post.channelName, icon = Icons.Outlined.Campaign)
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    if (open != null) P2pTextButton(onClick = open, contentPadding = PaddingValues(horizontal = 0.dp)) {
                        Text(post.channelName, fontWeight = FontWeight.SemiBold)
                    } else Text(post.channelName, fontWeight = FontWeight.SemiBold)
                    date?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                }
                ReadAloudButton("channel-post:${post.id}", post.text, "post")
            }
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
