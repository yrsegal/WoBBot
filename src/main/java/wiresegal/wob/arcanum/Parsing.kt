package wiresegal.wob.arcanum

import de.btobastian.javacord.entities.User
import de.btobastian.javacord.entities.channels.PrivateChannel
import de.btobastian.javacord.entities.message.Message
import de.btobastian.javacord.entities.message.embed.EmbedBuilder
import de.btobastian.javacord.entities.permissions.PermissionState
import de.btobastian.javacord.entities.permissions.PermissionType
import de.btobastian.javacord.entities.permissions.PermissionsBuilder
import wiresegal.wob.*
import wiresegal.wob.misc.setupControls
import wiresegal.wob.misc.setupDeletable
import wiresegal.wob.misc.util.FakeEmbedBuilder
import wiresegal.wob.plugin.sendError
import wiresegal.wob.plugin.visibleCommands

/**
 * @author WireSegal
 * Created at 11:40 PM on 2/15/18.
 */

fun embedFromContent(titlePrefix: String, entry: Entry): EmbedBuilder {
    val date = entry.date.split("-")
    val month = months[date[1].toInt() - 1]
    val dateStr = "($month ${date[2].removePrefix("0")}, ${date[0]})"

    val title = titlePrefix + entry.eventName + " " + dateStr

    val embed = EmbedBuilder()
            .setColor(arcanumColor)
            .setTitle(title)
            .setUrl(entry.toString())
            .setThumbnail(iconUrl)

    val flags = mutableListOf<String>()

    if (entry.eventState == ReviewState.PENDING) flags.add("__Pending Review__")
    if (entry.paraphrased) flags.add("__Paraphrased__")
    if (entry.eventState == ReviewState.APPROVED) flags.add("_Approved_")

    if (flags.isNotEmpty())
        embed.setDescription("**" + flags.joinToString() + "**")

    if (entry.note != null && entry.note.isNotBlank())
        embed.setFooter("Footnote: " + entry.getFooterText())

    val arcanumSuffix = "*… (Check Arcanum for more.)*"
    for ((speaker, comment) in entry.lines.map {
                val speaker = it.getTrueSpeaker().run { if (isEmpty()) "Context" else this }
                val comment = it.getTrueText()
                if (comment.length > 1024) speaker to comment.substring(0, 1024 - arcanumSuffix.length)
                        .replace("\\w+$".toRegex(), "").trim() + arcanumSuffix
                else speaker to comment
            }) {
        embed.addField(speaker, comment, false)
        val newJson = embed.toJsonNode()
        val footer = newJson.objectNode()
        footer.put("text", "(Too long to display. Check the original for more.)")
        val oldFooter = newJson.get("footer")?.toString() ?: ""
        val size = footer.toString().length - oldFooter.length
        if (newJson.toString().length > 2000 - size) {
            newJson.set("footer", footer)
            return FakeEmbedBuilder(newJson)
        }
    }

    if (embed.toJsonNode().toString().length > 2000)
        return backupEmbed(title, entry)

    return embed
}

fun backupEmbed(title: String, entry: Entry): EmbedBuilder {
    val backup = EmbedBuilder().setColor(arcanumColor).setTitle(title)
            .setUrl(entry.toString())
            .setThumbnail(iconUrl)
    backup.setDescription("This entry is too long. Click on the link above to see the original.")
    return backup
}

val months = listOf("Jan.", "Feb.", "March", "April",
        "May", "June", "July", "Aug.",
        "Sept.", "Oct.", "Nov.", "Dec.")

fun harvestFromSearch(terms: List<String>): List<EmbedBuilder> {
    val (allArticles, large) = entriesFromSearch(terms)
    val allEmbeds = mutableListOf<EmbedBuilder>()

    val size = if (large) "... (250)" else allArticles.size.toString()

    for ((idx, article) in allArticles.withIndex()) {
        val titleText = "Search: \"${terms.joinToString()}\" (${idx+1}/$size) \n"
        allEmbeds.add(embedFromContent(titleText, article))
    }

    return allEmbeds
}

fun searchWoB(message: Message, terms: List<String>) {
    var type = AutoCloseable {}
    try {
        val it = message.channel.sendMessage("Searching for \"${terms.joinToString(" ")}\"...").get()
        type = message.channel.typeContinuously()
        val allEmbeds = harvestFromSearch(terms)

        type.close()

        when {
            allEmbeds.isEmpty() -> message.channel.sendMessage("Couldn't find any entries for \"${terms.joinToString().replace("&!", "!")}\".")
            allEmbeds.size == 1 -> {
                val finalEmbed = allEmbeds.first()
                finalEmbed.setTitle(finalEmbed.toJsonNode()["title"].asText().replace(".*\n".toRegex(), ""))
                message.channel.sendMessage(finalEmbed).get().setupDeletable(message.author)
            }
            else ->
                message.channel.sendMessage(allEmbeds.first()).handle { u, _ -> type.close(); u }.get()
                        .setupDeletable(message.author).setupControls(message.author, 0, allEmbeds)
        }
        if (it.channel !is PrivateChannel)
            it.delete()
    } catch (e: Exception) {
        type.close()
        message.sendError("An error occurred trying to look up the entry.", e)
    }
}

fun about(message: Message) {
    val invite = api.createBotInvite(PermissionsBuilder().setState(PermissionType.MANAGE_MESSAGES, PermissionState.ALLOWED).build())
    val wireID = 77084495118868480L
    val wire = api.getUserById(wireID)
    val wireStr = if (wire.isPresent) wire.get().mentionTag else "@wiresegal#1522"
    val host = api.owner
    val hostStr = if (host.isPresent) host.get().mentionTag else wireStr

    val add = if (hostStr != wireStr) "\nHosted by: $hostStr" else ""

    message.channel.sendMessage(EmbedBuilder().apply {
        setTitle("About WoBBot")
        setColor(arcanumColor)

        setDescription("**Commands:** \n" +
                (visibleCommands.filter { it.matches(message) }.joinToString("\n") { " * " + it.name }) + "\n" +
                "Author: $wireStr$add\n" +
                "[Invite Link]($invite) | " +
                "[Github Source](https://github.com/Palanaeum/WoBBot) | " +
                "[Arcanum]($urlTarget)")
    })
}

fun applyToOwners(toApply: User.() -> Unit) {
    val wireID = 77084495118868480L
    val wire = api.getUserById(wireID)

    if (wobCommand == "wob" && wire.isPresent)
        wire.get().toApply()
    if (api.owner.isPresent && (api.ownerId != wireID || wobCommand != "wob"))
        api.owner.get().toApply()
}

fun notifyOwners() = notifyOwners {
    setColor(arcanumColor)
    setTitle("Launch Notification")
    addField("Last Commit", "$commitDesc ($commitId)", false)
    addField("Committer", committer.toString(), false)
    addField("Commit Time", version.toString(), false)
    setTimestamp()
}

fun notifyOwners(embed: EmbedBuilder.() -> Unit) = applyToOwners {
    sendMessage(EmbedBuilder().apply(embed))
}

fun notifyOwners(data: String, name: String) = applyToOwners {
    sendTo(data, name)
}

fun User.sendTo(data: String, name: String) {
    val prefix = "$name: "
    when {
        data.length > 2000 - prefix.length ->
            sendMessage(data.byteInputStream(), "$name.txt")
        data.isEmpty() ->
            sendMessage("Nothing found for $name!")
        else ->
            sendMessage(prefix + data)
    }
}