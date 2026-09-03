package com.tirth.digest;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

public final class Store {

    // Static so a warm Lambda container reuses the client rather than rebuilding its HTTP stack.
    private static final DynamoDbClient DYNAMO = DynamoDbClient.create();

    private static final String SENT = "SENT";
    private static final Duration SENTINEL_LIFETIME = Duration.ofDays(30);

    private final String tableName;

    public Store(String tableName) {
        this.tableName = tableName;
    }

    public boolean alreadySentOn(LocalDate date) {
        GetItemRequest request = GetItemRequest.builder()
                .tableName(tableName)
                .key(keyFor(date))
                .build();

        return DYNAMO.getItem(request).hasItem();
    }

    public void recordSentOn(LocalDate date) {
        Map<String, AttributeValue> item = new HashMap<>(keyFor(date));
        item.put("ttl", AttributeValue.fromN(
                Long.toString(Instant.now().plus(SENTINEL_LIFETIME).getEpochSecond())));

        DYNAMO.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(item)
                .build());
    }

    public boolean hasSeen(String kind, String id) {
        return DYNAMO.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(seenKey(kind, id))
                .build()).hasItem();
    }

    public void markSeen(String kind, String id, Duration lifetime) {
        Map<String, AttributeValue> item = new HashMap<>(seenKey(kind, id));
        item.put("ttl", AttributeValue.fromN(
                Long.toString(Instant.now().plus(lifetime).getEpochSecond())));

        DYNAMO.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(item)
                .build());
    }

    /** Reads a single rolling value, e.g. the headlines shown over the last few days. */
    public String readNote(String name) {
        Map<String, AttributeValue> item = DYNAMO.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(noteKey(name))
                .build()).item();

        AttributeValue value = item == null ? null : item.get("value");
        return value == null ? "" : value.s();
    }

    public void writeNote(String name, String value, Duration lifetime) {
        Map<String, AttributeValue> item = new HashMap<>(noteKey(name));
        item.put("value", AttributeValue.fromS(value));
        item.put("ttl", AttributeValue.fromN(
                Long.toString(Instant.now().plus(lifetime).getEpochSecond())));

        DYNAMO.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(item)
                .build());
    }

    private static Map<String, AttributeValue> noteKey(String name) {
        return Map.of(
                "pk", AttributeValue.fromS("NOTE#" + name),
                "sk", AttributeValue.fromS("VALUE"));
    }

    private static Map<String, AttributeValue> seenKey(String kind, String id) {
        return Map.of(
                "pk", AttributeValue.fromS(kind + "#" + id),
                "sk", AttributeValue.fromS("SEEN"));
    }

    private static Map<String, AttributeValue> keyFor(LocalDate date) {
        return Map.of(
                "pk", AttributeValue.fromS("DIGEST#" + date),
                "sk", AttributeValue.fromS(SENT));
    }
}
