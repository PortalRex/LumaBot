package gq.luma.bot.services;

import gq.luma.bot.Luma;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.javacord.api.entity.channel.ServerTextChannel;
import org.javacord.api.entity.message.Message;
import org.javacord.api.entity.message.Reaction;
import org.javacord.api.entity.message.WebhookMessageBuilder;
import org.javacord.api.entity.message.embed.Embed;
import org.javacord.api.entity.message.embed.EmbedBuilder;
import org.javacord.api.entity.message.mention.AllowedMentionsBuilder;
import org.javacord.api.entity.server.Server;
import org.javacord.api.entity.webhook.IncomingWebhook;
import org.javacord.api.entity.webhook.Webhook;
import org.javacord.api.event.message.reaction.SingleReactionEvent;
import org.javacord.core.DiscordApiImpl;
import org.javacord.core.entity.message.MessageImpl;
import org.javacord.core.util.rest.RestEndpoint;
import org.javacord.core.util.rest.RestMethod;
import org.javacord.core.util.rest.RestRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class PinsService implements Service {
    private static final Logger logger = LoggerFactory.getLogger(PinsService.class);

    @Override
    public void startService() {
        Bot.api.addReactionAddListener(event -> {
            if (event.getServer().isEmpty()) {
                return;
            }
            Server server = event.getServer().get();

            // Ignore messages in any channels on the blacklist

            if (Luma.database.isChannelPinBlacklisted(server.getId(), event.getChannel().getId())) {
                return;
            }

            // Check if the emoji matches the server's pin emoji (if it exists)
            if (Luma.database.ifServerPinEmojiMatches(server, event.getEmoji())) {
                // Check if the message has already been pinned
                if(Luma.database.isPinnedMessageNotified(event.getMessageId())) {
                    // Update its pin count
                    updateMessagePinCount(event, server);
                } else {
                    requestFreshMessage(event).thenAccept(pinnedMessage -> pinnedMessage
                            .getReactionByEmoji(event.getEmoji()).ifPresent(reaction -> {
                        // Check if it needs to be pinned
                        if (reaction.getCount() >= Luma.database.getServerPinThreshold(server).orElse(Integer.MAX_VALUE)) {
                            Luma.database.getServerPinChannel(server).ifPresent(pinsChannel -> {
                                IncomingWebhook pinWebhook = pinsChannel.getWebhooks().join().stream()
                                        .filter(Webhook::isIncomingWebhook)
                                        .filter(webhook -> webhook.getName().map(name -> name.contains("Luma")).orElse(false))
                                        .map(Webhook::asIncomingWebhook)
                                        .findAny().orElseGet(() -> this.createPinWebhook(pinsChannel))
                                        .orElseThrow(AssertionError::new);

                                // Preserve the original filename instead of deriving one from the signed URL.
                                WebhookMessageBuilder builder = new WebhookMessageBuilder();

                                builder.setDisplayAuthor(pinnedMessage.getAuthor());
                                builder.setDisplayAvatar(pinnedMessage.getAuthor().getAvatar());
                                builder.setDisplayName(pinnedMessage.getAuthor().getDisplayName());
                                String content = pinnedMessage.getContent() == null ? "" : pinnedMessage.getContent();
                                boolean hasGifAttachment = pinnedMessage.getAttachments().stream()
                                        .anyMatch(attachment -> attachment.getFileName().toLowerCase(Locale.ROOT).endsWith(".gif"));
                                for (Embed embed : pinnedMessage.getEmbeds()) {
                                    boolean directGifImage = "image".equals(embed.getType()) &&
                                            (hasGifAttachment || embed.getUrl().map(url -> url.getPath()
                                                    .toLowerCase(Locale.ROOT).endsWith(".gif")).orElse(false));
                                    if ("gifv".equals(embed.getType()) || directGifImage) {
                                        if (hasGifAttachment) {
                                            // The original GIF is copied from the attachment below.
                                            continue;
                                        }
                                        if (attachGifMedia(builder, embed, pinnedMessage.getId())) {
                                            String embedUrl = embed.getUrl().map(URL::toString).orElse("");
                                            if (!embedUrl.isEmpty()) {
                                                content = content.replace(embedUrl, "").trim();
                                            }
                                            continue;
                                        }
                                    }
                                    builder.addEmbed(embed.toBuilder());
                                }
                                builder.setContent(content);
                                pinnedMessage.getAttachments().forEach(attachment -> builder.addAttachment(
                                        attachment.asByteArray().join(),
                                        attachment.getFileName(),
                                        attachment.getDescription().orElse(null)));

                                Message pinNotification = builder
                                        .addEmbed(new EmbedBuilder()
                                                .setColor(Color.RED)
                                                .setDescription(reaction.getEmoji().getMentionTag() + " " + reaction.getCount() + " - [Jump!](" + pinnedMessage.getLink().toString() + ")"))
                                        .setAllowedMentions(new AllowedMentionsBuilder()
                                                .setMentionEveryoneAndHere(false)
                                                .setMentionRoles(false)
                                                .setMentionUsers(false).build())
                                        .send(pinWebhook).join();

                                Luma.database.createPinNotification(pinnedMessage.getId(), pinNotification.getId());
                            });
                        }
                    }));
                }
            }
        });

        Bot.api.addReactionRemoveListener(event -> {
            if (event.getServer().isEmpty()) {
                return;
            }
            Server server = event.getServer().get();

            // Check if the emoji matches the server's pin emoji (if it exists)
            if (Luma.database.ifServerPinEmojiMatches(server, event.getEmoji())) {
                // Check if the message is pinned
                if(Luma.database.isPinnedMessageNotified(event.getMessageId())) {
                    // Update its pin count
                    updateMessagePinCount(event, server);
                }
            }
        });

        Bot.api.addMessageDeleteListener(event -> {
            // TODO: Reflect deleted messages in pins

            if (Luma.database.isPinnedMessageNotified(event.getMessageId())) {
                // TODO: Delete the pin record
            }
        });

        Bot.api.addMessageEditListener(event -> {
            // TODO: Reflect edited messages in pins
        });
    }

    private CompletableFuture<Message> requestFreshMessage(SingleReactionEvent event) {
        return new RestRequest<Message>(Bot.api, RestMethod.GET, RestEndpoint.MESSAGE)
                .setUrlParameters(Long.toUnsignedString(event.getChannel().getId()),
                        Long.toUnsignedString(event.getMessageId()))
                .execute(result -> new MessageImpl((DiscordApiImpl) Bot.api,
                        event.getChannel(), result.getJsonBody()));
    }

    private boolean attachGifMedia(WebhookMessageBuilder builder, Embed embed, long messageId) {
        URL gifUrl = embed.getThumbnail().map(thumbnail -> thumbnail.getUrl())
                .filter(url -> url.getPath().toLowerCase(Locale.ROOT).endsWith(".gif"))
                .orElseGet(() -> embed.getUrl().filter(url -> url.getPath()
                        .toLowerCase(Locale.ROOT).endsWith(".gif")).orElse(null));
        if (gifUrl != null) {
            return attachGifUrl(builder, gifUrl, messageId);
        }
        return embed.getVideo().map(video -> attachGifvVideo(builder, video.getUrl(), messageId)).orElse(false);
    }

    private boolean attachGifUrl(WebhookMessageBuilder builder, URL url, long messageId) {
        Request request = new Request.Builder().url(url.toString()).build();
        try (Response response = Luma.okHttpClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                logger.warn("Could not download GIF for message {}: HTTP {}.", messageId, response.code());
                return false;
            }
            byte[] bytes = body.bytes();
            if (bytes.length < 6 || bytes[0] != 'G' || bytes[1] != 'I' || bytes[2] != 'F') {
                logger.warn("GIF URL for message {} did not return a GIF.", messageId);
                return false;
            }
            builder.addAttachment(bytes, "gif-" + messageId + ".gif");
            return true;
        } catch (IOException exception) {
            logger.warn("Could not download GIF for message {}.", messageId, exception);
            return false;
        }
    }

    private boolean attachGifvVideo(WebhookMessageBuilder builder, URL videoUrl, long messageId) {
        Path source = null;
        Path gif = null;
        try {
            source = Files.createTempFile("luma-gifv-", ".mp4");
            gif = Files.createTempFile("luma-gifv-", ".gif");
            Request request = new Request.Builder().url(videoUrl.toString()).build();
            try (Response response = Luma.okHttpClient.newCall(request).execute()) {
                ResponseBody body = response.body();
                if (!response.isSuccessful() || body == null) {
                    logger.warn("Could not download GIF video for message {}: HTTP {}.", messageId, response.code());
                    return false;
                }
                Files.copy(body.byteStream(), source, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            Process process = new ProcessBuilder("ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error",
                    "-y", "-i", source.toString(), "-t", "15", "-filter_complex",
                    "fps=12,scale=480:-1:force_original_aspect_ratio=decrease:flags=lanczos,split[a][b];"
                            + "[a]palettegen=max_colors=128[p];[b][p]paletteuse=dither=bayer:bayer_scale=5",
                    "-an", "-loop", "0", gif.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                logger.warn("GIF conversion timed out for message {}.", messageId);
                return false;
            }
            if (process.exitValue() != 0 || Files.size(gif) == 0) {
                logger.warn("GIF conversion failed for message {} (exit {}).", messageId, process.exitValue());
                return false;
            }
            builder.addAttachment(Files.readAllBytes(gif), "gif-" + messageId + ".gif");
            return true;
        } catch (IOException exception) {
            logger.warn("Could not convert GIF video for message {}.", messageId, exception);
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.warn("GIF conversion interrupted for message {}.", messageId, exception);
            return false;
        } finally {
            deleteTemporaryFile(source);
            deleteTemporaryFile(gif);
        }
    }

    private void deleteTemporaryFile(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException exception) {
                logger.warn("Could not remove temporary GIF file {}.", path, exception);
            }
        }
    }

    private void updateMessagePinCount(SingleReactionEvent event, Server server) {
        event.requestReaction().thenApply(reactionOp -> reactionOp.map(Reaction::getCount).orElse(0)).thenAccept(count -> {
            updateNumericPinCount(event, server, count);
        }).exceptionally(e -> {
            updateNumericPinCount(event, server, 0);
            return null;
        });
    }

    private void updateNumericPinCount(SingleReactionEvent event, Server server, int count) {
        String serverPinEmojiMention = Luma.database.getServerPinEmojiMention(server).orElseThrow(AssertionError::new);

        Message pinnedMessage = event.requestMessage().join();
        Message pinNotification = Luma.database.getPinNotificationByPinnedMessage(event.getMessageId(), Luma.database.getServerPinChannel(server).orElseThrow(AssertionError::new).getId());
        IncomingWebhook pinWebhook = pinNotification.getAuthor().asWebhook()
                .orElseThrow(AssertionError::new).join()
                .asIncomingWebhook().orElseThrow(AssertionError::new);

        EmbedBuilder pinBox = new EmbedBuilder()
                .setColor(Color.RED)
                .setDescription(serverPinEmojiMention + " " + count + " - [Jump!](" + pinnedMessage.getLink().toString() + ")");

        // Bot.api.getUncachedMessageUtil().edit(pinWebhook.getId(), pinWebhook.getToken(),
        //        pinNotification.getId(), pinNotification.getContent(), true,
        //        pinBox, true).exceptionally(ExceptionLogger.get());
    }

    private Optional<IncomingWebhook> createPinWebhook(ServerTextChannel textChannel) {
        return Optional.of(textChannel.createWebhookBuilder()
                .setName("Luma Pins")
                .setAvatar(Bot.api.getYourself().getAvatar())
                .create().join());
    }
}
