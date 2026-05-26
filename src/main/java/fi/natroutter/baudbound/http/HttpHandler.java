package fi.natroutter.baudbound.http;

import fi.natroutter.baudbound.storage.DataStore;
import org.jsoup.Connection;
import org.jsoup.Jsoup;

import java.util.Map;

/**
 * Executes outbound HTTP requests for webhook actions using Jsoup as the HTTP client.
 */
public class HttpHandler {

    /**
     * Result of a webhook request attempt.
     *
     * @param success    {@code true} if the response status is 2xx
     * @param statusCode the HTTP response status code, or {@code -1} on connection failure
     * @param body       the response body, or {@code null} on failure
     * @param headers    response headers, or an empty map on failure
     * @param error      the exception message on failure, otherwise {@code null}
     */
    public record WebhookResult(boolean success, int statusCode, String body, Map<String, String> headers, String error) {}

    /**
     * Fires the given webhook synchronously and returns the result.
     * <p>
     * The request times out after 10 seconds. HTTP errors (4xx, 5xx) are returned as
     * unsuccessful results rather than thrown; only network-level failures produce an error
     * message in the result.
     *
     * @param webhook the fully resolved webhook definition (URLs and headers already substituted)
     * @return a {@link WebhookResult} describing the outcome
     */
    public static WebhookResult fireWebhook(DataStore.Actions.Webhook webhook) {
        return fireWebhook(webhook, null);
    }

    public static WebhookResult fireWebhook(DataStore.Actions.Webhook webhook, String deliveryId) {
        try {
            Connection.Method method = Connection.Method.valueOf(
                    webhook.getMethod() != null ? webhook.getMethod().toUpperCase() : "GET"
            );

            Connection connection = Jsoup.connect(webhook.getUrl())
                    .method(method)
                    .ignoreContentType(true)
                    .ignoreHttpErrors(true)
                    .timeout(10_000);

            if (webhook.getHeaders() != null) {
                for (DataStore.Actions.Webhook.Header header : webhook.getHeaders()) {
                    // Key must be non-blank (HTTP spec requires a valid field name);
                    // value may be blank (e.g. "X-Empty-Header: ").
                    if (header.getKey() != null && !header.getKey().isBlank()) {
                        connection.header(header.getKey(), header.getValue() != null ? header.getValue() : "");
                    }
                }
            }

            if (deliveryId != null && !deliveryId.isBlank()) {
                connection.header("X-BaudBound-Delivery-Id", deliveryId);
            }

            if (webhook.getBody() != null && !webhook.getBody().isBlank()) {
                connection.requestBody(webhook.getBody());
            }

            Connection.Response response = connection.execute();
            int status = response.statusCode();
            return new WebhookResult(status >= 200 && status < 300, status, response.body(), response.headers(), null);

        } catch (Exception e) {
            return new WebhookResult(false, -1, null, Map.of(), e.getMessage());
        }
    }
}
