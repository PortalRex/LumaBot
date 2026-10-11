package gq.luma.bot.services;

import gq.luma.bot.Luma;
import org.javacord.api.DiscordApi;
import org.javacord.api.entity.channel.ServerTextChannel;
import org.javacord.api.entity.emoji.Emoji;
import org.javacord.api.entity.message.Message;
import org.javacord.api.entity.message.MessageAttachment;
import org.javacord.api.entity.message.Reaction;
import org.javacord.api.entity.server.Server;
import org.javacord.api.event.message.reaction.ReactionAddEvent;
import org.javacord.api.event.message.reaction.SingleReactionEvent;
import org.javacord.api.listener.message.reaction.ReactionAddListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class PinsServiceTest {
    private final Server server = stub(Server.class, Map.of("getId", 1L));
    private final ServerTextChannel channel = stub(ServerTextChannel.class, Map.of("getId", 2L));
    private final Emoji emoji = stub(Emoji.class, Map.of("getMentionTag", "pin"));
    private final TestDatabase database = new TestDatabase();
    private final TestPinsService service = new TestPinsService();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private ReactionAddListener listener;
    private DiscordApi previousApi;
    private Database previousDatabase;

    @BeforeEach
    void setUp() {
        previousApi = Bot.api;
        previousDatabase = Luma.database;
        Luma.database = database;
        Bot.api = (DiscordApi) Proxy.newProxyInstance(DiscordApi.class.getClassLoader(),
                new Class<?>[]{DiscordApi.class}, (proxy, method, args) -> {
                    if (method.getName().equals("addReactionAddListener")) {
                        listener = (ReactionAddListener) args[0];
                    }
                    return null;
                });
        service.startService();
        assertNotNull(listener);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        worker.shutdownNow();
        assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        Bot.api = previousApi;
        Luma.database = previousDatabase;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void overlappingReactionsSendOnePinForTextAndAttachments(boolean withAttachment) throws Exception {
        Message message = message(10L, 3, withAttachment);
        CompletableFuture<Message> first = react(10L);
        CompletableFuture<Message> second = react(10L);
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch finishSend = new CountDownLatch(1);
        service.sender = source -> {
            sending.countDown();
            await(finishSend);
            return notification(source);
        };

        CompletableFuture<Void> firstCompletion = CompletableFuture.runAsync(() -> first.complete(message), worker);
        try {
            await(sending);
            second.complete(message);
            assertEquals(1, service.sent.size());
            assertTrue(database.notifications.isEmpty());
        } finally {
            finishSend.countDown();
        }
        firstCompletion.get(5, TimeUnit.SECONDS);
        assertEquals(List.of(message), service.sent);
        assertEquals(1010L, database.notifications.get(10L));
    }

    @Test
    void guardIsHeldUntilDatabaseSaveCompletes() throws Exception {
        CompletableFuture<Message> first = react(10L);
        CompletableFuture<Message> second = react(10L);
        CountDownLatch saving = new CountDownLatch(1);
        CountDownLatch finishSave = new CountDownLatch(1);
        database.beforeSave = () -> {
            saving.countDown();
            await(finishSave);
        };
        CompletableFuture<Void> firstCompletion = CompletableFuture.runAsync(
                () -> first.complete(message(10L, 3, false)), worker);
        try {
            await(saving);
            second.complete(message(10L, 4, false));
            assertEquals(1, service.sent.size());
            assertEquals(1, database.saveAttempts.get());
        } finally {
            finishSave.countDown();
        }
        firstCompletion.get(5, TimeUnit.SECONDS);
        assertEquals(1, database.notifications.size());
    }

    @Test
    void delayedFetchRechecksPinRecordedByEarlierReaction() {
        CompletableFuture<Message> first = react(10L);
        CompletableFuture<Message> second = react(10L);
        first.complete(message(10L, 3, false));
        second.complete(message(10L, 4, false));
        assertEquals(1, service.sent.size());
        assertEquals(1, database.saveAttempts.get());
    }

    @Test
    void reactionReachingThresholdIsNotLostWhileAnEarlierFetchIsPending() {
        CompletableFuture<Message> belowThreshold = react(10L);
        CompletableFuture<Message> atThreshold = react(10L);
        belowThreshold.complete(message(10L, 2, false));
        assertTrue(service.sent.isEmpty());
        atThreshold.complete(message(10L, 3, false));
        assertEquals(1, service.sent.size());
    }

    @Test
    void failedSendCanBeRetriedOnNextReaction() {
        service.sender = source -> { throw new IllegalStateException("Simulated send failure"); };
        react(10L).complete(message(10L, 3, false));
        assertTrue(database.notifications.isEmpty());

        service.sender = this::notification;
        react(10L).complete(message(10L, 4, false));
        assertEquals(2, service.sent.size());
        assertEquals(1010L, database.notifications.get(10L));
    }

    @Test
    void failedSaveIsRetriedWithoutSendingAnotherPin() {
        database.failWrites = true;
        react(10L).complete(message(10L, 3, false));
        assertEquals(1, service.sent.size());
        assertTrue(database.notifications.isEmpty());

        database.failWrites = false;
        react(10L).complete(message(10L, 4, false));
        assertEquals(1, service.sent.size());
        assertEquals(2, database.saveAttempts.get());
        assertEquals(1010L, database.notifications.get(10L));
    }

    @Test
    void failedDatabaseRecheckDoesNotPostAndAllowsLaterRetry() {
        CompletableFuture<Message> first = react(10L);
        database.failReads = true;
        first.complete(message(10L, 3, false));
        assertTrue(service.sent.isEmpty());

        database.failReads = false;
        react(10L).complete(message(10L, 4, false));
        assertEquals(1, service.sent.size());
    }

    @Test
    void differentMessagesCanBePinnedWhileOneSendIsPending() throws Exception {
        CompletableFuture<Message> first = react(10L);
        CompletableFuture<Message> other = react(20L);
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch finishSend = new CountDownLatch(1);
        service.sender = source -> {
            if (source.getId() == 10L) {
                sending.countDown();
                await(finishSend);
            }
            return notification(source);
        };
        CompletableFuture<Void> firstCompletion = CompletableFuture.runAsync(
                () -> first.complete(message(10L, 3, false)), worker);
        try {
            await(sending);
            other.complete(message(20L, 3, true));
            assertEquals(1020L, database.notifications.get(20L));
            assertFalse(database.notifications.containsKey(10L));
        } finally {
            finishSend.countDown();
        }
        firstCompletion.get(5, TimeUnit.SECONDS);
        assertEquals(2, service.sent.size());
    }

    private CompletableFuture<Message> react(long messageId) {
        CompletableFuture<Message> fetch = new CompletableFuture<>();
        service.requests.add(fetch);
        listener.onReactionAdd(stub(ReactionAddEvent.class, Map.of(
                "getServer", Optional.of(server), "getChannel", channel,
                "getMessageId", messageId, "getEmoji", emoji)));
        return fetch;
    }

    private Message message(long id, int reactionCount, boolean withAttachment) {
        List<MessageAttachment> attachments = withAttachment
                ? List.of(stub(MessageAttachment.class, Map.of("getFileName", "image.png")))
                : Collections.emptyList();
        return stub(Message.class, Map.of("getId", id,
                "getContent", withAttachment ? "" : "A text message",
                "getAttachments", attachments,
                "getReactionByEmoji", Optional.of(stub(Reaction.class, Map.of("getCount", reactionCount)))));
    }

    private Message notification(Message source) {
        return stub(Message.class, Map.of("getId", source.getId() + 1000L));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for test step");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type, Map<String, Object> values) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "equals": return proxy == args[0];
                case "hashCode": return System.identityHashCode(proxy);
                case "toString": return "Test " + type.getSimpleName();
                default:
                    if (!values.containsKey(method.getName())) {
                        throw new AssertionError("Unexpected call: " + method);
                    }
                    return values.get(method.getName());
            }
        });
    }

    private class TestPinsService extends PinsService {
        final Queue<CompletableFuture<Message>> requests = new ConcurrentLinkedQueue<>();
        final List<Message> sent = new CopyOnWriteArrayList<>();
        Function<Message, Message> sender = PinsServiceTest.this::notification;

        @Override
        CompletableFuture<Message> requestFreshMessage(SingleReactionEvent event) {
            CompletableFuture<Message> result = requests.poll();
            assertNotNull(result, "Unexpected message fetch");
            return result;
        }

        @Override
        Message sendPinNotification(Message message, Reaction reaction, ServerTextChannel pinsChannel) {
            assertSame(channel, pinsChannel);
            sent.add(message);
            return sender.apply(message);
        }
    }

    private class TestDatabase extends Database {
        final Map<Long, Long> notifications = new ConcurrentHashMap<>();
        final AtomicInteger saveAttempts = new AtomicInteger();
        boolean failReads;
        boolean failWrites;
        Runnable beforeSave = () -> {};

        @Override
        public boolean isChannelPinBlacklisted(long serverId, long channelId) { return false; }

        @Override
        public boolean ifServerPinEmojiMatches(Server server, Emoji emoji) { return true; }

        @Override
        public boolean isPinnedMessageNotified(long id) {
            if (failReads) throw new IllegalStateException("Simulated database lookup failure");
            return notifications.containsKey(id);
        }

        @Override
        public OptionalInt getServerPinThreshold(Server server) { return OptionalInt.of(3); }

        @Override
        public Optional<ServerTextChannel> getServerPinChannel(Server server) { return Optional.of(channel); }

        @Override
        public void createPinNotification(long messageId, long notificationId) {
            saveAttempts.incrementAndGet();
            beforeSave.run();
            if (failWrites) throw new IllegalStateException("Simulated database save failure");
            assertNull(notifications.putIfAbsent(messageId, notificationId), "Duplicate pin record");
        }
    }
}
