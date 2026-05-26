package fi.natroutter.baudbound.http;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import fi.natroutter.baudbound.storage.DataStore;
import fi.natroutter.baudbound.storage.StorageProvider;
import fi.natroutter.foxlib.logger.FoxLogger;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Persistent at-least-once delivery queue for durable webhook actions.
 * <p>
 * Items are written to disk before delivery is attempted. A queued item is removed only after
 * the configured acknowledgement succeeds, so receivers must use {@code X-BaudBound-Delivery-Id}
 * or the {@code {delivery.id}} body token for idempotency.
 */
public class WebhookDeliveryQueue {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long IDLE_SLEEP_MS = 500;

    private final FoxLogger logger;
    private final File queueFile;
    private final Object lock = new Object();
    private QueueState state = new QueueState();
    private volatile boolean running;
    private Thread worker;

    public WebhookDeliveryQueue(FoxLogger logger) {
        this.logger = logger;
        this.queueFile = new File(StorageProvider.getConfigDir(), "webhook-queue.json");
        load();
    }

    public String enqueue(DataStore.Actions.Webhook webhook, String eventName) {
        return enqueue(UUID.randomUUID().toString(), webhook, eventName);
    }

    public String enqueue(String deliveryId, DataStore.Actions.Webhook webhook, String eventName) {
        DeliveryItem item = new DeliveryItem();
        item.id = deliveryId;
        item.webhookName = webhook.getName();
        item.eventName = eventName;
        item.webhook = webhook.deepCopy();
        item.createdAt = System.currentTimeMillis();
        item.nextAttemptAt = item.createdAt;

        synchronized (lock) {
            state.items.add(item);
            saveLocked();
            lock.notifyAll();
        }

        logger.info("Queued durable webhook \"" + webhook.getName() + "\" delivery " + item.id);
        return item.id;
    }

    public void start() {
        if (running) return;
        running = true;
        worker = Thread.ofVirtual().name("webhook-delivery-queue").start(this::runLoop);
    }

    public void stop() {
        running = false;
        synchronized (lock) {
            lock.notifyAll();
        }
        if (worker != null) worker.interrupt();
    }

    public int pendingCount() {
        synchronized (lock) {
            return state.items.size();
        }
    }

    /**
     * Marks a queued delivery as acknowledged by an external channel such as WebSocket.
     * Returns {@code true} when a pending item was found and removed.
     *
     * @param deliveryId the {@code X-BaudBound-Delivery-Id} / {@code {delivery.id}} value
     * @param source     human-readable acknowledgement source for logging
     */
    public boolean acknowledge(String deliveryId, String source) {
        if (deliveryId == null || deliveryId.isBlank()) return false;
        synchronized (lock) {
            DeliveryItem current = find(deliveryId.trim());
            if (current == null) return false;
            state.items.remove(current);
            saveLocked();
            logger.info("Durable webhook \"" + current.webhookName + "\" delivery " + current.id
                    + " acknowledged by " + source);
            lock.notifyAll();
            return true;
        }
    }

    /** Clears every pending delivery after the downstream queue has been reset. */
    public int resetAll(String source) {
        synchronized (lock) {
            int count = state.items.size();
            state.items.clear();
            saveLocked();
            logger.warn("Durable webhook queue reset by " + source + " — removed " + count + " pending delivery item(s)");
            lock.notifyAll();
            return count;
        }
    }

    /** Removes a single pending delivery after the downstream queue has discarded it. */
    public boolean reset(String deliveryId, String source) {
        if (deliveryId == null || deliveryId.isBlank()) return false;
        synchronized (lock) {
            DeliveryItem current = find(deliveryId.trim());
            if (current == null) return false;
            state.items.remove(current);
            saveLocked();
            logger.warn("Durable webhook \"" + current.webhookName + "\" delivery " + current.id
                    + " reset by " + source);
            lock.notifyAll();
            return true;
        }
    }

    private void runLoop() {
        while (running) {
            DeliveryItem item = nextDueItem();
            if (item == null) {
                sleepUntilNextAttempt();
                continue;
            }
            deliver(item);
        }
    }

    private DeliveryItem nextDueItem() {
        long now = System.currentTimeMillis();
        synchronized (lock) {
            return state.items.stream()
                    .filter(item -> item.nextAttemptAt <= now)
                    .min(Comparator.comparingLong(item -> item.nextAttemptAt))
                    .orElse(null);
        }
    }

