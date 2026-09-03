package com.tirth.digest.model;

import java.util.List;

public record Section(String title, List<Line> lines) {

    public static Section of(String title, List<String> texts) {
        return new Section(title, texts.stream().map(Line::of).toList());
    }
}
