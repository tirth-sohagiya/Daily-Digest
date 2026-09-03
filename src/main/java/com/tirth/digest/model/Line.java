package com.tirth.digest.model;

public record Line(String text, String link) {

    public static Line of(String text) {
        return new Line(text, null);
    }

    public boolean hasLink() {
        return link != null && !link.isBlank();
    }
}