    private void sleepUntilNextAttempt() {
        long waitMs = IDLE_SLEEP_MS;
        long now = System.currentTimeMillis();
        synchronized (lock) {
            long next = state.items.stream().mapToLong(item -> item.nextAttemptAt).min().orElse(now + IDLE_SLEEP_MS);
            waitMs = Math.max(IDLE_SLEEP_MS, Math.min(5_000, next - now));
            try {
                lock.wait(waitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void deliver(DeliveryItem item) {
        HttpHandler.WebhookResult result = HttpHandler.fireWebhook(item.webhook, item.id);
        boolean acked = isAcknowledged(item.webhook, result);

        synchronized (lock) {
            DeliveryItem current = find(item.id);
            if (current == null) return;

            if (acked) {
                state.items.remove(current);
                saveLocked();
                logger.info("Durable webhook \"" + current.webhookName + "\" delivery " + current.id + " acknowledged with HTTP " + result.statusCode());
                return;
            }

            current.attempts++;
            current.lastAttemptAt = System.currentTimeMillis();
            current.lastStatusCode = result.statusCode();
            current.lastError = result.error() != null ? result.error() : "HTTP " + result.statusCode() + " without acknowledgement";

            int maxAttempts = current.webhook.getEffectiveMaxAttempts();
            if (maxAttempts > 0 && current.attempts >= maxAttempts) {
                current.nextAttemptAt = Long.MAX_VALUE;
                logger.error("Durable webhook \"" + current.webhookName + "\" delivery " + current.id + " paused after " + current.attempts + " attempts: " + current.lastError);
            } else {
                long delay = retryDelay(current.webhook, current.attempts);
                current.nextAttemptAt = System.currentTimeMillis() + delay;
                logger.error("Durable webhook \"" + current.webhookName + "\" delivery " + current.id + " failed; retrying in " + delay + " ms: " + current.lastError);
            }
            saveLocked();
        }
    }

    private DeliveryItem find(String id) {
        return state.items.stream().filter(item -> item.id.equals(id)).findFirst().orElse(null);
    }

    private static long retryDelay(DataStore.Actions.Webhook webhook, int attempts) {
        long initial = webhook.getEffectiveRetryInitialMs();
        long max = webhook.getEffectiveRetryMaxMs();
        long multiplier = 1L << Math.min(Math.max(0, attempts - 1), 10);
        return Math.min(max, initial * multiplier);
    }

    private static boolean isAcknowledged(DataStore.Actions.Webhook webhook, HttpHandler.WebhookResult result) {
        if (!result.success()) return false;

        String bodyNeedle = webhook.getAckBodyContains();
        if (bodyNeedle != null && !bodyNeedle.isBlank()) {
            String body = result.body() != null ? result.body() : "";
            if (!body.contains(bodyNeedle)) return false;
        }

        String headerName = webhook.getAckHeaderName();
        String headerValue = webhook.getAckHeaderValue();
        if (headerName != null && !headerName.isBlank()) {
            String actual = result.headers().entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(headerName))
                    .map(java.util.Map.Entry::getValue)
                    .findFirst()
                    .orElse("");
            if (headerValue != null && !headerValue.isBlank()) {
                return actual.equals(headerValue);
            }
            return !actual.isBlank();
        }

        return true;
    }

    private void load() {
        if (!queueFile.exists()) return;
        try {
            String json = Files.readString(queueFile.toPath());
            QueueState loaded = GSON.fromJson(json, QueueState.class);
            if (loaded != null && loaded.items != null) state = loaded;
            logger.info("Loaded " + state.items.size() + " durable webhook queue item(s)");
        } catch (Exception e) {
            logger.error("Failed to load webhook queue: " + e.getMessage());
        }
    }

    private void saveLocked() {
        try {
            File parent = queueFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                logger.error("Failed to create webhook queue directory: " + parent.getAbsolutePath());
                return;
            }
            Files.writeString(queueFile.toPath(), GSON.toJson(state));
        } catch (Exception e) {
            logger.error("Failed to save webhook queue: " + e.getMessage());
        }
    }

    private static class QueueState {
        List<DeliveryItem> items = new ArrayList<>();
    }

    private static class DeliveryItem {
        String id;
        String webhookName;
        String eventName;
        DataStore.Actions.Webhook webhook;
        long createdAt;
        long nextAttemptAt;
        long lastAttemptAt;
        int attempts;
        int lastStatusCode;
        String lastError;
    }
}
