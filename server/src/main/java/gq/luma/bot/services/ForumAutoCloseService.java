package gq.luma.bot.services;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import gq.luma.bot.Luma;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.javacord.api.entity.channel.ServerChannel;
import org.javacord.api.entity.channel.ServerThreadChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class ForumAutoCloseService implements Service {
    private static final Logger logger = LoggerFactory.getLogger(ForumAutoCloseService.class);

    private static final String CHECK_MARK = "\u2705";
    private static final int MAX_FORUM_TAGS = 5;
    private static final MediaType JSON = MediaType.parse("application/json");

    private Config config;
    private boolean enabled;

    @Override
    public void startService() {
        enabled = loadConfig();
        if (!enabled) {
            logger.info("Forum auto-close disabled.");
            return;
        }

        logger.info("Forum auto-close enabled for forum channel {} with close tag {}.",
                config.forumChannelId, config.closeTagId);

        Luma.schedulerService.scheduleWithFixedDelay(this::scanActiveThreads, 5, 5, TimeUnit.SECONDS);

        Bot.api.addServerThreadChannelUpdateListener(event -> event.getChannel()
                .asServerThreadChannel()
                .ifPresent(this::closeTaggedThread));

        Bot.api.addReactionAddListener(event -> {
            if (!event.getEmoji().asUnicodeEmoji().filter(CHECK_MARK::equals).isPresent()) {
                return;
            }

            Optional<ServerThreadChannel> threadOptional = event.getServerThreadChannel();
            if (!threadOptional.isPresent()) {
                return;
            }

            ServerThreadChannel thread = threadOptional.get();
            if (thread.getParent().getId() != config.forumChannelId) {
                return;
            }

            event.getUser().ifPresent(user -> event.requestMessage().thenAccept(message -> {
                long reactingUserId = user.getId();
                long messageAuthorId = message.getAuthor().getId();
                if (thread.getOwnerId() == reactingUserId || messageAuthorId == reactingUserId) {
                    closeThreadFromReaction(thread);
                }
            }).exceptionally(t -> {
                logger.warn("Failed to request message {} for forum auto-close reaction.",
                        event.getMessageId(), t);
                return null;
            }));
        });
    }

    private boolean loadConfig() {
        Optional<Config> loadedConfig = parseConfig(System.getenv());
        loadedConfig.ifPresent(value -> config = value);
        return loadedConfig.isPresent();
    }

    static Optional<Config> parseConfig(Map<String, String> env) {
        String channelRaw = env.get("FORUM_AUTO_CLOSE_CHANNEL_ID");
        String tagRaw = env.get("FORUM_AUTO_CLOSE_TAG_ID");

        if (channelRaw == null && tagRaw == null) {
            return Optional.empty();
        }

        if (channelRaw == null || tagRaw == null) {
            throw new IllegalArgumentException("FORUM_AUTO_CLOSE_CHANNEL_ID and FORUM_AUTO_CLOSE_TAG_ID must both be provided when enabling forum auto-close.");
        }

        long forumChannelId;
        try {
            forumChannelId = Long.parseUnsignedLong(channelRaw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("FORUM_AUTO_CLOSE_CHANNEL_ID must be an integer Discord channel id.", e);
        }

        long closeTagId;
        try {
            closeTagId = Long.parseUnsignedLong(tagRaw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("FORUM_AUTO_CLOSE_TAG_ID must be an integer Discord forum tag id.", e);
        }

        return Optional.of(new Config(forumChannelId, closeTagId));
    }

    private void closeTaggedThread(ServerThreadChannel thread) {
        if (thread.getParent().getId() != config.forumChannelId) {
            return;
        }

        Luma.executorService.submit(() -> {
            try {
                DiscordThreadState state = requestThreadState(thread.getId());
                if (state.parentId == config.forumChannelId && state.hasCloseTag(config.closeTagId) && !state.archived) {
                    archiveThread(thread, false, true);
                }
            } catch (IOException e) {
                logger.warn("Failed to inspect thread {} for forum auto-close.", thread.getId(), e);
            }
        });
    }

    private void scanActiveThreads() {
        try {
            Optional<ServerChannel> forumChannel = Bot.api.getServerChannelById(config.forumChannelId);
            if (!forumChannel.isPresent()) {
                logger.warn("Configured forum auto-close channel {} is not visible to the bot.", config.forumChannelId);
                return;
            }

            long serverId = forumChannel.get().getServer().getId();
            Request request = new Request.Builder()
                    .url("https://discord.com/api/v10/guilds/" + Long.toUnsignedString(serverId) + "/threads/active")
                    .addHeader("Authorization", Bot.api.getPrefixedToken())
                    .build();

            try (Response response = Luma.okHttpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    logger.warn("Discord returned {} while scanning active threads for forum auto-close.", response.code());
                    return;
                }
                ResponseBody body = response.body();
                if (body == null) {
                    logger.warn("Discord returned an empty active thread response for forum auto-close.");
                    return;
                }

                Json.parse(body.string()).asObject()
                        .get("threads").asArray()
                        .forEach(threadJson -> {
                            DiscordThreadState state = DiscordThreadState.from(threadJson.asObject());
                            if (state.parentId == config.forumChannelId
                                    && state.hasCloseTag(config.closeTagId)
                                    && !state.archived) {
                                logger.info("Auto-closing tagged forum thread {}.", state.id);
                                archiveThreadById(state.id, true);
                            }
                        });
            }
        } catch (Exception e) {
            logger.warn("Failed to scan active threads for forum auto-close.", e);
        }
    }

    private void closeThreadFromReaction(ServerThreadChannel thread) {
        Luma.executorService.submit(() -> {
            try {
                DiscordThreadState state = requestThreadState(thread.getId());
                if (state.parentId != config.forumChannelId || state.archived) {
                    return;
                }

                CloseTagPlan closeTagPlan = planCloseTagUpdate(state.appliedTags, config.closeTagId);
                if (closeTagPlan == CloseTagPlan.ADD) {
                    state.appliedTags.add(config.closeTagId);
                    patchThreadTags(thread.getId(), state.appliedTags);
                } else if (closeTagPlan == CloseTagPlan.SKIP_TAG_LIMIT) {
                    logger.warn("Skipping close tag add for thread {}: already at tag limit ({}).",
                            thread.getId(), MAX_FORUM_TAGS);
                }

                archiveThread(thread, false, true);
            } catch (IOException e) {
                logger.warn("Failed to auto-close thread {} after owner reaction.", thread.getId(), e);
            }
        });
    }

    private void archiveThread(ServerThreadChannel thread, boolean retryingUnarchivedThread, boolean lockThread) {
        thread.createUpdater()
                .setArchivedFlag(true)
                .setLockedFlag(lockThread)
                .update()
                .exceptionally(t -> {
                    if (!retryingUnarchivedThread) {
                        thread.createUpdater()
                                .setArchivedFlag(false)
                                .update()
                                .thenRun(() -> archiveThread(thread, true, lockThread))
                                .exceptionally(retryError -> {
                                    logger.warn("Failed to unarchive thread {} before auto-closing.",
                                            thread.getId(), retryError);
                                    return null;
                                });
                    } else {
                        logger.warn("Failed to auto-close thread {}.", thread.getId(), t);
                    }
                    return null;
                });
    }

    private void archiveThreadById(long threadId, boolean lockThread) {
        Bot.api.getServerThreadChannelById(threadId)
                .ifPresentOrElse(
                        thread -> archiveThread(thread, false, lockThread),
                        () -> {
                            try {
                                patchThreadArchived(threadId, true, lockThread);
                            } catch (IOException e) {
                                logger.warn("Failed to auto-close uncached thread {}.", threadId, e);
                            }
                        });
    }

    private DiscordThreadState requestThreadState(long threadId) throws IOException {
        Request request = new Request.Builder()
                .url("https://discord.com/api/v10/channels/" + Long.toUnsignedString(threadId))
                .addHeader("Authorization", Bot.api.getPrefixedToken())
                .build();

        try (Response response = Luma.okHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Discord returned " + response.code() + " when fetching channel " + threadId);
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("Discord returned an empty channel response for " + threadId);
            }
            return DiscordThreadState.from(Json.parse(body.string()).asObject());
        }
    }

    private void patchThreadTags(long threadId, List<Long> appliedTags) throws IOException {
        JsonArray tags = new JsonArray();
        appliedTags.forEach(tags::add);
        JsonObject payload = new JsonObject().add("applied_tags", tags);

        Request request = new Request.Builder()
                .url("https://discord.com/api/v10/channels/" + Long.toUnsignedString(threadId))
                .addHeader("Authorization", Bot.api.getPrefixedToken())
                .patch(RequestBody.create(JSON, payload.toString()))
                .build();

        try (Response response = Luma.okHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Discord returned " + response.code() + " when updating tags for channel " + threadId);
            }
        }
    }

    private void patchThreadArchived(long threadId, boolean archived, boolean locked) throws IOException {
        JsonObject payload = new JsonObject()
                .add("archived", archived)
                .add("locked", locked);

        Request request = new Request.Builder()
                .url("https://discord.com/api/v10/channels/" + Long.toUnsignedString(threadId))
                .addHeader("Authorization", Bot.api.getPrefixedToken())
                .patch(RequestBody.create(JSON, payload.toString()))
                .build();

        try (Response response = Luma.okHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Discord returned " + response.code() + " when archiving channel " + threadId);
            }
        }
    }

    static CloseTagPlan planCloseTagUpdate(List<Long> appliedTags, long closeTagId) {
        if (appliedTags.contains(closeTagId)) {
            return CloseTagPlan.ALREADY_PRESENT;
        }
        if (appliedTags.size() >= MAX_FORUM_TAGS) {
            return CloseTagPlan.SKIP_TAG_LIMIT;
        }
        return CloseTagPlan.ADD;
    }

    static class Config {
        final long forumChannelId;
        final long closeTagId;

        Config(long forumChannelId, long closeTagId) {
            this.forumChannelId = forumChannelId;
            this.closeTagId = closeTagId;
        }
    }

    enum CloseTagPlan {
        ADD,
        ALREADY_PRESENT,
        SKIP_TAG_LIMIT
    }

    private static class DiscordThreadState {
        private final long id;
        private final long parentId;
        private final boolean archived;
        private final List<Long> appliedTags;

        private DiscordThreadState(long id, long parentId, boolean archived, List<Long> appliedTags) {
            this.id = id;
            this.parentId = parentId;
            this.archived = archived;
            this.appliedTags = appliedTags;
        }

        private boolean hasCloseTag(long closeTagId) {
            return appliedTags.contains(closeTagId);
        }

        private static DiscordThreadState from(JsonObject object) {
            long id = Long.parseUnsignedLong(object.getString("id", "0"));
            long parentId = Long.parseUnsignedLong(object.getString("parent_id", "0"));
            boolean archived = object.get("thread_metadata") != null
                    && object.get("thread_metadata").asObject().getBoolean("archived", false);
            List<Long> appliedTags = new ArrayList<>();

            if (object.get("applied_tags") != null && object.get("applied_tags").isArray()) {
                object.get("applied_tags").asArray().forEach(tag ->
                        appliedTags.add(Long.parseUnsignedLong(tag.asString())));
            }

            return new DiscordThreadState(id, parentId, archived, appliedTags);
        }
    }
}
