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

    private static Map<String, AttributeValue> keyFor(LocalDate date) {
        return Map.of(
                "pk", AttributeValue.fromS("DIGEST#" + date),
                "sk", AttributeValue.fromS(SENT));
    }
}
